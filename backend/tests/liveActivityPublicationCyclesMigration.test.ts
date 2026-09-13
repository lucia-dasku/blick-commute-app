import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { runLiveActivityPublicationCyclesMigration } from "../scripts/migrateLiveActivityPublicationCycles.js";

describe("live activity publication cycles migration", () => {
  it("adds global history-preserving cycle control and safe aggregate state", async () => {
    const migration = await readFile(
      new URL(
        "../migrations/006_live_activity_publication_cycles.sql",
        import.meta.url,
      ),
      "utf8",
    );

    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_publication_cycle_control",
    );
    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_publication_cycles",
    );
    expect(migration).toContain("last_fence_generation BIGINT");
    expect(migration).toContain("slot_start_epoch_seconds BIGINT");
    expect(migration).toContain("slot_cadence_seconds INTEGER");
    expect(migration).toContain("fence_generation BIGINT NOT NULL UNIQUE");
    expect(migration).toContain("live_activity_pub_cycles_active_scope_idx");
    expect(migration).toContain("WHERE state IN ('CLAIMED', 'RUNNING')");
    expect(migration).toContain("state = 'ABANDONED'");
    expect(migration).toContain("failure_code = 'LEASE_EXPIRED'");
    expect(migration).toContain("dispatch_result_not_recorded_count BIGINT");
    expect(migration).not.toMatch(
      /content_state|token_ciphertext|token_nonce|token_auth_tag|provider_jwt|private_key|request_body|raw_response|raw_sl_response/i,
    );
  });

  it("constrains slot alignment, state shape, summaries, and safe integer bounds", async () => {
    const migration = await readFile(
      new URL(
        "../migrations/006_live_activity_publication_cycles.sql",
        import.meta.url,
      ),
      "utf8",
    );

    expect(migration).toContain("9007199254740991");
    expect(migration).toContain(
      "MOD(slot_start_epoch_seconds, slot_cadence_seconds) = 0",
    );
    expect(migration).toContain("lease_expires_at > claimed_at");
    expect(migration).toContain("NUM_NONNULLS(");
    expect(migration).toContain("IN (0, 14)");
    expect(migration).toContain(
      "send_decision_count + no_push_decision_count + deferral_decision_count",
    );
    expect(migration).toContain(
      "network_attempted_count <= dispatch_requested_count",
    );
    expect(migration).toContain("state <> 'NO_WORK'");
  });

  it("keeps declared PostgreSQL identifiers within the 63-byte limit", async () => {
    const migration = await readFile(
      new URL(
        "../migrations/006_live_activity_publication_cycles.sql",
        import.meta.url,
      ),
      "utf8",
    );
    const identifiers = [
      ...migration.matchAll(
        /\b(?:CONSTRAINT|INDEX IF NOT EXISTS)\s+([a-z][a-z0-9_]*)/g,
      ),
    ].map((match) => match[1] as string);

    expect(identifiers.length).toBeGreaterThan(0);
    for (const identifier of identifiers) {
      expect(Buffer.byteLength(identifier, "utf8"), identifier).toBeLessThanOrEqual(
        63,
      );
    }
  });

  it("uses an explicit import-safe runner scoped only to migration 006", async () => {
    const runner = await readFile(
      new URL("../scripts/migrateLiveActivityPublicationCycles.ts", import.meta.url),
      "utf8",
    );

    expect(runner).toContain("LIVE_COMMUTE_MIGRATION_DATABASE_URL");
    expect(runner).toContain("006_live_activity_publication_cycles.sql");
    expect(runner).not.toMatch(/process\.env\.DATABASE_URL\b/);
    expect(runner).not.toMatch(
      /001_google_play_billing|002_live_commute_sessions|003_live_activity_delivery|004_live_activity_dispatch|005_live_activity_publication_policy/,
    );
    expect(runner).toContain("await sql.begin");
    expect(runner).toContain("await sql.end()");
    await expect(runLiveActivityPublicationCyclesMigration("")).rejects.toThrow(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL is required",
    );
  });
});
