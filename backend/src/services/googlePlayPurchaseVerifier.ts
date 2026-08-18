import { createHash, createSign } from "node:crypto";
import { z } from "zod";
import { AppError } from "../lib/errors.js";
import type { GooglePlayConfig } from "../config/env.js";
import type {
  AcknowledgementState,
  PurchaseState,
  PurchaseStateRecord,
  PurchaseStateStore,
} from "../billing/purchaseStateStore.js";

export const ANDROID_PACKAGE_NAME = "se.blick.app";
export const PREMIUM_PRODUCT_ID = "blick_premium_lifetime";
const OAUTH_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const ACTIVE_REVALIDATION_MS = 6 * 60 * 60 * 1_000;
const PENDING_REVALIDATION_MS = 60 * 1_000;
const INACTIVE_REVALIDATION_MS = 15 * 60 * 1_000;

const ProductPurchaseV2Schema = z.object({
  purchaseStateContext: z.object({
    purchaseState: z.enum(["PURCHASED", "PENDING", "CANCELLED"]),
  }),
  acknowledgementState: z.enum([
    "ACKNOWLEDGEMENT_STATE_PENDING",
    "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
  ]),
  productLineItem: z.array(z.object({
    productId: z.string().min(1),
    productOfferDetails: z.object({
      quantity: z.number().int().positive(),
      refundableQuantity: z.number().int().nonnegative(),
      consumptionState: z.enum([
        "CONSUMPTION_STATE_YET_TO_BE_CONSUMED",
        "CONSUMPTION_STATE_CONSUMED",
      ]),
    }),
  })).min(1),
  orderId: z.string().min(1).optional(),
  purchaseCompletionTime: z.string().datetime({ offset: true }).optional(),
}).superRefine((purchase, context) => {
  if (purchase.purchaseStateContext.purchaseState === "PURCHASED" && !purchase.purchaseCompletionTime) {
    context.addIssue({ code: "custom", message: "Purchased response is missing purchaseCompletionTime" });
  }
});

export interface PurchaseVerificationResult {
  verified: boolean;
  state: PurchaseState;
  verifiedAt: string;
}

export interface PurchaseVerificationOptions {
  forceGoogleRefresh?: boolean;
  sourceEventTime?: Date;
  expectedLifecycle?: "PURCHASED" | "INACTIVE";
  notificationOrderId?: string;
  notificationProductId?: string;
}

export interface GooglePlayPurchaseVerifier {
  verifyAndAcknowledge(
    purchaseToken: string,
    options?: PurchaseVerificationOptions,
  ): Promise<PurchaseVerificationResult>;
}

export interface GooglePlayApiClient {
  getProductPurchase(purchaseToken: string): Promise<unknown | undefined>;
  acknowledgeProduct(purchaseToken: string): Promise<void>;
}

function base64Url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

export function purchaseTokenFingerprint(purchaseToken: string): string {
  return createHash("sha256").update(purchaseToken, "utf8").digest("hex");
}

export function createGooglePlayApiClient(
  config: GooglePlayConfig,
  fetchImpl: typeof fetch = fetch,
): GooglePlayApiClient {
  if (config.packageName !== ANDROID_PACKAGE_NAME) {
    throw new Error(`GOOGLE_PLAY_PACKAGE_NAME must be ${ANDROID_PACKAGE_NAME}`);
  }

  let cachedAccessToken: { value: string; expiresAt: number } | undefined;

  async function accessToken(): Promise<string> {
    const nowMs = Date.now();
    if (cachedAccessToken && cachedAccessToken.expiresAt - 60_000 > nowMs) return cachedAccessToken.value;

    const now = Math.floor(nowMs / 1_000);
    const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = base64Url(JSON.stringify({
      iss: config.serviceAccountEmail,
      scope: OAUTH_SCOPE,
      aud: OAUTH_TOKEN_URL,
      iat: now,
      exp: now + 3600,
    }));
    const unsigned = `${header}.${claims}`;
    const signer = createSign("RSA-SHA256");
    signer.update(unsigned);
    signer.end();
    const assertion = `${unsigned}.${signer.sign(config.privateKey).toString("base64url")}`;

    let response: Response;
    try {
      response = await fetchImpl(OAUTH_TOKEN_URL, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
      });
    } catch {
      throw new AppError("UPSTREAM_ERROR", "Google Play authorization failed");
    }
    if (!response.ok) throw new AppError("UPSTREAM_ERROR", "Google Play authorization failed");

    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new AppError("UPSTREAM_ERROR", "Google Play authorization returned an invalid response");
    }
    const parsed = z.object({
      access_token: z.string().min(1),
      expires_in: z.number().int().positive().optional(),
    }).safeParse(body);
    if (!parsed.success) {
      throw new AppError("UPSTREAM_ERROR", "Google Play authorization returned an invalid response");
    }
    cachedAccessToken = {
      value: parsed.data.access_token,
      expiresAt: nowMs + (parsed.data.expires_in ?? 3600) * 1_000,
    };
    return cachedAccessToken.value;
  }

  async function publisherRequest(purchaseToken: string, acknowledge: boolean): Promise<Response> {
    const token = await accessToken();
    const encodedPackage = encodeURIComponent(ANDROID_PACKAGE_NAME);
    const encodedPurchaseToken = encodeURIComponent(purchaseToken);
    const url = acknowledge
      ? `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodedPackage}` +
        `/purchases/products/${PREMIUM_PRODUCT_ID}/tokens/${encodedPurchaseToken}:acknowledge`
      : `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodedPackage}` +
        `/purchases/productsv2/tokens/${encodedPurchaseToken}`;
    try {
      return await fetchImpl(url, {
        method: acknowledge ? "POST" : "GET",
        headers: {
          Authorization: `Bearer ${token}`,
          ...(acknowledge ? { "Content-Type": "application/json" } : {}),
        },
        ...(acknowledge ? { body: "{}" } : {}),
      });
    } catch {
      throw new AppError(
        "UPSTREAM_ERROR",
        acknowledge ? "Google Play purchase acknowledgement failed" : "Google Play purchase verification failed",
      );
    }
  }

  return {
    async getProductPurchase(purchaseToken: string): Promise<unknown | undefined> {
      const response = await publisherRequest(purchaseToken, false);
      if (response.status === 404) return undefined;
      if (!response.ok) throw new AppError("UPSTREAM_ERROR", "Google Play purchase verification failed");
      try {
        return await response.json();
      } catch {
        throw new AppError("UPSTREAM_ERROR", "Google Play purchase verification returned an invalid response");
      }
    },
    async acknowledgeProduct(purchaseToken: string): Promise<void> {
      const response = await publisherRequest(purchaseToken, true);
      if (!response.ok) throw new AppError("UPSTREAM_ERROR", "Google Play purchase acknowledgement failed");
    },
  };
}

function acknowledgementState(raw: string): AcknowledgementState {
  return raw === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" ? "ACKNOWLEDGED" : "PENDING";
}

function resultFromRecord(record: PurchaseStateRecord): PurchaseVerificationResult {
  return {
    verified: record.entitlementActive,
    state: record.voided ? "CANCELLED" : record.purchaseState,
    verifiedAt: record.lastVerifiedAt.toISOString(),
  };
}

function recordIsFresh(record: PurchaseStateRecord, now: Date): boolean {
  const maximumAge = record.entitlementActive
    ? ACTIVE_REVALIDATION_MS
    : record.purchaseState === "PENDING"
      ? PENDING_REVALIDATION_MS
      : INACTIVE_REVALIDATION_MS;
  return !(record.purchaseState === "PURCHASED" && record.acknowledgementState === "PENDING") &&
    now.getTime() - record.lastVerifiedAt.getTime() < maximumAge;
}

export function createGooglePlayPurchaseVerifier(
  config?: GooglePlayConfig,
  store?: PurchaseStateStore,
  apiClient?: GooglePlayApiClient,
  now: () => Date = () => new Date(),
): GooglePlayPurchaseVerifier {
  const googlePlay = config && (apiClient ?? createGooglePlayApiClient(config));

  return {
    async verifyAndAcknowledge(
      purchaseToken: string,
      options: PurchaseVerificationOptions = {},
    ): Promise<PurchaseVerificationResult> {
      if (!config || !store || !googlePlay) {
        throw new AppError("UPSTREAM_ERROR", "Purchase verification is temporarily unavailable");
      }
      const fingerprint = purchaseTokenFingerprint(purchaseToken);
      await store.pruneExpiredRecords();

      return store.withPurchaseLock(fingerprint, async (session) => {
        const existing = await session.get();
        const checkedAt = now();
        if (!options.forceGoogleRefresh && existing && recordIsFresh(existing, checkedAt)) {
          return resultFromRecord(existing);
        }

        const rawPurchase = await googlePlay.getProductPurchase(purchaseToken);
        if (rawPurchase === undefined) {
          if (options.expectedLifecycle === "PURCHASED") {
            throw new AppError("UPSTREAM_ERROR", "Google Play purchase state is not available yet");
          }
          if (existing || options.sourceEventTime) {
            const cancelled: PurchaseStateRecord = {
              tokenFingerprint: fingerprint,
              productId: existing?.productId ?? options.notificationProductId,
              orderId: existing?.orderId ?? options.notificationOrderId,
              purchaseState: "CANCELLED",
              acknowledgementState: existing?.acknowledgementState ?? "UNKNOWN",
              entitlementActive: false,
              voided: true,
              lastVerifiedAt: checkedAt,
              lastEventTime: options.sourceEventTime,
            };
            await session.save(cancelled);
            return resultFromRecord(cancelled);
          }
          return { verified: false, state: "CANCELLED", verifiedAt: checkedAt.toISOString() };
        }

        const parsed = ProductPurchaseV2Schema.safeParse(rawPurchase);
        if (!parsed.success) {
          throw new AppError("UPSTREAM_ERROR", "Google Play purchase verification returned an invalid response");
        }
        const purchase = parsed.data;
        const state = purchase.purchaseStateContext.purchaseState;
        const matchingItems = purchase.productLineItem.filter((line) => line.productId === PREMIUM_PRODUCT_ID);
        const item = matchingItems.length === 1 ? matchingItems[0] : undefined;
        const details = item?.productOfferDetails;
        const ownsExpectedProduct = item !== undefined &&
          details?.quantity === 1 &&
          details.refundableQuantity === 1 &&
          details.consumptionState === "CONSUMPTION_STATE_YET_TO_BE_CONSUMED";
        const currentlyEntitled = state === "PURCHASED" && ownsExpectedProduct;
        const isVoided = state === "CANCELLED" || (item !== undefined && details?.refundableQuantity === 0);

        if (options.expectedLifecycle === "INACTIVE" && currentlyEntitled) {
          throw new AppError("UPSTREAM_ERROR", "Google Play lifecycle state is not available yet");
        }

        const baseRecord: PurchaseStateRecord = {
          tokenFingerprint: fingerprint,
          productId: item?.productId,
          orderId: purchase.orderId ?? options.notificationOrderId,
          purchaseState: state,
          acknowledgementState: acknowledgementState(purchase.acknowledgementState),
          purchaseCompletionTime: purchase.purchaseCompletionTime
            ? new Date(purchase.purchaseCompletionTime)
            : undefined,
          quantity: details?.quantity,
          refundableQuantity: details?.refundableQuantity,
          consumptionState: details?.consumptionState,
          entitlementActive: currentlyEntitled &&
            purchase.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
          voided: isVoided,
          lastVerifiedAt: checkedAt,
          lastEventTime: options.sourceEventTime,
        };

        if (!item) {
          if (existing) await session.save({ ...baseRecord, entitlementActive: false });
          return { verified: false, state: "UNKNOWN", verifiedAt: checkedAt.toISOString() };
        }

        await session.save(baseRecord);
        if (currentlyEntitled && baseRecord.acknowledgementState === "PENDING") {
          await googlePlay.acknowledgeProduct(purchaseToken);
          const acknowledged = {
            ...baseRecord,
            acknowledgementState: "ACKNOWLEDGED" as const,
            entitlementActive: true,
          };
          await session.save(acknowledged);
          return resultFromRecord(acknowledged);
        }
        return resultFromRecord(baseRecord);
      });
    },
  };
}
