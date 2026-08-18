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

export interface RedisConfig {
  url: string;
  token: string;
}

export interface GooglePlayConfig {
  packageName: string;
  serviceAccountEmail: string;
  privateKey: string;
}

export interface DatabaseConfig { connectionString: string }
export interface GooglePlayRtdnConfig { audience: string; serviceAccountEmail: string }

export function readDatabaseConfig(raw: string | undefined): DatabaseConfig | undefined {
  const connectionString = raw?.trim();
  if (!connectionString) return undefined;
  const url = new URL(connectionString);
  if (url.protocol !== "postgres:" && url.protocol !== "postgresql:") {
    throw new Error("DATABASE_URL must use the postgres or postgresql protocol");
  }
  return { connectionString };
}

export function readGooglePlayRtdnConfig(
  audienceRaw: string | undefined,
  emailRaw: string | undefined,
): GooglePlayRtdnConfig | undefined {
  const audience = audienceRaw?.trim();
  const serviceAccountEmail = emailRaw?.trim();
  if (!audience && !serviceAccountEmail) return undefined;
  if (!audience || !serviceAccountEmail) {
    throw new Error("GOOGLE_PLAY_RTDN_AUDIENCE and GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL must both be set");
  }
  const url = new URL(audience);
  if (url.protocol !== "https:") throw new Error("GOOGLE_PLAY_RTDN_AUDIENCE must be an HTTPS URL");
  return { audience, serviceAccountEmail };
}

/** Credentials are optional so local development and non-billing tests remain usable. The
 * verification endpoint reports a sanitized temporary-unavailable response when they are not
 * configured. A partial configuration is rejected eagerly because it can never work. */
export function readGooglePlayConfig(
  packageNameRaw: string | undefined,
  serviceAccountEmailRaw: string | undefined,
  privateKeyRaw: string | undefined,
): GooglePlayConfig | undefined {
  const packageName = packageNameRaw?.trim() || undefined;
  const serviceAccountEmail = serviceAccountEmailRaw?.trim() || undefined;
  const privateKey = privateKeyRaw?.replace(/\\n/g, "\n").trim() || undefined;
  const values = [packageName, serviceAccountEmail, privateKey];
  if (values.every((value) => value === undefined)) return undefined;
  if (values.some((value) => value === undefined)) {
    throw new Error(
      "GOOGLE_PLAY_PACKAGE_NAME, GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL and " +
        "GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY must all be set, or all be left unset.",
    );
  }
  if (!privateKey!.includes("BEGIN PRIVATE KEY")) {
    throw new Error("GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY must be a PEM PKCS#8 private key");
  }
  return { packageName: packageName!, serviceAccountEmail: serviceAccountEmail!, privateKey: privateKey! };
}

/**
 * Validates the Upstash Redis REST credentials backing the shared cache/lock that
 * protects the SL Deviations upstream across all Vercel instances (see
 * docs/api-contract.md, "Caching and fair use", and src/services/deviationsSnapshotService.ts).
 * `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` are the exact variable names
 * Vercel's own Upstash marketplace integration populates automatically when a Redis
 * database is connected to a project, so no renaming/mapping step is needed there.
 *
 * Both variables must be set together, or both left unset — a partial configuration (only
 * one of the two) is always a deployment mistake, never intentional, and fails loudly
 * rather than silently falling back to an unprotected in-memory cache/lock.
 *
 * **In production, a missing Redis configuration is a hard startup failure.** SL's own
 * fair-use guidance allows at most one SL Deviations request per minute ACROSS EVERY
 * Vercel instance combined; the per-process `InMemoryCache`/`InMemoryLock` fallback
 * (src/lib/cache.ts, src/lib/distributedLock.ts) provides no cross-instance protection at
 * all, so production must never run with it silently. Outside production (local dev,
 * tests — `NODE_ENV` anything other than `"production"`), a missing configuration is
 * expected and simply selects the in-memory implementations instead (see src/app.ts).
 */
export function readRedisConfig(
  rawUrl: string | undefined,
  rawToken: string | undefined,
  nodeEnv: string,
): RedisConfig | undefined {
  const url = rawUrl?.trim() || undefined;
  const token = rawToken?.trim() || undefined;

  if (url === undefined && token === undefined) {
    if (nodeEnv === "production") {
      throw new Error(
        "UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN are required in production — " +
          "the SL Deviations upstream must be protected by a shared cache/lock across every " +
          "Vercel instance (see docs/api-contract.md, 'Caching and fair use'); production must " +
          "never silently run with only the per-instance in-memory fallback.",
      );
    }
    return undefined;
  }
  if (url === undefined || token === undefined) {
    throw new Error(
      "UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN must both be set, or both left " +
        "unset — a partial Redis configuration is never valid.",
    );
  }
  try {
    new URL(url); // constructed only to validate; the URL itself is what's returned below
  } catch {
    throw new Error(`Invalid UPSTASH_REDIS_REST_URL: ${JSON.stringify(url)} — must be a valid URL`);
  }
  return { url, token };
}

const nodeEnv = process.env.NODE_ENV ?? "development";

export const config = {
  nodeEnv,
  port: readPort(process.env.PORT),
  slTransportBaseUrl: process.env.SL_TRANSPORT_BASE_URL ?? "https://transport.integration.sl.se/v1",
  slDeviationsBaseUrl: process.env.SL_DEVIATIONS_BASE_URL ?? "https://deviations.integration.sl.se/v1",
  slJourneyPlannerBaseUrl: process.env.SL_JOURNEY_PLANNER_BASE_URL ?? "https://journeyplanner.integration.sl.se/v2",
  /**
   * How long to wait for an upstream (SL Transport or SL Deviations) response before
   * aborting the request and returning UPSTREAM_TIMEOUT (504). See
   * src/lib/upstreamFetch.ts and docs/api-contract.md, "Upstream networking".
   */
  upstreamTimeoutMs: readUpstreamTimeoutMs(process.env.UPSTREAM_TIMEOUT_MS),
  /** See `readRedisConfig`'s own doc. `undefined` outside production means "use the
   * in-memory Cache/DistributedLock fallback" (src/app.ts); `undefined` in production is
   * impossible — `readRedisConfig` throws first, failing startup instead. */
  redis: readRedisConfig(process.env.UPSTASH_REDIS_REST_URL, process.env.UPSTASH_REDIS_REST_TOKEN, nodeEnv),
  googlePlay: readGooglePlayConfig(
    process.env.GOOGLE_PLAY_PACKAGE_NAME,
    process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL,
    process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY,
  ),
  database: readDatabaseConfig(process.env.DATABASE_URL),
  googlePlayRtdn: readGooglePlayRtdnConfig(
    process.env.GOOGLE_PLAY_RTDN_AUDIENCE,
    process.env.GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL,
  ),
  /** Trafiklab GTFS Regional requires an API key to download (`GET
   * https://opendata.samtrafiken.se/gtfs/{operator}/{operator}.zip?key=...` — verified live
   * against Trafiklab's own current documentation, 2026-08-16; no free/keyless tier exists for
   * this feed). `undefined` when not configured — `services/lineTopologyDirectory.ts` treats
   * that exactly like any other topology-unavailable case (never a startup failure, never a
   * 500): the segment-parsing relevance enhancement it powers is additive over an
   * already-safe `LINE_RELEVANT` fallback, so simply not having this key yet must never block
   * `/api/v1/journeys/disruptions`, only leave that one enhancement inactive. See that
   * service's own doc for the required Vercel environment variable name and setup. */
  trafiklabApiKey: process.env.TRAFIKLAB_API_KEY,
} as const;
