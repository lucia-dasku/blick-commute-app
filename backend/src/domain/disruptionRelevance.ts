import type { RawDeviation } from "../services/upstreamTypes.js";
import type { DisruptionEffect } from "../models/disruption.js";
import type { Site } from "../models/site.js";
import { normalizeDisruption } from "../normalize/normalizeDisruption.js";
import { scopePolicyForEffect, type ResolvedLegScope, type ScopeSet } from "./journeyDisruptionScope.js";
import { parseStructuredDisruptionSegment, type ParsedSegmentCandidate } from "./journeySegmentParser.js";
import { actualLegEdgesKey, isRequestedCorridorTrusted, type ActualLineEdgeEvidence } from "./requestedCorridor.js";
import { edgeSetsIntersect, type StopAreaEdgeKey } from "./lineTopologyGraph.js";
import type { LineSegmentResolution, LineTopologyDirectory } from "../services/lineTopologyDirectory.js";

/** A Journey Planner disruption notice as already produced by `normalizeJourney.ts`'s own
 * `disruptionNotices` — the input side of this module's own combination step (see
 * `resolveJourneyDisruptions`'s own doc). Deliberately just `{ text, effect }`: this module never
 * re-classifies it, and never invents a `details` body for it (Journey Planner notices have none). */
export interface JourneyPlannerNoticeInput {
  text: string;
  effect: DisruptionEffect;
}

/**
 * How confidently a {@link ResolvedJourneyDisruption} is known to affect the current journey —
 * see `resolveDeviationRelevance`'s own doc for exactly which structured evidence produces which
 * value. A third, implicit state — "UNRELATED" — is deliberately NOT a member of this type: an
 * unrelated deviation is filtered out entirely before ever becoming a
 * {@link ResolvedJourneyDisruption}, never represented as a value a caller has to branch on.
 *
 * - `CONFIRMED`: structured evidence proves this disruption affects the journey (or the specific
 *   segment/stop it uses) — either a Journey Planner notice was attached directly to PRIMARY (the
 *   strongest possible evidence: Journey Planner itself already scoped it to this exact journey),
 *   or an SL Deviation's own structured stop scope (`scope.stop_areas`/`scope.stop_points`)
 *   genuinely intersects the journey's own resolved scope of the RIGHT kind for that deviation's
 *   own `effect` (see `journeyDisruptionScope.ts`'s own `scopePolicyForEffect`). Blick may show
 *   the disruption's own real classified `effect` (e.g. "No service") as definitely true for this
 *   journey.
 * - `LINE_RELEVANT`: an SL Deviation's line/mode scope matches a PRIMARY leg, but the currently
 *   available structured stop evidence cannot prove (or disprove) that the affected segment/stop
 *   intersects this exact journey's own resolved scope — no `scope.stop_areas`/`scope.stop_points`
 *   at all (SL itself did not scope it to specific stops), or the journey's own relevant scope is
 *   only `"PARTIAL"` and does not itself intersect. Blick must NOT present the real classified
 *   `effect` as definitely true for this journey's own segment; only a conservative, line-scoped
 *   warning is appropriate (see `matchedLineDesignations`). Never produced for a deviation with NO
 *   line evidence at all — see rule 2's own "stop-only" branch below for why that case can only
 *   ever reach `CONFIRMED` or UNRELATED, never this cautious middle state.
 */
export type DisruptionRelevance = "CONFIRMED" | "LINE_RELEVANT";

/** Which of the two sources produced a {@link ResolvedJourneyDisruption} — see that type's own
 * doc for how the two are combined and deduplicated. */
export type DisruptionSource = "JOURNEY_PLANNER" | "SL_DEVIATIONS";

/**
 * The backend's single, authoritative output for one currently-relevant exact-destination
 * disruption — the ONLY shape Android ever receives from
 * `GET /api/v1/journeys/disruptions` (`routes/journeyDisruptions.ts`). Android performs no
 * relevance inference of its own: it renders `relevance`/`effect`/`headline`/`details` exactly as
 * resolved here, never re-deriving them from raw deviation/notice fields (see this module's own
 * top-level doc on why that centralization matters).
 */
export interface ResolvedJourneyDisruption {
  /** The SL Deviations `deviation_case_id`, when {@link source} is `SL_DEVIATIONS`. Always absent
   * for a `JOURNEY_PLANNER`-sourced entry, which carries no stable upstream id — see
   * `resolveJourneyDisruptions`'s own doc on why cross-source deduplication therefore falls back
   * to text equality specifically for that side. */
  id?: string;
  /** SL's own unmodified text — a Journey Planner notice's own `text`, or an SL Deviation's own
   * `message.header`. Never translated, summarized, or replaced by the classification label. */
  headline: string;
  /** An SL Deviation's own `message.details` body. Always absent for a `JOURNEY_PLANNER`-sourced
   * entry, which has no separate longer body the way an SL Deviations message does. */
  details?: string;
  effect: DisruptionEffect;
  relevance: DisruptionRelevance;
  source: DisruptionSource;
  /** The distinct PRIMARY leg line designation(s) whose line/mode matched this SL Deviation's own
   * `scope.lines` — always empty for a `JOURNEY_PLANNER`-sourced entry (never matched by line at
   * all; already journey-scoped by Journey Planner itself), and always empty for an
   * `SL_DEVIATIONS` entry resolved purely from STOP evidence with no line scope at all (see rule
   * 2's own "stop-only" branch below — this field specifically means "line evidence", never
   * repurposed to mean "some leg's scope happened to intersect"). Populated for BOTH `CONFIRMED`
   * and `LINE_RELEVANT` line-matched `SL_DEVIATIONS` entries — a caller that needs to build a
   * conservative, line-scoped presentation (see `LINE_RELEVANT`'s own doc) reads this field
   * directly rather than re-deriving it from `headline`'s own free text. */
  matchedLineDesignations: string[];
}

/** PRIMARY's own real travel interval — `departureTime`/`arrivalTime` exactly as already present
 * on the `/api/v1/journeys` response, sent back unchanged (see `routes/journeyDisruptions.ts`'s
 * own request schema). `null` when the caller didn't supply one (an older Android build) — see
 * {@link deviationOverlapsJourneyWindow}'s own doc for how that degrades safely rather than
 * failing anything. */
export interface JourneyTimeWindow {
  departureTime: string;
  arrivalTime: string;
}

/**
 * Step A of `resolveDeviationRelevance` — temporal relevance, evaluated before any line/stop
 * matching and independently of it. A `null` [window] (no `departureTime`/`arrivalTime` supplied
 * — see {@link JourneyTimeWindow}'s own doc) always overlaps: temporal filtering is a pure
 * ADDITION on top of the pre-existing line/stop matching, never a new way for an older client to
 * lose disruption coverage it already had.
 *
 * A deviation whose `publish.upto` is before the journey's own `departureTime` has already
 * ended by the time the passenger would even start travelling — irrelevant. A deviation whose
 * `publish.from` is after the journey's own `arrivalTime` hasn't started yet by the time the
 * passenger will already have finished travelling — also irrelevant (this is what keeps a
 * `future=true` snapshot entry, published for later today or beyond, from being shown against a
 * journey happening right now). An absent `publish.from`/`publish.upto` is open-ended in that
 * direction, exactly as `deviationsFilter.ts`'s own `matchesDeviationsQuery` already treats it for
 * `LINE_DIRECTION` — this function intentionally mirrors that same open-ended convention rather
 * than inventing a second one, without sharing any code with (or changing the behavior of) that
 * `LINE_DIRECTION`-only function.
 */
export function deviationOverlapsJourneyWindow(deviation: RawDeviation, window: JourneyTimeWindow | null): boolean {
  if (window == null) return true;
  const journeyStartMs = new Date(window.departureTime).getTime();
  const journeyEndMs = new Date(window.arrivalTime).getTime();
  const publishFromMs = deviation.publish?.from ? new Date(deviation.publish.from).getTime() : null;
  const publishUptoMs = deviation.publish?.upto ? new Date(deviation.publish.upto).getTime() : null;

  if (publishUptoMs != null && publishUptoMs < journeyStartMs) return false;
  if (publishFromMs != null && publishFromMs > journeyEndMs) return false;
  return true;
}

function scopeSetFor(legScope: ResolvedLegScope, kind: ReturnType<typeof scopePolicyForEffect>): ScopeSet {
  return kind === "ACCESS_POINTS" ? legScope.accessPoints : legScope.travelledPath;
}

function unionScope(scopes: readonly ScopeSet[]): ScopeSet {
  const stopAreaIds = new Set<number>();
  const stopPointIds = new Set<number>();
  let completeness: ScopeSet["completeness"] = "COMPLETE";
  for (const scope of scopes) {
    for (const id of scope.stopAreaIds) stopAreaIds.add(id);
    for (const id of scope.stopPointIds) stopPointIds.add(id);
    if (scope.completeness === "PARTIAL") completeness = "PARTIAL";
  }
  return { stopAreaIds, stopPointIds, completeness };
}

function intersects(a: ReadonlySet<number>, b: readonly number[]): boolean {
  return b.some((id) => a.has(id));
}

/**
 * Resolves one SL Deviation's relevance against PRIMARY's own [legScopes] (see
 * `journeyDisruptionScope.ts`'s own `resolveLegScopes`) and [journeyWindow] — see
 * {@link resolveJourneyDisruptions}'s own doc for the full picture. Returns `null` for UNRELATED
 * (see {@link DisruptionRelevance}'s own doc for why that is not a value of the type itself).
 * [effect] is [deviation]'s own already-classified effect (`normalizeDisruption`'s own output —
 * the SAME classification `/api/v1/disruptions` uses), passed in rather than re-derived here so
 * `resolveJourneyDisruptions` classifies each deviation exactly once regardless of how many times
 * its relevance is checked.
 *
 * This exists because SL Journey Planner's own `infos` text is NOT a reliable disruption source on
 * its own: Journey Planner can silently reroute a journey around a disruption (e.g. terminating a
 * metro line short and continuing on foot/another line) without attaching any notice text to the
 * resulting legs at all — confirmed live for Akalla -> Kungsträdgården, where PRIMARY uses Metro 11
 * and every leg's own `infos` is empty even though SL Deviations has an active `NO_SERVICE`
 * deviation for exactly that corridor.
 *
 * Rules, in order:
 *
 * 1. **Temporal**: [deviation] does not overlap [journeyWindow] (see
 *    {@link deviationOverlapsJourneyWindow}) -> `null` (UNRELATED), before anything else below is
 *    even considered.
 * 2. **No line evidence at all** (`scope.lines` empty/absent): this deviation can ONLY ever
 *    resolve via STOP evidence — it can reach `CONFIRMED` or UNRELATED, but NEVER
 *    `LINE_RELEVANT` (that state specifically means "a line matched, but the stop is uncertain";
 *    manufacturing it from stop-only evidence with no line signal at all would be exactly the
 *    "random network-wide warning" this rule exists to avoid). If `scope.stop_areas` AND
 *    `scope.stop_points` are BOTH also empty -> `null` (UNRELATED): there is nothing structured
 *    to go on at all, and this module never falls back to free-text parsing. Otherwise, compare
 *    the deviation's own stop scope against the UNION of every transit leg's own resolved scope
 *    of the kind {@link scopePolicyForEffect} selects for [effect] (there is no line to narrow
 *    the comparison to one specific leg, so every leg is considered) — an intersection ->
 *    `CONFIRMED`; no intersection while that union is `"COMPLETE"` -> `null` (a genuine disproof);
 *    no intersection while `"PARTIAL"` -> `null` too (fails closed, per the rule's own opening
 *    sentence — NOT `LINE_RELEVANT`).
 * 3. **Line evidence present**: relevant only if at least one leg in [legScopes] shares BOTH the
 *    exact transport mode AND the exact line designation with a `scope.lines` entry — never mode
 *    alone, never designation alone, and never textual/fuzzy comparison. No match -> `null`
 *    (UNRELATED). This is what keeps Slussen -> Liljeholmen (Metro 13/14) correctly unaffected by
 *    an unrelated Bus 401 delay at the same station: sharing a station is never sharing a line.
 * 4. Given a line match and `scope.stop_areas`/`scope.stop_points` BOTH empty -> `LINE_RELEVANT`.
 *    SL itself did not scope this deviation to specific stops, so Blick must not invent a
 *    stricter restriction SL never provided — but it must also not claim the specific effect is
 *    proven for this exact journey's own segment merely because the line matches. This is exactly
 *    the confirmed Akalla case: `NO_SERVICE`, `affectedLines` = Metro 10 + 11, no stop scope at
 *    all — with only line-level evidence available, `LINE_RELEVANT` (not `CONFIRMED`) is the
 *    honest classification.
 * 5. Given a line match and a non-empty stop scope: compare it against the UNION of only the
 *    MATCHED legs' own resolved scope of the kind {@link scopePolicyForEffect} selects for
 *    [effect] — an exact `stop_areas` OR `stop_points` intersection -> `CONFIRMED` (direct
 *    structural proof, regardless of completeness); no intersection while that union is
 *    `"COMPLETE"` -> `null` (UNRELATED, a genuine disproof: Blick can vouch for every stop the
 *    matched leg(s) touch of the relevant kind, and none of it is in the affected scope); no
 *    intersection while `"PARTIAL"` -> `LINE_RELEVANT` (fails SAFE toward the cautious/uncertain
 *    state — the affected stop may simply be one Blick could not verify).
 */
export function resolveDeviationRelevance(
  deviation: RawDeviation,
  effect: DisruptionEffect,
  legScopes: readonly ResolvedLegScope[],
  journeyWindow: JourneyTimeWindow | null,
): { relevance: DisruptionRelevance; matchedLineDesignations: string[] } | null {
  if (!deviationOverlapsJourneyWindow(deviation, journeyWindow)) return null;

  const lines = deviation.scope.lines ?? [];
  const stopAreaIds = deviation.scope.stop_areas?.map((a) => a.id) ?? [];
  const stopPointIds = deviation.scope.stop_points?.map((p) => p.id) ?? [];
  const hasStopScope = stopAreaIds.length > 0 || stopPointIds.length > 0;
  const scopeKind = scopePolicyForEffect(effect);

  if (lines.length === 0) {
    if (!hasStopScope) return null;
    const union = unionScope(legScopes.map((leg) => scopeSetFor(leg, scopeKind)));
    const matched = intersects(union.stopAreaIds, stopAreaIds) || intersects(union.stopPointIds, stopPointIds);
    if (matched) return { relevance: "CONFIRMED", matchedLineDesignations: [] };
    return null; // fails closed either way -- COMPLETE (genuine disproof) or PARTIAL (no line signal to fall back on)
  }

  const matchedLegs = legScopes.filter((leg) => lines.some((line) => line.transport_mode === leg.transportMode && line.designation === leg.lineDesignation));
  if (matchedLegs.length === 0) return null;
  const matchedLineDesignations = [...new Set(matchedLegs.map((leg) => leg.lineDesignation))];

  if (!hasStopScope) return { relevance: "LINE_RELEVANT", matchedLineDesignations };

  const union = unionScope(matchedLegs.map((leg) => scopeSetFor(leg, scopeKind)));
  const matched = intersects(union.stopAreaIds, stopAreaIds) || intersects(union.stopPointIds, stopPointIds);
  if (matched) return { relevance: "CONFIRMED", matchedLineDesignations };
  if (union.completeness === "COMPLETE") return null;
  return { relevance: "LINE_RELEVANT", matchedLineDesignations };
}

/**
 * The single, authoritative combination point for exact-destination disruption evidence — ALL
 * matching and deduplication for `GET /api/v1/journeys/disruptions` goes through this one function
 * (see this module's own top-level doc on why that centralization matters: no scattered relevance
 * rules in route handlers, Android, the worker, or the notification mapper). Reads the shared,
 * already-cached SL Deviations snapshot's own [deviations] (see
 * `services/deviationsSnapshotService.ts`) — never fetches anything itself.
 *
 * Combination algorithm:
 *
 * 1. Each of [deviations] is classified exactly once (`normalizeDisruption`, the SAME classifier
 *    `/api/v1/disruptions` uses — never a second rule set), deduplicated by `deviation_case_id`
 *    (first occurrence wins — a genuinely repeated id within one snapshot is not expected, but
 *    this makes "SL Deviations are deduplicated by their own id" an explicit guarantee of this
 *    function rather than an assumption about its input), then resolved via
 *    {@link resolveDeviationRelevance}. Each survivor becomes a `source: "SL_DEVIATIONS"`
 *    {@link ResolvedJourneyDisruption}.
 * 2. Convert [journeyPlannerNotices] into `source: "JOURNEY_PLANNER"`, `relevance: "CONFIRMED"`
 *    entries (see {@link DisruptionRelevance}'s own doc on why a Journey Planner notice attached
 *    directly to PRIMARY is always the strongest possible evidence), deduplicated by exact `text`
 *    (Journey Planner notices carry no stable id — text equality is the only identity available).
 * 3. Cross-source merge: when a Journey Planner notice's own `text` exactly equals an SL
 *    Deviation's own `headline` (its `message.header`), they represent the SAME real disruption.
 *    The merged entry keeps the SL Deviation's own richer `id`/`details` (never the text-only,
 *    detail-less Journey Planner copy — this is the fix for a real bug: an earlier version of this
 *    combination logic put Journey Planner entries first in a plain list-concat +
 *    `distinctBy(text)`, which silently kept whichever copy happened to be listed first and
 *    discarded the other's fields, including a Deviation's own richer `details`), with
 *    `relevance` explicitly upgraded to `CONFIRMED` if it was not already — Journey Planner's own
 *    independent attachment to PRIMARY is itself confirming evidence, even when the Deviation's
 *    own structured fields alone could only prove `LINE_RELEVANT`.
 *
 * A Journey Planner notice with no matching Deviation, and a Deviation with no matching Journey
 * Planner notice, both pass through unchanged. Order: Journey-Planner-matched/merged entries
 * first, then remaining unmatched Deviations, mirroring the existing "PRIMARY's own notices first"
 * convention.
 *
 * [legScopes] and [journeyWindow] are forwarded to {@link resolveDeviationRelevance} unchanged for
 * every deviation — see that function's own doc for the full matching contract they feed.
 * `routes/journeyDisruptions.ts` is the one production caller.
 */
export function resolveJourneyDisruptions(
  journeyPlannerNotices: readonly JourneyPlannerNoticeInput[],
  deviations: readonly RawDeviation[],
  legScopes: readonly ResolvedLegScope[],
  journeyWindow: JourneyTimeWindow | null,
): ResolvedJourneyDisruption[] {
  const seenDeviationIds = new Set<string>();
  const deviationResults: ResolvedJourneyDisruption[] = [];
  for (const raw of deviations) {
    const id = String(raw.deviation_case_id);
    if (seenDeviationIds.has(id)) continue;
    const normalized = normalizeDisruption(raw);
    const resolved = resolveDeviationRelevance(raw, normalized.effect, legScopes, journeyWindow);
    if (resolved == null) continue;
    seenDeviationIds.add(id);
    deviationResults.push({
      id,
      headline: normalized.message.header,
      details: normalized.message.details,
      effect: normalized.effect,
      relevance: resolved.relevance,
      source: "SL_DEVIATIONS",
      matchedLineDesignations: resolved.matchedLineDesignations,
    });
  }

  const seenJpText = new Set<string>();
  const jpResults: ResolvedJourneyDisruption[] = [];
  for (const notice of journeyPlannerNotices) {
    if (seenJpText.has(notice.text)) continue;
    seenJpText.add(notice.text);
    jpResults.push({
      headline: notice.text,
      effect: notice.effect,
      relevance: "CONFIRMED",
      source: "JOURNEY_PLANNER",
      matchedLineDesignations: [],
    });
  }

  const deviationByHeadline = new Map(deviationResults.map((d) => [d.headline, d] as const));
  const consumedHeadlines = new Set<string>();
  const merged: ResolvedJourneyDisruption[] = [];

  for (const jp of jpResults) {
    const matchingDeviation = deviationByHeadline.get(jp.headline);
    if (matchingDeviation) {
      merged.push({ ...matchingDeviation, relevance: "CONFIRMED" });
      consumedHeadlines.add(jp.headline);
    } else {
      merged.push(jp);
    }
  }
  for (const d of deviationResults) {
    if (!consumedHeadlines.has(d.headline)) merged.push(d);
  }

  return merged;
}

/**
 * Identifier-namespace evidence this module's rules are built on:
 *
 * - PROVEN compatible (unchanged from before this feature): SL Transport's `site.id`/
 *   `stop_areas[]` and SL Deviations' `scope.stop_areas[].id` share one namespace — confirmed
 *   live during architecture review (see docs/api-contract.md, "Verified namespace result").
 * - PROVEN compatible (new for this feature): SL Journey Planner's own `stopSequence` platform
 *   node `id` (`type: "platform"`, `isGlobalId: true`) and SL Transport `/v1/stop-points`' own
 *   `pattern_point_gid` are the SAME value — see `services/stopPointDirectory.ts`'s own doc for
 *   the full live evidence (101/101 platform entries resolved across metro/train/tram/bus/ferry
 *   and two live multi-leg transfers, 0 ambiguous across 14,187 stop points). This is what makes
 *   [legScopes] (via `journeyDisruptionScope.ts`) able to verify the journey's DESTINATION and
 *   every INTERMEDIATE/transfer stop, not merely its origin — the gap the previous version of
 *   this module's own doc described as its central "known limitation" is closed by this bridge,
 *   for every stop Journey Planner itself supplies a resolvable platform id for.
 * - STILL a real, honest limitation: a `stopSequence` node this backend cannot resolve — because
 *   Journey Planner itself only supplied a coarser `type: "stop"` node (confirmed to happen live,
 *   even for a leg's own origin — see `normalizeJourney.ts`'s own `platformPatternPointGid` doc),
 *   because `StopPointDirectory` returns UNRESOLVED/AMBIGUOUS for it, or because Journey Planner
 *   supplied no `stopSequence` for a leg at all — simply contributes no evidence for that specific
 *   point, degrading the affected `ScopeSet`'s own `completeness` to `"PARTIAL"` rather than being
 *   treated as a disproof; see `journeyDisruptionScope.ts`'s own doc for exactly how that
 *   completeness then changes what `resolveDeviationRelevance`'s own non-intersection rules are
 *   allowed to conclude.
 * - Confirmed live (2026-08-16 architecture review): the real `/v1/messages` SL Deviations feed's
 *   own `scope.stop_points` is currently ALWAYS empty across a live 159-deviation snapshot —
 *   `scope.stop_areas` (89/159 of them) and `scope.lines` (159/159) are the only structured scope
 *   evidence SL currently actually sends on this endpoint. `scope.stop_points` is still modeled
 *   and compared (see `upstreamTypes.ts`'s own `RawDeviationSchema` doc) so this backend
 *   automatically benefits the moment SL starts populating it, with no further code change
 *   required — but today, EVERY `CONFIRMED` outcome that depends on structured stop evidence
 *   comes from a `scope.stop_areas` intersection, never `scope.stop_points`.
 * - A deviation with `scope.lines` but no stop scope at all still reaches only `LINE_RELEVANT`
 *   from THIS function (rule 4 above) — that remains the synchronous, structured-evidence-only
 *   contract `resolveDeviationRelevance`/`resolveJourneyDisruptions` have always had, and every
 *   existing caller of those two functions keeps exactly that behavior, unchanged. A newer,
 *   OPTIONAL, ADDITIVE layer — `resolveDeviationRelevanceAsync`/`resolveJourneyDisruptionsAsync`,
 *   further below — can upgrade specifically that `LINE_RELEVANT`-with-no-stop-scope case to
 *   `CONFIRMED`/UNRELATED when SL's own free text names the affected segment in the one
 *   evidence-backed grammar `journeySegmentParser.ts` recognizes ("mellan A och B") AND that
 *   segment resolves unambiguously against real GTFS-derived line topology (see
 *   `services/lineTopologyDirectory.ts`) — never a guess, and never reached at all unless a
 *   caller explicitly opts in by supplying a `SegmentEvidenceContext`.
 */

/**
 * Optional collaborators for the segment-parsing relevance enhancement (see
 * `resolveDeviationRelevanceAsync`'s own doc). Entirely opt-in: `routes/journeyDisruptions.ts`
 * omits this whenever `LineTopologyDirectory` itself has nothing to offer (no `disruptionContext`
 * resolved, or the topology directory was never wired up), in which case
 * `resolveJourneyDisruptionsAsync` behaves byte-for-byte like the synchronous
 * `resolveJourneyDisruptions`.
 */
export interface SegmentEvidenceContext {
  topologyDirectory: LineTopologyDirectory;
  /** PRIMARY's own real travelled edges, grouped by `${transportMode}:${lineDesignation}`, each
   * carrying its own COMPLETE/PARTIAL completeness — see `requestedCorridor.ts`'s own
   * `buildActualLegEdgesByLine`/`ActualLineEdgeEvidence` doc. */
  actualLegEdgesByLine: ReadonlyMap<string, ActualLineEdgeEvidence>;
  /** Present only when the routine's own requested origin/destination BOTH resolved to a real,
   * confirmed SL Transport `Site` (see `services/journeyEndpointSiteResolver.ts`) — absent means
   * the requested-corridor half of this enhancement (item 14 of this feature's own spec) is
   * unavailable for this request; actual-PRIMARY-edge evidence alone still applies. May be a
   * lazy, memoized provider rather than an already-resolved value — see
   * `routes/journeyDisruptions.ts`'s own doc on why this resolution should not happen eagerly on
   * every request regardless of whether it ends up needed. */
  requestedEndpoints?: { originSite: Site; destinationSite: Site } | (() => Promise<{ originSite: Site; destinationSite: Site } | null>);
}

/** Resolves [requestedEndpoints] regardless of whether it is an already-known value or a lazy,
 * memoized provider function — see {@link SegmentEvidenceContext.requestedEndpoints}'s own doc. */
async function resolveRequestedEndpoints(
  requestedEndpoints: SegmentEvidenceContext["requestedEndpoints"],
): Promise<{ originSite: Site; destinationSite: Site } | null> {
  if (requestedEndpoints == null) return null;
  if (typeof requestedEndpoints === "function") return requestedEndpoints();
  return requestedEndpoints;
}

/**
 * Every distinct `(transportMode, lineDesignation)` PAIR this enhancement must evaluate
 * independently, per item 24 of this feature's own spec ("do not assume the topology of line 10
 * == line 11") — and, critically, per the production-readiness review's own item 16: mode and
 * designation must stay INSEPARABLE throughout segment resolution, the exact same identity
 * `resolveDeviationRelevance`'s own line-matching rule already uses
 * (`line.transport_mode === leg.transportMode && line.designation === leg.lineDesignation`,
 * mirrored exactly below).
 *
 * An earlier version of this function reconstructed matched lines from [base]'s own
 * `matchedLineDesignations: string[]` — already lossy (mode information discarded, see that
 * field's own doc) — via `legScopes.find(leg => leg.lineDesignation === designation)`, a
 * DESIGNATION-ONLY lookup. When [legScopes] contains two legs sharing one designation on
 * DIFFERENT modes (e.g. Metro 13 and Bus 13, a real, unremarkable SL coincidence), `.find()`
 * would silently return whichever leg happened to appear FIRST in the array — even when
 * [deviation]'s own `scope.lines` only genuinely matched the OTHER mode. This function instead
 * derives matched pairs directly from [deviation]'s own `scope.lines` and [legScopes], never
 * through the lossy intermediate — confirmed-fixed by `segmentEvidenceEndToEnd.test.ts`'s own
 * "bug repro (item E)" regression.
 */
function matchedLinePairsFor(deviation: RawDeviation, legScopes: readonly ResolvedLegScope[]): Array<{ transportMode: string; lineDesignation: string }> {
  const lines = deviation.scope.lines ?? [];
  const seen = new Set<string>();
  const result: Array<{ transportMode: string; lineDesignation: string }> = [];
  for (const leg of legScopes) {
    const matchesThisLeg = lines.some((line) => line.transport_mode === leg.transportMode && line.designation === leg.lineDesignation);
    if (!matchesThisLeg) continue;
    const key = actualLegEdgesKey(leg.transportMode, leg.lineDesignation);
    if (seen.has(key)) continue;
    seen.add(key);
    result.push({ transportMode: leg.transportMode, lineDesignation: leg.lineDesignation });
  }
  return result;
}

/**
 * The requested normal corridor's own topology resolution for one line, resolved ONCE per line
 * (never once per candidate): corridor RESOLUTION depends only on [transportMode] +
 * [lineDesignation] + the requested origin/destination, never on which specific affected segment
 * is being evaluated — only corridor TRUST does (see `requestedCorridor.ts`'s own
 * `isRequestedCorridorTrusted` doc), which is why that check takes each candidate's own endpoints
 * as a separate, per-call argument in {@link evaluateCandidateAgainstEvidence} below rather than
 * being decided once here.
 */
async function resolveRequestedCorridorForLine(
  transportMode: string,
  lineDesignation: string,
  segmentContext: SegmentEvidenceContext,
): Promise<LineSegmentResolution | null> {
  const requestedEndpoints = await resolveRequestedEndpoints(segmentContext.requestedEndpoints);
  if (requestedEndpoints == null) return null;
  return segmentContext.topologyDirectory.resolveEndpointsCorridor(
    transportMode,
    lineDesignation,
    requestedEndpoints.originSite,
    requestedEndpoints.destinationSite,
  );
}

/**
 * One resolved affected candidate's own outcome against this line's available evidence — see
 * `evaluateLineSegmentEvidence`'s own doc for how these combine, across every candidate, into the
 * final CONFIRMED/UNRELATED/LINE_RELEVANT verdict.
 *
 * - `"CONFIRMED"`: [candidateEdges] overlaps [actualEvidence]'s own real travelled edges —
 *   regardless of completeness, production-readiness review item 11: one structurally known real
 *   edge is sufficient positive evidence even from a `"PARTIAL"` leg — OR overlaps a requested
 *   corridor that {@link isRequestedCorridorTrusted} trusts specifically with respect to THIS
 *   candidate's own two endpoints (the reroute/truncation-extension case).
 * - `"NON_OVERLAPPING_PROVEN"`: a requested corridor IS trusted for this exact candidate, and
 *   [candidateEdges] does NOT overlap it — a genuine, structurally-grounded negative.
 * - `"UNPROVEN"`: neither of the above — no requested corridor exists at all, or one exists but is
 *   not trusted with respect to THIS candidate's own endpoints. [actualEvidence] alone, however
 *   complete, can NEVER by itself produce a proven non-overlap — see `requestedCorridor.ts`'s own
 *   `ActualLineEdgeEvidence` doc for why a "complete" account of PRIMARY's CURRENT path proves
 *   nothing about the disruption's relevance to the passenger's ORIGINALLY intended route: Journey
 *   Planner may have already rerouted PRIMARY around the very disruption being evaluated.
 */
function evaluateCandidateAgainstEvidence(
  candidateEdges: ReadonlySet<StopAreaEdgeKey>,
  candidateStopAreaA: number,
  candidateStopAreaB: number,
  actualEvidence: ActualLineEdgeEvidence | undefined,
  requestedCorridor: LineSegmentResolution | null,
): "CONFIRMED" | "NON_OVERLAPPING_PROVEN" | "UNPROVEN" {
  if (actualEvidence && edgeSetsIntersect(candidateEdges, actualEvidence.edges)) return "CONFIRMED";

  if (requestedCorridor && requestedCorridor.status === "RESOLVED") {
    const trusted = isRequestedCorridorTrusted({
      requestedCorridorOrderedStopAreaIds: requestedCorridor.orderedStopAreaIds,
      actualRunsOnThisLine: actualEvidence?.orderedRuns ?? [],
      affectedStopAreaA: candidateStopAreaA,
      affectedStopAreaB: candidateStopAreaB,
    });
    if (trusted) {
      return edgeSetsIntersect(candidateEdges, requestedCorridor.edges) ? "CONFIRMED" : "NON_OVERLAPPING_PROVEN";
    }
  }

  return "UNPROVEN";
}

/**
 * One matched line's own segment-parsing evaluation — item 17.B of this feature's own spec.
 * [candidates] (from `journeySegmentParser.ts`) are tried against [transportMode] +
 * [lineDesignation]'s own topology independently: a candidate that resolves against a
 * STRUCTURALLY DIFFERENT line (see the real Blue-line/Green-line fixture in
 * `journeySegmentParser.test.ts`) simply fails to resolve here and contributes nothing, never a
 * false match.
 *
 * UNRELATED is deliberately harder to prove than CONFIRMED — the single most important property
 * of this function (production-readiness review, item 11: known overlap is stronger evidence than
 * known non-overlap). Each candidate's own outcome is ultimately decided by
 * {@link evaluateCandidateAgainstEvidence} (trust — and therefore what a non-overlap can prove —
 * is a PER-CANDIDATE question, never a per-line one; see that function's own doc), then combined:
 *
 * - `CONFIRMED`: as soon as ANY candidate's own outcome is `"CONFIRMED"`, that is sufficient
 *   positive evidence on its own, regardless of what any OTHER candidate did — a second, unrelated
 *   candidate that happens to be AMBIGUOUS, UNRESOLVED, or merely `"UNPROVEN"` on this same line
 *   cannot weaken or withdraw a genuine overlap already found.
 * - `UNRELATED`: only when at least one candidate actually resolved AND EVERY resolved candidate's
 *   own outcome was `"NON_OVERLAPPING_PROVEN"`. An AMBIGUOUS or UNRESOLVED candidate — or a
 *   resolved candidate that stayed `"UNPROVEN"` because no requested corridor was trustworthy for
 *   IT specifically — always prevents this: any one of them might be the real, relevant segment,
 *   and this feature's own spec repeatedly rules out guessing past that uncertainty.
 * - `LINE_RELEVANT`: every other case — no candidate resolved at all, or resolution was mixed
 *   without any overlap already found. The existing conservative, line-scoped presentation applies
 *   unchanged.
 *
 * ## Evaluation order is deliberately lazy about requested-endpoint resolution
 *
 * {@link resolveRequestedCorridorForLine} ultimately calls out to
 * `JourneyEndpointSiteResolver`/Journey Planner (via `SegmentEvidenceContext.requestedEndpoints` —
 * a real, non-free lookup even though it's memoized per HTTP request, see that field's own doc).
 * An earlier version of this function resolved the requested corridor UNCONDITIONALLY, before ever
 * checking whether any candidate even resolved or already overlapped actual PRIMARY — paying that
 * cost even when it could never change the outcome. This version resolves candidates against
 * topology FIRST and only reaches for requested-endpoint evidence once it's already established
 * that the answer could plausibly depend on it:
 *
 * 1. Resolve every candidate. No candidate RESOLVED at all -> `LINE_RELEVANT` immediately; nothing
 *    else can possibly confirm or disprove, so requested endpoints are never touched.
 * 2. Check every RESOLVED candidate against actual PRIMARY's own edges directly. Any overlap ->
 *    `CONFIRMED` immediately, again without ever touching requested endpoints — actual overlap
 *    alone is already sufficient positive evidence (see {@link evaluateCandidateAgainstEvidence}'s
 *    own doc).
 * 3. Requested-corridor trust ({@link isRequestedCorridorTrusted}) can only ever succeed given AT
 *    LEAST one real actual run on this exact line — with zero actual runs, no corridor could ever
 *    become trusted regardless of what it turns out to contain. In that case: `LINE_RELEVANT`,
 *    without resolving requested endpoints for an answer already known to be unhelpful.
 * 4. Only now — once corridor evidence could plausibly change the outcome — is
 *    {@link resolveRequestedCorridorForLine} (and therefore the requested-endpoints provider)
 *    actually invoked, exactly once for this line.
 *
 * Deliberately does NOT skip corridor resolution merely because SOME candidate is unresolved/
 * ambiguous while another is resolved-and-non-overlapping: a mixed unresolved + resolved-but-
 * unproven set of candidates can still reach `CONFIRMED` once the corridor confirms the resolved
 * one (positive evidence needs only one candidate to succeed — see the class-level doc above), so
 * that case still needs the corridor. Only the specific cases where the corridor is PROVABLY unable
 * to change anything (no candidate resolved at all; or a resolved candidate already overlaps
 * actual; or no actual run exists to ever be trusted) skip the lookup.
 */
async function evaluateLineSegmentEvidence(
  candidates: readonly ParsedSegmentCandidate[],
  transportMode: string,
  lineDesignation: string,
  segmentContext: SegmentEvidenceContext,
): Promise<"CONFIRMED" | "UNRELATED" | "LINE_RELEVANT"> {
  const actualEvidence = segmentContext.actualLegEdgesByLine.get(actualLegEdgesKey(transportMode, lineDesignation));

  type ResolvedSegment = Extract<LineSegmentResolution, { status: "RESOLVED" }>;
  const resolvedCandidates: ResolvedSegment[] = [];
  let anyUnresolvedOrAmbiguous = false;

  for (const candidate of candidates) {
    const resolution = await segmentContext.topologyDirectory.resolveSegment(transportMode, lineDesignation, candidate.stopA, candidate.stopB);
    if (resolution.status === "RESOLVED") resolvedCandidates.push(resolution);
    else anyUnresolvedOrAmbiguous = true;
  }
  if (resolvedCandidates.length === 0) return "LINE_RELEVANT";

  if (actualEvidence) {
    for (const candidate of resolvedCandidates) {
      if (edgeSetsIntersect(candidate.edges, actualEvidence.edges)) return "CONFIRMED";
    }
  }

  if ((actualEvidence?.orderedRuns.length ?? 0) === 0) return "LINE_RELEVANT";

  const requestedCorridor = await resolveRequestedCorridorForLine(transportMode, lineDesignation, segmentContext);

  let everyResolvedProvenNonOverlapping = !anyUnresolvedOrAmbiguous;
  for (const candidate of resolvedCandidates) {
    // Re-checks actual overlap first (see evaluateCandidateAgainstEvidence's own doc) -- already
    // ruled out for every candidate here by the direct check above, so this always falls through
    // to the corridor-trust half in practice; kept as the one place that decision is made rather
    // than duplicating it.
    const outcome = evaluateCandidateAgainstEvidence(candidate.edges, candidate.stopAreaA, candidate.stopAreaB, actualEvidence, requestedCorridor);
    if (outcome === "CONFIRMED") return "CONFIRMED";
    if (outcome !== "NON_OVERLAPPING_PROVEN") everyResolvedProvenNonOverlapping = false;
  }

  if (everyResolvedProvenNonOverlapping) return "UNRELATED";
  return "LINE_RELEVANT";
}

/**
 * The async, opt-in upgrade of {@link resolveDeviationRelevance} — identical in every respect
 * (including its own return shape and every one of its five existing rules) EXCEPT for one
 * additional, strictly-later step: when the synchronous result is `LINE_RELEVANT` specifically
 * because [deviation] had `scope.lines` but NO `scope.stop_areas`/`scope.stop_points` at all (the
 * exact case item 17 of this feature's own spec scopes this enhancement to — never a
 * `LINE_RELEVANT` that already reflects SOME, merely `"PARTIAL"`, structured stop evidence), this
 * attempts to parse and resolve an affected segment from [deviation]'s own free text and upgrade
 * the result to `CONFIRMED` or UNRELATED (`null`) — see `evaluateLineSegmentEvidence`'s own doc
 * for the full per-line evaluation this delegates to, independently for every one of
 * [matchedLineDesignations] (item 24: never assumes two different lines share one topology).
 *
 * Gated to `TRAVELLED_PATH`-policy effects only (see `journeyDisruptionScope.ts`'s own
 * `scopePolicyForEffect`): this enhancement proves ONLY "the affected segment lies on the path the
 * vehicle actually travelled," which is irrelevant to an `ACCESS_POINTS` effect (a broken lift, a
 * closed entrance, a moved stop) — those affect a passenger only at a stop they actually board,
 * alight, or transfer at, never merely pass through onboard. Without this gate, a pass-through
 * segment's own edge overlap would wrongly upgrade e.g. an `ACCESSIBILITY_ISSUE` at an intermediate
 * station to `CONFIRMED` even though the passenger never leaves the vehicle there — an
 * `ACCESS_POINTS` effect can still reach `CONFIRMED`, but only via the pre-existing structured
 * `scope.stop_areas`/`scope.stop_points` comparison in {@link resolveDeviationRelevance} itself
 * (rule 5), never via this text-segment path.
 *
 * `undefined` [segmentContext] (the default) makes this function behave byte-for-byte like
 * `resolveDeviationRelevance` — every existing caller of the synchronous function is completely
 * unaffected by this addition. When multiple matched lines each produce a different verdict, the
 * strongest wins in this order: any `CONFIRMED` wins outright; otherwise, `UNRELATED` only when
 * EVERY matched line independently resolved to `UNRELATED` (a genuine, structurally-proven
 * disproof on every one of them); any remaining uncertainty keeps the original `LINE_RELEVANT` —
 * never converts uncertainty into `UNRELATED` (this feature's own spec, item 17's closing rule).
 */
export async function resolveDeviationRelevanceAsync(
  deviation: RawDeviation,
  effect: DisruptionEffect,
  legScopes: readonly ResolvedLegScope[],
  journeyWindow: JourneyTimeWindow | null,
  segmentContext?: SegmentEvidenceContext,
): Promise<{ relevance: DisruptionRelevance; matchedLineDesignations: string[] } | null> {
  const base = resolveDeviationRelevance(deviation, effect, legScopes, journeyWindow);
  if (base == null || base.relevance !== "LINE_RELEVANT" || segmentContext == null) return base;
  if (scopePolicyForEffect(effect) !== "TRAVELLED_PATH") return base;

  const stopAreaIds = deviation.scope.stop_areas?.map((a) => a.id) ?? [];
  const stopPointIds = deviation.scope.stop_points?.map((p) => p.id) ?? [];
  if (stopAreaIds.length > 0 || stopPointIds.length > 0) return base;

  const normalized = normalizeDisruption(deviation);
  const parsed = parseStructuredDisruptionSegment(normalized.message);
  if (parsed.status !== "PARSED") return base;

  const lines = matchedLinePairsFor(deviation, legScopes);
  if (lines.length === 0) return base;

  const evaluations = await Promise.all(
    lines.map((line) => evaluateLineSegmentEvidence(parsed.candidates, line.transportMode, line.lineDesignation, segmentContext)),
  );

  if (evaluations.includes("CONFIRMED")) return { relevance: "CONFIRMED", matchedLineDesignations: base.matchedLineDesignations };
  if (evaluations.every((e) => e === "UNRELATED")) return null;
  return base;
}

/**
 * The async, opt-in upgrade of {@link resolveJourneyDisruptions} — identical merge/dedupe logic,
 * calling {@link resolveDeviationRelevanceAsync} per deviation instead of the synchronous
 * function. `undefined` [segmentContext] makes this behave byte-for-byte like the synchronous
 * version; `routes/journeyDisruptions.ts` is the one production caller, and is also the only
 * place that ever constructs a real {@link SegmentEvidenceContext}.
 */
export async function resolveJourneyDisruptionsAsync(
  journeyPlannerNotices: readonly JourneyPlannerNoticeInput[],
  deviations: readonly RawDeviation[],
  legScopes: readonly ResolvedLegScope[],
  journeyWindow: JourneyTimeWindow | null,
  segmentContext?: SegmentEvidenceContext,
): Promise<ResolvedJourneyDisruption[]> {
  const seenDeviationIds = new Set<string>();
  const deviationResults: ResolvedJourneyDisruption[] = [];
  for (const raw of deviations) {
    const id = String(raw.deviation_case_id);
    if (seenDeviationIds.has(id)) continue;
    const normalized = normalizeDisruption(raw);
    const resolved = await resolveDeviationRelevanceAsync(raw, normalized.effect, legScopes, journeyWindow, segmentContext);
    if (resolved == null) continue;
    seenDeviationIds.add(id);
    deviationResults.push({
      id,
      headline: normalized.message.header,
      details: normalized.message.details,
      effect: normalized.effect,
      relevance: resolved.relevance,
      source: "SL_DEVIATIONS",
      matchedLineDesignations: resolved.matchedLineDesignations,
    });
  }

  const seenJpText = new Set<string>();
  const jpResults: ResolvedJourneyDisruption[] = [];
  for (const notice of journeyPlannerNotices) {
    if (seenJpText.has(notice.text)) continue;
    seenJpText.add(notice.text);
    jpResults.push({
      headline: notice.text,
      effect: notice.effect,
      relevance: "CONFIRMED",
      source: "JOURNEY_PLANNER",
      matchedLineDesignations: [],
    });
  }

  const deviationByHeadline = new Map(deviationResults.map((d) => [d.headline, d] as const));
  const consumedHeadlines = new Set<string>();
  const merged: ResolvedJourneyDisruption[] = [];

  for (const jp of jpResults) {
    const matchingDeviation = deviationByHeadline.get(jp.headline);
    if (matchingDeviation) {
      merged.push({ ...matchingDeviation, relevance: "CONFIRMED" });
      consumedHeadlines.add(jp.headline);
    } else {
      merged.push(jp);
    }
  }
  for (const d of deviationResults) {
    if (!consumedHeadlines.has(d.headline)) merged.push(d);
  }

  return merged;
}
