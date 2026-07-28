/**
 * Runtime configuration. Both current upstreams (SL Transport, SL Deviations) are
 * keyless public APIs (see docs/api-contract.md) so nothing here is a secret today.
 * Base URLs are overridable for testing against a mock server.
 *
 * `PORT` and `UPSTREAM_TIMEOUT_MS` are validated eagerly at module load: both are only
 * ever supplied by deployment configuration (never by an end user of the API), so a
 * malformed value is a deployment mistake that should fail loudly and immediately
 * rather than silently coercing to `NaN` (which would then surface as a confusing
 * runtime failure much later — an unbounded/instant upstream timeout, or a server that
 * fails to bind at all with no clear reason why).
 */

const MIN_PORT = 1;
const MAX_PORT = 65_535;

export function readPort(rawValue: string | undefined): number {
  if (rawValue === undefined) return 8787;
  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value < MIN_PORT || value > MAX_PORT) {
    throw new Error(
      `Invalid PORT: ${JSON.stringify(rawValue)} — must be an integer between ${MIN_PORT} and ${MAX_PORT}`,
    );
  }
  return value;
}

export function readUpstreamTimeoutMs(rawValue: string | undefined): number {
  if (rawValue === undefined) return 10_000;
  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(
      `Invalid UPSTREAM_TIMEOUT_MS: ${JSON.stringify(rawValue)} — must be a positive integer number of milliseconds`,
    );
  }
  return value;
}

export const config = {
  nodeEnv: process.env.NODE_ENV ?? "development",
  port: readPort(process.env.PORT),
  slTransportBaseUrl: process.env.SL_TRANSPORT_BASE_URL ?? "https://transport.integration.sl.se/v1",
  slDeviationsBaseUrl: process.env.SL_DEVIATIONS_BASE_URL ?? "https://deviations.integration.sl.se/v1",
  /**
   * How long to wait for an upstream (SL Transport or SL Deviations) response before
   * aborting the request and returning UPSTREAM_TIMEOUT (504). See
   * src/lib/upstreamFetch.ts and docs/api-contract.md, "Upstream networking".
   */
  upstreamTimeoutMs: readUpstreamTimeoutMs(process.env.UPSTREAM_TIMEOUT_MS),
} as const;
