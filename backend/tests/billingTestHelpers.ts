import type {
  PurchaseStateRecord,
  PurchaseStateStore,
  RtdnMessageClaim,
} from "../src/billing/purchaseStateStore.js";

export class FakePurchaseStateStore implements PurchaseStateStore {
  readonly records = new Map<string, PurchaseStateRecord>();
  readonly messages = new Map<string, "PROCESSING" | "PROCESSED" | "FAILED">();
  private readonly lockTails = new Map<string, Promise<void>>();

  async withPurchaseLock<T>(
    tokenFingerprint: string,
    operation: (session: { get(): Promise<PurchaseStateRecord | undefined>; save(record: PurchaseStateRecord): Promise<void> }) => Promise<T>,
  ): Promise<T> {
    const previous = this.lockTails.get(tokenFingerprint) ?? Promise.resolve();
    let release = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    this.lockTails.set(tokenFingerprint, previous.then(() => gate));
    await previous;
    try {
      return await operation({
        get: async () => this.records.get(tokenFingerprint),
        save: async (record) => {
          const existing = this.records.get(tokenFingerprint);
          if (existing?.lastEventTime && record.lastEventTime && record.lastEventTime < existing.lastEventTime) return;
          this.records.set(tokenFingerprint, { ...record });
        },
      });
    } finally {
      release();
    }
  }

  async claimRtdnMessage(claim: RtdnMessageClaim): Promise<boolean> {
    const status = this.messages.get(claim.messageId);
    if (status === "PROCESSING" || status === "PROCESSED") return false;
    this.messages.set(claim.messageId, "PROCESSING");
    return true;
  }
  async completeRtdnMessage(messageId: string): Promise<void> { this.messages.set(messageId, "PROCESSED"); }
  async failRtdnMessage(messageId: string): Promise<void> { this.messages.set(messageId, "FAILED"); }
  async pruneExpiredRecords(): Promise<void> {}
}
