import { describe, expect, it } from "vitest";
import { createBillingRoute } from "../src/routes/billing.js";
import { PREMIUM_PRODUCT_ID, type GooglePlayPurchaseVerifier } from "../src/services/googlePlayPurchaseVerifier.js";
import type { SuccessEnvelope } from "./testHelpers.js";

describe("premium billing verification route", () => {
  it("accepts only the configured lifetime product and returns no-store", async () => {
    const verifier: GooglePlayPurchaseVerifier = {
      async verifyAndAcknowledge() { return { verified: true, state: "PURCHASED" }; },
    };
    const response = await createBillingRoute(verifier).request("/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ productId: PREMIUM_PRODUCT_ID, purchaseToken: "a-valid-purchase-token" }),
    });
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    const body = (await response.json()) as SuccessEnvelope<{ verified: boolean }>;
    expect(body.data.verified).toBe(true);
  });

  it("rejects a different product id before verification", async () => {
    let calls = 0;
    const verifier: GooglePlayPurchaseVerifier = {
      async verifyAndAcknowledge() { calls++; return { verified: true, state: "PURCHASED" }; },
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
});
