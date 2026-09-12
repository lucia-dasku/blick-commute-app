import postgres from "postgres";
import {
  createStoredLiveCommuteInstallation,
  createStoredLiveCommuteSession,
  deserializePersistedLiveCommuteQuery,
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
} from "./deliveryStore.js";
import type { ProtectedActivityKitToken } from "./tokenProtection.js";

export type LiveActivityDeliveryPostgresSql = ReturnType<typeof postgres>;

const PERSISTENCE_ERROR_MESSAGE = "Live Activity delivery persistence operation failed";

export class LiveActivityDeliveryPersistenceError extends Error {
  readonly code = "LIVE_ACTIVITY_DELIVERY_PERSISTENCE_FAILED" as const;

  constructor() {
    super(PERSISTENCE_ERROR_MESSAGE);
    this.name = "LiveActivityDeliveryPersistenceError";
  }
}

class DeliveryCallbackFailure extends Error {
  constructor(readonly failure: unknown) {
    super("Live Activity delivery transaction callback failed");
    this.name = "DeliveryCallbackFailure";
  }
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

interface PushToStartTokenRow {
  installation_id: string;
  server_revision: number;
  client_generation: number;
  apns_environment: StoredPushToStartToken["environment"];
  lifecycle: StoredPushToStartToken["lifecycle"];
  token_digest: string;
  token_ciphertext: Buffer;
  token_nonce: Buffer;
  token_auth_tag: Buffer;
  created_at: Date;
  updated_at: Date;
  replaced_at: Date | null;
  invalidated_at: Date | null;
}

interface DeliveryBindingRow {
  binding_id: string;
  installation_id: string;
  session_id: string;
  session_revision: number;
  delivery_strategy: LiveActivityDeliveryBinding["strategy"];
  lifecycle: LiveActivityDeliveryBinding["lifecycle"];
  apple_activity_id: string | null;
  created_at: Date;
  updated_at: Date;
  ended_at: Date | null;
  invalidated_at: Date | null;
}

interface UpdateTokenRow {
  binding_id: string;
  installation_id: string;
  binding_strategy: "DIRECT_TOKEN";
  server_revision: number;
  client_generation: number;
  apns_environment: StoredLiveActivityUpdateToken["environment"];
  lifecycle: StoredLiveActivityUpdateToken["lifecycle"];
  token_digest: string;
  token_ciphertext: Buffer;
  token_nonce: Buffer;
  token_auth_tag: Buffer;
  created_at: Date;
  updated_at: Date;
  replaced_at: Date | null;
  invalidated_at: Date | null;
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

function protectedTokenFromRow(
  row: Pick<
    PushToStartTokenRow,
    "token_digest" | "token_ciphertext" | "token_nonce" | "token_auth_tag"
  >,
): ProtectedActivityKitToken {
  return {
    version: 1,
    algorithm: "AES-256-GCM",
    digest: row.token_digest as ProtectedActivityKitToken["digest"],
    ciphertext: row.token_ciphertext,
    nonce: row.token_nonce,
    authenticationTag: row.token_auth_tag,
  };
}

function pushToStartTokenFromRow(row: PushToStartTokenRow): StoredPushToStartToken {
  return createStoredPushToStartToken({
    installationId: row.installation_id,
    serverRevision: row.server_revision,
    clientGeneration: row.client_generation,
    environment: row.apns_environment,
    lifecycle: row.lifecycle,
    protectedToken: protectedTokenFromRow(row),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    replacedAt: row.replaced_at,
    invalidatedAt: row.invalidated_at,
  });
}

function deliveryBindingFromRow(row: DeliveryBindingRow): LiveActivityDeliveryBinding {
  return createLiveActivityDeliveryBinding({
    bindingId: row.binding_id,
    installationId: row.installation_id,
    sessionId: row.session_id,
    sessionRevision: row.session_revision,
    strategy: row.delivery_strategy,
    lifecycle: row.lifecycle,
    appleActivityId: row.apple_activity_id,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    endedAt: row.ended_at,
    invalidatedAt: row.invalidated_at,
  });
}

function updateTokenFromRow(row: UpdateTokenRow): StoredLiveActivityUpdateToken {
  return createStoredLiveActivityUpdateToken({
    installationId: row.installation_id,
    bindingId: row.binding_id,
    serverRevision: row.server_revision,
    clientGeneration: row.client_generation,
    environment: row.apns_environment,
    lifecycle: row.lifecycle,
    protectedToken: protectedTokenFromRow(row),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    replacedAt: row.replaced_at,
    invalidatedAt: row.invalidated_at,
  });
}

function protectedTokensEqual(
  left: ProtectedActivityKitToken,
  right: ProtectedActivityKitToken,
): boolean {
  return (
    left.version === right.version &&
    left.algorithm === right.algorithm &&
    left.digest === right.digest &&
    left.ciphertext.equals(right.ciphertext) &&
    left.nonce.equals(right.nonce) &&
    left.authenticationTag.equals(right.authenticationTag)
  );
}

function datesEqual(left: Date | null, right: Date | null): boolean {
  return left?.getTime() === right?.getTime();
}

function pushTokenIdentityEqual(
  left: StoredPushToStartToken,
  right: StoredPushToStartToken,
): boolean {
  return (
    left.installationId === right.installationId &&
    left.serverRevision === right.serverRevision &&
    left.clientGeneration === right.clientGeneration &&
    left.environment === right.environment &&
    left.createdAt.getTime() === right.createdAt.getTime() &&
    protectedTokensEqual(left.protectedToken, right.protectedToken)
  );
}

function updateTokenIdentityEqual(
  left: StoredLiveActivityUpdateToken,
  right: StoredLiveActivityUpdateToken,
): boolean {
  return (
    left.installationId === right.installationId &&
    left.bindingId === right.bindingId &&
    left.serverRevision === right.serverRevision &&
    left.clientGeneration === right.clientGeneration &&
    left.environment === right.environment &&
    left.createdAt.getTime() === right.createdAt.getTime() &&
    protectedTokensEqual(left.protectedToken, right.protectedToken)
  );
}

function tokenStateEqual(
  left: StoredPushToStartToken | StoredLiveActivityUpdateToken,
  right: StoredPushToStartToken | StoredLiveActivityUpdateToken,
): boolean {
  return (
    left.lifecycle === right.lifecycle &&
    left.updatedAt.getTime() === right.updatedAt.getTime() &&
    datesEqual(left.replacedAt, right.replacedAt) &&
    datesEqual(left.invalidatedAt, right.invalidatedAt)
  );
}

function validTokenTransition(
  current: StoredPushToStartToken | StoredLiveActivityUpdateToken,
  next: StoredPushToStartToken | StoredLiveActivityUpdateToken,
): boolean {
  return (
    current.lifecycle === "CURRENT" &&
    (next.lifecycle === "REPLACED" || next.lifecycle === "INVALIDATED") &&
    next.updatedAt.getTime() >= current.updatedAt.getTime()
  );
}

function bindingIdentityEqual(
  left: LiveActivityDeliveryBinding,
  right: LiveActivityDeliveryBinding,
): boolean {
  return (
    left.bindingId === right.bindingId &&
    left.installationId === right.installationId &&
    left.sessionId === right.sessionId &&
    left.sessionRevision === right.sessionRevision &&
    left.strategy === right.strategy &&
    left.createdAt.getTime() === right.createdAt.getTime()
  );
}

function bindingStateEqual(
  left: LiveActivityDeliveryBinding,
  right: LiveActivityDeliveryBinding,
): boolean {
  return (
    left.appleActivityId === right.appleActivityId &&
    left.lifecycle === right.lifecycle &&
    left.updatedAt.getTime() === right.updatedAt.getTime() &&
    datesEqual(left.endedAt, right.endedAt) &&
    datesEqual(left.invalidatedAt, right.invalidatedAt)
  );
}

async function persistenceOperation<T>(operation: () => Promise<T> | T): Promise<T> {
  try {
    return await operation();
  } catch {
    throw new LiveActivityDeliveryPersistenceError();
  }
}

/**
 * PostgreSQL-backed ActivityKit delivery state. The caller owns and closes the supplied
 * connection pool. Construction performs no database or network work.
 */
export class PostgresLiveActivityDeliveryStore implements LiveActivityDeliveryStore {
  constructor(private readonly sql: LiveActivityDeliveryPostgresSql) {}

  async withInstallationTransaction<T>(
    installationId: string,
    operation: (transaction: LiveActivityDeliveryInstallationTransaction) => Promise<T>,
  ): Promise<T> {
    const normalizedInstallationId = normalizedAppleDeliveryIdentifier(
      installationId,
      "installationId",
    );

    try {
      return (await this.sql.begin(async (transactionSql) => {
        await transactionSql`
          SELECT installation_id
          FROM live_commute_installations
          WHERE installation_id = ${normalizedInstallationId}
          FOR UPDATE
        `;

        let tokenHistoryWasSaved = false;
        const transaction: LiveActivityDeliveryInstallationTransaction = {
          getInstallation: async () =>
            await persistenceOperation(async () => {
              const rows = await transactionSql<InstallationRow[]>`
                SELECT installation_id, credential_digest, revoked_at, created_at, updated_at
                FROM live_commute_installations
                WHERE installation_id = ${normalizedInstallationId}
              `;
              return rows[0] == null ? undefined : installationFromRow(rows[0]);
            }),

          getSession: async (sessionId) =>
            await persistenceOperation(async () => {
              const normalizedSessionId = normalizedAppleDeliveryIdentifier(
                sessionId,
                "sessionId",
              );
              const rows = await transactionSql<SessionRow[]>`
                SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                       lifecycle, revision, created_at, updated_at, cancelled_at
                FROM live_commute_sessions
                WHERE installation_id = ${normalizedInstallationId}
                  AND session_id = ${normalizedSessionId}
              `;
              return rows[0] == null ? undefined : sessionFromRow(rows[0]);
            }),

          getLatestPushToStartToken: async () =>
            await persistenceOperation(async () => {
              const rows = await transactionSql<PushToStartTokenRow[]>`
                SELECT installation_id, server_revision, client_generation, apns_environment,
                       lifecycle, token_digest, token_ciphertext, token_nonce, token_auth_tag,
                       created_at, updated_at, replaced_at, invalidated_at
                FROM live_activity_push_to_start_tokens
                WHERE installation_id = ${normalizedInstallationId}
                ORDER BY server_revision DESC
                LIMIT 1
              `;
              return rows[0] == null ? undefined : pushToStartTokenFromRow(rows[0]);
            }),

          getPushToStartToken: async (clientGeneration) =>
            await persistenceOperation(async () => {
              const generation = positiveActivityKitGeneration(
                clientGeneration,
                "clientGeneration",
              );
              const rows = await transactionSql<PushToStartTokenRow[]>`
                SELECT installation_id, server_revision, client_generation, apns_environment,
                       lifecycle, token_digest, token_ciphertext, token_nonce, token_auth_tag,
                       created_at, updated_at, replaced_at, invalidated_at
                FROM live_activity_push_to_start_tokens
                WHERE installation_id = ${normalizedInstallationId}
                  AND client_generation = ${generation}
              `;
              return rows[0] == null ? undefined : pushToStartTokenFromRow(rows[0]);
            }),

          savePushToStartToken: async (token) =>
            await persistenceOperation(async () => {
              tokenHistoryWasSaved = true;
              const record = createStoredPushToStartToken(token);
              if (record.installationId !== normalizedInstallationId) {
                throw new Error("push-to-start token transaction identity mismatch");
              }
              const existingRows = await transactionSql<PushToStartTokenRow[]>`
                SELECT installation_id, server_revision, client_generation, apns_environment,
                       lifecycle, token_digest, token_ciphertext, token_nonce, token_auth_tag,
                       created_at, updated_at, replaced_at, invalidated_at
                FROM live_activity_push_to_start_tokens
                WHERE installation_id = ${normalizedInstallationId}
                  AND server_revision = ${record.serverRevision}
              `;
              const existing =
                existingRows[0] == null ? undefined : pushToStartTokenFromRow(existingRows[0]);

              if (existing == null) {
                if (record.lifecycle !== "CURRENT") {
                  throw new Error("initial push-to-start token transition is invalid");
                }
                const latestRows = await transactionSql<PushToStartTokenRow[]>`
                  SELECT installation_id, server_revision, client_generation, apns_environment,
                         lifecycle, token_digest, token_ciphertext, token_nonce, token_auth_tag,
                         created_at, updated_at, replaced_at, invalidated_at
                  FROM live_activity_push_to_start_tokens
                  WHERE installation_id = ${normalizedInstallationId}
                  ORDER BY server_revision DESC
                  LIMIT 1
                `;
                const latest =
                  latestRows[0] == null
                    ? undefined
                    : pushToStartTokenFromRow(latestRows[0]);
                if (
                  record.serverRevision !== (latest?.serverRevision ?? 0) + 1 ||
                  (latest != null &&
                    (latest.lifecycle === "CURRENT" ||
                      record.clientGeneration <= latest.clientGeneration ||
                      record.createdAt.getTime() < latest.updatedAt.getTime()))
                ) {
                  throw new Error("push-to-start token revision is invalid");
                }
                await transactionSql`
                  INSERT INTO live_activity_push_to_start_tokens (
                    installation_id, server_revision, client_generation, apns_environment,
                    lifecycle, token_digest, token_ciphertext, token_nonce, token_auth_tag,
                    created_at, updated_at, replaced_at, invalidated_at
                  ) VALUES (
                    ${record.installationId}, ${record.serverRevision},
                    ${record.clientGeneration}, ${record.environment}, ${record.lifecycle},
                    ${record.protectedToken.digest}, ${record.protectedToken.ciphertext},
                    ${record.protectedToken.nonce}, ${record.protectedToken.authenticationTag},
                    ${record.createdAt}, ${record.updatedAt}, ${record.replacedAt},
                    ${record.invalidatedAt}
                  )
                `;
                return;
              }

              if (!pushTokenIdentityEqual(existing, record)) {
                throw new Error("push-to-start token identity mutation is invalid");
              }
              if (tokenStateEqual(existing, record)) return;
              if (!validTokenTransition(existing, record)) {
                throw new Error("push-to-start token transition is invalid");
              }
              const rows = await transactionSql<{ server_revision: number }[]>`
                UPDATE live_activity_push_to_start_tokens
                SET lifecycle = ${record.lifecycle},
                    updated_at = ${record.updatedAt},
                    replaced_at = ${record.replacedAt},
                    invalidated_at = ${record.invalidatedAt}
                WHERE installation_id = ${normalizedInstallationId}
                  AND server_revision = ${record.serverRevision}
                  AND lifecycle = 'CURRENT'
                RETURNING server_revision
              `;
              if (rows.length !== 1) {
                throw new Error("push-to-start token no longer current");
              }
            }),

          getDeliveryBinding: async (bindingId) =>
            await persistenceOperation(async () => {
              const normalizedBindingId = normalizedLiveActivityBindingId(bindingId);
              const rows = await transactionSql<DeliveryBindingRow[]>`
                SELECT binding_id, installation_id, session_id, session_revision,
                       delivery_strategy, lifecycle, apple_activity_id, created_at, updated_at,
                       ended_at, invalidated_at
                FROM live_activity_delivery_bindings
                WHERE binding_id = ${normalizedBindingId}
                  AND installation_id = ${normalizedInstallationId}
              `;
              return rows[0] == null ? undefined : deliveryBindingFromRow(rows[0]);
            }),

          getDeliveryBindingForSessionVersion: async (sessionId, sessionRevision) =>
            await persistenceOperation(async () => {
              const normalizedSessionId = normalizedAppleDeliveryIdentifier(
                sessionId,
                "sessionId",
              );
              const revision = positiveActivityKitGeneration(
                sessionRevision,
                "sessionRevision",
              );
              const rows = await transactionSql<DeliveryBindingRow[]>`
                SELECT binding_id, installation_id, session_id, session_revision,
                       delivery_strategy, lifecycle, apple_activity_id, created_at, updated_at,
                       ended_at, invalidated_at
                FROM live_activity_delivery_bindings
                WHERE installation_id = ${normalizedInstallationId}
                  AND session_id = ${normalizedSessionId}
                  AND session_revision = ${revision}
              `;
              return rows[0] == null ? undefined : deliveryBindingFromRow(rows[0]);
            }),

          saveDeliveryBinding: async (binding) =>
            await persistenceOperation(async () => {
              const record = createLiveActivityDeliveryBinding(binding);
              if (record.installationId !== normalizedInstallationId) {
                throw new Error("delivery binding transaction identity mismatch");
              }
              if (record.appleActivityId != null) {
                const conflictingActivityRows = await transactionSql<
                  { binding_id: string }[]
                >`
                  SELECT binding_id
                  FROM live_activity_delivery_bindings
                  WHERE installation_id = ${normalizedInstallationId}
                    AND apple_activity_id = ${record.appleActivityId}
                    AND binding_id <> ${record.bindingId}
                  LIMIT 1
                `;
                if (conflictingActivityRows.length > 0) return false;
              }
              const existingRows = await transactionSql<DeliveryBindingRow[]>`
                SELECT binding_id, installation_id, session_id, session_revision,
                       delivery_strategy, lifecycle, apple_activity_id, created_at, updated_at,
                       ended_at, invalidated_at
                FROM live_activity_delivery_bindings
                WHERE binding_id = ${record.bindingId}
                  AND installation_id = ${normalizedInstallationId}
              `;
              const existing =
                existingRows[0] == null ? undefined : deliveryBindingFromRow(existingRows[0]);

              if (existing == null) {
                if (record.lifecycle !== "PENDING_START") {
                  throw new Error("initial delivery binding transition is invalid");
                }
                const sessionRows = await transactionSql<SessionRow[]>`
                  SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                         lifecycle, revision, created_at, updated_at, cancelled_at
                  FROM live_commute_sessions
                  WHERE installation_id = ${normalizedInstallationId}
                    AND session_id = ${record.sessionId}
                `;
                const session =
                  sessionRows[0] == null ? undefined : sessionFromRow(sessionRows[0]);
                if (
                  session == null ||
                  session.lifecycle !== "REGISTERED" ||
                  session.revision !== record.sessionRevision
                ) {
                  throw new Error("delivery binding session authority is invalid");
                }
                const rows = await transactionSql<{ binding_id: string }[]>`
                  INSERT INTO live_activity_delivery_bindings (
                    binding_id, installation_id, session_id, session_revision,
                    delivery_strategy, lifecycle, apple_activity_id, created_at, updated_at,
                    ended_at, invalidated_at
                  ) VALUES (
                    ${record.bindingId}, ${record.installationId}, ${record.sessionId},
                    ${record.sessionRevision}, ${record.strategy}, ${record.lifecycle},
                    ${record.appleActivityId}, ${record.createdAt}, ${record.updatedAt},
                    ${record.endedAt}, ${record.invalidatedAt}
                  )
                  ON CONFLICT DO NOTHING
                  RETURNING binding_id
                `;
                return rows.length === 1;
              }

              if (!bindingIdentityEqual(existing, record)) {
                throw new Error("delivery binding identity mutation is invalid");
              }
              if (bindingStateEqual(existing, record)) return true;
              const attachesActivityIdentifier =
                existing.lifecycle === "PENDING_START" &&
                record.lifecycle === "PENDING_START" &&
                existing.appleActivityId == null &&
                record.appleActivityId != null;
              const terminalizesBinding =
                existing.lifecycle === "PENDING_START" &&
                (record.lifecycle === "ENDED" ||
                  record.lifecycle === "INVALIDATED") &&
                record.appleActivityId === existing.appleActivityId;
              if (
                (!attachesActivityIdentifier && !terminalizesBinding) ||
                record.updatedAt.getTime() < existing.updatedAt.getTime()
              ) {
                throw new Error("delivery binding transition is invalid");
              }
              const rows = await transactionSql<{ binding_id: string }[]>`
                UPDATE live_activity_delivery_bindings
                SET apple_activity_id = ${record.appleActivityId},
                    lifecycle = ${record.lifecycle},
                    updated_at = ${record.updatedAt},
                    ended_at = ${record.endedAt},
                    invalidated_at = ${record.invalidatedAt}
                WHERE binding_id = ${record.bindingId}
                  AND installation_id = ${normalizedInstallationId}
                  AND lifecycle = 'PENDING_START'
                RETURNING binding_id
              `;
              if (rows.length !== 1) {
                throw new Error("delivery binding no longer pending");
              }
              return true;
            }),

          getLatestUpdateToken: async (bindingId) =>
            await persistenceOperation(async () => {
              const normalizedBindingId = normalizedLiveActivityBindingId(bindingId);
              const rows = await transactionSql<UpdateTokenRow[]>`
                SELECT binding_id, installation_id, binding_strategy, server_revision,
                       client_generation, apns_environment, lifecycle, token_digest,
                       token_ciphertext, token_nonce, token_auth_tag, created_at, updated_at,
                       replaced_at, invalidated_at
                FROM live_activity_update_tokens
                WHERE binding_id = ${normalizedBindingId}
                  AND installation_id = ${normalizedInstallationId}
                ORDER BY server_revision DESC
                LIMIT 1
              `;
              return rows[0] == null ? undefined : updateTokenFromRow(rows[0]);
            }),

          getUpdateToken: async (bindingId, clientGeneration) =>
            await persistenceOperation(async () => {
              const normalizedBindingId = normalizedLiveActivityBindingId(bindingId);
              const generation = positiveActivityKitGeneration(
                clientGeneration,
                "clientGeneration",
              );
              const rows = await transactionSql<UpdateTokenRow[]>`
                SELECT binding_id, installation_id, binding_strategy, server_revision,
                       client_generation, apns_environment, lifecycle, token_digest,
                       token_ciphertext, token_nonce, token_auth_tag, created_at, updated_at,
                       replaced_at, invalidated_at
                FROM live_activity_update_tokens
                WHERE binding_id = ${normalizedBindingId}
                  AND installation_id = ${normalizedInstallationId}
                  AND client_generation = ${generation}
              `;
              return rows[0] == null ? undefined : updateTokenFromRow(rows[0]);
            }),

          saveUpdateToken: async (token) =>
            await persistenceOperation(async () => {
              tokenHistoryWasSaved = true;
              const record = createStoredLiveActivityUpdateToken(token);
              if (record.installationId !== normalizedInstallationId) {
                throw new Error("update token transaction identity mismatch");
              }
              const bindingRows = await transactionSql<DeliveryBindingRow[]>`
                SELECT binding_id, installation_id, session_id, session_revision,
                       delivery_strategy, lifecycle, apple_activity_id, created_at, updated_at,
                       ended_at, invalidated_at
                FROM live_activity_delivery_bindings
                WHERE binding_id = ${record.bindingId}
                  AND installation_id = ${normalizedInstallationId}
              `;
              const binding =
                bindingRows[0] == null ? undefined : deliveryBindingFromRow(bindingRows[0]);
              if (binding == null || binding.strategy !== "DIRECT_TOKEN") {
                throw new Error("update token binding ownership is invalid");
              }

              const existingRows = await transactionSql<UpdateTokenRow[]>`
                SELECT binding_id, installation_id, binding_strategy, server_revision,
                       client_generation, apns_environment, lifecycle, token_digest,
                       token_ciphertext, token_nonce, token_auth_tag, created_at, updated_at,
                       replaced_at, invalidated_at
                FROM live_activity_update_tokens
                WHERE binding_id = ${record.bindingId}
                  AND installation_id = ${normalizedInstallationId}
                  AND server_revision = ${record.serverRevision}
              `;
              const existing =
                existingRows[0] == null ? undefined : updateTokenFromRow(existingRows[0]);

              if (existing == null) {
                if (
                  record.lifecycle !== "CURRENT" ||
                  binding.lifecycle !== "PENDING_START"
                ) {
                  throw new Error("initial update token transition is invalid");
                }
                const sessionRows = await transactionSql<SessionRow[]>`
                  SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                         lifecycle, revision, created_at, updated_at, cancelled_at
                  FROM live_commute_sessions
                  WHERE installation_id = ${normalizedInstallationId}
                    AND session_id = ${binding.sessionId}
                `;
                const session =
                  sessionRows[0] == null ? undefined : sessionFromRow(sessionRows[0]);
                if (
                  session == null ||
                  session.lifecycle !== "REGISTERED" ||
                  session.revision !== binding.sessionRevision
                ) {
                  throw new Error("update token session authority is invalid");
                }
                const latestRows = await transactionSql<UpdateTokenRow[]>`
                  SELECT binding_id, installation_id, binding_strategy, server_revision,
                         client_generation, apns_environment, lifecycle, token_digest,
                         token_ciphertext, token_nonce, token_auth_tag, created_at, updated_at,
                         replaced_at, invalidated_at
                  FROM live_activity_update_tokens
                  WHERE binding_id = ${record.bindingId}
                    AND installation_id = ${normalizedInstallationId}
                  ORDER BY server_revision DESC
                  LIMIT 1
                `;
                const latest =
                  latestRows[0] == null ? undefined : updateTokenFromRow(latestRows[0]);
                if (
                  record.serverRevision !== (latest?.serverRevision ?? 0) + 1 ||
                  (latest != null &&
                    (latest.lifecycle === "CURRENT" ||
                      record.clientGeneration <= latest.clientGeneration ||
                      record.createdAt.getTime() < latest.updatedAt.getTime()))
                ) {
                  throw new Error("update token revision is invalid");
                }
                await transactionSql`
                  INSERT INTO live_activity_update_tokens (
                    binding_id, installation_id, binding_strategy, server_revision,
                    client_generation, apns_environment, lifecycle, token_digest,
                    token_ciphertext, token_nonce, token_auth_tag, created_at, updated_at,
                    replaced_at, invalidated_at
                  ) VALUES (
                    ${record.bindingId}, ${record.installationId}, 'DIRECT_TOKEN',
                    ${record.serverRevision}, ${record.clientGeneration}, ${record.environment},
                    ${record.lifecycle}, ${record.protectedToken.digest},
                    ${record.protectedToken.ciphertext}, ${record.protectedToken.nonce},
                    ${record.protectedToken.authenticationTag}, ${record.createdAt},
                    ${record.updatedAt}, ${record.replacedAt}, ${record.invalidatedAt}
                  )
                `;
                return;
              }

              if (!updateTokenIdentityEqual(existing, record)) {
                throw new Error("update token identity mutation is invalid");
              }
              if (tokenStateEqual(existing, record)) return;
              if (!validTokenTransition(existing, record)) {
                throw new Error("update token transition is invalid");
              }
              if (record.lifecycle === "REPLACED") {
                if (binding.lifecycle !== "PENDING_START") {
                  throw new Error("update token binding is terminal");
                }
                const sessionRows = await transactionSql<SessionRow[]>`
                  SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                         lifecycle, revision, created_at, updated_at, cancelled_at
                  FROM live_commute_sessions
                  WHERE installation_id = ${normalizedInstallationId}
                    AND session_id = ${binding.sessionId}
                `;
                const session =
                  sessionRows[0] == null ? undefined : sessionFromRow(sessionRows[0]);
                if (
                  session == null ||
                  session.lifecycle !== "REGISTERED" ||
                  session.revision !== binding.sessionRevision
                ) {
                  throw new Error("update token session authority is invalid");
                }
              }
              const rows = await transactionSql<{ server_revision: number }[]>`
                UPDATE live_activity_update_tokens
                SET lifecycle = ${record.lifecycle},
                    updated_at = ${record.updatedAt},
                    replaced_at = ${record.replacedAt},
                    invalidated_at = ${record.invalidatedAt}
                WHERE binding_id = ${record.bindingId}
                  AND installation_id = ${normalizedInstallationId}
                  AND server_revision = ${record.serverRevision}
                  AND lifecycle = 'CURRENT'
                RETURNING server_revision
              `;
              if (rows.length !== 1) {
                throw new Error("update token no longer current");
              }
            }),
        };

        try {
          const result = await operation(transaction);
          if (tokenHistoryWasSaved) await persistenceOperation(async () => {
            const invalidPushHistory = await transactionSql<{ invalid: number }[]>`
              WITH history AS (
                SELECT lifecycle,
                       row_number() OVER (ORDER BY server_revision DESC) AS recency,
                       count(*) FILTER (WHERE lifecycle = 'CURRENT') OVER () AS current_count
                FROM live_activity_push_to_start_tokens
                WHERE installation_id = ${normalizedInstallationId}
              )
              SELECT 1 AS invalid
              FROM history
              WHERE recency = 1
                AND (
                  (current_count = 0 AND lifecycle <> 'INVALIDATED')
                  OR (current_count = 1 AND lifecycle <> 'CURRENT')
                  OR current_count > 1
                )
              LIMIT 1
            `;
            const invalidUpdateHistory = await transactionSql<{ invalid: number }[]>`
              WITH history AS (
                SELECT binding_id, lifecycle,
                       row_number() OVER (
                         PARTITION BY binding_id ORDER BY server_revision DESC
                       ) AS recency,
                       count(*) FILTER (WHERE lifecycle = 'CURRENT') OVER (
                         PARTITION BY binding_id
                       ) AS current_count
                FROM live_activity_update_tokens
                WHERE installation_id = ${normalizedInstallationId}
              )
              SELECT 1 AS invalid
              FROM history
              WHERE recency = 1
                AND (
                  (current_count = 0 AND lifecycle <> 'INVALIDATED')
                  OR (current_count = 1 AND lifecycle <> 'CURRENT')
                  OR current_count > 1
                )
              LIMIT 1
            `;
            if (invalidPushHistory.length > 0 || invalidUpdateHistory.length > 0) {
              throw new Error("delivery token history is incomplete");
            }
          });
          return result;
        } catch (failure) {
          throw new DeliveryCallbackFailure(failure);
        }
      })) as T;
    } catch (failure) {
      if (failure instanceof DeliveryCallbackFailure) throw failure.failure;
      throw new LiveActivityDeliveryPersistenceError();
    }
  }
}
