export interface DominanceCandidate {
  journeyId: string;
  departureTime: string;
  arrivalTime: string;
  transferCount: number;
  /** `null` means genuinely unknown, not zero — see normalizeJourney.ts's own doc on
   * `walkingDurationSeconds`. Never compared as if it were a known value. */
  walkingDurationSeconds: number | null;
}

/**
 * Whether [b] Pareto-dominates [a]: [b] is no worse than [a] on every relevant dimension
 * and strictly better on at least one — see backend/src/routes/journeys.ts's own doc for
 * the product framing this implements.
 *
 * A LATER departure counts as "no worse" when arrival is no later — the passenger can
 * leave later and still achieve the same or better outcome — so [b] is compared as
 * `b.departure >= a.departure`, never `<=`.
 *
 * Dominance is a claim that [b] is objectively no worse than [a] across EVERY relevant
 * dimension, and walking duration is one of those dimensions — so it must be genuinely
 * known for BOTH candidates before that claim can be made at all. If either side's
 * [DominanceCandidate.walkingDurationSeconds] is unknown, [b] cannot be shown to be "no
 * worse than [a]" on walking (the unknown side might require substantially more), so this
 * returns `false` outright — it never falls back to deciding dominance from the other
 * three dimensions alone, and never treats the unknown value as zero, as equal to the
 * known side, or as automatically no worse. This is stricter than merely excluding walking
 * from the comparison: a candidate whose own walking is unknown must not be able to
 * displace a known-walking candidate just by winning on arrival/transfers/departure, since
 * the unknown candidate could still turn out to require far more walking than the one it
 * would otherwise be eliminating. A known ZERO is a real, comparable value here — only a
 * genuinely missing (`null`) value blocks the claim.
 */
export function dominates(b: DominanceCandidate, a: DominanceCandidate): boolean {
  if (b.walkingDurationSeconds == null || a.walkingDurationSeconds == null) return false;

  const bDeparture = Date.parse(b.departureTime);
  const aDeparture = Date.parse(a.departureTime);
  const bArrival = Date.parse(b.arrivalTime);
  const aArrival = Date.parse(a.arrivalTime);

  const departureNoWorse = bDeparture >= aDeparture;
  const arrivalNoWorse = bArrival <= aArrival;
  const transfersNoWorse = b.transferCount <= a.transferCount;
  const walkingNoWorse = b.walkingDurationSeconds <= a.walkingDurationSeconds;

  if (!departureNoWorse || !arrivalNoWorse || !transfersNoWorse || !walkingNoWorse) return false;

  const departureStrictlyBetter = bDeparture > aDeparture;
  const arrivalStrictlyBetter = bArrival < aArrival;
  const transfersStrictlyBetter = b.transferCount < a.transferCount;
  const walkingStrictlyBetter = b.walkingDurationSeconds < a.walkingDurationSeconds;

  return departureStrictlyBetter || arrivalStrictlyBetter || transfersStrictlyBetter || walkingStrictlyBetter;
}

/**
 * Removes every journey that is Pareto-dominated by at least one OTHER journey still in
 * [candidates] — see `dominates`'s own doc. Never compares a candidate against itself,
 * and never removes two journeys that are merely equal on every dimension (neither
 * dominates the other under a strict Pareto rule; that is a journey-identity concern, not
 * a dominance one — see backend/src/services/candidateCollector.ts's own journeyId-keyed
 * pool, which keeps at most one entry per logical journey and runs entirely separately).
 */
export function removeDominatedJourneys<T extends DominanceCandidate>(candidates: T[]): T[] {
  return candidates.filter(
    (candidate) => !candidates.some((other) => other.journeyId !== candidate.journeyId && dominates(other, candidate)),
  );
}
