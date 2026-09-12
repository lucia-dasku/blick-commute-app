import { createHash } from "node:crypto";
import type { ActivityKitStartMode } from "./activityKitPayload.js";
import type {
  LiveActivityDirectDispatchOperation,
  LiveActivityDispatchState,
  LiveActivityPublicationMetadata,
} from "./dispatchModel.js";
import type { LiveActivityDeliveryStrategy } from "./deliveryModel.js";
import {
  prepareLiveActivityContentState,
  AuthoritativeReadyLiveCommutePublication,
  type PreparedLiveActivityContentState,
} from "./deliveryPlan.js";
import type {
  BlickLiveActivityContentState,
  BlickLiveActivityExactContentStateV1,
  BlickLiveActivityLineContentStateV1,
} from "./liveActivityWireContract.js";

const SHA256_HEX_PATTERN = /^[0-9a-f]{64}$/;

export type LiveActivityPublicationCapability =
  | "DIRECT_LEGACY"
  | "DIRECT_IOS18"
  | "BROADCAST_CAPABLE"
  | "UNKNOWN";

export type LiveActivityFrequentPushState = "ENABLED" | "DISABLED" | "UNKNOWN";

export type LiveActivityUnknownUpdateReconciliationPolicy =
  | "ENABLED"
  | "DISABLED";

export interface LiveActivityPublicationPolicyConfig {
  readonly freshSourceLifetimeMilliseconds: number;
  readonly freshnessHeartbeatLeadMilliseconds: number;
  readonly minimumFreshnessPublicationIntervalMilliseconds: number;
  readonly minimumStaleDateLeadMilliseconds: number;
  readonly unknownUpdateReconciliation: LiveActivityUnknownUpdateReconciliationPolicy;
}

export interface LiveActivityPublicationHistoryEntry {
  readonly operation: LiveActivityDirectDispatchOperation;
  readonly state: LiveActivityDispatchState;
  readonly eventTimestamp: number;
  readonly publicationMetadata: LiveActivityPublicationMetadata | null;
  readonly completedAt: Date | null;
  readonly retryNotBefore: Date | null;
}

export interface LiveActivityPublicationHistory {
  readonly latestAcceptedAttempt: LiveActivityPublicationHistoryEntry | null;
  readonly latestAttempt: LiveActivityPublicationHistoryEntry | null;
}

export interface LiveActivityPublicationIntent {
  readonly status: "READY" | "READY_STALE";
  readonly contentState: BlickLiveActivityContentState;
  readonly visibleContentFingerprint: string;
  readonly sourceFetchedAt: Date;
  readonly staleAt: Date;
  readonly hasVisibleContent: boolean;
}

export interface CreateLiveActivityPublicationIntentInput {
  readonly status: LiveActivityPublicationIntent["status"];
  readonly contentState: BlickLiveActivityContentState;
  readonly config: LiveActivityPublicationPolicyConfig;
}

export interface PreparedLiveActivityPublication {
  readonly publication: AuthoritativeReadyLiveCommutePublication;
  readonly generatedAt: Date;
  readonly preparedContentState: PreparedLiveActivityContentState;
  readonly intent: LiveActivityPublicationIntent;
}

export interface PrepareLiveActivityPublicationInput {
  readonly publication: AuthoritativeReadyLiveCommutePublication;
  readonly generatedAt: Date;
  readonly config: LiveActivityPublicationPolicyConfig;
}

export interface LiveActivityPublicationPolicyInput {
  /** Trusted ActivityKit generation instant; distinct from sourceFetchedAt. */
  readonly decisionAt: Date;
  readonly intent: LiveActivityPublicationIntent;
  readonly history: LiveActivityPublicationHistory;
  readonly deliveryStrategy: LiveActivityDeliveryStrategy;
  readonly capability: LiveActivityPublicationCapability;
  readonly frequentPushes: LiveActivityFrequentPushState;
  readonly startAlertAvailable: boolean;
  /** Client-supplied evidence that this binding already represents an on-device activity. */
  readonly existingActivityKnown: boolean;
  readonly config: LiveActivityPublicationPolicyConfig;
}

interface LiveActivityPublicationSendDecisionBase {
  readonly publicationMetadata: LiveActivityPublicationMetadata;
}

export type LiveActivityPublicationDecision =
  | (LiveActivityPublicationSendDecisionBase & {
      readonly kind: "START";
      readonly mode: Extract<
        ActivityKitStartMode,
        { readonly kind: "DIRECT_LEGACY" | "DIRECT_IOS_18" }
      >;
    })
  | (LiveActivityPublicationSendDecisionBase & {
      readonly kind:
        | "UPDATE_CONTENT"
        | "UPDATE_FRESHNESS"
        | "UPDATE_RECONCILIATION";
      /** STALE content is honest on its own and carries no renewed stale-date. */
      readonly staleAt: Date | null;
    })
  | {
      readonly kind:
        | "NO_PUSH_UNCHANGED"
        | "NO_PUSH_STALE_SOURCE"
        | "DEFER_START_CAPABILITY_UNKNOWN"
        | "DEFER_START_ALERT_UNAVAILABLE"
        | "DEFER_START_CONTENT_UNAVAILABLE"
        | "DEFER_BROADCAST"
        | "DEFER_DISPATCH_BUSY"
        | "DEFER_OUTCOME_UNKNOWN"
        | "DEFER_PUBLICATION_HISTORY"
        | "END_REQUIRED_BUT_UNAVAILABLE";
    };

const preparedLiveActivityPublications = new WeakSet<object>();

function validDuration(value: number, field: string, allowZero: boolean): number {
  if (
    !Number.isSafeInteger(value) ||
    value < (allowZero ? 0 : 1)
  ) {
    throw new RangeError(`${field} must be ${allowZero ? "a nonnegative" : "a positive"} safe integer`);
  }
  return value;
}

function validDate(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function frozenKind(kind: Exclude<LiveActivityPublicationDecision["kind"], "START" | "UPDATE_CONTENT" | "UPDATE_FRESHNESS" | "UPDATE_RECONCILIATION">): LiveActivityPublicationDecision {
  return Object.freeze({ kind });
}

export function createLiveActivityPublicationPolicyConfig(
  input: LiveActivityPublicationPolicyConfig,
): LiveActivityPublicationPolicyConfig {
  if (input == null || typeof input !== "object") {
    throw new TypeError("publication policy config must be an object");
  }
  if (
    input.unknownUpdateReconciliation !== "ENABLED" &&
    input.unknownUpdateReconciliation !== "DISABLED"
  ) {
    throw new RangeError("unknownUpdateReconciliation is invalid");
  }
  return Object.freeze({
    freshSourceLifetimeMilliseconds: validDuration(
      input.freshSourceLifetimeMilliseconds,
      "freshSourceLifetimeMilliseconds",
      false,
    ),
    freshnessHeartbeatLeadMilliseconds: validDuration(
      input.freshnessHeartbeatLeadMilliseconds,
      "freshnessHeartbeatLeadMilliseconds",
      true,
    ),
    minimumFreshnessPublicationIntervalMilliseconds: validDuration(
      input.minimumFreshnessPublicationIntervalMilliseconds,
      "minimumFreshnessPublicationIntervalMilliseconds",
      true,
    ),
    minimumStaleDateLeadMilliseconds: validDuration(
      input.minimumStaleDateLeadMilliseconds,
      "minimumStaleDateLeadMilliseconds",
      true,
    ),
    unknownUpdateReconciliation: input.unknownUpdateReconciliation,
  });
}

function lineVisibleValue(state: BlickLiveActivityLineContentStateV1) {
  return {
    schemaVersion: state.schemaVersion,
    commuteKind: state.commuteKind,
    freshness: state.freshness,
    departures: state.departures.map((departure) => ({
      departureId: departure.departureId,
      lineDesignation: departure.lineDesignation,
      direction: departure.direction,
      destination: departure.destination,
      scheduledAt: departure.scheduledAt,
      expectedAt: departure.expectedAt,
      effectiveAt: departure.effectiveAt,
      isCancelled: departure.isCancelled,
      departureState: departure.departureState,
      journeyState: departure.journeyState,
      predictionState: departure.predictionState,
    })),
  };
}

function exactVisibleValue(state: BlickLiveActivityExactContentStateV1) {
  return {
    schemaVersion: state.schemaVersion,
    commuteKind: state.commuteKind,
    freshness: state.freshness,
    journeys: state.journeys.map((journey) => ({
      journeyId: journey.journeyId,
      role: journey.role,
      originName: journey.originName,
      destinationName: journey.destinationName,
      departureAt: journey.departureAt,
      effectiveDepartureAt: journey.effectiveDepartureAt,
      arrivalAt: journey.arrivalAt,
      transferCount: journey.transferCount,
      firstLeg: {
        transportMode: journey.firstLeg.transportMode,
        lineDesignation: journey.firstLeg.lineDesignation,
        direction: journey.firstLeg.direction,
        originName: journey.firstLeg.originName,
        destinationName: journey.firstLeg.destinationName,
        departureAt: journey.firstLeg.departureAt,
        arrivalAt: journey.firstLeg.arrivalAt,
        isRealtime: journey.firstLeg.isRealtime,
      },
    })),
  };
}

/** SHA-256 over visible v1 wire semantics only; source bookkeeping never enters the hash. */
export function liveActivityVisibleFingerprint(
  contentState: BlickLiveActivityContentState,
): string {
  if (contentState == null || typeof contentState !== "object") {
    throw new TypeError("contentState must be an object");
  }
  const visible =
    contentState.commuteKind === "LINE_DIRECTION"
      ? lineVisibleValue(contentState)
      : contentState.commuteKind === "EXACT_DESTINATION"
        ? exactVisibleValue(contentState)
        : (() => {
            throw new RangeError("contentState.commuteKind is invalid");
          })();
  return createHash("sha256").update(JSON.stringify(visible), "utf8").digest("hex");
}

export function createLiveActivityPublicationIntent(
  input: CreateLiveActivityPublicationIntentInput,
): LiveActivityPublicationIntent {
  const config = createLiveActivityPublicationPolicyConfig(input.config);
  if (input.status !== "READY" && input.status !== "READY_STALE") {
    throw new RangeError("publication status is invalid");
  }
  if (
    (input.status === "READY" && input.contentState.freshness !== "FRESH") ||
    (input.status === "READY_STALE" && input.contentState.freshness !== "STALE")
  ) {
    throw new RangeError("publication status and content freshness do not match");
  }
  const sourceFetchedAt = new Date(input.contentState.sourceFetchedAt * 1_000);
  validDate(sourceFetchedAt, "contentState.sourceFetchedAt");
  const staleAt = new Date(
    Math.floor(
      (sourceFetchedAt.getTime() + config.freshSourceLifetimeMilliseconds) /
        1_000,
    ) * 1_000,
  );
  validDate(staleAt, "publication staleAt");
  const visibleContentFingerprint = liveActivityVisibleFingerprint(input.contentState);
  const hasVisibleContent =
    input.contentState.commuteKind === "LINE_DIRECTION"
      ? input.contentState.departures.length > 0
      : input.contentState.journeys.length > 0;
  const sourceFetchedAtMilliseconds = sourceFetchedAt.getTime();
  const staleAtMilliseconds = staleAt.getTime();
  return Object.freeze({
    status: input.status,
    contentState: input.contentState,
    visibleContentFingerprint,
    get sourceFetchedAt() {
      return new Date(sourceFetchedAtMilliseconds);
    },
    get staleAt() {
      return new Date(staleAtMilliseconds);
    },
    hasVisibleContent,
  });
}

/** Maps and fingerprints one authoritative group exactly once for safe recipient reuse. */
export function prepareLiveActivityPublication(
  input: PrepareLiveActivityPublicationInput,
): PreparedLiveActivityPublication {
  if (input == null || typeof input !== "object") {
    throw new TypeError("prepared publication input must be an object");
  }
  const generatedAt = validDate(input.generatedAt, "generatedAt");
  const publication = input.publication;
  if (
    publication == null ||
    (publication.status !== "READY" && publication.status !== "READY_STALE")
  ) {
    throw new RangeError("only an authoritative ready publication can be prepared");
  }
  const preparedContentState = prepareLiveActivityContentState({
    publication,
    generatedAt,
  });
  const intent = createLiveActivityPublicationIntent({
    status: publication.status,
    contentState: preparedContentState.contentState,
    config: input.config,
  });
  const generatedAtMilliseconds = generatedAt.getTime();
  const prepared = Object.freeze({
    publication,
    get generatedAt() {
      return new Date(generatedAtMilliseconds);
    },
    preparedContentState,
    intent,
  });
  preparedLiveActivityPublications.add(prepared);
  return prepared;
}

/** Rejects structurally forged or cross-group prepared state without repeating the mapping/hash. */
export function assertPreparedLiveActivityPublication(
  input: unknown,
  publication: AuthoritativeReadyLiveCommutePublication,
  generatedAt: Date,
): asserts input is PreparedLiveActivityPublication {
  if (
    input == null ||
    typeof input !== "object" ||
    !preparedLiveActivityPublications.has(input) ||
    (input as PreparedLiveActivityPublication).publication !== publication ||
    (input as PreparedLiveActivityPublication).generatedAt.getTime() !==
      generatedAt.getTime() ||
    (input as PreparedLiveActivityPublication).intent.contentState !==
      (input as PreparedLiveActivityPublication).preparedContentState.contentState
  ) {
    throw new RangeError(
      "prepared Live Activity publication does not match the dispatch input",
    );
  }
}

function publicationMetadata(
  intent: LiveActivityPublicationIntent,
  staleAt: Date | null,
): LiveActivityPublicationMetadata {
  return Object.freeze({
    visibleContentFingerprint: intent.visibleContentFingerprint,
    sourceFetchedAt: new Date(intent.sourceFetchedAt),
    staleAt: staleAt == null ? null : new Date(staleAt),
  });
}

function freshStaleDateIsSafe(
  intent: LiveActivityPublicationIntent,
  decisionAt: Date,
  config: LiveActivityPublicationPolicyConfig,
): boolean {
  return (
    intent.contentState.freshness !== "FRESH" ||
    intent.staleAt.getTime() >
      decisionAt.getTime() + config.minimumStaleDateLeadMilliseconds
  );
}

function startModeFor(
  capability: LiveActivityPublicationCapability,
): Extract<
  ActivityKitStartMode,
  { readonly kind: "DIRECT_LEGACY" | "DIRECT_IOS_18" }
> | null {
  if (capability === "DIRECT_LEGACY") {
    return Object.freeze({ kind: "DIRECT_LEGACY" });
  }
  if (capability === "DIRECT_IOS18") {
    return Object.freeze({ kind: "DIRECT_IOS_18" });
  }
  return null;
}

function sendUpdate(
  kind: "UPDATE_CONTENT" | "UPDATE_FRESHNESS" | "UPDATE_RECONCILIATION",
  intent: LiveActivityPublicationIntent,
): LiveActivityPublicationDecision {
  const staleAt =
    intent.contentState.freshness === "FRESH" ? new Date(intent.staleAt) : null;
  return Object.freeze({
    kind,
    staleAt,
    publicationMetadata: publicationMetadata(intent, staleAt),
  });
}

/** Pure cross-process decision over authoritative visible state and durable attempt history. */
export function decideLiveActivityPublication(
  input: LiveActivityPublicationPolicyInput,
): LiveActivityPublicationDecision {
  if (input == null || typeof input !== "object") {
    throw new TypeError("publication policy input must be an object");
  }
  const config = createLiveActivityPublicationPolicyConfig(input.config);
  const decisionAt = validDate(input.decisionAt, "decisionAt");
  const { intent, history } = input;
  if (intent == null || history == null) {
    throw new TypeError("publication intent and history are required");
  }
  if (!SHA256_HEX_PATTERN.test(intent.visibleContentFingerprint)) {
    throw new RangeError("visibleContentFingerprint must be a lowercase SHA-256 value");
  }
  if (typeof input.existingActivityKnown !== "boolean") {
    throw new TypeError("existingActivityKnown must be a boolean");
  }
  if (input.deliveryStrategy === "BROADCAST_CHANNEL") {
    return frozenKind("DEFER_BROADCAST");
  }

  const latestAccepted = history.latestAcceptedAttempt;
  const latest = history.latestAttempt;
  const existingActivityKnown =
    input.existingActivityKnown ||
    (latest != null && latest.operation !== "START");
  const dispatchIsBusy =
    latest?.state === "RESERVED" ||
    latest?.state === "IN_FLIGHT" ||
    (latest?.state === "RETRYABLE" &&
      latest.retryNotBefore != null &&
      latest.retryNotBefore.getTime() > decisionAt.getTime());
  if (latestAccepted == null) {
    if (latest?.state === "ACCEPTED") {
      return frozenKind("DEFER_PUBLICATION_HISTORY");
    }
    if (dispatchIsBusy) {
      return frozenKind("DEFER_DISPATCH_BUSY");
    }
    if (latest?.operation === "DIRECT_END") {
      return frozenKind("END_REQUIRED_BUT_UNAVAILABLE");
    }
    if (!intent.hasVisibleContent) {
      return existingActivityKnown
        ? frozenKind("END_REQUIRED_BUT_UNAVAILABLE")
        : frozenKind("DEFER_START_CONTENT_UNAVAILABLE");
    }
    if (existingActivityKnown) {
      if (latest?.state === "OUTCOME_UNKNOWN") {
        const currentEventTimestamp = Math.floor(decisionAt.getTime() / 1_000);
        if (currentEventTimestamp <= latest.eventTimestamp) {
          return frozenKind("DEFER_OUTCOME_UNKNOWN");
        }
        const unknownMetadata = latest.publicationMetadata;
        if (
          unknownMetadata != null &&
          intent.sourceFetchedAt.getTime() < unknownMetadata.sourceFetchedAt.getTime()
        ) {
          return frozenKind("NO_PUSH_STALE_SOURCE");
        }
        if (
          unknownMetadata != null &&
          unknownMetadata.visibleContentFingerprint !==
            intent.visibleContentFingerprint
        ) {
          if (!freshStaleDateIsSafe(intent, decisionAt, config)) {
            return frozenKind("NO_PUSH_STALE_SOURCE");
          }
          return sendUpdate("UPDATE_CONTENT", intent);
        }
        if (
          config.unknownUpdateReconciliation === "ENABLED" &&
          freshStaleDateIsSafe(intent, decisionAt, config)
        ) {
          return sendUpdate("UPDATE_RECONCILIATION", intent);
        }
        return frozenKind("DEFER_OUTCOME_UNKNOWN");
      }
      if (!freshStaleDateIsSafe(intent, decisionAt, config)) {
        return frozenKind("NO_PUSH_STALE_SOURCE");
      }
      return sendUpdate("UPDATE_CONTENT", intent);
    }
    if (latest?.operation === "START" && latest.state === "OUTCOME_UNKNOWN") {
      return frozenKind("DEFER_OUTCOME_UNKNOWN");
    }
    if (intent.status !== "READY" || intent.contentState.freshness !== "FRESH") {
      return frozenKind("NO_PUSH_STALE_SOURCE");
    }
    if (!freshStaleDateIsSafe(intent, decisionAt, config)) {
      return frozenKind("NO_PUSH_STALE_SOURCE");
    }
    if (input.capability === "UNKNOWN") {
      return frozenKind("DEFER_START_CAPABILITY_UNKNOWN");
    }
    const mode = startModeFor(input.capability);
    if (mode == null) return frozenKind("DEFER_BROADCAST");
    if (!input.startAlertAvailable) {
      return frozenKind("DEFER_START_ALERT_UNAVAILABLE");
    }
    return Object.freeze({
      kind: "START",
      mode,
      publicationMetadata: publicationMetadata(intent, null),
    });
  }

  const acceptedMetadata = latestAccepted.publicationMetadata;
  if (latestAccepted.state !== "ACCEPTED" || acceptedMetadata == null) {
    return frozenKind("DEFER_PUBLICATION_HISTORY");
  }
  if (latestAccepted.operation === "DIRECT_END" || !intent.hasVisibleContent) {
    return frozenKind("END_REQUIRED_BUT_UNAVAILABLE");
  }
  if (dispatchIsBusy) return frozenKind("DEFER_DISPATCH_BUSY");
  if (latest?.operation === "DIRECT_END") {
    return frozenKind("END_REQUIRED_BUT_UNAVAILABLE");
  }
  if (
    intent.sourceFetchedAt.getTime() < acceptedMetadata.sourceFetchedAt.getTime()
  ) {
    return frozenKind("NO_PUSH_STALE_SOURCE");
  }

  const unknownAfterAccepted =
    latest != null &&
    latest.state === "OUTCOME_UNKNOWN" &&
    latest.eventTimestamp > latestAccepted.eventTimestamp;
  if (unknownAfterAccepted) {
    if (latest.operation === "START") return frozenKind("DEFER_OUTCOME_UNKNOWN");
    const currentEventTimestamp = Math.floor(decisionAt.getTime() / 1_000);
    if (
      latest.publicationMetadata != null &&
      intent.sourceFetchedAt.getTime() <
        latest.publicationMetadata.sourceFetchedAt.getTime()
    ) {
      return frozenKind("NO_PUSH_STALE_SOURCE");
    }
    if (
      currentEventTimestamp > latest.eventTimestamp &&
      latest.publicationMetadata != null &&
      latest.publicationMetadata.visibleContentFingerprint !==
        intent.visibleContentFingerprint
    ) {
      if (!freshStaleDateIsSafe(intent, decisionAt, config)) {
        return frozenKind("NO_PUSH_STALE_SOURCE");
      }
      return sendUpdate("UPDATE_CONTENT", intent);
    }
    if (
      config.unknownUpdateReconciliation === "ENABLED" &&
      currentEventTimestamp > latest.eventTimestamp &&
      freshStaleDateIsSafe(intent, decisionAt, config)
    ) {
      return sendUpdate("UPDATE_RECONCILIATION", intent);
    }
    return frozenKind("DEFER_OUTCOME_UNKNOWN");
  }

  if (
    acceptedMetadata.visibleContentFingerprint !== intent.visibleContentFingerprint
  ) {
    if (!freshStaleDateIsSafe(intent, decisionAt, config)) {
      return frozenKind("NO_PUSH_STALE_SOURCE");
    }
    return sendUpdate("UPDATE_CONTENT", intent);
  }

  if (intent.contentState.freshness !== "FRESH") {
    return frozenKind("NO_PUSH_UNCHANGED");
  }
  if (
    intent.sourceFetchedAt.getTime() === acceptedMetadata.sourceFetchedAt.getTime() ||
    input.frequentPushes !== "ENABLED"
  ) {
    return frozenKind("NO_PUSH_UNCHANGED");
  }
  if (
    acceptedMetadata.staleAt == null ||
    intent.staleAt.getTime() <= acceptedMetadata.staleAt.getTime() ||
    acceptedMetadata.staleAt.getTime() - decisionAt.getTime() >
      config.freshnessHeartbeatLeadMilliseconds
  ) {
    return frozenKind("NO_PUSH_UNCHANGED");
  }
  if (
    latestAccepted.completedAt == null ||
    decisionAt.getTime() - latestAccepted.completedAt.getTime() <
      config.minimumFreshnessPublicationIntervalMilliseconds
  ) {
    return latestAccepted.completedAt == null
      ? frozenKind("DEFER_PUBLICATION_HISTORY")
      : frozenKind("NO_PUSH_UNCHANGED");
  }
  if (!freshStaleDateIsSafe(intent, decisionAt, config)) {
    return frozenKind("NO_PUSH_STALE_SOURCE");
  }
  return sendUpdate("UPDATE_FRESHNESS", intent);
}
