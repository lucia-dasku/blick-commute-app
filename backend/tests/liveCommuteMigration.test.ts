import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";

describe("live commute migration", () => {
  it("keeps ownership and concrete-session state separate from billing", async () => {
    const migration = await readFile(
      new URL("../migrations/002_live_commute_sessions.sql", import.meta.url),
      "utf8",
    );

    expect(migration).toContain("CREATE TABLE IF NOT EXISTS live_commute_installations");
    expect(migration).toContain("CREATE TABLE IF NOT EXISTS live_commute_sessions");
    expect(migration).toContain("credential_digest CHAR(64)");
    expect(migration).toContain("PRIMARY KEY (installation_id, session_id)");
    expect(migration).toContain("CHECK (starts_at < ends_at)");
    expect(migration).toContain("lifecycle IN ('REGISTERED', 'CANCELLED')");
    expect(migration).toContain("revision INTEGER NOT NULL CHECK (revision > 0)");
    expect(migration).toContain("REFERENCES live_commute_installations");
    expect(migration).not.toMatch(/google_play|purchase_token|bearer_credential|apns/i);
  });

  it("has an explicit runner scoped only to the live commute migration", async () => {
    const runner = await readFile(
      new URL("../scripts/migrateLiveCommute.ts", import.meta.url),
      "utf8",
    );

    expect(runner).toContain("LIVE_COMMUTE_MIGRATION_DATABASE_URL");
    expect(runner).toContain("002_live_commute_sessions.sql");
    expect(runner).not.toMatch(/process\.env\.DATABASE_URL\b/);
    expect(runner).not.toContain("001_google_play_billing.sql");
    expect(runner).toContain("await sql.end()");
  });
});
