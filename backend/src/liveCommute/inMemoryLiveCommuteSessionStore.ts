import {
  createStoredLiveCommuteInstallation,
  createStoredLiveCommuteSession,
  liveCommuteSessionIdentityKey,
  type LiveCommuteInstallationTransaction,
  type LiveCommuteSessionStore,
  type LiveCommuteSessionVersionRef,
  type StoredLiveCommuteInstallation,
  type StoredLiveCommuteSession,
} from "./sessionStore.js";

function validInstant(value: Date): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("at must be a valid absolute Date");
  }
  return new Date(value.getTime());
}

function validIdentifier(value: string, field: string): string {
  if (typeof value !== "string") throw new TypeError(`${field} must be a string`);
  const normalized = value.trim();
  if (normalized.length === 0) throw new RangeError(`${field} is invalid`);
  return normalized;
}

function validVersionRef(reference: LiveCommuteSessionVersionRef): LiveCommuteSessionVersionRef {
  if (reference == null || typeof reference !== "object") {
    throw new TypeError("session version reference must be an object");
  }
  if (!Number.isSafeInteger(reference.revision) || reference.revision <= 0) {
    throw new RangeError("session version revision must be a positive safe integer");
  }
  return Object.freeze({
    installationId: validIdentifier(reference.installationId, "installationId"),
    sessionId: validIdentifier(reference.sessionId, "sessionId"),
    revision: reference.revision,
  });
}

function windowsOverlap(
  left: StoredLiveCommuteSession,
  right: StoredLiveCommuteSession,
): boolean {
  return (
    left.session.startsAt.getTime() < right.session.endsAt.getTime() &&
    right.session.startsAt.getTime() < left.session.endsAt.getTime()
  );
}

/**
 * Deterministic test adapter. Its process-local queue models the store's atomic contract;
 * production relies on PostgreSQL transactions and parent-row locks instead.
 */
export class InMemoryLiveCommuteSessionStore implements LiveCommuteSessionStore {
  private readonly installations = new Map<string, StoredLiveCommuteInstallation>();
  private readonly sessions = new Map<string, Map<string, StoredLiveCommuteSession>>();
  private transactionTail: Promise<void> = Promise.resolve();

  private async runExclusive<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.transactionTail;
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.transactionTail = previous.then(() => gate);
    await previous;
    try {
      return await operation();
    } finally {
      release();
    }
  }

  async createInstallation(installation: StoredLiveCommuteInstallation): Promise<boolean> {
    const candidate = createStoredLiveCommuteInstallation(installation);
    return await this.runExclusive(async () => {
      if (this.installations.has(candidate.installationId)) return false;
      if (
        [...this.installations.values()].some(
          ({ credentialDigest }) => credentialDigest === candidate.credentialDigest,
        )
      ) {
        return false;
      }
      this.installations.set(
        candidate.installationId,
        createStoredLiveCommuteInstallation(candidate),
      );
      this.sessions.set(candidate.installationId, new Map());
      return true;
    });
  }

  async withInstallationTransaction<T>(
    installationId: string,
    operation: (transaction: LiveCommuteInstallationTransaction) => Promise<T>,
  ): Promise<T> {
    const normalizedInstallationId = validIdentifier(installationId, "installationId");
    return await this.runExclusive(async () => {
      const persistedInstallation = this.installations.get(normalizedInstallationId);
      let workingInstallation =
        persistedInstallation == null
          ? undefined
          : createStoredLiveCommuteInstallation(persistedInstallation);
      const workingSessions = new Map<string, StoredLiveCommuteSession>();
      for (const [sessionId, session] of this.sessions.get(normalizedInstallationId) ?? []) {
        workingSessions.set(sessionId, createStoredLiveCommuteSession(session));
      }

      const transaction: LiveCommuteInstallationTransaction = {
        getInstallation: async () =>
          workingInstallation == null
            ? undefined
            : createStoredLiveCommuteInstallation(workingInstallation),
        revokeInstallation: async (revokedAt) => {
          if (workingInstallation == null) {
            throw new Error("installation transaction cannot create its parent");
          }
          if (workingInstallation.state === "REVOKED") {
            return createStoredLiveCommuteInstallation(workingInstallation);
          }
          const requestedAt = validInstant(revokedAt);
          let effectiveMillis = Math.max(
            requestedAt.getTime(),
            workingInstallation.updatedAt.getTime(),
          );
          for (const stored of workingSessions.values()) {
            if (stored.lifecycle === "REGISTERED") {
              effectiveMillis = Math.max(effectiveMillis, stored.updatedAt.getTime());
            }
          }
          const effectiveAt = new Date(effectiveMillis);
          for (const [sessionId, stored] of workingSessions) {
            if (stored.lifecycle !== "REGISTERED") continue;
            workingSessions.set(
              sessionId,
              createStoredLiveCommuteSession({
                ...stored,
                lifecycle: "CANCELLED",
                updatedAt: effectiveAt,
                cancelledAt: effectiveAt,
              }),
            );
          }
          workingInstallation = createStoredLiveCommuteInstallation({
            ...workingInstallation,
            state: "REVOKED",
            updatedAt: effectiveAt,
            revokedAt: effectiveAt,
          });
          return createStoredLiveCommuteInstallation(workingInstallation);
        },
        getSession: async (sessionId) => {
          const normalizedSessionId = validIdentifier(sessionId, "sessionId");
          const session = workingSessions.get(normalizedSessionId);
          return session == null ? undefined : createStoredLiveCommuteSession(session);
        },
        listSessions: async () =>
          Object.freeze(
            [...workingSessions.values()]
              .sort((left, right) =>
                left.session.sessionId.localeCompare(right.session.sessionId),
              )
              .map(createStoredLiveCommuteSession),
          ),
        saveSession: async (session) => {
          if (workingInstallation == null) {
            throw new Error("session cannot be saved without its installation");
          }
          const next = createStoredLiveCommuteSession(session);
          if (next.session.installationId !== normalizedInstallationId) {
            throw new Error("session transaction ownership mismatch");
          }
          const current = workingSessions.get(next.session.sessionId);
          if (current == null) {
            if (next.lifecycle !== "REGISTERED" || next.revision !== 1) {
              throw new Error("initial session transition is invalid");
            }
          } else if (
            current.lifecycle === "CANCELLED" ||
            current.createdAt.getTime() !== next.createdAt.getTime() ||
            next.updatedAt.getTime() < current.updatedAt.getTime() ||
            (next.lifecycle === "REGISTERED" && next.revision !== current.revision + 1) ||
            (next.lifecycle === "CANCELLED" &&
              next.revision !== current.revision &&
              next.revision !== current.revision + 1)
          ) {
            throw new Error("session transition is invalid");
          }
          if (
            next.lifecycle === "REGISTERED" &&
            [...workingSessions.values()].some(
              (stored) =>
                stored.session.sessionId !== next.session.sessionId &&
                stored.lifecycle === "REGISTERED" &&
                windowsOverlap(stored, next),
            )
          ) {
            throw new Error("session window overlaps");
          }
          workingSessions.set(next.session.sessionId, next);
        },
      };

      const result = await operation(transaction);
      if (workingInstallation != null) {
        this.installations.set(
          normalizedInstallationId,
          createStoredLiveCommuteInstallation(workingInstallation),
        );
        this.sessions.set(
          normalizedInstallationId,
          new Map(
            [...workingSessions].map(([sessionId, session]) => [
              sessionId,
              createStoredLiveCommuteSession(session),
            ]),
          ),
        );
      }
      return result;
    });
  }

  async listEligibleSessions(at: Date): Promise<readonly StoredLiveCommuteSession[]> {
    const instant = validInstant(at).getTime();
    return await this.runExclusive(async () => {
      const eligible: StoredLiveCommuteSession[] = [];
      for (const [installationId, installation] of this.installations) {
        if (installation.state !== "ACTIVE") continue;
        for (const session of this.sessions.get(installationId)?.values() ?? []) {
          if (
            session.lifecycle === "REGISTERED" &&
            session.session.startsAt.getTime() <= instant &&
            instant < session.session.endsAt.getTime()
          ) {
            eligible.push(createStoredLiveCommuteSession(session));
          }
        }
      }
      eligible.sort((left, right) =>
        liveCommuteSessionIdentityKey(left.session).localeCompare(
          liveCommuteSessionIdentityKey(right.session),
        ),
      );
      return Object.freeze(eligible);
    });
  }

  async revalidateSessionVersions(
    references: readonly LiveCommuteSessionVersionRef[],
  ): Promise<readonly StoredLiveCommuteSession[]> {
    if (!Array.isArray(references)) {
      throw new TypeError("session version references must be an array");
    }
    const validatedReferences = references.map(validVersionRef);
    return await this.runExclusive(async () => {
      const found: StoredLiveCommuteSession[] = [];
      const visited = new Set<string>();
      for (const reference of validatedReferences) {
        const identity = liveCommuteSessionIdentityKey(reference);
        if (visited.has(identity)) continue;
        visited.add(identity);
        const installation = this.installations.get(reference.installationId);
        const session = this.sessions
          .get(reference.installationId)
          ?.get(reference.sessionId);
        if (
          installation?.state === "ACTIVE" &&
          session?.lifecycle === "REGISTERED" &&
          session.revision === reference.revision
        ) {
          found.push(createStoredLiveCommuteSession(session));
        }
      }
      return Object.freeze(found);
    });
  }
}
