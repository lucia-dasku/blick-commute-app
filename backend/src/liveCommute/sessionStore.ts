import {
  canonicalizePublicationQuery,
  createLiveCommuteSession,
  normalizeLiveCommuteQuery,
  type CanonicalPublicationQuery,
  type LiveCommuteQuery,
  type LiveCommuteSession,
} from "./model.js";

export type LiveCommuteInstallationState = "ACTIVE" | "REVOKED";
export type StoredLiveCommuteSessionLifecycle = "REGISTERED" | "CANCELLED";

declare const installationCredentialDigestBrand: unique symbol;

/** Internal SHA-256 representation. It must never be included in an ordinary DTO. */
export type InstallationCredentialDigest = string & {
  readonly [installationCredentialDigestBrand]: true;
};

export interface LiveCommuteInstallation {
  readonly installationId: string;
  readonly state: LiveCommuteInstallationState;
  readonly createdAt: Date;
  readonly updatedAt: Date;
  readonly revokedAt: Date | null;
}

/** Persistence-only authentication material, deliberately separate from safe DTOs. */
export interface StoredLiveCommuteInstallation extends LiveCommuteInstallation {
  readonly credentialDigest: InstallationCredentialDigest;
}

export interface StoredLiveCommuteSession {
  readonly session: LiveCommuteSession;
  readonly lifecycle: StoredLiveCommuteSessionLifecycle;
  readonly revision: number;
  readonly createdAt: Date;
  readonly updatedAt: Date;
  readonly cancelledAt: Date | null;
}

export interface LiveCommuteSessionVersionRef {
  readonly installationId: string;
  readonly sessionId: string;
  readonly revision: number;
}

export type PersistedLiveCommuteQuery = CanonicalPublicationQuery;

export interface LiveCommuteInstallationTransaction {
  getInstallation(): Promise<StoredLiveCommuteInstallation | undefined>;
  /** Atomically revokes the locked parent and cancels its registered sessions. */
  revokeInstallation(revokedAt: Date): Promise<StoredLiveCommuteInstallation>;
  getSession(sessionId: string): Promise<StoredLiveCommuteSession | undefined>;
  listSessions(): Promise<readonly StoredLiveCommuteSession[]>;
  /** Inserts REGISTERED revision 1 or validates a transition from REGISTERED. */
  saveSession(session: StoredLiveCommuteSession): Promise<void>;
}

/**
 * Durable ownership and concrete-session storage. `withInstallationTransaction` must
 * serialize on the installation parent row and commit every callback write atomically.
 * The callback is for short storage operations only, never transit or delivery I/O.
 */
export interface LiveCommuteSessionStore {
  /** Returns false only for an installation-ID or credential-digest collision. */
  createInstallation(installation: StoredLiveCommuteInstallation): Promise<boolean>;
  withInstallationTransaction<T>(
    installationId: string,
    operation: (transaction: LiveCommuteInstallationTransaction) => Promise<T>,
  ): Promise<T>;
  listEligibleSessions(at: Date): Promise<readonly StoredLiveCommuteSession[]>;
  /** Returns only active-parent, registered sessions whose stored revision is exact. */
  revalidateSessionVersions(
    references: readonly LiveCommuteSessionVersionRef[],
  ): Promise<readonly StoredLiveCommuteSession[]>;
}

function validDate(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function normalizedIdentifier(value: string, field: string): string {
  if (typeof value !== "string") throw new TypeError(`${field} must be a string`);
  const normalized = value.trim();
  if (normalized.length === 0) throw new RangeError(`${field} is invalid`);
  return normalized;
}

function boundedIdentifier(value: string, field: string, maxLength: number): string {
  const normalized = normalizedIdentifier(value, field);
  if (normalized.length > maxLength) throw new RangeError(`${field} is invalid`);
  return normalized;
}

function positiveRevision(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError("revision must be a positive safe integer");
  }
  return value;
}

function recordValue(value: unknown): Record<string, unknown> {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("persisted live commute query must be an object");
  }
  return value as Record<string, unknown>;
}

function hasExactKeys(record: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(record).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

function sameJsonValue(left: unknown, right: unknown): boolean {
  if (left === right) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    return (
      Array.isArray(left) &&
      Array.isArray(right) &&
      left.length === right.length &&
      left.every((value, index) => sameJsonValue(value, right[index]))
    );
  }
  if (
    left == null ||
    right == null ||
    typeof left !== "object" ||
    typeof right !== "object"
  ) {
    return false;
  }
  const leftRecord = left as Record<string, unknown>;
  const rightRecord = right as Record<string, unknown>;
  const leftKeys = Object.keys(leftRecord).sort();
  const rightKeys = Object.keys(rightRecord).sort();
  return (
    leftKeys.length === rightKeys.length &&
    leftKeys.every(
      (key, index) =>
        key === rightKeys[index] && sameJsonValue(leftRecord[key], rightRecord[key]),
    )
  );
}

export function installationCredentialDigest(value: string): InstallationCredentialDigest {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) {
    throw new RangeError("credential digest is invalid");
  }
  return value as InstallationCredentialDigest;
}

export function createStoredLiveCommuteInstallation(
  input: StoredLiveCommuteInstallation,
): StoredLiveCommuteInstallation {
  if (input == null || typeof input !== "object") {
    throw new TypeError("installation must be an object");
  }
  const installationId = normalizedIdentifier(input.installationId, "installationId");
  const credentialDigest = installationCredentialDigest(input.credentialDigest);
  if (input.state !== "ACTIVE" && input.state !== "REVOKED") {
    throw new RangeError("installation state is invalid");
  }
  const createdAt = validDate(input.createdAt, "installation.createdAt");
  const updatedAt = validDate(input.updatedAt, "installation.updatedAt");
  const revokedAt =
    input.revokedAt == null ? null : validDate(input.revokedAt, "installation.revokedAt");
  if (updatedAt.getTime() < createdAt.getTime()) {
    throw new RangeError("installation updatedAt cannot precede createdAt");
  }
  if (
    (input.state === "ACTIVE" && revokedAt != null) ||
    (input.state === "REVOKED" && revokedAt == null)
  ) {
    throw new RangeError("installation state and revokedAt are inconsistent");
  }
  if (
    revokedAt != null &&
    (revokedAt.getTime() < createdAt.getTime() || revokedAt.getTime() > updatedAt.getTime())
  ) {
    throw new RangeError("installation revokedAt is outside its metadata window");
  }
  return Object.freeze({
    installationId,
    credentialDigest,
    state: input.state,
    createdAt,
    updatedAt,
    revokedAt,
  });
}

export function safeLiveCommuteInstallation(
  installation: StoredLiveCommuteInstallation,
): LiveCommuteInstallation {
  const stored = createStoredLiveCommuteInstallation(installation);
  return Object.freeze({
    installationId: stored.installationId,
    state: stored.state,
    createdAt: new Date(stored.createdAt),
    updatedAt: new Date(stored.updatedAt),
    revokedAt: stored.revokedAt == null ? null : new Date(stored.revokedAt),
  });
}

/** Stores the already-normalized publication identity, including exact-search bounds. */
export function serializePersistedLiveCommuteQuery(
  query: LiveCommuteQuery,
): PersistedLiveCommuteQuery {
  return canonicalizePublicationQuery(query);
}

/**
 * Strictly decodes storage data. Unlike request normalization, this rejects noncanonical
 * casing, mode ordering, timestamps, missing fields, and extra fields as corruption.
 */
export function deserializePersistedLiveCommuteQuery(value: unknown): LiveCommuteQuery {
  const record = recordValue(value);
  let input: Parameters<typeof normalizeLiveCommuteQuery>[0];
  if (record.kind === "LINE_DIRECTION") {
    if (
      !hasExactKeys(record, [
        "kind",
        "siteId",
        "transportMode",
        "lineId",
        "directionCode",
      ])
    ) {
      throw new RangeError("persisted LINE_DIRECTION query shape is invalid");
    }
    input = {
      kind: "LINE_DIRECTION",
      siteId: record.siteId as number,
      transportMode: record.transportMode as string,
      lineId: record.lineId as number | null,
      directionCode: record.directionCode as number | null,
    };
  } else if (record.kind === "EXACT_DESTINATION") {
    if (
      !hasExactKeys(record, [
        "kind",
        "originId",
        "destinationId",
        "transportModes",
        "changesPreference",
        "searchUntil",
        "searchMode",
        "laterJourneyCount",
      ]) ||
      typeof record.searchUntil !== "string"
    ) {
      throw new RangeError("persisted EXACT_DESTINATION query shape is invalid");
    }
    input = {
      kind: "EXACT_DESTINATION",
      originId: record.originId as string,
      destinationId: record.destinationId as string,
      transportModes: record.transportModes as never,
      changesPreference: record.changesPreference as never,
      searchUntil: new Date(record.searchUntil),
    };
  } else {
    throw new RangeError("persisted live commute query kind is invalid");
  }

  const normalized = normalizeLiveCommuteQuery(input);
  const canonical = canonicalizePublicationQuery(normalized);
  if (!sameJsonValue(record, canonical)) {
    throw new RangeError("persisted live commute query is not canonical");
  }
  return normalized;
}

export function createStoredLiveCommuteSession(
  input: StoredLiveCommuteSession,
): StoredLiveCommuteSession {
  if (input == null || typeof input !== "object") {
    throw new TypeError("stored session must be an object");
  }
  const session = createLiveCommuteSession(input.session);
  boundedIdentifier(session.sessionId, "sessionId", 256);
  boundedIdentifier(session.routineId, "routineId", 256);
  const revision = positiveRevision(input.revision);
  if (input.lifecycle !== "REGISTERED" && input.lifecycle !== "CANCELLED") {
    throw new RangeError("session lifecycle is invalid");
  }
  const createdAt = validDate(input.createdAt, "session.createdAt");
  const updatedAt = validDate(input.updatedAt, "session.updatedAt");
  const cancelledAt =
    input.cancelledAt == null ? null : validDate(input.cancelledAt, "session.cancelledAt");
  if (updatedAt.getTime() < createdAt.getTime()) {
    throw new RangeError("session updatedAt cannot precede createdAt");
  }
  if (
    (input.lifecycle === "REGISTERED" && cancelledAt != null) ||
    (input.lifecycle === "CANCELLED" && cancelledAt == null)
  ) {
    throw new RangeError("session lifecycle and cancelledAt are inconsistent");
  }
  if (
    cancelledAt != null &&
    (cancelledAt.getTime() < createdAt.getTime() || cancelledAt.getTime() > updatedAt.getTime())
  ) {
    throw new RangeError("session cancelledAt is outside its metadata window");
  }
  return Object.freeze({
    session,
    lifecycle: input.lifecycle,
    revision,
    createdAt,
    updatedAt,
    cancelledAt,
  });
}

export function liveCommuteSessionIdentityKey(
  value: Pick<LiveCommuteSession, "installationId" | "sessionId">,
): string {
  return JSON.stringify([
    normalizedIdentifier(value.installationId, "installationId"),
    normalizedIdentifier(value.sessionId, "sessionId"),
  ]);
}

export function liveCommuteSessionVersionRef(
  record: StoredLiveCommuteSession,
): LiveCommuteSessionVersionRef {
  const stored = createStoredLiveCommuteSession(record);
  return Object.freeze({
    installationId: stored.session.installationId,
    sessionId: stored.session.sessionId,
    revision: stored.revision,
  });
}

export function liveCommuteSessionSpecificationFingerprint(
  session: LiveCommuteSession,
): string {
  const normalized = createLiveCommuteSession(session);
  return JSON.stringify({
    routineId: normalized.routineId,
    startsAt: normalized.startsAt.toISOString(),
    endsAt: normalized.endsAt.toISOString(),
    query: canonicalizePublicationQuery(normalized.query),
  });
}

export function liveCommuteSessionSpecificationEquals(
  left: LiveCommuteSession,
  right: LiveCommuteSession,
): boolean {
  return (
    liveCommuteSessionSpecificationFingerprint(left) ===
    liveCommuteSessionSpecificationFingerprint(right)
  );
}

export function liveCommuteStoredSessionMatchesVersion(
  captured: StoredLiveCommuteSession,
  current: StoredLiveCommuteSession,
): boolean {
  const left = createStoredLiveCommuteSession(captured);
  const right = createStoredLiveCommuteSession(current);
  return (
    left.revision === right.revision &&
    liveCommuteSessionIdentityKey(left.session) === liveCommuteSessionIdentityKey(right.session) &&
    liveCommuteSessionSpecificationEquals(left.session, right.session)
  );
}
