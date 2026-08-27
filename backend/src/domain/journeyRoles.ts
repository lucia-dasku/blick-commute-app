import { removeDominatedJourneys, type DominanceCandidate } from "./dominance.js";
import { isRouteCompatible, type RoutePattern } from "./routePattern.js";

export interface RankableJourney extends DominanceCandidate {
  pattern: RoutePattern;
}

function compareKnownWalking(a: number | null, b: number | null): number {
  // An unknown walking duration on either side is neither better nor worse for ranking
  // purposes -- it simply falls through to the next tie-break dimension, mirroring
  // dominance.ts's own "never claim a comparison the data can't support" rule.
  if (a == null || b == null) return 0;
  return a - b;
}

/** Blick's existing purpose is fastest useful arrival: earliest final arrival first, then
 * fewer transfers, then less known walking, then a LATER departure (more time for the
 * same outcome -- note the b-a order below, the larger/later departure sorts first), then
 * journeyId as the final deterministic tie-break. Used for both `selectPrimary` and
 * `selectAlternative` — see the product spec's own point that both use this identical
 * lexicographic order. */
function compareByPreference(a: RankableJourney, b: RankableJourney): number {
  return (
    Date.parse(a.arrivalTime) - Date.parse(b.arrivalTime) ||
    a.transferCount - b.transferCount ||
    compareKnownWalking(a.walkingDurationSeconds, b.walkingDurationSeconds) ||
    Date.parse(b.departureTime) - Date.parse(a.departureTime) ||
    a.journeyId.localeCompare(b.journeyId)
  );
}

/** NEXT's own comparator: earliest effective departure first — NEXT answers "when can I
 * leave next", never "which arrives soonest overall" (see `selectNext`'s own doc), so
 * departure is the primary key, not arrival. Two journeys departing at exactly the same
 * effective instant answer that question equally well, so ties fall through to the SAME
 * secondary dimensions `compareByPreference` uses to decide "which is actually better":
 * earliest arrival, fewer transfers, then less KNOWN walking (see `compareKnownWalking`'s
 * own doc — an unknown walking duration on either side is never invented, it simply falls
 * through to the next dimension), and finally journeyId as the last deterministic
 * tie-break. Deliberately does NOT use the later-departure-wins rule `compareByPreference`
 * applies to ITS OWN final tie-break: that rule exists because PRIMARY/ALTERNATIVE are
 * already ranked by arrival first, so a later-yet-equally-good departure is a genuine
 * bonus; here departure is already the very thing being minimized, so a "later wins" rule
 * would contradict the primary key instead of merely breaking a tie under it. */
function compareByDeparture(a: RankableJourney, b: RankableJourney): number {
  return (
    Date.parse(a.departureTime) - Date.parse(b.departureTime) ||
    Date.parse(a.arrivalTime) - Date.parse(b.arrivalTime) ||
    a.transferCount - b.transferCount ||
    compareKnownWalking(a.walkingDurationSeconds, b.walkingDurationSeconds) ||
    a.journeyId.localeCompare(b.journeyId)
  );
}

/**
 * Selects PRIMARY: the current regular route family's own departure to catch right now.
 * Deterministic lexicographic order — see `compareByPreference`'s own doc — never a
 * weighted score. Assumes [journeys] has already been through normalization, allowed-
 * transport-mode validation, current-time filtering, MAX_CHANGES validation, and
 * deduplication (see backend/src/routes/journeys.ts's own doc) — performs none of that
 * itself.
 *
 * Deliberately does NOT require [journeys] to be Pareto-filtered first, unlike an earlier
 * version of this pipeline: `compareByPreference`'s own lexicographic order already ranks
 * any Pareto-dominated candidate no better than its dominator (a journey B that dominates A
 * is, by `dominates`'s own definition, no worse than A on arrival/transfers/known-walking
 * and no worse on departure under `compareByPreference`'s own later-wins tie-break either),
 * so pre-filtering can never change which journey wins PRIMARY — it would only be a
 * redundant pass. See `selectNext`'s own doc for why skipping it is NOT merely harmless but
 * actually REQUIRED there.
 */
export function selectPrimary<T extends RankableJourney>(journeys: T[]): T | undefined {
  return [...journeys].sort(compareByPreference)[0];
}

/** Selects the recommendation for an ARRIVE_BY timetable result. All candidates must already
 * arrive no later than the requested deadline. Unlike live PRIMARY, arriving earlier is not the
 * objective: among SL's bounded, already-sensible proposals this prefers fewer changes, then less
 * known walking, then the latest useful departure, then the arrival closest to the deadline.
 * This keeps a later multi-change detour from displacing a simpler route merely because it leaves
 * slightly later, while equal-quality regular journeys naturally choose the latest one that still
 * satisfies the deadline. The final journey-id tie-break keeps the result deterministic.
 *
 * These dimensions also preserve the relevant Pareto property for this context: a candidate that
 * is no worse on changes/walking and leaves later cannot lose to the candidate it improves upon;
 * unknown walking remains neutral rather than being invented as zero, matching live ranking. */
export function selectArriveByPrimary<T extends RankableJourney>(journeys: T[]): T | undefined {
  return [...journeys].sort(
    (a, b) =>
      a.transferCount - b.transferCount ||
      compareKnownWalking(a.walkingDurationSeconds, b.walkingDurationSeconds) ||
      Date.parse(b.departureTime) - Date.parse(a.departureTime) ||
      Date.parse(b.arrivalTime) - Date.parse(a.arrivalTime) ||
      a.journeyId.localeCompare(b.journeyId),
  )[0];
}

/**
 * NEXT means the next departure the user can take if they miss PRIMARY while continuing
 * to travel in essentially the same normal way. A candidate qualifies only when it is:
 * not PRIMARY itself, route-compatible with PRIMARY (see
 * backend/src/domain/routePattern.ts's own `isRouteCompatible`), and departing strictly
 * after PRIMARY. Among matching candidates the EARLIEST upcoming departure wins —
 * deliberately NOT the general arrival-first `compareByPreference` order
 * `selectPrimary`/`selectAlternative` use: NEXT answers "when can I leave next", not "which
 * arrives soonest overall". Two candidates departing at exactly the same effective instant
 * answer that question equally well, so `compareByDeparture` breaks such a tie by earliest
 * arrival, then fewer transfers, then less known walking, then journeyId — never merely
 * whichever id happens to sort first (see that function's own doc): e.g. Metro A departing
 * 18:39 and arriving 18:55 versus Metro B also departing 18:39 but arriving 18:43 — both
 * equally answer "what can I catch next", so Blick picks the genuinely better one, B.
 *
 * [pool] must already be eligible (current + within MAX_CHANGES — see
 * backend/src/services/candidateCollector.ts's own `isEligibleJourney`) but must NOT be
 * Pareto-filtered — dominance and "soonest compatible departure" are different questions,
 * and applying dominance globally before this runs can silently eliminate the correct
 * answer. Two concrete failure modes this avoids:
 *
 * 1. A LATER same-family departure that happens to arrive earlier (faster/fewer transfers)
 *    Pareto-dominates an earlier same-family one under `dominates` (a later departure with
 *    an equal-or-earlier arrival counts as "no worse", so a strictly earlier arrival makes
 *    it dominate outright). If that earlier departure were removed from [pool] before this
 *    runs, NEXT would wrongly become the later, "better" journey — even though the earlier
 *    one is genuinely the next departure the user could catch. Example: PRIMARY 18:35,
 *    candidate 18:39 (the genuine NEXT), candidate 18:42 arriving sooner than 18:39 —
 *    18:42 must never suppress 18:39 as NEXT.
 * 2. A journey from a DIFFERENT, route-incompatible family departing strictly after the
 *    genuine NEXT can also Pareto-dominate it (dominance has no concept of route
 *    compatibility at all — it only compares departure/arrival/transfers/walking). Since
 *    that different-family journey could never legally become NEXT anyway (it fails the
 *    `isRouteCompatible` check below), letting it eliminate the real NEXT via a prior
 *    global dominance pass would leave NEXT undefined even though a valid compatible
 *    candidate genuinely exists. Example: PRIMARY 18:35, NEXT-candidate metro 18:39, an
 *    unrelated bus 18:40 that arrives before the metro — the bus must never be allowed to
 *    remove the metro from consideration for NEXT.
 */
export function selectNext<T extends RankableJourney>(pool: T[], primary: T): T | undefined {
  const candidates = pool.filter(
    (journey) =>
      journey.journeyId !== primary.journeyId &&
      Date.parse(journey.departureTime) > Date.parse(primary.departureTime) &&
      isRouteCompatible(primary.pattern, journey.pattern),
  );
  return [...candidates].sort(compareByDeparture)[0];
}

/**
 * ALTERNATIVE means a useful journey from a route-INCOMPATIBLE family that can replace
 * waiting for NEXT — see backend/src/routes/journeys.ts's own doc for the full product
 * spec. A candidate qualifies only when it:
 * - is NOT route-compatible with PRIMARY (NEXT is, by construction, already compatible
 *   with PRIMARY, so this single check rules out both);
 * - departs strictly after PRIMARY;
 * - departs strictly before NEXT;
 * - arrives at the final destination strictly before NEXT's own arrival.
 *
 * No minimum-minute advantage exists — an alternative that merely ties NEXT's own arrival
 * does not qualify; it must genuinely arrive earlier. [pool] must already be eligible
 * (current + within MAX_CHANGES) — see `selectNext`'s own doc — but, like `selectPrimary`/
 * `selectNext`, must NOT be pre-filtered by Pareto dominance globally.
 *
 * Dominance IS applied here, but scoped ONLY to the candidates that already structurally
 * qualify as ALTERNATIVE (the `qualifying` filter below): every one of them is already
 * constrained to the same PRIMARY→NEXT interval and is being compared for the exact same
 * purpose (replacing the wait for NEXT), so an objectively worse one among them can safely
 * be discarded — this is precisely the scoping `selectNext`'s own doc explains is unsafe to
 * do globally. Among the remaining, non-dominated qualifying candidates,
 * `compareByPreference` (the same lexicographic order `selectPrimary` uses) decides the
 * winner — never a weighted convenience score.
 */
export function selectAlternative<T extends RankableJourney>(pool: T[], primary: T, next: T): T | undefined {
  const primaryDepartureMs = Date.parse(primary.departureTime);
  const nextDepartureMs = Date.parse(next.departureTime);
  const nextArrivalMs = Date.parse(next.arrivalTime);

  const qualifying = pool.filter((candidate) => {
    if (candidate.journeyId === primary.journeyId || candidate.journeyId === next.journeyId) return false;
    if (isRouteCompatible(primary.pattern, candidate.pattern)) return false;
    if (Date.parse(candidate.departureTime) <= primaryDepartureMs) return false;
    if (Date.parse(candidate.departureTime) >= nextDepartureMs) return false;
    if (Date.parse(candidate.arrivalTime) >= nextArrivalMs) return false;
    return true;
  });
  const nonDominated = removeDominatedJourneys(qualifying);
  return [...nonDominated].sort(compareByPreference)[0];
}
