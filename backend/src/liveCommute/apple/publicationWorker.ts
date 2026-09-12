import {
  runStoredLiveCommuteTick,
  type RunStoredLiveCommuteTickInput,
  type StoredLiveCommuteTickResult,
} from "../coordinator.js";
import {
  type LiveCommuteSessionVersionRef,
} from "../sessionStore.js";
import {
  createActivityKitAlert,
  type ActivityKitAlert,
} from "./activityKitPayload.js";
import type { ApnsDirectLiveActivityPriority } from "./apnsProtocol.js";
import type {
  LiveActivityDeliveryBinding,
} from "./deliveryModel.js";
import type {
  LiveActivityDeliveryStore,
  LiveActivityPublicationBindingState,
} from "./deliveryStore.js";
import type {
  AuthoritativeReadyLiveCommutePublication,
} from "./deliveryPlan.js";
import type {
  LiveActivityDirectDispatchInput,
  LiveActivityDirectDispatchResult,
  LiveActivityDirectDispatcher,
} from "./directDispatcher.js";
import type {
  LiveActivityDirectDispatchHistory,
} from "./dispatchModel.js";
import type { LiveActivityDispatchStore } from "./dispatchStore.js";
import {
  decideLiveActivityPublication,
  prepareLiveActivityPublication,
  type LiveActivityFrequentPushState,
  type LiveActivityPublicationCapability,
  type LiveActivityPublicationDecision,
  type LiveActivityPublicationHistory,
  type LiveActivityPublicationPolicyConfig,
  type PreparedLiveActivityPublication,
} from "./publicationPolicy.js";

export interface LiveActivityClientPublicationState {
  readonly capability: LiveActivityPublicationCapability;
  readonly frequentPushes: LiveActivityFrequentPushState;
}

export interface LiveActivityClientPublicationStateProvider {
  getClientPublicationState(
    binding: LiveActivityDeliveryBinding,
  ): Promise<LiveActivityClientPublicationState>;
}

export interface LiveActivityStartAlertProvider {
  createStartAlert(input: {
    readonly binding: LiveActivityDeliveryBinding;
    readonly publication: AuthoritativeReadyLiveCommutePublication;
  }): Promise<ActivityKitAlert | undefined>;
}

export interface LiveActivityPublicationPriorityPolicy {
  priorityFor(input: {
    readonly decision: Extract<
      LiveActivityPublicationDecision,
      { readonly kind: "START" | "UPDATE_CONTENT" | "UPDATE_FRESHNESS" | "UPDATE_RECONCILIATION" }
    >;
    readonly binding: LiveActivityDeliveryBinding;
    readonly publication: AuthoritativeReadyLiveCommutePublication;
  }): Promise<ApnsDirectLiveActivityPriority>;
}

interface LiveActivityPublicationCycleDependencies {
  readonly deliveryStore: Pick<
    LiveActivityDeliveryStore,
    "listDeliveryBindingsForSessionVersions"
  >;
  readonly dispatchStore: Pick<
    LiveActivityDispatchStore,
    "listDirectDispatchHistoryForBindings"
  >;
  readonly dispatcher: Pick<LiveActivityDirectDispatcher, "dispatch">;
  readonly clientStateProvider: LiveActivityClientPublicationStateProvider;
  readonly startAlertProvider: LiveActivityStartAlertProvider;
  readonly priorityPolicy: LiveActivityPublicationPriorityPolicy;
  readonly policyConfig: LiveActivityPublicationPolicyConfig;
  /** Explicit maximum number of recipient pipelines active at once. */
  readonly dispatchConcurrency: number;
  /** Test seam; production callers omit it and use the accepted stored coordinator. */
  readonly runStoredTick?: (
    input: RunStoredLiveCommuteTickInput,
  ) => Promise<StoredLiveCommuteTickResult>;
}

export interface RunLiveActivityPublicationCycleInput
  extends RunStoredLiveCommuteTickInput,
    LiveActivityPublicationCycleDependencies {}

export type LiveActivityPublicationWorkerDecisionKind =
  | LiveActivityPublicationDecision["kind"]
  | "DEFER_CLIENT_STATE_UNAVAILABLE"
  | "DEFER_PRIORITY_POLICY_UNAVAILABLE";

export type SafeLiveActivityDispatchResult =
  | {
      readonly outcome: "NOT_RESERVED";
      readonly reservationStatus: Extract<
        LiveActivityDirectDispatchResult,
        { readonly outcome: "NOT_RESERVED" }
      >["reservation"]["status"];
      readonly networkAttempted: false;
    }
  | {
      readonly outcome: "NOT_SENT";
      readonly reason: Extract<
        LiveActivityDirectDispatchResult,
        { readonly outcome: "NOT_SENT" }
      >["reason"];
      readonly abortRecorded: boolean;
      readonly networkAttempted: false;
    }
  | {
      readonly outcome: "RECORDED";
      readonly state: Extract<
        LiveActivityDirectDispatchResult,
        { readonly outcome: "RECORDED" }
      >["attempt"]["state"];
      readonly networkAttempted: true;
    }
  | {
      readonly outcome: "RESULT_NOT_RECORDED";
      readonly transportOutcome: Extract<
        LiveActivityDirectDispatchResult,
        { readonly outcome: "RESULT_NOT_RECORDED" }
      >["transportOutcome"];
      readonly networkAttempted: boolean;
    }
  | {
      readonly outcome: "CALL_FAILED";
      readonly networkAttempted: "UNKNOWN";
    };

export interface LiveActivityPublicationBindingOutcome {
  readonly bindingId: string;
  readonly sessionRevision: number;
  readonly decision: LiveActivityPublicationWorkerDecisionKind;
  readonly dispatch: SafeLiveActivityDispatchResult | null;
}

export interface LiveActivityPublicationCount {
  readonly kind: string;
  readonly count: number;
}

export interface LiveActivityPublicationCycleSummary {
  readonly plannedAt: string;
  readonly generatedAt: string;
  readonly acquisitionCount: number;
  readonly publicationOutcomeCount: number;
  readonly readyPublicationGroupCount: number;
  readonly bindingCount: number;
  readonly sessionVersionWithoutBindingCount: number;
  readonly historyLookupFailed: boolean;
  readonly decisionCounts: readonly LiveActivityPublicationCount[];
  readonly dispatchOutcomeCounts: readonly LiveActivityPublicationCount[];
  readonly bindings: readonly LiveActivityPublicationBindingOutcome[];
}

interface PublicationRecipient {
  readonly prepared: PreparedLiveActivityPublication;
  readonly binding: LiveActivityDeliveryBinding;
  readonly hasUpdateTokenHistory: boolean;
}

function readClock(now: () => Date): Date {
  const value = now();
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

function positiveConcurrency(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError("dispatchConcurrency must be a positive safe integer");
  }
  return value;
}

function bindingHistoryKey(input: {
  readonly bindingId: string;
  readonly installationId: string;
  readonly sessionRevision: number;
}): string {
  return JSON.stringify([
    input.bindingId,
    input.installationId,
    input.sessionRevision,
  ]);
}

function sessionVersionIdentityKey(input: LiveCommuteSessionVersionRef): string {
  return JSON.stringify([input.installationId, input.sessionId, input.revision]);
}

function validClientState(
  input: LiveActivityClientPublicationState,
): LiveActivityClientPublicationState {
  if (
    input == null ||
    (input.capability !== "DIRECT_LEGACY" &&
      input.capability !== "DIRECT_IOS18" &&
      input.capability !== "BROADCAST_CAPABLE" &&
      input.capability !== "UNKNOWN") ||
    (input.frequentPushes !== "ENABLED" &&
      input.frequentPushes !== "DISABLED" &&
      input.frequentPushes !== "UNKNOWN")
  ) {
    throw new RangeError("client publication state is invalid");
  }
  return Object.freeze({ ...input });
}

function priority(value: ApnsDirectLiveActivityPriority): ApnsDirectLiveActivityPriority {
  if (value !== 5 && value !== 10) {
    throw new RangeError("publication priority is invalid");
  }
  return value;
}

function safeDispatchResult(
  result: LiveActivityDirectDispatchResult,
): SafeLiveActivityDispatchResult {
  if (result.outcome === "NOT_RESERVED") {
    return Object.freeze({
      outcome: result.outcome,
      reservationStatus: result.reservation.status,
      networkAttempted: false,
    });
  }
  if (result.outcome === "NOT_SENT") {
    return Object.freeze({
      outcome: result.outcome,
      reason: result.reason,
      abortRecorded: result.abortRecorded,
      networkAttempted: false,
    });
  }
  if (result.outcome === "RECORDED") {
    return Object.freeze({
      outcome: result.outcome,
      state: result.attempt.state,
      networkAttempted: true,
    });
  }
  return Object.freeze({
    outcome: result.outcome,
    transportOutcome: result.transportOutcome,
    networkAttempted: result.networkAttempted,
  });
}

function policyHistory(
  history: LiveActivityDirectDispatchHistory,
): LiveActivityPublicationHistory {
  return Object.freeze({
    latestAcceptedAttempt: history.latestAcceptedAttempt,
    latestAttempt: history.latestAttempt,
  });
}

function isSendDecision(
  decision: LiveActivityPublicationDecision,
): decision is Extract<
  LiveActivityPublicationDecision,
  { readonly kind: "START" | "UPDATE_CONTENT" | "UPDATE_FRESHNESS" | "UPDATE_RECONCILIATION" }
> {
  return (
    decision.kind === "START" ||
    decision.kind === "UPDATE_CONTENT" ||
    decision.kind === "UPDATE_FRESHNESS" ||
    decision.kind === "UPDATE_RECONCILIATION"
  );
}

function directDispatchInput(
  recipient: PublicationRecipient,
  decision: Extract<
    LiveActivityPublicationDecision,
    { readonly kind: "START" | "UPDATE_CONTENT" | "UPDATE_FRESHNESS" | "UPDATE_RECONCILIATION" }
  >,
  generatedAt: Date,
  alert: ActivityKitAlert | undefined,
  dispatchPriority: ApnsDirectLiveActivityPriority,
): LiveActivityDirectDispatchInput {
  const common = {
    installationId: recipient.binding.installationId,
    bindingId: recipient.binding.bindingId,
    sessionRevision: recipient.binding.sessionRevision,
    publication: recipient.prepared.publication,
    generatedAt,
    preparedPublication: recipient.prepared,
    priority: dispatchPriority,
  } as const;
  if (decision.kind === "START") {
    if (alert == null) throw new Error("start alert disappeared after policy decision");
    return {
      ...common,
      operation: "START",
      mode: decision.mode,
      alert,
    };
  }
  return {
    ...common,
    operation: "DIRECT_UPDATE",
  };
}

async function boundedMap<T, R>(
  values: readonly T[],
  concurrency: number,
  operation: (value: T) => Promise<R>,
): Promise<readonly R[]> {
  if (values.length === 0) return Object.freeze([]);
  const results = new Array<R>(values.length);
  let nextIndex = 0;
  const worker = async (): Promise<void> => {
    for (;;) {
      const index = nextIndex;
      if (index >= values.length) return;
      nextIndex += 1;
      results[index] = await operation(values[index] as T);
    }
  };
  const workers = Array.from(
    { length: Math.min(concurrency, values.length) },
    () => worker(),
  );
  await Promise.all(workers);
  return Object.freeze(results);
}

function counts(values: readonly string[]): readonly LiveActivityPublicationCount[] {
  const accumulated = new Map<string, number>();
  for (const value of values) {
    accumulated.set(value, (accumulated.get(value) ?? 0) + 1);
  }
  return Object.freeze(
    [...accumulated]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([kind, count]) => Object.freeze({ kind, count })),
  );
}

async function processRecipient(
  recipient: PublicationRecipient,
  history: LiveActivityDirectDispatchHistory | undefined,
  generatedAt: Date,
  input: RunLiveActivityPublicationCycleInput,
): Promise<LiveActivityPublicationBindingOutcome> {
  const base = {
    bindingId: recipient.binding.bindingId,
    sessionRevision: recipient.binding.sessionRevision,
  } as const;
  if (recipient.binding.lifecycle !== "PENDING_START") {
    return Object.freeze({
      ...base,
      decision: "END_REQUIRED_BUT_UNAVAILABLE",
      dispatch: null,
    });
  }
  if (recipient.binding.strategy === "BROADCAST_CHANNEL") {
    return Object.freeze({
      ...base,
      decision: "DEFER_BROADCAST",
      dispatch: null,
    });
  }
  if (history == null) {
    return Object.freeze({
      ...base,
      decision: "DEFER_PUBLICATION_HISTORY",
      dispatch: null,
    });
  }

  let clientState: LiveActivityClientPublicationState;
  let clientStateAvailable = true;
  try {
    clientState = validClientState(
      await input.clientStateProvider.getClientPublicationState(recipient.binding),
    );
  } catch {
    clientStateAvailable = false;
    clientState = Object.freeze({ capability: "UNKNOWN", frequentPushes: "UNKNOWN" });
  }

  const evaluatePolicy = (startAlertAvailable: boolean) =>
    decideLiveActivityPublication({
      decisionAt: generatedAt,
      intent: recipient.prepared.intent,
      history: policyHistory(history),
      deliveryStrategy: recipient.binding.strategy,
      capability: clientState.capability,
      frequentPushes: clientState.frequentPushes,
      startAlertAvailable,
      existingActivityKnown:
        recipient.binding.appleActivityId != null ||
        recipient.hasUpdateTokenHistory ||
        (history.latestAttempt != null &&
          history.latestAttempt.operation !== "START"),
      config: input.policyConfig,
    });

  let alert: ActivityKitAlert | undefined;
  let decision = evaluatePolicy(false);
  if (decision.kind === "DEFER_START_ALERT_UNAVAILABLE") {
    try {
      const provided = await input.startAlertProvider.createStartAlert({
        binding: recipient.binding,
        publication: recipient.prepared.publication,
      });
      alert = provided == null ? undefined : createActivityKitAlert(provided);
    } catch {
      alert = undefined;
    }
    if (alert != null) decision = evaluatePolicy(true);
  }
  if (!isSendDecision(decision)) {
    const decisionKind =
      !clientStateAvailable && decision.kind === "DEFER_START_CAPABILITY_UNKNOWN"
        ? "DEFER_CLIENT_STATE_UNAVAILABLE"
        : decision.kind;
    return Object.freeze({ ...base, decision: decisionKind, dispatch: null });
  }

  let dispatchPriority: ApnsDirectLiveActivityPriority;
  try {
    dispatchPriority = priority(
      await input.priorityPolicy.priorityFor({
        decision,
        binding: recipient.binding,
        publication: recipient.prepared.publication,
      }),
    );
  } catch {
    return Object.freeze({
      ...base,
      decision: "DEFER_PRIORITY_POLICY_UNAVAILABLE",
      dispatch: null,
    });
  }

  try {
    const result = await input.dispatcher.dispatch(
      directDispatchInput(
        recipient,
        decision,
        generatedAt,
        alert,
        dispatchPriority,
      ),
    );
    return Object.freeze({
      ...base,
      decision: decision.kind,
      dispatch: safeDispatchResult(result),
    });
  } catch {
    return Object.freeze({
      ...base,
      decision: decision.kind,
      dispatch: Object.freeze({
        outcome: "CALL_FAILED",
        networkAttempted: "UNKNOWN",
      }),
    });
  }
}

/**
 * Runs one explicit publication cycle. This function creates no route, timer, recurrence,
 * queue consumer, database connection, APNs transport, or production configuration.
 */
export async function runLiveActivityPublicationCycle(
  input: RunLiveActivityPublicationCycleInput,
): Promise<LiveActivityPublicationCycleSummary> {
  const dispatchConcurrency = positiveConcurrency(input.dispatchConcurrency);
  const executeStoredTick = input.runStoredTick ?? runStoredLiveCommuteTick;
  const tick = await executeStoredTick({
    sessionStore: input.sessionStore,
    now: input.now,
    transportClient: input.transportClient,
    journeyClient: input.journeyClient,
    previousSnapshots: input.previousSnapshots,
  });
  const generatedAt = readClock(input.now);
  const ready = tick.publications.filter(
    (publication): publication is AuthoritativeReadyLiveCommutePublication =>
      publication.status === "READY" || publication.status === "READY_STALE",
  );
  const prepared: readonly PreparedLiveActivityPublication[] = Object.freeze(
    ready.map((publication) =>
      prepareLiveActivityPublication({
        publication,
        generatedAt,
        config: input.policyConfig,
      }),
    ),
  );

  const uniqueSessionVersions = new Map<string, LiveCommuteSessionVersionRef>();
  for (const item of prepared) {
    for (const reference of item.publication.sessionVersions) {
      uniqueSessionVersions.set(
        sessionVersionIdentityKey(reference),
        reference,
      );
    }
  }
  const sessionVersions = Object.freeze([...uniqueSessionVersions.values()]);
  const bindingStates = await input.deliveryStore.listDeliveryBindingsForSessionVersions(
    sessionVersions,
  );
  const bindingsBySessionVersion =
    new Map<string, LiveActivityPublicationBindingState>();
  for (const bindingState of bindingStates) {
    const { binding } = bindingState;
    bindingsBySessionVersion.set(
      sessionVersionIdentityKey({
        installationId: binding.installationId,
        sessionId: binding.sessionId,
        revision: binding.sessionRevision,
      }),
      bindingState,
    );
  }

  const recipients: PublicationRecipient[] = [];
  let sessionVersionWithoutBindingCount = 0;
  for (const item of prepared) {
    for (const reference of item.publication.sessionVersions) {
      const bindingState = bindingsBySessionVersion.get(
        sessionVersionIdentityKey(reference),
      );
      if (bindingState == null) {
        sessionVersionWithoutBindingCount += 1;
      } else {
        recipients.push(
          Object.freeze({ prepared: item, ...bindingState }),
        );
      }
    }
  }

  let historyLookupFailed = false;
  let histories: readonly LiveActivityDirectDispatchHistory[] = [];
  try {
    histories = await input.dispatchStore.listDirectDispatchHistoryForBindings(
      recipients.map(({ binding }) => ({
        bindingId: binding.bindingId,
        installationId: binding.installationId,
        sessionRevision: binding.sessionRevision,
      })),
    );
  } catch {
    historyLookupFailed = true;
  }
  const historiesByBinding = new Map(
    histories.map((history) => [bindingHistoryKey(history), history] as const),
  );
  const outcomes = await boundedMap(
    recipients,
    dispatchConcurrency,
    async (recipient) =>
      await processRecipient(
        recipient,
        historyLookupFailed
          ? undefined
          : historiesByBinding.get(bindingHistoryKey(recipient.binding)),
        generatedAt,
        input,
      ),
  );

  return Object.freeze({
    plannedAt: tick.plannedAt,
    generatedAt: generatedAt.toISOString(),
    acquisitionCount: tick.acquisitions.length,
    publicationOutcomeCount: tick.publications.length,
    readyPublicationGroupCount: prepared.length,
    bindingCount: outcomes.length,
    sessionVersionWithoutBindingCount,
    historyLookupFailed,
    decisionCounts: counts(outcomes.map(({ decision }) => decision)),
    dispatchOutcomeCounts: counts(
      outcomes.map(({ dispatch }) => dispatch?.outcome ?? "NOT_REQUESTED"),
    ),
    bindings: Object.freeze(outcomes),
  });
}
