import type { PublicationKey, LiveCommuteSession } from "./model.js";
import type { LiveCommutePublicationGroup } from "./planner.js";
import type { LiveCommuteSnapshot } from "./snapshot.js";

/** Supplies concrete sessions without prescribing persistence or an account model. */
export interface LiveCommuteSessionSource {
  listSessions(): Promise<readonly LiveCommuteSession[]>;
}

/**
 * Optional caller-owned history. This phase provides no storage implementation; an eventual
 * host can supply a previous publication snapshot without changing the tick engine.
 */
export interface LiveCommutePreviousSnapshotSource {
  getPreviousSnapshot(key: PublicationKey): LiveCommuteSnapshot | undefined;
}

/**
 * Future platform-neutral delivery seam. The Phase 2 tick returns publication outcomes and
 * never invokes this port itself.
 */
export interface LiveCommuteSnapshotPublisher {
  publishSnapshot(
    group: LiveCommutePublicationGroup,
    snapshot: LiveCommuteSnapshot,
  ): Promise<void>;
}
