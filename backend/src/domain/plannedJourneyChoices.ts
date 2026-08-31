import type { DominanceCandidate } from "./dominance.js";

export type PlannedJourneySearchMode = "LEAVE_AT" | "ARRIVE_BY";
export type PlannedJourneyRole = "EARLIER" | "RECOMMENDED" | "LATER";

export interface PlannedJourneyChoice<T> {
  role: PlannedJourneyRole;
  journey: T;
}

export interface PlannedJourneySelection<T> {
  eligiblePool: T[];
  earlier: T | undefined;
  recommended: T | undefined;
  later: T | undefined;
  choices: PlannedJourneyChoice<T>[];
}

function compareWalking(a: DominanceCandidate, b: DominanceCandidate): number {
  if (a.walkingDurationSeconds == null && b.walkingDurationSeconds == null) return 0;
  if (a.walkingDurationSeconds == null) return 1;
  if (b.walkingDurationSeconds == null) return -1;
  return a.walkingDurationSeconds - b.walkingDurationSeconds;
}

function durationMillis(journey: DominanceCandidate): number {
  return Date.parse(journey.arrivalTime) - Date.parse(journey.departureTime);
}

function compareComfort(a: DominanceCandidate, b: DominanceCandidate): number {
  const transfers = a.transferCount - b.transferCount;
  if (transfers !== 0) return transfers;
  return compareWalking(a, b);
}

/**
 * A planned chooser answers a different question from live PRIMARY selection. The ordering
 * below is deliberately lexicographic instead of a weighted score: every tie-break remains
 * explainable and no arbitrary minute threshold can silently outweigh a transfer or a long
 * walk.
 *
 * ARRIVE_BY first avoids transfers and known walking, then duration, a later departure, and an
 * arrival closer to the deadline. The full selector adds one pool-relative rule before those
 * final tie-breaks: within the same comfort tier, prefer an interior option that preserves both
 * an earlier and later valid choice. That is what provides a sensible, robust chooser center
 * without a fixed minute cutoff. LEAVE_AT first gets the passenger to the destination sooner,
 * then applies comfort and duration quality, preferring a later departure only on a tie.
 */
export function comparePlannedRecommendation<T extends DominanceCandidate>(
  a: T,
  b: T,
  searchMode: PlannedJourneySearchMode,
): number {
  if (searchMode === "LEAVE_AT") {
    const arrival = Date.parse(a.arrivalTime) - Date.parse(b.arrivalTime);
    if (arrival !== 0) return arrival;
  }

  const comfort = compareComfort(a, b);
  if (comfort !== 0) return comfort;

  const duration = durationMillis(a) - durationMillis(b);
  if (duration !== 0) return duration;

  const departure = Date.parse(b.departureTime) - Date.parse(a.departureTime);
  if (departure !== 0) return departure;

  if (searchMode === "ARRIVE_BY") {
    const arrival = Date.parse(b.arrivalTime) - Date.parse(a.arrivalTime);
    if (arrival !== 0) return arrival;
  }

  return a.journeyId.localeCompare(b.journeyId);
}

function compareChronologically<T extends DominanceCandidate>(a: T, b: T): number {
  const departure = Date.parse(a.departureTime) - Date.parse(b.departureTime);
  if (departure !== 0) return departure;
  const arrival = Date.parse(a.arrivalTime) - Date.parse(b.arrivalTime);
  if (arrival !== 0) return arrival;
  return a.journeyId.localeCompare(b.journeyId);
}

function removeDuplicateOpportunities<T extends DominanceCandidate>(
  journeys: T[],
  searchMode: PlannedJourneySearchMode,
): T[] {
  const byJourneyId = new Map<string, T>();
  for (const journey of journeys) byJourneyId.set(journey.journeyId, journey);

  const byTimes = new Map<string, T>();
  for (const journey of byJourneyId.values()) {
    const key = `${journey.departureTime}\u0000${journey.arrivalTime}`;
    const current = byTimes.get(key);
    if (current == null || comparePlannedRecommendation(journey, current, searchMode) < 0) {
      byTimes.set(key, journey);
    }
  }
  return [...byTimes.values()];
}

function closestNeighbor<T extends DominanceCandidate>(
  candidates: T[],
  recommended: T,
  searchMode: PlannedJourneySearchMode,
): T | undefined {
  const recommendedDeparture = Date.parse(recommended.departureTime);
  return [...candidates].sort((a, b) => {
    const gapA = Math.abs(Date.parse(a.departureTime) - recommendedDeparture);
    const gapB = Math.abs(Date.parse(b.departureTime) - recommendedDeparture);
    if (gapA !== gapB) return gapA - gapB;
    return comparePlannedRecommendation(a, b, searchMode);
  })[0];
}

/**
 * Selects at most three distinct planned choices. The role is relative to RECOMMENDED's
 * departure, while the returned array is always chronological for direct UI rendering.
 * Earlier/later are neighboring useful opportunities and are intentionally not constrained
 * to RECOMMENDED's route family.
 */
export function selectPlannedJourneyChoices<T extends DominanceCandidate>(
  pool: T[],
  searchMode: PlannedJourneySearchMode,
  requestedDateTime: Date,
): PlannedJourneySelection<T> {
  const requestedMillis = requestedDateTime.getTime();
  const eligible = pool.filter((journey) =>
    searchMode === "ARRIVE_BY"
      ? Date.parse(journey.arrivalTime) <= requestedMillis
      : Date.parse(journey.departureTime) >= requestedMillis,
  );
  const eligiblePool = removeDuplicateOpportunities(eligible, searchMode).sort(compareChronologically);
  const isInterior = (candidate: T) => {
    const departure = Date.parse(candidate.departureTime);
    return (
      eligiblePool.some((journey) => Date.parse(journey.departureTime) < departure) &&
      eligiblePool.some((journey) => Date.parse(journey.departureTime) > departure)
    );
  };
  const recommended = [...eligiblePool].sort((a, b) => {
    const comfort = compareComfort(a, b);
    if (comfort !== 0) return comfort;
    if (searchMode === "ARRIVE_BY") {
      const interior = Number(isInterior(b)) - Number(isInterior(a));
      if (interior !== 0) return interior;
    }
    return comparePlannedRecommendation(a, b, searchMode);
  })[0];
  if (recommended == null) {
    return { eligiblePool, earlier: undefined, recommended: undefined, later: undefined, choices: [] };
  }

  const recommendedDeparture = Date.parse(recommended.departureTime);
  const earlier = closestNeighbor(
    eligiblePool.filter((journey) => Date.parse(journey.departureTime) < recommendedDeparture),
    recommended,
    searchMode,
  );
  const later = closestNeighbor(
    eligiblePool.filter((journey) => Date.parse(journey.departureTime) > recommendedDeparture),
    recommended,
    searchMode,
  );

  const roleByJourneyId = new Map<string, PlannedJourneyRole>([[recommended.journeyId, "RECOMMENDED"]]);
  if (earlier != null) roleByJourneyId.set(earlier.journeyId, "EARLIER");
  if (later != null) roleByJourneyId.set(later.journeyId, "LATER");
  const choices = [earlier, recommended, later]
    .filter((journey): journey is T => journey != null)
    .sort(compareChronologically)
    .map((journey) => ({ role: roleByJourneyId.get(journey.journeyId)!, journey }));

  return { eligiblePool, earlier, recommended, later, choices };
}
