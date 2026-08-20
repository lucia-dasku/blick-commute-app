import postgres from "postgres";
import type {
  PurchaseStateRecord,
  PurchaseStateSession,
  PurchaseStateStore,
  RtdnMessageClaim,
} from "./purchaseStateStore.js";

const RTDN_PROCESSING_LEASE_MINUTES = 10;
const RTDN_RETENTION_DAYS = 90;
const INACTIVE_PURCHASE_RETENTION_MONTHS = 24;

interface PurchaseRow {
  token_fingerprint: string;
  product_id: string | null;
  order_id: string | null;
  purchase_state: PurchaseStateRecord["purchaseState"];
  acknowledgement_state: PurchaseStateRecord["acknowledgementState"];
  purchase_completion_time: Date | null;
  quantity: number | null;
  refundable_quantity: number | null;
  consumption_state: string | null;
  entitlement_active: boolean;
  voided: boolean;
  last_verified_at: Date;
  last_event_time: Date | null;
}

function toRecord(row: PurchaseRow): PurchaseStateRecord {
  return {
    tokenFingerprint: row.token_fingerprint,
    productId: row.product_id ?? undefined,
    orderId: row.order_id ?? undefined,
    purchaseState: row.purchase_state,
    acknowledgementState: row.acknowledgement_state,
    purchaseCompletionTime: row.purchase_completion_time ?? undefined,
    quantity: row.quantity ?? undefined,
    refundableQuantity: row.refundable_quantity ?? undefined,
    consumptionState: row.consumption_state ?? undefined,
    entitlementActive: row.entitlement_active,
    voided: row.voided,
    lastVerifiedAt: row.last_verified_at,
    lastEventTime: row.last_event_time ?? undefined,
  };
}

export class PostgresPurchaseStateStore implements PurchaseStateStore {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      idle_timeout: 20,
      connect_timeout: 10,
      prepare: false,
    });
  }

  async withPurchaseLock<T>(
    tokenFingerprint: string,
    operation: (session: PurchaseStateSession) => Promise<T>,
  ): Promise<T> {
    return await this.sql.begin(async (transaction) => {
      await transaction`SELECT pg_advisory_xact_lock(hashtextextended(${tokenFingerprint}, 0))`;
      const session: PurchaseStateSession = {
        get: async () => {
          const rows = await transaction<PurchaseRow[]>`
            SELECT token_fingerprint, product_id, order_id, purchase_state,
                   acknowledgement_state, purchase_completion_time, quantity,
                   refundable_quantity, consumption_state, entitlement_active,
                   voided, last_verified_at, last_event_time
            FROM google_play_purchases
            WHERE token_fingerprint = ${tokenFingerprint}
          `;
          return rows[0] ? toRecord(rows[0]) : undefined;
        },
        save: async (record) => {
          await transaction`
            INSERT INTO google_play_purchases (
              token_fingerprint, product_id, order_id, purchase_state,
              acknowledgement_state, purchase_completion_time, quantity,
              refundable_quantity, consumption_state, entitlement_active,
              voided, last_verified_at, last_event_time
            ) VALUES (
              ${record.tokenFingerprint}, ${record.productId ?? null}, ${record.orderId ?? null},
              ${record.purchaseState}, ${record.acknowledgementState},
              ${record.purchaseCompletionTime ?? null}, ${record.quantity ?? null},
              ${record.refundableQuantity ?? null}, ${record.consumptionState ?? null},
              ${record.entitlementActive}, ${record.voided}, ${record.lastVerifiedAt},
              ${record.lastEventTime ?? null}
            )
            ON CONFLICT (token_fingerprint) DO UPDATE SET
              product_id = EXCLUDED.product_id,
              order_id = COALESCE(EXCLUDED.order_id, google_play_purchases.order_id),
              purchase_state = EXCLUDED.purchase_state,
              acknowledgement_state = EXCLUDED.acknowledgement_state,
              purchase_completion_time = EXCLUDED.purchase_completion_time,
              quantity = EXCLUDED.quantity,
              refundable_quantity = EXCLUDED.refundable_quantity,
              consumption_state = EXCLUDED.consumption_state,
              entitlement_active = EXCLUDED.entitlement_active,
              voided = EXCLUDED.voided,
              last_verified_at = EXCLUDED.last_verified_at,
              last_event_time = COALESCE(EXCLUDED.last_event_time, google_play_purchases.last_event_time),
              updated_at = NOW()
            WHERE EXCLUDED.last_event_time IS NULL
               OR google_play_purchases.last_event_time IS NULL
               OR EXCLUDED.last_event_time >= google_play_purchases.last_event_time
          `;
        },
      };
      return operation(session);
    }) as T;
  }

  async claimRtdnMessage(claim: RtdnMessageClaim): Promise<boolean> {
    const rows = await this.sql<{ message_id: string }[]>`
      INSERT INTO google_play_rtdn_messages (
        message_id, publish_time, status, processing_started_at, attempt_count
      ) VALUES (${claim.messageId}, ${claim.publishTime ?? null}, 'PROCESSING', NOW(), 1)
      ON CONFLICT (message_id) DO UPDATE SET
        status = 'PROCESSING',
        processing_started_at = NOW(),
        attempt_count = google_play_rtdn_messages.attempt_count + 1,
        last_failure_code = NULL
      WHERE google_play_rtdn_messages.status = 'FAILED'
         OR (
           google_play_rtdn_messages.status = 'PROCESSING'
           AND google_play_rtdn_messages.processing_started_at
             < NOW() - (${RTDN_PROCESSING_LEASE_MINUTES} * INTERVAL '1 minute')
         )
      RETURNING message_id
    `;
    return rows.length === 1;
  }

  async completeRtdnMessage(messageId: string): Promise<void> {
    await this.sql`
      UPDATE google_play_rtdn_messages
      SET status = 'PROCESSED', processed_at = NOW(), last_failure_code = NULL
      WHERE message_id = ${messageId}
    `;
  }

  async failRtdnMessage(messageId: string, failureCode: string): Promise<void> {
    await this.sql`
      UPDATE google_play_rtdn_messages
      SET status = 'FAILED', last_failure_code = ${failureCode}
      WHERE message_id = ${messageId}
    `;
  }

  async pruneExpiredRecords(): Promise<void> {
    await this.sql`
      DELETE FROM google_play_rtdn_messages
      WHERE status = 'PROCESSED'
        AND processed_at < NOW() - (${RTDN_RETENTION_DAYS} * INTERVAL '1 day')
    `;
    await this.sql`
      DELETE FROM google_play_purchases
      WHERE entitlement_active = FALSE
        AND updated_at < NOW() - (${INACTIVE_PURCHASE_RETENTION_MONTHS} * INTERVAL '1 month')
    `;
  }
}
