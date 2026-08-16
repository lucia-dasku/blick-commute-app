import { Hono } from "hono";
import { z } from "zod";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import { DisruptionEffectSchema } from "../models/disruption.js";
import { JourneyDisruptionContextSchema, JOURNEY_DISRUPTION_CONTEXT_VERSION } from "../models/journeyDisruptionContext.js";
import {
  resolveJourneyDisruptions,
  type JourneyTimeWindow,
  type ResolvedJourneyDisruption,
} from "../domain/disruptionRelevance.js";
import {
  resolveLegScopes,
  resolveExactJourneyOriginStopArea,
  type ExactJourneyOriginStopArea,
  type ResolvedLegScope,
} from "../domain/journeyDisruptionScope.js";
import { resolveSiteStopAreaIds } from "../services/deviationsFilter.js";
import type { DeviationsSnapshotService } from "../services/deviationsSnapshotService.js";
import type { SiteDirectory } from "../services/siteDirectory.js";
import type { StopPointDirectory } from "../services/stopPointDirectory.js";

const RequestSchema = z.object({
  // The PRIMARY journey's own transit legs (WALK legs and legs with no line designation carry no
  // line-scope signal and must never be sent by the caller — see resolveDeviationRelevance's own
  // doc). Empty is valid (nothing to match against) and simply yields no relevant deviations.
  // Still required/read even once `disruptionContext` is supplied (see below): kept exactly as
  // before this feature so an existing Android build's own request-building code needs no change
  // at all beyond additionally attaching `disruptionContext` — never consulted directly once a
  // recognized `disruptionContext` is present and its own richer per-leg resolution succeeds.
  legs: z.array(z.object({ transportMode: z.string().min(1), lineDesignation: z.string().min(1) })),
  // The routine's own SL-Transport-namespace ORIGIN site id (see disruptionRelevance.ts's own
  // "Known limitation" doc for why this remains valuable even now that most of the journey's own
  // stops are independently verifiable). Absent when unavailable — never invented.
  originSiteId: z.number().int().positive().optional(),
  // PRIMARY's own already-fetched Journey Planner notices (journey.disruptionNotices, unchanged)
  // — sent back so this endpoint can perform the FULL combine+dedupe+merge in one authoritative
  // place (see resolveJourneyDisruptions's own doc); Android never merges these itself.
  journeyPlannerNotices: z.array(z.object({ text: z.string(), effect: DisruptionEffectSchema })),
  // Additive (see models/journeyDisruptionContext.ts's own doc): PRIMARY's own structural
  // metadata, retained by Android unchanged from the matching /api/v1/journeys response and sent
  // back verbatim. Absent for an older Android build that predates this feature — see this
  // route's own doc for the resulting legacy fallback.
  disruptionContext: JourneyDisruptionContextSchema.optional(),
  // Additive: PRIMARY's own real travel interval, exactly as already present on the
  // /api/v1/journeys response — enables the temporal-relevance check (see
  // deviationOverlapsJourneyWindow's own doc). Absent (an older client, or PRIMARY not yet fully
  // known for some reason) simply skips that check entirely, exactly as it was always skipped
  // before this feature existed.
  departureTime: z.string().datetime({ offset: true }).optional(),
  arrivalTime: z.string().datetime({ offset: true }).optional(),
});

/**
 * Builds a synthetic, uniform `ResolvedLegScope` for every one of [legs] — [originStopAreaIds]
 * (or an empty set, if unavailable) applied identically to BOTH `accessPoints` and
 * `travelledPath`, both always `"PARTIAL"`. This is deliberately NOT effect-aware (unlike the
 * real `resolveLegScopes`): it exists ONLY to reproduce, byte-for-byte, the exact PARTIAL/
 * origin-only relevance behavior this endpoint had before `disruptionContext`/`StopPointDirectory`
 * existed — see this route's own doc for the two situations that select it (an older Android
 * build's request, and a `StopPointDirectory` failure on an otherwise-rich request). Never used
 * when a real `disruptionContext` resolution succeeds.
 */
function legacyLegScopes(legs: readonly { transportMode: string; lineDesignation: string }[], originStopAreaIds: ReadonlySet<number>): ResolvedLegScope[] {
  const scope = { stopAreaIds: originStopAreaIds, stopPointIds: new Set<number>(), completeness: "PARTIAL" as const };
  return legs.map((leg) => ({ transportMode: leg.transportMode, lineDesignation: leg.lineDesignation, accessPoints: scope, travelledPath: scope }));
}

/**
 * `POST /api/v1/journeys/disruptions` — the single authoritative source of exact-destination
 * disruption relevance. Supplements/merges a specific PRIMARY journey's own Journey Planner
 * `infos` (sent in the request body, unchanged from `/api/v1/journeys`' own
 * `journey.disruptionNotices`) with structurally-matched entries from the SAME shared,
 * already-cached SL Deviations snapshot `/api/v1/disruptions` already reads (see
 * `services/deviationsSnapshotService.ts`) — never a second upstream SL request, never a fresh
 * synchronous snapshot fetch bypassing that cache. All matching and combination logic lives in
 * `domain/disruptionRelevance.ts`'s own `resolveJourneyDisruptions` — this route is a thin HTTP
 * adapter over it (plus the `disruptionContext` -> `ResolvedLegScope[]` resolution step below),
 * never a second place relevance rules could drift from that one.
 *
 * A POST (not GET) specifically because the request body needs to carry an arbitrary-length list
 * of Journey Planner notices, not just a few scalar query parameters — see `RequestSchema`'s own
 * doc for the exact shape.
 *
 * Deliberately a SEPARATE route from `/api/v1/journeys`, not a field baked into that response: a
 * disruption-relevance lookup must never be able to delay or fail the PRIMARY journey update
 * itself (see `RoutineActiveWindowWorker`'s own "primary data first, disruption lookup second"
 * doc on the Android side) — keeping this a genuinely separate HTTP call is what makes that
 * ordering structurally guaranteed rather than merely conventional. `StopPointDirectory`'s own
 * (potentially 8MB-backing) index lookup happens ONLY here, never as part of `/api/v1/journeys`.
 *
 * ## `disruptionContext` resolution, and the legacy fallback
 *
 * When the request carries a `disruptionContext` whose `version` this backend recognizes, its
 * per-leg structural metadata is resolved into real `ResolvedLegScope[]` via
 * `journeyDisruptionScope.ts`'s own `resolveLegScopes` (one batched `StopPointDirectory` lookup
 * for the whole journey, never one per leg or per stop) — this is what lets `resolveJourneyDisruptions`
 * verify the journey's DESTINATION and every transfer/intermediate stop, not merely its origin.
 *
 * Two situations instead use `legacyLegScopes` — a synthetic, origin-only, always-`"PARTIAL"`
 * scope applied uniformly to every leg, reproducing this endpoint's own PRE-`disruptionContext`
 * behavior exactly:
 *
 * 1. `disruptionContext` is absent, or its `version` isn't `JOURNEY_DISRUPTION_CONTEXT_VERSION`
 *    (an older Android build that predates this feature, or a build newer than THIS backend
 *    somehow — never assumed compatible just because it parses).
 * 2. `disruptionContext` IS present and recognized, but `resolveLegScopes` itself throws (the
 *    `StopPointDirectory` could not be loaded at all — no fresh snapshot, no stale fallback, and
 *    the live refresh also failed). This request must still succeed with the pre-existing,
 *    strictly-worse-but-functional PARTIAL behavior rather than failing outright — see
 *    `stopPointDirectory.ts`'s own `resolveMany` doc for why that failure mode is rare (a stale
 *    index is preferred over none at all) and `AppError`'s own narrow catch below for why only
 *    THAT specific, already-controlled failure mode is caught, never an unexpected bug elsewhere
 *    in this route silently downgraded to "PARTIAL" instead of surfacing as a real 5xx.
 *
 * Response: `{ fetchedAt, disruptions: ResolvedJourneyDisruption[] }` — the fully resolved,
 * deduplicated, already-merged result. Android performs no relevance inference of its own; it
 * renders `relevance`/`effect`/`headline`/`details`/`matchedLineDesignations` exactly as returned.
 */
export function createJourneyDisruptionsRoute(
  snapshotService: DeviationsSnapshotService,
  siteDirectory: SiteDirectory,
  stopPointDirectory: StopPointDirectory,
) {
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
    const { legs, originSiteId, journeyPlannerNotices, disruptionContext, departureTime, arrivalTime } = parsed.data;

    // Two deliberately different origin views, computed from the SAME sites fetch (no extra
    // upstream/cache call): `originStopAreaIds` is the pre-existing BROAD per-site membership
    // (still used only by legacyLegScopes below, unchanged from before this correction);
    // `originFallback` is the NARROW, exact-destination-only view resolveLegScopes actually
    // consumes — see resolveExactJourneyOriginStopArea's own doc for why a multi-mode site (e.g.
    // Slussen: metro StopArea 1011 + bus StopArea 44000) must never be collapsed into one broad
    // set for ACCESS_POINTS purposes.
    let originStopAreaIds = new Set<number>();
    let originFallback: ExactJourneyOriginStopArea | undefined;
    if (originSiteId != null) {
      const sites = await siteDirectory.getAllSites();
      originStopAreaIds = resolveSiteStopAreaIds(originSiteId, sites);
      originFallback = resolveExactJourneyOriginStopArea(originSiteId, sites);
    }

    let legScopes: ResolvedLegScope[];
    if (disruptionContext != null && disruptionContext.version === JOURNEY_DISRUPTION_CONTEXT_VERSION) {
      try {
        legScopes = await resolveLegScopes(disruptionContext, stopPointDirectory, originFallback);
      } catch (err) {
        if (!(err instanceof AppError)) throw err;
        legScopes = legacyLegScopes(legs, originStopAreaIds);
      }
    } else {
      legScopes = legacyLegScopes(legs, originStopAreaIds);
    }

    const journeyWindow: JourneyTimeWindow | null = departureTime != null && arrivalTime != null ? { departureTime, arrivalTime } : null;

    const snapshot = await snapshotService.getSnapshot();
    const disruptions: ResolvedJourneyDisruption[] = resolveJourneyDisruptions(
      journeyPlannerNotices,
      snapshot.deviations,
      legScopes,
      journeyWindow,
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
