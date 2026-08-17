import { describe, expect, it } from "vitest";
import { buildActualLegEdgesByLine, isRequestedCorridorTrusted, resolveEndpointStopAreaOnLine } from "../src/domain/requestedCorridor.js";
import { buildLineTopologyGraph, edgeKey, type TripStopSequenceEntry } from "../src/domain/lineTopologyGraph.js";
import type { Site } from "../src/models/site.js";
import { JOURNEY_DISRUPTION_CONTEXT_VERSION, type JourneyDisruptionContext, type JourneyDisruptionContextLeg } from "../src/models/journeyDisruptionContext.js";
import type { PatternPointGid, StopPointResolution } from "../src/services/stopPointDirectory.js";

const AKALLA = 3271,
  HUSBY = 3261,
  KISTA = 3251,
  TCENTRALEN = 1051,
  KUNGSTRADGARDEN = 3031;

function pattern(tripId: string, stops: number[]): TripStopSequenceEntry[] {
  return stops.map((stopAreaId, index) => ({ tripId, stopAreaId, sequence: index }));
}

function site(siteId: number, stopAreaIds: number[]): Site {
  return { siteId, name: `Site ${siteId}`, note: null, lat: null, lon: null, stopAreaIds };
}

describe("resolveEndpointStopAreaOnLine", () => {
  const graph = buildLineTopologyGraph(pattern("t1", [AKALLA, HUSBY, KISTA, TCENTRALEN, KUNGSTRADGARDEN]));

  it("a single-StopArea site on this line resolves directly", () => {
    expect(resolveEndpointStopAreaOnLine(site(1, [AKALLA]), graph)).toEqual({ status: "RESOLVED", stopAreaId: AKALLA });
  });

  it("a multi-mode site (e.g. Slussen: metro + bus StopAreas) narrows to only the StopArea actually on this line", () => {
    const multiModeSite = site(9192, [TCENTRALEN, 44000]); // 44000 is not part of this line's topology
    expect(resolveEndpointStopAreaOnLine(multiModeSite, graph)).toEqual({ status: "RESOLVED", stopAreaId: TCENTRALEN });
  });

  it("a site whose StopAreas are entirely absent from this line's topology is UNRESOLVED", () => {
    expect(resolveEndpointStopAreaOnLine(site(1, [99999]), graph)).toEqual({ status: "UNRESOLVED" });
  });

  it("a site with two StopAreas that are BOTH on this same line's topology is AMBIGUOUS -- never an arbitrary first pick", () => {
    expect(resolveEndpointStopAreaOnLine(site(1, [AKALLA, HUSBY]), graph)).toEqual({ status: "AMBIGUOUS" });
  });

  it("an empty stopAreaIds list is UNRESOLVED", () => {
    expect(resolveEndpointStopAreaOnLine(site(1, []), graph)).toEqual({ status: "UNRESOLVED" });
  });
});

describe("isRequestedCorridorTrusted: exact ordered prefix/suffix trust (corrected from edge-subset + single-boundary-match)", () => {
  // Generic, structural stop ids -- this algorithm must hold for any line's own topology, not just
  // the real Blue line, so these tests deliberately avoid station names (see the requirement that
  // the loop/repeated-stop and corridor-trust fixes both be proven with generic structural cases).
  // [requestedCorridorOrderedStopAreaIds] is always constructed here exactly as
  // LineTopologyDirectory.resolveEndpointsCorridor now guarantees it: origin first, destination last.
  const A = 1,
    B = 2,
    C = 3,
    D = 4,
    E = 5;

  it("BUG REPRO (item 11): an internal fragment -- neither end at the requested origin/destination -- is NOT trusted, even though C is an affected-segment endpoint", () => {
    // Requested A-B-C-D-E, actual run B-C. The run's edges sit inside the corridor and C happens
    // to be an affected-segment endpoint, which the OLD edge-subset + single-boundary-match rule
    // wrongly trusted -- but B-C is only an internal fragment (an ordinary mid-journey transfer),
    // never proven to be a genuine prefix or suffix of the requested journey.
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[B, C]], affectedStopAreaA: C, affectedStopAreaB: D }),
    ).toBe(false);
  });

  it("(item 13) valid forward prefix: actual A-B-C, truncated exactly at affected boundary C -> trusted", () => {
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[A, B, C]], affectedStopAreaA: C, affectedStopAreaB: D }),
    ).toBe(true);
  });

  it("(item 14) valid suffix: actual C-D-E, truncated exactly at affected boundary C -> trusted", () => {
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[C, D, E]], affectedStopAreaA: B, affectedStopAreaB: C }),
    ).toBe(true);
  });

  it("(item 15) a genuinely reverse-oriented requested journey: requested E-D-C-B-A, actual E-D-C, affected C<->B -> trusted", () => {
    // Different from reversing an actual run incorrectly (see the next test): here the REQUESTED
    // corridor itself is oriented E -> A (a real reverse-direction journey), so E-D-C is a true
    // prefix of THAT corridor, not of A-B-C-D-E.
    const requested = [E, D, C, B, A];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[E, D, C]], affectedStopAreaA: C, affectedStopAreaB: B }),
    ).toBe(true);
  });

  it("(item 12) a reversed prefix is NOT trusted: requested A-B-C-D-E, actual C-B-A (the wrong direction)", () => {
    // C-B-A's edges are identical to A-B-C's, but the passenger is travelling the OPPOSITE
    // direction from the requested corridor -- journey direction matters, and ordered-sequence
    // comparison (never an edge set) is what catches this.
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[C, B, A]], affectedStopAreaA: C, affectedStopAreaB: D }),
    ).toBe(false);
  });

  it("(item 16) a valid suffix whose own first stop is NOT an affected boundary is not trusted -- suffix status alone is not enough", () => {
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[C, D, E]], affectedStopAreaA: A, affectedStopAreaB: B }),
    ).toBe(false);
  });

  it("(item 17) a valid prefix that terminates before the affected segment is not trusted -- do not assume every early termination was caused by THIS disruption", () => {
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[A, B]], affectedStopAreaA: C, affectedStopAreaB: D }),
    ).toBe(false);
  });

  it("Case A: an exact full match is trusted unconditionally, even for a candidate not anchored at either boundary", () => {
    const requested = [A, B, C, D, E];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[A, B, C, D, E]], affectedStopAreaA: B, affectedStopAreaB: C }),
    ).toBe(true);
  });

  it("a missing intermediate stop must never be accepted as a prefix, even if that shortcut happens to be an edge elsewhere in the graph", () => {
    // Requested A-B-C-D, actual A-C -- A-C is not THIS requested corridor's own prefix, regardless
    // of whether an A<->C edge exists somewhere else in the line's real topology.
    const requested = [A, B, C, D];
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[A, C]], affectedStopAreaA: A, affectedStopAreaB: B }),
    ).toBe(false);
  });

  it("not trusted: PRIMARY never uses this line at all (no actual runs)", () => {
    const requested = [A, B, C, D, E];
    expect(isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [], affectedStopAreaA: A, affectedStopAreaB: B })).toBe(
      false,
    );
  });

  it("not trusted: an empty requested corridor (nothing resolved) trusts nothing", () => {
    expect(
      isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: [], actualRunsOnThisLine: [[A, B]], affectedStopAreaA: A, affectedStopAreaB: B }),
    ).toBe(false);
  });

  it("trust is per-candidate: the SAME actual prefix run is trusted for the segment at its own boundary but not for an unrelated segment elsewhere on the same corridor", () => {
    const requested = [A, B, C, D, E];
    const actualRunsOnThisLine = [[A, B, C]];
    expect(isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine, affectedStopAreaA: C, affectedStopAreaB: D })).toBe(true);
    expect(isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine, affectedStopAreaA: D, affectedStopAreaB: E })).toBe(
      false,
    );
  });

  it("a run of length 1 (a single stop, no traversal at all) is never trusted", () => {
    const requested = [A, B, C];
    expect(isRequestedCorridorTrusted({ requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine: [[A]], affectedStopAreaA: A, affectedStopAreaB: B })).toBe(
      false,
    );
  });

  it("a direct two-stop full-corridor match matches the ordinary, non-rerouted direct-journey shape", () => {
    const requested = [TCENTRALEN, KUNGSTRADGARDEN];
    expect(
      isRequestedCorridorTrusted({
        requestedCorridorOrderedStopAreaIds: requested,
        actualRunsOnThisLine: [[TCENTRALEN, KUNGSTRADGARDEN]],
        affectedStopAreaA: TCENTRALEN,
        affectedStopAreaB: KUNGSTRADGARDEN,
      }),
    ).toBe(true);
  });
});

describe("buildActualLegEdgesByLine: never bridging an unresolved PRIMARY stop (production-readiness review items 8/9)", () => {
  function leg(overrides: Partial<JourneyDisruptionContextLeg> & { transportMode: string; lineDesignation: string | null }): JourneyDisruptionContextLeg {
    return { stopPatternPointGids: [], stopSequenceComplete: true, ...overrides };
  }
  function context(legs: JourneyDisruptionContextLeg[]): JourneyDisruptionContext {
    return { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Start", journeyEnd: "End", legs };
  }
  function resolutionsFor(resolved: Record<string, number>, unresolvedGids: string[] = []): Map<PatternPointGid, StopPointResolution> {
    const map = new Map<PatternPointGid, StopPointResolution>();
    for (const [gid, stopAreaId] of Object.entries(resolved)) {
      map.set(gid, { status: "RESOLVED", patternPointGid: gid, stopPointId: stopAreaId * 10, stopAreaId, stopAreaType: "METROSTN" });
    }
    for (const gid of unresolvedGids) map.set(gid, { status: "UNRESOLVED", patternPointGid: gid });
    return map;
  }

  it("(item 11) A resolved -> B unresolved -> C resolved never produces an A<->C edge", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b", "pp-c"] });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-c": KISTA }, ["pp-b"]);
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.edges.has(edgeKey(AKALLA, KISTA))).toBe(false);
    expect(evidence.edges.size).toBe(0); // no edge at all crosses the gap
  });

  it("(item 12) stopSequenceComplete=false makes the leg's own evidence PARTIAL, even when every PRESENT gid resolves cleanly", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b"], stopSequenceComplete: false });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY });
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.completeness).toBe("PARTIAL");
    expect(evidence.edges).toEqual(new Set([edgeKey(AKALLA, HUSBY)])); // still a real, structurally correct edge -- just not COMPLETE
  });

  it("(item 13) stopSequenceComplete=true but one identity fails to resolve -> still PARTIAL", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b", "pp-c"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-c": KISTA }, ["pp-b"]);
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.completeness).toBe("PARTIAL");
  });

  it("(item 14) every identity resolved AND stopSequenceComplete=true -> COMPLETE, with the full stop sequence as one ordered run", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b", "pp-c"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY, "pp-c": KISTA });
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence).toEqual({
      edges: new Set([edgeKey(AKALLA, HUSBY), edgeKey(HUSBY, KISTA)]),
      completeness: "COMPLETE",
      orderedRuns: [[AKALLA, HUSBY, KISTA]],
    });
  });

  it("(item 15) consecutive resolutions to the SAME StopArea do not create a self-edge", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a1", "pp-a2", "pp-b"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-a1": AKALLA, "pp-a2": AKALLA, "pp-b": HUSBY });
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.edges).toEqual(new Set([edgeKey(AKALLA, HUSBY)]));
    expect(evidence.edges.has(edgeKey(AKALLA, AKALLA))).toBe(false);
  });

  it("(item 16) two legs on the SAME line union their edges but are never bridged to each other", () => {
    const leg1 = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b"], stopSequenceComplete: true });
    const leg2 = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-t", "pp-k"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY, "pp-t": TCENTRALEN, "pp-k": KUNGSTRADGARDEN });
    const evidence = buildActualLegEdgesByLine(context([leg1, leg2]), resolutions).get("METRO:11")!;
    expect(evidence.edges).toEqual(new Set([edgeKey(AKALLA, HUSBY), edgeKey(TCENTRALEN, KUNGSTRADGARDEN)]));
    expect(evidence.edges.has(edgeKey(HUSBY, TCENTRALEN))).toBe(false); // never a fabricated bridge between legs
  });

  it("(item 17) one incomplete leg on a shared line makes the MERGED evidence PARTIAL even though the other leg alone was complete", () => {
    const completeLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b"], stopSequenceComplete: true });
    const incompleteLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-t", "pp-k"], stopSequenceComplete: false });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY, "pp-t": TCENTRALEN, "pp-k": KUNGSTRADGARDEN });
    const evidence = buildActualLegEdgesByLine(context([completeLeg, incompleteLeg]), resolutions).get("METRO:11")!;
    expect(evidence.completeness).toBe("PARTIAL");
  });

  it("a WALK leg (no lineDesignation) contributes nothing", () => {
    const walkLeg = leg({ transportMode: "WALK", lineDesignation: null, stopPatternPointGids: ["pp-a", "pp-b"] });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY });
    const result = buildActualLegEdgesByLine(context([walkLeg]), resolutions);
    expect(result.size).toBe(0);
  });

  it("a gap at the very start of the sequence withholds only the edge that would have crossed it, not the edge after it", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b", "pp-c"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-b": HUSBY, "pp-c": KISTA }, ["pp-a"]);
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.edges).toEqual(new Set([edgeKey(HUSBY, KISTA)]));
    expect(evidence.completeness).toBe("PARTIAL");
  });

  it("orderedRuns: a gap in the MIDDLE of a leg splits it into two separate runs, never bridged", () => {
    const theLeg = leg({
      transportMode: "METRO",
      lineDesignation: "11",
      stopPatternPointGids: ["pp-a", "pp-b", "pp-gap", "pp-t", "pp-k"],
      stopSequenceComplete: true,
    });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY, "pp-t": TCENTRALEN, "pp-k": KUNGSTRADGARDEN }, ["pp-gap"]);
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.orderedRuns).toEqual([
      [AKALLA, HUSBY],
      [TCENTRALEN, KUNGSTRADGARDEN],
    ]);
  });

  it("orderedRuns: two legs on the same line contribute their own separate runs, never merged into one", () => {
    const leg1 = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-a", "pp-b"], stopSequenceComplete: true });
    const leg2 = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-t", "pp-k"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-a": AKALLA, "pp-b": HUSBY, "pp-t": TCENTRALEN, "pp-k": KUNGSTRADGARDEN });
    const evidence = buildActualLegEdgesByLine(context([leg1, leg2]), resolutions).get("METRO:11")!;
    expect(evidence.orderedRuns).toEqual([
      [AKALLA, HUSBY],
      [TCENTRALEN, KUNGSTRADGARDEN],
    ]);
  });

  it("orderedRuns: a single resolved stop with no resolved neighbor on either side contributes no run at all (no traversal evidence)", () => {
    const theLeg = leg({ transportMode: "METRO", lineDesignation: "11", stopPatternPointGids: ["pp-gap1", "pp-a", "pp-gap2"], stopSequenceComplete: true });
    const resolutions = resolutionsFor({ "pp-a": AKALLA }, ["pp-gap1", "pp-gap2"]);
    const evidence = buildActualLegEdgesByLine(context([theLeg]), resolutions).get("METRO:11")!;
    expect(evidence.orderedRuns).toEqual([]);
    expect(evidence.edges.size).toBe(0);
  });
});
