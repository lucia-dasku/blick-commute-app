/**
 * Opaque authority seam shared by the publication worker and final APNs send boundary.
 * Implementations must fail closed and expose no lease, fence, or persistence diagnostics.
 */
export type LiveActivityPublicationCycleAuthorityStopReason =
  | "CYCLE_AUTHORITY_LOST"
  | "CYCLE_AUTHORITY_UNAVAILABLE";

export type LiveActivityPublicationCycleAuthorityStatus =
  | "CURRENT"
  | LiveActivityPublicationCycleAuthorityStopReason;

export type BeginLiveActivityPublicationCycleAuthorizedSend<T> =
  | {
      readonly status: "STARTED";
      readonly completion: Promise<T>;
    }
  | {
      readonly status: "STOPPED";
      readonly reason: LiveActivityPublicationCycleAuthorityStopReason;
    };

export interface LiveActivityPublicationCycleAuthority {
  confirmCurrent(): Promise<LiveActivityPublicationCycleAuthorityStatus>;
  stopReason(): LiveActivityPublicationCycleAuthorityStopReason | null;
  beginSendIfCurrent<T>(
    start: () => Promise<T>,
  ): Promise<BeginLiveActivityPublicationCycleAuthorizedSend<T>>;
}
