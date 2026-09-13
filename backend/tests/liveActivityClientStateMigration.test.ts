import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { runLiveActivityClientPublicationStateMigration } from "../scripts/migrateLiveActivityClientPublicationState.js";

describe("live activity client publication state migration", () => {
  it("adds only installation-owned, constrained nonsecret client state", async () => {
    const migration = await readFile(
      new URL(
        "../migrations/007_live_activity_client_publication_state.sql",
        import.meta.url,
      ),
      "utf8",
    );
    expect(migration).toContain(
      "CREATE TABLE IF NOT EXISTS live_activity_client_publication_state",
    );
    expect(migration).toContain("live_activity_client_state_installation_fk");
    expect(migration).toContain(
      "'DIRECT_LEGACY',\n        'DIRECT_IOS18',\n        'BROADCAST_CAPABLE',\n        'UNKNOWN'",
    );
    expect(migration).toContain("'ENABLED', 'DISABLED', 'UNKNOWN'");
    expect(migration).toContain("locale IN ('en', 'sv')");
    expect(migration).not.toMatch(
      /token|credential|private_key|provider_jwt|request_body|user_agent/i,
    );
  });

  it("uses an import-safe runner scoped only to migration 007", async () => {
    const runner = await readFile(
      new URL(
        "../scripts/migrateLiveActivityClientPublicationState.ts",
        import.meta.url,
      ),
      "utf8",
    );
    expect(runner).toContain("LIVE_COMMUTE_MIGRATION_DATABASE_URL");
    expect(runner).toContain("007_live_activity_client_publication_state.sql");
    expect(runner).not.toMatch(/process\.env\.DATABASE_URL\b/);
    expect(runner).not.toMatch(/001_google_play_billing|00[2-6]_/);
    await expect(
      runLiveActivityClientPublicationStateMigration(""),
    ).rejects.toThrow("LIVE_COMMUTE_MIGRATION_DATABASE_URL is required");
  });
});
