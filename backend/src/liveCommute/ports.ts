import type { LiveCommutePublicationGroup } from "./planner.js";
import type { CanonicalLiveCommuteQuery, LiveCommuteSession } from "./model.js";

/** Supplies concrete sessions without prescribing persistence or an account model. */
export interface LiveCommuteSessionSource {
  listSessions(): Promise<readonly LiveCommuteSession[]>;
}

/** Acquires one fresh, normalized transit state for one canonical query group. */
export interface LiveCommuteStateSource<State> {
  acquireFreshState(query: CanonicalLiveCommuteQuery, now: Date): Promise<State>;
}

/**
 * Publishes one acquired state without naming a push platform. The distinct group type
 * requires callers to revalidate session expiry after asynchronous acquisition.
 */
export interface LiveCommuteStatePublisher<State> {
  publishState(group: LiveCommutePublicationGroup, state: State): Promise<void>;
}
