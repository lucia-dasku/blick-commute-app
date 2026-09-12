import {
  createStoredLiveCommuteInstallation,
  createStoredLiveCommuteSession,
  type LiveCommuteInstallationTransaction,
  type LiveCommuteSessionVersionRef,
  type LiveCommuteSessionStore,
  type StoredLiveCommuteInstallation,
  type StoredLiveCommuteSession,
} from "../sessionStore.js";
import {
  createLiveActivityDeliveryBinding,
  createStoredLiveActivityUpdateToken,
  createStoredPushToStartToken,
  normalizedAppleDeliveryIdentifier,
  normalizedLiveActivityBindingId,
  positiveActivityKitGeneration,
  type LiveActivityDeliveryBinding,
  type StoredLiveActivityUpdateToken,
  type StoredPushToStartToken,
} from "./deliveryModel.js";
import type {
  LiveActivityDeliveryInstallationTransaction,
  LiveActivityDeliveryStore,
  LiveActivityPublicationBindingState,
} from "./deliveryStore.js";
import type { ProtectedActivityKitToken } from "./tokenProtection.js";

type StoredActivityKitToken =
  | StoredPushToStartToken
  | StoredLiveActivityUpdateToken;

interface InstallationDeliveryState {
  readonly pushToStartTokens: Map<number, StoredPushToStartToken>;
  readonly bindings: Map<string, LiveActivityDeliveryBinding>;
  readonly updateTokens: Map<string, Map<number, StoredLiveActivityUpdateToken>>;
}

function transitionFailure(subject: string): Error {
  return new Error(`${subject} transition is invalid`);
}

function sameDate(left: Date | null, right: Date | null): boolean {
  return left == null || right == null
    ? left === right
    : left.getTime() === right.getTime();
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return Buffer.from(left).equals(Buffer.from(right));
}

function sameProtectedToken(
  left: ProtectedActivityKitToken,
  right: ProtectedActivityKitToken,
): boolean {
  return (
    left.version === right.version &&
    left.algorithm === right.algorithm &&
    left.digest === right.digest &&
    sameBytes(left.nonce, right.nonce) &&
    sameBytes(left.authenticationTag, right.authenticationTag) &&
    sameBytes(left.ciphertext, right.ciphertext)
  );
}

function sameTokenScope(
  left: StoredActivityKitToken,
  right: StoredActivityKitToken,
): boolean {
  if (left.installationId !== right.installationId) return false;
  const leftBinding = "bindingId" in left ? left.bindingId : undefined;
  const rightBinding = "bindingId" in right ? right.bindingId : undefined;
  return leftBinding === rightBinding;
}

function sameTokenRecord(
  left: StoredActivityKitToken,
  right: StoredActivityKitToken,
): boolean {
  return (
    sameTokenScope(left, right) &&
    left.clientGeneration === right.clientGeneration &&
    left.serverRevision === right.serverRevision &&
    left.environment === right.environment &&
    left.lifecycle === right.lifecycle &&
    sameDate(left.createdAt, right.createdAt) &&
    sameDate(left.updatedAt, right.updatedAt) &&
    sameDate(left.replacedAt, right.replacedAt) &&
    sameDate(left.invalidatedAt, right.invalidatedAt) &&
    sameProtectedToken(left.protectedToken, right.protectedToken)
  );
}

function sameImmutableTokenFields(
  left: StoredActivityKitToken,
  right: StoredActivityKitToken,
): boolean {
  return (
    sameTokenScope(left, right) &&
    left.clientGeneration === right.clientGeneration &&
    left.serverRevision === right.serverRevision &&
    left.environment === right.environment &&
    sameDate(left.createdAt, right.createdAt) &&
    sameProtectedToken(left.protectedToken, right.protectedToken)
  );
}

function latestToken<T extends StoredActivityKitToken>(
  history: ReadonlyMap<number, T>,
): T | undefined {
  let latest: T | undefined;
  for (const token of history.values()) {
    if (latest == null || token.clientGeneration > latest.clientGeneration) {
      latest = token;
    }
  }
  return latest;
}

function saveToken<T extends StoredActivityKitToken>(
  history: Map<number, T>,
  candidate: T,
  subject: string,
): boolean {
  const current = history.get(candidate.clientGeneration);
  if (current != null) {
    if (sameTokenRecord(current, candidate)) return false;
    if (
      current.lifecycle !== "CURRENT" ||
      candidate.lifecycle === "CURRENT" ||
      !sameImmutableTokenFields(current, candidate) ||
      candidate.updatedAt.getTime() < current.updatedAt.getTime()
    ) {
      throw transitionFailure(subject);
    }
    history.set(candidate.clientGeneration, candidate);
    return true;
  }

  const latest = latestToken(history);
  if (
    candidate.lifecycle !== "CURRENT" ||
    (latest == null
      ? candidate.serverRevision !== 1
      : candidate.clientGeneration <= latest.clientGeneration ||
        candidate.serverRevision !== latest.serverRevision + 1 ||
        candidate.createdAt.getTime() < latest.updatedAt.getTime())
  ) {
    throw transitionFailure(subject);
  }
  history.set(candidate.clientGeneration, candidate);
  return true;
}

function validateTokenHistory<T extends StoredActivityKitToken>(
  history: ReadonlyMap<number, T>,
  copy: (token: T) => T,
  subject: string,
): void {
  const revisions = new Set<number>();
  const currentTokens: T[] = [];
  for (const [generation, input] of history) {
    const token = copy(input);
    if (generation !== token.clientGeneration || revisions.has(token.serverRevision)) {
      throw transitionFailure(subject);
    }
    revisions.add(token.serverRevision);
    if (token.lifecycle === "CURRENT") currentTokens.push(token);
  }
  if (currentTokens.length > 1) throw transitionFailure(subject);

  const latest = latestToken(history);
  if (latest == null) return;
  if (currentTokens.length === 0) {
    if (latest.lifecycle !== "INVALIDATED") throw transitionFailure(subject);
    return;
  }
  if (
    currentTokens[0]!.clientGeneration !== latest.clientGeneration ||
    currentTokens[0]!.serverRevision !== latest.serverRevision
  ) {
    throw transitionFailure(subject);
  }
}

function sameBinding(
  left: LiveActivityDeliveryBinding,
  right: LiveActivityDeliveryBinding,
): boolean {
  return (
    left.bindingId === right.bindingId &&
    left.installationId === right.installationId &&
    left.sessionId === right.sessionId &&
    left.sessionRevision === right.sessionRevision &&
    left.strategy === right.strategy &&
    left.lifecycle === right.lifecycle &&
    left.appleActivityId === right.appleActivityId &&
    sameDate(left.createdAt, right.createdAt) &&
    sameDate(left.updatedAt, right.updatedAt) &&
    sameDate(left.endedAt, right.endedAt) &&
    sameDate(left.invalidatedAt, right.invalidatedAt)
  );
}

function sameBindingIdentity(
  left: LiveActivityDeliveryBinding,
  right: LiveActivityDeliveryBinding,
): boolean {
  return (
    left.bindingId === right.bindingId &&
    left.installationId === right.installationId &&
    left.sessionId === right.sessionId &&
    left.sessionRevision === right.sessionRevision &&
    left.strategy === right.strategy &&
    sameDate(left.createdAt, right.createdAt)
  );
}

function sessionVersionKey(sessionId: string, revision: number): string {
  return JSON.stringify([sessionId, revision]);
}

function ownedSessionVersionKey(reference: LiveCommuteSessionVersionRef): string {
  return JSON.stringify([
    normalizedAppleDeliveryIdentifier(reference.installationId, "installationId"),
    normalizedAppleDeliveryIdentifier(reference.sessionId, "sessionId"),
    positiveActivityKitGeneration(reference.revision, "revision"),
  ]);
}

function emptyState(): InstallationDeliveryState {
  return {
    pushToStartTokens: new Map(),
    bindings: new Map(),
    updateTokens: new Map(),
  };
}

function cloneState(input: InstallationDeliveryState | undefined): InstallationDeliveryState {
  if (input == null) return emptyState();
  return {
    pushToStartTokens: new Map(
      [...input.pushToStartTokens].map(([generation, token]) => [
        generation,
        createStoredPushToStartToken(token),
      ]),
    ),
    bindings: new Map(
      [...input.bindings].map(([bindingId, binding]) => [
        bindingId,
        createLiveActivityDeliveryBinding(binding),
      ]),
    ),
    updateTokens: new Map(
      [...input.updateTokens].map(([bindingId, history]) => [
        bindingId,
        new Map(
          [...history].map(([generation, token]) => [
            generation,
            createStoredLiveActivityUpdateToken(token),
          ]),
        ),
      ]),
    ),
  };
}

async function activeInstallation(
  transaction: LiveCommuteInstallationTransaction,
): Promise<StoredLiveCommuteInstallation> {
  const input = await transaction.getInstallation();
  if (input == null) throw new Error("delivery installation does not exist");
  const installation = createStoredLiveCommuteInstallation(input);
  if (installation.state !== "ACTIVE") {
    throw new Error("delivery installation is not active");
  }
  return installation;
}

async function authoritativeSession(
  transaction: LiveCommuteInstallationTransaction,
  sessionId: string,
  revision: number,
): Promise<StoredLiveCommuteSession> {
  const input = await transaction.getSession(sessionId);
  if (input == null) throw new Error("delivery session does not exist");
  const session = createStoredLiveCommuteSession(input);
  if (session.lifecycle !== "REGISTERED" || session.revision !== revision) {
    throw new Error("delivery session is not authoritative");
  }
  return session;
}

function validateState(
  installationId: string,
  state: InstallationDeliveryState,
): void {
  for (const token of state.pushToStartTokens.values()) {
    if (createStoredPushToStartToken(token).installationId !== installationId) {
      throw transitionFailure("push-to-start token");
    }
  }
  validateTokenHistory(
    state.pushToStartTokens,
    createStoredPushToStartToken,
    "push-to-start token",
  );

  const sessionVersions = new Set<string>();
  const appleActivityIds = new Set<string>();
  for (const [bindingId, input] of state.bindings) {
    const binding = createLiveActivityDeliveryBinding(input);
    if (bindingId !== binding.bindingId || binding.installationId !== installationId) {
      throw transitionFailure("delivery binding");
    }
    const versionKey = sessionVersionKey(binding.sessionId, binding.sessionRevision);
    if (sessionVersions.has(versionKey)) throw transitionFailure("delivery binding");
    sessionVersions.add(versionKey);
    if (binding.appleActivityId != null) {
      if (appleActivityIds.has(binding.appleActivityId)) {
        throw transitionFailure("delivery binding");
      }
      appleActivityIds.add(binding.appleActivityId);
    }
  }

  for (const [bindingId, history] of state.updateTokens) {
    const binding = state.bindings.get(bindingId);
    if (binding == null || binding.strategy !== "DIRECT_TOKEN") {
      throw transitionFailure("Live Activity update token");
    }
    for (const token of history.values()) {
      const stored = createStoredLiveActivityUpdateToken(token);
      if (
        stored.installationId !== installationId ||
        stored.bindingId !== bindingId
      ) {
        throw transitionFailure("Live Activity update token");
      }
    }
    validateTokenHistory(
      history,
      createStoredLiveActivityUpdateToken,
      "Live Activity update token",
    );
  }
}

/**
 * Deterministic adapter for tests. Apple state is staged while the Phase 3A parent
 * transaction owns its installation lock, so failed callbacks leave both stores unchanged.
 */
export class InMemoryLiveActivityDeliveryStore implements LiveActivityDeliveryStore {
  private readonly states = new Map<string, InstallationDeliveryState>();

  constructor(private readonly coreStore: LiveCommuteSessionStore) {}

  async listDeliveryBindingsForSessionVersions(
    references: readonly LiveCommuteSessionVersionRef[],
  ): Promise<readonly LiveActivityPublicationBindingState[]> {
    if (!Array.isArray(references)) {
      throw new TypeError("session version references must be an array");
    }
    const requested = new Set(references.map(ownedSessionVersionKey));
    const bindings = [...this.states.values()]
      .flatMap((state) =>
        [...state.bindings.values()].map((binding) => ({ binding, state })),
      )
      .filter(({ binding }) =>
        requested.has(
          ownedSessionVersionKey({
            installationId: binding.installationId,
            sessionId: binding.sessionId,
            revision: binding.sessionRevision,
          }),
        ),
      )
      .map(({ binding, state }) =>
        Object.freeze({
          binding: createLiveActivityDeliveryBinding(binding),
          hasUpdateTokenHistory:
            (state.updateTokens.get(binding.bindingId)?.size ?? 0) > 0,
        }),
      )
      .sort(
        (left, right) =>
          left.binding.installationId.localeCompare(right.binding.installationId) ||
          left.binding.sessionId.localeCompare(right.binding.sessionId) ||
          left.binding.sessionRevision - right.binding.sessionRevision ||
          left.binding.bindingId.localeCompare(right.binding.bindingId),
      );
    return Object.freeze(bindings);
  }

  async withInstallationTransaction<T>(
    installationId: string,
    operation: (transaction: LiveActivityDeliveryInstallationTransaction) => Promise<T>,
  ): Promise<T> {
    const normalizedInstallationId = normalizedAppleDeliveryIdentifier(
      installationId,
      "installationId",
    );

    return await this.coreStore.withInstallationTransaction(
      normalizedInstallationId,
      async (coreTransaction) => {
        const staged = cloneState(this.states.get(normalizedInstallationId));
        let changed = false;

        const transaction: LiveActivityDeliveryInstallationTransaction = {
          getInstallation: async () => {
            const record = await coreTransaction.getInstallation();
            return record == null
              ? undefined
              : createStoredLiveCommuteInstallation(record);
          },
          getSession: async (sessionId) => {
            const record = await coreTransaction.getSession(
              normalizedAppleDeliveryIdentifier(sessionId, "sessionId"),
            );
            return record == null ? undefined : createStoredLiveCommuteSession(record);
          },
          getLatestPushToStartToken: async () => {
            const token = latestToken(staged.pushToStartTokens);
            return token == null ? undefined : createStoredPushToStartToken(token);
          },
          getPushToStartToken: async (clientGeneration) => {
            const token = staged.pushToStartTokens.get(
              positiveActivityKitGeneration(clientGeneration, "clientGeneration"),
            );
            return token == null ? undefined : createStoredPushToStartToken(token);
          },
          savePushToStartToken: async (input) => {
            await activeInstallation(coreTransaction);
            const token = createStoredPushToStartToken(input);
            if (token.installationId !== normalizedInstallationId) {
              throw new Error("push-to-start token ownership mismatch");
            }
            changed =
              saveToken(
                staged.pushToStartTokens,
                token,
                "push-to-start token",
              ) || changed;
          },
          getDeliveryBinding: async (bindingId) => {
            const binding = staged.bindings.get(normalizedLiveActivityBindingId(bindingId));
            return binding == null
              ? undefined
              : createLiveActivityDeliveryBinding(binding);
          },
          getDeliveryBindingForSessionVersion: async (sessionId, sessionRevision) => {
            const wanted = sessionVersionKey(
              normalizedAppleDeliveryIdentifier(sessionId, "sessionId"),
              positiveActivityKitGeneration(sessionRevision, "sessionRevision"),
            );
            const binding = [...staged.bindings.values()].find(
              (candidate) =>
                sessionVersionKey(candidate.sessionId, candidate.sessionRevision) === wanted,
            );
            return binding == null
              ? undefined
              : createLiveActivityDeliveryBinding(binding);
          },
          saveDeliveryBinding: async (input) => {
            await activeInstallation(coreTransaction);
            const binding = createLiveActivityDeliveryBinding(input);
            if (binding.installationId !== normalizedInstallationId) {
              throw new Error("delivery binding ownership mismatch");
            }
            if (
              binding.appleActivityId != null &&
              [...staged.bindings.values()].some(
                (candidate) =>
                  candidate.bindingId !== binding.bindingId &&
                  candidate.appleActivityId === binding.appleActivityId,
              )
            ) {
              return false;
            }

            const existing = staged.bindings.get(binding.bindingId);
            if (existing == null) {
              if (binding.lifecycle !== "PENDING_START") {
                throw transitionFailure("delivery binding");
              }
              const versionKey = sessionVersionKey(
                binding.sessionId,
                binding.sessionRevision,
              );
              if (
                [...staged.bindings.values()].some(
                  (candidate) =>
                    sessionVersionKey(
                      candidate.sessionId,
                      candidate.sessionRevision,
                    ) === versionKey,
                ) ||
                [...this.states.entries()].some(
                  ([owner, state]) =>
                    owner !== normalizedInstallationId &&
                    state.bindings.has(binding.bindingId),
                )
              ) {
                return false;
              }
              await authoritativeSession(
                coreTransaction,
                binding.sessionId,
                binding.sessionRevision,
              );
              staged.bindings.set(binding.bindingId, binding);
              changed = true;
              return true;
            }

            if (sameBinding(existing, binding)) return true;
            if (
              existing.installationId !== binding.installationId ||
              existing.sessionId !== binding.sessionId ||
              existing.sessionRevision !== binding.sessionRevision
            ) {
              return false;
            }
            const attachesActivityIdentifier =
              existing.lifecycle === "PENDING_START" &&
              binding.lifecycle === "PENDING_START" &&
              existing.appleActivityId == null &&
              binding.appleActivityId != null;
            const terminalizesBinding =
              existing.lifecycle === "PENDING_START" &&
              (binding.lifecycle === "ENDED" ||
                binding.lifecycle === "INVALIDATED") &&
              binding.appleActivityId === existing.appleActivityId;
            if (
              !sameBindingIdentity(existing, binding) ||
              (!attachesActivityIdentifier && !terminalizesBinding) ||
              binding.updatedAt.getTime() < existing.updatedAt.getTime()
            ) {
              throw transitionFailure("delivery binding");
            }
            staged.bindings.set(binding.bindingId, binding);
            changed = true;
            return true;
          },
          getLatestUpdateToken: async (bindingId) => {
            const normalizedBindingId = normalizedLiveActivityBindingId(bindingId);
            const token = latestToken(
              staged.updateTokens.get(normalizedBindingId) ?? new Map(),
            );
            return token == null
              ? undefined
              : createStoredLiveActivityUpdateToken(token);
          },
          getUpdateToken: async (bindingId, clientGeneration) => {
            const normalizedBindingId = normalizedLiveActivityBindingId(bindingId);
            const token = staged.updateTokens
              .get(normalizedBindingId)
              ?.get(
                positiveActivityKitGeneration(clientGeneration, "clientGeneration"),
              );
            return token == null
              ? undefined
              : createStoredLiveActivityUpdateToken(token);
          },
          saveUpdateToken: async (input) => {
            await activeInstallation(coreTransaction);
            const token = createStoredLiveActivityUpdateToken(input);
            if (token.installationId !== normalizedInstallationId) {
              throw new Error("Live Activity update token ownership mismatch");
            }
            const binding = staged.bindings.get(token.bindingId);
            if (
              binding == null ||
              binding.installationId !== normalizedInstallationId ||
              binding.strategy !== "DIRECT_TOKEN"
            ) {
              throw new Error("Live Activity update token binding is invalid");
            }
            let history = staged.updateTokens.get(token.bindingId);
            if (history == null) {
              history = new Map();
              staged.updateTokens.set(token.bindingId, history);
            }
            if (token.lifecycle !== "INVALIDATED") {
              if (binding.lifecycle !== "PENDING_START") {
                throw new Error("Live Activity update token binding is inactive");
              }
              await authoritativeSession(
                coreTransaction,
                binding.sessionId,
                binding.sessionRevision,
              );
            } else {
              const existing = history.get(token.clientGeneration);
              if (
                existing == null ||
                (existing.lifecycle !== "CURRENT" &&
                  !sameTokenRecord(existing, token))
              ) {
                throw new Error("Live Activity update token binding is inactive");
              }
            }
            changed =
              saveToken(history, token, "Live Activity update token") || changed;
          },
        };

        const result = await operation(transaction);
        validateState(normalizedInstallationId, staged);
        if (changed) this.states.set(normalizedInstallationId, cloneState(staged));
        return result;
      },
    );
  }
}
