import type { RawDeviation } from "../services/upstreamTypes.js";
import type { DisruptionEffect } from "../models/disruption.js";
import { normalizeDisruption } from "../normalize/normalizeDisruption.js";

/** One PRIMARY journey leg's own transport mode + line designation — the only two fields this
 * module trusts for line-scope matching (see `resolveDeviationRelevance`'s own doc). Deliberately
 * NOT the full `NormalizedJourney` leg shape: WALK legs and legs with no line designation carry no
 * line-scope signal at all and must never reach this module (see `routes/journeyDisruptions.ts`'s
 * own doc for where that filtering happens). */
export interface RelevanceLeg {
  transportMode: string;
  lineDesignation: string;
}

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
 *   or an SL Deviation's own line/mode scope AND verified stop-area scope both match. Blick may
 *   show the disruption's own real classified `effect` (e.g. "No service") as definitely true for
 *   this journey.
 * - `LINE_RELEVANT`: an SL Deviation's line/mode scope matches a PRIMARY leg, but the currently
 *   available structured fields cannot prove the affected segment/stop intersects this exact
 *   journey (no `affectedStopAreas` at all, or a stop id that cannot be verified against a
 *   reliably-namespaced identifier — see this module's own doc below). Blick must NOT present the
 *   real classified `effect` as definitely true for this journey's own segment; only a
 *   conservative, line-scoped warning is appropriate (see `matchedLineDesignations`).
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
   * all; already journey-scoped by Journey Planner itself). Populated for BOTH `CONFIRMED` and
   * `LINE_RELEVANT` `SL_DEVIATIONS` entries — a caller that needs to build a conservative,
   * line-scoped presentation (see `LINE_RELEVANT`'s own doc) reads this field directly rather than
   * re-deriving it from `headline`'s own free text. */
  matchedLineDesignations: string[];
}

/**
 * The verified SL-Transport/SL-Deviations-namespace stop-area ids Blick can currently vouch for on
 * behalf of one journey — see {@link resolveDeviationRelevance}'s own "Known limitation" doc for
 * exactly why only the origin is ever `"COMPLETE"`-eligible today, and why that gap must be
 * represented explicitly rather than silently assumed away by treating "the one stop I checked
 * doesn't match" as "no stop on this journey matches".
 */
export interface VerifiedJourneyStopScope {
  stopAreaIds: ReadonlySet<number>;
  /**
   * `"PARTIAL"`: {@link stopAreaIds} is NOT necessarily every stop area this journey touches — a
   * stop OUTSIDE it (typically the destination or an intermediate stop, for which Blick has no
   * verified-namespace id today) may still genuinely be part of the journey. A stop found HERE
   * intersecting a deviation's own scope is still direct proof (`CONFIRMED`); the ABSENCE of an
   * intersection here proves nothing about the rest of the journey and must never be treated as a
   * disproof.
   *
   * `"COMPLETE"`: {@link stopAreaIds} genuinely is every stop area this journey touches. Only then
   * does a lack of intersection become a genuine disproof (`null`/UNRELATED). Not achievable with
   * any data Blick has today — reserved for once verified destination/intermediate stop-area ids
   * become available, without requiring any change to this function's callers or to the
   * Android/notification/widget layers downstream of {@link ResolvedJourneyDisruption}.
   */
  completeness: "PARTIAL" | "COMPLETE";
}

/**
 * Resolves one SL Deviation's relevance to a journey whose transit legs are [legs], given
 * [journeyStopScope] — see {@link resolveJourneyDisruptions}'s own doc for the full picture, and
 * this module's own bottom section for the identifier-namespace evidence this function's rules are
 * built on. Returns `null` for UNRELATED (see {@link DisruptionRelevance}'s own doc for why that
 * is not a value of the type itself).
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
 * 1. `scope.lines` empty/absent -> `null` (UNRELATED). This module deliberately never treats
 *    "affects this transport mode somewhere" (`affectedModes`) as relevance evidence on its own —
 *    that would make nearly every metro deviation "relevant" to nearly every metro journey. Line
 *    scope is the one signal reliable enough to anchor a match on.
 * 2. Given `scope.lines` non-empty: relevant only if at least one leg in [legs] shares BOTH the
 *    exact transport mode AND the exact line designation with a `scope.lines` entry — never mode
 *    alone, never designation alone, and never textual/fuzzy comparison. No match -> `null`
 *    (UNRELATED). This is what keeps Slussen -> Liljeholmen (Metro 13/14) correctly unaffected by
 *    an unrelated Bus 401 delay at the same station: sharing a station is never sharing a line.
 * 3. Given a line/mode match and `scope.stop_areas` EMPTY -> `LINE_RELEVANT`. SL itself did not
 *    scope this deviation to specific stops, so Blick must not invent a stricter restriction SL
 *    never provided — but it must also not claim the specific effect is proven for this exact
 *    journey's own segment merely because the line matches. This is exactly the confirmed Akalla
 *    case: `NO_SERVICE`, `affectedLines` = Metro 10 + 11, `affectedStopAreas` = [] — with only
 *    line-level evidence available, `LINE_RELEVANT` (not `CONFIRMED`) is the honest classification.
 * 4. Given a line/mode match and `scope.stop_areas` non-empty: relevance depends on how much of the
 *    journey's own stop set [journeyStopScope] can actually vouch for:
 *    - a stop in {@link VerifiedJourneyStopScope.stopAreaIds} intersects `scope.stop_areas` ->
 *      `CONFIRMED` — direct structural proof, regardless of completeness.
 *    - no intersection among the verified stops, AND
 *      {@link VerifiedJourneyStopScope.completeness} is `"COMPLETE"` -> `null` (UNRELATED) — a
 *      genuine disproof: Blick can vouch for the journey's ENTIRE stop set, and none of it is in
 *      the affected scope.
 *    - no intersection among the verified stops, AND completeness is `"PARTIAL"` (or
 *      [journeyStopScope] itself is `null`, i.e. no verified stop at all) -> `LINE_RELEVANT` — NOT
 *      a disproof: the affected stop may simply be one Blick could not verify (typically the
 *      destination or an intermediate stop — see this function's own "Known limitation" doc).
 *      Fails SAFE toward the cautious/uncertain state rather than silently discarding a real
 *      line-level signal merely because the one stop Blick COULD check isn't the affected one.
 *
 *      This is the fix for a real bug: an earlier version of this function treated "the journey's
 *      only VERIFIED stop (the origin) does not intersect" as equivalent to "the journey does not
 *      intersect" and returned UNRELATED — but with only the origin ever verified today, those are
 *      not the same claim. Concretely: Akalla -> Kungsträdgården, an accessibility issue scoped to
 *      `affectedStopAreas = [Kungsträdgården]` (the destination, not the origin) was incorrectly
 *      dropped as UNRELATED, even though Kungsträdgården genuinely IS this journey's own
 *      destination — Blick simply had no verified id to prove it. The corrected rule surfaces it as
 *      `LINE_RELEVANT` instead of silently discarding it.
 *
 * **Known limitation** (documented here rather than worked around with fuzzy matching): Blick's
 * exact-destination routines persist a reliable SL-Transport-namespace site id for their ORIGIN
 * only (`CommuteRoutine.siteId`, the same field `LINE_DIRECTION` routines already use for their own
 * disruption lookups). The DESTINATION and any intermediate stop are known only via SL Journey
 * Planner's own place-id format (`journeyDestinationId`, and each leg's own canonical stop ids —
 * see `normalizeJourney.ts`'s own `canonicalStopId`), which is a genuinely different,
 * never-verified-compatible namespace from SL Transport/Deviations' `stop_areas[].id` — see this
 * file's own bottom section for the full evidence. Deriving one from the other (e.g. by string
 * prefix/suffix manipulation) would be exactly the kind of "substring ID hack" this module must
 * never use. As a direct consequence, [journeyStopScope] built from data available today is always
 * `completeness: "PARTIAL"`, containing at most the origin's own stop-area ids — see
 * {@link resolveJourneyDisruptions}'s own doc for exactly how that scope is constructed. A
 * deviation whose relevance depends on a stop scope that includes only the destination or an
 * intermediate stop — not the origin — can therefore reach at most `LINE_RELEVANT`, never
 * `CONFIRMED`, however specific its own stop scope actually is; rule 4 above is what makes that the
 * honest, structurally-derived outcome rather than a special case bolted on separately. Should
 * verified destination/intermediate stop-area ids become available in the future, the caller only
 * needs to build a `completeness: "COMPLETE"` {@link VerifiedJourneyStopScope} covering the whole
 * route — this function's own rule 4 already knows how to use that correctly, with no further
 * change needed here.
 */
export function resolveDeviationRelevance(
  deviation: RawDeviation,
  legs: readonly RelevanceLeg[],
  journeyStopScope: VerifiedJourneyStopScope | null,
): { relevance: DisruptionRelevance; matchedLineDesignations: string[] } | null {
  const lines = deviation.scope.lines ?? [];
  if (lines.length === 0) return null;

  const matchedLineDesignations = Array.from(
    new Set(
      legs
        .filter((leg) => lines.some((line) => line.transport_mode === leg.transportMode && line.designation === leg.lineDesignation))
        .map((leg) => leg.lineDesignation),
    ),
  );
  if (matchedLineDesignations.length === 0) return null;

  const stopAreaIds = deviation.scope.stop_areas?.map((a) => a.id) ?? [];
  if (stopAreaIds.length === 0) return { relevance: "LINE_RELEVANT", matchedLineDesignations };

  const knownStopIds = journeyStopScope?.stopAreaIds ?? new Set<number>();
  const intersects = stopAreaIds.some((id) => knownStopIds.has(id));
  if (intersects) return { relevance: "CONFIRMED", matchedLineDesignations };

  // No intersection among the stops Blick could verify. Whether that is a genuine disproof
  // depends entirely on whether [journeyStopScope] covers the journey's WHOLE stop set -- see this
  // function's own rule 4 doc above.
  if (journeyStopScope?.completeness === "COMPLETE") return null;
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
 * 1. Resolve each of [deviations] via {@link resolveDeviationRelevance}, deduplicated by
 *    `deviation_case_id` (first occurrence wins — a genuinely repeated id within one snapshot is
 *    not expected, but this makes "SL Deviations are deduplicated by their own id" an explicit
 *    guarantee of this function rather than an assumption about its input). Each survivor is
 *    normalized (reusing the existing `normalizeDisruption`, which already runs it through the
 *    same classifier `/api/v1/disruptions` uses — never a second rule set) into a
 *    `source: "SL_DEVIATIONS"` {@link ResolvedJourneyDisruption}.
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
 * [journeyStopScope] is forwarded to {@link resolveDeviationRelevance} unchanged for every
 * deviation — see that function's own doc for the `"PARTIAL"`/`"COMPLETE"` distinction it encodes.
 * `routes/journeyDisruptions.ts` is the one production caller, and today always builds a
 * `completeness: "PARTIAL"` scope from the routine's own verified ORIGIN stop-area ids alone.
 */
export function resolveJourneyDisruptions(
  journeyPlannerNotices: readonly JourneyPlannerNoticeInput[],
  deviations: readonly RawDeviation[],
  legs: readonly RelevanceLeg[],
  journeyStopScope: VerifiedJourneyStopScope | null,
): ResolvedJourneyDisruption[] {
  const seenDeviationIds = new Set<string>();
  const deviationResults: ResolvedJourneyDisruption[] = [];
  for (const raw of deviations) {
    const id = String(raw.deviation_case_id);
    if (seenDeviationIds.has(id)) continue;
    const resolved = resolveDeviationRelevance(raw, legs, journeyStopScope);
    if (resolved == null) continue;
    seenDeviationIds.add(id);
    const normalized = normalizeDisruption(raw);
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
 * Identifier-namespace evidence this module's rules are built on (see `resolveDeviationRelevance`'s
 * own "Known limitation" doc) — recorded here, not just in a commit message, so a future change
 * can verify its own assumptions against the same evidence rather than re-discovering it:
 *
 * - PROVEN compatible: SL Transport's `site.id`/`stop_areas[]` and SL Deviations'
 *   `scope.stop_areas[].id` share one namespace — confirmed live during architecture review (see
 *   docs/api-contract.md, "Verified namespace result") and already relied on by
 *   `services/deviationsFilter.ts`'s own `resolveSiteStopAreaIds`, which THIS module reuses
 *   unchanged via its `originStopAreaIds` parameter (built from `CommuteRoutine.siteId`, itself
 *   confirmed to be an SL-Transport-namespace id — see `CommuteRoutine.kt`'s own doc describing
 *   `siteId` as a "platform-neutral identity field" shared by both routine types).
 * - NOT proven compatible: SL Journey Planner's own place `id` format (e.g.
 *   `"9091001000009192"`, used for `journeyOriginId`/`journeyDestinationId` and each normalized
 *   leg's own internal `stopIds` — see `normalizeJourney.ts`'s own `canonicalStopId`) versus SL
 *   Transport/Deviations' plain-integer `siteId`/`stop_areas[].id` (e.g. `9192`). These two ID
 *   spaces are never cross-referenced anywhere else in this codebase, and no upstream
 *   documentation (SL Transport, SL Deviations, or SL Journey Planner's own OpenAPI-equivalent
 *   material reviewed for this project) states or implies a derivable relationship between them.
 *   Although the same site's two ids happen to share trailing digits in the examples observed live
 *   (Slussen: siteId `9192`, Journey Planner id `"9091001000009192"`), treating that as a reliable
 *   mapping would be exactly the "last-N-digit"/substring hack this module is required not to use
 *   — it is an unverified coincidence from a handful of examples, not a documented or schema-backed
 *   guarantee, so it is NOT used anywhere in this module. Consequently: the journey's DESTINATION
 *   and any INTERMEDIATE stop cannot currently be checked against `affectedStopAreas` at all; only
 *   the ORIGIN can, via the proven `CommuteRoutine.siteId` path above.
 * - What CAN reliably establish route-segment intersection today: only whether the journey's own
 *   ORIGIN site (via `originStopAreaIds`) is named in a deviation's `scope.stop_areas`. Nothing
 *   else in the currently available structured data (Journey Planner's own leg/stop metadata, SL
 *   Deviations' `affectedModes`, priority, or validity fields) can prove segment-level intersection
 *   without inventing an unverified mapping or parsing SL's own free-text message — both explicitly
 *   out of scope for this module.
 */
