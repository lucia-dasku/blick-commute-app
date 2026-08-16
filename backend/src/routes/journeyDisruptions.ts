import { Hono } from "hono";
import { z } from "zod";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import { DisruptionEffectSchema } from "../models/disruption.js";
import { resolveJourneyDisruptions, type ResolvedJourneyDisruption, type VerifiedJourneyStopScope } from "../domain/disruptionRelevance.js";
import { resolveSiteStopAreaIds } from "../services/deviationsFilter.js";
import type { DeviationsSnapshotService } from "../services/deviationsSnapshotService.js";
import type { SiteDirectory } from "../services/siteDirectory.js";

const RequestSchema = z.object({
  // The PRIMARY journey's own transit legs (WALK legs and legs with no line designation carry no
  // line-scope signal and must never be sent by the caller — see resolveDeviationRelevance's own
  // doc). Empty is valid (nothing to match against) and simply yields no relevant deviations.
  legs: z.array(z.object({ transportMode: z.string().min(1), lineDesignation: z.string().min(1) })),
  // The routine's own SL-Transport-namespace ORIGIN site id (see disruptionRelevance.ts's own
  // "Known limitation" doc for why only the origin, never the destination, can be supplied this
  // way today). Absent when unavailable — never invented.
  originSiteId: z.number().int().positive().optional(),
  // PRIMARY's own already-fetched Journey Planner notices (journey.disruptionNotices, unchanged)
  // — sent back so this endpoint can perform the FULL combine+dedupe+merge in one authoritative
  // place (see resolveJourneyDisruptions's own doc); Android never merges these itself.
  journeyPlannerNotices: z.array(z.object({ text: z.string(), effect: DisruptionEffectSchema })),
});

/**
 * `POST /api/v1/journeys/disruptions` — the single authoritative source of exact-destination
 * disruption relevance. Supplements/merges a specific PRIMARY journey's own Journey Planner
 * `infos` (sent in the request body, unchanged from `/api/v1/journeys`' own
 * `journey.disruptionNotices`) with structurally-matched entries from the SAME shared,
 * already-cached SL Deviations snapshot `/api/v1/disruptions` already reads (see
 * `services/deviationsSnapshotService.ts`) — never a second upstream SL request, never a fresh
 * synchronous snapshot fetch bypassing that cache. All matching and combination logic lives in
 * `domain/disruptionRelevance.ts`'s own `resolveJourneyDisruptions` — this route is a thin HTTP
 * adapter over it, never a second place relevance rules could drift from that one.
 *
 * A POST (not GET) specifically because the request body needs to carry an arbitrary-length list
 * of Journey Planner notices, not just a few scalar query parameters — see `RequestSchema`'s own
 * doc for the exact shape.
 *
 * Deliberately a SEPARATE route from `/api/v1/journeys`, not a field baked into that response: a
 * disruption-relevance lookup must never be able to delay or fail the PRIMARY journey update
 * itself (see `RoutineActiveWindowWorker`'s own "primary data first, disruption lookup second"
 * doc on the Android side) — keeping this a genuinely separate HTTP call is what makes that
 * ordering structurally guaranteed rather than merely conventional.
 *
 * Response: `{ fetchedAt, disruptions: ResolvedJourneyDisruption[] }` — the fully resolved,
 * deduplicated, already-merged result. Android performs no relevance inference of its own; it
 * renders `relevance`/`effect`/`headline`/`details`/`matchedLineDesignations` exactly as returned.
 */
export function createJourneyDisruptionsRoute(snapshotService: DeviationsSnapshotService, siteDirectory: SiteDirectory) {
  const route = new Hono();

  route.post("/", async (c) => {
    let raw: unknown;
    try {
      raw = await c.req.json();
    } catch {
      throw new AppError("VALIDATION_ERROR", "Request body must be valid JSON");
    }
    const parsed = RequestSchema.safeParse(raw);
    if (!parsed.success) throw new AppError("VALIDATION_ERROR", "Invalid journey disruption relevance request");
    const { legs, originSiteId, journeyPlannerNotices } = parsed.data;

    // PARTIAL, never COMPLETE: Blick only has a verified SL-Transport-namespace stop-area mapping
    // for the routine's own ORIGIN today (see disruptionRelevance.ts's own "Known limitation"
    // doc) -- the destination and any intermediate stop are not represented here at all, so
    // resolveDeviationRelevance must never treat a non-intersection against this scope as a
    // disproof of relevance for the whole journey.
    let journeyStopScope: VerifiedJourneyStopScope | null = null;
    if (originSiteId != null) {
      const sites = await siteDirectory.getAllSites();
      journeyStopScope = { stopAreaIds: resolveSiteStopAreaIds(originSiteId, sites), completeness: "PARTIAL" };
    }

    const snapshot = await snapshotService.getSnapshot();
    const disruptions: ResolvedJourneyDisruption[] = resolveJourneyDisruptions(
      journeyPlannerNotices,
      snapshot.deviations,
      legs,
      journeyStopScope,
    );

    // Mirrors /api/v1/journeys' own short edge cache -- this is derived from the same
    // near-real-time-relevant cached snapshot, not a resource that benefits from a longer TTL.
    // (A POST response is not shared-cacheable by intermediaries regardless, but the app's own
    // OkHttp cache still respects Cache-Control for direct client reuse within the window.)
    c.header("Cache-Control", "private, max-age=30");
    return c.json(successEnvelope({ fetchedAt: snapshot.fetchedAt, disruptions }));
  });

  return route;
}
