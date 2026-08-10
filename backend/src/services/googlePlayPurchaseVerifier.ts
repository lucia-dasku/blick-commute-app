import { createSign } from "node:crypto";
import { AppError } from "../lib/errors.js";
import type { GooglePlayConfig } from "../config/env.js";

export const PREMIUM_PRODUCT_ID = "blick_premium_lifetime";
const OAUTH_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";

export interface PurchaseVerificationResult {
  verified: boolean;
  state: "PURCHASED" | "PENDING" | "CANCELLED" | "UNKNOWN";
}

export interface GooglePlayPurchaseVerifier {
  verifyAndAcknowledge(purchaseToken: string): Promise<PurchaseVerificationResult>;
}

function base64Url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

async function accessToken(config: GooglePlayConfig): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(
    JSON.stringify({
      iss: config.serviceAccountEmail,
      scope: OAUTH_SCOPE,
      aud: OAUTH_TOKEN_URL,
      iat: now,
      exp: now + 3600,
    }),
  );
  const unsigned = `${header}.${claims}`;
  const signer = createSign("RSA-SHA256");
  signer.update(unsigned);
  signer.end();
  const assertion = `${unsigned}.${signer.sign(config.privateKey).toString("base64url")}`;
  const response = await fetch(OAUTH_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!response.ok) throw new AppError("UPSTREAM_ERROR", "Google Play authorization failed");
  const body = (await response.json()) as { access_token?: unknown };
  if (typeof body.access_token !== "string") {
    throw new AppError("UPSTREAM_ERROR", "Google Play authorization returned an invalid response");
  }
  return body.access_token;
}

type ProductPurchaseV2 = {
  purchaseStateContext?: { purchaseState?: string };
  acknowledgementState?: string;
  productLineItem?: Array<{
    productId?: string;
    productOfferDetails?: { refundableQuantity?: number };
  }>;
};

export function createGooglePlayPurchaseVerifier(config?: GooglePlayConfig): GooglePlayPurchaseVerifier {
  return {
    async verifyAndAcknowledge(purchaseToken: string): Promise<PurchaseVerificationResult> {
      if (!config) throw new AppError("UPSTREAM_ERROR", "Purchase verification is temporarily unavailable");
      const token = await accessToken(config);
      const encodedPackage = encodeURIComponent(config.packageName);
      const encodedPurchaseToken = encodeURIComponent(purchaseToken);
      const getUrl =
        `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodedPackage}` +
        `/purchases/productsv2/tokens/${encodedPurchaseToken}`;
      const response = await fetch(getUrl, { headers: { Authorization: `Bearer ${token}` } });
      if (response.status === 404) return { verified: false, state: "CANCELLED" };
      if (!response.ok) throw new AppError("UPSTREAM_ERROR", "Google Play purchase verification failed");
      const purchase = (await response.json()) as ProductPurchaseV2;
      const rawState = purchase.purchaseStateContext?.purchaseState;
      const state = rawState === "PURCHASED" || rawState === "PENDING" || rawState === "CANCELLED" ? rawState : "UNKNOWN";
      const item = purchase.productLineItem?.find((line) => line.productId === PREMIUM_PRODUCT_ID);
      const refundable = item?.productOfferDetails?.refundableQuantity;
      const verified = state === "PURCHASED" && item !== undefined && refundable !== 0;

      if (verified && purchase.acknowledgementState !== "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED") {
        const acknowledgeUrl =
          `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodedPackage}` +
          `/purchases/products/${PREMIUM_PRODUCT_ID}/tokens/${encodedPurchaseToken}:acknowledge`;
        const acknowledge = await fetch(acknowledgeUrl, {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: "{}",
        });
        if (!acknowledge.ok) throw new AppError("UPSTREAM_ERROR", "Google Play purchase acknowledgement failed");
      }
      return { verified, state };
    },
  };
}
