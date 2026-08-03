import { config } from "../config/env.js";
import { AppError } from "../lib/errors.js";
import type { Cache } from "../lib/cache.js";
import type { DistributedLock } from "../lib/distributedLock.js";
import type { SlDeviationsClient } from "./slDeviationsClient.js";
import type { RawDeviation } from "./upstreamTypes.js";

export interface DeviationsSnapshot {
  fetchedAt: string;
  deviations: RawDeviation[];
}

export interface DeviationsSnapshotService {
  getSnapshot(): Promise<DeviationsSnapshot>;
}

const SNAPSHOT_CACHE_KEY = "sl-deviations:snapshot:v1";
const REFRESH_LOCK_KEY = "sl-deviations:refresh-lock:v1";
const RATE_LIMIT_KEY = "sl-deviations:rate-limit:v1";

/**
 * SL Deviations' own fair-use guidance: at most one request per minute, in aggregate
 * across every Vercel instance and every distinct site/line/mode/future combination (see
 * docs/api-contract.md, "Caching and fair use"). This is a floor mandated by the
 * upstream, not a target to shave down later — it governs BOTH how long a snapshot is
 * considered fresh enough to skip refreshing, and the minimum spacing between upstream
 * attempts, including failed ones.
 */
const FRESH_WINDOW_MS = 60_000;

/**
 * How long the last successful snapshot is kept as a stale fallback once it is no longer
 * "fresh" — deliberately much longer than `FRESH_WINDOW_MS`, so a prolonged SL Deviations
 * outage degrades to "possibly-outdated disruption data" rather than a hard failure on
 * every request. 6 hours is a deliberate, generous choice for a feed that stays broadly
 * useful even somewhat stale — SL does not itself document a recommended value.
 */
const STALE_FALLBACK_TTL_SECONDS = 6 * 60 * 60;

/**
 * The refresh lock's own TTL — generous relative to `config.upstreamTimeoutMs` (the
 * actual fetch's own bound), so a legitimately slow-but-successful fetch is never
 * preempted mid-flight, while still recovering well within the 60s rate-limit window if
 * the holder crashes without releasing (see `DistributedLock`'s own "safe expiry" doc).
 */
const REFRESH_LOCK_TTL_MS = config.upstreamTimeoutMs + 10_000;

/**
 * How many times, and how often, a request that lost the refresh-lock race polls the
 * shared cache for the in-flight refresh's result before giving up and falling back to
 * stale data (or the controlled upstream error, if there is no stale data at all) — "wait
 * briefly", never indefinitely, and never by making an upstream call of its own.
 */
const WAIT_RETRY_COUNT = 5;
const WAIT_RETRY_DELAY_MS = 200;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isFresh(snapshot: DeviationsSnapshot, nowMs: number): boolean {
  return nowMs - new Date(snapshot.fetchedAt).getTime() < FRESH_WINDOW_MS;
}

const NOT_YET_AVAILABLE = new AppError(
  "UPSTREAM_ERROR",
  "SL Deviations data is not yet available; please retry shortly",
);

/**
 * Produces the single, network-wide SL Deviations snapshot every `/api/v1/disruptions`
 * request filters locally (see `deviationsFilter.ts`) — never one upstream call per
 * query. Coordinates across ALL Vercel instances via `lock`/`cache` (Redis-backed in
 * production — see src/app.ts and src/lib/redisClient.ts) so that, in aggregate, SL
 * Deviations sees at most one request per 60-second window, including failed attempts —
 * satisfying SL's own fair-use guidance for the first time, rather than only limiting
 * repeat requests for the SAME filter combination the way the previous per-query design
 * did (see docs/api-contract.md, "Caching and fair use").
 *
 * Call sequence for every `getSnapshot()` call, from any instance:
 *
 * 1. If a cached snapshot exists and is still within `FRESH_WINDOW_MS` of its own
 *    `fetchedAt`, return it immediately — no lock, no rate-limit check, no upstream call.
 * 2. Otherwise, try to acquire the short-lived `REFRESH_LOCK_KEY`. If another instance
 *    already holds it (actively refreshing right now), wait briefly
 *    (`WAIT_RETRY_COUNT` × `WAIT_RETRY_DELAY_MS`) for their result to appear in the
 *    shared cache, then fall back to whatever stale snapshot exists, then — only if
 *    there is truly nothing at all — a controlled `AppError`.
 * 3. Holding the refresh lock, re-check the cache once more (another instance may have
 *    just finished while this call was waiting to acquire it), then try to claim the 60s
 *    `RATE_LIMIT_KEY` window. If someone else already claimed it (successfully or not)
 *    within the last 60s, this instance must not fetch either — same stale/error
 *    fallback as step 2. The rate-limit claim is deliberately NEVER released early: it
 *    is set with the full `FRESH_WINDOW_MS` as its own TTL and left to expire naturally,
 *    so a claim made moments before a fetch FAILS still blocks every instance from
 *    retrying for the rest of that 60s window, exactly like a successful claim would.
 * 4. Only the instance that won both the refresh lock and the rate-limit claim actually
 *    calls `client.fetchAllDeviations()`. On success, the new snapshot is cached (with
 *    `STALE_FALLBACK_TTL_SECONDS`, far longer than the 60s freshness window, specifically
 *    so it remains available as a fallback long after it stops being "fresh"). On
 *    failure, the last known-good snapshot is returned instead, with its ORIGINAL
 *    `fetchedAt` untouched (see docs/api-contract.md, "fetchedAt semantics") — and only
 *    when no such snapshot exists at all does the real upstream error propagate
 *    unchanged, exactly the same controlled error this endpoint already produced before
 *    this shared-protection layer existed.
 */
export function createDeviationsSnapshotService(
  client: SlDeviationsClient,
  cache: Cache,
  lock: DistributedLock,
): DeviationsSnapshotService {
  async function attempt(retriesLeft: number): Promise<DeviationsSnapshot> {
    const existing = await cache.get<DeviationsSnapshot>(SNAPSHOT_CACHE_KEY);
    if (existing && isFresh(existing, Date.now())) {
      return existing;
    }

    const refreshToken = await lock.acquire(REFRESH_LOCK_KEY, REFRESH_LOCK_TTL_MS);
    if (refreshToken == null) {
      // Someone else (this instance or another) is actively refreshing right now.
      if (retriesLeft > 0) {
        await delay(WAIT_RETRY_DELAY_MS);
        return attempt(retriesLeft - 1);
      }
      if (existing) return existing;
      throw NOT_YET_AVAILABLE;
    }

    try {
      // Another instance may have finished refreshing while this call waited to
      // acquire the lock above -- re-check before doing any work ourselves.
      const refreshedByOther = await cache.get<DeviationsSnapshot>(SNAPSHOT_CACHE_KEY);
      if (refreshedByOther && isFresh(refreshedByOther, Date.now())) {
        return refreshedByOther;
      }

      const rateLimitToken = await lock.acquire(RATE_LIMIT_KEY, FRESH_WINDOW_MS);
      if (rateLimitToken == null) {
        // Another instance already attempted (successfully or not) within the last
        // 60s. Must not attempt again ourselves, even though we hold the refresh lock.
        // Deliberately does NOT retry/wait here, unlike the refresh-lock-contention
        // branch above: there is no active fetch in flight to catch up with — the
        // rate-limit claim can legitimately stay in effect for up to the REST of its
        // 60s window, far longer than a "wait briefly" is meant to cover — so this
        // resolves immediately, one way or the other.
        const fallback = refreshedByOther ?? existing;
        if (fallback) return fallback;
        throw NOT_YET_AVAILABLE;
      }
      // rateLimitToken is deliberately never released — see this function's own doc.

      try {
        const deviations = await client.fetchAllDeviations();
        const snapshot: DeviationsSnapshot = { fetchedAt: new Date().toISOString(), deviations };
        await cache.set(SNAPSHOT_CACHE_KEY, snapshot, STALE_FALLBACK_TTL_SECONDS);
        return snapshot;
      } catch (err) {
        // Refresh failed — fall back to the last known-good snapshot, preserving its
        // ORIGINAL fetchedAt, if one exists; otherwise let the real, already-controlled
        // upstream error through completely unchanged.
        const fallback = refreshedByOther ?? existing;
        if (fallback) return fallback;
        throw err;
      }
    } finally {
      // Best-effort: a throwing release() must never override whatever the try block above is
      // already returning or throwing -- by JS semantics, an exception from a `finally` block
      // replaces a pending return/throw from its own `try`, so an UNGUARDED release() call here
      // could turn an already-successful, already-cached snapshot into a 500 for this request,
      // even though the data was fetched fine and is now available to every other request. The
      // lock's own TTL (REFRESH_LOCK_TTL_MS) still guarantees eventual release if this fails.
      try {
        await lock.release(REFRESH_LOCK_KEY, refreshToken);
      } catch (err) {
        console.warn("Failed to release SL Deviations refresh lock (will expire via its own TTL):", err);
      }
    }
  }

  return {
    getSnapshot: () => attempt(WAIT_RETRY_COUNT),
  };
}
