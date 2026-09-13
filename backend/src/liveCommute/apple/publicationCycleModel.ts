export const LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE = "GLOBAL" as const;

export type LiveActivityPublicationCycleState =
  | "CLAIMED"
  | "RUNNING"
  | "COMPLETED"
  | "NO_WORK"
  | "FAILED"
  | "ABANDONED";

export type LiveActivityPublicationCycleFailureCode =
  | "WORKER_FAILED"
  | "PUBLICATION_HISTORY_UNAVAILABLE"
  | "LEASE_EXPIRED";

export interface LiveActivityPublicationCycleSlot {
  readonly startEpochSeconds: number;
  readonly cadenceSeconds: number;
}

export interface LiveActivityPublicationCycleOwner {
  readonly cycleId: string;
  readonly slot: LiveActivityPublicationCycleSlot;
  readonly fenceGeneration: number;
  readonly claimedAt: Date;
  readonly startedAt: Date | null;
  readonly leaseExpiresAt: Date;
}

export interface LiveActivityPublicationCycleSafeSummary {
  readonly acquisitionCount: number;
  readonly publicationOutcomeCount: number;
  readonly readyPublicationGroupCount: number;
  readonly bindingCount: number;
  readonly sendDecisionCount: number;
  readonly noPushDecisionCount: number;
  readonly deferralDecisionCount: number;
  readonly dispatchRequestedCount: number;
  readonly networkAttemptedCount: number;
  readonly dispatchRecordedCount: number;
  readonly dispatchNotReservedCount: number;
  readonly dispatchNotSentCount: number;
  readonly dispatchResultNotRecordedCount: number;
  readonly dispatchCallFailedCount: number;
}

export interface LiveActivityPublicationCycleRecord
  extends LiveActivityPublicationCycleOwner {
  readonly state: LiveActivityPublicationCycleState;
  readonly finalizedAt: Date | null;
  readonly updatedAt: Date;
  readonly summary: LiveActivityPublicationCycleSafeSummary | null;
  readonly failureCode: LiveActivityPublicationCycleFailureCode | null;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function validInstant(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function nonnegativeSafeInteger(value: number, field: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${field} must be a nonnegative safe integer`);
  }
  return value;
}

export function liveActivityPublicationCycleCadenceMilliseconds(
  value: number,
): number {
  if (
    !Number.isSafeInteger(value) ||
    value < 1_000 ||
    value % 1_000 !== 0
  ) {
    throw new RangeError(
      "cycle cadence must be a whole number of seconds and at least one second",
    );
  }
  return value;
}

export function liveActivityPublicationCycleLeaseDurationMilliseconds(
  value: number,
): number {
  if (!Number.isSafeInteger(value) || value < 1_000) {
    throw new RangeError("cycle lease duration must be at least one second");
  }
  return value;
}

export function createLiveActivityPublicationCycleSlot(
  input: LiveActivityPublicationCycleSlot,
): LiveActivityPublicationCycleSlot {
  if (input == null || typeof input !== "object") {
    throw new TypeError("publication cycle slot must be an object");
  }
  const startEpochSeconds = nonnegativeSafeInteger(
    input.startEpochSeconds,
    "slot.startEpochSeconds",
  );
  const cadenceSeconds = nonnegativeSafeInteger(
    input.cadenceSeconds,
    "slot.cadenceSeconds",
  );
  if (cadenceSeconds === 0) {
    throw new RangeError("slot.cadenceSeconds must be positive");
  }
  if (startEpochSeconds % cadenceSeconds !== 0) {
    throw new RangeError("publication cycle slot must be cadence-aligned");
  }
  return Object.freeze({ startEpochSeconds, cadenceSeconds });
}

export function liveActivityPublicationCycleSlotAt(
  atInput: Date,
  cadenceMillisecondsInput: number,
): LiveActivityPublicationCycleSlot {
  const at = validInstant(atInput, "at");
  const cadenceMilliseconds = liveActivityPublicationCycleCadenceMilliseconds(
    cadenceMillisecondsInput,
  );
  if (at.getTime() < 0) {
    throw new RangeError("publication cycle slots require a nonnegative UNIX instant");
  }
  const cadenceSeconds = cadenceMilliseconds / 1_000;
  const epochSeconds = Math.floor(at.getTime() / 1_000);
  return createLiveActivityPublicationCycleSlot({
    startEpochSeconds:
      Math.floor(epochSeconds / cadenceSeconds) * cadenceSeconds,
    cadenceSeconds,
  });
}

export function isLiveActivityPublicationCycleSlotCurrent(
  slotInput: LiveActivityPublicationCycleSlot,
  atInput: Date,
): boolean {
  const slot = createLiveActivityPublicationCycleSlot(slotInput);
  const at = validInstant(atInput, "at");
  const atMilliseconds = at.getTime();
  const startMilliseconds = slot.startEpochSeconds * 1_000;
  return (
    atMilliseconds >= startMilliseconds &&
    atMilliseconds < startMilliseconds + slot.cadenceSeconds * 1_000
  );
}

export function normalizedLiveActivityPublicationCycleUuid(
  value: string,
): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new RangeError("publication cycle ID is invalid");
  }
  return value.toLowerCase();
}

export function positiveLiveActivityPublicationCycleFence(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError("publication cycle fence must be a positive safe integer");
  }
  return value;
}

export function createLiveActivityPublicationCycleSafeSummary(
  input: LiveActivityPublicationCycleSafeSummary,
): LiveActivityPublicationCycleSafeSummary {
  if (input == null || typeof input !== "object") {
    throw new TypeError("publication cycle summary must be an object");
  }
  const summary = Object.freeze({
    acquisitionCount: nonnegativeSafeInteger(
      input.acquisitionCount,
      "summary.acquisitionCount",
    ),
    publicationOutcomeCount: nonnegativeSafeInteger(
      input.publicationOutcomeCount,
      "summary.publicationOutcomeCount",
    ),
    readyPublicationGroupCount: nonnegativeSafeInteger(
      input.readyPublicationGroupCount,
      "summary.readyPublicationGroupCount",
    ),
    bindingCount: nonnegativeSafeInteger(input.bindingCount, "summary.bindingCount"),
    sendDecisionCount: nonnegativeSafeInteger(
      input.sendDecisionCount,
      "summary.sendDecisionCount",
    ),
    noPushDecisionCount: nonnegativeSafeInteger(
      input.noPushDecisionCount,
      "summary.noPushDecisionCount",
    ),
    deferralDecisionCount: nonnegativeSafeInteger(
      input.deferralDecisionCount,
      "summary.deferralDecisionCount",
    ),
    dispatchRequestedCount: nonnegativeSafeInteger(
      input.dispatchRequestedCount,
      "summary.dispatchRequestedCount",
    ),
    networkAttemptedCount: nonnegativeSafeInteger(
      input.networkAttemptedCount,
      "summary.networkAttemptedCount",
    ),
    dispatchRecordedCount: nonnegativeSafeInteger(
      input.dispatchRecordedCount,
      "summary.dispatchRecordedCount",
    ),
    dispatchNotReservedCount: nonnegativeSafeInteger(
      input.dispatchNotReservedCount,
      "summary.dispatchNotReservedCount",
    ),
    dispatchNotSentCount: nonnegativeSafeInteger(
      input.dispatchNotSentCount,
      "summary.dispatchNotSentCount",
    ),
    dispatchResultNotRecordedCount: nonnegativeSafeInteger(
      input.dispatchResultNotRecordedCount,
      "summary.dispatchResultNotRecordedCount",
    ),
    dispatchCallFailedCount: nonnegativeSafeInteger(
      input.dispatchCallFailedCount,
      "summary.dispatchCallFailedCount",
    ),
  });
  if (
    summary.sendDecisionCount +
      summary.noPushDecisionCount +
      summary.deferralDecisionCount !==
    summary.bindingCount
  ) {
    throw new RangeError("publication decision counts must equal binding count");
  }
  if (
    summary.dispatchRecordedCount +
      summary.dispatchNotReservedCount +
      summary.dispatchNotSentCount +
      summary.dispatchResultNotRecordedCount +
      summary.dispatchCallFailedCount !==
    summary.dispatchRequestedCount
  ) {
    throw new RangeError("dispatch outcome counts must equal requested count");
  }
  if (summary.networkAttemptedCount > summary.dispatchRequestedCount) {
    throw new RangeError("network attempts cannot exceed requested dispatches");
  }
  return summary;
}

export function emptyLiveActivityPublicationCycleSafeSummary(): LiveActivityPublicationCycleSafeSummary {
  return createLiveActivityPublicationCycleSafeSummary({
    acquisitionCount: 0,
    publicationOutcomeCount: 0,
    readyPublicationGroupCount: 0,
    bindingCount: 0,
    sendDecisionCount: 0,
    noPushDecisionCount: 0,
    deferralDecisionCount: 0,
    dispatchRequestedCount: 0,
    networkAttemptedCount: 0,
    dispatchRecordedCount: 0,
    dispatchNotReservedCount: 0,
    dispatchNotSentCount: 0,
    dispatchResultNotRecordedCount: 0,
    dispatchCallFailedCount: 0,
  });
}

export function cloneLiveActivityPublicationCycleOwner(
  owner: LiveActivityPublicationCycleOwner,
): LiveActivityPublicationCycleOwner {
  return Object.freeze({
    cycleId: normalizedLiveActivityPublicationCycleUuid(owner.cycleId),
    slot: createLiveActivityPublicationCycleSlot(owner.slot),
    fenceGeneration: positiveLiveActivityPublicationCycleFence(
      owner.fenceGeneration,
    ),
    claimedAt: validInstant(owner.claimedAt, "owner.claimedAt"),
    startedAt:
      owner.startedAt == null
        ? null
        : validInstant(owner.startedAt, "owner.startedAt"),
    leaseExpiresAt: validInstant(
      owner.leaseExpiresAt,
      "owner.leaseExpiresAt",
    ),
  });
}
