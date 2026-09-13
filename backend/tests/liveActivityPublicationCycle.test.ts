import { describe, expect, it, vi } from "vitest";
import { InMemoryLiveActivityPublicationCycleStore } from "../src/liveCommute/apple/inMemoryLiveActivityPublicationCycleStore.js";
import {
  liveActivityPublicationCycleSlotAt,
  type LiveActivityPublicationCycleOwner,
} from "../src/liveCommute/apple/publicationCycleModel.js";
import {
  runClaimedLiveActivityPublicationCycle,
  type RunClaimedLiveActivityPublicationCycleInput,
} from "../src/liveCommute/apple/publicationCycleCoordinator.js";
import { createLiveActivityCycleTrigger } from "../src/liveCommute/apple/publicationCycleTrigger.js";
import type { LiveActivityPublicationCycleStore } from "../src/liveCommute/apple/publicationCycleStore.js";
import type { LiveActivityPublicationCycleSummary } from "../src/liveCommute/apple/publicationWorker.js";

const SLOT_AT = new Date("2026-09-12T10:00:07.000Z");
const CADENCE_MILLISECONDS = 30_000;
const LEASE_MILLISECONDS = 90_000;
const CYCLE_IDS = Object.freeze([
  "66bc2834-5d2e-4ac0-8efe-b178a87db115",
  "801d2305-42a7-4d12-b64c-185c07f16d82",
  "d17a1d30-fad2-482f-a5b0-a9f3293409c6",
  "e9349756-2783-4603-98f5-b52420eb0052",
]);

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function noWorkSummary(
  cycleAuthorityStatus: LiveActivityPublicationCycleSummary["cycleAuthorityStatus"] =
    "CURRENT",
): LiveActivityPublicationCycleSummary {
  return Object.freeze({
    plannedAt: SLOT_AT.toISOString(),
    generatedAt: SLOT_AT.toISOString(),
    acquisitionCount: 0,
    publicationOutcomeCount: 0,
    readyPublicationGroupCount: 0,
    bindingCount: 0,
    sessionVersionWithoutBindingCount: 0,
    historyLookupFailed: false,
    cycleAuthorityStatus,
    decisionCounts: Object.freeze([]),
    dispatchOutcomeCounts: Object.freeze([]),
    bindings: Object.freeze([]),
  });
}

function oneNoPushSummary(
  overrides: Partial<LiveActivityPublicationCycleSummary> = {},
): LiveActivityPublicationCycleSummary {
  return Object.freeze({
    plannedAt: SLOT_AT.toISOString(),
    generatedAt: SLOT_AT.toISOString(),
    acquisitionCount: 1,
    publicationOutcomeCount: 1,
    readyPublicationGroupCount: 1,
    bindingCount: 1,
    sessionVersionWithoutBindingCount: 0,
    historyLookupFailed: false,
    cycleAuthorityStatus: "CURRENT",
    decisionCounts: Object.freeze([
      Object.freeze({ kind: "NO_PUSH_UNCHANGED", count: 1 }),
    ]),
    dispatchOutcomeCounts: Object.freeze([
      Object.freeze({ kind: "NOT_REQUESTED", count: 1 }),
    ]),
    bindings: Object.freeze([
      Object.freeze({
        bindingId: "must-not-escape-cycle-result",
        sessionRevision: 1,
        decision: "NO_PUSH_UNCHANGED",
        dispatch: null,
      }),
    ]),
    ...overrides,
  });
}

function cycleStore(): InMemoryLiveActivityPublicationCycleStore {
  let index = 0;
  return new InMemoryLiveActivityPublicationCycleStore(
    () => CYCLE_IDS[index++] as string,
  );
}

function baseInput(options: {
  readonly store: LiveActivityPublicationCycleStore;
  readonly now: () => Date;
  readonly runPublicationCycle: NonNullable<
    RunClaimedLiveActivityPublicationCycleInput["runPublicationCycle"]
  >;
  readonly leaseDurationMilliseconds?: number;
}): RunClaimedLiveActivityPublicationCycleInput {
  return {
    cycleStore: options.store,
    cadenceMilliseconds: CADENCE_MILLISECONDS,
    leaseDurationMilliseconds:
      options.leaseDurationMilliseconds ?? LEASE_MILLISECONDS,
    sessionStore: {} as never,
    now: options.now,
    transportClient: {} as never,
    journeyClient: {} as never,
    deliveryStore: {} as never,
    dispatchStore: {} as never,
    dispatcher: {} as never,
    clientStateProvider: {} as never,
    startAlertProvider: {} as never,
    priorityPolicy: {} as never,
    policyConfig: {} as never,
    dispatchConcurrency: 2,
    runPublicationCycle: options.runPublicationCycle,
  };
}

describe("global Live Activity publication-cycle coordination", () => {
  it("derives a canonical absolute 30-second slot and rejects invalid cadence", () => {
    expect(
      liveActivityPublicationCycleSlotAt(SLOT_AT, CADENCE_MILLISECONDS),
    ).toEqual({ startEpochSeconds: 1_789_207_200, cadenceSeconds: 30 });
    expect(() => liveActivityPublicationCycleSlotAt(SLOT_AT, 500)).toThrow(
      "at least one second",
    );
    expect(() => liveActivityPublicationCycleSlotAt(SLOT_AT, 30_500)).toThrow(
      "whole number of seconds",
    );
  });

  it("lets exactly one of sixteen simultaneous same-slot triggers run the worker", async () => {
    const store = cycleStore();
    const entered = deferred<void>();
    const release = deferred<void>();
    const worker = vi.fn(async () => {
      entered.resolve();
      await release.promise;
      return oneNoPushSummary();
    });
    const input = baseInput({
      store,
      now: () => new Date(SLOT_AT),
      runPublicationCycle: worker,
    });

    const runs = Array.from({ length: 16 }, () =>
      runClaimedLiveActivityPublicationCycle(input),
    );
    await entered.promise;
    expect(worker).toHaveBeenCalledOnce();
    release.resolve();
    const results = await Promise.all(runs);

    expect(
      results.filter(({ outcome }) => outcome === "CLAIMED_AND_COMPLETED"),
    ).toHaveLength(1);
    expect(
      results.filter(({ outcome }) => outcome === "ALREADY_RUNNING"),
    ).toHaveLength(15);
    expect(JSON.stringify(results)).not.toContain("must-not-escape-cycle-result");
  });

  it("returns already completed for a repeated finalized slot without another worker", async () => {
    const store = cycleStore();
    const worker = vi.fn(async () => oneNoPushSummary());
    const input = baseInput({
      store,
      now: () => new Date(SLOT_AT),
      runPublicationCycle: worker,
    });

    await expect(runClaimedLiveActivityPublicationCycle(input)).resolves.toMatchObject({
      outcome: "CLAIMED_AND_COMPLETED",
    });
    await expect(runClaimedLiveActivityPublicationCycle(input)).resolves.toEqual({
      outcome: "ALREADY_COMPLETED",
      slot: liveActivityPublicationCycleSlotAt(SLOT_AT, CADENCE_MILLISECONDS),
      terminalState: "COMPLETED",
    });
    expect(worker).toHaveBeenCalledOnce();
  });

  it("blocks an adjacent slot while the previous global cycle legitimately overruns", async () => {
    const store = cycleStore();
    const entered = deferred<void>();
    const release = deferred<void>();
    let now = new Date(SLOT_AT);
    const worker = vi.fn(async () => {
      entered.resolve();
      await release.promise;
      return oneNoPushSummary();
    });
    const input = baseInput({ store, now: () => new Date(now), runPublicationCycle: worker });

    const first = runClaimedLiveActivityPublicationCycle(input);
    await entered.promise;
    now = new Date(SLOT_AT.getTime() + CADENCE_MILLISECONDS);
    await expect(runClaimedLiveActivityPublicationCycle(input)).resolves.toMatchObject({
      outcome: "ALREADY_RUNNING",
      activeSlot: liveActivityPublicationCycleSlotAt(
        SLOT_AT,
        CADENCE_MILLISECONDS,
      ),
    });
    expect(worker).toHaveBeenCalledOnce();
    release.resolve();
    await expect(first).resolves.toMatchObject({
      outcome: "CLAIMED_AND_COMPLETED",
    });

    now = new Date(SLOT_AT.getTime() + 2 * CADENCE_MILLISECONDS);
    await expect(runClaimedLiveActivityPublicationCycle(input)).resolves.toMatchObject({
      outcome: "CLAIMED_AND_COMPLETED",
      slot: liveActivityPublicationCycleSlotAt(now, CADENCE_MILLISECONDS),
    });
    expect(worker).toHaveBeenCalledTimes(2);
  });

  it("jumps directly to the current slot after a five-minute gap", async () => {
    const store = cycleStore();
    let now = new Date(SLOT_AT);
    const worker = vi.fn(async () => noWorkSummary());
    const input = baseInput({ store, now: () => new Date(now), runPublicationCycle: worker });

    await runClaimedLiveActivityPublicationCycle(input);
    now = new Date(SLOT_AT.getTime() + 5 * 60_000);
    const current = await runClaimedLiveActivityPublicationCycle(input);

    expect(current).toMatchObject({
      outcome: "NO_ACTIVE_SESSIONS",
      slot: liveActivityPublicationCycleSlotAt(now, CADENCE_MILLISECONDS),
    });
    expect(worker).toHaveBeenCalledTimes(2);
    expect(store.listPublicationCyclesForTests()).toHaveLength(2);
  });

  it("fences an expired claimant at its next send boundary and blocks stale finalization", async () => {
    const store = cycleStore();
    const firstEntered = deferred<void>();
    const releaseFirst = deferred<void>();
    const staleApnsStart = vi.fn(async () => undefined);
    let now = new Date(SLOT_AT);
    let invocation = 0;
    const worker = vi.fn(async (workerInput) => {
      invocation += 1;
      if (invocation === 1) {
        firstEntered.resolve();
        await releaseFirst.promise;
        const guardedSend = await workerInput.cycleAuthority!.beginSendIfCurrent(
          staleApnsStart,
        );
        return noWorkSummary(
          guardedSend.status === "STOPPED" ? guardedSend.reason : "CURRENT",
        );
      }
      return noWorkSummary();
    });
    const input = baseInput({
      store,
      now: () => new Date(now),
      runPublicationCycle: worker,
      leaseDurationMilliseconds: 1_000,
    });

    const staleRun = runClaimedLiveActivityPublicationCycle(input);
    await firstEntered.promise;
    now = new Date(SLOT_AT.getTime() + 2_000);
    const recovery = await runClaimedLiveActivityPublicationCycle(input);
    expect(recovery).toMatchObject({
      outcome: "NO_ACTIVE_SESSIONS",
      fenceGeneration: 2,
    });
    releaseFirst.resolve();
    await expect(staleRun).resolves.toMatchObject({
      outcome: "LOST_LEASE",
      fenceGeneration: 1,
    });
    expect(staleApnsStart).not.toHaveBeenCalled();

    const records = store.listPublicationCyclesForTests();
    expect(records.map(({ state }) => state)).toEqual(["ABANDONED", "NO_WORK"]);
    expect(records.map(({ fenceGeneration }) => fenceGeneration)).toEqual([1, 2]);
  });

  it("fails closed before transit or APNs work when claim persistence fails", async () => {
    const worker = vi.fn(async () => oneNoPushSummary());
    const store: LiveActivityPublicationCycleStore = {
      claimPublicationCycle: async () => {
        throw new Error("postgresql://synthetic:secret@db.invalid/live");
      },
      renewPublicationCycle: async () => undefined,
      finalizePublicationCycle: async () => undefined,
    };

    const result = await runClaimedLiveActivityPublicationCycle(
      baseInput({ store, now: () => new Date(SLOT_AT), runPublicationCycle: worker }),
    );

    expect(result).toEqual({
      outcome: "COORDINATION_FAILED",
      slot: liveActivityPublicationCycleSlotAt(SLOT_AT, CADENCE_MILLISECONDS),
    });
    expect(worker).not.toHaveBeenCalled();
    expect(JSON.stringify(result)).not.toContain("secret");
  });

  it("fails closed before the worker when initial fence validation is unavailable", async () => {
    const owner: LiveActivityPublicationCycleOwner = {
      cycleId: CYCLE_IDS[0] as string,
      slot: liveActivityPublicationCycleSlotAt(SLOT_AT, CADENCE_MILLISECONDS),
      fenceGeneration: 1,
      claimedAt: SLOT_AT,
      startedAt: null,
      leaseExpiresAt: new Date(SLOT_AT.getTime() + LEASE_MILLISECONDS),
    };
    const worker = vi.fn(async () => oneNoPushSummary());
    const store: LiveActivityPublicationCycleStore = {
      claimPublicationCycle: async () => ({ status: "CLAIMED", owner }),
      renewPublicationCycle: async () => {
        throw new Error("synthetic persistence failure");
      },
      finalizePublicationCycle: async () => undefined,
    };

    const result = await runClaimedLiveActivityPublicationCycle(
      baseInput({ store, now: () => new Date(SLOT_AT), runPublicationCycle: worker }),
    );

    expect(result).toMatchObject({ outcome: "COORDINATION_FAILED" });
    expect(worker).not.toHaveBeenCalled();
  });

  it("marks exceptions, invalid summaries, and systemic history failure as terminal worker failures", async () => {
    const firstStore = cycleStore();
    const throwingWorker = vi.fn(async () => {
      throw new Error("synthetic.jwt.private-key");
    });
    const firstInput = baseInput({
      store: firstStore,
      now: () => new Date(SLOT_AT),
      runPublicationCycle: throwingWorker,
    });
    const failed = await runClaimedLiveActivityPublicationCycle(firstInput);
    expect(failed).toMatchObject({ outcome: "WORKER_FAILED" });
    expect(JSON.stringify(failed)).not.toContain("private-key");
    await expect(runClaimedLiveActivityPublicationCycle(firstInput)).resolves.toMatchObject({
      outcome: "ALREADY_COMPLETED",
      terminalState: "FAILED",
    });

    const malformedStore = cycleStore();
    const malformedWorker = vi.fn(async () =>
      oneNoPushSummary({ bindingCount: 2 }),
    );
    await expect(
      runClaimedLiveActivityPublicationCycle(
        baseInput({
          store: malformedStore,
          now: () => new Date(SLOT_AT),
          runPublicationCycle: malformedWorker,
        }),
      ),
    ).resolves.toMatchObject({ outcome: "WORKER_FAILED" });
    expect(malformedStore.listPublicationCyclesForTests()[0]).toMatchObject({
      state: "FAILED",
      failureCode: "WORKER_FAILED",
      summary: null,
    });

    const secondStore = cycleStore();
    const historyWorker = vi.fn(async () =>
      oneNoPushSummary({ historyLookupFailed: true }),
    );
    const historyFailed = await runClaimedLiveActivityPublicationCycle(
      baseInput({
        store: secondStore,
        now: () => new Date(SLOT_AT),
        runPublicationCycle: historyWorker,
      }),
    );
    expect(historyFailed).toMatchObject({ outcome: "WORKER_FAILED" });
    expect(secondStore.listPublicationCyclesForTests()[0]).toMatchObject({
      state: "FAILED",
      failureCode: "PUBLICATION_HISTORY_UNAVAILABLE",
    });
  });

  it("finalizes no eligible sessions as NO_WORK with zero result counters", async () => {
    const store = cycleStore();
    const worker = vi.fn(async () => noWorkSummary());
    const result = await runClaimedLiveActivityPublicationCycle(
      baseInput({ store, now: () => new Date(SLOT_AT), runPublicationCycle: worker }),
    );

    expect(result).toMatchObject({
      outcome: "NO_ACTIVE_SESSIONS",
      summary: {
        acquisitionCount: 0,
        bindingCount: 0,
        dispatchRequestedCount: 0,
        networkAttemptedCount: 0,
      },
    });
    expect(store.listPublicationCyclesForTests()[0]).toMatchObject({
      state: "NO_WORK",
      summary: { dispatchRequestedCount: 0, networkAttemptedCount: 0 },
    });
  });

  it("keeps the scheduler boundary one-shot and provider-agnostic", async () => {
    const runCurrentCycle = vi.fn(async () => ({
      outcome: "STALE_TRIGGER" as const,
      slot: liveActivityPublicationCycleSlotAt(SLOT_AT, CADENCE_MILLISECONDS),
    }));
    const trigger = createLiveActivityCycleTrigger(runCurrentCycle);

    await expect(trigger.trigger()).resolves.toMatchObject({
      outcome: "STALE_TRIGGER",
    });
    expect(runCurrentCycle).toHaveBeenCalledOnce();
  });
});
