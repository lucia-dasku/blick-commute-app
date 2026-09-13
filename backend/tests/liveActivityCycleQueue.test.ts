import { describe, expect, it, vi } from "vitest";
import {
  LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
  LIVE_ACTIVITY_CYCLE_QUEUE_RETENTION_SECONDS,
  LIVE_ACTIVITY_CYCLE_QUEUE_TOPIC,
  LiveActivityCycleQueuePermanentError,
  LiveActivityCycleQueueTransientError,
  createLiveActivityCycleQueueConsumer,
  ensureLiveActivityCycleScheduled,
  liveActivityCycleQueueIdempotencyKey,
  liveActivityCycleQueueRetry,
  type LiveActivityCycleQueueSend,
  type LiveActivityCycleWakeUp,
} from "../src/liveCommute/apple/publicationCycleQueue.js";
import type { RunClaimedLiveActivityPublicationCycleResult } from "../src/liveCommute/apple/publicationCycleCoordinator.js";
import {
  emptyLiveActivityPublicationCycleSafeSummary,
  liveActivityPublicationCycleSlotAt,
  type LiveActivityPublicationCycleSlot,
} from "../src/liveCommute/apple/publicationCycleModel.js";
import type { LiveActivityCycleTrigger } from "../src/liveCommute/apple/publicationCycleTrigger.js";

const CYCLE_ID = "11111111-1111-4111-8111-111111111111";

function wakeUp(slot: LiveActivityPublicationCycleSlot): LiveActivityCycleWakeUp {
  return Object.freeze({ schemaVersion: 1, scheduledSlot: slot });
}

function claimedCompleted(
  slot: LiveActivityPublicationCycleSlot,
): RunClaimedLiveActivityPublicationCycleResult {
  return Object.freeze({
    outcome: "CLAIMED_AND_COMPLETED",
    slot,
    cycleId: CYCLE_ID,
    fenceGeneration: 1,
    summary: emptyLiveActivityPublicationCycleSafeSummary(),
  });
}

function noActiveSessions(
  slot: LiveActivityPublicationCycleSlot,
): RunClaimedLiveActivityPublicationCycleResult {
  return Object.freeze({
    outcome: "NO_ACTIVE_SESSIONS",
    slot,
    cycleId: CYCLE_ID,
    fenceGeneration: 1,
    summary: emptyLiveActivityPublicationCycleSafeSummary(),
  });
}

function triggerReturning(
  ...results: RunClaimedLiveActivityPublicationCycleResult[]
): LiveActivityCycleTrigger {
  let index = 0;
  return Object.freeze({
    trigger: vi.fn(async () => {
      const result = results[index] ?? results.at(-1);
      index += 1;
      if (result == null) throw new Error("test trigger has no result");
      return result;
    }),
  });
}

function capturingSender() {
  const calls: Array<{
    topic: string;
    payload: LiveActivityCycleWakeUp;
    options: Parameters<LiveActivityCycleQueueSend>[2];
  }> = [];
  const sendMessage: LiveActivityCycleQueueSend = vi.fn(
    async (topic, payload, options) => {
      calls.push({ topic, payload, options });
      return { messageId: `message-${calls.length}` };
    },
  );
  return { calls, sendMessage };
}

describe("live activity publication-cycle queue scheduling", () => {
  it("calculates an epoch-aligned 30-second successor and delayed delivery", async () => {
    const at = new Date("2026-01-01T00:00:00.250Z");
    const current = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const next = Object.freeze({
      startEpochSeconds: current.startEpochSeconds + current.cadenceSeconds,
      cadenceSeconds: current.cadenceSeconds,
    });
    const sender = capturingSender();

    const scheduled = await ensureLiveActivityCycleScheduled({
      notBeforeSlot: next,
      now: () => at,
      sendMessage: sender.sendMessage,
    });

    expect(scheduled.slot).toEqual(next);
    expect(scheduled.delaySeconds).toBe(30);
    expect(sender.calls).toEqual([
      {
        topic: LIVE_ACTIVITY_CYCLE_QUEUE_TOPIC,
        payload: wakeUp(next),
        options: {
          delaySeconds: 30,
          idempotencyKey: liveActivityCycleQueueIdempotencyKey(next),
          retentionSeconds: LIVE_ACTIVITY_CYCLE_QUEUE_RETENTION_SECONDS,
        },
      },
    ]);
  });

  it("uses one deterministic idempotency key for repeated scheduling of a slot", async () => {
    const at = new Date("2026-01-01T00:00:14.999Z");
    const sender = capturingSender();

    const first = await ensureLiveActivityCycleScheduled({
      now: () => at,
      sendMessage: sender.sendMessage,
    });
    const second = await ensureLiveActivityCycleScheduled({
      now: () => at,
      sendMessage: sender.sendMessage,
    });

    expect(first.slot).toEqual(second.slot);
    expect(first.delaySeconds).toBe(0);
    expect(first.idempotencyKey).toBe(second.idempotencyKey);
    expect(sender.calls[0]?.options.idempotencyKey).toBe(
      sender.calls[1]?.options.idempotencyKey,
    );
  });

  it("makes duplicate wake-ups harmless through cycle and successor idempotency", async () => {
    const at = new Date("2026-01-01T00:00:00.500Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const trigger = triggerReturning(claimedCompleted(slot), {
      outcome: "ALREADY_COMPLETED",
      slot,
      terminalState: "COMPLETED",
    });
    const sender = capturingSender();
    const consume = createLiveActivityCycleQueueConsumer({
      trigger,
      now: () => at,
      sendMessage: sender.sendMessage,
    });

    await consume(wakeUp(slot));
    await consume(wakeUp(slot));

    expect(trigger.trigger).toHaveBeenCalledTimes(2);
    expect(sender.calls).toHaveLength(2);
    expect(sender.calls[0]?.options.idempotencyKey).toBe(
      sender.calls[1]?.options.idempotencyKey,
    );
  });

  it("ignores a late message's historical slot and continues from the trigger's current cycle", async () => {
    const deliveredAt = new Date("2026-01-01T00:05:00.100Z");
    const currentSlot = liveActivityPublicationCycleSlotAt(
      deliveredAt,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const historicalSlot = Object.freeze({
      startEpochSeconds: currentSlot.startEpochSeconds - 300,
      cadenceSeconds: currentSlot.cadenceSeconds,
    });
    const sender = capturingSender();
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning(claimedCompleted(currentSlot)),
      now: () => deliveredAt,
      sendMessage: sender.sendMessage,
    });

    await consume(wakeUp(historicalSlot));

    expect(sender.calls[0]?.payload.scheduledSlot.startEpochSeconds).toBe(
      currentSlot.startEpochSeconds + currentSlot.cadenceSeconds,
    );
    expect(sender.calls[0]?.options.delaySeconds).toBe(30);
  });

  it("skips missed slots when a completed worker crosses multiple cadence boundaries", async () => {
    const triggeredAt = new Date("2026-01-01T00:00:00.000Z");
    const completedAt = new Date("2026-01-01T00:05:00.100Z");
    const originalSlot = liveActivityPublicationCycleSlotAt(
      triggeredAt,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const currentSlot = liveActivityPublicationCycleSlotAt(
      completedAt,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const sender = capturingSender();
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning(claimedCompleted(originalSlot)),
      now: () => completedAt,
      sendMessage: sender.sendMessage,
    });

    await consume(wakeUp(originalSlot));

    expect(sender.calls[0]?.payload.scheduledSlot).toEqual(currentSlot);
    expect(sender.calls[0]?.options.delaySeconds).toBe(0);
  });

  it("schedules exactly one successor for a completed active cycle", async () => {
    const at = new Date("2026-01-01T00:00:02.000Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const sender = capturingSender();
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning(claimedCompleted(slot)),
      now: () => at,
      sendMessage: sender.sendMessage,
    });

    await consume(wakeUp(slot));

    expect(sender.calls).toHaveLength(1);
    expect(sender.calls[0]?.payload.scheduledSlot.startEpochSeconds).toBe(
      slot.startEpochSeconds + slot.cadenceSeconds,
    );
  });

  it.each([
    ["new no-work result", (slot: LiveActivityPublicationCycleSlot) => noActiveSessions(slot)],
    [
      "existing no-work result",
      (slot: LiveActivityPublicationCycleSlot) =>
        ({
          outcome: "ALREADY_COMPLETED",
          slot,
          terminalState: "NO_WORK",
        }) as const,
    ],
  ])("stops the wake-up chain for %s", async (_name, resultFor) => {
    const at = new Date("2026-01-01T00:00:00.000Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const sender = capturingSender();
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning(resultFor(slot)),
      now: () => at,
      sendMessage: sender.sendMessage,
    });

    await consume(wakeUp(slot));

    expect(sender.calls).toHaveLength(0);
  });

  it("fails the handler when the required successor cannot be enqueued", async () => {
    const at = new Date("2026-01-01T00:00:00.000Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning(claimedCompleted(slot)),
      now: () => at,
      sendMessage: vi.fn(async () => {
        throw new Error("synthetic queue outage");
      }),
    });

    await expect(consume(wakeUp(slot))).rejects.toBeInstanceOf(
      LiveActivityCycleQueueTransientError,
    );
  });

  it("treats worker and coordinator failures as retryable", async () => {
    const at = new Date("2026-01-01T00:00:00.000Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const cases: RunClaimedLiveActivityPublicationCycleResult[] = [
      {
        outcome: "WORKER_FAILED",
        slot,
        cycleId: CYCLE_ID,
        fenceGeneration: 1,
      },
      { outcome: "COORDINATION_FAILED", slot },
    ];

    for (const result of cases) {
      const consume = createLiveActivityCycleQueueConsumer({
        trigger: triggerReturning(result),
      });
      await expect(consume(wakeUp(slot))).rejects.toBeInstanceOf(
        LiveActivityCycleQueueTransientError,
      );
    }
  });

  it("acknowledges an already-running duplicate without scheduling competing work", async () => {
    const at = new Date("2026-01-01T00:00:00.000Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const sender = capturingSender();
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning({
        outcome: "ALREADY_RUNNING",
        slot,
        activeSlot: slot,
      }),
      sendMessage: sender.sendMessage,
    });

    await consume(wakeUp(slot));

    expect(sender.calls).toHaveLength(0);
  });

  it("acknowledges permanent cadence/configuration failures instead of retrying forever", async () => {
    const at = new Date("2026-01-01T00:00:00.000Z");
    const slot = liveActivityPublicationCycleSlotAt(
      at,
      LIVE_ACTIVITY_CYCLE_CADENCE_MILLISECONDS,
    );
    const consume = createLiveActivityCycleQueueConsumer({
      trigger: triggerReturning({ outcome: "CADENCE_CONFLICT", slot }),
    });

    let error: unknown;
    try {
      await consume(wakeUp(slot));
    } catch (caught) {
      error = caught;
    }

    expect(error).toBeInstanceOf(LiveActivityCycleQueuePermanentError);
    expect(liveActivityCycleQueueRetry(error)).toEqual({ acknowledge: true });
    expect(liveActivityCycleQueueRetry(new Error("transient"))).toEqual({
      afterSeconds: 10,
    });
  });

  it("rejects malformed wake-ups permanently before invoking the cycle trigger", async () => {
    const trigger = triggerReturning();
    const consume = createLiveActivityCycleQueueConsumer({ trigger });

    let error: unknown;
    try {
      await consume({ schemaVersion: 99 });
    } catch (caught) {
      error = caught;
    }

    expect(error).toBeInstanceOf(LiveActivityCycleQueuePermanentError);
    expect(liveActivityCycleQueueRetry(error)).toEqual({ acknowledge: true });
    expect(trigger.trigger).not.toHaveBeenCalled();
  });

  it("does not create queue or transit network activity when modules are imported", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    vi.resetModules();

    const queueModule = await import(
      "../src/liveCommute/apple/publicationCycleQueue.js"
    );
    const apiModule = await import("../api/live-activity-cycle.js");

    expect(queueModule.ensureLiveActivityCycleScheduled).toBeTypeOf("function");
    expect(apiModule.default).toBeTypeOf("function");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
