import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { runLiveActivityDeliveryMigration } from "../scripts/migrateLiveActivityDelivery.js";

describe("live activity delivery migration", () => {
  it("persists protected token generations and exact-revision delivery bindings", async () => {
    const migration = await readFile(
      new URL("../migrations/003_live_activity_delivery.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_push_to_start_tokens",
    );
    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_delivery_bindings",
    );
    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_update_tokens",
    );
    expect(migration).toContain("REFERENCES live_commute_installations");
    expect(migration).toContain("REFERENCES live_commute_sessions");
    expect(migration).toContain(
      "UNIQUE (installation_id, session_id, session_revision)",
    );
    expect(migration).toContain(
      "live_activity_delivery_bindings_activity_idx",
    );
    expect(migration).toContain(
      "ON live_activity_delivery_bindings (installation_id, apple_activity_id)",
    );
    expect(migration).not.toMatch(
      /FOREIGN KEY\s*\(installation_id, session_id, session_revision\)/i,
    );
    expect(migration).not.toMatch(/broadcast_channel_id/i);
    expect(migration).not.toMatch(/google_play|purchase_token|bearer_credential/i);
  });

  it("constrains protected material, token lifecycle, and direct-token ownership", async () => {
    const migration = await readFile(
      new URL("../migrations/003_live_activity_delivery.sql", import.meta.url),
      "utf8",
    );

    expect(migration.match(/octet_length\(token_nonce\) = 12/g)).toHaveLength(2);
    expect(migration.match(/octet_length\(token_auth_tag\) = 16/g)).toHaveLength(2);
    expect(
      migration.match(/octet_length\(token_ciphertext\) BETWEEN 1 AND 4096/g),
    ).toHaveLength(2);
    expect(migration.match(/token_digest ~ '\^\[0-9a-f\]\{64\}\$'/g)).toHaveLength(2);
    expect(migration.match(/'CURRENT', 'REPLACED', 'INVALIDATED'/g)).toHaveLength(2);
    expect(migration.match(/server_revision INTEGER NOT NULL/g)).toHaveLength(2);
    expect(migration.match(/client_generation INTEGER NOT NULL/g)).toHaveLength(2);
    expect(migration.match(/server_revision BETWEEN 1 AND 2147483647/g)).toHaveLength(2);
    expect(migration.match(/client_generation BETWEEN 1 AND 2147483647/g)).toHaveLength(2);
    expect(migration).not.toMatch(/(?:server_revision|client_generation) BIGINT/);
    expect(migration.match(/apns_environment TEXT NOT NULL CHECK/g)).toHaveLength(2);
    expect(migration).toContain("WHERE lifecycle = 'CURRENT'");
    expect(migration).toContain("CHECK (binding_strategy = 'DIRECT_TOKEN')");
    expect(migration).toContain(
      "FOREIGN KEY (binding_id, installation_id, binding_strategy)",
    );
    expect(migration).toContain(
      "binding_id, installation_id, delivery_strategy",
    );
  });

  it("keeps binding terminal timestamps coherent without claiming delivery", async () => {
    const migration = await readFile(
      new URL("../migrations/003_live_activity_delivery.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain(
      "lifecycle IN ('PENDING_START', 'ENDED', 'INVALIDATED')",
    );
    expect(migration).toContain(
      "lifecycle = 'PENDING_START' AND ended_at IS NULL AND invalidated_at IS NULL",
    );
    expect(migration).toContain(
      "lifecycle = 'ENDED' AND ended_at IS NOT NULL AND invalidated_at IS NULL",
    );
    expect(migration).toContain(
      "lifecycle = 'INVALIDATED' AND ended_at IS NULL AND invalidated_at IS NOT NULL",
    );
    expect(migration).not.toMatch(/START_REQUESTED|ACTIVE|END_REQUESTED/);
  });

  it("has an explicit import-safe runner scoped only to migration 003", async () => {
    const runner = await readFile(
      new URL("../scripts/migrateLiveActivityDelivery.ts", import.meta.url),
      "utf8",
    );

    expect(runner).toContain("LIVE_COMMUTE_MIGRATION_DATABASE_URL");
    expect(runner).toContain("003_live_activity_delivery.sql");
    expect(runner).not.toMatch(/process\.env\.DATABASE_URL\b/);
    expect(runner).not.toMatch(/001_google_play_billing|002_live_commute_sessions/);
    expect(runner).toContain("await sql.begin");
    expect(runner).toContain("await sql.end()");
    await expect(runLiveActivityDeliveryMigration("")).rejects.toThrow(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL is required",
    );
  });
});
