import {
  createLiveActivityPublicationCycleSafeSummary,
  liveActivityPublicationCycleCadenceMilliseconds,
  liveActivityPublicationCycleLeaseDurationMilliseconds,
  liveActivityPublicationCycleSlotAt,
  type LiveActivityPublicationCycleOwner,
  type LiveActivityPublicationCycleSafeSummary,
  type LiveActivityPublicationCycleSlot,
} from "./publicationCycleModel.js";
import type {
  BeginLiveActivityPublicationCycleAuthorizedSend,
  LiveActivityPublicationCycleAuthority,
  LiveActivityPublicationCycleAuthorityStatus,
  LiveActivityPublicationCycleAuthorityStopReason,
} from "./publicationCycleAuthority.js";
import type { LiveActivityPublicationCycleStore } from "./publicationCycleStore.js";
import {
  runLiveActivityPublicationCycle,
  type LiveActivityPublicationCycleSummary,
  type RunLiveActivityPublicationCycleInput,
} from "./publicationWorker.js";

type PublicationCycleRunner = (
  input: RunLiveActivityPublicationCycleInput,
) => Promise<LiveActivityPublicationCycleSummary>;

export interface RunClaimedLiveActivityPublicationCycleInput
  extends Omit<RunLiveActivityPublicationCycleInput, "cycleAuthority"> {
  readonly cycleStore: LiveActivityPublicationCycleStore;
  readonly cadenceMilliseconds: number;
  readonly leaseDurationMilliseconds: number;
  /** Test seam; production activation callers omit it and use the Phase 5A worker. */
  readonly runPublicationCycle?: PublicationCycleRunner;
}

interface ClaimedCycleResultBase {
  readonly slot: LiveActivityPublicationCycleSlot;
  readonly cycleId: string;
  readonly fenceGeneration: number;
}

export type RunClaimedLiveActivityPublicationCycleResult =
  | (ClaimedCycleResultBase & {
      readonly outcome: "CLAIMED_AND_COMPLETED";
      readonly summary: LiveActivityPublicationCycleSafeSummary;
    })
  | (ClaimedCycleResultBase & {
      readonly outcome: "NO_ACTIVE_SESSIONS";
      readonly summary: LiveActivityPublicationCycleSafeSummary;
    })
  | (ClaimedCycleResultBase & {
      readonly outcome: "LOST_LEASE" | "WORKER_FAILED";
    })
  | (Partial<ClaimedCycleResultBase> & {
      readonly outcome: "COORDINATION_FAILED";
      readonly slot: LiveActivityPublicationCycleSlot;
    })
  | {
      readonly outcome: "ALREADY_RUNNING";
      readonly slot: LiveActivityPublicationCycleSlot;
      readonly activeSlot: LiveActivityPublicationCycleSlot;
    }
  | {
      readonly outcome: "ALREADY_COMPLETED";
      readonly slot: LiveActivityPublicationCycleSlot;
      readonly terminalState: "COMPLETED" | "NO_WORK" | "FAILED";
    }
  | {
      readonly outcome: "STALE_TRIGGER" | "CADENCE_CONFLICT";
      readonly slot: LiveActivityPublicationCycleSlot;
    };

interface CoordinatedAuthority {
  readonly authority: LiveActivityPublicationCycleAuthority;
  owner(): LiveActivityPublicationCycleOwner;
  stopReason(): LiveActivityPublicationCycleAuthorityStopReason | null;
}

function readClock(now: () => Date): Date {
  const value = now();
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

function createCoordinatedAuthority(input: {
  readonly store: LiveActivityPublicationCycleStore;
  readonly initialOwner: LiveActivityPublicationCycleOwner;
  readonly now: () => Date;
  readonly leaseDurationMilliseconds: number;
}): CoordinatedAuthority {
  let currentOwner = input.initialOwner;
  let stopped: LiveActivityPublicationCycleAuthorityStopReason | null = null;
  let confirmation: Promise<LiveActivityPublicationCycleAuthorityStatus> | null = null;

  const confirmCurrent = async (): Promise<LiveActivityPublicationCycleAuthorityStatus> => {
    if (stopped != null) return stopped;
    if (confirmation != null) return await confirmation;
    const pending = (async (): Promise<LiveActivityPublicationCycleAuthorityStatus> => {
      try {
        const renewed = await input.store.renewPublicationCycle({
          owner: currentOwner,
          checkedAt: readClock(input.now),
          leaseDurationMilliseconds: input.leaseDurationMilliseconds,
        });
        if (renewed == null) {
          stopped = "CYCLE_AUTHORITY_LOST";
          return stopped;
        }
        currentOwner = renewed;
        return "CURRENT";
      } catch {
        stopped = "CYCLE_AUTHORITY_UNAVAILABLE";
        return stopped;
      }
    })();
    confirmation = pending;
    try {
      return await pending;
    } finally {
      if (confirmation === pending) confirmation = null;
    }
  };

  const authority: LiveActivityPublicationCycleAuthority = Object.freeze({
    confirmCurrent,
    stopReason: () => stopped,
    beginSendIfCurrent: async <T>(
      start: () => Promise<T>,
    ): Promise<BeginLiveActivityPublicationCycleAuthorizedSend<T>> => {
      const status = await confirmCurrent();
      if (status !== "CURRENT") {
        return Object.freeze({ status: "STOPPED", reason: status });
      }
      let completion: Promise<T>;
      try {
        completion = Promise.resolve(start());
      } catch (error) {
        completion = Promise.reject(error);
      }
      return Object.freeze({ status: "STARTED", completion });
    },
  });

  return Object.freeze({
    authority,
    owner: () => currentOwner,
    stopReason: () => stopped,
  });
}

const SEND_DECISIONS = new Set([
  "START",
  "UPDATE_CONTENT",
  "UPDATE_FRESHNESS",
  "UPDATE_RECONCILIATION",
]);

function safeSummary(
  summary: LiveActivityPublicationCycleSummary,
): LiveActivityPublicationCycleSafeSummary {
  const decisions = summary.bindings.map(({ decision }) => decision);
  const dispatches = summary.bindings
    .map(({ dispatch }) => dispatch)
    .filter((dispatch) => dispatch != null);
  return createLiveActivityPublicationCycleSafeSummary({
    acquisitionCount: summary.acquisitionCount,
    publicationOutcomeCount: summary.publicationOutcomeCount,
    readyPublicationGroupCount: summary.readyPublicationGroupCount,
    bindingCount: summary.bindingCount,
    sendDecisionCount: decisions.filter((decision) => SEND_DECISIONS.has(decision))
      .length,
    noPushDecisionCount: decisions.filter((decision) =>
      decision.startsWith("NO_PUSH_"),
    ).length,
    deferralDecisionCount: decisions.filter(
      (decision) =>
        !SEND_DECISIONS.has(decision) && !decision.startsWith("NO_PUSH_"),
    ).length,
    dispatchRequestedCount: dispatches.length,
    networkAttemptedCount: dispatches.filter(
      ({ networkAttempted }) => networkAttempted === true,
    ).length,
    dispatchRecordedCount: dispatches.filter(
      ({ outcome }) => outcome === "RECORDED",
    ).length,
    dispatchNotReservedCount: dispatches.filter(
      ({ outcome }) => outcome === "NOT_RESERVED",
    ).length,
    dispatchNotSentCount: dispatches.filter(
      ({ outcome }) => outcome === "NOT_SENT",
    ).length,
    dispatchResultNotRecordedCount: dispatches.filter(
      ({ outcome }) => outcome === "RESULT_NOT_RECORDED",
    ).length,
    dispatchCallFailedCount: dispatches.filter(
      ({ outcome }) => outcome === "CALL_FAILED",
    ).length,
  });
}

function claimedBase(
  slot: LiveActivityPublicationCycleSlot,
  owner: LiveActivityPublicationCycleOwner,
): ClaimedCycleResultBase {
  return Object.freeze({
    slot,
    cycleId: owner.cycleId,
    fenceGeneration: owner.fenceGeneration,
  });
}

function authorityStoppedResult(
  slot: LiveActivityPublicationCycleSlot,
  coordinated: CoordinatedAuthority,
): RunClaimedLiveActivityPublicationCycleResult {
  const base = claimedBase(slot, coordinated.owner());
  return coordinated.stopReason() === "CYCLE_AUTHORITY_UNAVAILABLE"
    ? Object.freeze({ ...base, outcome: "COORDINATION_FAILED" })
    : Object.freeze({ ...base, outcome: "LOST_LEASE" });
}

async function finalizeFailure(
  input: RunClaimedLiveActivityPublicationCycleInput,
  slot: LiveActivityPublicationCycleSlot,
  coordinated: CoordinatedAuthority,
  summary: LiveActivityPublicationCycleSafeSummary | null,
  failureCode: "WORKER_FAILED" | "PUBLICATION_HISTORY_UNAVAILABLE",
): Promise<RunClaimedLiveActivityPublicationCycleResult> {
  try {
    const finalized = await input.cycleStore.finalizePublicationCycle({
      owner: coordinated.owner(),
      finalizedAt: readClock(input.now),
      state: "FAILED",
      summary,
      failureCode,
    });
    if (finalized == null) {
      return Object.freeze({
        ...claimedBase(slot, coordinated.owner()),
        outcome: "LOST_LEASE",
      });
    }
    return Object.freeze({
      ...claimedBase(slot, finalized),
      outcome: "WORKER_FAILED",
    });
  } catch {
    return Object.freeze({
      ...claimedBase(slot, coordinated.owner()),
      outcome: "COORDINATION_FAILED",
    });
  }
}

/**
 * Attempts exactly one current logical slot. It creates no route, timer, recurrence,
 * connection, scheduler registration, transport, or production configuration.
 */
export async function runClaimedLiveActivityPublicationCycle(
  input: RunClaimedLiveActivityPublicationCycleInput,
): Promise<RunClaimedLiveActivityPublicationCycleResult> {
  const cadenceMilliseconds = liveActivityPublicationCycleCadenceMilliseconds(
    input.cadenceMilliseconds,
  );
  const leaseDurationMilliseconds =
    liveActivityPublicationCycleLeaseDurationMilliseconds(
      input.leaseDurationMilliseconds,
    );
  const triggeredAt = readClock(input.now);
  const slot = liveActivityPublicationCycleSlotAt(
    triggeredAt,
    cadenceMilliseconds,
  );

  let claim;
  try {
    claim = await input.cycleStore.claimPublicationCycle({
      slot,
      claimedAt: triggeredAt,
      leaseDurationMilliseconds,
    });
  } catch {
    return Object.freeze({ outcome: "COORDINATION_FAILED", slot });
  }
  if (claim.status === "ALREADY_RUNNING") {
    return Object.freeze({
      outcome: "ALREADY_RUNNING",
      slot,
      activeSlot: claim.active.slot,
    });
  }
  if (claim.status === "ALREADY_FINALIZED") {
    return Object.freeze({
      outcome: "ALREADY_COMPLETED",
      slot,
      terminalState: claim.cycle.state as "COMPLETED" | "NO_WORK" | "FAILED",
    });
  }
  if (claim.status === "STALE_SLOT") {
    return Object.freeze({ outcome: "STALE_TRIGGER", slot });
  }
  if (claim.status === "CADENCE_CONFLICT") {
    return Object.freeze({ outcome: "CADENCE_CONFLICT", slot });
  }

  const coordinated = createCoordinatedAuthority({
    store: input.cycleStore,
    initialOwner: claim.owner,
    now: input.now,
    leaseDurationMilliseconds,
  });
  if ((await coordinated.authority.confirmCurrent()) !== "CURRENT") {
    return authorityStoppedResult(slot, coordinated);
  }

  const configuredRunner = input.runPublicationCycle;
  const publicationInput: RunLiveActivityPublicationCycleInput = {
    sessionStore: input.sessionStore,
    now: input.now,
    transportClient: input.transportClient,
    journeyClient: input.journeyClient,
    previousSnapshots: input.previousSnapshots,
    deliveryStore: input.deliveryStore,
    dispatchStore: input.dispatchStore,
    dispatcher: input.dispatcher,
    clientStateProvider: input.clientStateProvider,
    startAlertProvider: input.startAlertProvider,
    priorityPolicy: input.priorityPolicy,
    policyConfig: input.policyConfig,
    dispatchConcurrency: input.dispatchConcurrency,
    runStoredTick: input.runStoredTick,
  };
  const executePublicationCycle =
    configuredRunner ?? runLiveActivityPublicationCycle;
  let workerSummary: LiveActivityPublicationCycleSummary;
  try {
    workerSummary = await executePublicationCycle({
      ...publicationInput,
      cycleAuthority: coordinated.authority,
    });
  } catch {
    if (coordinated.stopReason() != null) {
      return authorityStoppedResult(slot, coordinated);
    }
    return await finalizeFailure(
      input,
      slot,
      coordinated,
      null,
      "WORKER_FAILED",
    );
  }

  if (
    workerSummary.cycleAuthorityStatus === "CYCLE_AUTHORITY_LOST" ||
    workerSummary.cycleAuthorityStatus === "CYCLE_AUTHORITY_UNAVAILABLE" ||
    coordinated.stopReason() != null
  ) {
    return authorityStoppedResult(slot, coordinated);
  }

  let summary: LiveActivityPublicationCycleSafeSummary;
  try {
    summary = safeSummary(workerSummary);
  } catch {
    return await finalizeFailure(
      input,
      slot,
      coordinated,
      null,
      "WORKER_FAILED",
    );
  }
  if (workerSummary.historyLookupFailed) {
    return await finalizeFailure(
      input,
      slot,
      coordinated,
      summary,
      "PUBLICATION_HISTORY_UNAVAILABLE",
    );
  }

  const noActiveSessions =
    summary.acquisitionCount === 0 &&
    summary.publicationOutcomeCount === 0 &&
    summary.readyPublicationGroupCount === 0 &&
    summary.bindingCount === 0;
  try {
    const finalized = await input.cycleStore.finalizePublicationCycle({
      owner: coordinated.owner(),
      finalizedAt: readClock(input.now),
      state: noActiveSessions ? "NO_WORK" : "COMPLETED",
      summary,
      failureCode: null,
    });
    if (finalized == null) {
      return Object.freeze({
        ...claimedBase(slot, coordinated.owner()),
        outcome: "LOST_LEASE",
      });
    }
    return Object.freeze({
      ...claimedBase(slot, finalized),
      outcome: noActiveSessions
        ? "NO_ACTIVE_SESSIONS"
        : "CLAIMED_AND_COMPLETED",
      summary,
    });
  } catch {
    return Object.freeze({
      ...claimedBase(slot, coordinated.owner()),
      outcome: "COORDINATION_FAILED",
    });
  }
}
