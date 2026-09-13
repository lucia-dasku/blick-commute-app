import {
  send as sendQueueMessage,
  type RetryDirective,
  type SendOptions,
} from "@vercel/queue";
import type { RunClaimedLiveActivityPublicationCycleResult } from "./publicationCycleCoordinator.js";
import {
  createLiveActivityPublicationCycleSlot,
  liveActivityPublicationCycleCadenceMilliseconds,
  liveActivityPublicationCycleSlotAt,
  type LiveActivityPublicationCycleSlot,
} from "./publicationCycleModel.js";
import type { LiveActivityCycleTrigger } from "./publicationCycleTrigger.js";

export const LIVE_ACTIVITY_CYCLE_QUEUE_TOPIC =
  "live-activity-cycle-wakeup" as const;
export const LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS = 30_000 as const;
export const LIVE_ACTIVITY_CYCLE_QUEUE_RETENTION_SECONDS = 300 as const;
export const LIVE_ACTIVITY_CYCLE_QUEUE_RETRY_SECONDS = 10 as const;
export const LIVE_ACTIVITY_CYCLE_QUEUE_VISIBILITY_TIMEOUT_SECONDS = 300 as const;

const LIVE_ACTIVITY_CYCLE_WAKE_UP_SCHEMA_VERSION = 1 as const;
const LIVE_ACTIVITY_CYCLE_IDEMPOTENCY_PREFIX =
  "live-activity-cycle-slot-v1" as const;

export interface LiveActivityCycleWakeUp {
  readonly schemaVersion: typeof LIVE_ACTIVITY_CYCLE_WAKE_UP_SCHEMA_VERSION;
  readonly scheduledSlot: LiveActivityPublicationCycleSlot;
}

export interface LiveActivityCycleQueueSendResult {
  readonly messageId: string | null;
}

export type LiveActivityCycleQueueSend = (
  topic: string,
  payload: LiveActivityCycleWakeUp,
  options: SendOptions,
) => Promise<LiveActivityCycleQueueSendResult>;

export interface EnsureLiveActivityCycleScheduledInput {
  /** Defaults to the current epoch-aligned slot. */
  readonly notBeforeSlot?: LiveActivityPublicationCycleSlot;
  readonly now?: () => Date;
  readonly cadenceMilliseconds?: number;
  /** Test/runtime seam. The default sends through the Queue SDK only when called. */
  readonly sendMessage?: LiveActivityCycleQueueSend;
}

export interface EnsuredLiveActivityCycleSchedule {
  readonly slot: LiveActivityPublicationCycleSlot;
  readonly delaySeconds: number;
  readonly idempotencyKey: string;
  readonly messageId: string | null;
}

export class LiveActivityCycleQueuePermanentError extends Error {
  readonly retryable = false as const;

  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "LiveActivityCycleQueuePermanentError";
  }
}

export class LiveActivityCycleQueueTransientError extends Error {
  readonly retryable = true as const;

  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "LiveActivityCycleQueueTransientError";
  }
}

function validNow(now: () => Date): Date {
  let value: Date;
  try {
    value = now();
  } catch (cause) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue clock is unavailable",
      { cause },
    );
  }
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue clock must return a valid absolute Date",
    );
  }
  return new Date(value.getTime());
}

function configuredCadence(value: number): number {
  try {
    return liveActivityPublicationCycleCadenceMilliseconds(value);
  } catch (cause) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue cadence is invalid",
      { cause },
    );
  }
}

function configuredSlot(
  value: LiveActivityPublicationCycleSlot,
): LiveActivityPublicationCycleSlot {
  try {
    return createLiveActivityPublicationCycleSlot(value);
  } catch (cause) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue slot is invalid",
      { cause },
    );
  }
}

function targetSlot(input: {
  readonly now: Date;
  readonly cadenceMilliseconds: number;
  readonly notBeforeSlot?: LiveActivityPublicationCycleSlot;
}): LiveActivityPublicationCycleSlot {
  let current: LiveActivityPublicationCycleSlot;
  try {
    current = liveActivityPublicationCycleSlotAt(
      input.now,
      input.cadenceMilliseconds,
    );
  } catch (cause) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue current slot cannot be calculated",
      { cause },
    );
  }
  if (input.notBeforeSlot == null) return current;

  const notBefore = configuredSlot(input.notBeforeSlot);
  if (notBefore.cadenceSeconds !== current.cadenceSeconds) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue slot cadence does not match configured cadence",
    );
  }
  const nextStart = current.startEpochSeconds + current.cadenceSeconds;
  if (!Number.isSafeInteger(nextStart)) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue successor slot is outside the supported range",
    );
  }
  if (notBefore.startEpochSeconds > nextStart) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue cannot schedule historical catch-up or skip future slots",
    );
  }
  return notBefore.startEpochSeconds > current.startEpochSeconds
    ? notBefore
    : current;
}

export function liveActivityCycleQueueIdempotencyKey(
  slotInput: LiveActivityPublicationCycleSlot,
): string {
  const slot = configuredSlot(slotInput);
  return `${LIVE_ACTIVITY_CYCLE_IDEMPOTENCY_PREFIX}:${slot.cadenceSeconds}:${slot.startEpochSeconds}`;
}

function delayUntilSlotSeconds(
  now: Date,
  slot: LiveActivityPublicationCycleSlot,
): number {
  const slotMilliseconds = slot.startEpochSeconds * 1_000;
  if (!Number.isSafeInteger(slotMilliseconds)) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue slot is outside the supported date range",
    );
  }
  const delaySeconds = Math.ceil(
    Math.max(0, slotMilliseconds - now.getTime()) / 1_000,
  );
  if (delaySeconds > LIVE_ACTIVITY_CYCLE_QUEUE_RETENTION_SECONDS) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue delay exceeds bounded message retention",
    );
  }
  return delaySeconds;
}

/**
 * Ensures one wake-up for the current slot, or for a supplied immediate successor.
 * Queue idempotency deduplicates repeated calls for the same logical slot.
 */
export async function ensureLiveActivityCycleScheduled(
  input: EnsureLiveActivityCycleScheduledInput = {},
): Promise<EnsuredLiveActivityCycleSchedule> {
  const now = validNow(input.now ?? (() => new Date()));
  const cadenceMilliseconds = configuredCadence(
    input.cadenceMilliseconds ?? LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
  );
  const slot = targetSlot({
    now,
    cadenceMilliseconds,
    notBeforeSlot: input.notBeforeSlot,
  });
  const delaySeconds = delayUntilSlotSeconds(now, slot);
  const idempotencyKey = liveActivityCycleQueueIdempotencyKey(slot);
  const wakeUp: LiveActivityCycleWakeUp = Object.freeze({
    schemaVersion: LIVE_ACTIVITY_CYCLE_WAKE_UP_SCHEMA_VERSION,
    scheduledSlot: slot,
  });
  const result = await (input.sendMessage ?? sendQueueMessage)(
    LIVE_ACTIVITY_CYCLE_QUEUE_TOPIC,
    wakeUp,
    {
      delaySeconds,
      idempotencyKey,
      retentionSeconds: LIVE_ACTIVITY_CYCLE_QUEUE_RETENTION_SECONDS,
    },
  );
  return Object.freeze({
    slot,
    delaySeconds,
    idempotencyKey,
    messageId: result.messageId,
  });
}

function parseWakeUp(
  value: unknown,
  cadenceMilliseconds: number,
): LiveActivityCycleWakeUp {
  if (value == null || typeof value !== "object") {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue wake-up must be an object",
    );
  }
  const candidate = value as Partial<LiveActivityCycleWakeUp>;
  if (candidate.schemaVersion !== LIVE_ACTIVITY_CYCLE_WAKE_UP_SCHEMA_VERSION) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue wake-up schema version is unsupported",
    );
  }
  if (candidate.scheduledSlot == null) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue wake-up slot is missing",
    );
  }
  const scheduledSlot = configuredSlot(candidate.scheduledSlot);
  if (scheduledSlot.cadenceSeconds !== cadenceMilliseconds / 1_000) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue wake-up cadence does not match configured cadence",
    );
  }
  return Object.freeze({
    schemaVersion: LIVE_ACTIVITY_CYCLE_WAKE_UP_SCHEMA_VERSION,
    scheduledSlot,
  });
}

function successorSlot(
  slotInput: LiveActivityPublicationCycleSlot,
  cadenceMilliseconds: number,
): LiveActivityPublicationCycleSlot {
  const slot = configuredSlot(slotInput);
  if (slot.cadenceSeconds !== cadenceMilliseconds / 1_000) {
    throw new LiveActivityCycleQueuePermanentError(
      "publication result cadence does not match queue cadence",
    );
  }
  const startEpochSeconds = slot.startEpochSeconds + slot.cadenceSeconds;
  if (!Number.isSafeInteger(startEpochSeconds)) {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity queue successor slot is outside the supported range",
    );
  }
  return configuredSlot({
    startEpochSeconds,
    cadenceSeconds: slot.cadenceSeconds,
  });
}

async function scheduleSuccessor(input: {
  readonly result: RunClaimedLiveActivityPublicationCycleResult;
  readonly now: () => Date;
  readonly cadenceMilliseconds: number;
  readonly sendMessage?: LiveActivityCycleQueueSend;
}): Promise<void> {
  try {
    await ensureLiveActivityCycleScheduled({
      notBeforeSlot: successorSlot(input.result.slot, input.cadenceMilliseconds),
      now: input.now,
      cadenceMilliseconds: input.cadenceMilliseconds,
      sendMessage: input.sendMessage,
    });
  } catch (cause) {
    if (cause instanceof LiveActivityCycleQueuePermanentError) throw cause;
    throw new LiveActivityCycleQueueTransientError(
      "live activity queue successor could not be enqueued",
      { cause },
    );
  }
}

export interface LiveActivityCycleQueueConsumerInput {
  readonly trigger: LiveActivityCycleTrigger;
  readonly now?: () => Date;
  readonly cadenceMilliseconds?: number;
  readonly sendMessage?: LiveActivityCycleQueueSend;
}

/**
 * Creates a single-message consumer. The message's slot is diagnostic only: the trigger
 * always evaluates its own current slot, so delayed delivery never replays old work.
 */
export function createLiveActivityCycleQueueConsumer(
  input: LiveActivityCycleQueueConsumerInput,
): (message: unknown) => Promise<void> {
  if (input.trigger == null || typeof input.trigger.trigger !== "function") {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity cycle trigger is not configured",
    );
  }
  const now = input.now ?? (() => new Date());
  const cadenceMilliseconds = configuredCadence(
    input.cadenceMilliseconds ?? LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
  );

  return async (message: unknown): Promise<void> => {
    parseWakeUp(message, cadenceMilliseconds);

    let result: RunClaimedLiveActivityPublicationCycleResult;
    try {
      result = await input.trigger.trigger();
    } catch (cause) {
      if (cause instanceof LiveActivityCycleQueuePermanentError) throw cause;
      throw new LiveActivityCycleQueueTransientError(
        "live activity publication cycle trigger failed",
        { cause },
      );
    }

    switch (result.outcome) {
      case "CLAIMED_AND_COMPLETED":
        await scheduleSuccessor({
          result,
          now,
          cadenceMilliseconds,
          sendMessage: input.sendMessage,
        });
        return;
      case "ALREADY_COMPLETED":
        if (result.terminalState === "COMPLETED") {
          await scheduleSuccessor({
            result,
            now,
            cadenceMilliseconds,
            sendMessage: input.sendMessage,
          });
          return;
        }
        if (result.terminalState === "NO_WORK") return;
        throw new LiveActivityCycleQueueTransientError(
          "live activity publication cycle is durably failed",
        );
      case "NO_ACTIVE_SESSIONS":
      case "ALREADY_RUNNING":
        return;
      case "LOST_LEASE":
      case "WORKER_FAILED":
      case "COORDINATION_FAILED":
        throw new LiveActivityCycleQueueTransientError(
          `live activity publication cycle requires retry: ${result.outcome}`,
        );
      case "STALE_TRIGGER":
      case "CADENCE_CONFLICT":
        throw new LiveActivityCycleQueuePermanentError(
          `live activity publication cycle rejected scheduler configuration: ${result.outcome}`,
        );
    }
  };
}

export function liveActivityCycleQueueRetry(
  error: unknown,
): RetryDirective {
  return error instanceof LiveActivityCycleQueuePermanentError
    ? { acknowledge: true }
    : { afterSeconds: LIVE_ACTIVITY_CYCLE_QUEUE_RETRY_SECONDS };
}
