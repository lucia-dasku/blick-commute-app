import { z } from "zod";

export const SCHEMA_VERSION = 1 as const;

/**
 * SL's own transport_mode values, as seen on SL Transport (`line.transport_mode`) and
 * SL Deviations (`scope.lines.transport_mode`). This list is the closed set used ONLY to
 * validate incoming *request* filters (e.g. `?transportMode=` on
 * `GET /api/v1/disruptions`) against modes SL actually documents as filterable.
 *
 * It is deliberately NOT used to validate normalized response data — see
 * `TransportModeSchema` below and docs/api-contract.md, "Request validation vs. response
 * compatibility": a closed enum on the response side would silently contradict this
 * codebase's forward-compatibility guarantee the moment SL adds a new mode.
 */
export const KNOWN_TRANSPORT_MODES = ["BUS", "METRO", "TRAIN", "TRAM", "SHIP", "FERRY", "TAXI"] as const;

/** Strict — used only to validate an incoming request's `transportMode` filter value. */
export const RequestTransportModeSchema = z.enum(KNOWN_TRANSPORT_MODES);
export type RequestTransportMode = z.infer<typeof RequestTransportModeSchema>;

/**
 * Permissive — used for normalized response/DTO `transportMode` fields. A plain
 * non-empty string, not a closed enum, so an upstream addition never breaks parsing.
 * `asTransportMode` (see src/normalize/transportMode.ts) guarantees this is never an
 * empty string by mapping a missing/empty upstream value to the literal `"UNKNOWN"`.
 */
export const TransportModeSchema = z.string().min(1);
export type TransportMode = z.infer<typeof TransportModeSchema>;

export const ErrorCodeSchema = z.enum([
  "VALIDATION_ERROR",
  "UPSTREAM_ERROR",
  "UPSTREAM_TIMEOUT",
  "UPSTREAM_RATE_LIMITED",
  "NOT_FOUND",
  "RATE_LIMITED",
  "INTERNAL_ERROR",
]);
export type ErrorCode = z.infer<typeof ErrorCodeSchema>;

export function successEnvelope<T>(data: T) {
  return { schemaVersion: SCHEMA_VERSION, data };
}

export function errorEnvelope(code: ErrorCode, message: string) {
  return { schemaVersion: SCHEMA_VERSION, error: { code, message } };
}
