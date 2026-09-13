import { QueueClient } from "@vercel/queue";
import {
  LIVE_ACTIVITY_CYCLE_QUEUE_VISIBILITY_TIMEOUT_SECONDS,
  LiveActivityCycleQueuePermanentError,
  createLiveActivityCycleQueueConsumer,
  liveActivityCycleQueueRetry,
} from "../src/liveCommute/apple/publicationCycleQueue.js";
import type { LiveActivityCycleTrigger } from "../src/liveCommute/apple/publicationCycleTrigger.js";

const queueClient = new QueueClient();

export function createLiveActivityCycleQueueNodeHandler(input: {
  readonly trigger: LiveActivityCycleTrigger;
  readonly client?: QueueClient;
}) {
  const client = input.client ?? queueClient;
  return client.handleNodeCallback(
    createLiveActivityCycleQueueConsumer({
      trigger: input.trigger,
      sendMessage: client.send,
    }),
    {
      visibilityTimeoutSeconds:
        LIVE_ACTIVITY_CYCLE_QUEUE_VISIBILITY_TIMEOUT_SECONDS,
      retry: liveActivityCycleQueueRetry,
    },
  );
}

const unconfiguredTrigger: LiveActivityCycleTrigger = Object.freeze({
  trigger: async () => {
    throw new LiveActivityCycleQueuePermanentError(
      "live activity cycle runtime is not configured",
    );
  },
});

/**
 * The queue endpoint is private and dormant until a later API slice replaces the
 * unconfigured trigger with the reviewed production cycle composition and seeds a message.
 */
export default createLiveActivityCycleQueueNodeHandler({
  trigger: unconfiguredTrigger,
});
