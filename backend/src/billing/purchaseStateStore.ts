export type PurchaseState = "PURCHASED" | "PENDING" | "CANCELLED" | "UNKNOWN";
export type AcknowledgementState = "PENDING" | "ACKNOWLEDGED" | "UNKNOWN";

export interface PurchaseStateRecord {
  tokenFingerprint: string;
  productId?: string;
  orderId?: string;
  purchaseState: PurchaseState;
  acknowledgementState: AcknowledgementState;
  purchaseCompletionTime?: Date;
  quantity?: number;
  refundableQuantity?: number;
  consumptionState?: string;
  entitlementActive: boolean;
  voided: boolean;
  lastVerifiedAt: Date;
  lastEventTime?: Date;
}

export interface PurchaseStateSession {
  get(): Promise<PurchaseStateRecord | undefined>;
  save(record: PurchaseStateRecord): Promise<void>;
}

export interface RtdnMessageClaim {
  messageId: string;
  publishTime?: Date;
}

/**
 * Durable billing storage. Production uses PostgreSQL; tests provide deterministic fakes.
 * Raw Google Play purchase tokens must never cross this boundary.
 */
export interface PurchaseStateStore {
  withPurchaseLock<T>(
    tokenFingerprint: string,
    operation: (session: PurchaseStateSession) => Promise<T>,
  ): Promise<T>;
  claimRtdnMessage(claim: RtdnMessageClaim): Promise<boolean>;
  completeRtdnMessage(messageId: string): Promise<void>;
  failRtdnMessage(messageId: string, failureCode: string): Promise<void>;
  pruneExpiredRecords(): Promise<void>;
}
