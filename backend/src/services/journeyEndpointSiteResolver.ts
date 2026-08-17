import type { Cache } from "../lib/cache.js";
import type { SlJourneyPlannerClient } from "./slJourneyPlannerClient.js";
import type { SiteDirectory } from "./siteDirectory.js";

/**
 * This feature's requested-corridor identity bridge (see `domain/requestedCorridor.ts`'s own
 * top-level doc for how the resolved site id is then used): resolves a Journey-Planner-native
 * `originId`/`destinationId` — the SAME opaque, already-received `location.id` string Android
 * already sends to `GET /api/v1/journeys` (see `slJourneyPlannerClient.ts`'s own `LocationSchema`
 * doc for the full live-verified bridge this rests on) — to the SL Transport `site.id` it
 * actually is.
 *
 * Re-verified live (2026-08-16, production-readiness review) against the alternative of using
 * `location.id` directly as an exact SL Transport `site.gid` bridge, to see whether the
 * `- 18000000` arithmetic below could be retired in favor of an exact GID→GID match. It could
 * not: `location.id` is NOT an independent identifier that happens to equal `site.gid` — it is
 * Journey Planner's OWN internally-constructed id, `"9091001000" + zero-padded(site.id, 6)`,
 * confirmed exactly across 11/11 fresh live stop-finder lookups (T-Centralen, Slussen, Odenplan,
 * Gullmarsplan, Fridhemsplan, Akalla, Kungsträdgården, Mariatorget, Älvsjö, Danderyds sjukhus,
 * Vaxholm). SL Transport's own REAL `site.gid` (from `GET /v1/sites`) is a SEPARATE value that
 * only coincidentally equals that same formula for some stations (3/11 tested: Slussen, Akalla,
 * Kungsträdgården) and is off by exactly ±1 for the rest (8/11: e.g. T-Centralen `site.id=9001`
 * has real `site.gid=...009000`, not the `...009001` `location.id` would imply) — `site.gid` is
 * independently assigned and drifts slightly from a clean function of `site.id` over time
 * (historical renumbering/merges), so `location.id` cannot be trusted as a `site.gid` stand-in.
 * The full raw Journey Planner location object was also inspected directly (`coord`,
 * `disassembledName`, `id`, `isGlobalId`, `matchQuality`, `name`, `parent`, `productClasses`,
 * `properties.{mainLocality,stopId}`, `type`) — no other field carries anything resembling an
 * independent `site.gid` echo. `properties.stopId` (the bridge actually used below) remains the
 * best available option: it matches Trafiklab's own documented "rikshållplats"/national-stop-id
 * convention (a semi-official scheme, unlike `location.id`'s undocumented internal formula) and
 * was reconfirmed exact across the SAME 11/11 fresh stations (plus the pre-existing 11+ from this
 * feature's original investigation phase), with the one known, unchanged exception below
 * (ferry-only Vaxholm). Conclusion: outcome (3) of this investigation's own preference order —
 * "empirically proven arithmetic conversion + verification" — remains correct; no exact GID
 * bridge exists to switch to, so this arithmetic is being KEPT, not replaced.
 *
 * Deliberately never called from `/api/v1/journeys` itself (see this feature's own spec, item 11:
 * that route must stay fast and independent of this enhancement entirely) — the one production
 * caller is `routes/journeyDisruptions.ts`, which already accepts `journeyOriginId`/
 * `journeyDestinationId` as new, OPTIONAL fields on its own POST body (Android simply resends the
 * exact same ids it already holds from its own most recent `/api/v1/journeys` call — no new
 * Android-side computation, no new persisted field, matching this feature's own spec item 14:
 * "Android must simply preserve/pass these values back unchanged").
 *
 * Requires one extra SL Journey Planner call per distinct id (a bare-id `/stop-finder` lookup —
 * confirmed live that `type_sf: "any"`/`name_sf` accepts a raw `id` and returns that exact
 * place's own record, not just a text search) — cached aggressively (see `CACHE_TTL_SECONDS`)
 * specifically because this is a STABLE identity mapping for a given routine, asked again on
 * every worker tick, never a value that changes over time the way live departures do.
 */
export interface JourneyEndpointSiteResolver {
  /** Never throws — an upstream failure, a stop-finder response that does not contain the exact
   * requested [journeyPlannerLocationId] AT ALL (a search response naming other, similar/nearby
   * places is NOT identity evidence for the requested id itself — no exact-id match means no
   * evidence, never a first-result/name/coordinate fallback), a `properties.stopId` that is
   * absent or non-numeric, or a derived id that does not correspond to any real, currently-known
   * site (confirmed against `SiteDirectory`, never assumed — see this file's own doc on the one
   * live-confirmed exception, a ferry-only destination) all resolve to `null`, exactly like any
   * other "cannot prove this" outcome elsewhere in this feature. `domain/requestedCorridor.ts`'s
   * own caller treats `null` as "the requested-corridor enhancement is inactive for this
   * endpoint", never as a reason to fail the request. */
  resolveSiteId(journeyPlannerLocationId: string): Promise<number | null>;
}

const NATIONAL_STOP_ID_OFFSET = 18_000_000;

/** A pure identity mapping, not time-varying operational data — a much longer TTL than any other
 * cache in this codebase is appropriate (compare `StopPointDirectory`'s own 24h reference-data
 * window): the place a given `originId` refers to essentially never changes. */
const CACHE_TTL_SECONDS = 30 * 24 * 60 * 60;
const CACHE_KEY_PREFIX = "journey-endpoint-site:v1:";

function extractStopId(location: { id: string; properties?: { stopId?: string } } | undefined): string | null {
  const stopId = location?.properties?.stopId;
  return stopId != null && /^\d+$/.test(stopId) ? stopId : null;
}

export function createJourneyEndpointSiteResolver(
  journeyPlannerClient: Pick<SlJourneyPlannerClient, "searchStops">,
  siteDirectory: SiteDirectory,
  cache: Cache,
): JourneyEndpointSiteResolver {
  return {
    async resolveSiteId(journeyPlannerLocationId) {
      const cacheKey = `${CACHE_KEY_PREFIX}${journeyPlannerLocationId}`;
      try {
        const cached = await cache.get<number | null>(cacheKey);
        if (cached !== undefined) return cached;

        const locations = await journeyPlannerClient.searchStops(journeyPlannerLocationId);
        // EXACT match only -- a stop-finder response naming other, similar/nearby places is not
        // identity evidence for the requested id itself. An earlier version of this code fell
        // back to `locations[0]` when no exact match was found, which could silently accept a
        // completely unrelated place as if it were the requested id's own identity (confirmed
        // live-reproducible bug; see journeyEndpointSiteResolver.test.ts's own "item D" case).
        const match = locations.find((l) => l.id === journeyPlannerLocationId);
        const rawStopId = extractStopId(match);
        if (rawStopId == null) {
          await cache.set(cacheKey, null, CACHE_TTL_SECONDS);
          return null;
        }

        const derivedSiteId = Number(rawStopId) - NATIONAL_STOP_ID_OFFSET;
        const sites = await siteDirectory.getAllSites();
        const confirmed = sites.some((s) => s.siteId === derivedSiteId) ? derivedSiteId : null;
        await cache.set(cacheKey, confirmed, CACHE_TTL_SECONDS);
        return confirmed;
      } catch (err) {
        console.warn("JourneyEndpointSiteResolver.resolveSiteId failed; the requested-corridor relevance enhancement is inactive for this endpoint:", err);
        return null;
      }
    },
  };
}
