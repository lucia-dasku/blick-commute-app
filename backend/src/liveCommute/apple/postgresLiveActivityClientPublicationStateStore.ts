import postgres from "postgres";
import {
  createStoredLiveCommuteInstallation,
  type StoredLiveCommuteInstallation,
} from "../sessionStore.js";
import {
  createStoredLiveActivityClientPublicationState,
  type LiveActivityClientPublicationStateStore,
  type LiveActivityClientPublicationStateTransaction,
  type StoredLiveActivityClientPublicationState,
} from "./clientPublicationState.js";
import { normalizedAppleDeliveryIdentifier } from "./deliveryModel.js";

export type LiveActivityClientStatePostgresSql = ReturnType<typeof postgres>;

interface InstallationRow {
  installation_id: string;
  credential_digest: string;
  revoked_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

interface ClientStateRow {
  installation_id: string;
  capability: StoredLiveActivityClientPublicationState["capability"];
  frequent_pushes: StoredLiveActivityClientPublicationState["frequentPushes"];
  locale: StoredLiveActivityClientPublicationState["locale"];
  created_at: Date;
  updated_at: Date;
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

function stateFromRow(
  row: ClientStateRow,
): StoredLiveActivityClientPublicationState {
  return createStoredLiveActivityClientPublicationState({
    installationId: row.installation_id,
    capability: row.capability,
    frequentPushes: row.frequent_pushes,
    locale: row.locale,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

export class PostgresLiveActivityClientPublicationStateStore
  implements LiveActivityClientPublicationStateStore
{
  constructor(private readonly sql: LiveActivityClientStatePostgresSql) {}

  async withInstallationTransaction<T>(
    installationId: string,
    operation: (
      transaction: LiveActivityClientPublicationStateTransaction,
    ) => Promise<T>,
  ): Promise<T> {
    const normalizedInstallationId = normalizedAppleDeliveryIdentifier(
      installationId,
      "installationId",
    );
    return (await this.sql.begin(async (transactionSql) => {
      await transactionSql`
        SELECT installation_id
        FROM live_commute_installations
        WHERE installation_id = ${normalizedInstallationId}
        FOR UPDATE
      `;
      const transaction: LiveActivityClientPublicationStateTransaction = {
        getInstallation: async () => {
          const rows = await transactionSql<InstallationRow[]>`
            SELECT installation_id, credential_digest, revoked_at, created_at, updated_at
            FROM live_commute_installations
            WHERE installation_id = ${normalizedInstallationId}
          `;
          return rows[0] == null ? undefined : installationFromRow(rows[0]);
        },
        getState: async () => {
          const rows = await transactionSql<ClientStateRow[]>`
            SELECT installation_id, capability, frequent_pushes, locale,
                   created_at, updated_at
            FROM live_activity_client_publication_state
            WHERE installation_id = ${normalizedInstallationId}
          `;
          return rows[0] == null ? undefined : stateFromRow(rows[0]);
        },
        saveState: async (input) => {
          const state = createStoredLiveActivityClientPublicationState(input);
          if (state.installationId !== normalizedInstallationId) {
            throw new Error("client state ownership mismatch");
          }
          await transactionSql`
            INSERT INTO live_activity_client_publication_state (
              installation_id, capability, frequent_pushes, locale,
              created_at, updated_at
            ) VALUES (
              ${state.installationId}, ${state.capability}, ${state.frequentPushes},
              ${state.locale}, ${state.createdAt}, ${state.updatedAt}
            )
            ON CONFLICT (installation_id) DO UPDATE SET
              capability = EXCLUDED.capability,
              frequent_pushes = EXCLUDED.frequent_pushes,
              locale = EXCLUDED.locale,
              updated_at = EXCLUDED.updated_at
          `;
        },
      };
      return await operation(transaction);
    })) as T;
  }

  async getState(
    installationId: string,
  ): Promise<StoredLiveActivityClientPublicationState | undefined> {
    const normalizedInstallationId = normalizedAppleDeliveryIdentifier(
      installationId,
      "installationId",
    );
    const rows = await this.sql<ClientStateRow[]>`
      SELECT installation_id, capability, frequent_pushes, locale,
             created_at, updated_at
      FROM live_activity_client_publication_state
      WHERE installation_id = ${normalizedInstallationId}
    `;
    return rows[0] == null ? undefined : stateFromRow(rows[0]);
  }
}
