import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { runLiveActivityDispatchMigration } from "../scripts/migrateLiveActivityDispatch.js";

describe("live activity dispatch migration", () => {
  it("adds only durable direct-dispatch ordering and result state", async () => {
    const migration = await readFile(
      new URL("../migrations/004_live_activity_dispatch.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_direct_dispatch_state",
    );
    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_direct_dispatch_attempts",
    );
    expect(migration).toContain("last_reserved_event_timestamp BIGINT NOT NULL");
    expect(migration).toContain("terminal_intent_event_timestamp BIGINT");
    expect(migration).toContain(
      "operation_kind IN ('START', 'DIRECT_UPDATE', 'DIRECT_END')",
    );
    expect(migration).toContain("payload_fingerprint CHAR(64)");
    expect(migration).toContain("post_send_authority");
    expect(migration).toContain("token_invalidation_outcome");
    expect(migration).not.toMatch(
      /token_ciphertext|token_nonce|token_auth_tag|provider_jwt|private_key|request_body|raw_headers/i,
    );
  });

  it("enforces direct ownership, exact generation correlation, and active slots", async () => {
    const migration = await readFile(
      new URL("../migrations/004_live_activity_dispatch.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain("CHECK (binding_strategy = 'DIRECT_TOKEN')");
    expect(migration).toContain(
      "live_activity_delivery_bindings_dispatch_identity_idx",
    );
    expect(migration).toContain(
      "live_activity_direct_dispatch_attempts_push_generation_fk",
    );
    expect(migration).toContain(
      "live_activity_direct_dispatch_attempts_update_generation_fk",
    );
    expect(migration).toContain("live_activity_direct_dispatch_active_idx");
    expect(migration).toContain("WHERE state IN ('RESERVED', 'IN_FLIGHT')");
    expect(migration).toContain("live_activity_direct_dispatch_start_blocker_idx");
    expect(migration).toContain("UNIQUE (binding_id, event_timestamp)");
    expect(migration).toContain("9007199254740991");
  });

  it("uses an explicit import-safe runner scoped only to migration 004", async () => {
    const runner = await readFile(
      new URL("../scripts/migrateLiveActivityDispatch.ts", import.meta.url),
      "utf8",
    );

    expect(runner).toContain("LIVE_COMMUTE_MIGRATION_DATABASE_URL");
    expect(runner).toContain("004_live_activity_dispatch.sql");
    expect(runner).not.toMatch(/process\.env\.DATABASE_URL\b/);
    expect(runner).not.toMatch(
      /001_google_play_billing|002_live_commute_sessions|003_live_activity_delivery/,
    );
    expect(runner).toContain("await sql.begin");
    expect(runner).toContain("await sql.end()");
    await expect(runLiveActivityDispatchMigration("")).rejects.toThrow(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL is required",
    );
  });
});
