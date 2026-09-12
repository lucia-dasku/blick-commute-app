import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { runLiveActivityPublicationPolicyMigration } from "../scripts/migrateLiveActivityPublicationPolicy.js";

describe("live activity publication policy migration", () => {
  it("adds only safe nullable publication metadata to durable dispatch attempts", async () => {
    const migration = await readFile(
      new URL("../migrations/005_live_activity_publication_policy.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain("ALTER TABLE live_activity_direct_dispatch_attempts");
    expect(migration).toContain("visible_content_fingerprint CHAR(64)");
    expect(migration).toContain("publication_source_fetched_at TIMESTAMPTZ");
    expect(migration).toContain("publication_stale_at TIMESTAMPTZ");
    expect(migration).toContain(
      "live_activity_direct_dispatch_accepted_publication_idx",
    );
    expect(migration).toContain("WHERE state = 'ACCEPTED'");
    expect(migration).not.toMatch(
      /content_state|token_ciphertext|token_nonce|token_auth_tag|provider_jwt|private_key|request_body|raw_response/i,
    );
  });

  it("keeps legacy rows nullable while constraining complete publication metadata", async () => {
    const migration = await readFile(
      new URL("../migrations/005_live_activity_publication_policy.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain("visible_content_fingerprint IS NULL");
    expect(migration).toContain("publication_source_fetched_at IS NULL");
    expect(migration).toContain("visible_content_fingerprint IS NOT NULL");
    expect(migration).toContain("publication_source_fetched_at IS NOT NULL");
    expect(migration).toContain("publication_stale_at >= publication_source_fetched_at");
    expect(migration).toContain("visible_content_fingerprint ~ '^[0-9a-f]{64}$'");
  });

  it("uses an explicit import-safe runner scoped only to migration 005", async () => {
    const runner = await readFile(
      new URL("../scripts/migrateLiveActivityPublicationPolicy.ts", import.meta.url),
      "utf8",
    );

    expect(runner).toContain("LIVE_COMMUTE_MIGRATION_DATABASE_URL");
    expect(runner).toContain("005_live_activity_publication_policy.sql");
    expect(runner).not.toMatch(/process\.env\.DATABASE_URL\b/);
    expect(runner).not.toMatch(
      /001_google_play_billing|002_live_commute_sessions|003_live_activity_delivery|004_live_activity_dispatch/,
    );
    expect(runner).toContain("await sql.begin");
    expect(runner).toContain("await sql.end()");
    await expect(runLiveActivityPublicationPolicyMigration("")).rejects.toThrow(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL is required",
    );
  });
});
