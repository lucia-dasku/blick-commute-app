import postgres from "postgres";
import {
  createStoredLiveCommuteInstallation,
  createStoredLiveCommuteSession,
  deserializePersistedLiveCommuteQuery,
  serializePersistedLiveCommuteQuery,
  type LiveCommuteInstallationTransaction,
  type LiveCommuteSessionStore,
  type LiveCommuteSessionVersionRef,
  type StoredLiveCommuteInstallation,
  type StoredLiveCommuteSession,
} from "./sessionStore.js";

export type LiveCommutePostgresSql = ReturnType<typeof postgres>;

const PERSISTENCE_ERROR_MESSAGE = "Live commute persistence operation failed";

export class LiveCommutePersistenceError extends Error {
  readonly code = "LIVE_COMMUTE_PERSISTENCE_FAILED" as const;

  constructor() {
    super(PERSISTENCE_ERROR_MESSAGE);
    this.name = "LiveCommutePersistenceError";
  }
}

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

function validVersionRef(
  reference: LiveCommuteSessionVersionRef,
): LiveCommuteSessionVersionRef {
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

interface InstallationRow {
  installation_id: string;
  credential_digest: string;
  revoked_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

interface SessionRow {
  installation_id: string;
  session_id: string;
  routine_id: string;
  starts_at: Date;
  ends_at: Date;
  query: unknown;
  lifecycle: StoredLiveCommuteSession["lifecycle"];
  revision: number;
  created_at: Date;
  updated_at: Date;
  cancelled_at: Date | null;
}

function installationFromRow(row: InstallationRow): StoredLiveCommuteInstallation {
  return createStoredLiveCommuteInstallation({
    installationId: row.installation_id,
    credentialDigest: row.credential_digest as StoredLiveCommuteInstallation["credentialDigest"],
    state: row.revoked_at == null ? "ACTIVE" : "REVOKED",
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    revokedAt: row.revoked_at,
  });
}

function sessionFromRow(row: SessionRow): StoredLiveCommuteSession {
  return createStoredLiveCommuteSession({
    session: {
      installationId: row.installation_id,
      sessionId: row.session_id,
      routineId: row.routine_id,
      startsAt: row.starts_at,
      endsAt: row.ends_at,
      query: deserializePersistedLiveCommuteQuery(row.query),
    },
    lifecycle: row.lifecycle,
    revision: row.revision,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    cancelledAt: row.cancelled_at,
  });
}

function persistedQueryJson(
  session: StoredLiveCommuteSession,
): postgres.JSONValue {
  return serializePersistedLiveCommuteQuery(session.session.query) as unknown as postgres.JSONValue;
}

/**
 * PostgreSQL-backed ownership/session state. The caller owns the supplied pool and must
 * close it explicitly. Constructing this adapter performs no I/O.
 */
export class PostgresLiveCommuteSessionStore implements LiveCommuteSessionStore {
  constructor(private readonly sql: LiveCommutePostgresSql) {}

  async createInstallation(installation: StoredLiveCommuteInstallation): Promise<boolean> {
    const record = createStoredLiveCommuteInstallation(installation);
    try {
      const rows = await this.sql<{ installation_id: string }[]>`
        INSERT INTO live_commute_installations (
          installation_id, credential_digest, revoked_at, created_at, updated_at
        ) VALUES (
          ${record.installationId}, ${record.credentialDigest}, ${record.revokedAt},
          ${record.createdAt}, ${record.updatedAt}
        )
        ON CONFLICT DO NOTHING
        RETURNING installation_id
      `;
      return rows.length === 1;
    } catch {
      // postgres.js errors retain bound parameters as hidden properties. Never allow the
      // credential digest bound above to escape through an adapter diagnostic.
      throw new LiveCommutePersistenceError();
    }
  }

  async withInstallationTransaction<T>(
    installationId: string,
    operation: (transaction: LiveCommuteInstallationTransaction) => Promise<T>,
  ): Promise<T> {
    const normalizedInstallationId = validIdentifier(installationId, "installationId");
    return await this.sql.begin(async (transactionSql) => {
      await transactionSql`
        SELECT installation_id
        FROM live_commute_installations
        WHERE installation_id = ${normalizedInstallationId}
        FOR UPDATE
      `;

      const transaction: LiveCommuteInstallationTransaction = {
        getInstallation: async () => {
          const rows = await transactionSql<InstallationRow[]>`
            SELECT installation_id, credential_digest, revoked_at, created_at, updated_at
            FROM live_commute_installations
            WHERE installation_id = ${normalizedInstallationId}
          `;
          return rows[0] == null ? undefined : installationFromRow(rows[0]);
        },
        revokeInstallation: async (revokedAt) => {
          const requestedAt = validInstant(revokedAt);
          const rows = await transactionSql<InstallationRow[]>`
            UPDATE live_commute_installations
            SET revoked_at = GREATEST(${requestedAt}, updated_at),
                updated_at = GREATEST(${requestedAt}, updated_at)
            WHERE installation_id = ${normalizedInstallationId}
              AND revoked_at IS NULL
            RETURNING installation_id, credential_digest, revoked_at, created_at, updated_at
          `;
          const row = rows[0];
          if (row == null || row.revoked_at == null) {
            throw new Error("installation cannot be revoked");
          }
          await transactionSql`
            UPDATE live_commute_sessions
            SET lifecycle = 'CANCELLED',
                updated_at = GREATEST(updated_at, ${row.revoked_at}),
                cancelled_at = GREATEST(updated_at, ${row.revoked_at})
            WHERE installation_id = ${normalizedInstallationId}
              AND lifecycle = 'REGISTERED'
          `;
          return installationFromRow(row);
        },
        getSession: async (sessionId) => {
          const normalizedSessionId = validIdentifier(sessionId, "sessionId");
          const rows = await transactionSql<SessionRow[]>`
            SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                   lifecycle, revision, created_at, updated_at, cancelled_at
            FROM live_commute_sessions
            WHERE installation_id = ${normalizedInstallationId}
              AND session_id = ${normalizedSessionId}
          `;
          return rows[0] == null ? undefined : sessionFromRow(rows[0]);
        },
        listSessions: async () => {
          const rows = await transactionSql<SessionRow[]>`
            SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                   lifecycle, revision, created_at, updated_at, cancelled_at
            FROM live_commute_sessions
            WHERE installation_id = ${normalizedInstallationId}
            ORDER BY starts_at, session_id
          `;
          return Object.freeze(rows.map(sessionFromRow));
        },
        saveSession: async (session) => {
          const record = createStoredLiveCommuteSession(session);
          if (record.session.installationId !== normalizedInstallationId) {
            throw new Error("session transaction identity mismatch");
          }
          const existing = await transactionSql<SessionRow[]>`
            SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                   lifecycle, revision, created_at, updated_at, cancelled_at
            FROM live_commute_sessions
            WHERE installation_id = ${normalizedInstallationId}
              AND session_id = ${record.session.sessionId}
          `;
          if (existing.length === 0) {
            if (record.lifecycle !== "REGISTERED" || record.revision !== 1) {
              throw new Error("initial session transition is invalid");
            }
            const overlaps = await transactionSql<{ session_id: string }[]>`
              SELECT session_id
              FROM live_commute_sessions
              WHERE installation_id = ${normalizedInstallationId}
                AND lifecycle = 'REGISTERED'
                AND starts_at < ${record.session.endsAt}
                AND ends_at > ${record.session.startsAt}
              LIMIT 1
            `;
            if (overlaps.length !== 0) throw new Error("session window overlaps");
            await transactionSql`
              INSERT INTO live_commute_sessions (
                installation_id, session_id, routine_id, starts_at, ends_at, query,
                lifecycle, revision, created_at, updated_at, cancelled_at
              ) VALUES (
                ${record.session.installationId}, ${record.session.sessionId},
                ${record.session.routineId}, ${record.session.startsAt}, ${record.session.endsAt},
                ${transactionSql.json(persistedQueryJson(record))},
                ${record.lifecycle}, ${record.revision}, ${record.createdAt}, ${record.updatedAt},
                ${record.cancelledAt}
              )
            `;
            return;
          }

          const current = sessionFromRow(existing[0]!);
          if (
            current.lifecycle === "CANCELLED" ||
            current.createdAt.getTime() !== record.createdAt.getTime() ||
            record.updatedAt.getTime() < current.updatedAt.getTime() ||
            (record.lifecycle === "REGISTERED" &&
              record.revision !== current.revision + 1) ||
            (record.lifecycle === "CANCELLED" &&
              record.revision !== current.revision &&
              record.revision !== current.revision + 1)
          ) {
            throw new Error("session transition is invalid");
          }

          if (record.lifecycle === "REGISTERED") {
            const overlaps = await transactionSql<{ session_id: string }[]>`
              SELECT session_id
              FROM live_commute_sessions
              WHERE installation_id = ${normalizedInstallationId}
                AND session_id <> ${record.session.sessionId}
                AND lifecycle = 'REGISTERED'
                AND starts_at < ${record.session.endsAt}
                AND ends_at > ${record.session.startsAt}
              LIMIT 1
            `;
            if (overlaps.length !== 0) throw new Error("session window overlaps");
          }

          const rows = await transactionSql<{ session_id: string }[]>`
            UPDATE live_commute_sessions
            SET routine_id = ${record.session.routineId},
                starts_at = ${record.session.startsAt},
                ends_at = ${record.session.endsAt},
                query = ${transactionSql.json(persistedQueryJson(record))},
                lifecycle = ${record.lifecycle},
                revision = ${record.revision},
                created_at = ${record.createdAt},
                updated_at = ${record.updatedAt},
                cancelled_at = ${record.cancelledAt}
            WHERE installation_id = ${normalizedInstallationId}
              AND session_id = ${record.session.sessionId}
            RETURNING session_id
          `;
          if (rows.length !== 1) throw new Error("session no longer exists");
        },
      };

      return operation(transaction);
    }) as T;
  }

  async listEligibleSessions(at: Date): Promise<readonly StoredLiveCommuteSession[]> {
    const instant = validInstant(at);
    const rows = await this.sql<SessionRow[]>`
      SELECT s.installation_id, s.session_id, s.routine_id, s.starts_at, s.ends_at,
             s.query, s.lifecycle, s.revision, s.created_at, s.updated_at,
             s.cancelled_at
      FROM live_commute_sessions AS s
      INNER JOIN live_commute_installations AS i
        ON i.installation_id = s.installation_id
      WHERE i.revoked_at IS NULL
        AND s.lifecycle = 'REGISTERED'
        AND s.starts_at <= ${instant}
        AND s.ends_at > ${instant}
      ORDER BY s.installation_id, s.starts_at, s.session_id
    `;
    return Object.freeze(rows.map(sessionFromRow));
  }

  async revalidateSessionVersions(
    references: readonly LiveCommuteSessionVersionRef[],
  ): Promise<readonly StoredLiveCommuteSession[]> {
    if (!Array.isArray(references)) {
      throw new TypeError("session version references must be an array");
    }
    if (references.length === 0) return Object.freeze([]);
    const requested = references.map(validVersionRef).map((reference) => ({
      installation_id: reference.installationId,
      session_id: reference.sessionId,
      revision: reference.revision,
    }));
    const rows = await this.sql<SessionRow[]>`
      WITH requested AS (
        SELECT DISTINCT installation_id, session_id, revision
        FROM jsonb_to_recordset(${this.sql.json(requested)}::jsonb)
          AS value(installation_id TEXT, session_id TEXT, revision INTEGER)
      )
      SELECT s.installation_id, s.session_id, s.routine_id, s.starts_at, s.ends_at,
             s.query, s.lifecycle, s.revision, s.created_at, s.updated_at,
             s.cancelled_at
      FROM requested AS r
      INNER JOIN live_commute_sessions AS s
        ON s.installation_id = r.installation_id
       AND s.session_id = r.session_id
       AND s.revision = r.revision
      INNER JOIN live_commute_installations AS i
        ON i.installation_id = s.installation_id
      WHERE i.revoked_at IS NULL
        AND s.lifecycle = 'REGISTERED'
      ORDER BY s.installation_id, s.session_id
    `;
    return Object.freeze(rows.map(sessionFromRow));
  }
}
