import { OAuth2Client, type LoginTicket } from "google-auth-library";
import { z } from "zod";
import type { GooglePlayRtdnConfig } from "../config/env.js";
import { AppError, isAppError } from "../lib/errors.js";
import {
  ANDROID_PACKAGE_NAME,
  PREMIUM_PRODUCT_ID,
  type GooglePlayPurchaseVerifier,
} from "../services/googlePlayPurchaseVerifier.js";
import type { PurchaseStateStore } from "./purchaseStateStore.js";

const PubSubEnvelopeSchema = z.object({
  message: z.object({
    data: z.string().min(1),
    messageId: z.string().min(1),
    publishTime: z.string().datetime({ offset: true }).optional(),
  }),
});

const NotificationSchema = z.object({
  version: z.string().optional(),
  packageName: z.literal(ANDROID_PACKAGE_NAME),
  eventTimeMillis: z.string().regex(/^\d+$/),
  oneTimeProductNotification: z.object({
    version: z.string().optional(),
    notificationType: z.union([z.literal(1), z.literal(2)]),
    purchaseToken: z.string().min(1).max(4096),
    sku: z.literal(PREMIUM_PRODUCT_ID),
  }).optional(),
  voidedPurchaseNotification: z.object({
    purchaseToken: z.string().min(1).max(4096),
    orderId: z.string().min(1),
    productType: z.literal(2),
    refundType: z.number().int(),
  }).optional(),
  testNotification: z.object({ version: z.string().optional() }).optional(),
}).superRefine((value, context) => {
  const count = [value.oneTimeProductNotification, value.voidedPurchaseNotification, value.testNotification]
    .filter(Boolean).length;
  if (count !== 1) context.addIssue({ code: "custom", message: "Exactly one notification payload is required" });
});

export interface RtdnAuthenticator { authenticate(authorization: string | undefined): Promise<void> }

export function createGoogleRtdnAuthenticator(
  config: GooglePlayRtdnConfig,
  client: OAuth2Client = new OAuth2Client(),
): RtdnAuthenticator {
  return {
    async authenticate(authorization) {
      const match = /^Bearer (\S+)$/i.exec(authorization ?? "");
      if (!match) throw new AppError("AUTHENTICATION_ERROR", "Invalid notification authentication");
      try {
        const ticket = await (client.verifyIdToken({
          idToken: match[1]!,
          audience: config.audience,
        }) as Promise<LoginTicket>);
        const payload = ticket.getPayload();
        if (payload?.email !== config.serviceAccountEmail || payload.email_verified !== true) {
          throw new Error("Unexpected identity");
        }
      } catch {
        throw new AppError("AUTHENTICATION_ERROR", "Invalid notification authentication");
      }
    },
  };
}

export interface GooglePlayRtdnHandler {
  handle(authorization: string | undefined, body: unknown): Promise<void>;
}

export function createGooglePlayRtdnHandler(
  authenticator: RtdnAuthenticator,
  store: PurchaseStateStore,
  verifier: GooglePlayPurchaseVerifier,
): GooglePlayRtdnHandler {
  return {
    async handle(authorization, body) {
      await authenticator.authenticate(authorization);
      const envelope = PubSubEnvelopeSchema.safeParse(body);
      if (!envelope.success) throw new AppError("VALIDATION_ERROR", "Invalid Pub/Sub notification");

      let decoded: unknown;
      try {
        decoded = JSON.parse(Buffer.from(envelope.data.message.data, "base64").toString("utf8"));
      } catch {
        throw new AppError("VALIDATION_ERROR", "Invalid Pub/Sub notification data");
      }
      const notification = NotificationSchema.safeParse(decoded);
      if (!notification.success) throw new AppError("VALIDATION_ERROR", "Invalid Google Play notification");
      const eventTime = new Date(Number(notification.data.eventTimeMillis));
      if (!Number.isFinite(eventTime.getTime())) throw new AppError("VALIDATION_ERROR", "Invalid notification time");

      const claimed = await store.claimRtdnMessage({
        messageId: envelope.data.message.messageId,
        publishTime: envelope.data.message.publishTime ? new Date(envelope.data.message.publishTime) : undefined,
      });
      if (!claimed) return;

      try {
        const oneTime = notification.data.oneTimeProductNotification;
        const voided = notification.data.voidedPurchaseNotification;
        if (oneTime) {
          await verifier.verifyAndAcknowledge(oneTime.purchaseToken, {
            forceGoogleRefresh: true,
            sourceEventTime: eventTime,
            expectedLifecycle: oneTime.notificationType === 1 ? "PURCHASED" : "INACTIVE",
            notificationProductId: oneTime.sku,
          });
        } else if (voided) {
          await verifier.verifyAndAcknowledge(voided.purchaseToken, {
            forceGoogleRefresh: true,
            sourceEventTime: eventTime,
            expectedLifecycle: "INACTIVE",
            notificationOrderId: voided.orderId,
          });
        }
        await store.completeRtdnMessage(envelope.data.message.messageId);
      } catch (error) {
        await store.failRtdnMessage(
          envelope.data.message.messageId,
          isAppError(error) ? error.code : "INTERNAL_ERROR",
        );
        throw error;
      }
    },
  };
}
