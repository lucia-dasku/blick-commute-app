import { Hono } from "hono";
import { successEnvelope, RequestTransportModeSchema } from "../models/common.js";
import { AppError } from "../lib/errors.js";
import { normalizeDisruption } from "../normalize/normalizeDisruption.js";
import type { Disruption } from "../models/disruption.js";
import type { SlDeviationsClient } from "../services/slDeviationsClient.js";
import type { Cache, InFlightDeduper } from "../lib/cache.js";

// SL's fair-use guidance says at most one request per minute to this upstream (see
// docs/api-contract.md, "Caching and fair use"). 60s is a floor, not a target to shave
// down later.
const DEVIATIONS_CACHE_TTL_SECONDS = 60;

interface CachedDisruptions {
  fetchedAt: string;
  disruptions: Disruption[];
}

/**
 * `future` may only be absent, `"true"`, or `"false"` — any other value (e.g.
 * `"banana"`) is a client error, not something to silently coerce to `false`.
 */
function parseFutureFlag(raw: string | undefined): boolean {
  if (raw == null) return false;
  if (raw === "true") return true;
  if (raw === "false") return false;
  throw new AppError("VALIDATION_ERROR", "Query parameter 'future' must be 'true' or 'false' if present");
}

/**
 * Validates an incoming `transportMode` request filter against the documented, closed
 * set of modes SL Deviations actually accepts as a filter (`RequestTransportModeSchema`
 * — see docs/api-contract.md, "Request validation vs. response compatibility"). This is
 * deliberately a different, stricter schema than the one used for *response* fields:
 * a client-supplied filter value that isn't one of SL's own modes is a validation error,
 * whereas an unfamiliar mode string appearing in upstream *response* data must never be
 * rejected (see src/normalize/transportMode.ts).
 */
function parseTransportModeFilter(raw: string | undefined): string | undefined {
  if (raw == null) return undefined;
  const result = RequestTransportModeSchema.safeParse(raw);
  if (!result.success) {
    throw new AppError(
      "VALIDATION_ERROR",
      `Query parameter 'transportMode' must be one of: ${RequestTransportModeSchema.options.join(", ")}`,
    );
  }
  return result.data;
}

export function createDisruptionsRoute(
  client: SlDeviationsClient,
  cache: Cache,
  deduper: InFlightDeduper,
) {
  const route = new Hono();

  route.get("/", async (c) => {
    const siteIdRaw = c.req.query("siteId");
    const lineIdRaw = c.req.query("lineId");

    // siteId is required (see docs/api-contract.md): a routine always has a site, and
    // requiring it here keeps disruption requests properly scoped rather than pulling
    // the entire SL network's deviations on every call.
    if (siteIdRaw == null) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'siteId' is required");
    }
    const siteId = Number(siteIdRaw);
    if (!Number.isInteger(siteId) || siteId <= 0) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'siteId' must be a positive integer");
    }

    const lineId = lineIdRaw != null ? Number(lineIdRaw) : undefined;
    if (lineIdRaw != null && (!Number.isInteger(lineId) || (lineId as number) <= 0)) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'lineId' must be a positive integer");
    }

    const transportMode = parseTransportModeFilter(c.req.query("transportMode") ?? undefined);
    const future = parseFutureFlag(c.req.query("future") ?? undefined);

    const cacheKey = `sl-deviations:${siteId}:${lineId ?? ""}:${transportMode ?? ""}:${future}`;

    // The `{fetchedAt, disruptions}` pair is cached and deduplicated together, as one
    // unit: a cache hit returns the ORIGINAL upstream-fetch time, never a freshly
    // generated one, and N concurrent identical requests (via `deduper.run`) share
    // exactly one upstream call and therefore one fetchedAt (see
    // docs/api-contract.md, "fetchedAt semantics").
    const result = await deduper.run(cacheKey, async (): Promise<CachedDisruptions> => {
      const cached = await cache.get<CachedDisruptions>(cacheKey);
      if (cached) return cached;

      const raw = await client.fetchDeviations({ siteId, lineId, transportMode, future });
      const fetchedAt = new Date();
      const disruptions = raw.map(normalizeDisruption);
      const fresh: CachedDisruptions = { fetchedAt: fetchedAt.toISOString(), disruptions };
      await cache.set(cacheKey, fresh, DEVIATIONS_CACHE_TTL_SECONDS);
      return fresh;
    });

    c.header("Cache-Control", "public, s-maxage=60, stale-while-revalidate=60");
    return c.json(successEnvelope(result));
  });

  return route;
}
