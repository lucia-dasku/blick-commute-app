import { randomUUID as nodeRandomUuid } from "node:crypto";
import {
  createLiveCommuteInstallationAuthenticationProof,
  liveCommuteInstallationCredentialMatches,
  LiveCommuteSessionServiceError,
  type LiveCommuteInstallationAuthentication,
  type LiveCommuteInstallationAuthenticationProof,
} from "../installationService.js";
import type {
  StoredLiveCommuteInstallation,
  StoredLiveCommuteSession,
} from "../sessionStore.js";
import {
  activityKitPushToStartProtectionContext,
  APPLE_ACTIVITY_IDENTIFIER_MAX_CHARACTERS,
  createLiveActivityDeliveryBinding,
  createStoredLiveActivityUpdateToken,
  createStoredPushToStartToken,
  liveActivityUpdateProtectionContext,
  normalizedAppleDeliveryIdentifier,
  normalizedApplePushEnvironment,
  normalizedLiveActivityBindingId,
  normalizedLiveActivityDeliveryStrategy,
  positiveActivityKitGeneration,
  safeActivityKitTokenMetadata,
  type ActivityKitTokenMetadata,
  type ApplePushEnvironment,
  type LiveActivityDeliveryBinding,
  type LiveActivityDeliveryLifecycle,
  type LiveActivityDeliveryStrategy,
  type StoredLiveActivityUpdateToken,
  type StoredPushToStartToken,
} from "./deliveryModel.js";
import type {
  LiveActivityDeliveryInstallationTransaction,
  LiveActivityDeliveryStore,
} from "./deliveryStore.js";
import {
  normalizeActivityKitToken,
  type ActivityKitTokenProtector,
} from "./tokenProtection.js";

const BINDING_CREATION_ATTEMPTS = 5;

export type LiveActivityDeliveryServiceErrorCode =
  | "TOKEN_GENERATION_CONFLICT"
  | "TOKEN_NOT_FOUND"
  | "SESSION_NOT_AUTHORIZED"
  | "DELIVERY_BINDING_NOT_FOUND"
  | "DELIVERY_BINDING_CONFLICT"
  | "DELIVERY_BINDING_INACTIVE"
  | "DELIVERY_STRATEGY_MISMATCH"
  | "DELIVERY_BINDING_REGISTRATION_FAILED";

const ERROR_MESSAGES: Readonly<Record<LiveActivityDeliveryServiceErrorCode, string>> = {
  TOKEN_GENERATION_CONFLICT: "ActivityKit token generation conflict",
  TOKEN_NOT_FOUND: "ActivityKit token was not found",
  SESSION_NOT_AUTHORIZED: "Live commute session is not authorized for delivery",
  DELIVERY_BINDING_NOT_FOUND: "Live Activity delivery binding was not found",
  DELIVERY_BINDING_CONFLICT: "Live Activity delivery binding conflicts with stored state",
  DELIVERY_BINDING_INACTIVE: "Live Activity delivery binding is inactive",
  DELIVERY_STRATEGY_MISMATCH: "Live Activity delivery strategy mismatch",
  DELIVERY_BINDING_REGISTRATION_FAILED: "Live Activity delivery binding registration failed",
};

export class LiveActivityDeliveryServiceError extends Error {
  readonly code: LiveActivityDeliveryServiceErrorCode;

  constructor(code: LiveActivityDeliveryServiceErrorCode) {
    super(ERROR_MESSAGES[code]);
    this.name = "LiveActivityDeliveryServiceError";
    this.code = code;
  }
}

export interface RegisterActivityKitTokenInput {
  readonly token: Uint8Array;
  readonly clientGeneration: number;
  readonly environment: ApplePushEnvironment;
}

export interface InvalidateActivityKitTokenInput {
  readonly expectedClientGeneration: number;
}

export interface RegisterActivityKitTokenResult {
  readonly status: "REGISTERED" | "ROTATED" | "UNCHANGED";
  readonly token: ActivityKitTokenMetadata;
}

export interface InvalidateActivityKitTokenResult {
  readonly status: "INVALIDATED" | "UNCHANGED";
  readonly token: ActivityKitTokenMetadata;
}

export interface CreateLiveActivityDeliveryBindingInput {
  readonly sessionId: string;
  readonly sessionRevision: number;
  readonly strategy: LiveActivityDeliveryStrategy;
  readonly appleActivityId?: string | null;
}

export interface CreateLiveActivityDeliveryBindingResult {
  readonly status: "CREATED" | "UNCHANGED" | "ALREADY_TERMINAL";
  readonly binding: LiveActivityDeliveryBinding;
}

export interface AttachLiveActivityIdentifierInput {
  readonly bindingId: string;
  readonly appleActivityId: string;
}

export interface AttachLiveActivityIdentifierResult {
  readonly status: "ATTACHED" | "UNCHANGED";
  readonly binding: LiveActivityDeliveryBinding;
}

export interface MutateLiveActivityDeliveryBindingInput {
  readonly bindingId: string;
}

export interface MutateLiveActivityDeliveryBindingResult {
  readonly status: "ENDED" | "INVALIDATED" | "UNCHANGED";
  readonly binding: LiveActivityDeliveryBinding;
}

export interface RegisterLiveActivityUpdateTokenInput
  extends RegisterActivityKitTokenInput {
  readonly bindingId: string;
}

export interface InvalidateLiveActivityUpdateTokenInput
  extends InvalidateActivityKitTokenInput {
  readonly bindingId: string;
}

export interface LiveActivityDeliveryServiceOptions {
  readonly now?: () => Date;
  readonly randomUuid?: () => string;
}

function readClock(now: () => Date): Date {
  const value = now();
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

function effectiveMutationTime(now: () => Date, previousUpdatedAt?: Date): Date {
  const requested = readClock(now);
  if (previousUpdatedAt == null || requested.getTime() >= previousUpdatedAt.getTime()) {
    return requested;
  }
  return new Date(previousUpdatedAt);
}

function frozen<T extends object>(value: T): Readonly<T> {
  return Object.freeze(value);
}

function tokenMetadata(record: StoredPushToStartToken | StoredLiveActivityUpdateToken) {
  return safeActivityKitTokenMetadata(record);
}

function sameProtectedIdentity(
  left: StoredPushToStartToken | StoredLiveActivityUpdateToken,
  digest: string,
  environment: ApplePushEnvironment,
): boolean {
  return left.protectedToken.digest === digest && left.environment === environment;
}

function assertSessionAuthority(
  session: StoredLiveCommuteSession | undefined,
  expectedRevision: number,
  now: Date,
): StoredLiveCommuteSession {
  if (
    session == null ||
    session.lifecycle !== "REGISTERED" ||
    session.revision !== expectedRevision ||
    session.session.endsAt.getTime() <= now.getTime()
  ) {
    throw new LiveActivityDeliveryServiceError("SESSION_NOT_AUTHORIZED");
  }
  return session;
}

function assertOwnedBinding(
  binding: LiveActivityDeliveryBinding | undefined,
  installationId: string,
): LiveActivityDeliveryBinding {
  if (binding == null || binding.installationId !== installationId) {
    throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_NOT_FOUND");
  }
  return binding;
}

function bindingSpecificationMatches(
  binding: LiveActivityDeliveryBinding,
  input: CreateLiveActivityDeliveryBindingInput,
): boolean {
  return (
    binding.strategy === input.strategy &&
    (input.appleActivityId == null || binding.appleActivityId === input.appleActivityId)
  );
}

export class LiveActivityDeliveryService {
  private readonly now: () => Date;
  private readonly randomUuid: () => string;

  constructor(
    private readonly store: LiveActivityDeliveryStore,
    private readonly tokenProtector: ActivityKitTokenProtector,
    options: LiveActivityDeliveryServiceOptions = {},
  ) {
    this.now = options.now ?? (() => new Date());
    this.randomUuid = options.randomUuid ?? nodeRandomUuid;
  }

  async registerPushToStartToken(
    authentication: LiveCommuteInstallationAuthentication,
    input: RegisterActivityKitTokenInput,
  ): Promise<RegisterActivityKitTokenResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const token = normalizeActivityKitToken(input.token);
    const clientGeneration = positiveActivityKitGeneration(
      input.clientGeneration,
      "clientGeneration",
    );
    const environment = normalizedApplePushEnvironment(input.environment);
    const context = activityKitPushToStartProtectionContext(
      proof.installationId,
      clientGeneration,
      environment,
    );
    const digest = this.tokenProtector.digest(token);

    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      const existingGeneration = await transaction.getPushToStartToken(clientGeneration);
      const latest = await transaction.getLatestPushToStartToken();
      if (existingGeneration != null) {
        if (
          existingGeneration.lifecycle === "CURRENT" &&
          sameProtectedIdentity(existingGeneration, digest, environment)
        ) {
          return frozen({ status: "UNCHANGED", token: tokenMetadata(existingGeneration) });
        }
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }
      if (latest != null && clientGeneration <= latest.clientGeneration) {
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }

      const changedAt = effectiveMutationTime(this.now, latest?.updatedAt);
      if (latest?.lifecycle === "CURRENT") {
        await transaction.savePushToStartToken(
          createStoredPushToStartToken({
            ...latest,
            lifecycle: "REPLACED",
            updatedAt: changedAt,
            replacedAt: changedAt,
          }),
        );
      }
      const created = createStoredPushToStartToken({
        installationId: proof.installationId,
        clientGeneration,
        serverRevision: (latest?.serverRevision ?? 0) + 1,
        environment,
        lifecycle: "CURRENT",
        protectedToken: this.tokenProtector.protect(token, context),
        createdAt: changedAt,
        updatedAt: changedAt,
        replacedAt: null,
        invalidatedAt: null,
      });
      await transaction.savePushToStartToken(created);
      return frozen({
        status: latest == null ? "REGISTERED" : "ROTATED",
        token: tokenMetadata(created),
      });
    });
  }

  async invalidatePushToStartToken(
    authentication: LiveCommuteInstallationAuthentication,
    input: InvalidateActivityKitTokenInput,
  ): Promise<InvalidateActivityKitTokenResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const expected = positiveActivityKitGeneration(
      input.expectedClientGeneration,
      "expectedClientGeneration",
    );
    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      const latest = await transaction.getLatestPushToStartToken();
      if (latest == null) {
        throw new LiveActivityDeliveryServiceError("TOKEN_NOT_FOUND");
      }
      if (latest.clientGeneration !== expected) {
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }
      if (latest.lifecycle === "INVALIDATED") {
        return frozen({ status: "UNCHANGED", token: tokenMetadata(latest) });
      }
      if (latest.lifecycle !== "CURRENT") {
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }
      const invalidatedAt = effectiveMutationTime(this.now, latest.updatedAt);
      const invalidated = createStoredPushToStartToken({
        ...latest,
        lifecycle: "INVALIDATED",
        updatedAt: invalidatedAt,
        replacedAt: null,
        invalidatedAt,
      });
      await transaction.savePushToStartToken(invalidated);
      return frozen({ status: "INVALIDATED", token: tokenMetadata(invalidated) });
    });
  }

  async createDeliveryBinding(
    authentication: LiveCommuteInstallationAuthentication,
    input: CreateLiveActivityDeliveryBindingInput,
  ): Promise<CreateLiveActivityDeliveryBindingResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const sessionId = normalizedAppleDeliveryIdentifier(input.sessionId, "sessionId");
    const sessionRevision = positiveActivityKitGeneration(
      input.sessionRevision,
      "sessionRevision",
    );
    const strategy = normalizedLiveActivityDeliveryStrategy(input.strategy);
    const appleActivityId =
      input.appleActivityId == null
        ? null
        : normalizedAppleDeliveryIdentifier(
            input.appleActivityId,
            "appleActivityId",
            APPLE_ACTIVITY_IDENTIFIER_MAX_CHARACTERS,
          );

    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      assertSessionAuthority(
        await transaction.getSession(sessionId),
        sessionRevision,
        readClock(this.now),
      );
      const existing = await transaction.getDeliveryBindingForSessionVersion(
        sessionId,
        sessionRevision,
      );
      if (existing != null) {
        if (
          !bindingSpecificationMatches(existing, {
            ...input,
            strategy,
            appleActivityId,
          })
        ) {
          throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_CONFLICT");
        }
        return frozen({
          status: existing.lifecycle === "PENDING_START" ? "UNCHANGED" : "ALREADY_TERMINAL",
          binding: existing,
        });
      }

      const createdAt = readClock(this.now);
      for (let attempt = 0; attempt < BINDING_CREATION_ATTEMPTS; attempt += 1) {
        let binding: LiveActivityDeliveryBinding;
        try {
          binding = createLiveActivityDeliveryBinding({
            bindingId: this.randomUuid(),
            installationId: proof.installationId,
            sessionId,
            sessionRevision,
            strategy,
            lifecycle: "PENDING_START",
            appleActivityId,
            createdAt,
            updatedAt: createdAt,
            endedAt: null,
            invalidatedAt: null,
          });
        } catch {
          throw new LiveActivityDeliveryServiceError(
            "DELIVERY_BINDING_REGISTRATION_FAILED",
          );
        }
        if (await transaction.saveDeliveryBinding(binding)) {
          return frozen({ status: "CREATED", binding });
        }
      }
      throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_REGISTRATION_FAILED");
    });
  }

  async registerUpdateToken(
    authentication: LiveCommuteInstallationAuthentication,
    input: RegisterLiveActivityUpdateTokenInput,
  ): Promise<RegisterActivityKitTokenResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const bindingId = normalizedLiveActivityBindingId(input.bindingId);
    const token = normalizeActivityKitToken(input.token);
    const clientGeneration = positiveActivityKitGeneration(
      input.clientGeneration,
      "clientGeneration",
    );
    const environment = normalizedApplePushEnvironment(input.environment);
    const context = liveActivityUpdateProtectionContext(
      proof.installationId,
      bindingId,
      clientGeneration,
      environment,
    );
    const digest = this.tokenProtector.digest(token);

    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      const binding = assertOwnedBinding(
        await transaction.getDeliveryBinding(bindingId),
        proof.installationId,
      );
      if (binding.lifecycle !== "PENDING_START") {
        throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_INACTIVE");
      }
      if (binding.strategy !== "DIRECT_TOKEN") {
        throw new LiveActivityDeliveryServiceError("DELIVERY_STRATEGY_MISMATCH");
      }
      assertSessionAuthority(
        await transaction.getSession(binding.sessionId),
        binding.sessionRevision,
        readClock(this.now),
      );

      const existingGeneration = await transaction.getUpdateToken(
        bindingId,
        clientGeneration,
      );
      const latest = await transaction.getLatestUpdateToken(bindingId);
      if (existingGeneration != null) {
        if (
          existingGeneration.lifecycle === "CURRENT" &&
          sameProtectedIdentity(existingGeneration, digest, environment)
        ) {
          return frozen({ status: "UNCHANGED", token: tokenMetadata(existingGeneration) });
        }
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }
      if (latest != null && clientGeneration <= latest.clientGeneration) {
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }

      const changedAt = effectiveMutationTime(this.now, latest?.updatedAt);
      if (latest?.lifecycle === "CURRENT") {
        await transaction.saveUpdateToken(
          createStoredLiveActivityUpdateToken({
            ...latest,
            lifecycle: "REPLACED",
            updatedAt: changedAt,
            replacedAt: changedAt,
          }),
        );
      }
      const created = createStoredLiveActivityUpdateToken({
        installationId: proof.installationId,
        bindingId,
        clientGeneration,
        serverRevision: (latest?.serverRevision ?? 0) + 1,
        environment,
        lifecycle: "CURRENT",
        protectedToken: this.tokenProtector.protect(token, context),
        createdAt: changedAt,
        updatedAt: changedAt,
        replacedAt: null,
        invalidatedAt: null,
      });
      await transaction.saveUpdateToken(created);
      return frozen({
        status: latest == null ? "REGISTERED" : "ROTATED",
        token: tokenMetadata(created),
      });
    });
  }

  async attachAppleActivityIdentifier(
    authentication: LiveCommuteInstallationAuthentication,
    input: AttachLiveActivityIdentifierInput,
  ): Promise<AttachLiveActivityIdentifierResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const bindingId = normalizedLiveActivityBindingId(input.bindingId);
    const appleActivityId = normalizedAppleDeliveryIdentifier(
      input.appleActivityId,
      "appleActivityId",
      APPLE_ACTIVITY_IDENTIFIER_MAX_CHARACTERS,
    );

    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      const binding = assertOwnedBinding(
        await transaction.getDeliveryBinding(bindingId),
        proof.installationId,
      );
      if (binding.lifecycle !== "PENDING_START") {
        throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_INACTIVE");
      }
      assertSessionAuthority(
        await transaction.getSession(binding.sessionId),
        binding.sessionRevision,
        readClock(this.now),
      );
      if (binding.appleActivityId === appleActivityId) {
        return frozen({ status: "UNCHANGED", binding });
      }
      if (binding.appleActivityId != null) {
        throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_CONFLICT");
      }
      const updatedAt = effectiveMutationTime(this.now, binding.updatedAt);
      const attached = createLiveActivityDeliveryBinding({
        ...binding,
        appleActivityId,
        updatedAt,
      });
      if (!(await transaction.saveDeliveryBinding(attached))) {
        throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_CONFLICT");
      }
      return frozen({ status: "ATTACHED", binding: attached });
    });
  }

  async invalidateUpdateToken(
    authentication: LiveCommuteInstallationAuthentication,
    input: InvalidateLiveActivityUpdateTokenInput,
  ): Promise<InvalidateActivityKitTokenResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const bindingId = normalizedLiveActivityBindingId(input.bindingId);
    const expected = positiveActivityKitGeneration(
      input.expectedClientGeneration,
      "expectedClientGeneration",
    );

    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      const binding = assertOwnedBinding(
        await transaction.getDeliveryBinding(bindingId),
        proof.installationId,
      );
      if (binding.strategy !== "DIRECT_TOKEN") {
        throw new LiveActivityDeliveryServiceError("DELIVERY_STRATEGY_MISMATCH");
      }
      const latest = await transaction.getLatestUpdateToken(bindingId);
      if (latest == null) {
        throw new LiveActivityDeliveryServiceError("TOKEN_NOT_FOUND");
      }
      if (latest.clientGeneration !== expected) {
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }
      if (latest.lifecycle === "INVALIDATED") {
        return frozen({ status: "UNCHANGED", token: tokenMetadata(latest) });
      }
      if (latest.lifecycle !== "CURRENT") {
        throw new LiveActivityDeliveryServiceError("TOKEN_GENERATION_CONFLICT");
      }
      const invalidatedAt = effectiveMutationTime(this.now, latest.updatedAt);
      const invalidated = createStoredLiveActivityUpdateToken({
        ...latest,
        lifecycle: "INVALIDATED",
        updatedAt: invalidatedAt,
        replacedAt: null,
        invalidatedAt,
      });
      await transaction.saveUpdateToken(invalidated);
      return frozen({ status: "INVALIDATED", token: tokenMetadata(invalidated) });
    });
  }

  async endDeliveryBinding(
    authentication: LiveCommuteInstallationAuthentication,
    input: MutateLiveActivityDeliveryBindingInput,
  ): Promise<MutateLiveActivityDeliveryBindingResult> {
    return await this.terminalizeBinding(authentication, input, "ENDED");
  }

  async invalidateDeliveryBinding(
    authentication: LiveCommuteInstallationAuthentication,
    input: MutateLiveActivityDeliveryBindingInput,
  ): Promise<MutateLiveActivityDeliveryBindingResult> {
    return await this.terminalizeBinding(authentication, input, "INVALIDATED");
  }

  private async terminalizeBinding(
    authentication: LiveCommuteInstallationAuthentication,
    input: MutateLiveActivityDeliveryBindingInput,
    lifecycle: Exclude<LiveActivityDeliveryLifecycle, "PENDING_START">,
  ): Promise<MutateLiveActivityDeliveryBindingResult> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    const bindingId = normalizedLiveActivityBindingId(input.bindingId);
    return await this.withAuthenticatedInstallation(proof, async (transaction) => {
      const binding = assertOwnedBinding(
        await transaction.getDeliveryBinding(bindingId),
        proof.installationId,
      );
      if (binding.lifecycle === lifecycle) {
        return frozen({ status: "UNCHANGED", binding });
      }
      if (binding.lifecycle !== "PENDING_START") {
        throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_INACTIVE");
      }
      const currentToken = await transaction.getLatestUpdateToken(bindingId);
      const previousUpdatedAt =
        currentToken == null ||
        currentToken.updatedAt.getTime() <= binding.updatedAt.getTime()
          ? binding.updatedAt
          : currentToken.updatedAt;
      const terminalAt = effectiveMutationTime(this.now, previousUpdatedAt);
      const terminal = createLiveActivityDeliveryBinding({
        ...binding,
        lifecycle,
        updatedAt: terminalAt,
        endedAt: lifecycle === "ENDED" ? terminalAt : null,
        invalidatedAt: lifecycle === "INVALIDATED" ? terminalAt : null,
      });
      const saved = await transaction.saveDeliveryBinding(terminal);
      if (!saved) {
        throw new LiveActivityDeliveryServiceError("DELIVERY_BINDING_CONFLICT");
      }
      if (currentToken?.lifecycle === "CURRENT") {
        await transaction.saveUpdateToken(
          createStoredLiveActivityUpdateToken({
            ...currentToken,
            lifecycle: "INVALIDATED",
            updatedAt: terminalAt,
            replacedAt: null,
            invalidatedAt: terminalAt,
          }),
        );
      }
      return frozen({ status: lifecycle, binding: terminal });
    });
  }

  private async withAuthenticatedInstallation<T>(
    proof: LiveCommuteInstallationAuthenticationProof,
    operation: (
      transaction: LiveActivityDeliveryInstallationTransaction,
      installation: StoredLiveCommuteInstallation,
    ) => Promise<T>,
  ): Promise<T> {
    return await this.store.withInstallationTransaction(
      proof.installationId,
      async (transaction) => {
        const installation = await transaction.getInstallation();
        if (
          installation == null ||
          installation.state !== "ACTIVE" ||
          !liveCommuteInstallationCredentialMatches(
            installation.credentialDigest,
            proof.digest,
          )
        ) {
          throw new LiveCommuteSessionServiceError(
            "INSTALLATION_AUTHENTICATION_FAILED",
          );
        }
        return await operation(transaction, installation);
      },
    );
  }
}

export function createLiveActivityDeliveryService(
  store: LiveActivityDeliveryStore,
  tokenProtector: ActivityKitTokenProtector,
  options?: LiveActivityDeliveryServiceOptions,
): LiveActivityDeliveryService {
  return new LiveActivityDeliveryService(store, tokenProtector, options);
}
