import { randomUUID } from "node:crypto";
import {
  activityKitEpochSeconds,
  type ActivityKitAlert,
  type ActivityKitStartMode,
} from "./activityKitPayload.js";
import {
  classifyApnsDeviceResponse,
  type ApnsDirectLiveActivityPriority,
} from "./apnsProtocol.js";
import {
  ApnsProviderTokenCacheError,
  type ApnsProviderTokenLease,
  type LazyApnsProviderTokenCache,
} from "./apnsProviderTokenCache.js";
import type {
  ApnsTransport,
  ApnsTransportNotAttemptedReason,
  ApnsTransportResult,
} from "./apnsTransport.js";
import {
  buildDirectLiveActivityEndPlan,
  buildDirectLiveActivityUpdatePlan,
  buildLiveActivityStartPlan,
  type AuthoritativeReadyLiveCommutePublication,
  type LiveActivityDeliveryPlan,
} from "./deliveryPlan.js";
import type {
  InternalDirectLiveActivityUpdateTarget,
  InternalLiveActivityStartTarget,
  LiveActivityDeliveryResolver,
} from "./deliveryResolver.js";
import {
  assertPreparedLiveActivityPublication,
  type PreparedLiveActivityPublication,
} from "./publicationPolicy.js";
import {
  normalizedLiveActivityDispatchUuid,
  sameLiveActivityDispatchTokenGeneration,
  type LiveActivityDirectDispatchAttempt,
  type LiveActivityDispatchRetryAdvice,
} from "./dispatchModel.js";
import type {
  LiveActivityDispatchStore,
  ReserveLiveActivityDirectDispatchResult,
} from "./dispatchStore.js";

export const APNS_SERVER_RETRY_NOT_BEFORE_MILLISECONDS = 15 * 60 * 1_000;

type DirectStartMode = Extract<
  ActivityKitStartMode,
  { readonly kind: "DIRECT_LEGACY" | "DIRECT_IOS_18" }
>;

interface LiveActivityDirectDispatchInputBase {
  readonly installationId: string;
  readonly bindingId: string;
  readonly sessionRevision: number;
  readonly publication: AuthoritativeReadyLiveCommutePublication;
  /** The authoritative content-generation instant and ActivityKit ordering value. */
  readonly generatedAt: Date;
  /** Opaque group-level mapping/fingerprint reused across recipient bindings. */
  readonly preparedPublication?: PreparedLiveActivityPublication;
  readonly priority: ApnsDirectLiveActivityPriority;
  readonly expiration?: number;
  readonly collapseId?: string;
  /** Optional only to support an already allocated durable correlation identifier. */
  readonly dispatchId?: string;
  /** Optional only to support an already allocated APNs request identifier. */
  readonly apnsRequestId?: string;
}

export interface LiveActivityDirectStartDispatchInput
  extends LiveActivityDirectDispatchInputBase {
  readonly operation: "START";
  readonly mode: DirectStartMode;
  readonly alert: ActivityKitAlert;
}

export interface LiveActivityDirectUpdateDispatchInput
  extends LiveActivityDirectDispatchInputBase {
  readonly operation: "DIRECT_UPDATE";
  readonly staleAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

export interface LiveActivityDirectEndDispatchInput
  extends LiveActivityDirectDispatchInputBase {
  readonly operation: "DIRECT_END";
  readonly dismissalAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

export type LiveActivityDirectDispatchInput =
  | LiveActivityDirectStartDispatchInput
  | LiveActivityDirectUpdateDispatchInput
  | LiveActivityDirectEndDispatchInput;

type ReservationRejection = Exclude<
  ReserveLiveActivityDirectDispatchResult,
  { readonly status: "RESERVED" }
>;

export type LiveActivityDirectDispatchPreparationFailure =
  | "TARGET_NO_LONGER_AUTHORIZED"
  | "PROVIDER_TOKEN_UNAVAILABLE"
  | "REQUEST_PREPARATION_FAILED"
  | "STORE_UNAVAILABLE";

export type LiveActivityDirectDispatchResult =
  | {
      readonly outcome: "NOT_RESERVED";
      readonly reservation: ReservationRejection;
      readonly networkAttempted: false;
    }
  | {
      readonly outcome: "NOT_SENT";
      readonly reason:
        | LiveActivityDirectDispatchPreparationFailure
        | "ABORTED_AT_CLAIM"
        | "SUPERSEDED_AT_CLAIM"
        | "MISSING_AT_CLAIM"
        | ApnsTransportNotAttemptedReason;
      readonly attempt: LiveActivityDirectDispatchAttempt | null;
      readonly abortRecorded: boolean;
      readonly networkAttempted: false;
    }
  | {
      readonly outcome: "RECORDED";
      readonly attempt: LiveActivityDirectDispatchAttempt;
      readonly networkAttempted: true;
    }
  | {
      /** APNs may have observed the request, but its result could not be durably recorded. */
      readonly outcome: "RESULT_NOT_RECORDED";
      readonly dispatchId: string;
      readonly apnsRequestId: string;
      readonly transportOutcome: ApnsTransportResult["outcome"];
      readonly networkAttempted: boolean;
    };

interface DirectDeliveryTargetResolver {
  resolveStartTarget(input: {
    readonly installationId: string;
    readonly bindingId: string;
  }): ReturnType<LiveActivityDeliveryResolver["resolveStartTarget"]>;
  resolveUpdateTarget(input: {
    readonly installationId: string;
    readonly bindingId: string;
  }): ReturnType<LiveActivityDeliveryResolver["resolveUpdateTarget"]>;
}

interface ProviderTokenCache {
  getToken(): ApnsProviderTokenLease;
  invalidateIfCurrent(lease: ApnsProviderTokenLease): boolean;
}

export interface LiveActivityDirectDispatcherOptions {
  readonly store: LiveActivityDispatchStore;
  readonly resolver: DirectDeliveryTargetResolver;
  readonly providerTokenCache: Pick<
    LazyApnsProviderTokenCache,
    "getToken" | "invalidateIfCurrent"
  >;
  readonly transport: ApnsTransport;
  readonly bundleId: string;
  readonly now?: () => Date;
  readonly createUuid?: () => string;
}

interface CompletionDecision {
  readonly state: "ACCEPTED" | "REJECTED" | "RETRYABLE" | "OUTCOME_UNKNOWN";
  readonly apnsStatus: number | null;
  readonly apnsReason: string | null;
  readonly retryAdvice: LiveActivityDispatchRetryAdvice;
  readonly retryNotBefore: Date | null;
  readonly invalidateExactTokenGeneration: boolean;
}

function validInstant(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function matchingTarget(
  target: InternalLiveActivityStartTarget | InternalDirectLiveActivityUpdateTarget,
  attempt: LiveActivityDirectDispatchAttempt,
): boolean {
  const expectedKind = attempt.operation === "START" ? "START" : "DIRECT_UPDATE";
  return (
    target.kind === expectedKind &&
    target.strategy === "DIRECT_TOKEN" &&
    target.bindingId === attempt.bindingId &&
    target.sessionVersion.installationId === attempt.installationId &&
    target.sessionVersion.revision === attempt.sessionRevision &&
    target.environment === attempt.environment &&
    sameLiveActivityDispatchTokenGeneration(
      target.tokenGeneration,
      attempt.tokenGeneration,
    )
  );
}

function preparationAdvice(
  reason: LiveActivityDirectDispatchPreparationFailure,
): LiveActivityDispatchRetryAdvice {
  if (reason === "PROVIDER_TOKEN_UNAVAILABLE") return "REFRESH_PROVIDER_TOKEN";
  if (reason === "REQUEST_PREPARATION_FAILED") {
    return "OPERATOR_CONFIGURATION_REQUIRED";
  }
  return "NO_RETRY";
}

function completionDecision(
  result: Exclude<ApnsTransportResult, { readonly outcome: "NOT_ATTEMPTED" }>,
  completedAt: Date,
): CompletionDecision {
  if (result.outcome === "OUTCOME_UNKNOWN") {
    return Object.freeze({
      state: "OUTCOME_UNKNOWN",
      apnsStatus: null,
      apnsReason: result.reason,
      retryAdvice: "OUTCOME_UNKNOWN",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
  }

  const response = classifyApnsDeviceResponse(result.response);
  if (response.disposition === "ACCEPTED") {
    return Object.freeze({
      state: "ACCEPTED",
      apnsStatus: response.statusCode,
      apnsReason: response.reason,
      retryAdvice: "NO_RETRY",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
  }
  if (response.disposition === "DEVICE_TOKEN_INVALID") {
    return Object.freeze({
      state: "REJECTED",
      apnsStatus: response.statusCode,
      apnsReason: response.reason,
      retryAdvice: "PERMANENT_DESTINATION_FAILURE",
      retryNotBefore: null,
      invalidateExactTokenGeneration: true,
    });
  }
  if (response.disposition === "AUTHENTICATION_ERROR") {
    const expired = response.reason === "ExpiredProviderToken";
    return Object.freeze({
      state: expired ? "RETRYABLE" : "REJECTED",
      apnsStatus: response.statusCode,
      apnsReason: response.reason,
      retryAdvice: expired
        ? "REFRESH_PROVIDER_TOKEN"
        : "OPERATOR_CONFIGURATION_REQUIRED",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
  }
  if (response.disposition === "THROTTLED") {
    const providerRotationFailure =
      response.reason === "TooManyProviderTokenUpdates";
    return Object.freeze({
      state: providerRotationFailure ? "REJECTED" : "RETRYABLE",
      apnsStatus: response.statusCode,
      apnsReason: response.reason,
      retryAdvice: providerRotationFailure
        ? "OPERATOR_CONFIGURATION_REQUIRED"
        : "RETRY_THROTTLED",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
  }
  if (response.disposition === "TRANSIENT_SERVER_ERROR") {
    return Object.freeze({
      state: "RETRYABLE",
      apnsStatus: response.statusCode,
      apnsReason: response.reason,
      retryAdvice: "RETRY_AFTER_APPLE_BACKOFF",
      retryNotBefore: new Date(
        completedAt.getTime() + APNS_SERVER_RETRY_NOT_BEFORE_MILLISECONDS,
      ),
      invalidateExactTokenGeneration: false,
    });
  }
  if (response.disposition === "REQUEST_REJECTED") {
    const payloadFailure =
      response.statusCode === 413 || response.reason === "PayloadTooLarge";
    return Object.freeze({
      state: "REJECTED",
      apnsStatus: response.statusCode,
      apnsReason: response.reason,
      retryAdvice: payloadFailure
        ? "PERMANENT_PAYLOAD_FAILURE"
        : "OPERATOR_CONFIGURATION_REQUIRED",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
  }
  return Object.freeze({
    state: "OUTCOME_UNKNOWN",
    apnsStatus: response.statusCode,
    apnsReason: response.reason,
    retryAdvice: "OUTCOME_UNKNOWN",
    retryNotBefore: null,
    invalidateExactTokenGeneration: false,
  });
}

/**
 * Explicit, one-shot direct delivery orchestration. Construction and import perform no
 * network, database, signing, timer, or filesystem work.
 */
export class LiveActivityDirectDispatcher {
  readonly #store: LiveActivityDispatchStore;
  readonly #resolver: DirectDeliveryTargetResolver;
  readonly #providerTokenCache: ProviderTokenCache;
  readonly #transport: ApnsTransport;
  readonly #bundleId: string;
  readonly #now: () => Date;
  readonly #createUuid: () => string;

  constructor(options: LiveActivityDirectDispatcherOptions) {
    if (
      options == null ||
      typeof options.store?.reserveDirectDispatch !== "function" ||
      typeof options.resolver?.resolveStartTarget !== "function" ||
      typeof options.resolver?.resolveUpdateTarget !== "function" ||
      typeof options.providerTokenCache?.getToken !== "function" ||
      typeof options.providerTokenCache?.invalidateIfCurrent !== "function" ||
      typeof options.transport?.send !== "function" ||
      typeof options.bundleId !== "string" ||
      options.bundleId.length === 0 ||
      (options.now != null && typeof options.now !== "function") ||
      (options.createUuid != null && typeof options.createUuid !== "function")
    ) {
      throw new TypeError("direct Live Activity dispatcher configuration is invalid");
    }
    this.#store = options.store;
    this.#resolver = options.resolver;
    this.#providerTokenCache = options.providerTokenCache;
    this.#transport = options.transport;
    this.#bundleId = options.bundleId;
    this.#now = options.now ?? (() => new Date());
    this.#createUuid = options.createUuid ?? randomUUID;
  }

  async dispatch(
    input: LiveActivityDirectDispatchInput,
  ): Promise<LiveActivityDirectDispatchResult> {
    if (input.preparedPublication != null) {
      assertPreparedLiveActivityPublication(
        input.preparedPublication,
        input.publication,
        input.generatedAt,
      );
    }
    const eventTimestamp = activityKitEpochSeconds(input.generatedAt, "generatedAt");
    const dispatchId = normalizedLiveActivityDispatchUuid(
      input.dispatchId ?? this.#createUuid(),
      "dispatchId",
    );
    const apnsRequestId = normalizedLiveActivityDispatchUuid(
      input.apnsRequestId ?? this.#createUuid(),
      "apnsRequestId",
    );
    const reservedAt = validInstant(this.#now(), "now()");

    let reservation: ReserveLiveActivityDirectDispatchResult;
    try {
      reservation = await this.#store.reserveDirectDispatch({
        installationId: input.installationId,
        bindingId: input.bindingId,
        sessionRevision: input.sessionRevision,
        operation: input.operation,
        eventTimestamp,
        dispatchId,
        apnsRequestId,
        reservedAt,
        publicationMetadata:
          input.preparedPublication == null
            ? undefined
            : {
                visibleContentFingerprint:
                  input.preparedPublication.intent.visibleContentFingerprint,
                sourceFetchedAt: input.preparedPublication.intent.sourceFetchedAt,
                staleAt:
                  input.operation === "DIRECT_UPDATE" &&
                  input.preparedPublication.intent.contentState.freshness === "FRESH"
                    ? input.preparedPublication.intent.staleAt
                    : null,
              },
      });
    } catch {
      return Object.freeze({
        outcome: "NOT_SENT",
        reason: "STORE_UNAVAILABLE",
        attempt: null,
        abortRecorded: false,
        networkAttempted: false,
      });
    }
    if (reservation.status !== "RESERVED") {
      return Object.freeze({
        outcome: "NOT_RESERVED",
        reservation,
        networkAttempted: false,
      });
    }

    let lease: ApnsProviderTokenLease;
    let plan: LiveActivityDeliveryPlan;
    let fingerprint: string;
    try {
      const target =
        input.operation === "START"
          ? await this.#resolver.resolveStartTarget({
              installationId: input.installationId,
              bindingId: input.bindingId,
            })
          : await this.#resolver.resolveUpdateTarget({
              installationId: input.installationId,
              bindingId: input.bindingId,
            });
      if (
        target == null ||
        target.kind === "BROADCAST_CHANNEL_REQUIRED" ||
        !matchingTarget(target, reservation.attempt)
      ) {
        return await this.#notSentAfterReservation(
          reservation.attempt,
          "TARGET_NO_LONGER_AUTHORIZED",
        );
      }

      try {
        lease = this.#providerTokenCache.getToken();
      } catch (error) {
        const cacheError =
          error instanceof ApnsProviderTokenCacheError ? error : undefined;
        const retryNotBefore =
          cacheError?.retryNotBeforeEpochSeconds == null
            ? null
            : new Date(cacheError.retryNotBeforeEpochSeconds * 1_000);
        const retryAdvice =
          cacheError?.code === "APNS_PROVIDER_TOKEN_REFRESH_TOO_SOON"
            ? "REFRESH_PROVIDER_TOKEN"
            : "OPERATOR_CONFIGURATION_REQUIRED";
        return await this.#notSentAfterReservation(
          reservation.attempt,
          "PROVIDER_TOKEN_UNAVAILABLE",
          retryAdvice,
          retryNotBefore,
        );
      }
      plan = this.#buildPlan(input, target, lease, apnsRequestId);
      if (plan.eventTimestamp !== reservation.attempt.eventTimestamp) {
        throw new Error("ActivityKit event timestamp changed during planning");
      }
      fingerprint = plan.payloadFingerprint;
    } catch {
      return await this.#notSentAfterReservation(
        reservation.attempt,
        "REQUEST_PREPARATION_FAILED",
      );
    }

    let claim;
    try {
      claim = await this.#store.claimDirectDispatch({
        installationId: input.installationId,
        dispatchId,
        payloadFingerprint: fingerprint,
        claimedAt: validInstant(this.#now(), "now()"),
      });
    } catch {
      return await this.#notSentAfterReservation(
        reservation.attempt,
        "STORE_UNAVAILABLE",
      );
    }
    if (claim.status !== "CLAIMED") {
      const reason =
        claim.status === "ABORTED"
          ? "ABORTED_AT_CLAIM"
          : claim.status === "SUPERSEDED"
            ? "SUPERSEDED_AT_CLAIM"
            : "MISSING_AT_CLAIM";
      return Object.freeze({
        outcome: "NOT_SENT",
        reason,
        attempt: claim.attempt,
        abortRecorded: claim.status === "ABORTED",
        networkAttempted: false,
      });
    }

    let transportResult: ApnsTransportResult;
    try {
      transportResult = await this.#transport.send(plan.request);
    } catch {
      transportResult = Object.freeze({
        outcome: "OUTCOME_UNKNOWN",
        reason: "SESSION_ERROR",
      });
    }
    let completedAt: Date;
    let completion: CompletionDecision;
    try {
      completedAt = validInstant(this.#now(), "now()");
      if (transportResult.outcome === "NOT_ATTEMPTED") {
        const recorded = await this.#store.completeDirectDispatch({
          installationId: input.installationId,
          dispatchId,
          completedAt,
          state: "ABORTED",
          apnsStatus: null,
          apnsReason: null,
          retryAdvice: "OPERATOR_CONFIGURATION_REQUIRED",
          retryNotBefore: null,
          invalidateExactTokenGeneration: false,
        });
        if (recorded.status === "COMPLETED") {
          return Object.freeze({
            outcome: "NOT_SENT",
            reason: transportResult.reason,
            attempt: recorded.attempt,
            abortRecorded: true,
            networkAttempted: false,
          });
        }
        return Object.freeze({
          outcome: "RESULT_NOT_RECORDED",
          dispatchId,
          apnsRequestId,
          transportOutcome: transportResult.outcome,
          networkAttempted: false,
        });
      }
      completion = completionDecision(transportResult, completedAt);
    } catch {
      return Object.freeze({
        outcome: "RESULT_NOT_RECORDED",
        dispatchId,
        apnsRequestId,
        transportOutcome: transportResult.outcome,
        networkAttempted: transportResult.outcome !== "NOT_ATTEMPTED",
      });
    }
    if (
      transportResult.outcome === "APNS_RESPONSE" &&
      transportResult.response.statusCode === 403 &&
      transportResult.response.reason === "ExpiredProviderToken"
    ) {
      try {
        this.#providerTokenCache.invalidateIfCurrent(lease);
      } catch {
        // Cache state is process-local and must not prevent durable response recording.
      }
    }

    try {
      const recorded = await this.#store.completeDirectDispatch({
        installationId: input.installationId,
        dispatchId,
        completedAt,
        ...completion,
      });
      if (recorded.status === "COMPLETED") {
        return Object.freeze({
          outcome: "RECORDED",
          attempt: recorded.attempt,
          networkAttempted: true,
        });
      }
    } catch {
      // A request may already have reached APNs. Preserve that ambiguity for the caller.
    }
    return Object.freeze({
      outcome: "RESULT_NOT_RECORDED",
      dispatchId,
      apnsRequestId,
      transportOutcome: transportResult.outcome,
      networkAttempted: true,
    });
  }

  #buildPlan(
    input: LiveActivityDirectDispatchInput,
    target: InternalLiveActivityStartTarget | InternalDirectLiveActivityUpdateTarget,
    lease: ApnsProviderTokenLease,
    apnsRequestId: string,
  ): LiveActivityDeliveryPlan {
    const request = {
      target,
      publication: input.publication,
      generatedAt: input.generatedAt,
      preparedContentState: input.preparedPublication?.preparedContentState,
      bundleId: this.#bundleId,
      providerToken: lease.token,
      priority: input.priority,
      expiration: input.expiration,
      apnsId: apnsRequestId,
      collapseId: input.collapseId,
    } as const;
    if (input.operation === "START") {
      if (target.kind !== "START") throw new Error("start target is unavailable");
      return buildLiveActivityStartPlan({
        ...request,
        target,
        mode: input.mode,
        alert: input.alert,
      });
    }
    if (target.kind !== "DIRECT_UPDATE") {
      throw new Error("direct update target is unavailable");
    }
    if (input.operation === "DIRECT_UPDATE") {
      return buildDirectLiveActivityUpdatePlan({
        ...request,
        target,
        staleAt:
          input.preparedPublication == null
            ? input.staleAt
            : input.preparedPublication.intent.contentState.freshness === "FRESH"
              ? input.preparedPublication.intent.staleAt
              : undefined,
        alert: input.alert,
      });
    }
    return buildDirectLiveActivityEndPlan({
      ...request,
      target,
      dismissalAt: input.dismissalAt,
      alert: input.alert,
    });
  }

  async #notSentAfterReservation(
    attempt: LiveActivityDirectDispatchAttempt,
    reason: LiveActivityDirectDispatchPreparationFailure,
    retryAdvice: LiveActivityDispatchRetryAdvice = preparationAdvice(reason),
    retryNotBefore: Date | null = null,
  ): Promise<LiveActivityDirectDispatchResult> {
    let aborted: LiveActivityDirectDispatchAttempt | undefined;
    try {
      aborted = await this.#store.abortDirectDispatch({
        installationId: attempt.installationId,
        dispatchId: attempt.dispatchId,
        completedAt: validInstant(this.#now(), "now()"),
        retryAdvice,
        retryNotBefore,
      });
    } catch {
      // No network attempt occurred; expose only whether the abort was durably recorded.
    }
    return Object.freeze({
      outcome: "NOT_SENT",
      reason,
      attempt: aborted ?? attempt,
      abortRecorded: aborted?.state === "ABORTED",
      networkAttempted: false,
    });
  }
}

export function createLiveActivityDirectDispatcher(
  options: LiveActivityDirectDispatcherOptions,
): LiveActivityDirectDispatcher {
  return new LiveActivityDirectDispatcher(options);
}
