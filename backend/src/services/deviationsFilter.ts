import type { RawDeviation } from "./upstreamTypes.js";
import type { Site } from "../models/site.js";

export interface DeviationsQuery {
  siteId: number;
  lineId?: number;
  transportMode?: string;
  future: boolean;
}

/**
 * Filters an already-fetched, network-wide SL Deviations snapshot
 * (`deviationsSnapshotService.ts`) down to the routine-scoped subset a single
 * `/api/v1/disruptions` request asks for — replacing the per-request upstream
 * `site`/`line`/`transport_mode`/`future` query parameters this backend used to forward
 * directly (see docs/api-contract.md, "Caching and fair use", for why one full-network
 * fetch shared by every query, rather than one upstream call per distinct filter
 * combination, is what actually makes the 60s-per-minute fair-use floor enforceable in
 * aggregate).
 *
 * Every filter dimension is applied independently (AND'ed together), matching the
 * original per-request query parameters' own semantics one-for-one:
 *
 * - `siteId`: matches if any of the deviation's `scope.stop_areas[].id` is present in
 *   `siteStopAreaIds` (the site's own ID plus its child stop-area IDs — see
 *   `resolveSiteStopAreaIds`). Confirmed live during architecture review that a site's
 *   deviations are scoped by its child stop areas' IDs, in the same ID namespace as SL
 *   Transport (docs/api-contract.md §1, "Verified namespace result"). A deviation with
 *   no `scope.stop_areas` at all (e.g. line-only or network-wide) never matches a
 *   `siteId` filter — this mirrors the previous upstream-side `site=` filter, which only
 *   ever returned site-scoped deviations.
 * - `lineId` (optional): matches if any of `scope.lines[].id` equals it.
 * - `transportMode` (optional): matches if any of `scope.lines[].transport_mode` equals
 *   it.
 * - `future`: a deviation is EXPIRED if `publish.upto` is set and in the past — always
 *   excluded, regardless of `future`. A deviation is NOT YET STARTED if `publish.from`
 *   is set and in the future; `future=false` (the default) excludes these, `future=true`
 *   includes them — matching the documented behavior ("a normal active-routine request
 *   only sees currently published disruptions... future=true also see disruptions
 *   published for the future", docs/api-contract.md §3.2). A missing `publish.from`/
 *   `publish.upto` is treated as "has always been valid" / "valid indefinitely",
 *   respectively.
 */
export function matchesDeviationsQuery(
  deviation: RawDeviation,
  query: DeviationsQuery,
  siteStopAreaIds: ReadonlySet<number>,
  now: Date,
): boolean {
  const stopAreaIds = deviation.scope.stop_areas?.map((a) => a.id) ?? [];
  if (!stopAreaIds.some((id) => siteStopAreaIds.has(id))) {
    return false;
  }

  const lines = deviation.scope.lines ?? [];
  if (query.lineId != null && !lines.some((l) => l.id === query.lineId)) {
    return false;
  }
  if (query.transportMode != null && !lines.some((l) => l.transport_mode === query.transportMode)) {
    return false;
  }

  const nowMs = now.getTime();
  const from = deviation.publish?.from ? new Date(deviation.publish.from).getTime() : null;
  const upto = deviation.publish?.upto ? new Date(deviation.publish.upto).getTime() : null;
  if (upto != null && upto < nowMs) return false; // always exclude expired
  if (!query.future && from != null && from > nowMs) return false; // not yet started

  return true;
}

/**
 * Resolves the set of stop-area IDs a `siteId` filter should match against: the site's
 * own ID plus every child stop-area ID from the site directory (see
 * `matchesDeviationsQuery`'s own doc for why both are included). Falls back to just
 * `{siteId}` when the ID isn't found in the directory — an unrecognized siteId simply
 * matches nothing (or very little), exactly as it did when forwarded to upstream's own
 * `site=` filter, never a validation error (siteId's own numeric-format check already
 * happened at the route).
 */
export function resolveSiteStopAreaIds(siteId: number, sites: readonly Site[]): Set<number> {
  const site = sites.find((s) => s.siteId === siteId);
  return new Set<number>([siteId, ...(site?.stopAreaIds ?? [])]);
}
