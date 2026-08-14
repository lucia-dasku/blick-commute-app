import { describe, expect, it } from "vitest";
import { dominates, removeDominatedJourneys, type DominanceCandidate } from "../src/domain/dominance.js";

function candidate(overrides: Partial<DominanceCandidate> & { journeyId: string }): DominanceCandidate {
  return {
    departureTime: "2026-08-10T18:00:00Z",
    arrivalTime: "2026-08-10T18:30:00Z",
    transferCount: 0,
    walkingDurationSeconds: 0,
    ...overrides,
  };
}

describe("dominates", () => {
  it("a slow direct bus is dominated by a metro that leaves later but arrives earlier", () => {
    // The product spec's own worked example.
    const bus = candidate({ journeyId: "bus", departureTime: "2026-08-10T18:36:00Z", arrivalTime: "2026-08-10T18:52:00Z" });
    const metro = candidate({ journeyId: "metro", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:42:00Z" });
    expect(dominates(metro, bus)).toBe(true);
    expect(dominates(bus, metro)).toBe(false);
  });

  it("a later departure alone, with an equal arrival, is a strict improvement", () => {
    const a = candidate({ journeyId: "a", departureTime: "2026-08-10T18:00:00Z" });
    const b = candidate({ journeyId: "b", departureTime: "2026-08-10T18:05:00Z" });
    expect(dominates(b, a)).toBe(true);
  });

  it("an earlier arrival alone is a strict improvement", () => {
    const a = candidate({ journeyId: "a", arrivalTime: "2026-08-10T18:30:00Z" });
    const b = candidate({ journeyId: "b", arrivalTime: "2026-08-10T18:25:00Z" });
    expect(dominates(b, a)).toBe(true);
  });

  it("identical on every dimension: neither dominates the other", () => {
    const a = candidate({ journeyId: "a" });
    const b = candidate({ journeyId: "b" });
    expect(dominates(a, b)).toBe(false);
    expect(dominates(b, a)).toBe(false);
  });

  it("worse on one dimension blocks dominance even when better on every other", () => {
    // b departs later and arrives earlier (both favourable) but uses one MORE transfer --
    // that alone must block the dominance claim.
    const a = candidate({ journeyId: "a", departureTime: "2026-08-10T18:00:00Z", arrivalTime: "2026-08-10T18:30:00Z", transferCount: 0 });
    const b = candidate({ journeyId: "b", departureTime: "2026-08-10T18:05:00Z", arrivalTime: "2026-08-10T18:20:00Z", transferCount: 1 });
    expect(dominates(b, a)).toBe(false);
  });

  it("transfer count prevents an apparently faster journey from dominating a simpler one when it is worse on complexity", () => {
    // The product spec's transfer-count safeguard: b arrives earlier but needs an extra
    // change -- a genuinely faster journey is not automatically better once complexity is
    // weighed as its own, non-negotiable dimension.
    const simple = candidate({ journeyId: "simple", arrivalTime: "2026-08-10T18:40:00Z", transferCount: 0 });
    const complex = candidate({ journeyId: "complex", arrivalTime: "2026-08-10T18:35:00Z", transferCount: 1 });
    expect(dominates(complex, simple)).toBe(false);
    // Neither dominates the other -- complex sacrifices transfer count for a faster
    // arrival, a genuine trade-off, not an objective improvement.
    expect(dominates(simple, complex)).toBe(false);
  });

  it("less walking, all else equal, is a strict improvement -- both sides known", () => {
    const a = candidate({ journeyId: "a", walkingDurationSeconds: 300 });
    const b = candidate({ journeyId: "b", walkingDurationSeconds: 60 });
    expect(dominates(b, a)).toBe(true);
    expect(dominates(a, b)).toBe(false);
  });

  it("a candidate with unknown walking can never dominate another, even when it wins outright on every other dimension", () => {
    // Arrives strictly earlier and is no worse on every other dimension -- under a looser
    // rule this alone would establish dominance without ever comparing walking at all.
    // That is too strong: the unknown side might require substantially more walking than
    // the known candidate it would otherwise be eliminating, so dominance must not be
    // established here at all.
    const unknownWalking = candidate({ journeyId: "unknown", arrivalTime: "2026-08-10T18:20:00Z", walkingDurationSeconds: null });
    const known = candidate({ journeyId: "known", arrivalTime: "2026-08-10T18:30:00Z", walkingDurationSeconds: 500 });
    expect(dominates(unknownWalking, known)).toBe(false);
  });

  it("a KNOWN-walking candidate can never dominate one whose walking is unknown, even when it wins outright on every other dimension", () => {
    const known = candidate({ journeyId: "known", arrivalTime: "2026-08-10T18:20:00Z", walkingDurationSeconds: 0 });
    const unknownWalking = candidate({ journeyId: "unknown", arrivalTime: "2026-08-10T18:30:00Z", walkingDurationSeconds: null });
    // known arrives strictly earlier and has zero (not merely low) walking of its own --
    // still cannot be shown "no worse than unknownWalking's own walking", since that value
    // is genuinely unknown and might turn out to be even less.
    expect(dominates(known, unknownWalking)).toBe(false);
  });

  it("both sides' walking unknown: neither dominates the other, even when one wins outright on every other dimension", () => {
    const fewerTransfers = candidate({ journeyId: "fewer-transfers", transferCount: 0, walkingDurationSeconds: null });
    const moreTransfers = candidate({ journeyId: "more-transfers", transferCount: 1, walkingDurationSeconds: null });
    // fewerTransfers is strictly better on transfer count and no worse on everything else
    // -- under a looser rule this alone would establish dominance, walking aside. With
    // walking unknown on BOTH sides, dominance cannot be established via any dimension.
    expect(dominates(fewerTransfers, moreTransfers)).toBe(false);
    expect(dominates(moreTransfers, fewerTransfers)).toBe(false);
  });

  it("known zero walking is distinct from unknown walking -- it participates in dominance as a genuine, comparable value", () => {
    const zeroWalking = candidate({ journeyId: "zero", walkingDurationSeconds: 0 });
    const someWalking = candidate({ journeyId: "some", walkingDurationSeconds: 60 });
    // Both sides known -- zero genuinely beats a known non-zero value, proving `0` is
    // treated as real data (via `!= null`), never coerced to/from "unknown".
    expect(dominates(zeroWalking, someWalking)).toBe(true);
    expect(dominates(someWalking, zeroWalking)).toBe(false);
  });
});

describe("removeDominatedJourneys", () => {
  it("removes the slow bus dominated by a faster metro, keeping every non-dominated journey", () => {
    const bus = candidate({ journeyId: "bus", departureTime: "2026-08-10T18:36:00Z", arrivalTime: "2026-08-10T18:52:00Z" });
    const metro = candidate({ journeyId: "metro", departureTime: "2026-08-10T18:39:00Z", arrivalTime: "2026-08-10T18:42:00Z" });
    const unrelatedLater = candidate({ journeyId: "later", departureTime: "2026-08-10T19:30:00Z", arrivalTime: "2026-08-10T20:00:00Z" });

    const survivors = removeDominatedJourneys([bus, metro, unrelatedLater]);

    expect(survivors.map((j) => j.journeyId).sort()).toEqual(["later", "metro"]);
  });

  it("a genuinely Pareto-efficient earlier-arriving candidate survives regardless of what produced it", () => {
    // Two mutually non-dominated trade-offs (fewer transfers vs. earlier arrival) must
    // BOTH survive -- dominance never removes something merely for being different.
    const fewerTransfers = candidate({ journeyId: "fewer-transfers", arrivalTime: "2026-08-10T18:40:00Z", transferCount: 0 });
    const earlierArrival = candidate({ journeyId: "earlier-arrival", arrivalTime: "2026-08-10T18:35:00Z", transferCount: 1 });

    const survivors = removeDominatedJourneys([fewerTransfers, earlierArrival]);

    expect(survivors.map((j) => j.journeyId).sort()).toEqual(["earlier-arrival", "fewer-transfers"]);
  });

  it("never compares a candidate against itself", () => {
    const only = candidate({ journeyId: "only" });
    expect(removeDominatedJourneys([only])).toEqual([only]);
  });

  it("two candidates that can't be compared on walking (one unknown) both survive, even though one arrives earlier than the other", () => {
    // Under a looser rule, the earlier-arriving one would dominate and eliminate the
    // other outright. With its own walking unknown, that claim can't be made, so BOTH
    // remain available for whatever ordering runs next (e.g. selectAlternative's own
    // lexicographic pick) -- dominance never removes a candidate based on incomplete
    // information about a competitor.
    const earlierArrivalUnknownWalking = candidate({
      journeyId: "earlier-unknown-walking",
      arrivalTime: "2026-08-10T18:20:00Z",
      walkingDurationSeconds: null,
    });
    const laterArrivalKnownWalking = candidate({
      journeyId: "later-known-walking",
      arrivalTime: "2026-08-10T18:30:00Z",
      walkingDurationSeconds: 0,
    });

    const survivors = removeDominatedJourneys([earlierArrivalUnknownWalking, laterArrivalKnownWalking]);

    expect(survivors.map((j) => j.journeyId).sort()).toEqual(["earlier-unknown-walking", "later-known-walking"]);
  });
});
