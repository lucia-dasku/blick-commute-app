import { describe, expect, it } from "vitest";
import { selectAlternative, selectNext, selectPrimary, type RankableJourney } from "../src/domain/journeyRoles.js";
import type { RoutePattern } from "../src/domain/routePattern.js";

const METRO_PATTERN: RoutePattern = { legs: [{ transportMode: "METRO", stopIds: ["A", "B"] }] };
const OTHER_METRO_PATTERN: RoutePattern = { legs: [{ transportMode: "METRO", stopIds: ["A", "B", "C"] }] };
const BUS_PATTERN: RoutePattern = { legs: [{ transportMode: "BUS", stopIds: ["A", "B"] }] };

function journey(overrides: Partial<RankableJourney> & { journeyId: string }): RankableJourney {
  return {
    departureTime: "2026-08-10T18:00:00Z",
    arrivalTime: "2026-08-10T18:20:00Z",
    transferCount: 0,
    walkingDurationSeconds: 0,
    pattern: METRO_PATTERN,
    ...overrides,
  };
}

describe("selectPrimary", () => {
  it("earliest final arrival wins", () => {
    const slow = journey({ journeyId: "slow", arrivalTime: "2026-08-10T18:31:00Z" });
    const fast = journey({ journeyId: "fast", arrivalTime: "2026-08-10T18:23:00Z" });
    expect(selectPrimary([slow, fast])?.journeyId).toBe("fast");
  });

  it("tied arrival: fewer transfers wins", () => {
    const moreChanges = journey({ journeyId: "more-changes", transferCount: 2 });
    const fewerChanges = journey({ journeyId: "fewer-changes", transferCount: 0 });
    expect(selectPrimary([moreChanges, fewerChanges])?.journeyId).toBe("fewer-changes");
  });

  it("tied arrival and transfers: less known walking wins", () => {
    const moreWalking = journey({ journeyId: "more-walking", walkingDurationSeconds: 300 });
    const lessWalking = journey({ journeyId: "less-walking", walkingDurationSeconds: 30 });
    expect(selectPrimary([moreWalking, lessWalking])?.journeyId).toBe("less-walking");
  });

  it("tied arrival, transfers, and walking: a later departure wins -- more time for the same outcome", () => {
    const earlierDeparture = journey({ journeyId: "earlier-departure", departureTime: "2026-08-10T17:55:00Z" });
    const laterDeparture = journey({ journeyId: "later-departure", departureTime: "2026-08-10T18:10:00Z" });
    expect(selectPrimary([earlierDeparture, laterDeparture])?.journeyId).toBe("later-departure");
  });

  it("tied on every ranked dimension: journeyId is the final deterministic tie-break", () => {
    const b = journey({ journeyId: "b-journey" });
    const a = journey({ journeyId: "a-journey" });
    expect(selectPrimary([b, a])?.journeyId).toBe("a-journey");
  });

  it("an empty pool selects nothing", () => {
    expect(selectPrimary([])).toBeUndefined();
  });
});

describe("selectNext", () => {
  it("the product spec's own worked example: a same-family later metro is NEXT, the bus in between is not", () => {
    const primary = journey({ journeyId: "metro-1835", departureTime: "2026-08-10T18:35:00Z", arrivalTime: "2026-08-10T18:38:00Z", pattern: METRO_PATTERN });
    const bus = journey({ journeyId: "bus-1836", departureTime: "2026-08-10T18:36:00Z", arrivalTime: "2026-08-10T18:52:00Z", pattern: BUS_PATTERN });
    const nextMetro = journey({ journeyId: "metro-1839", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:42:00Z", pattern: METRO_PATTERN });

    const next = selectNext([primary, bus, nextMetro], primary);

    expect(next?.journeyId).toBe("metro-1839");
  });

  it("a same-family candidate is NEXT even under a completely different journeyId/line identity -- only the structural pattern is compared", () => {
    const primary = journey({ journeyId: "line-14-early", departureTime: "2026-08-10T18:35:00Z", pattern: METRO_PATTERN });
    const sameFamilyDifferentLine = journey({ journeyId: "line-19-later", departureTime: "2026-08-10T18:50:00Z", pattern: METRO_PATTERN });
    expect(selectNext([primary, sameFamilyDifferentLine], primary)?.journeyId).toBe("line-19-later");
  });

  it("no same-family candidate available: returns undefined, never an unrelated journey", () => {
    const primary = journey({ journeyId: "primary", departureTime: "2026-08-10T18:35:00Z", pattern: METRO_PATTERN });
    const unrelatedBus = journey({ journeyId: "bus", departureTime: "2026-08-10T18:40:00Z", pattern: BUS_PATTERN });
    const unrelatedDifferentMetro = journey({ journeyId: "other-metro", departureTime: "2026-08-10T18:45:00Z", pattern: OTHER_METRO_PATTERN });
    expect(selectNext([primary, unrelatedBus, unrelatedDifferentMetro], primary)).toBeUndefined();
  });

  it("among multiple same-family candidates, the earliest departure wins -- not the general arrival-first preference order", () => {
    const primary = journey({ journeyId: "primary", departureTime: "2026-08-10T18:00:00Z", pattern: METRO_PATTERN });
    // Departs later than earlierDeparture but arrives sooner -- selectPrimary's own
    // preference order would pick this one; selectNext must not.
    const fasterButLater = journey({ journeyId: "faster-but-later", departureTime: "2026-08-10T18:20:00Z", arrivalTime: "2026-08-10T18:25:00Z", pattern: METRO_PATTERN });
    const earlierDeparture = journey({ journeyId: "earlier-departure", departureTime: "2026-08-10T18:10:00Z", arrivalTime: "2026-08-10T18:40:00Z", pattern: METRO_PATTERN });
    expect(selectNext([primary, fasterButLater, earlierDeparture], primary)?.journeyId).toBe("earlier-departure");
  });

  it("a candidate departing at or before PRIMARY's own departure is never NEXT", () => {
    const primary = journey({ journeyId: "primary", departureTime: "2026-08-10T18:00:00Z", pattern: METRO_PATTERN });
    const sameTime = journey({ journeyId: "same-time", departureTime: "2026-08-10T18:00:00Z", pattern: METRO_PATTERN });
    const earlier = journey({ journeyId: "earlier", departureTime: "2026-08-10T17:55:00Z", pattern: METRO_PATTERN });
    expect(selectNext([primary, sameTime, earlier], primary)).toBeUndefined();
  });

  describe("tie-breaking when two candidates depart at exactly the same effective instant", () => {
    const primary = journey({ journeyId: "primary", departureTime: "2026-08-10T18:35:00Z", arrivalTime: "2026-08-10T18:38:00Z", pattern: METRO_PATTERN });

    it("the product spec's own worked example: equal departure, earlier arrival wins, never whichever journeyId sorts first", () => {
      // "metro-a" sorts before "metro-b" alphabetically -- if departure+id were still the
      // whole comparator, metro-a would wrongly win despite arriving 12 minutes later.
      const metroA = journey({ journeyId: "metro-a", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:55:00Z", pattern: METRO_PATTERN });
      const metroB = journey({ journeyId: "metro-b", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:43:00Z", pattern: METRO_PATTERN });
      expect(selectNext([primary, metroA, metroB], primary)?.journeyId).toBe("metro-b");
    });

    it("equal departure and arrival: fewer transfers wins", () => {
      // "a-more-changes" sorts first alphabetically -- proves transfers decided it, not id.
      const moreChanges = journey({ journeyId: "a-more-changes", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", transferCount: 2, pattern: METRO_PATTERN });
      const fewerChanges = journey({ journeyId: "b-fewer-changes", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", transferCount: 0, pattern: METRO_PATTERN });
      expect(selectNext([primary, moreChanges, fewerChanges], primary)?.journeyId).toBe("b-fewer-changes");
    });

    it("equal departure, arrival, and transfers: less KNOWN walking wins", () => {
      // "a-more-walking" sorts first alphabetically -- proves walking decided it, not id.
      const moreWalking = journey({ journeyId: "a-more-walking", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", transferCount: 0, walkingDurationSeconds: 300, pattern: METRO_PATTERN });
      const lessWalking = journey({ journeyId: "b-less-walking", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", transferCount: 0, walkingDurationSeconds: 30, pattern: METRO_PATTERN });
      expect(selectNext([primary, moreWalking, lessWalking], primary)?.journeyId).toBe("b-less-walking");
    });

    it("equal departure, arrival, and transfers with walking unknown on one side: falls through to journeyId, never inventing a value for the unknown side", () => {
      // "a-known-walking" has real, low (not merely unknown) walking and sorts first --
      // if unknown walking were ever wrongly treated as zero/best, "b-unknown-walking"
      // would win instead.
      const knownWalking = journey({ journeyId: "a-known-walking", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", transferCount: 0, walkingDurationSeconds: 30, pattern: METRO_PATTERN });
      const unknownWalking = journey({ journeyId: "b-unknown-walking", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", transferCount: 0, walkingDurationSeconds: null, pattern: METRO_PATTERN });
      expect(selectNext([primary, knownWalking, unknownWalking], primary)?.journeyId).toBe("a-known-walking");
    });

    it("a complete tie on every ranked dimension: journeyId is the final deterministic tie-break", () => {
      const b = journey({ journeyId: "b-journey", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", pattern: METRO_PATTERN });
      const a = journey({ journeyId: "a-journey", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:45:00Z", pattern: METRO_PATTERN });
      expect(selectNext([primary, b, a], primary)?.journeyId).toBe("a-journey");
    });
  });
});

describe("selectAlternative", () => {
  const primary = journey({ journeyId: "primary-metro", departureTime: "2026-08-10T22:30:00Z", arrivalTime: "2026-08-10T23:00:00Z", pattern: METRO_PATTERN });
  const next = journey({ journeyId: "next-metro", departureTime: "2026-08-10T23:30:00Z", arrivalTime: "2026-08-11T00:00:00Z", pattern: METRO_PATTERN });

  it("the product spec's own worked example: a direct bus qualifies -- transferCount 0 does not disqualify it", () => {
    const directBus = journey({ journeyId: "direct-bus", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-10T23:40:00Z", transferCount: 0, pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, directBus], primary, next)?.journeyId).toBe("direct-bus");
  });

  it("departs before NEXT and arrives before NEXT: qualifies", () => {
    const candidate = journey({ journeyId: "candidate", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-10T23:40:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, candidate], primary, next)?.journeyId).toBe("candidate");
  });

  it("departs after NEXT but arrives before NEXT: reject", () => {
    const candidate = journey({ journeyId: "candidate", departureTime: "2026-08-10T23:35:00Z", arrivalTime: "2026-08-10T23:50:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, candidate], primary, next)).toBeUndefined();
  });

  it("departs before NEXT but arrives after NEXT: reject", () => {
    const candidate = journey({ journeyId: "candidate", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-11T00:05:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, candidate], primary, next)).toBeUndefined();
  });

  it("arrives exactly when NEXT arrives: reject -- the advantage must be strict, not merely equal", () => {
    const candidate = journey({ journeyId: "candidate", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-11T00:00:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, candidate], primary, next)).toBeUndefined();
  });

  it("departs exactly when NEXT departs: reject", () => {
    const candidate = journey({ journeyId: "candidate", departureTime: "2026-08-10T23:30:00Z", arrivalTime: "2026-08-10T23:55:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, candidate], primary, next)).toBeUndefined();
  });

  it("departs exactly when PRIMARY departs: reject", () => {
    const candidate = journey({ journeyId: "candidate", departureTime: "2026-08-10T22:30:00Z", arrivalTime: "2026-08-10T23:20:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, candidate], primary, next)).toBeUndefined();
  });

  it("a candidate in the same route family as PRIMARY is never an alternative, regardless of timing", () => {
    const sameFamily = journey({ journeyId: "same-family", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-10T23:10:00Z", pattern: METRO_PATTERN });
    expect(selectAlternative([primary, next, sameFamily], primary, next)).toBeUndefined();
  });

  it("a candidate with 2 changes qualifies -- ALTERNATIVE may have up to MAX_CHANGES", () => {
    const twoChanges = journey({ journeyId: "two-changes", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-10T23:40:00Z", transferCount: 2, pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, twoChanges], primary, next)?.journeyId).toBe("two-changes");
  });

  it("when multiple candidates qualify, the same lexicographic preference selectPrimary uses picks the winner", () => {
    const earlierArrival = journey({ journeyId: "earlier-arrival", departureTime: "2026-08-10T22:45:00Z", arrivalTime: "2026-08-10T23:30:00Z", pattern: BUS_PATTERN });
    const laterArrival = journey({ journeyId: "later-arrival", departureTime: "2026-08-10T22:50:00Z", arrivalTime: "2026-08-10T23:40:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, earlierArrival, laterArrival], primary, next)?.journeyId).toBe("earlier-arrival");
  });

  it("no qualifying candidate: returns undefined", () => {
    expect(selectAlternative([primary, next], primary, next)).toBeUndefined();
  });

  it("a candidate that Pareto-dominates another otherwise-qualifying candidate eliminates it from consideration", () => {
    // Dominance IS applied here, scoped to just the candidates that already structurally
    // qualify as ALTERNATIVE -- see selectAlternative's own doc for why this is safe here
    // even though the same global filter is unsafe for selectNext.
    const weaker = journey({ journeyId: "weaker", departureTime: "2026-08-10T22:40:00Z", arrivalTime: "2026-08-10T23:35:00Z", pattern: BUS_PATTERN });
    // Departs later and arrives earlier than weaker, same transfer count -- dominates it outright.
    const stronger = journey({ journeyId: "stronger", departureTime: "2026-08-10T22:45:00Z", arrivalTime: "2026-08-10T23:20:00Z", pattern: BUS_PATTERN });
    expect(selectAlternative([primary, next, weaker, stronger], primary, next)?.journeyId).toBe("stronger");
  });
});
