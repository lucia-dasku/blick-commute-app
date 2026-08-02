import { Hono } from "hono";
import { successEnvelope, RequestTransportModeSchema } from "../models/common.js";
import { AppError } from "../lib/errors.js";
import { normalizeDisruption } from "../normalize/normalizeDisruption.js";
import { matchesDeviationsQuery, resolveSiteStopAreaIds } from "../services/deviationsFilter.js";
import type { DeviationsSnapshotService } from "../services/deviationsSnapshotService.js";
import type { SiteDirectory } from "../services/siteDirectory.js";

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

/**
 * Request validation and response structure are UNCHANGED from the previous per-query
 * implementation (see docs/api-contract.md §3.2) — only where the disruption data comes
 * from has changed: `snapshotService.getSnapshot()` returns the one shared, network-wide
 * snapshot (see deviationsSnapshotService.ts), and `matchesDeviationsQuery` filters it
 * locally per request (see deviationsFilter.ts), rather than this route forwarding
 * `siteId`/`lineId`/`transportMode`/`future` to SL Deviations as its own upstream query
 * parameters and caching/deduplicating per exact filter combination.
 */
export function createDisruptionsRoute(snapshotService: DeviationsSnapshotService, siteDirectory: SiteDirectory) {
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

    // The shared snapshot's own fetchedAt is what every response reports — it reflects
    // when SL Deviations was actually last called, never a freshly generated time (see
    // docs/api-contract.md, "fetchedAt semantics"). Filtering below is pure, local, and
    // does not affect fetchedAt regardless of which filters this specific request used.
    const snapshot = await snapshotService.getSnapshot();
    const sites = await siteDirectory.getAllSites();
    const siteStopAreaIds = resolveSiteStopAreaIds(siteId, sites);
    const now = new Date();

    const disruptions = snapshot.deviations
      .filter((raw) => matchesDeviationsQuery(raw, { siteId, lineId, transportMode, future }, siteStopAreaIds, now))
      .map(normalizeDisruption);

    c.header("Cache-Control", "public, s-maxage=60, stale-while-revalidate=60");
    return c.json(successEnvelope({ fetchedAt: snapshot.fetchedAt, disruptions }));
  });

  return route;
}
