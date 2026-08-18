import { describe, expect, it, vi } from "vitest";
import { createBillingRoute } from "../src/routes/billing.js";
import { PREMIUM_PRODUCT_ID, type GooglePlayPurchaseVerifier } from "../src/services/googlePlayPurchaseVerifier.js";
import type { SuccessEnvelope } from "./testHelpers.js";
import { onError } from "../src/middleware/errorHandler.js";

describe("premium billing verification route", () => {
  it("accepts only the configured lifetime product and returns no-store", async () => {
    const verifier: GooglePlayPurchaseVerifier = {
      async verifyAndAcknowledge() { return { verified: true, state: "PURCHASED", verifiedAt: "2026-08-18T00:00:00.000Z" }; },
      async reviewPendingRefund() {},
    };
    const response = await createBillingRoute(verifier).request("/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ productId: PREMIUM_PRODUCT_ID, purchaseToken: "a-valid-purchase-token" }),
    });
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    const body = (await response.json()) as SuccessEnvelope<{ verified: boolean; verifiedAt: string }>;
    expect(body.data.verified).toBe(true);
    expect(body.data.verifiedAt).toBe("2026-08-18T00:00:00.000Z");
  });

  it("rejects a different product id before verification", async () => {
    let calls = 0;
    const verifier: GooglePlayPurchaseVerifier = {
      async verifyAndAcknowledge() { calls++; return { verified: true, state: "PURCHASED", verifiedAt: "2026-08-18T00:00:00.000Z" }; },
      async reviewPendingRefund() {},
    };
    const app = createBillingRoute(verifier);
    app.onError((error, c) => c.json({ error: error.message }, 400));
    const response = await app.request("/verify", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ productId: "fake", purchaseToken: "a-valid-purchase-token" }),
    });
    expect(response.status).toBe(400);
    expect(calls).toBe(0);
  });

  it("rate limits only billing using a token fingerprint, never the raw token", async () => {
    let calls = 0;
    let limiterKey = "";
    const verifier: GooglePlayPurchaseVerifier = {
      async verifyAndAcknowledge() { calls++; return { verified: true, state: "PURCHASED", verifiedAt: "2026-08-18T00:00:00.000Z" }; },
      async reviewPendingRefund() {},
    };
    const app = createBillingRoute(verifier, { allow: async (fingerprint) => {
      limiterKey = fingerprint;
      return false;
    } });
    app.onError(onError);
    const rawToken = "never-use-this-raw-token";
    const response = await app.request("/verify", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ productId: PREMIUM_PRODUCT_ID, purchaseToken: rawToken }),
    });
    expect(response.status).toBe(429);
    expect(limiterKey).toMatch(/^[a-f0-9]{64}$/);
    expect(limiterKey).not.toContain(rawToken);
    expect(calls).toBe(0);
  });

  it("exposes the authenticated Pub/Sub handler as a no-store 204 endpoint", async () => {
    const verifier: GooglePlayPurchaseVerifier = {
      async verifyAndAcknowledge() { return { verified: false, state: "UNKNOWN", verifiedAt: "2026-08-18T00:00:00.000Z" }; },
      async reviewPendingRefund() {},
    };
    const handle = vi.fn(async () => {});
    const response = await createBillingRoute(verifier, undefined, { handle }).request("/rtdn", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer signed" },
      body: JSON.stringify({ message: { data: "encoded" } }),
    });
    expect(response.status).toBe(204);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(handle).toHaveBeenCalledWith("Bearer signed", { message: { data: "encoded" } });
  });
});
