import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";

describe("billing migration", () => {
  it("persists token fingerprints and lifecycle state without raw purchase tokens", async () => {
    const sql = await readFile(new URL("../migrations/001_google_play_billing.sql", import.meta.url), "utf8");
    expect(sql).toContain("token_fingerprint CHAR(64) PRIMARY KEY");
    expect(sql).toContain("google_play_rtdn_messages");
    expect(sql).toContain("entitlement_active BOOLEAN");
    expect(sql).not.toMatch(/\bpurchase_token\b/i);
  });
});
