import {
  normalizedApplePushEnvironment,
  type ApplePushEnvironment,
} from "./deliveryModel.js";
import { normalizeActivityKitToken } from "./tokenProtection.js";
import {
  verifiedActivityKitPayloadBody,
  type BuiltActivityKitPayload,
} from "./activityKitPayload.js";

export const APNS_DIRECT_PAYLOAD_MAX_BYTES = 4_096;
export const APNS_BROADCAST_PAYLOAD_MAX_BYTES = 5_120;
export const ACTIVITY_KIT_CONTENT_STATE_MAX_BYTES = 4_096;

const RESPONSE_BODY_MAX_BYTES = 8 * 1_024 * 1_024;
const OPAQUE_HEADER_MAX_BYTES = 16 * 1_024;
const JSON_MAX_DEPTH = 32;
const JSON_MAX_NODES = 25_000;
const NODE_INSPECT_CUSTOM = Symbol.for("nodejs.util.inspect.custom");
const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BUNDLE_ID_PATTERN = /^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/;
const PROVIDER_TOKEN_PATTERN =
  /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;
const SAFE_RESPONSE_VALUE_PATTERN = /^[\x20-\x7e]+$/;

export interface ApnsEnvironmentEndpoints {
  readonly directDevice: string;
  readonly broadcast: string;
  readonly channelManagement: string;
}

export const APNS_ENVIRONMENT_ENDPOINTS: Readonly<
  Record<ApplePushEnvironment, ApnsEnvironmentEndpoints>
> = Object.freeze({
  SANDBOX: Object.freeze({
    directDevice: "https://api.sandbox.push.apple.com:443",
    broadcast: "https://api-broadcast.sandbox.push.apple.com:443",
    channelManagement:
      "https://api-manage-broadcast.sandbox.push.apple.com:2195",
  }),
  PRODUCTION: Object.freeze({
    directDevice: "https://api.push.apple.com:443",
    broadcast: "https://api-broadcast.push.apple.com:443",
    channelManagement: "https://api-manage-broadcast.push.apple.com:2196",
  }),
});

export function apnsEnvironmentEndpoints(
  environment: ApplePushEnvironment,
): ApnsEnvironmentEndpoints {
  return APNS_ENVIRONMENT_ENDPOINTS[
    normalizedApplePushEnvironment(environment)
  ];
}

export type ApnsJsonPrimitive = string | number | boolean | null;
export type ApnsJsonValue =
  | ApnsJsonPrimitive
  | readonly ApnsJsonValue[]
  | { readonly [key: string]: ApnsJsonValue };

export type LiveActivityPushEvent = "start" | "update" | "end";
export type ApnsDirectLiveActivityPriority = 5 | 10;
export type ApnsBroadcastLiveActivityPriority =
  | 1
  | ApnsDirectLiveActivityPriority;
export type ApnsLiveActivityPriority = ApnsBroadcastLiveActivityPriority;
export type ApnsChannelMessageStoragePolicy = 0 | 1;
export type ApnsHttpMethod = "POST" | "GET" | "DELETE";
export type ApnsRequestKind =
  | "DIRECT_LIVE_ACTIVITY"
  | "BROADCAST_LIVE_ACTIVITY"
  | "CHANNEL_CREATE"
  | "CHANNEL_READ"
  | "CHANNEL_DELETE"
  | "CHANNEL_LIST";

export interface ApnsMaterializedRequest {
  readonly endpoint: string;
  readonly method: ApnsHttpMethod;
  readonly path: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: string | null;
  readonly bodyByteLength: number;
}

export interface RedactedApnsRequestDiagnostic {
  readonly kind: ApnsRequestKind;
  readonly environment: ApplePushEnvironment;
  readonly endpoint: string;
  readonly method: ApnsHttpMethod;
  readonly pathTemplate: string;
  readonly event: LiveActivityPushEvent | null;
  readonly priority: ApnsLiveActivityPriority | null;
  readonly expiration: number | null;
  readonly requestId: string | null;
  readonly bodyByteLength: number;
}

/**
 * A validated request whose sensitive transport material is private by default.
 * Only the transport boundary should call materializeForTransport().
 */
export interface ApnsRequestDescription {
  readonly kind: ApnsRequestKind;
  readonly environment: ApplePushEnvironment;
  readonly endpoint: string;
  readonly method: ApnsHttpMethod;
  readonly bodyByteLength: number;
  materializeForTransport(): ApnsMaterializedRequest;
  toRedactedDiagnostic(): RedactedApnsRequestDiagnostic;
  toJSON(): RedactedApnsRequestDiagnostic;
  toString(): string;
}

export interface CreateDirectLiveActivityRequestDescriptionInput {
  readonly environment: ApplePushEnvironment;
  readonly bundleId: string;
  readonly providerToken: string;
  readonly deviceToken: Uint8Array;
  readonly payload: BuiltActivityKitPayload;
  readonly priority: ApnsDirectLiveActivityPriority;
  readonly expiration?: number;
  readonly apnsId?: string;
  readonly collapseId?: string;
}

export interface CreateBroadcastLiveActivityRequestDescriptionInput {
  readonly environment: ApplePushEnvironment;
  readonly bundleId: string;
  readonly providerToken: string;
  readonly channelId: string;
  readonly payload: BuiltActivityKitPayload;
  readonly priority: ApnsBroadcastLiveActivityPriority;
  readonly expiration: number;
  readonly requestId?: string;
}

interface ApnsChannelRequestInput {
  readonly environment: ApplePushEnvironment;
  readonly bundleId: string;
  readonly providerToken: string;
  readonly requestId?: string;
}

export interface CreateApnsChannelCreateRequestDescriptionInput
  extends ApnsChannelRequestInput {
  readonly messageStoragePolicy: ApnsChannelMessageStoragePolicy;
}

export interface CreateApnsChannelTargetRequestDescriptionInput
  extends ApnsChannelRequestInput {
  readonly channelId: string;
}

export type CreateApnsChannelListRequestDescriptionInput = ApnsChannelRequestInput;

class SecureApnsRequestDescription implements ApnsRequestDescription {
  readonly #transport: ApnsMaterializedRequest;
  readonly #diagnostic: RedactedApnsRequestDiagnostic;

  constructor(
    transport: ApnsMaterializedRequest,
    diagnostic: RedactedApnsRequestDiagnostic,
  ) {
    this.#transport = Object.freeze({
      ...transport,
      headers: Object.freeze({ ...transport.headers }),
    });
    this.#diagnostic = Object.freeze({ ...diagnostic });
    Object.freeze(this);
  }

  get kind(): ApnsRequestKind {
    return this.#diagnostic.kind;
  }

  get environment(): ApplePushEnvironment {
    return this.#diagnostic.environment;
  }

  get endpoint(): string {
    return this.#diagnostic.endpoint;
  }

  get method(): ApnsHttpMethod {
    return this.#diagnostic.method;
  }

  get bodyByteLength(): number {
    return this.#diagnostic.bodyByteLength;
  }

  materializeForTransport(): ApnsMaterializedRequest {
    return this.#transport;
  }

  toRedactedDiagnostic(): RedactedApnsRequestDiagnostic {
    return this.#diagnostic;
  }

  toJSON(): RedactedApnsRequestDiagnostic {
    return this.#diagnostic;
  }

  toString(): string {
    return JSON.stringify(this.#diagnostic);
  }

  [NODE_INSPECT_CUSTOM](): RedactedApnsRequestDiagnostic {
    return this.#diagnostic;
  }
}

function normalizedBundleId(value: string): string {
  if (
    typeof value !== "string" ||
    value.length > 255 ||
    !BUNDLE_ID_PATTERN.test(value)
  ) {
    throw new RangeError("APNs bundle ID is invalid");
  }
  return value;
}

function normalizedProviderToken(value: string): string {
  if (
    typeof value !== "string" ||
    Buffer.byteLength(value, "ascii") > OPAQUE_HEADER_MAX_BYTES ||
    !PROVIDER_TOKEN_PATTERN.test(value)
  ) {
    throw new RangeError("APNs provider token is invalid");
  }
  return value;
}

function normalizedCanonicalUuid(value: string, field: string): string {
  if (typeof value !== "string" || !CANONICAL_UUID_PATTERN.test(value)) {
    throw new RangeError(`${field} is invalid`);
  }
  return value.toLowerCase();
}

function normalizedOptionalRequestId(
  value: string | undefined,
  field: string,
): string | undefined {
  return value == null ? undefined : normalizedCanonicalUuid(value, field);
}

function normalizedChannelId(value: unknown): string {
  if (
    typeof value !== "string" ||
    Buffer.byteLength(value, "ascii") > OPAQUE_HEADER_MAX_BYTES ||
    !BASE64_PATTERN.test(value) ||
    value.length % 4 === 1 ||
    (value.includes("=") && value.length % 4 !== 0)
  ) {
    throw new RangeError("APNs channel ID is invalid");
  }
  const decoded = Buffer.from(value, "base64");
  const canonicalUnpadded = decoded.toString("base64").replace(/=+$/, "");
  if (
    decoded.byteLength === 0 ||
    canonicalUnpadded !== value.replace(/=+$/, "")
  ) {
    throw new RangeError("APNs channel ID is invalid");
  }
  return value;
}

function normalizedDirectPriority(value: number): ApnsDirectLiveActivityPriority {
  if (value !== 5 && value !== 10) {
    throw new RangeError("ActivityKit APNs priority must be 5 or 10");
  }
  return value;
}

function normalizedBroadcastPriority(
  value: number,
): ApnsBroadcastLiveActivityPriority {
  if (value !== 1 && value !== 5 && value !== 10) {
    throw new RangeError("ActivityKit broadcast APNs priority must be 1, 5, or 10");
  }
  return value;
}

function normalizedExpiration(value: number, field = "APNs expiration"): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${field} must be a nonnegative epoch-second integer`);
  }
  return value;
}

function containsAsciiControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 0x1f || code === 0x7f) return true;
  }
  return false;
}

function normalizedCollapseId(value: string): string {
  if (
    typeof value !== "string" ||
    Buffer.byteLength(value, "utf8") === 0 ||
    Buffer.byteLength(value, "utf8") > 64 ||
    containsAsciiControlCharacter(value)
  ) {
    throw new RangeError("APNs collapse ID is invalid");
  }
  return value;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function assertPlainJson(
  value: unknown,
  seen: Set<object>,
  depth: number,
  nodeCount: { value: number },
  maximumStringBytes = APNS_BROADCAST_PAYLOAD_MAX_BYTES,
): asserts value is ApnsJsonValue {
  nodeCount.value += 1;
  if (depth > JSON_MAX_DEPTH || nodeCount.value > JSON_MAX_NODES) {
    throw new RangeError("APNs JSON payload is too complex");
  }
  if (value == null || typeof value === "boolean") return;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      throw new TypeError("APNs JSON payload contains a non-finite number");
    }
    return;
  }
  if (typeof value === "string") {
    if (Buffer.byteLength(value, "utf8") > maximumStringBytes) {
      throw new RangeError("APNs JSON string exceeds the payload limit");
    }
    return;
  }
  if (typeof value !== "object") {
    throw new TypeError("APNs payload must contain only JSON values");
  }
  if (seen.has(value)) {
    throw new TypeError("APNs JSON payload must not contain cycles");
  }
  seen.add(value);
  try {
    if (Array.isArray(value)) {
      if (Object.keys(value).length !== value.length) {
        throw new TypeError("APNs JSON arrays must be dense and unextended");
      }
      for (let index = 0; index < value.length; index += 1) {
        if (!Object.hasOwn(value, index)) {
          throw new TypeError("APNs JSON arrays must not contain holes");
        }
        assertPlainJson(
          value[index],
          seen,
          depth + 1,
          nodeCount,
          maximumStringBytes,
        );
      }
      return;
    }
    if (!isPlainRecord(value)) {
      throw new TypeError("APNs payload must use plain JSON objects");
    }
    if (
      Object.getOwnPropertySymbols(value).some(
        (symbol) => Object.getOwnPropertyDescriptor(value, symbol)?.enumerable,
      )
    ) {
      throw new TypeError("APNs JSON objects must not contain symbol keys");
    }
    const descriptors = Object.getOwnPropertyDescriptors(value);
    for (const descriptor of Object.values(descriptors)) {
      if (!descriptor.enumerable) continue;
      if (!("value" in descriptor)) {
        throw new TypeError("APNs JSON objects must not contain accessors");
      }
      assertPlainJson(
        descriptor.value,
        seen,
        depth + 1,
        nodeCount,
        maximumStringBytes,
      );
    }
  } finally {
    seen.delete(value);
  }
}

function ownValue(record: Record<string, unknown>, key: string): unknown {
  return Object.hasOwn(record, key) ? record[key] : undefined;
}

function requiredPlainRecord(
  value: unknown,
  field: string,
): Record<string, unknown> {
  if (!isPlainRecord(value)) {
    throw new TypeError(`${field} must be a JSON object`);
  }
  return value;
}

function assertEpochSeconds(value: unknown, field: string): asserts value is number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new RangeError(`${field} must be a nonnegative epoch-second integer`);
  }
}

function validatedLiveActivityPayload(
  payload: BuiltActivityKitPayload,
  allowedEvents: readonly LiveActivityPushEvent[],
  transportMaximumBytes: number,
): { readonly body: string; readonly byteLength: number; readonly event: LiveActivityPushEvent } {
  const body = verifiedActivityKitPayloadBody(payload);
  assertPlainJson(payload.value, new Set(), 0, { value: 0 });
  const root = requiredPlainRecord(payload.value, "APNs payload");
  const aps = requiredPlainRecord(ownValue(root, "aps"), "APNs aps");
  assertEpochSeconds(ownValue(aps, "timestamp"), "ActivityKit timestamp");

  const event = ownValue(aps, "event");
  if (
    (event !== "start" && event !== "update" && event !== "end") ||
    !allowedEvents.includes(event)
  ) {
    throw new RangeError("ActivityKit event is invalid for this APNs request");
  }
  const contentState = requiredPlainRecord(
    ownValue(aps, "content-state"),
    "ActivityKit content-state",
  );
  const contentStateBytes = Buffer.byteLength(JSON.stringify(contentState), "utf8");
  if (contentStateBytes > ACTIVITY_KIT_CONTENT_STATE_MAX_BYTES) {
    throw new RangeError("ActivityKit content-state exceeds 4096 bytes");
  }

  const staleDate = ownValue(aps, "stale-date");
  if (staleDate !== undefined) {
    if (event !== "update") {
      throw new RangeError("ActivityKit stale-date is only valid for update events");
    }
    assertEpochSeconds(staleDate, "ActivityKit stale-date");
  }
  const dismissalDate = ownValue(aps, "dismissal-date");
  if (dismissalDate !== undefined) {
    if (event !== "end") {
      throw new RangeError("ActivityKit dismissal-date is only valid for end events");
    }
    assertEpochSeconds(dismissalDate, "ActivityKit dismissal-date");
  }
  const relevanceScore = ownValue(aps, "relevance-score");
  if (
    relevanceScore !== undefined &&
    (typeof relevanceScore !== "number" || !Number.isFinite(relevanceScore))
  ) {
    throw new RangeError("ActivityKit relevance-score must be finite");
  }
  const alert = ownValue(aps, "alert");
  if (
    alert !== undefined &&
    typeof alert !== "string" &&
    !isPlainRecord(alert)
  ) {
    throw new TypeError("ActivityKit alert must be a string or JSON object");
  }

  const attributesType = ownValue(aps, "attributes-type");
  const attributes = ownValue(aps, "attributes");
  const inputPushChannel = ownValue(aps, "input-push-channel");
  const inputPushToken = ownValue(aps, "input-push-token");
  if (event === "start") {
    if (alert === undefined) {
      throw new RangeError("ActivityKit remote start requires an alert");
    }
    if (
      typeof attributesType !== "string" ||
      attributesType.length === 0 ||
      attributesType.length > 512 ||
      containsAsciiControlCharacter(attributesType)
    ) {
      throw new RangeError("ActivityKit attributes-type is invalid");
    }
    requiredPlainRecord(attributes, "ActivityKit attributes");
    if (inputPushChannel !== undefined) normalizedChannelId(inputPushChannel);
    if (inputPushToken !== undefined && inputPushToken !== 1) {
      throw new RangeError("ActivityKit input-push-token must equal 1");
    }
    if (inputPushChannel !== undefined && inputPushToken !== undefined) {
      throw new RangeError("ActivityKit start push inputs are mutually exclusive");
    }
  } else if (
    attributesType !== undefined ||
    attributes !== undefined ||
    inputPushChannel !== undefined ||
    inputPushToken !== undefined
  ) {
    throw new RangeError("ActivityKit start-only fields require a start event");
  }

  const byteLength = Buffer.byteLength(body, "utf8");
  if (byteLength > transportMaximumBytes) {
    throw new RangeError(
      `APNs payload exceeds the ${transportMaximumBytes}-byte transport limit`,
    );
  }
  return Object.freeze({ body, byteLength, event });
}

function requestDescription(
  input: {
    readonly kind: ApnsRequestKind;
    readonly environment: ApplePushEnvironment;
    readonly endpoint: string;
    readonly method: ApnsHttpMethod;
    readonly path: string;
    readonly pathTemplate: string;
    readonly headers: Readonly<Record<string, string>>;
    readonly body: string | null;
    readonly event?: LiveActivityPushEvent;
    readonly priority?: ApnsLiveActivityPriority;
    readonly expiration?: number;
    readonly requestId?: string;
  },
): ApnsRequestDescription {
  const bodyByteLength =
    input.body == null ? 0 : Buffer.byteLength(input.body, "utf8");
  return new SecureApnsRequestDescription(
    {
      endpoint: input.endpoint,
      method: input.method,
      path: input.path,
      headers: input.headers,
      body: input.body,
      bodyByteLength,
    },
    {
      kind: input.kind,
      environment: input.environment,
      endpoint: input.endpoint,
      method: input.method,
      pathTemplate: input.pathTemplate,
      event: input.event ?? null,
      priority: input.priority ?? null,
      expiration: input.expiration ?? null,
      requestId: input.requestId ?? null,
      bodyByteLength,
    },
  );
}

function authenticatedHeaders(
  providerToken: string,
  requestId?: string,
): Record<string, string> {
  const headers: Record<string, string> = {
    authorization: `bearer ${normalizedProviderToken(providerToken)}`,
  };
  if (requestId != null) headers["apns-request-id"] = requestId;
  return headers;
}

export function createDirectLiveActivityRequestDescription(
  input: CreateDirectLiveActivityRequestDescriptionInput,
): ApnsRequestDescription {
  if (input == null || typeof input !== "object") {
    throw new TypeError("direct APNs request input must be an object");
  }
  const environment = normalizedApplePushEnvironment(input.environment);
  const bundleId = normalizedBundleId(input.bundleId);
  const priority = normalizedDirectPriority(input.priority);
  const expiration =
    input.expiration == null ? undefined : normalizedExpiration(input.expiration);
  const apnsId = normalizedOptionalRequestId(input.apnsId, "APNs ID");
  const payload = validatedLiveActivityPayload(
    input.payload,
    ["start", "update", "end"],
    APNS_DIRECT_PAYLOAD_MAX_BYTES,
  );
  const token = normalizeActivityKitToken(input.deviceToken).toString("hex");
  const headers: Record<string, string> = {
    ...authenticatedHeaders(input.providerToken),
    "content-type": "application/json",
    "apns-push-type": "liveactivity",
    "apns-topic": `${bundleId}.push-type.liveactivity`,
    "apns-priority": String(priority),
  };
  if (expiration != null) headers["apns-expiration"] = String(expiration);
  if (apnsId != null) headers["apns-id"] = apnsId;
  if (input.collapseId != null) {
    headers["apns-collapse-id"] = normalizedCollapseId(input.collapseId);
  }
  return requestDescription({
    kind: "DIRECT_LIVE_ACTIVITY",
    environment,
    endpoint: apnsEnvironmentEndpoints(environment).directDevice,
    method: "POST",
    path: `/3/device/${token}`,
    pathTemplate: "/3/device/<redacted>",
    headers,
    body: payload.body,
    event: payload.event,
    priority,
    expiration,
    requestId: apnsId,
  });
}

export function createBroadcastLiveActivityRequestDescription(
  input: CreateBroadcastLiveActivityRequestDescriptionInput,
): ApnsRequestDescription {
  if (input == null || typeof input !== "object") {
    throw new TypeError("broadcast APNs request input must be an object");
  }
  const environment = normalizedApplePushEnvironment(input.environment);
  const bundleId = normalizedBundleId(input.bundleId);
  const channelId = normalizedChannelId(input.channelId);
  const priority = normalizedBroadcastPriority(input.priority);
  const expiration = normalizedExpiration(input.expiration);
  const requestId = normalizedOptionalRequestId(input.requestId, "APNs request ID");
  const payload = validatedLiveActivityPayload(
    input.payload,
    ["update", "end"],
    APNS_BROADCAST_PAYLOAD_MAX_BYTES,
  );
  const headers: Record<string, string> = {
    ...authenticatedHeaders(input.providerToken, requestId),
    "content-type": "application/json",
    "apns-channel-id": channelId,
    "apns-expiration": String(expiration),
    "apns-priority": String(priority),
    "apns-push-type": "liveactivity",
  };
  return requestDescription({
    kind: "BROADCAST_LIVE_ACTIVITY",
    environment,
    endpoint: apnsEnvironmentEndpoints(environment).broadcast,
    method: "POST",
    path: `/4/broadcasts/apps/${bundleId}`,
    pathTemplate: "/4/broadcasts/apps/<bundle-id>",
    headers,
    body: payload.body,
    event: payload.event,
    priority,
    expiration,
    requestId,
  });
}

function normalizedChannelBase(
  input: ApnsChannelRequestInput,
): {
  readonly environment: ApplePushEnvironment;
  readonly bundleId: string;
  readonly requestId: string | undefined;
  readonly headers: Record<string, string>;
} {
  if (input == null || typeof input !== "object") {
    throw new TypeError("channel APNs request input must be an object");
  }
  const environment = normalizedApplePushEnvironment(input.environment);
  const bundleId = normalizedBundleId(input.bundleId);
  const requestId = normalizedOptionalRequestId(input.requestId, "APNs request ID");
  return {
    environment,
    bundleId,
    requestId,
    headers: authenticatedHeaders(input.providerToken, requestId),
  };
}

export function createApnsChannelCreateRequestDescription(
  input: CreateApnsChannelCreateRequestDescriptionInput,
): ApnsRequestDescription {
  const base = normalizedChannelBase(input);
  if (input.messageStoragePolicy !== 0 && input.messageStoragePolicy !== 1) {
    throw new RangeError("APNs channel message storage policy must be 0 or 1");
  }
  const body = JSON.stringify({
    "message-storage-policy": input.messageStoragePolicy,
    "push-type": "LiveActivity",
  });
  return requestDescription({
    kind: "CHANNEL_CREATE",
    environment: base.environment,
    endpoint: apnsEnvironmentEndpoints(base.environment).channelManagement,
    method: "POST",
    path: `/1/apps/${base.bundleId}/channels`,
    pathTemplate: "/1/apps/<bundle-id>/channels",
    headers: { ...base.headers, "content-type": "application/json" },
    body,
    requestId: base.requestId,
  });
}

function createTargetedChannelRequestDescription(
  kind: "CHANNEL_READ" | "CHANNEL_DELETE",
  method: "GET" | "DELETE",
  input: CreateApnsChannelTargetRequestDescriptionInput,
): ApnsRequestDescription {
  const base = normalizedChannelBase(input);
  const channelId = normalizedChannelId(input.channelId);
  return requestDescription({
    kind,
    environment: base.environment,
    endpoint: apnsEnvironmentEndpoints(base.environment).channelManagement,
    method,
    path: `/1/apps/${base.bundleId}/channels`,
    pathTemplate: "/1/apps/<bundle-id>/channels",
    headers: { ...base.headers, "apns-channel-id": channelId },
    body: null,
    requestId: base.requestId,
  });
}

export function createApnsChannelReadRequestDescription(
  input: CreateApnsChannelTargetRequestDescriptionInput,
): ApnsRequestDescription {
  return createTargetedChannelRequestDescription("CHANNEL_READ", "GET", input);
}

export function createApnsChannelDeleteRequestDescription(
  input: CreateApnsChannelTargetRequestDescriptionInput,
): ApnsRequestDescription {
  return createTargetedChannelRequestDescription("CHANNEL_DELETE", "DELETE", input);
}

export function createApnsChannelListRequestDescription(
  input: CreateApnsChannelListRequestDescriptionInput,
): ApnsRequestDescription {
  const base = normalizedChannelBase(input);
  return requestDescription({
    kind: "CHANNEL_LIST",
    environment: base.environment,
    endpoint: apnsEnvironmentEndpoints(base.environment).channelManagement,
    method: "GET",
    path: `/1/apps/${base.bundleId}/all-channels`,
    pathTemplate: "/1/apps/<bundle-id>/all-channels",
    headers: base.headers,
    body: null,
    requestId: base.requestId,
  });
}

export type RawApnsTransportHeaderValue =
  | string
  | readonly string[]
  | number
  | undefined;

export interface RawApnsTransportResponse {
  readonly statusCode: number;
  readonly headers?: Readonly<Record<string, RawApnsTransportHeaderValue>>;
  readonly body?: string | Uint8Array | null;
}

export type ApnsResponseBodyState = "EMPTY" | "JSON" | "INVALID_JSON";
export type ApnsResponseValidationIssue =
  | "INVALID_APNS_ID"
  | "INVALID_APNS_REQUEST_ID"
  | "INVALID_APNS_UNIQUE_ID"
  | "INVALID_APNS_CHANNEL_ID"
  | "INVALID_RESPONSE_BODY"
  | "INVALID_RESPONSE_REASON"
  | "INVALID_INVALIDATION_TIMESTAMP";

export interface ApnsProtocolResponseMaterial {
  readonly channelId: string | null;
  readonly jsonBody: ApnsJsonValue | null;
}

export interface RedactedApnsTransportResponseDiagnostic {
  readonly statusCode: number;
  readonly apnsId: string | null;
  readonly apnsRequestId: string | null;
  readonly apnsUniqueId: string | null;
  readonly reason: string | null;
  readonly tokenInvalidationTimestampMilliseconds: number | null;
  readonly bodyState: ApnsResponseBodyState;
  readonly validationIssues: readonly ApnsResponseValidationIssue[];
}

export interface NormalizedApnsTransportResponse
  extends RedactedApnsTransportResponseDiagnostic {
  materializeForProtocolClassification(): ApnsProtocolResponseMaterial;
  toRedactedDiagnostic(): RedactedApnsTransportResponseDiagnostic;
  toJSON(): RedactedApnsTransportResponseDiagnostic;
  toString(): string;
}

class SecureNormalizedApnsTransportResponse
  implements NormalizedApnsTransportResponse
{
  readonly #diagnostic: RedactedApnsTransportResponseDiagnostic;
  readonly #material: ApnsProtocolResponseMaterial;

  constructor(
    diagnostic: RedactedApnsTransportResponseDiagnostic,
    material: ApnsProtocolResponseMaterial,
  ) {
    this.#diagnostic = Object.freeze({
      ...diagnostic,
      validationIssues: Object.freeze([...diagnostic.validationIssues]),
    });
    this.#material = Object.freeze({ ...material });
    Object.freeze(this);
  }

  get statusCode(): number {
    return this.#diagnostic.statusCode;
  }

  get apnsId(): string | null {
    return this.#diagnostic.apnsId;
  }

  get apnsRequestId(): string | null {
    return this.#diagnostic.apnsRequestId;
  }

  get apnsUniqueId(): string | null {
    return this.#diagnostic.apnsUniqueId;
  }

  get reason(): string | null {
    return this.#diagnostic.reason;
  }

  get tokenInvalidationTimestampMilliseconds(): number | null {
    return this.#diagnostic.tokenInvalidationTimestampMilliseconds;
  }

  get bodyState(): ApnsResponseBodyState {
    return this.#diagnostic.bodyState;
  }

  get validationIssues(): readonly ApnsResponseValidationIssue[] {
    return this.#diagnostic.validationIssues;
  }

  materializeForProtocolClassification(): ApnsProtocolResponseMaterial {
    return this.#material;
  }

  toRedactedDiagnostic(): RedactedApnsTransportResponseDiagnostic {
    return this.#diagnostic;
  }

  toJSON(): RedactedApnsTransportResponseDiagnostic {
    return this.#diagnostic;
  }

  toString(): string {
    return JSON.stringify(this.#diagnostic);
  }

  [NODE_INSPECT_CUSTOM](): RedactedApnsTransportResponseDiagnostic {
    return this.#diagnostic;
  }
}

function responseHeader(
  headers: RawApnsTransportResponse["headers"],
  name: string,
): unknown {
  if (headers == null) return undefined;
  const matches = Object.entries(headers).filter(
    ([key]) => key.toLowerCase() === name,
  );
  if (matches.length === 0) return undefined;
  if (matches.length > 1) return null;
  const value = matches[0]?.[1];
  if (Array.isArray(value)) return value.length === 1 ? value[0] : null;
  return value;
}

function normalizedResponseUuid(
  value: unknown,
  issue: ApnsResponseValidationIssue,
  issues: ApnsResponseValidationIssue[],
): string | null {
  if (value === undefined) return null;
  if (typeof value !== "string" || !CANONICAL_UUID_PATTERN.test(value)) {
    issues.push(issue);
    return null;
  }
  return value.toLowerCase();
}

function normalizedResponseOpaqueValue(
  value: unknown,
  issue: ApnsResponseValidationIssue,
  issues: ApnsResponseValidationIssue[],
): string | null {
  if (value === undefined) return null;
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    Buffer.byteLength(value, "utf8") > 256 ||
    !SAFE_RESPONSE_VALUE_PATTERN.test(value)
  ) {
    issues.push(issue);
    return null;
  }
  return value;
}

function freezeJson(value: ApnsJsonValue): ApnsJsonValue {
  if (value == null || typeof value !== "object") return value;
  if (Array.isArray(value)) {
    return Object.freeze(value.map((item) => freezeJson(item)));
  }
  const result: Record<string, ApnsJsonValue> = {};
  for (const [key, item] of Object.entries(value)) {
    result[key] = freezeJson(item);
  }
  return Object.freeze(result);
}

function normalizedResponseBody(
  body: RawApnsTransportResponse["body"],
): { readonly state: ApnsResponseBodyState; readonly json: ApnsJsonValue | null } {
  if (body == null || body === "" || (typeof body === "string" && body.trim() === "")) {
    return Object.freeze({ state: "EMPTY", json: null });
  }
  let encoded: Buffer;
  if (typeof body === "string") encoded = Buffer.from(body, "utf8");
  else if (body instanceof Uint8Array) encoded = Buffer.from(body);
  else return Object.freeze({ state: "INVALID_JSON", json: null });
  if (encoded.byteLength === 0) {
    return Object.freeze({ state: "EMPTY", json: null });
  }
  if (encoded.byteLength > RESPONSE_BODY_MAX_BYTES) {
    return Object.freeze({ state: "INVALID_JSON", json: null });
  }
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(encoded);
  } catch {
    return Object.freeze({ state: "INVALID_JSON", json: null });
  }
  if (text.trim().length === 0) {
    return Object.freeze({ state: "EMPTY", json: null });
  }
  try {
    const parsed: unknown = JSON.parse(text);
    assertPlainJson(parsed, new Set(), 0, { value: 0 }, RESPONSE_BODY_MAX_BYTES);
    return Object.freeze({ state: "JSON", json: freezeJson(parsed) });
  } catch {
    return Object.freeze({ state: "INVALID_JSON", json: null });
  }
}

export function normalizeApnsTransportResponse(
  input: RawApnsTransportResponse,
): NormalizedApnsTransportResponse {
  if (input == null || typeof input !== "object") {
    throw new TypeError("APNs transport response must be an object");
  }
  if (
    !Number.isInteger(input.statusCode) ||
    input.statusCode < 100 ||
    input.statusCode > 599
  ) {
    throw new RangeError("APNs response status code is invalid");
  }
  const issues: ApnsResponseValidationIssue[] = [];
  const apnsId = normalizedResponseUuid(
    responseHeader(input.headers, "apns-id"),
    "INVALID_APNS_ID",
    issues,
  );
  const apnsRequestId = normalizedResponseUuid(
    responseHeader(input.headers, "apns-request-id"),
    "INVALID_APNS_REQUEST_ID",
    issues,
  );
  const apnsUniqueId = normalizedResponseOpaqueValue(
    responseHeader(input.headers, "apns-unique-id"),
    "INVALID_APNS_UNIQUE_ID",
    issues,
  );
  const channelHeader = responseHeader(input.headers, "apns-channel-id");
  let channelId: string | null = null;
  if (channelHeader !== undefined) {
    try {
      channelId = normalizedChannelId(channelHeader as string);
    } catch {
      issues.push("INVALID_APNS_CHANNEL_ID");
    }
  }
  const body = normalizedResponseBody(input.body);
  if (body.state === "INVALID_JSON") issues.push("INVALID_RESPONSE_BODY");
  const bodyRecord = isPlainRecord(body.json) ? body.json : undefined;
  const rawReason = bodyRecord == null ? undefined : ownValue(bodyRecord, "reason");
  let reason: string | null = null;
  if (rawReason !== undefined) {
    if (
      typeof rawReason === "string" &&
      rawReason.length > 0 &&
      rawReason.length <= 128 &&
      SAFE_RESPONSE_VALUE_PATTERN.test(rawReason)
    ) {
      reason = rawReason;
    } else {
      issues.push("INVALID_RESPONSE_REASON");
    }
  }
  let tokenInvalidationTimestampMilliseconds: number | null = null;
  const rawTimestamp = bodyRecord == null ? undefined : ownValue(bodyRecord, "timestamp");
  if (input.statusCode === 410 && rawTimestamp !== undefined) {
    if (Number.isSafeInteger(rawTimestamp) && (rawTimestamp as number) >= 0) {
      tokenInvalidationTimestampMilliseconds = rawTimestamp as number;
    } else {
      issues.push("INVALID_INVALIDATION_TIMESTAMP");
    }
  }
  return new SecureNormalizedApnsTransportResponse(
    {
      statusCode: input.statusCode,
      apnsId,
      apnsRequestId,
      apnsUniqueId,
      reason,
      tokenInvalidationTimestampMilliseconds,
      bodyState: body.state,
      validationIssues: issues,
    },
    { channelId, jsonBody: body.json },
  );
}

export type ApnsRetryDirective =
  | "NO_RETRY"
  | "AFTER_CORRECTION"
  | "WITH_DELAY"
  | "AFTER_15_MINUTES";

interface ApnsClassifiedResponseBase {
  readonly statusCode: number;
  readonly requestId: string | null;
  readonly uniqueId: string | null;
  readonly reason: string | null;
  readonly retry: ApnsRetryDirective;
}

export type ApnsDeviceResponseDisposition =
  | "ACCEPTED"
  | "DEVICE_TOKEN_INVALID"
  | "AUTHENTICATION_ERROR"
  | "THROTTLED"
  | "TRANSIENT_SERVER_ERROR"
  | "REQUEST_REJECTED"
  | "PROTOCOL_ERROR";

export interface ApnsDeviceResponseClassification
  extends ApnsClassifiedResponseBase {
  readonly target: "DEVICE";
  readonly disposition: ApnsDeviceResponseDisposition;
  readonly tokenInvalidationTimestampMilliseconds: number | null;
}

const DEVICE_TOKEN_BAD_REQUEST_REASONS = new Set([
  "BadDeviceToken",
  "DeviceTokenNotForTopic",
]);
const DEVICE_TOKEN_GONE_REASONS = new Set(["ExpiredToken", "Unregistered"]);

function isTerminalDeviceResponse(
  response: NormalizedApnsTransportResponse,
): boolean {
  return (
    (response.statusCode === 400 &&
      response.reason != null &&
      DEVICE_TOKEN_BAD_REQUEST_REASONS.has(response.reason)) ||
    (response.statusCode === 410 &&
      response.reason != null &&
      DEVICE_TOKEN_GONE_REASONS.has(response.reason))
  );
}

function retryDirective(
  statusCode: number,
  reason: string | null,
  terminal: boolean,
): ApnsRetryDirective {
  if (
    terminal ||
    reason === "Forbidden" ||
    reason === "PayloadTooLarge" ||
    statusCode === 413 ||
    statusCode === 200 ||
    statusCode === 201 ||
    statusCode === 204
  ) {
    return "NO_RETRY";
  }
  if (statusCode === 429) {
    return reason === "TooManyProviderTokenUpdates"
      ? "AFTER_CORRECTION"
      : "WITH_DELAY";
  }
  if (statusCode === 500 || statusCode === 503) return "AFTER_15_MINUTES";
  if (statusCode >= 400 && statusCode < 500) return "AFTER_CORRECTION";
  return "NO_RETRY";
}

export function classifyApnsDeviceResponse(
  response: NormalizedApnsTransportResponse,
): ApnsDeviceResponseClassification {
  const malformedSuccess =
    response.statusCode === 200 &&
    (response.bodyState !== "EMPTY" ||
      response.apnsId == null ||
      response.validationIssues.length > 0);
  let disposition: ApnsDeviceResponseDisposition;
  if (malformedSuccess || (response.statusCode >= 200 && response.statusCode < 300 && response.statusCode !== 200)) {
    disposition = "PROTOCOL_ERROR";
  } else if (response.statusCode === 200) {
    disposition = "ACCEPTED";
  } else if (isTerminalDeviceResponse(response)) {
    disposition = "DEVICE_TOKEN_INVALID";
  } else if (response.statusCode === 403) {
    disposition = "AUTHENTICATION_ERROR";
  } else if (response.statusCode === 429) {
    disposition = "THROTTLED";
  } else if (response.statusCode === 500 || response.statusCode === 503) {
    disposition = "TRANSIENT_SERVER_ERROR";
  } else if (response.statusCode >= 400 && response.statusCode < 500) {
    disposition = "REQUEST_REJECTED";
  } else {
    disposition = "PROTOCOL_ERROR";
  }
  const terminal = disposition === "DEVICE_TOKEN_INVALID";
  return Object.freeze({
    target: "DEVICE",
    disposition,
    statusCode: response.statusCode,
    requestId: response.apnsId,
    uniqueId: response.apnsUniqueId,
    reason: response.reason,
    retry: retryDirective(response.statusCode, response.reason, terminal),
    tokenInvalidationTimestampMilliseconds:
      response.tokenInvalidationTimestampMilliseconds,
  });
}

export type ApnsBroadcastResponseDisposition =
  | "ACCEPTED"
  | "CHANNEL_INVALID"
  | "FEATURE_DISABLED"
  | "AUTHENTICATION_ERROR"
  | "THROTTLED"
  | "TRANSIENT_SERVER_ERROR"
  | "REQUEST_REJECTED"
  | "PROTOCOL_ERROR";

export interface ApnsBroadcastResponseClassification
  extends ApnsClassifiedResponseBase {
  readonly target: "BROADCAST";
  readonly disposition: ApnsBroadcastResponseDisposition;
}

const CHANNEL_INVALID_REASONS = new Set(["BadChannelId", "ChannelNotRegistered"]);

export function classifyApnsBroadcastResponse(
  response: NormalizedApnsTransportResponse,
): ApnsBroadcastResponseClassification {
  const requestId = response.apnsRequestId ?? response.apnsId;
  const malformedSuccess =
    response.statusCode === 200 &&
    (response.bodyState !== "EMPTY" ||
      requestId == null ||
      response.apnsUniqueId == null ||
      response.validationIssues.length > 0);
  let disposition: ApnsBroadcastResponseDisposition;
  if (malformedSuccess || (response.statusCode >= 200 && response.statusCode < 300 && response.statusCode !== 200)) {
    disposition = "PROTOCOL_ERROR";
  } else if (response.statusCode === 200) {
    disposition = "ACCEPTED";
  } else if (
    response.statusCode === 400 &&
    response.reason != null &&
    CHANNEL_INVALID_REASONS.has(response.reason)
  ) {
    disposition = "CHANNEL_INVALID";
  } else if (
    response.statusCode === 400 &&
    response.reason === "FeatureNotEnabled"
  ) {
    disposition = "FEATURE_DISABLED";
  } else if (response.statusCode === 403) {
    disposition = "AUTHENTICATION_ERROR";
  } else if (response.statusCode === 429) {
    disposition = "THROTTLED";
  } else if (response.statusCode === 500 || response.statusCode === 503) {
    disposition = "TRANSIENT_SERVER_ERROR";
  } else if (response.statusCode >= 400 && response.statusCode < 500) {
    disposition = "REQUEST_REJECTED";
  } else {
    disposition = "PROTOCOL_ERROR";
  }
  return Object.freeze({
    target: "BROADCAST",
    disposition,
    statusCode: response.statusCode,
    requestId,
    uniqueId: response.apnsUniqueId,
    reason: response.reason,
    retry: retryDirective(
      response.statusCode,
      response.reason,
      disposition === "CHANNEL_INVALID",
    ),
  });
}

export type ApnsChannelOperation = "CREATE" | "READ" | "DELETE" | "LIST";
export interface ApnsChannelConfiguration {
  readonly messageStoragePolicy: ApnsChannelMessageStoragePolicy;
  readonly pushType: "LiveActivity";
}

export type ApnsChannelOperationResult =
  | { readonly operation: "CREATE"; readonly channelId: string }
  | { readonly operation: "READ"; readonly configuration: ApnsChannelConfiguration }
  | { readonly operation: "DELETE" }
  | { readonly operation: "LIST"; readonly channelIds: readonly string[] };

export type ApnsChannelResponseDisposition =
  | "SUCCEEDED"
  | "CHANNEL_INVALID"
  | "CHANNEL_LIMIT_REACHED"
  | "FEATURE_DISABLED"
  | "AUTHENTICATION_ERROR"
  | "THROTTLED"
  | "TRANSIENT_SERVER_ERROR"
  | "REQUEST_REJECTED"
  | "PROTOCOL_ERROR";

export interface RedactedApnsChannelResponseDiagnostic
  extends ApnsClassifiedResponseBase {
  readonly target: "CHANNEL";
  readonly operation: ApnsChannelOperation;
  readonly disposition: ApnsChannelResponseDisposition;
  readonly hasResult: boolean;
}

export interface ApnsChannelResponseClassification
  extends RedactedApnsChannelResponseDiagnostic {
  materializeResult(): ApnsChannelOperationResult | null;
  toRedactedDiagnostic(): RedactedApnsChannelResponseDiagnostic;
  toJSON(): RedactedApnsChannelResponseDiagnostic;
  toString(): string;
}

class SecureApnsChannelResponseClassification
  implements ApnsChannelResponseClassification
{
  readonly #diagnostic: RedactedApnsChannelResponseDiagnostic;
  readonly #result: ApnsChannelOperationResult | null;

  constructor(
    diagnostic: RedactedApnsChannelResponseDiagnostic,
    result: ApnsChannelOperationResult | null,
  ) {
    this.#diagnostic = Object.freeze({ ...diagnostic });
    this.#result = result;
    Object.freeze(this);
  }

  get target(): "CHANNEL" {
    return "CHANNEL";
  }

  get operation(): ApnsChannelOperation {
    return this.#diagnostic.operation;
  }

  get disposition(): ApnsChannelResponseDisposition {
    return this.#diagnostic.disposition;
  }

  get statusCode(): number {
    return this.#diagnostic.statusCode;
  }

  get requestId(): string | null {
    return this.#diagnostic.requestId;
  }

  get uniqueId(): string | null {
    return this.#diagnostic.uniqueId;
  }

  get reason(): string | null {
    return this.#diagnostic.reason;
  }

  get retry(): ApnsRetryDirective {
    return this.#diagnostic.retry;
  }

  get hasResult(): boolean {
    return this.#diagnostic.hasResult;
  }

  materializeResult(): ApnsChannelOperationResult | null {
    return this.#result;
  }

  toRedactedDiagnostic(): RedactedApnsChannelResponseDiagnostic {
    return this.#diagnostic;
  }

  toJSON(): RedactedApnsChannelResponseDiagnostic {
    return this.#diagnostic;
  }

  toString(): string {
    return JSON.stringify(this.#diagnostic);
  }

  [NODE_INSPECT_CUSTOM](): RedactedApnsChannelResponseDiagnostic {
    return this.#diagnostic;
  }
}

function expectedChannelSuccessStatus(operation: ApnsChannelOperation): number {
  if (operation === "CREATE") return 201;
  if (operation === "DELETE") return 204;
  return 200;
}

function channelSuccessResult(
  operation: ApnsChannelOperation,
  response: NormalizedApnsTransportResponse,
): ApnsChannelOperationResult | null {
  const material = response.materializeForProtocolClassification();
  if (operation === "CREATE") {
    if (response.bodyState !== "EMPTY" || material.channelId == null) return null;
    return Object.freeze({ operation, channelId: material.channelId });
  }
  if (operation === "DELETE") {
    return response.bodyState === "EMPTY" ? Object.freeze({ operation }) : null;
  }
  if (response.bodyState !== "JSON" || !isPlainRecord(material.jsonBody)) {
    return null;
  }
  if (operation === "READ") {
    const policy = ownValue(material.jsonBody, "message-storage-policy");
    const pushType = ownValue(material.jsonBody, "push-type");
    if ((policy !== 0 && policy !== 1) || pushType !== "LiveActivity") return null;
    return Object.freeze({
      operation,
      configuration: Object.freeze({
        messageStoragePolicy: policy,
        pushType,
      }),
    });
  }
  const channels = ownValue(material.jsonBody, "channels");
  if (!Array.isArray(channels)) return null;
  try {
    const channelIds = channels.map((channel) => normalizedChannelId(channel));
    return Object.freeze({ operation, channelIds: Object.freeze(channelIds) });
  } catch {
    return null;
  }
}

export function classifyApnsChannelResponse(
  operation: ApnsChannelOperation,
  response: NormalizedApnsTransportResponse,
): ApnsChannelResponseClassification {
  if (
    operation !== "CREATE" &&
    operation !== "READ" &&
    operation !== "DELETE" &&
    operation !== "LIST"
  ) {
    throw new RangeError("APNs channel operation is invalid");
  }
  const expectedStatus = expectedChannelSuccessStatus(operation);
  const result =
    response.statusCode === expectedStatus &&
    response.apnsRequestId != null &&
    response.validationIssues.length === 0
      ? channelSuccessResult(operation, response)
      : null;
  let disposition: ApnsChannelResponseDisposition;
  if (response.statusCode >= 200 && response.statusCode < 300) {
    disposition = result == null ? "PROTOCOL_ERROR" : "SUCCEEDED";
  } else if (
    response.statusCode === 400 &&
    response.reason != null &&
    CHANNEL_INVALID_REASONS.has(response.reason)
  ) {
    disposition = "CHANNEL_INVALID";
  } else if (
    response.statusCode === 400 &&
    response.reason === "CannotCreateChannelConfig"
  ) {
    disposition = "CHANNEL_LIMIT_REACHED";
  } else if (
    response.statusCode === 400 &&
    response.reason === "FeatureNotEnabled"
  ) {
    disposition = "FEATURE_DISABLED";
  } else if (response.statusCode === 403) {
    disposition = "AUTHENTICATION_ERROR";
  } else if (response.statusCode === 429) {
    disposition = "THROTTLED";
  } else if (response.statusCode === 500 || response.statusCode === 503) {
    disposition = "TRANSIENT_SERVER_ERROR";
  } else if (response.statusCode >= 400 && response.statusCode < 500) {
    disposition = "REQUEST_REJECTED";
  } else {
    disposition = "PROTOCOL_ERROR";
  }
  return new SecureApnsChannelResponseClassification(
    {
      target: "CHANNEL",
      operation,
      disposition,
      statusCode: response.statusCode,
      requestId: response.apnsRequestId ?? response.apnsId,
      uniqueId: response.apnsUniqueId,
      reason: response.reason,
      retry: retryDirective(
        response.statusCode,
        response.reason,
        disposition === "CHANNEL_INVALID",
      ),
      hasResult: result != null,
    },
    result,
  );
}
