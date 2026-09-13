import {
  normalizedAppleDeliveryIdentifier,
  normalizedApplePushEnvironment,
  normalizedLiveActivityBindingId,
  positiveActivityKitGeneration,
  type ApplePushEnvironment,
} from "./deliveryModel.js";

export const LIVE_ACTIVITY_EVENT_TIMESTAMP_MAX = Number.MAX_SAFE_INTEGER;

const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SHA256_HEX_PATTERN = /^[0-9a-f]{64}$/;
const SAFE_APNS_REASON_PATTERN = /^[\x20-\x7e]+$/;

export type LiveActivityDirectDispatchOperation =
  | "START"
  | "DIRECT_UPDATE"
  | "DIRECT_END";

export type LiveActivityDispatchState =
  | "RESERVED"
  | "IN_FLIGHT"
  | "ACCEPTED"
  | "REJECTED"
  | "RETRYABLE"
  | "OUTCOME_UNKNOWN"
  | "ABORTED"
  | "SUPERSEDED";

export type LiveActivityDispatchRetryAdvice =
  | "NO_RETRY"
  | "RETRY_AFTER_APPLE_BACKOFF"
  | "RETRY_THROTTLED"
  | "REFRESH_PROVIDER_TOKEN"
  | "PERMANENT_DESTINATION_FAILURE"
  | "PERMANENT_PAYLOAD_FAILURE"
  | "OUTCOME_UNKNOWN"
  | "OPERATOR_CONFIGURATION_REQUIRED";

export type LiveActivityPostSendAuthority =
  | "NOT_CHECKED"
  | "MATCHED"
  | "CHANGED";

export type LiveActivityTokenInvalidationOutcome =
  | "NOT_APPLICABLE"
  | "INVALIDATED_EXACT_GENERATION"
  | "GENERATION_NO_LONGER_CURRENT"
  | "ALREADY_INVALIDATED";

export interface LiveActivityDispatchTokenGeneration {
  readonly clientGeneration: number;
  readonly serverRevision: number;
}

/** Safe semantic publication metadata. It deliberately contains no ContentState or token. */
export interface LiveActivityPublicationMetadata {
  readonly visibleContentFingerprint: string;
  readonly sourceFetchedAt: Date;
  readonly staleAt: Date | null;
}

export interface LiveActivityDispatchCursor {
  readonly bindingId: string;
  readonly installationId: string;
  readonly sessionRevision: number;
  readonly lastReservedEventTimestamp: number;
  readonly terminalIntentEventTimestamp: number | null;
  readonly createdAt: Date;
  readonly updatedAt: Date;
}

export interface LiveActivityDirectDispatchAttempt {
  readonly dispatchId: string;
  readonly bindingId: string;
  readonly installationId: string;
  readonly sessionRevision: number;
  readonly operation: LiveActivityDirectDispatchOperation;
  readonly eventTimestamp: number;
  readonly tokenGeneration: LiveActivityDispatchTokenGeneration;
  readonly environment: ApplePushEnvironment;
  readonly apnsRequestId: string;
  readonly payloadFingerprint: string | null;
  /** Nullable for Phase 4B/legacy attempts that predate durable publication policy. */
  readonly publicationMetadata: LiveActivityPublicationMetadata | null;
  readonly state: LiveActivityDispatchState;
  readonly apnsStatus: number | null;
  readonly apnsReason: string | null;
  readonly retryAdvice: LiveActivityDispatchRetryAdvice | null;
  readonly retryNotBefore: Date | null;
  readonly postSendAuthority: LiveActivityPostSendAuthority;
  readonly tokenInvalidationOutcome: LiveActivityTokenInvalidationOutcome;
  readonly createdAt: Date;
  readonly inFlightAt: Date | null;
  readonly completedAt: Date | null;
}

export interface LiveActivityDispatchBindingReference {
  readonly bindingId: string;
  readonly installationId: string;
  readonly sessionRevision: number;
}

export interface LiveActivityDirectDispatchHistory
  extends LiveActivityDispatchBindingReference {
  readonly latestAcceptedAttempt: LiveActivityDirectDispatchAttempt | null;
  readonly latestAttempt: LiveActivityDirectDispatchAttempt | null;
}

export function normalizedLiveActivityDispatchUuid(
  value: string,
  field: string,
): string {
  if (typeof value !== "string" || !CANONICAL_UUID_PATTERN.test(value)) {
    throw new RangeError(`${field} is invalid`);
  }
  return value.toLowerCase();
}

export function normalizedLiveActivityDispatchInstant(
  value: Date,
  field: string,
): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

export function normalizedLiveActivityEventTimestamp(
  value: number,
  field = "eventTimestamp",
): number {
  if (
    !Number.isSafeInteger(value) ||
    value < 0 ||
    value > LIVE_ACTIVITY_EVENT_TIMESTAMP_MAX
  ) {
    throw new RangeError(`${field} must be a nonnegative safe epoch-second integer`);
  }
  return value;
}

export function normalizedPayloadFingerprint(value: string): string {
  if (typeof value !== "string" || !SHA256_HEX_PATTERN.test(value)) {
    throw new RangeError("payloadFingerprint must be a lowercase SHA-256 value");
  }
  return value;
}

export function createLiveActivityPublicationMetadata(
  input: LiveActivityPublicationMetadata,
): LiveActivityPublicationMetadata {
  if (input == null || typeof input !== "object") {
    throw new TypeError("publication metadata must be an object");
  }
  const sourceFetchedAt = normalizedLiveActivityDispatchInstant(
    input.sourceFetchedAt,
    "publicationMetadata.sourceFetchedAt",
  );
  const staleAt =
    input.staleAt == null
      ? null
      : normalizedLiveActivityDispatchInstant(
          input.staleAt,
          "publicationMetadata.staleAt",
        );
  if (staleAt != null && staleAt.getTime() < sourceFetchedAt.getTime()) {
    throw new RangeError("publication staleAt cannot precede sourceFetchedAt");
  }
  return Object.freeze({
    visibleContentFingerprint: normalizedPayloadFingerprint(
      input.visibleContentFingerprint,
    ),
    sourceFetchedAt,
    staleAt,
  });
}

export function createLiveActivityDispatchBindingReference(
  input: LiveActivityDispatchBindingReference,
): LiveActivityDispatchBindingReference {
  if (input == null || typeof input !== "object") {
    throw new TypeError("dispatch binding reference must be an object");
  }
  return Object.freeze({
    bindingId: normalizedLiveActivityBindingId(input.bindingId),
    installationId: normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    ),
    sessionRevision: positiveActivityKitGeneration(
      input.sessionRevision,
      "sessionRevision",
    ),
  });
}

export function normalizedSafeApnsReason(value: string | null): string | null {
  if (value == null) return null;
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > 128 ||
    !SAFE_APNS_REASON_PATTERN.test(value)
  ) {
    throw new RangeError("APNs reason is invalid");
  }
  return value;
}

export function createLiveActivityDispatchCursor(
  input: LiveActivityDispatchCursor,
): LiveActivityDispatchCursor {
  if (input == null || typeof input !== "object") {
    throw new TypeError("dispatch cursor must be an object");
  }
  const createdAt = normalizedLiveActivityDispatchInstant(
    input.createdAt,
    "cursor.createdAt",
  );
  const updatedAt = normalizedLiveActivityDispatchInstant(
    input.updatedAt,
    "cursor.updatedAt",
  );
  const lastReservedEventTimestamp = normalizedLiveActivityEventTimestamp(
    input.lastReservedEventTimestamp,
    "lastReservedEventTimestamp",
  );
  const terminalIntentEventTimestamp =
    input.terminalIntentEventTimestamp == null
      ? null
      : normalizedLiveActivityEventTimestamp(
          input.terminalIntentEventTimestamp,
          "terminalIntentEventTimestamp",
        );
  if (updatedAt.getTime() < createdAt.getTime()) {
    throw new RangeError("cursor updatedAt cannot precede createdAt");
  }
  if (
    terminalIntentEventTimestamp != null &&
    terminalIntentEventTimestamp > lastReservedEventTimestamp
  ) {
    throw new RangeError("terminal intent cannot follow the reservation cursor");
  }
  return Object.freeze({
    bindingId: normalizedLiveActivityBindingId(input.bindingId),
    installationId: normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    ),
    sessionRevision: positiveActivityKitGeneration(
      input.sessionRevision,
      "sessionRevision",
    ),
    lastReservedEventTimestamp,
    terminalIntentEventTimestamp,
    createdAt,
    updatedAt,
  });
}

export function normalizedLiveActivityDispatchOperation(
  value: LiveActivityDirectDispatchOperation,
): LiveActivityDirectDispatchOperation {
  if (
    value !== "START" &&
    value !== "DIRECT_UPDATE" &&
    value !== "DIRECT_END"
  ) {
    throw new RangeError("dispatch operation is invalid");
  }
  return value;
}

function normalizedDispatchState(value: LiveActivityDispatchState): LiveActivityDispatchState {
  if (
    value !== "RESERVED" &&
    value !== "IN_FLIGHT" &&
    value !== "ACCEPTED" &&
    value !== "REJECTED" &&
    value !== "RETRYABLE" &&
    value !== "OUTCOME_UNKNOWN" &&
    value !== "ABORTED" &&
    value !== "SUPERSEDED"
  ) {
    throw new RangeError("dispatch state is invalid");
  }
  return value;
}

function normalizedRetryAdvice(
  value: LiveActivityDispatchRetryAdvice | null,
): LiveActivityDispatchRetryAdvice | null {
  if (
    value != null &&
    value !== "NO_RETRY" &&
    value !== "RETRY_AFTER_APPLE_BACKOFF" &&
    value !== "RETRY_THROTTLED" &&
    value !== "REFRESH_PROVIDER_TOKEN" &&
    value !== "PERMANENT_DESTINATION_FAILURE" &&
    value !== "PERMANENT_PAYLOAD_FAILURE" &&
    value !== "OUTCOME_UNKNOWN" &&
    value !== "OPERATOR_CONFIGURATION_REQUIRED"
  ) {
    throw new RangeError("dispatch retry advice is invalid");
  }
  return value;
}

function normalizedPostSendAuthority(
  value: LiveActivityPostSendAuthority,
): LiveActivityPostSendAuthority {
  if (value !== "NOT_CHECKED" && value !== "MATCHED" && value !== "CHANGED") {
    throw new RangeError("post-send authority is invalid");
  }
  return value;
}

function normalizedTokenInvalidationOutcome(
  value: LiveActivityTokenInvalidationOutcome,
): LiveActivityTokenInvalidationOutcome {
  if (
    value !== "NOT_APPLICABLE" &&
    value !== "INVALIDATED_EXACT_GENERATION" &&
    value !== "GENERATION_NO_LONGER_CURRENT" &&
    value !== "ALREADY_INVALIDATED"
  ) {
    throw new RangeError("token invalidation outcome is invalid");
  }
  return value;
}

export function createLiveActivityDirectDispatchAttempt(
  input: LiveActivityDirectDispatchAttempt,
): LiveActivityDirectDispatchAttempt {
  if (input == null || typeof input !== "object") {
    throw new TypeError("dispatch attempt must be an object");
  }
  const operation = normalizedLiveActivityDispatchOperation(input.operation);
  const state = normalizedDispatchState(input.state);
  const createdAt = normalizedLiveActivityDispatchInstant(
    input.createdAt,
    "attempt.createdAt",
  );
  const inFlightAt =
    input.inFlightAt == null
      ? null
      : normalizedLiveActivityDispatchInstant(
          input.inFlightAt,
          "attempt.inFlightAt",
        );
  const completedAt =
    input.completedAt == null
      ? null
      : normalizedLiveActivityDispatchInstant(
          input.completedAt,
          "attempt.completedAt",
        );
  const retryNotBefore =
    input.retryNotBefore == null
      ? null
      : normalizedLiveActivityDispatchInstant(
          input.retryNotBefore,
          "attempt.retryNotBefore",
        );
  const payloadFingerprint =
    input.payloadFingerprint == null
      ? null
      : normalizedPayloadFingerprint(input.payloadFingerprint);
  const publicationMetadata =
    input.publicationMetadata == null
      ? null
      : createLiveActivityPublicationMetadata(input.publicationMetadata);
  const retryAdvice = normalizedRetryAdvice(input.retryAdvice);
  const postSendAuthority = normalizedPostSendAuthority(input.postSendAuthority);
  const tokenInvalidationOutcome = normalizedTokenInvalidationOutcome(
    input.tokenInvalidationOutcome,
  );
  const terminalAfterNetwork =
    state === "ACCEPTED" ||
    state === "REJECTED" ||
    state === "RETRYABLE" ||
    state === "OUTCOME_UNKNOWN";
  if (
    (state === "RESERVED" &&
      (payloadFingerprint != null || inFlightAt != null || completedAt != null)) ||
    (state === "IN_FLIGHT" &&
      (payloadFingerprint == null || inFlightAt == null || completedAt != null)) ||
    (terminalAfterNetwork &&
      (payloadFingerprint == null || inFlightAt == null || completedAt == null)) ||
    (state === "ABORTED" &&
      (completedAt == null ||
        (payloadFingerprint == null) !== (inFlightAt == null))) ||
    (state === "SUPERSEDED" &&
      (payloadFingerprint != null || inFlightAt != null || completedAt == null))
  ) {
    throw new RangeError("dispatch lifecycle timestamps are inconsistent");
  }
  if (
    (inFlightAt != null && inFlightAt.getTime() < createdAt.getTime()) ||
    (completedAt != null && completedAt.getTime() < createdAt.getTime()) ||
    (inFlightAt != null &&
      completedAt != null &&
      completedAt.getTime() < inFlightAt.getTime()) ||
    (retryNotBefore != null &&
      (completedAt == null || retryNotBefore.getTime() < completedAt.getTime()))
  ) {
    throw new RangeError("dispatch timestamps are out of order");
  }
  if (
    ((state === "RESERVED" || state === "IN_FLIGHT") && retryAdvice != null) ||
    (state !== "RESERVED" && state !== "IN_FLIGHT" && retryAdvice == null)
  ) {
    throw new RangeError("dispatch retry advice is inconsistent");
  }
  if (
    (state === "ACCEPTED" && input.apnsStatus !== 200) ||
    ((state === "REJECTED" || state === "RETRYABLE") &&
      input.apnsStatus == null) ||
    ((state === "RESERVED" ||
      state === "IN_FLIGHT" ||
      state === "ABORTED" ||
      state === "SUPERSEDED") &&
      (input.apnsStatus != null || input.apnsReason != null)) ||
    (input.apnsStatus != null &&
      (!Number.isInteger(input.apnsStatus) ||
        input.apnsStatus < 100 ||
        input.apnsStatus > 599))
  ) {
    throw new RangeError("dispatch APNs result is inconsistent");
  }
  if (
    (state === "ACCEPTED" && postSendAuthority === "NOT_CHECKED") ||
    (state !== "ACCEPTED" && postSendAuthority !== "NOT_CHECKED")
  ) {
    throw new RangeError("dispatch post-send authority is inconsistent");
  }
  if (
    tokenInvalidationOutcome !== "NOT_APPLICABLE" &&
    state !== "REJECTED"
  ) {
    throw new RangeError("dispatch token invalidation outcome is inconsistent");
  }
  return Object.freeze({
    dispatchId: normalizedLiveActivityDispatchUuid(input.dispatchId, "dispatchId"),
    bindingId: normalizedLiveActivityBindingId(input.bindingId),
    installationId: normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    ),
    sessionRevision: positiveActivityKitGeneration(
      input.sessionRevision,
      "sessionRevision",
    ),
    operation,
    eventTimestamp: normalizedLiveActivityEventTimestamp(input.eventTimestamp),
    tokenGeneration: Object.freeze({
      clientGeneration: positiveActivityKitGeneration(
        input.tokenGeneration.clientGeneration,
        "tokenGeneration.clientGeneration",
      ),
      serverRevision: positiveActivityKitGeneration(
        input.tokenGeneration.serverRevision,
        "tokenGeneration.serverRevision",
      ),
    }),
    environment: normalizedApplePushEnvironment(input.environment),
    apnsRequestId: normalizedLiveActivityDispatchUuid(
      input.apnsRequestId,
      "apnsRequestId",
    ),
    payloadFingerprint,
    publicationMetadata,
    state,
    apnsStatus: input.apnsStatus,
    apnsReason: normalizedSafeApnsReason(input.apnsReason),
    retryAdvice,
    retryNotBefore,
    postSendAuthority,
    tokenInvalidationOutcome,
    createdAt,
    inFlightAt,
    completedAt,
  });
}

export function createLiveActivityDirectDispatchHistory(
  input: LiveActivityDirectDispatchHistory,
): LiveActivityDirectDispatchHistory {
  const reference = createLiveActivityDispatchBindingReference(input);
  const latestAcceptedAttempt =
    input.latestAcceptedAttempt == null
      ? null
      : createLiveActivityDirectDispatchAttempt(input.latestAcceptedAttempt);
  const latestAttempt =
    input.latestAttempt == null
      ? null
      : createLiveActivityDirectDispatchAttempt(input.latestAttempt);
  for (const attempt of [latestAcceptedAttempt, latestAttempt]) {
    if (
      attempt != null &&
      (attempt.bindingId !== reference.bindingId ||
        attempt.installationId !== reference.installationId ||
        attempt.sessionRevision !== reference.sessionRevision)
    ) {
      throw new RangeError("dispatch history attempt identity does not match");
    }
  }
  if (latestAcceptedAttempt != null && latestAcceptedAttempt.state !== "ACCEPTED") {
    throw new RangeError("latest accepted dispatch history is not accepted");
  }
  return Object.freeze({
    ...reference,
    latestAcceptedAttempt,
    latestAttempt,
  });
}

export function sameLiveActivityDispatchTokenGeneration(
  left: LiveActivityDispatchTokenGeneration,
  right: LiveActivityDispatchTokenGeneration,
): boolean {
  return (
    left.clientGeneration === right.clientGeneration &&
    left.serverRevision === right.serverRevision
  );
}
