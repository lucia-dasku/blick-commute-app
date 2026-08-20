import { Hono } from "hono";
import { z } from "zod";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import { PREMIUM_PRODUCT_ID, type GooglePlayPurchaseVerifier } from "../services/googlePlayPurchaseVerifier.js";
import { purchaseTokenFingerprint } from "../services/googlePlayPurchaseVerifier.js";
import type { BillingRateLimiter } from "../billing/billingRateLimiter.js";
import type { GooglePlayRtdnHandler } from "../billing/googlePlayRtdn.js";

const RequestSchema = z.object({
  productId: z.literal(PREMIUM_PRODUCT_ID),
  purchaseToken: z.string().min(16).max(4096),
});

export function createBillingRoute(
  verifier: GooglePlayPurchaseVerifier,
  rateLimiter?: BillingRateLimiter,
  rtdnHandler?: GooglePlayRtdnHandler,
) {
  const route = new Hono();
  route.post("/verify", async (c) => {
    let raw: unknown;
    try {
      raw = await c.req.json();
    } catch {
      throw new AppError("VALIDATION_ERROR", "Request body must be valid JSON");
    }
    const parsed = RequestSchema.safeParse(raw);
    if (!parsed.success) throw new AppError("VALIDATION_ERROR", "Invalid purchase verification request");
    if (rateLimiter && !await rateLimiter.allow(purchaseTokenFingerprint(parsed.data.purchaseToken))) {
      throw new AppError("RATE_LIMITED", "Too many purchase verification requests");
    }
    const result = await verifier.verifyAndAcknowledge(parsed.data.purchaseToken);
    c.header("Cache-Control", "no-store");
    return c.json(successEnvelope({ productId: PREMIUM_PRODUCT_ID, ...result }));
  });
  route.post("/rtdn", async (c) => {
    if (!rtdnHandler) throw new AppError("UPSTREAM_ERROR", "Purchase notifications are unavailable");
    let raw: unknown;
    try { raw = await c.req.json(); } catch {
      throw new AppError("VALIDATION_ERROR", "Request body must be valid JSON");
    }
    await rtdnHandler.handle(c.req.header("Authorization"), raw);
    c.header("Cache-Control", "no-store");
    return c.body(null, 204);
  });
  return route;
}
