import type {
  LiveActivityPublicationCycleFailureCode,
  LiveActivityPublicationCycleOwner,
  LiveActivityPublicationCycleRecord,
  LiveActivityPublicationCycleSafeSummary,
  LiveActivityPublicationCycleSlot,
} from "./publicationCycleModel.js";

export interface ClaimLiveActivityPublicationCycleInput {
  readonly slot: LiveActivityPublicationCycleSlot;
  readonly claimedAt: Date;
  readonly leaseDurationMilliseconds: number;
}

export type ClaimLiveActivityPublicationCycleResult =
  | {
      readonly status: "CLAIMED";
      readonly owner: LiveActivityPublicationCycleOwner;
    }
  | {
      readonly status: "ALREADY_RUNNING";
      readonly active: LiveActivityPublicationCycleOwner;
    }
  | {
      readonly status: "ALREADY_FINALIZED";
      readonly cycle: LiveActivityPublicationCycleRecord;
    }
  | {
      readonly status: "STALE_SLOT";
    }
  | {
      readonly status: "CADENCE_CONFLICT";
    };

export interface RenewLiveActivityPublicationCycleInput {
  readonly owner: LiveActivityPublicationCycleOwner;
  readonly checkedAt: Date;
  readonly leaseDurationMilliseconds: number;
}

export interface FinalizeLiveActivityPublicationCycleInput {
  readonly owner: LiveActivityPublicationCycleOwner;
  readonly finalizedAt: Date;
  readonly state: "COMPLETED" | "NO_WORK" | "FAILED";
  readonly summary: LiveActivityPublicationCycleSafeSummary | null;
  readonly failureCode: Exclude<
    LiveActivityPublicationCycleFailureCode,
    "LEASE_EXPIRED"
  > | null;
}

/**
 * Global durable coordination boundary. Implementations use short transactions only;
 * callbacks, transit acquisition, and delivery work never run while a lock is held.
 */
export interface LiveActivityPublicationCycleStore {
  claimPublicationCycle(
    input: ClaimLiveActivityPublicationCycleInput,
  ): Promise<ClaimLiveActivityPublicationCycleResult>;
  renewPublicationCycle(
    input: RenewLiveActivityPublicationCycleInput,
  ): Promise<LiveActivityPublicationCycleOwner | undefined>;
  finalizePublicationCycle(
    input: FinalizeLiveActivityPublicationCycleInput,
  ): Promise<LiveActivityPublicationCycleRecord | undefined>;
}
