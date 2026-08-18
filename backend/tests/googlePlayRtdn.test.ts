import { describe, expect, it, vi } from "vitest";
import { AppError } from "../src/lib/errors.js";
import {
  ANDROID_PACKAGE_NAME,
  PREMIUM_PRODUCT_ID,
  createGooglePlayPurchaseVerifier,
  purchaseTokenFingerprint,
  type GooglePlayApiClient,
  type GooglePlayPurchaseVerifier,
} from "../src/services/googlePlayPurchaseVerifier.js";
import {
  createGooglePlayRtdnHandler,
  createGoogleRtdnAuthenticator,
  type RtdnAuthenticator,
} from "../src/billing/googlePlayRtdn.js";
import type { OAuth2Client } from "google-auth-library";
import { FakePurchaseStateStore } from "./billingTestHelpers.js";

const auth: RtdnAuthenticator = { authenticate: vi.fn(async () => {}) };
const config = { packageName: ANDROID_PACKAGE_NAME, serviceAccountEmail: "unused", privateKey: "unused" };

function pubsub(payload: object, messageId = "message-1") {
  return {
    message: {
      messageId,
      publishTime: "2026-08-18T10:00:01.000Z",
      data: Buffer.from(JSON.stringify(payload)).toString("base64"),
    },
  };
}

function oneTime(type: 1 | 2, eventTimeMillis = "1787047200000") {
  return {
    version: "1.0",
    packageName: ANDROID_PACKAGE_NAME,
    eventTimeMillis,
    oneTimeProductNotification: {
      version: "1.0", notificationType: type, purchaseToken: "rtdn-purchase-token", sku: PREMIUM_PRODUCT_ID,
    },
  };
}

function voided(eventTimeMillis = "1787047200000") {
  return {
    packageName: ANDROID_PACKAGE_NAME,
    eventTimeMillis,
    voidedPurchaseNotification: {
      purchaseToken: "rtdn-purchase-token", orderId: "order-voided", productType: 2, refundType: 1,
    },
  };
}

function pendingRefundReview(eventTimeMillis = "1787047200000") {
  return {
    packageName: ANDROID_PACKAGE_NAME,
    eventTimeMillis,
    pendingRefundReviewNotification: {
      version: "1.0",
      pendingRefundToken: "pending-refund-token",
      orderId: "order-under-review",
      refundReason: 7,
    },
  };
}

function purchased(refundableQuantity = 1) {
  return {
    purchaseStateContext: { purchaseState: "PURCHASED" },
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    productLineItem: [{ productId: PREMIUM_PRODUCT_ID, productOfferDetails: {
      quantity: 1, refundableQuantity, consumptionState: "CONSUMPTION_STATE_YET_TO_BE_CONSUMED",
    } }],
    orderId: "order-voided",
    purchaseCompletionTime: "2026-08-18T09:00:00.000Z",
  };
}

describe("Google Play RTDN handler", () => {
  it("authenticates, validates and forces Google resolution for a valid notification", async () => {
    const store = new FakePurchaseStateStore();
    const verifier: GooglePlayPurchaseVerifier = {
      verifyAndAcknowledge: vi.fn(async () => ({
        verified: true, state: "PURCHASED" as const, verifiedAt: "2026-08-18T10:00:00.000Z",
      })),
      reviewPendingRefund: vi.fn(async () => {}),
    };
    await createGooglePlayRtdnHandler(auth, store, verifier).handle("Bearer oidc", pubsub(oneTime(1)));
    expect(auth.authenticate).toHaveBeenCalledWith("Bearer oidc");
    expect(verifier.verifyAndAcknowledge).toHaveBeenCalledWith("rtdn-purchase-token", expect.objectContaining({
      forceGoogleRefresh: true, expectedLifecycle: "PURCHASED",
    }));
    expect(store.messages.get("message-1")).toBe("PROCESSED");
  });

  it("deduplicates the Pub/Sub message id", async () => {
    const store = new FakePurchaseStateStore();
    const verifier: GooglePlayPurchaseVerifier = {
      verifyAndAcknowledge: vi.fn(async () => ({
        verified: true, state: "PURCHASED" as const, verifiedAt: "2026-08-18T10:00:00.000Z",
      })),
      reviewPendingRefund: vi.fn(async () => {}),
    };
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    await handler.handle("Bearer oidc", pubsub(oneTime(1)));
    await handler.handle("Bearer oidc", pubsub(oneTime(1)));
    expect(verifier.verifyAndAcknowledge).toHaveBeenCalledTimes(1);
  });

  it("responds neutrally to a valid pending refund review without changing entitlement", async () => {
    const store = new FakePurchaseStateStore();
    const fingerprint = purchaseTokenFingerprint("rtdn-purchase-token");
    await store.withPurchaseLock(fingerprint, (session) => session.save({
      tokenFingerprint: fingerprint,
      productId: PREMIUM_PRODUCT_ID,
      orderId: "order-under-review",
      purchaseState: "PURCHASED",
      acknowledgementState: "ACKNOWLEDGED",
      entitlementActive: true,
      voided: false,
      lastVerifiedAt: new Date("2026-08-18T09:00:00.000Z"),
    }));
    const verifier: GooglePlayPurchaseVerifier = {
      verifyAndAcknowledge: vi.fn(),
      reviewPendingRefund: vi.fn(async () => {}),
    };
    await createGooglePlayRtdnHandler(auth, store, verifier)
      .handle("Bearer oidc", pubsub(pendingRefundReview(), "pending-review"));
    expect(verifier.reviewPendingRefund).toHaveBeenCalledWith("order-under-review", "pending-refund-token");
    expect(verifier.verifyAndAcknowledge).not.toHaveBeenCalled();
    expect(store.records.get(fingerprint)?.entitlementActive).toBe(true);
    expect(store.messages.get("pending-review")).toBe("PROCESSED");
  });

  it("deduplicates a pending refund review response", async () => {
    const store = new FakePurchaseStateStore();
    const verifier: GooglePlayPurchaseVerifier = {
      verifyAndAcknowledge: vi.fn(),
      reviewPendingRefund: vi.fn(async () => {}),
    };
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    await handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "pending-review"));
    await handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "pending-review"));
    expect(verifier.reviewPendingRefund).toHaveBeenCalledTimes(1);
  });

  it("rejects a malformed pending refund review before claiming it", async () => {
    const store = new FakePurchaseStateStore();
    const verifier = { verifyAndAcknowledge: vi.fn(), reviewPendingRefund: vi.fn() } as GooglePlayPurchaseVerifier;
    const malformed = pendingRefundReview();
    delete (malformed.pendingRefundReviewNotification as Partial<typeof malformed.pendingRefundReviewNotification>)
      .pendingRefundToken;
    await expect(createGooglePlayRtdnHandler(auth, store, verifier)
      .handle("Bearer oidc", pubsub(malformed, "malformed-review")))
      .rejects.toMatchObject({ code: "VALIDATION_ERROR" });
    expect(store.messages.size).toBe(0);
  });

  it("rejects malformed notifications before processing", async () => {
    const store = new FakePurchaseStateStore();
    const verifier = { verifyAndAcknowledge: vi.fn() } as unknown as GooglePlayPurchaseVerifier;
    await expect(createGooglePlayRtdnHandler(auth, store, verifier).handle("Bearer oidc", { message: { data: "bad" } }))
      .rejects.toMatchObject({ code: "VALIDATION_ERROR" });
    expect(store.messages.size).toBe(0);
  });

  it("rejects invalid authentication before reading notification state", async () => {
    const store = new FakePurchaseStateStore();
    const denied: RtdnAuthenticator = { authenticate: vi.fn(async () => {
      throw new AppError("AUTHENTICATION_ERROR", "Invalid notification authentication");
    }) };
    const verifier = { verifyAndAcknowledge: vi.fn() } as unknown as GooglePlayPurchaseVerifier;
    await expect(createGooglePlayRtdnHandler(denied, store, verifier).handle("Bearer bad", pubsub(oneTime(1))))
      .rejects.toMatchObject({ code: "AUTHENTICATION_ERROR" });
    expect(store.messages.size).toBe(0);
  });

  it("records an unknown voided purchase as inactive after Google resolution", async () => {
    const store = new FakePurchaseStateStore();
    const api: GooglePlayApiClient = {
      getProductPurchase: vi.fn(async () => undefined), acknowledgeProduct: vi.fn(), reviewRefund: vi.fn(),
    };
    const verifier = createGooglePlayPurchaseVerifier(config, store, api);
    await createGooglePlayRtdnHandler(auth, store, verifier).handle("Bearer oidc", pubsub(voided()));
    expect(store.records.get(purchaseTokenFingerprint("rtdn-purchase-token"))).toMatchObject({
      entitlementActive: false, voided: true, orderId: "order-voided",
    });
  });

  it("updates a refunded or revoked purchase to inactive", async () => {
    const store = new FakePurchaseStateStore();
    const api: GooglePlayApiClient = {
      getProductPurchase: vi.fn(async () => purchased(0)), acknowledgeProduct: vi.fn(), reviewRefund: vi.fn(),
    };
    const verifier = createGooglePlayPurchaseVerifier(config, store, api);
    await createGooglePlayRtdnHandler(auth, store, verifier).handle("Bearer oidc", pubsub(voided()));
    expect(store.records.get(purchaseTokenFingerprint("rtdn-purchase-token"))).toMatchObject({
      entitlementActive: false, voided: true, refundableQuantity: 0,
    });
  });

  it("keeps entitlement during review and removes it after a subsequent final refund", async () => {
    const store = new FakePurchaseStateStore();
    const api: GooglePlayApiClient = {
      getProductPurchase: vi.fn().mockResolvedValueOnce(purchased(1)).mockResolvedValueOnce(purchased(0)),
      acknowledgeProduct: vi.fn(),
      reviewRefund: vi.fn(async () => {}),
    };
    const verifier = createGooglePlayPurchaseVerifier(config, store, api);
    await verifier.verifyAndAcknowledge("rtdn-purchase-token");
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    await handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "pending-review"));
    expect(store.records.get(purchaseTokenFingerprint("rtdn-purchase-token"))?.entitlementActive).toBe(true);
    await handler.handle("Bearer oidc", pubsub(voided("1787047260000"), "final-refund"));
    expect(store.records.get(purchaseTokenFingerprint("rtdn-purchase-token"))).toMatchObject({
      entitlementActive: false, voided: true,
    });
  });

  it("does not let an older notification overwrite newer durable state", async () => {
    const store = new FakePurchaseStateStore();
    const api: GooglePlayApiClient = {
      getProductPurchase: vi.fn().mockResolvedValueOnce(purchased(0)).mockResolvedValueOnce(purchased(1)),
      acknowledgeProduct: vi.fn(),
      reviewRefund: vi.fn(),
    };
    const verifier = createGooglePlayPurchaseVerifier(config, store, api);
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    await handler.handle("Bearer oidc", pubsub(voided("1787047200000"), "newer"));
    await handler.handle("Bearer oidc", pubsub(oneTime(1, "1787043600000"), "older"));
    expect(store.records.get(purchaseTokenFingerprint("rtdn-purchase-token"))?.entitlementActive).toBe(false);
  });

  it("marks a failed notification retryable when Google is unavailable", async () => {
    const store = new FakePurchaseStateStore();
    const verifier: GooglePlayPurchaseVerifier = {
      verifyAndAcknowledge: vi.fn(async () => {
        throw new AppError("UPSTREAM_ERROR", "Google unavailable");
      }),
      reviewPendingRefund: vi.fn(async () => {}),
    };
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    await expect(handler.handle("Bearer oidc", pubsub(oneTime(1)))).rejects.toMatchObject({ code: "UPSTREAM_ERROR" });
    expect(store.messages.get("message-1")).toBe("FAILED");
    await expect(handler.handle("Bearer oidc", pubsub(oneTime(1)))).rejects.toMatchObject({ code: "UPSTREAM_ERROR" });
    expect(verifier.verifyAndAcknowledge).toHaveBeenCalledTimes(2);
  });

  it("retries a pending refund review after a sanitized Google API failure", async () => {
    const store = new FakePurchaseStateStore();
    const sensitiveToken = "pending-refund-token";
    const reviewPendingRefund = vi.fn()
      .mockRejectedValueOnce(new AppError("UPSTREAM_ERROR", "Google Play refund review response failed"))
      .mockResolvedValueOnce(undefined);
    const verifier: GooglePlayPurchaseVerifier = { verifyAndAcknowledge: vi.fn(), reviewPendingRefund };
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    const firstError = await handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "retry-review"))
      .catch((caught: unknown) => caught);
    expect(firstError).toMatchObject({ code: "UPSTREAM_ERROR" });
    expect(JSON.stringify(firstError)).not.toContain(sensitiveToken);
    expect(store.messages.get("retry-review")).toBe("FAILED");
    await expect(handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "retry-review")))
      .resolves.toBeUndefined();
    expect(reviewPendingRefund).toHaveBeenCalledTimes(2);
    expect(store.messages.get("retry-review")).toBe("PROCESSED");
  });

  it("retries safely when durable completion initially fails", async () => {
    class FlakyCompletionStore extends FakePurchaseStateStore {
      private attempts = 0;
      override async completeRtdnMessage(messageId: string): Promise<void> {
        this.attempts += 1;
        if (this.attempts === 1) throw new Error("database unavailable");
        await super.completeRtdnMessage(messageId);
      }
    }
    const store = new FlakyCompletionStore();
    const verifier: GooglePlayPurchaseVerifier = {
      verifyAndAcknowledge: vi.fn(), reviewPendingRefund: vi.fn(async () => {}),
    };
    const handler = createGooglePlayRtdnHandler(auth, store, verifier);
    await expect(handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "db-retry"))).rejects.toThrow();
    expect(store.messages.get("db-retry")).toBe("FAILED");
    await expect(handler.handle("Bearer oidc", pubsub(pendingRefundReview(), "db-retry")))
      .resolves.toBeUndefined();
    expect(store.messages.get("db-retry")).toBe("PROCESSED");
  });
});

describe("Google Pub/Sub OIDC authentication", () => {
  it("requires the configured audience and verified service-account identity", async () => {
    const verifyIdToken = vi.fn(async () => ({ getPayload: () => ({
      email: "pubsub@example.invalid", email_verified: true,
    }) }));
    const authenticator = createGoogleRtdnAuthenticator(
      { audience: "https://api.example.invalid/api/v1/billing/rtdn", serviceAccountEmail: "pubsub@example.invalid" },
      { verifyIdToken } as unknown as OAuth2Client,
    );
    await expect(authenticator.authenticate("Bearer signed-token")).resolves.toBeUndefined();
    expect(verifyIdToken).toHaveBeenCalledWith({
      idToken: "signed-token", audience: "https://api.example.invalid/api/v1/billing/rtdn",
    });
  });

  it("rejects invalid signatures and unexpected sources with a sanitized error", async () => {
    const invalidSignature = createGoogleRtdnAuthenticator(
      { audience: "https://api.example.invalid/rtdn", serviceAccountEmail: "pubsub@example.invalid" },
      { verifyIdToken: vi.fn(async () => { throw new Error("bad signature"); }) } as unknown as OAuth2Client,
    );
    await expect(invalidSignature.authenticate("Bearer bad-token"))
      .rejects.toMatchObject({ code: "AUTHENTICATION_ERROR", message: "Invalid notification authentication" });

    const wrongSource = createGoogleRtdnAuthenticator(
      { audience: "https://api.example.invalid/rtdn", serviceAccountEmail: "pubsub@example.invalid" },
      { verifyIdToken: vi.fn(async () => ({ getPayload: () => ({
        email: "other@example.invalid", email_verified: true,
      }) })) } as unknown as OAuth2Client,
    );
    await expect(wrongSource.authenticate("Bearer signed-token"))
      .rejects.toMatchObject({ code: "AUTHENTICATION_ERROR" });
  });
});
