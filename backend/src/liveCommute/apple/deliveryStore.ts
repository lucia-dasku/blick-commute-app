import type {
  StoredLiveCommuteInstallation,
  StoredLiveCommuteSession,
} from "../sessionStore.js";
import type {
  LiveActivityDeliveryBinding,
  StoredLiveActivityUpdateToken,
  StoredPushToStartToken,
} from "./deliveryModel.js";

export interface LiveActivityDeliveryInstallationTransaction {
  getInstallation(): Promise<StoredLiveCommuteInstallation | undefined>;
  getSession(sessionId: string): Promise<StoredLiveCommuteSession | undefined>;

  getLatestPushToStartToken(): Promise<StoredPushToStartToken | undefined>;
  getPushToStartToken(
    clientGeneration: number,
  ): Promise<StoredPushToStartToken | undefined>;
  savePushToStartToken(token: StoredPushToStartToken): Promise<void>;

  getDeliveryBinding(bindingId: string): Promise<LiveActivityDeliveryBinding | undefined>;
  getDeliveryBindingForSessionVersion(
    sessionId: string,
    sessionRevision: number,
  ): Promise<LiveActivityDeliveryBinding | undefined>;
  saveDeliveryBinding(binding: LiveActivityDeliveryBinding): Promise<boolean>;

  getLatestUpdateToken(
    bindingId: string,
  ): Promise<StoredLiveActivityUpdateToken | undefined>;
  getUpdateToken(
    bindingId: string,
    clientGeneration: number,
  ): Promise<StoredLiveActivityUpdateToken | undefined>;
  saveUpdateToken(token: StoredLiveActivityUpdateToken): Promise<void>;
}

/**
 * Apple delivery state shares the Phase 3A installation-parent lock. The callback is for
 * short persistence/crypto work only and must never perform APNs or transit network I/O.
 * Multi-row rotations may pass through an intermediate no-current state, but each callback
 * must finish with the latest token either CURRENT or explicitly INVALIDATED.
 */
export interface LiveActivityDeliveryStore {
  withInstallationTransaction<T>(
    installationId: string,
    operation: (transaction: LiveActivityDeliveryInstallationTransaction) => Promise<T>,
  ): Promise<T>;
}
