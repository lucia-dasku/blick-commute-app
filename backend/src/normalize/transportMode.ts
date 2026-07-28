import type { TransportMode } from "../models/common.js";

/**
 * Fallback value used only when upstream provides no usable transport-mode string at
 * all (missing, null, or empty after trimming). Never used to replace a value SL did
 * provide, however unfamiliar — see docs/api-contract.md, "Request validation vs.
 * response compatibility".
 */
export const UNKNOWN_TRANSPORT_MODE: TransportMode = "UNKNOWN";

/**
 * Passes an upstream `transport_mode` string through unchanged, however unfamiliar —
 * this backend must never fail to deserialize a departure or disruption just because SL
 * introduced a new mode. Falls back to `"UNKNOWN"` only when upstream gives us nothing
 * usable, so this field is never an empty string.
 */
export function asTransportMode(value: string | null | undefined): TransportMode {
  const trimmed = value?.trim();
  return trimmed ? trimmed : UNKNOWN_TRANSPORT_MODE;
}
