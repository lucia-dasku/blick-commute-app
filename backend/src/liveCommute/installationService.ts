import {
  createHash,
  randomBytes as nodeRandomBytes,
  randomUUID as nodeRandomUuid,
  timingSafeEqual,
} from "node:crypto";
import {
  createLiveCommuteSession,
  type LiveCommuteSession,
  type LiveCommuteSessionInput,
} from "./model.js";
import {
  createStoredLiveCommuteInstallation,
  createStoredLiveCommuteSession,
  installationCredentialDigest,
  liveCommuteSessionSpecificationEquals,
  safeLiveCommuteInstallation,
  type InstallationCredentialDigest,
  type LiveCommuteInstallation,
  type LiveCommuteInstallationTransaction,
  type LiveCommuteSessionStore,
  type StoredLiveCommuteInstallation,
  type StoredLiveCommuteSession,
} from "./sessionStore.js";

const BEARER_CREDENTIAL_BYTES = 32;
const BASE64URL_32_BYTE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const INSTALLATION_CREATION_ATTEMPTS = 5;

export interface LiveCommuteInstallationAuthentication {
  readonly installationId: string;
  readonly bearerCredential: string;
}

export type IssuedLiveCommuteInstallation = LiveCommuteInstallationAuthentication;

export type LiveCommuteConcreteSessionInput = Omit<
  LiveCommuteSessionInput,
  "installationId"
>;

export interface ReplaceLiveCommuteSessionInput extends LiveCommuteConcreteSessionInput {
  readonly expectedRevision: number;
}

export interface CancelLiveCommuteSessionInput {
  readonly sessionId: string;
  readonly expectedRevision: number;
}

export type RegisterLiveCommuteSessionResult =
  | {
      readonly status: "REGISTERED" | "UNCHANGED";
      readonly session: StoredLiveCommuteSession;
    }
  | {
      readonly status: "ALREADY_CANCELLED";
      readonly session: StoredLiveCommuteSession;
    };

export interface ReplaceLiveCommuteSessionResult {
  readonly status: "REPLACED" | "UNCHANGED";
  readonly session: StoredLiveCommuteSession;
}

export interface CancelLiveCommuteSessionResult {
  readonly status: "CANCELLED" | "UNCHANGED";
  readonly session: StoredLiveCommuteSession;
}

export interface RevokeLiveCommuteInstallationResult {
  readonly status: "REVOKED" | "UNCHANGED";
  readonly installation: LiveCommuteInstallation;
}

export type LiveCommuteSessionServiceErrorCode =
  | "INSTALLATION_AUTHENTICATION_FAILED"
  | "INSTALLATION_REGISTRATION_FAILED"
  | "SESSION_NOT_FOUND"
  | "SESSION_REGISTRATION_CONFLICT"
  | "SESSION_REVISION_CONFLICT"
  | "SESSION_OVERLAP"
  | "SESSION_CANCELLED";

const ERROR_MESSAGES: Readonly<Record<LiveCommuteSessionServiceErrorCode, string>> = {
  INSTALLATION_AUTHENTICATION_FAILED: "Installation authentication failed",
  INSTALLATION_REGISTRATION_FAILED: "Installation registration failed",
  SESSION_NOT_FOUND: "Live commute session was not found",
  SESSION_REGISTRATION_CONFLICT: "Live commute session registration conflicts with stored state",
  SESSION_REVISION_CONFLICT: "Live commute session revision conflict",
  SESSION_OVERLAP: "Live commute session overlaps another registered window",
  SESSION_CANCELLED: "Live commute session is cancelled",
};

export class LiveCommuteSessionServiceError extends Error {
  readonly code: LiveCommuteSessionServiceErrorCode;

  constructor(code: LiveCommuteSessionServiceErrorCode) {
    super(ERROR_MESSAGES[code]);
    this.name = "LiveCommuteSessionServiceError";
    this.code = code;
  }
}

export interface LiveCommuteInstallationServiceOptions {
  readonly now?: () => Date;
  readonly randomBytes?: (size: number) => Buffer;
  readonly randomUuid?: () => string;
}

function readClock(now: () => Date): Date {
  const value = now();
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

function normalizedAuthentication(
  authentication: LiveCommuteInstallationAuthentication,
): LiveCommuteInstallationAuthentication & { readonly digest: InstallationCredentialDigest } {
  if (authentication == null || typeof authentication !== "object") {
    throw new LiveCommuteSessionServiceError("INSTALLATION_AUTHENTICATION_FAILED");
  }
  if (
    typeof authentication.installationId !== "string" ||
    typeof authentication.bearerCredential !== "string" ||
    !BASE64URL_32_BYTE_PATTERN.test(authentication.bearerCredential)
  ) {
    throw new LiveCommuteSessionServiceError("INSTALLATION_AUTHENTICATION_FAILED");
  }
  const installationId = authentication.installationId.trim();
  if (installationId.length === 0) {
    throw new LiveCommuteSessionServiceError("INSTALLATION_AUTHENTICATION_FAILED");
  }
  return Object.freeze({
    installationId,
    bearerCredential: authentication.bearerCredential,
    digest: digestBearerCredential(authentication.bearerCredential),
  });
}

function digestBearerCredential(value: string): InstallationCredentialDigest {
  return installationCredentialDigest(createHash("sha256").update(value, "utf8").digest("hex"));
}

function credentialMatches(
  stored: InstallationCredentialDigest,
  supplied: InstallationCredentialDigest,
): boolean {
  return timingSafeEqual(Buffer.from(stored, "hex"), Buffer.from(supplied, "hex"));
}

function validExpectedRevision(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new LiveCommuteSessionServiceError("SESSION_REVISION_CONFLICT");
  }
  return value;
}

function nextRevision(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0 || value === Number.MAX_SAFE_INTEGER) {
    throw new LiveCommuteSessionServiceError("SESSION_REVISION_CONFLICT");
  }
  return value + 1;
}

function overlaps(left: LiveCommuteSession, right: LiveCommuteSession): boolean {
  return (
    left.startsAt.getTime() < right.endsAt.getTime() &&
    right.startsAt.getTime() < left.endsAt.getTime()
  );
}

function assertNoRegisteredOverlap(
  proposed: LiveCommuteSession,
  sessions: readonly StoredLiveCommuteSession[],
  excludedSessionId?: string,
): void {
  if (
    sessions.some(
      (stored) =>
        stored.lifecycle === "REGISTERED" &&
        stored.session.sessionId !== excludedSessionId &&
        overlaps(stored.session, proposed),
    )
  ) {
    throw new LiveCommuteSessionServiceError("SESSION_OVERLAP");
  }
}

function ownedSession(
  installationId: string,
  input: LiveCommuteConcreteSessionInput,
): LiveCommuteSession {
  return createLiveCommuteSession({ ...input, installationId });
}

function frozenResult<T extends object>(value: T): Readonly<T> {
  return Object.freeze(value);
}

export class LiveCommuteInstallationService {
  private readonly now: () => Date;
  private readonly randomBytes: (size: number) => Buffer;
  private readonly randomUuid: () => string;

  constructor(
    private readonly store: LiveCommuteSessionStore,
    options: LiveCommuteInstallationServiceOptions = {},
  ) {
    this.now = options.now ?? (() => new Date());
    this.randomBytes = options.randomBytes ?? nodeRandomBytes;
    this.randomUuid = options.randomUuid ?? nodeRandomUuid;
  }

  async registerInstallation(): Promise<IssuedLiveCommuteInstallation> {
    for (let attempt = 0; attempt < INSTALLATION_CREATION_ATTEMPTS; attempt++) {
      const installationId = this.randomUuid();
      const secret = this.randomBytes(BEARER_CREDENTIAL_BYTES);
      if (!UUID_PATTERN.test(installationId) || secret.length !== BEARER_CREDENTIAL_BYTES) {
        throw new LiveCommuteSessionServiceError("INSTALLATION_REGISTRATION_FAILED");
      }
      const bearerCredential = secret.toString("base64url");
      const createdAt = readClock(this.now);
      const record = createStoredLiveCommuteInstallation({
        installationId,
        credentialDigest: digestBearerCredential(bearerCredential),
        state: "ACTIVE",
        createdAt,
        updatedAt: createdAt,
        revokedAt: null,
      });
      let created: boolean;
      try {
        created = await this.store.createInstallation(record);
      } catch {
        throw new LiveCommuteSessionServiceError("INSTALLATION_REGISTRATION_FAILED");
      }
      if (created) {
        return frozenResult({ installationId, bearerCredential });
      }
    }
    throw new LiveCommuteSessionServiceError("INSTALLATION_REGISTRATION_FAILED");
  }

  async authenticateInstallation(
    authentication: LiveCommuteInstallationAuthentication,
  ): Promise<LiveCommuteInstallation> {
    return await this.withAuthenticatedInstallation(authentication, false, async (_tx, record) =>
      safeLiveCommuteInstallation(record),
    );
  }

  async registerSession(
    authentication: LiveCommuteInstallationAuthentication,
    input: LiveCommuteConcreteSessionInput,
  ): Promise<RegisterLiveCommuteSessionResult> {
    const proof = normalizedAuthentication(authentication);
    return await this.withAuthenticatedProof(proof, false, async (transaction) => {
      const proposed = ownedSession(proof.installationId, input);
      const existing = await transaction.getSession(proposed.sessionId);
      if (existing != null) {
        if (!liveCommuteSessionSpecificationEquals(existing.session, proposed)) {
          throw new LiveCommuteSessionServiceError("SESSION_REGISTRATION_CONFLICT");
        }
        return frozenResult({
          status: existing.lifecycle === "CANCELLED" ? "ALREADY_CANCELLED" : "UNCHANGED",
          session: existing,
        });
      }

      assertNoRegisteredOverlap(proposed, await transaction.listSessions());
      const createdAt = readClock(this.now);
      const stored = createStoredLiveCommuteSession({
        session: proposed,
        lifecycle: "REGISTERED",
        revision: 1,
        createdAt,
        updatedAt: createdAt,
        cancelledAt: null,
      });
      await transaction.saveSession(stored);
      return frozenResult({ status: "REGISTERED", session: stored });
    });
  }

  async listSessions(
    authentication: LiveCommuteInstallationAuthentication,
  ): Promise<readonly StoredLiveCommuteSession[]> {
    return await this.withAuthenticatedInstallation(
      authentication,
      false,
      async (transaction) => Object.freeze([...(await transaction.listSessions())]),
    );
  }

  async replaceSession(
    authentication: LiveCommuteInstallationAuthentication,
    input: ReplaceLiveCommuteSessionInput,
  ): Promise<ReplaceLiveCommuteSessionResult> {
    const proof = normalizedAuthentication(authentication);
    return await this.withAuthenticatedProof(proof, false, async (transaction) => {
      const expectedRevision = validExpectedRevision(input.expectedRevision);
      const proposed = ownedSession(proof.installationId, input);
      const existing = await transaction.getSession(proposed.sessionId);
      if (existing == null) {
        throw new LiveCommuteSessionServiceError("SESSION_NOT_FOUND");
      }
      if (existing.lifecycle === "CANCELLED") {
        throw new LiveCommuteSessionServiceError("SESSION_CANCELLED");
      }

      const specificationMatches = liveCommuteSessionSpecificationEquals(
        existing.session,
        proposed,
      );
      if (existing.revision === expectedRevision + 1 && specificationMatches) {
        return frozenResult({ status: "UNCHANGED", session: existing });
      }
      if (existing.revision !== expectedRevision) {
        throw new LiveCommuteSessionServiceError("SESSION_REVISION_CONFLICT");
      }
      if (specificationMatches) {
        return frozenResult({ status: "UNCHANGED", session: existing });
      }

      assertNoRegisteredOverlap(
        proposed,
        await transaction.listSessions(),
        existing.session.sessionId,
      );
      const updated = createStoredLiveCommuteSession({
        ...existing,
        session: proposed,
        revision: nextRevision(existing.revision),
        updatedAt: readClock(this.now),
      });
      await transaction.saveSession(updated);
      return frozenResult({ status: "REPLACED", session: updated });
    });
  }

  async cancelSession(
    authentication: LiveCommuteInstallationAuthentication,
    input: CancelLiveCommuteSessionInput,
  ): Promise<CancelLiveCommuteSessionResult> {
    return await this.withAuthenticatedInstallation(
      authentication,
      false,
      async (transaction) => {
        const expectedRevision = validExpectedRevision(input.expectedRevision);
        const existing = await transaction.getSession(input.sessionId);
        if (existing == null) {
          throw new LiveCommuteSessionServiceError("SESSION_NOT_FOUND");
        }
        if (existing.revision !== expectedRevision) {
          throw new LiveCommuteSessionServiceError("SESSION_REVISION_CONFLICT");
        }
        if (existing.lifecycle === "CANCELLED") {
          return frozenResult({ status: "UNCHANGED", session: existing });
        }
        const cancelledAt = readClock(this.now);
        const cancelled = createStoredLiveCommuteSession({
          ...existing,
          lifecycle: "CANCELLED",
          updatedAt: cancelledAt,
          cancelledAt,
        });
        await transaction.saveSession(cancelled);
        return frozenResult({ status: "CANCELLED", session: cancelled });
      },
    );
  }

  async revokeInstallation(
    authentication: LiveCommuteInstallationAuthentication,
  ): Promise<RevokeLiveCommuteInstallationResult> {
    return await this.withAuthenticatedInstallation(
      authentication,
      true,
      async (transaction, installation) => {
        if (installation.state === "REVOKED") {
          return frozenResult({
            status: "UNCHANGED",
            installation: safeLiveCommuteInstallation(installation),
          });
        }

        const revokedAt = readClock(this.now);
        const revoked = await transaction.revokeInstallation(revokedAt);
        return frozenResult({
          status: "REVOKED",
          installation: safeLiveCommuteInstallation(revoked),
        });
      },
    );
  }

  private async withAuthenticatedInstallation<T>(
    authentication: LiveCommuteInstallationAuthentication,
    allowRevoked: boolean,
    operation: (
      transaction: LiveCommuteInstallationTransaction,
      installation: StoredLiveCommuteInstallation,
    ) => Promise<T>,
  ): Promise<T> {
    return await this.withAuthenticatedProof(
      normalizedAuthentication(authentication),
      allowRevoked,
      operation,
    );
  }

  private async withAuthenticatedProof<T>(
    proof: LiveCommuteInstallationAuthentication & {
      readonly digest: InstallationCredentialDigest;
    },
    allowRevoked: boolean,
    operation: (
      transaction: LiveCommuteInstallationTransaction,
      installation: StoredLiveCommuteInstallation,
    ) => Promise<T>,
  ): Promise<T> {
    return await this.store.withInstallationTransaction(
      proof.installationId,
      async (transaction) => {
        const installation = await transaction.getInstallation();
        if (
          installation == null ||
          !credentialMatches(installation.credentialDigest, proof.digest) ||
          (!allowRevoked && installation.state !== "ACTIVE")
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

export function createLiveCommuteInstallationService(
  store: LiveCommuteSessionStore,
  options?: LiveCommuteInstallationServiceOptions,
): LiveCommuteInstallationService {
  return new LiveCommuteInstallationService(store, options);
}
