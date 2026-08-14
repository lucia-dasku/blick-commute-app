/**
 * A journey's structural identity for "is this candidate a legitimate stand-in for another
 * journey" comparisons — see backend/src/routes/journeys.ts's own doc on why the backend
 * needs this at all: two journeys with different line numbers, or a local/express pair
 * where one skips some of the other's intermediate stops, can still represent the same
 * useful way of travelling.
 *
 * Deliberately excludes anything that changes between two otherwise-identical trips on
 * the same route: journey/trip id, departure/arrival time, realtime delay, platform id,
 * vehicle id. A platform change alone must never make two journeys incompatible — see
 * `RoutePatternLeg`'s own doc.
 */
export interface RoutePattern {
  legs: RoutePatternLeg[];
}

export interface RoutePatternLeg {
  transportMode: string;
  /** Ordered, canonicalized (stop-area, not platform — see normalizeJourney.ts's own
   * canonicalStopId) stop ids this leg calls at, boarding through alighting. */
  stopIds: string[];
}

interface RoutePatternSourceLeg {
  transportMode: string;
  stopIds: string[];
}

interface RoutePatternSourceJourney {
  legs: RoutePatternSourceLeg[];
}

/** Builds a journey's `RoutePattern` from its own already-normalized legs — WALK legs are
 * excluded entirely (mirrors normalizeJourney's own firstLeg-selection convention: only
 * public-transport legs define a route). */
export function buildRoutePattern(journey: RoutePatternSourceJourney): RoutePattern {
  return {
    legs: journey.legs
      .filter((leg) => leg.transportMode !== "WALK")
      .map((leg) => ({ transportMode: leg.transportMode, stopIds: leg.stopIds })),
  };
}

/** Whether the shorter of [a]/[b] is an ORDERED (not necessarily contiguous) subsequence
 * of the longer one — the structural basis for "one skips some of the other's
 * intermediate stops" (a local/express pair, or two different lines through the same
 * corridor). Neither list may be empty: an empty stop sequence carries no structural
 * information to compare. */
function isCompatibleStopSequence(a: string[], b: string[]): boolean {
  if (a.length === 0 || b.length === 0) return false;
  if (a.length === b.length) return a.every((id, i) => id === b[i]);
  const [shorter, longer] = a.length < b.length ? [a, b] : [b, a];
  let i = 0;
  for (const stopId of longer) {
    if (i < shorter.length && stopId === shorter[i]) i++;
  }
  return i === shorter.length;
}

/**
 * Whether [candidate] is structurally compatible with [primary] for NEXT/ALTERNATIVE
 * selection purposes — see this file's own doc, and the product spec's exact definition:
 * - the same number of public-transport legs;
 * - the same transport mode for each corresponding leg;
 * - the same boarding and alighting canonical stop id for each corresponding leg;
 * - each corresponding leg's stop sequence either matches exactly, or one is an ordered
 *   subsequence of the other (a local/express pair, or a different line through the same
 *   corridor, skipping only INTERIOR stops — never a different boarding/alighting point).
 *
 * Does not require the exact line designation to match — two different line numbers
 * travelling through the same corridor with the same boarding/alighting points are
 * compatible. An all-WALK "journey" (no public-transport legs at all) is never compatible
 * with anything, including another all-WALK one — there is no route to compare.
 *
 * **This is a pairwise compatibility check, not a globally transitive equivalence
 * relation.** [primary] compatible with B, and B compatible with C, does NOT imply
 * [primary] is compatible with C — an ordered-subsequence match is not transitive in
 * general. Concrete counterexample (see this file's own tests):
 * ```
 * A = [S1, S2, S4]          (a single leg's own stop sequence)
 * B = [S1, S2, S3, S4]
 * C = [S1, S3, S4]
 * ```
 * A is compatible with B (A is an ordered subsequence of B), and B is compatible with C (C
 * is an ordered subsequence of B), but A is NOT compatible with C (same length, differ at
 * index 1). Blick only ever asks "is this ONE candidate compatible with THE current
 * PRIMARY" — never "do these journeys all belong to one shared family" — so this is never a
 * problem in practice: there is no clustering or equivalence-class concept anywhere in this
 * codebase, and none should be introduced here. The relation happens to be symmetric
 * (`isRouteCompatible(x, y) === isRouteCompatible(y, x)`) purely as a side effect of its own
 * definition, not because it expresses membership in a shared class — callers still name
 * their arguments `primary`/`candidate` to keep the actual product question ("is this
 * candidate usable in place of PRIMARY") front and center.
 */
export function isRouteCompatible(primary: RoutePattern, candidate: RoutePattern): boolean {
  if (primary.legs.length === 0 || primary.legs.length !== candidate.legs.length) return false;
  return primary.legs.every((legA, i) => {
    const legB = candidate.legs[i]!;
    if (legA.transportMode !== legB.transportMode) return false;
    if (legA.stopIds.length === 0 || legB.stopIds.length === 0) return false;
    if (legA.stopIds[0] !== legB.stopIds[0]) return false;
    if (legA.stopIds[legA.stopIds.length - 1] !== legB.stopIds[legB.stopIds.length - 1]) return false;
    return isCompatibleStopSequence(legA.stopIds, legB.stopIds);
  });
}
