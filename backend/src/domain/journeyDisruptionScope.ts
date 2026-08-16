import type { DisruptionEffect } from "../models/disruption.js";
import type { JourneyDisruptionContext, JourneyDisruptionContextLeg } from "../models/journeyDisruptionContext.js";
import type { PatternPointGid, StopPointDirectory, StopPointResolution } from "../services/stopPointDirectory.js";
import type { Site } from "../models/site.js";

/**
 * Which portion of the network a disruption's own `effect` can plausibly affect — see
 * `scopePolicyForEffect`'s own doc for the source-of-truth mapping. `ACCESS_POINTS`: only where
 * the passenger actually interacts with a station/stop (boards, alights, or transfers) — an
 * ordinary intermediate stop the passenger stays onboard through is NOT an access point.
 * `TRAVELLED_PATH`: anywhere along the segment actually travelled, boarding through alighting,
 * including every intermediate stop.
 */
export type DisruptionScopeKind = "ACCESS_POINTS" | "TRAVELLED_PATH";

/**
 * Effect -> scope policy, the ONE place this decision is made — `domain/disruptionRelevance.ts`
 * reads this, never re-deriving it, and neither Android nor any route handler duplicates it (see
 * this codebase's own "no scattered relevance rules" convention, already established for
 * `resolveDeviationRelevance`).
 *
 * `ACCESS_POINTS` — the passenger has to physically interact with the affected infrastructure for
 * this to matter: a broken lift/escalator (`ACCESSIBILITY_ISSUE`), a closed entrance or other
 * station-access problem (`STATION_ACCESS`), or a moved/changed stop location (`STOP_CHANGE`).
 * None of these affect a rider who stays on board through an intermediate stop.
 *
 * `TRAVELLED_PATH` — everything else: a vehicle-level or line-level service problem
 * (`DELAYS`, `NO_SERVICE`, `REDUCED_SERVICE`, `ROUTE_CHANGE`, `REPLACEMENT_SERVICE`, and the
 * conservative catch-all `DISRUPTION`) affects every rider on the affected segment regardless of
 * which stop they personally board/alight at.
 *
 * Deliberately a switch with NO `default` branch: `DisruptionEffect` is a closed 9-value union
 * (`models/disruption.ts`), so TypeScript itself refuses to compile this function once every
 * branch returns and the switch is exhaustive — a FUTURE 10th effect added to that union without
 * updating this function is a compile error, not a silently-wrong runtime default. See
 * `tests/journeyDisruptionScope.test.ts` for the accompanying explicit exhaustiveness test.
 */
export function scopePolicyForEffect(effect: DisruptionEffect): DisruptionScopeKind {
  switch (effect) {
    case "ACCESSIBILITY_ISSUE":
    case "STATION_ACCESS":
    case "STOP_CHANGE":
      return "ACCESS_POINTS";
    case "DELAYS":
    case "NO_SERVICE":
    case "REDUCED_SERVICE":
    case "ROUTE_CHANGE":
    case "REPLACEMENT_SERVICE":
    case "DISRUPTION":
      return "TRAVELLED_PATH";
  }
}

/**
 * The verified stop identity Blick can vouch for within one {@link DisruptionScopeKind} of one
 * PRIMARY transit leg — see {@link ResolvedLegScope}'s own doc for how the two kinds
 * (`accessPoints`/`travelledPath`) are built per leg, and `resolveDeviationRelevance`'s own doc
 * (`domain/disruptionRelevance.ts`) for exactly how `completeness` changes what a
 * non-intersection is allowed to mean.
 */
export interface ScopeSet {
  stopAreaIds: ReadonlySet<number>;
  stopPointIds: ReadonlySet<number>;
  /** `"COMPLETE"`: this set genuinely contains every stop this scope kind should cover for this
   * leg — a lack of intersection against a deviation's own scope is a genuine disproof.
   * `"PARTIAL"`: at least one relevant point could not be verified (unresolved/ambiguous
   * identity, or Journey Planner itself didn't supply enough structure) — a lack of intersection
   * proves nothing here; only an ACTUAL intersection is ever usable as evidence. */
  completeness: "PARTIAL" | "COMPLETE";
}

/** One PRIMARY transit leg's own resolved scopes — see `resolveLegScopes`'s own doc for how
 * these are built, and `disruptionRelevance.ts`'s own doc for how a deviation is matched against
 * the RIGHT one of the two (never both, never neither) via `scopePolicyForEffect`. Kept
 * PER-LEG, never merged into one journey-wide set, specifically so a deviation scoped to one
 * line is only ever compared against the stops THAT line's own leg actually uses — never a
 * stop from a structurally unrelated leg elsewhere in the same transfer journey. */
export interface ResolvedLegScope {
  transportMode: string;
  lineDesignation: string;
  accessPoints: ScopeSet;
  travelledPath: ScopeSet;
}

const EMPTY_SCOPE: ScopeSet = { stopAreaIds: new Set(), stopPointIds: new Set(), completeness: "PARTIAL" };

/**
 * The routine's own origin site, narrowed to exact-destination ACCESS_POINTS precision — see
 * `resolveExactJourneyOriginStopArea`'s own doc for why this is a DIFFERENT, narrower question
 * than `deviationsFilter.ts`'s own `resolveSiteStopAreaIds` (which stays unchanged, still used
 * by `LINE_DIRECTION` filtering and by `routes/journeyDisruptions.ts`'s own legacy fallback).
 *
 * - `"RESOLVED"`: the site has EXACTLY one child StopArea — safe to treat as the journey's own
 *   boarding StopArea when the exact platform mapping itself is unavailable.
 * - `"AMBIGUOUS"`: the site has MORE than one child StopArea (a multi-mode site, e.g. Slussen's
 *   metro StopArea `1011` and bus terminal StopArea `44000`) — which one the passenger actually
 *   boards at cannot be inferred from the site alone, so this is treated exactly like
 *   `"UNRESOLVED"` by every caller: it rescues nothing, and the affected scope stays `PARTIAL`.
 * - `"UNRESOLVED"`: the site is unknown, or has no child StopArea at all.
 */
export type ExactJourneyOriginStopArea =
  | { status: "RESOLVED"; stopAreaId: number }
  | { status: "AMBIGUOUS"; stopAreaIds: readonly number[] }
  | { status: "UNRESOLVED" };

/**
 * Narrow, exact-destination-only counterpart to `deviationsFilter.ts`'s own broad
 * `resolveSiteStopAreaIds` (`{siteId} ∪ site.stopAreaIds`, suitable for `LINE_DIRECTION`'s own
 * per-site deviation filtering, where "does this deviation concern this site AT ALL" is exactly
 * the right question). Exact-destination ACCESS_POINTS relevance asks a DIFFERENT, stricter
 * question — "which SPECIFIC StopArea does the passenger actually board at" — for which that
 * broad set is unsafe: at a multi-mode site, unioning every child StopArea would let a
 * bus-terminal-only disruption falsely match a metro-only journey (or vice versa) merely because
 * both happen to share a parent site.
 *
 * Deliberately built from `site.stopAreaIds` ONLY — never `site.siteId` itself. A site id and a
 * StopArea id are different entities that merely share one numeric namespace (see
 * `docs/api-contract.md`, "Verified namespace result"); inserting `site.siteId` into a StopArea
 * set would be exactly the kind of same-looking-integer conflation this function exists to avoid
 * (see `buildAccessPoints`'s own doc for where this result is actually consumed).
 */
export function resolveExactJourneyOriginStopArea(siteId: number, sites: readonly Site[]): ExactJourneyOriginStopArea {
  const site = sites.find((s) => s.siteId === siteId);
  const stopAreaIds = site?.stopAreaIds ?? [];
  if (stopAreaIds.length === 0) return { status: "UNRESOLVED" };
  if (stopAreaIds.length === 1) return { status: "RESOLVED", stopAreaId: stopAreaIds[0]! };
  return { status: "AMBIGUOUS", stopAreaIds };
}

/** A resolved point's own stop identity, forwarded from `StopPointResolution` — `stopPointId` is
 * absent for a `"STOP_AREA_ONLY"` resolution (see that status's own doc in
 * `stopPointDirectory.ts`): the StopArea is still genuine, verified evidence (usable for a
 * `scope.stop_areas` intersection), but no single StopPoint id may be claimed proven, so it must
 * never be added to a `ScopeSet.stopPointIds` set a `scope.stop_points` intersection could match
 * against. */
function resolvedStopArea(resolution: StopPointResolution | undefined): { stopAreaId: number; stopPointId?: number } | undefined {
  if (resolution?.status === "RESOLVED") return { stopAreaId: resolution.stopAreaId, stopPointId: resolution.stopPointId };
  if (resolution?.status === "STOP_AREA_ONLY") return { stopAreaId: resolution.stopAreaId };
  return undefined;
}

/** Builds one leg's own ACCESS_POINTS scope: boarding + alighting only, deliberately never
 * anything from `stopPatternPointGids`' own intermediate entries — see `DisruptionScopeKind`'s
 * own doc for why an intermediate, stayed-onboard stop is never an access point.
 *
 * [originFallback] (the journey's own first transit leg only — see `resolveLegScopes`'s own doc)
 * is consulted ONLY when the boarding side's own exact platform resolution is itself absent
 * (`leg.boardingPatternPointGid` missing, or `StopPointDirectory` returned anything other than
 * `"RESOLVED"`/`"STOP_AREA_ONLY"` for it) — an exact resolution is always authoritative and is
 * NEVER additionally broadened by the coarser site fallback, at a single-mode OR multi-mode site
 * alike. When the exact side genuinely is unresolved, the fallback itself only rescues it when
 * `originFallback.status === "RESOLVED"` (the site has exactly one child StopArea) — an
 * `"AMBIGUOUS"` site (e.g. Slussen: metro StopArea `1011` + bus StopArea `44000`) contributes
 * NOTHING, leaving the boarding side genuinely unresolved rather than guessing between them; see
 * this module's own `resolveExactJourneyOriginStopArea` doc. Completeness requires BOTH sides to
 * have at least one verified StopArea, from either the exact bridge or (boarding only) the
 * single-StopArea fallback — never from the fallback's own StopPoint identity, since the site
 * fallback is StopArea-only by construction. */
function buildAccessPoints(
  leg: JourneyDisruptionContextLeg,
  resolutions: ReadonlyMap<PatternPointGid, StopPointResolution>,
  originFallback: ExactJourneyOriginStopArea | undefined,
): ScopeSet {
  const boarding = leg.boardingPatternPointGid != null ? resolvedStopArea(resolutions.get(leg.boardingPatternPointGid)) : undefined;
  const alighting = leg.alightingPatternPointGid != null ? resolvedStopArea(resolutions.get(leg.alightingPatternPointGid)) : undefined;

  const boardingStopAreaIds = new Set<number>();
  const stopPointIds = new Set<number>();
  if (boarding) {
    // Exact platform resolution succeeded -- authoritative, and never unioned with the site
    // fallback below, however many OTHER StopAreas that site might also have.
    boardingStopAreaIds.add(boarding.stopAreaId);
    if (boarding.stopPointId != null) stopPointIds.add(boarding.stopPointId);
  } else if (originFallback?.status === "RESOLVED") {
    // Exact resolution is genuinely absent -- rescued ONLY because the site itself narrows to
    // exactly one StopArea. Never contributes a StopPoint id: the site fallback only ever proves
    // station-level identity.
    boardingStopAreaIds.add(originFallback.stopAreaId);
  }

  const alightingStopAreaIds = new Set<number>();
  if (alighting) {
    alightingStopAreaIds.add(alighting.stopAreaId);
    if (alighting.stopPointId != null) stopPointIds.add(alighting.stopPointId);
  }

  const stopAreaIds = new Set<number>([...boardingStopAreaIds, ...alightingStopAreaIds]);
  const completeness: ScopeSet["completeness"] = boardingStopAreaIds.size > 0 && alightingStopAreaIds.size > 0 ? "COMPLETE" : "PARTIAL";
  return { stopAreaIds, stopPointIds, completeness };
}

/** Builds one leg's own TRAVELLED_PATH scope from every entry in `stopPatternPointGids` (boarding
 * through alighting, including every intermediate stop) — `"COMPLETE"` only when Journey Planner
 * itself supplied a full stop sequence for this leg AND every single entry in it resolved to at
 * least StopArea precision (see `JourneyDisruptionContextLeg.stopSequenceComplete`'s own doc for
 * the first half of that; this function additionally requires the SECOND half — that
 * `StopPointDirectory` itself resolved each one — since a structurally complete sequence can
 * still contain an id the directory doesn't (yet) recognize, e.g. a newly opened stop).
 *
 * Deliberately takes NO origin-site fallback parameter at all: the routine's own origin site is
 * specifically about rescuing the FIRST leg's own BOARDING access point when its exact platform
 * mapping fails (see `buildAccessPoints`'s own doc) — it is never a substitute for, or addition
 * to, the travelled path's own `stopSequence`-derived evidence, at a single-mode or multi-mode
 * site alike. */
function buildTravelledPath(leg: JourneyDisruptionContextLeg, resolutions: ReadonlyMap<PatternPointGid, StopPointResolution>): ScopeSet {
  const stopAreaIds = new Set<number>();
  const stopPointIds = new Set<number>();
  let everyStopResolved = true;
  for (const gid of leg.stopPatternPointGids) {
    const resolved = resolvedStopArea(resolutions.get(gid));
    if (resolved) {
      stopAreaIds.add(resolved.stopAreaId);
      if (resolved.stopPointId != null) stopPointIds.add(resolved.stopPointId);
    } else {
      everyStopResolved = false;
    }
  }
  const completeness: ScopeSet["completeness"] = leg.stopSequenceComplete && everyStopResolved ? "COMPLETE" : "PARTIAL";
  return { stopAreaIds, stopPointIds, completeness };
}

/**
 * Resolves every PRIMARY transit leg's own {@link ResolvedLegScope} in ONE batched
 * `StopPointDirectory.resolveMany` call (never one directory lookup per leg or per stop) — the
 * single production entry point `routes/journeyDisruptions.ts` calls once per request, after
 * receiving Android's own unchanged `disruptionContext`.
 *
 * A WALK leg (no `lineDesignation`) is skipped entirely — it carries no line disruption scope of
 * its own (see `DisruptionScopeKind`'s own top-level doc), and its own boarding/alighting points
 * are already captured as the ADJACENT transit legs' own alighting/boarding (confirmed live: a
 * walk transfer's origin/destination are the exact same `PatternPointGid`s as the transit legs on
 * either side of it — see `stopPointDirectory.ts`'s own evidence doc). A leg whose own
 * `boardingPatternPointGid`/`alightingPatternPointGid`/`stopPatternPointGids` entries don't
 * resolve simply contributes less evidence (degrading that leg's own `completeness` to
 * `"PARTIAL"`) — never an error, never a reason to drop the leg from the result.
 *
 * [originFallback] is the routine's own origin site, ALREADY narrowed to
 * `ExactJourneyOriginStopArea` precision by the caller (`resolveExactJourneyOriginStopArea` —
 * see that function's own doc for why this is a deliberately different, stricter question than
 * `resolveSiteStopAreaIds`'s own broad per-site membership). Consulted ONLY for the JOURNEY'S OWN
 * FIRST transit leg's own `accessPoints` (never any other leg's, and never `travelledPath` at
 * all — see `buildAccessPoints`'s/`buildTravelledPath`'s own docs), and even then only as a
 * fallback for a boarding side whose OWN exact platform resolution is absent — never unioned
 * alongside an already-successful exact resolution, at a single-mode or multi-mode origin site
 * alike. This is why a journey's own boarding point can still resolve on the rare occasion
 * Journey Planner echoes it as a coarser `type: "stop"` node instead of a specific platform (see
 * `normalizeJourney.ts`'s own `platformPatternPointGid` doc for that observed quirk), without a
 * multi-mode site's OTHER StopAreas ever leaking into this journey's own scope.
 */
export async function resolveLegScopes(
  context: JourneyDisruptionContext,
  directory: StopPointDirectory,
  originFallback?: ExactJourneyOriginStopArea,
): Promise<ResolvedLegScope[]> {
  const transitLegs = context.legs.filter((leg): leg is JourneyDisruptionContextLeg & { lineDesignation: string } => leg.lineDesignation != null);
  if (transitLegs.length === 0) return [];

  const allGids = new Set<PatternPointGid>();
  for (const leg of transitLegs) {
    if (leg.boardingPatternPointGid != null) allGids.add(leg.boardingPatternPointGid);
    if (leg.alightingPatternPointGid != null) allGids.add(leg.alightingPatternPointGid);
    for (const gid of leg.stopPatternPointGids) allGids.add(gid);
  }
  const resolutions = allGids.size > 0 ? await directory.resolveMany([...allGids]) : new Map<PatternPointGid, StopPointResolution>();

  return transitLegs.map((leg, index) => ({
    transportMode: leg.transportMode,
    lineDesignation: leg.lineDesignation,
    accessPoints: buildAccessPoints(leg, resolutions, index === 0 ? originFallback : undefined),
    travelledPath: buildTravelledPath(leg, resolutions),
  }));
}

/** Re-exported only so `disruptionRelevance.ts`/tests never need an empty-set literal of their
 * own for "no evidence at all" (e.g. a stop-only deviation compared against a WALK-only, all-
 * skipped journey). Always `completeness: "PARTIAL"` — see {@link ScopeSet}'s own doc for why an
 * empty set can never be treated as a `"COMPLETE"` disproof. */
export function emptyScopeSet(): ScopeSet {
  return EMPTY_SCOPE;
}
