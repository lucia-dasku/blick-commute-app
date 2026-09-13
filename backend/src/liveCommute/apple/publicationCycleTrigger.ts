import type { RunClaimedLiveActivityPublicationCycleResult } from "./publicationCycleCoordinator.js";

/** Scheduler-provider boundary. An adapter may request one current cycle and nothing more. */
export interface LiveActivityCycleTrigger {
  trigger(): Promise<RunClaimedLiveActivityPublicationCycleResult>;
}

export function createLiveActivityCycleTrigger(
  runCurrentCycle: () => Promise<RunClaimedLiveActivityPublicationCycleResult>,
): LiveActivityCycleTrigger {
  if (typeof runCurrentCycle !== "function") {
    throw new TypeError("runCurrentCycle must be a function");
  }
  return Object.freeze({
    trigger: async () => await runCurrentCycle(),
  });
}
