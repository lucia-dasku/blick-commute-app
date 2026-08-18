import { generateKeyPairSync } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { AppError } from "../src/lib/errors.js";
import {
  ANDROID_PACKAGE_NAME,
  PREMIUM_PRODUCT_ID,
  createGooglePlayApiClient,
  createGooglePlayPurchaseVerifier,
  purchaseTokenFingerprint,
  type GooglePlayApiClient,
} from "../src/services/googlePlayPurchaseVerifier.js";
import { FakePurchaseStateStore } from "./billingTestHelpers.js";

const config = {
  packageName: ANDROID_PACKAGE_NAME,
  serviceAccountEmail: "play-verifier@example.invalid",
  privateKey: "unused with fake API",
};
const checkedAt = new Date("2026-08-18T10:00:00.000Z");

function purchase(
  state: "PURCHASED" | "PENDING" | "CANCELLED" = "PURCHASED",
  overrides: { productId?: string; acknowledged?: boolean; refundableQuantity?: number; quantity?: number } = {},
) {
  return {
    purchaseStateContext: { purchaseState: state },
    acknowledgementState: overrides.acknowledged === false
      ? "ACKNOWLEDGEMENT_STATE_PENDING"
      : "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    productLineItem: [{
      productId: overrides.productId ?? PREMIUM_PRODUCT_ID,
      productOfferDetails: {
        quantity: overrides.quantity ?? 1,
        refundableQuantity: overrides.refundableQuantity ?? 1,
        consumptionState: "CONSUMPTION_STATE_YET_TO_BE_CONSUMED",
      },
    }],
    orderId: "order-1",
    ...(state === "PURCHASED" ? { purchaseCompletionTime: "2026-08-18T09:00:00.000Z" } : {}),
  };
}

function setup(response: unknown | undefined = purchase()) {
  const store = new FakePurchaseStateStore();
  const api: GooglePlayApiClient = {
    getProductPurchase: vi.fn(async () => response),
    acknowledgeProduct: vi.fn(async () => {}),
  };
  return { store, api, verifier: createGooglePlayPurchaseVerifier(config, store, api, () => checkedAt) };
}

describe("Google Play purchase verifier", () => {
  it("grants only a valid purchased, owned, expected product", async () => {
    const { verifier, store } = setup();
    expect(await verifier.verifyAndAcknowledge("valid-token-0001")).toEqual({
      verified: true, state: "PURCHASED", verifiedAt: checkedAt.toISOString(),
    });
    const record = store.records.get(purchaseTokenFingerprint("valid-token-0001"));
    expect(record).toMatchObject({ productId: PREMIUM_PRODUCT_ID, quantity: 1, refundableQuantity: 1, entitlementActive: true });
    expect(JSON.stringify(record)).not.toContain("valid-token-0001");
  });

  it.each([
    ["PENDING", purchase("PENDING"), "PENDING"],
    ["cancelled", purchase("CANCELLED"), "CANCELLED"],
    ["refunded or revoked", purchase("PURCHASED", { refundableQuantity: 0 }), "CANCELLED"],
    ["wrong product", purchase("PURCHASED", { productId: "other" }), "UNKNOWN"],
    ["invalid quantity", purchase("PURCHASED", { quantity: 2 }), "PURCHASED"],
  ])("does not grant %s", async (_name, response, expectedState) => {
    const { verifier } = setup(response);
    const result = await verifier.verifyAndAcknowledge("valid-token-0002");
    expect(result).toMatchObject({ verified: false, state: expectedState });
  });

  it("rejects an invalid token and malformed Google response safely", async () => {
    const invalid = setup();
    vi.mocked(invalid.api.getProductPurchase).mockResolvedValue(undefined);
    expect(await invalid.verifier.verifyAndAcknowledge("invalid-token-01")).toMatchObject({ verified: false });
    await expect(setup({ broken: true }).verifier.verifyAndAcknowledge("malformed-token1"))
      .rejects.toMatchObject({ code: "UPSTREAM_ERROR" });
  });

  it("acknowledges an unacknowledged purchase once and persists only after success", async () => {
    const { verifier, api, store } = setup(purchase("PURCHASED", { acknowledged: false }));
    await expect(verifier.verifyAndAcknowledge("unacked-token-01")).resolves.toMatchObject({ verified: true });
    expect(api.acknowledgeProduct).toHaveBeenCalledTimes(1);
    expect(store.records.get(purchaseTokenFingerprint("unacked-token-01"))?.acknowledgementState).toBe("ACKNOWLEDGED");
  });

  it("does not acknowledge an already acknowledged purchase", async () => {
    const { verifier, api } = setup();
    await verifier.verifyAndAcknowledge("acked-token-0001");
    expect(api.acknowledgeProduct).not.toHaveBeenCalled();
  });

  it("does not grant when acknowledgement fails and permits a retry", async () => {
    const { verifier, api, store } = setup(purchase("PURCHASED", { acknowledged: false }));
    vi.mocked(api.acknowledgeProduct).mockRejectedValueOnce(new AppError("UPSTREAM_ERROR", "Acknowledgement failed"));
    await expect(verifier.verifyAndAcknowledge("retry-token-0001")).rejects.toMatchObject({ code: "UPSTREAM_ERROR" });
    expect(store.records.get(purchaseTokenFingerprint("retry-token-0001"))?.entitlementActive).toBe(false);
    await expect(verifier.verifyAndAcknowledge("retry-token-0001")).resolves.toMatchObject({ verified: true });
  });

  it("supports repeated verification and legitimate restore without token-reuse rejection", async () => {
    const { verifier, api } = setup();
    await verifier.verifyAndAcknowledge("restore-token-001");
    await expect(verifier.verifyAndAcknowledge("restore-token-001")).resolves.toMatchObject({ verified: true });
    expect(api.getProductPurchase).toHaveBeenCalledTimes(1);
  });

  it("serializes duplicate concurrent verification", async () => {
    const { verifier, api } = setup();
    await Promise.all([
      verifier.verifyAndAcknowledge("concurrent-token1"),
      verifier.verifyAndAcknowledge("concurrent-token1"),
    ]);
    expect(api.getProductPurchase).toHaveBeenCalledTimes(1);
  });

  it("propagates Google API failures without manufacturing entitlement", async () => {
    const { verifier, api } = setup();
    vi.mocked(api.getProductPurchase).mockRejectedValue(new AppError("UPSTREAM_ERROR", "Google unavailable"));
    await expect(verifier.verifyAndAcknowledge("failed-api-token1")).rejects.toMatchObject({ code: "UPSTREAM_ERROR" });
  });

  it("rejects a misconfigured package before any request", () => {
    expect(() => createGooglePlayApiClient({ ...realApiConfig(), packageName: "wrong.package" }))
      .toThrow(`GOOGLE_PLAY_PACKAGE_NAME must be ${ANDROID_PACKAGE_NAME}`);
  });
});

function realApiConfig() {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  return {
    packageName: ANDROID_PACKAGE_NAME,
    serviceAccountEmail: "play-verifier@example.invalid",
    privateKey: privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
  };
}

describe("Google Play HTTP client failures", () => {
  it.each([400, 500])("sanitizes a Google HTTP %i response", async (status) => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: "access", expires_in: 3600 }), { status: 200 }))
      .mockResolvedValueOnce(new Response("upstream body", { status }));
    await expect(createGooglePlayApiClient(realApiConfig(), fetchMock).getProductPurchase("sensitive-token"))
      .rejects.toMatchObject({ code: "UPSTREAM_ERROR", message: "Google Play purchase verification failed" });
  });

  it("sanitizes a Google network failure", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: "access" }), { status: 200 }))
      .mockRejectedValueOnce(new Error("network with sensitive-token"));
    await expect(createGooglePlayApiClient(realApiConfig(), fetchMock).getProductPurchase("sensitive-token"))
      .rejects.toMatchObject({ code: "UPSTREAM_ERROR", message: "Google Play purchase verification failed" });
  });
});
