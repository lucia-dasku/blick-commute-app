import {
  normalizeProtectedActivityKitToken,
  type ProtectedActivityKitToken,
} from "./tokenProtection.js";

export const LIVE_ACTIVITY_GENERATION_MAX = 2_147_483_647;
export const APPLE_ACTIVITY_IDENTIFIER_MAX_CHARACTERS = 512;

export type ApplePushEnvironment = "SANDBOX" | "PRODUCTION";
export type ActivityKitTokenLifecycle = "CURRENT" | "REPLACED" | "INVALIDATED";
export type LiveActivityDeliveryStrategy = "DIRECT_TOKEN" | "BROADCAST_CHANNEL";
export type LiveActivityDeliveryLifecycle =
  | "PENDING_START"
  | "ENDED"
  | "INVALIDATED";

export interface ActivityKitTokenMetadata {
  readonly clientGeneration: number;
  readonly serverRevision: number;
  readonly environment: ApplePushEnvironment;
  readonly lifecycle: ActivityKitTokenLifecycle;
  readonly createdAt: Date;
  readonly updatedAt: Date;
  readonly replacedAt: Date | null;
  readonly invalidatedAt: Date | null;
}

/** Persistence-only material. Never include this shape in an ordinary DTO. */
export interface StoredPushToStartToken extends ActivityKitTokenMetadata {
  readonly installationId: string;
  readonly protectedToken: ProtectedActivityKitToken;
}

/** Persistence-only material. Never include this shape in an ordinary DTO. */
export interface StoredLiveActivityUpdateToken extends ActivityKitTokenMetadata {
  readonly installationId: string;
  readonly bindingId: string;
  readonly protectedToken: ProtectedActivityKitToken;
}

export interface LiveActivityDeliveryBinding {
  readonly bindingId: string;
  readonly installationId: string;
  readonly sessionId: string;
  readonly sessionRevision: number;
  readonly strategy: LiveActivityDeliveryStrategy;
  readonly lifecycle: LiveActivityDeliveryLifecycle;
  readonly appleActivityId: string | null;
  readonly createdAt: Date;
  readonly updatedAt: Date;
  readonly endedAt: Date | null;
  readonly invalidatedAt: Date | null;
}

const UUID_V4_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function validDate(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

export function normalizedAppleDeliveryIdentifier(
  value: string,
  field: string,
  maxLength = 256,
): string {
  if (typeof value !== "string") throw new TypeError(`${field} must be a string`);
  const normalized = value.trim();
  if (normalized.length === 0 || normalized.length > maxLength) {
    throw new RangeError(`${field} is invalid`);
  }
  return normalized;
}

export function normalizedLiveActivityBindingId(value: string): string {
  const normalized = normalizedAppleDeliveryIdentifier(value, "bindingId", 36);
  if (!UUID_V4_PATTERN.test(normalized)) {
    throw new RangeError("bindingId is invalid");
  }
  return normalized.toLowerCase();
}

export function positiveActivityKitGeneration(value: number, field: string): number {
  if (
    !Number.isSafeInteger(value) ||
    value <= 0 ||
    value > LIVE_ACTIVITY_GENERATION_MAX
  ) {
    throw new RangeError(`${field} must be a positive supported integer`);
  }
  return value;
}

export function normalizedApplePushEnvironment(value: string): ApplePushEnvironment {
  if (value !== "SANDBOX" && value !== "PRODUCTION") {
    throw new RangeError("APNs environment is invalid");
  }
  return value;
}

export function normalizedLiveActivityDeliveryStrategy(
  value: string,
): LiveActivityDeliveryStrategy {
  if (value !== "DIRECT_TOKEN" && value !== "BROADCAST_CHANNEL") {
    throw new RangeError("delivery strategy is invalid");
  }
  return value;
}

function normalizeTokenMetadata<T extends ActivityKitTokenMetadata>(
  input: T,
): ActivityKitTokenMetadata {
  const clientGeneration = positiveActivityKitGeneration(
    input.clientGeneration,
    "clientGeneration",
  );
  const serverRevision = positiveActivityKitGeneration(
    input.serverRevision,
    "serverRevision",
  );
  const environment = normalizedApplePushEnvironment(input.environment);
  if (
    input.lifecycle !== "CURRENT" &&
    input.lifecycle !== "REPLACED" &&
    input.lifecycle !== "INVALIDATED"
  ) {
    throw new RangeError("token lifecycle is invalid");
  }
  const createdAt = validDate(input.createdAt, "token.createdAt");
  const updatedAt = validDate(input.updatedAt, "token.updatedAt");
  const replacedAt =
    input.replacedAt == null ? null : validDate(input.replacedAt, "token.replacedAt");
  const invalidatedAt =
    input.invalidatedAt == null
      ? null
      : validDate(input.invalidatedAt, "token.invalidatedAt");
  if (updatedAt.getTime() < createdAt.getTime()) {
    throw new RangeError("token updatedAt cannot precede createdAt");
  }
  if (
    (input.lifecycle === "CURRENT" &&
      (replacedAt != null || invalidatedAt != null)) ||
    (input.lifecycle === "REPLACED" &&
      (replacedAt == null || invalidatedAt != null)) ||
    (input.lifecycle === "INVALIDATED" &&
      (invalidatedAt == null || replacedAt != null))
  ) {
    throw new RangeError("token lifecycle metadata is inconsistent");
  }
  const terminalAt = replacedAt ?? invalidatedAt;
  if (
    terminalAt != null &&
    (terminalAt.getTime() < createdAt.getTime() ||
      terminalAt.getTime() > updatedAt.getTime())
  ) {
    throw new RangeError("token terminal timestamp is outside its metadata window");
  }
  return Object.freeze({
    clientGeneration,
    serverRevision,
    environment,
    lifecycle: input.lifecycle,
    createdAt,
    updatedAt,
    replacedAt,
    invalidatedAt,
  });
}

export function createStoredPushToStartToken(
  input: StoredPushToStartToken,
): StoredPushToStartToken {
  if (input == null || typeof input !== "object") {
    throw new TypeError("push-to-start token must be an object");
  }
  const metadata = normalizeTokenMetadata(input);
  return Object.freeze({
    installationId: normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    ),
    ...metadata,
    protectedToken: normalizeProtectedActivityKitToken(input.protectedToken),
  });
}

export function createStoredLiveActivityUpdateToken(
  input: StoredLiveActivityUpdateToken,
): StoredLiveActivityUpdateToken {
  if (input == null || typeof input !== "object") {
    throw new TypeError("Live Activity update token must be an object");
  }
  const metadata = normalizeTokenMetadata(input);
  return Object.freeze({
    installationId: normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    ),
    bindingId: normalizedLiveActivityBindingId(input.bindingId),
    ...metadata,
    protectedToken: normalizeProtectedActivityKitToken(input.protectedToken),
  });
}

export function safeActivityKitTokenMetadata(
  input: ActivityKitTokenMetadata,
): ActivityKitTokenMetadata {
  const metadata = normalizeTokenMetadata(input);
  return Object.freeze({ ...metadata });
}

export function createLiveActivityDeliveryBinding(
  input: LiveActivityDeliveryBinding,
): LiveActivityDeliveryBinding {
  if (input == null || typeof input !== "object") {
    throw new TypeError("delivery binding must be an object");
  }
  const bindingId = normalizedLiveActivityBindingId(input.bindingId);
  const installationId = normalizedAppleDeliveryIdentifier(
    input.installationId,
    "installationId",
  );
  const sessionId = normalizedAppleDeliveryIdentifier(input.sessionId, "sessionId");
  const sessionRevision = positiveActivityKitGeneration(
    input.sessionRevision,
    "sessionRevision",
  );
  const strategy = normalizedLiveActivityDeliveryStrategy(input.strategy);
  if (
    input.lifecycle !== "PENDING_START" &&
    input.lifecycle !== "ENDED" &&
    input.lifecycle !== "INVALIDATED"
  ) {
    throw new RangeError("delivery lifecycle is invalid");
  }
  const appleActivityId =
    input.appleActivityId == null
      ? null
      : normalizedAppleDeliveryIdentifier(
          input.appleActivityId,
          "appleActivityId",
          APPLE_ACTIVITY_IDENTIFIER_MAX_CHARACTERS,
        );
  const createdAt = validDate(input.createdAt, "binding.createdAt");
  const updatedAt = validDate(input.updatedAt, "binding.updatedAt");
  const endedAt =
    input.endedAt == null ? null : validDate(input.endedAt, "binding.endedAt");
  const invalidatedAt =
    input.invalidatedAt == null
      ? null
      : validDate(input.invalidatedAt, "binding.invalidatedAt");
  if (updatedAt.getTime() < createdAt.getTime()) {
    throw new RangeError("binding updatedAt cannot precede createdAt");
  }
  if (
    (input.lifecycle === "PENDING_START" &&
      (endedAt != null || invalidatedAt != null)) ||
    (input.lifecycle === "ENDED" && (endedAt == null || invalidatedAt != null)) ||
    (input.lifecycle === "INVALIDATED" &&
      (invalidatedAt == null || endedAt != null))
  ) {
    throw new RangeError("delivery lifecycle metadata is inconsistent");
  }
  const terminalAt = endedAt ?? invalidatedAt;
  if (
    terminalAt != null &&
    (terminalAt.getTime() < createdAt.getTime() ||
      terminalAt.getTime() > updatedAt.getTime())
  ) {
    throw new RangeError("binding terminal timestamp is outside its metadata window");
  }
  return Object.freeze({
    bindingId,
    installationId,
    sessionId,
    sessionRevision,
    strategy,
    lifecycle: input.lifecycle,
    appleActivityId,
    createdAt,
    updatedAt,
    endedAt,
    invalidatedAt,
  });
}

export function activityKitPushToStartProtectionContext(
  installationId: string,
  clientGeneration: number,
  environment: ApplePushEnvironment,
): string {
  return JSON.stringify([
    "ACTIVITYKIT_PUSH_TO_START",
    normalizedAppleDeliveryIdentifier(installationId, "installationId"),
    positiveActivityKitGeneration(clientGeneration, "clientGeneration"),
    normalizedApplePushEnvironment(environment),
  ]);
}

export function liveActivityUpdateProtectionContext(
  installationId: string,
  bindingId: string,
  clientGeneration: number,
  environment: ApplePushEnvironment,
): string {
  return JSON.stringify([
    "ACTIVITYKIT_UPDATE",
    normalizedAppleDeliveryIdentifier(installationId, "installationId"),
    normalizedLiveActivityBindingId(bindingId),
    positiveActivityKitGeneration(clientGeneration, "clientGeneration"),
    normalizedApplePushEnvironment(environment),
  ]);
}
