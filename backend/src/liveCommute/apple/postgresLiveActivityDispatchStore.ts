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
  type LiveActivityDeliveryBinding,
  type StoredLiveActivityUpdateToken,
  type StoredPushToStartToken,
} from "./deliveryModel.js";
import {
  createLiveActivityDirectDispatchAttempt,
  createLiveActivityDispatchCursor,
  normalizedLiveActivityDispatchOperation,
  type LiveActivityDirectDispatchAttempt,
  type LiveActivityDispatchCursor,
} from "./dispatchModel.js";
import {
  CoordinatedLiveActivityDispatchStore,
  type LiveActivityDispatchInstallationTransaction,
} from "./dispatchStore.js";
import type { ProtectedActivityKitToken } from "./tokenProtection.js";

export type LiveActivityDispatchPostgresSql = ReturnType<typeof postgres>;

export class LiveActivityDispatchPersistenceError extends Error {
  readonly code = "LIVE_ACTIVITY_DISPATCH_PERSISTENCE_FAILED" as const;

  constructor() {
    super("Live Activity dispatch persistence operation failed");
    this.name = "LiveActivityDispatchPersistenceError";
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

interface BindingRow {
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

interface TokenRow {
  installation_id: string;
  binding_id?: string;
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

interface CursorRow {
  binding_id: string;
  installation_id: string;
  session_revision: number;
  last_reserved_event_timestamp: string | number;
  terminal_intent_event_timestamp: string | number | null;
  created_at: Date;
  updated_at: Date;
}

interface AttemptRow {
  dispatch_id: string;
  binding_id: string;
  installation_id: string;
  session_revision: number;
  operation_kind: LiveActivityDirectDispatchAttempt["operation"];
  event_timestamp: string | number;
  push_to_start_server_revision: number | null;
  push_to_start_client_generation: number | null;
  update_token_server_revision: number | null;
  update_token_client_generation: number | null;
  apns_environment: LiveActivityDirectDispatchAttempt["environment"];
  apns_request_id: string;
  payload_fingerprint: string | null;
  state: LiveActivityDirectDispatchAttempt["state"];
  apns_status: number | null;
  apns_reason: string | null;
  retry_advice: LiveActivityDirectDispatchAttempt["retryAdvice"];
  retry_not_before: Date | null;
  post_send_authority: LiveActivityDirectDispatchAttempt["postSendAuthority"];
  token_invalidation_outcome: LiveActivityDirectDispatchAttempt["tokenInvalidationOutcome"];
  created_at: Date;
  in_flight_at: Date | null;
  completed_at: Date | null;
}

function installationFromRow(row: InstallationRow): StoredLiveCommuteInstallation {
  return createStoredLiveCommuteInstallation({
    installationId: row.installation_id,
    credentialDigest:
      row.credential_digest as StoredLiveCommuteInstallation["credentialDigest"],
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

function bindingFromRow(row: BindingRow): LiveActivityDeliveryBinding {
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

function protectedTokenFromRow(row: TokenRow): ProtectedActivityKitToken {
  return {
    version: 1,
    algorithm: "AES-256-GCM",
    digest: row.token_digest as ProtectedActivityKitToken["digest"],
    ciphertext: row.token_ciphertext,
    nonce: row.token_nonce,
    authenticationTag: row.token_auth_tag,
  };
}

function pushTokenFromRow(row: TokenRow): StoredPushToStartToken {
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

function updateTokenFromRow(row: TokenRow): StoredLiveActivityUpdateToken {
  if (row.binding_id == null) throw new Error("missing update-token binding");
  return createStoredLiveActivityUpdateToken({
    bindingId: row.binding_id,
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

function safeBigIntNumber(value: string | number, field: string): number {
  const result = typeof value === "number" ? value : Number(value);
  if (!Number.isSafeInteger(result) || result < 0) {
    throw new RangeError(`${field} is outside the safe integer range`);
  }
  return result;
}

function cursorFromRow(row: CursorRow): LiveActivityDispatchCursor {
  return createLiveActivityDispatchCursor({
    bindingId: row.binding_id,
    installationId: row.installation_id,
    sessionRevision: row.session_revision,
    lastReservedEventTimestamp: safeBigIntNumber(
      row.last_reserved_event_timestamp,
      "last reserved event timestamp",
    ),
    terminalIntentEventTimestamp:
      row.terminal_intent_event_timestamp == null
        ? null
        : safeBigIntNumber(
            row.terminal_intent_event_timestamp,
            "terminal intent event timestamp",
          ),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

function attemptFromRow(row: AttemptRow): LiveActivityDirectDispatchAttempt {
  const isStart = row.operation_kind === "START";
  const clientGeneration = isStart
    ? row.push_to_start_client_generation
    : row.update_token_client_generation;
  const serverRevision = isStart
    ? row.push_to_start_server_revision
    : row.update_token_server_revision;
  if (clientGeneration == null || serverRevision == null) {
    throw new Error("dispatch token correlation is incomplete");
  }
  return createLiveActivityDirectDispatchAttempt({
    dispatchId: row.dispatch_id,
    bindingId: row.binding_id,
    installationId: row.installation_id,
    sessionRevision: row.session_revision,
    operation: row.operation_kind,
    eventTimestamp: safeBigIntNumber(row.event_timestamp, "event timestamp"),
    tokenGeneration: { clientGeneration, serverRevision },
    environment: row.apns_environment,
    apnsRequestId: row.apns_request_id,
    payloadFingerprint: row.payload_fingerprint,
    state: row.state,
    apnsStatus: row.apns_status,
    apnsReason: row.apns_reason,
    retryAdvice: row.retry_advice,
    retryNotBefore: row.retry_not_before,
    postSendAuthority: row.post_send_authority,
    tokenInvalidationOutcome: row.token_invalidation_outcome,
    createdAt: row.created_at,
    inFlightAt: row.in_flight_at,
    completedAt: row.completed_at,
  });
}

/** PostgreSQL is the cross-process authority; construction performs no I/O. */
export class PostgresLiveActivityDispatchStore extends CoordinatedLiveActivityDispatchStore {
  constructor(private readonly sql: LiveActivityDispatchPostgresSql) {
    super();
  }

  protected async withDispatchInstallationTransaction<T>(
    installationId: string,
    operation: (
      transaction: LiveActivityDispatchInstallationTransaction,
    ) => Promise<T>,
  ): Promise<T> {
    try {
      return (await this.sql.begin(async (transactionSql) => {
        await transactionSql`
          SELECT installation_id
          FROM live_commute_installations
          WHERE installation_id = ${installationId}
          FOR UPDATE
        `;

        const transaction: LiveActivityDispatchInstallationTransaction = {
          getInstallation: async () => {
            const rows = await transactionSql<InstallationRow[]>`
              SELECT installation_id, credential_digest, revoked_at, created_at, updated_at
              FROM live_commute_installations
              WHERE installation_id = ${installationId}
            `;
            return rows[0] == null ? undefined : installationFromRow(rows[0]);
          },
          getSession: async (sessionId) => {
            const rows = await transactionSql<SessionRow[]>`
              SELECT installation_id, session_id, routine_id, starts_at, ends_at, query,
                     lifecycle, revision, created_at, updated_at, cancelled_at
              FROM live_commute_sessions
              WHERE installation_id = ${installationId}
                AND session_id = ${sessionId}
            `;
            return rows[0] == null ? undefined : sessionFromRow(rows[0]);
          },
          getDeliveryBinding: async (bindingId) => {
            const rows = await transactionSql<BindingRow[]>`
              SELECT binding_id, installation_id, session_id, session_revision,
                     delivery_strategy, lifecycle, apple_activity_id, created_at,
                     updated_at, ended_at, invalidated_at
              FROM live_activity_delivery_bindings
              WHERE binding_id = ${bindingId}
                AND installation_id = ${installationId}
            `;
            return rows[0] == null ? undefined : bindingFromRow(rows[0]);
          },
          getLatestPushToStartToken: async () => {
            const rows = await transactionSql<TokenRow[]>`
              SELECT installation_id, server_revision, client_generation,
                     apns_environment, lifecycle, token_digest, token_ciphertext,
                     token_nonce, token_auth_tag, created_at, updated_at,
                     replaced_at, invalidated_at
              FROM live_activity_push_to_start_tokens
              WHERE installation_id = ${installationId}
              ORDER BY server_revision DESC
              LIMIT 1
            `;
            return rows[0] == null ? undefined : pushTokenFromRow(rows[0]);
          },
          getPushToStartToken: async (clientGeneration) => {
            const rows = await transactionSql<TokenRow[]>`
              SELECT installation_id, server_revision, client_generation,
                     apns_environment, lifecycle, token_digest, token_ciphertext,
                     token_nonce, token_auth_tag, created_at, updated_at,
                     replaced_at, invalidated_at
              FROM live_activity_push_to_start_tokens
              WHERE installation_id = ${installationId}
                AND client_generation = ${clientGeneration}
            `;
            return rows[0] == null ? undefined : pushTokenFromRow(rows[0]);
          },
          savePushToStartToken: async (tokenInput) => {
            const token = createStoredPushToStartToken(tokenInput);
            if (token.installationId !== installationId || token.lifecycle !== "INVALIDATED") {
              throw new Error("invalid dispatch push-token transition");
            }
            const rows = await transactionSql<{ server_revision: number }[]>`
              UPDATE live_activity_push_to_start_tokens
              SET lifecycle = 'INVALIDATED',
                  updated_at = ${token.updatedAt},
                  replaced_at = NULL,
                  invalidated_at = ${token.invalidatedAt}
              WHERE installation_id = ${installationId}
                AND server_revision = ${token.serverRevision}
                AND client_generation = ${token.clientGeneration}
                AND apns_environment = ${token.environment}
                AND lifecycle = 'CURRENT'
              RETURNING server_revision
            `;
            if (rows.length !== 1) throw new Error("push-token generation changed");
          },
          getLatestUpdateToken: async (bindingId) => {
            const rows = await transactionSql<TokenRow[]>`
              SELECT binding_id, installation_id, server_revision,
                     client_generation, apns_environment, lifecycle, token_digest,
                     token_ciphertext, token_nonce, token_auth_tag, created_at,
                     updated_at, replaced_at, invalidated_at
              FROM live_activity_update_tokens
              WHERE binding_id = ${bindingId}
                AND installation_id = ${installationId}
              ORDER BY server_revision DESC
              LIMIT 1
            `;
            return rows[0] == null ? undefined : updateTokenFromRow(rows[0]);
          },
          getUpdateToken: async (bindingId, clientGeneration) => {
            const rows = await transactionSql<TokenRow[]>`
              SELECT binding_id, installation_id, server_revision,
                     client_generation, apns_environment, lifecycle, token_digest,
                     token_ciphertext, token_nonce, token_auth_tag, created_at,
                     updated_at, replaced_at, invalidated_at
              FROM live_activity_update_tokens
              WHERE binding_id = ${bindingId}
                AND installation_id = ${installationId}
                AND client_generation = ${clientGeneration}
            `;
            return rows[0] == null ? undefined : updateTokenFromRow(rows[0]);
          },
          saveUpdateToken: async (tokenInput) => {
            const token = createStoredLiveActivityUpdateToken(tokenInput);
            if (token.installationId !== installationId || token.lifecycle !== "INVALIDATED") {
              throw new Error("invalid dispatch update-token transition");
            }
            const rows = await transactionSql<{ server_revision: number }[]>`
              UPDATE live_activity_update_tokens
              SET lifecycle = 'INVALIDATED',
                  updated_at = ${token.updatedAt},
                  replaced_at = NULL,
                  invalidated_at = ${token.invalidatedAt}
              WHERE binding_id = ${token.bindingId}
                AND installation_id = ${installationId}
                AND server_revision = ${token.serverRevision}
                AND client_generation = ${token.clientGeneration}
                AND apns_environment = ${token.environment}
                AND lifecycle = 'CURRENT'
              RETURNING server_revision
            `;
            if (rows.length !== 1) throw new Error("update-token generation changed");
          },
          getDispatchCursor: async (bindingId) => {
            const rows = await transactionSql<CursorRow[]>`
              SELECT binding_id, installation_id, session_revision,
                     last_reserved_event_timestamp,
                     terminal_intent_event_timestamp, created_at, updated_at
              FROM live_activity_direct_dispatch_state
              WHERE binding_id = ${bindingId}
                AND installation_id = ${installationId}
              FOR UPDATE
            `;
            return rows[0] == null ? undefined : cursorFromRow(rows[0]);
          },
          insertDispatchCursor: async (cursorInput) => {
            const cursor = createLiveActivityDispatchCursor(cursorInput);
            const rows = await transactionSql<{ binding_id: string }[]>`
              INSERT INTO live_activity_direct_dispatch_state (
                binding_id, installation_id, binding_strategy, session_revision,
                last_reserved_event_timestamp, terminal_intent_event_timestamp,
                created_at, updated_at
              ) VALUES (
                ${cursor.bindingId}, ${cursor.installationId}, 'DIRECT_TOKEN',
                ${cursor.sessionRevision}, ${cursor.lastReservedEventTimestamp},
                ${cursor.terminalIntentEventTimestamp}, ${cursor.createdAt},
                ${cursor.updatedAt}
              )
              ON CONFLICT DO NOTHING
              RETURNING binding_id
            `;
            return rows.length === 1;
          },
          updateDispatchCursor: async (cursorInput) => {
            const cursor = createLiveActivityDispatchCursor(cursorInput);
            const rows = await transactionSql<{ binding_id: string }[]>`
              UPDATE live_activity_direct_dispatch_state
              SET last_reserved_event_timestamp = ${cursor.lastReservedEventTimestamp},
                  terminal_intent_event_timestamp = ${cursor.terminalIntentEventTimestamp},
                  updated_at = ${cursor.updatedAt}
              WHERE binding_id = ${cursor.bindingId}
                AND installation_id = ${cursor.installationId}
                AND session_revision = ${cursor.sessionRevision}
                AND last_reserved_event_timestamp < ${cursor.lastReservedEventTimestamp}
              RETURNING binding_id
            `;
            return rows.length === 1;
          },
          getDispatchAttempt: async (dispatchId) => {
            const rows = await transactionSql<AttemptRow[]>`
              SELECT dispatch_id, binding_id, installation_id, session_revision,
                     operation_kind, event_timestamp,
                     push_to_start_server_revision,
                     push_to_start_client_generation, update_token_server_revision,
                     update_token_client_generation, apns_environment,
                     apns_request_id, payload_fingerprint, state, apns_status,
                     apns_reason, retry_advice, retry_not_before,
                     post_send_authority, token_invalidation_outcome, created_at,
                     in_flight_at, completed_at
              FROM live_activity_direct_dispatch_attempts
              WHERE dispatch_id = ${dispatchId}
                AND installation_id = ${installationId}
            `;
            return rows[0] == null ? undefined : attemptFromRow(rows[0]);
          },
          getActiveDispatchAttempt: async (bindingId) => {
            const rows = await transactionSql<AttemptRow[]>`
              SELECT dispatch_id, binding_id, installation_id, session_revision,
                     operation_kind, event_timestamp,
                     push_to_start_server_revision,
                     push_to_start_client_generation, update_token_server_revision,
                     update_token_client_generation, apns_environment,
                     apns_request_id, payload_fingerprint, state, apns_status,
                     apns_reason, retry_advice, retry_not_before,
                     post_send_authority, token_invalidation_outcome, created_at,
                     in_flight_at, completed_at
              FROM live_activity_direct_dispatch_attempts
              WHERE binding_id = ${bindingId}
                AND installation_id = ${installationId}
                AND state IN ('RESERVED', 'IN_FLIGHT')
              LIMIT 1
            `;
            return rows[0] == null ? undefined : attemptFromRow(rows[0]);
          },
          getLatestDispatchAttemptForOperation: async (bindingId, operationInput) => {
            const operationKind = normalizedLiveActivityDispatchOperation(operationInput);
            const rows = await transactionSql<AttemptRow[]>`
              SELECT dispatch_id, binding_id, installation_id, session_revision,
                     operation_kind, event_timestamp,
                     push_to_start_server_revision,
                     push_to_start_client_generation, update_token_server_revision,
                     update_token_client_generation, apns_environment,
                     apns_request_id, payload_fingerprint, state, apns_status,
                     apns_reason, retry_advice, retry_not_before,
                     post_send_authority, token_invalidation_outcome, created_at,
                     in_flight_at, completed_at
              FROM live_activity_direct_dispatch_attempts
              WHERE binding_id = ${bindingId}
                AND installation_id = ${installationId}
                AND operation_kind = ${operationKind}
              ORDER BY event_timestamp DESC
              LIMIT 1
            `;
            return rows[0] == null ? undefined : attemptFromRow(rows[0]);
          },
          insertDispatchAttempt: async (attemptInput) => {
            const attempt = createLiveActivityDirectDispatchAttempt(attemptInput);
            const isStart = attempt.operation === "START";
            const rows = await transactionSql<{ dispatch_id: string }[]>`
              INSERT INTO live_activity_direct_dispatch_attempts (
                dispatch_id, binding_id, installation_id, session_revision,
                operation_kind, event_timestamp, push_to_start_server_revision,
                push_to_start_client_generation, update_token_server_revision,
                update_token_client_generation, apns_environment, apns_request_id,
                payload_fingerprint, state, apns_status, apns_reason, retry_advice,
                retry_not_before, post_send_authority, token_invalidation_outcome,
                created_at, in_flight_at, completed_at
              ) VALUES (
                ${attempt.dispatchId}, ${attempt.bindingId}, ${attempt.installationId},
                ${attempt.sessionRevision}, ${attempt.operation}, ${attempt.eventTimestamp},
                ${isStart ? attempt.tokenGeneration.serverRevision : null},
                ${isStart ? attempt.tokenGeneration.clientGeneration : null},
                ${isStart ? null : attempt.tokenGeneration.serverRevision},
                ${isStart ? null : attempt.tokenGeneration.clientGeneration},
                ${attempt.environment}, ${attempt.apnsRequestId},
                ${attempt.payloadFingerprint}, ${attempt.state}, ${attempt.apnsStatus},
                ${attempt.apnsReason}, ${attempt.retryAdvice}, ${attempt.retryNotBefore},
                ${attempt.postSendAuthority}, ${attempt.tokenInvalidationOutcome},
                ${attempt.createdAt}, ${attempt.inFlightAt}, ${attempt.completedAt}
              )
              ON CONFLICT DO NOTHING
              RETURNING dispatch_id
            `;
            return rows.length === 1;
          },
          updateDispatchAttempt: async (expectedState, attemptInput) => {
            const attempt = createLiveActivityDirectDispatchAttempt(attemptInput);
            const rows = await transactionSql<{ dispatch_id: string }[]>`
              UPDATE live_activity_direct_dispatch_attempts
              SET payload_fingerprint = ${attempt.payloadFingerprint},
                  state = ${attempt.state},
                  apns_status = ${attempt.apnsStatus},
                  apns_reason = ${attempt.apnsReason},
                  retry_advice = ${attempt.retryAdvice},
                  retry_not_before = ${attempt.retryNotBefore},
                  post_send_authority = ${attempt.postSendAuthority},
                  token_invalidation_outcome = ${attempt.tokenInvalidationOutcome},
                  in_flight_at = ${attempt.inFlightAt},
                  completed_at = ${attempt.completedAt}
              WHERE dispatch_id = ${attempt.dispatchId}
                AND binding_id = ${attempt.bindingId}
                AND installation_id = ${attempt.installationId}
                AND state = ${expectedState}
              RETURNING dispatch_id
            `;
            return rows.length === 1;
          },
        };
        return await operation(transaction);
      })) as T;
    } catch {
      throw new LiveActivityDispatchPersistenceError();
    }
  }
}
