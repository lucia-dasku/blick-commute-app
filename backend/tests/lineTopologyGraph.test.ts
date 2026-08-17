import { describe, expect, it } from "vitest";
import {
  buildLineTopologyGraph,
  edgeKey,
  edgeSetsIntersect,
  edgesFromOrderedStops,
  resolveSegmentEdges,
  type TripStopSequenceEntry,
} from "../src/domain/lineTopologyGraph.js";

// Stand-ins for real StopArea ids along a simplified Metro 13-shaped line:
// Slussen(1011) - Mariatorget(1210) - Zinkensdamm(1220) - ... - Mälarhöjden(1360)
const SLUSSEN = 1011;
const MARIATORGET = 1210;
const ZINKENSDAMM = 1220;
const HORNSTULL = 1230;
const MALARHOJDEN = 1360;
const T_CENTRALEN = 1051;
const KUNGSTRADGARDEN = 1016;
const AKALLA = 3271;
const HUSBY = 3261;
const KISTA = 3251;

function fullLinePattern(tripId: string, stops: number[]): TripStopSequenceEntry[] {
  return stops.map((stopAreaId, index) => ({ tripId, stopAreaId, sequence: index }));
}

describe("buildLineTopologyGraph: consecutive edges", () => {
  it("a single trip produces exactly the consecutive-pair edges, in order", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]));
    expect(graph.edges).toEqual(
      new Set([edgeKey(SLUSSEN, MARIATORGET), edgeKey(MARIATORGET, ZINKENSDAMM), edgeKey(ZINKENSDAMM, MALARHOJDEN)]),
    );
    expect(graph.nodes).toEqual(new Set([SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]));
  });

  it("stop_id order (sequence field), not array order, determines adjacency", () => {
    const entries: TripStopSequenceEntry[] = [
      { tripId: "t1", stopAreaId: MALARHOJDEN, sequence: 2 },
      { tripId: "t1", stopAreaId: SLUSSEN, sequence: 0 },
      { tripId: "t1", stopAreaId: MARIATORGET, sequence: 1 },
    ];
    const graph = buildLineTopologyGraph(entries);
    expect(graph.edges).toEqual(new Set([edgeKey(SLUSSEN, MARIATORGET), edgeKey(MARIATORGET, MALARHOJDEN)]));
  });

  it("consecutive duplicate StopAreas in the same trip collapse to one node, no self-edge", () => {
    const entries: TripStopSequenceEntry[] = [
      { tripId: "t1", stopAreaId: SLUSSEN, sequence: 0 },
      { tripId: "t1", stopAreaId: SLUSSEN, sequence: 1 }, // duplicate platform entry at the same position
      { tripId: "t1", stopAreaId: MARIATORGET, sequence: 2 },
    ];
    const graph = buildLineTopologyGraph(entries);
    expect(graph.edges).toEqual(new Set([edgeKey(SLUSSEN, MARIATORGET)]));
    expect(graph.edges.has(edgeKey(SLUSSEN, SLUSSEN))).toBe(false);
  });
});

describe("buildLineTopologyGraph: multiple trip patterns deduplicate", () => {
  it("many trips sharing the exact same stop sequence collapse to one pattern", () => {
    const entries = [
      ...fullLinePattern("t1", [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]),
      ...fullLinePattern("t2", [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]),
      ...fullLinePattern("t3", [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]),
    ];
    const graph = buildLineTopologyGraph(entries);
    expect(graph.patterns).toHaveLength(1);
  });

  it("a short-turn pattern (terminates early) is kept as its own distinct pattern, and does not destroy the full graph's own edges", () => {
    const entries = [
      ...fullLinePattern("full", [SLUSSEN, MARIATORGET, ZINKENSDAMM, HORNSTULL, MALARHOJDEN]),
      ...fullLinePattern("short-turn", [SLUSSEN, MARIATORGET, ZINKENSDAMM]), // turns back before Hornstull
    ];
    const graph = buildLineTopologyGraph(entries);
    expect(graph.patterns).toHaveLength(2);
    // The full pattern's own edges beyond the short-turn's terminus are still present.
    expect(graph.edges.has(edgeKey(ZINKENSDAMM, HORNSTULL))).toBe(true);
    expect(graph.edges.has(edgeKey(HORNSTULL, MALARHOJDEN))).toBe(true);
  });
});

describe("resolveSegmentEdges: RESOLVED", () => {
  it("adjacent stops resolve to the single edge between them", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]));
    const result = resolveSegmentEdges(graph, SLUSSEN, MARIATORGET);
    expect(result).toEqual({ status: "RESOLVED", edges: new Set([edgeKey(SLUSSEN, MARIATORGET)]), orderedStopAreaIds: [SLUSSEN, MARIATORGET] });
  });

  it("non-adjacent stops on the same pattern resolve to every edge along the way", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]));
    const result = resolveSegmentEdges(graph, SLUSSEN, MALARHOJDEN);
    expect(result).toEqual({
      status: "RESOLVED",
      edges: new Set([edgeKey(SLUSSEN, MARIATORGET), edgeKey(MARIATORGET, ZINKENSDAMM), edgeKey(ZINKENSDAMM, MALARHOJDEN)]),
      orderedStopAreaIds: [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN],
    });
  });

  it("reversed direction resolves to the exact same edge set -- mellan A och B is direction-independent", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [AKALLA, HUSBY, KISTA, T_CENTRALEN]));
    const forward = resolveSegmentEdges(graph, AKALLA, T_CENTRALEN);
    const backward = resolveSegmentEdges(graph, T_CENTRALEN, AKALLA);
    expect(forward).toEqual(backward);
  });

  it("agreeing patterns (an express pattern skipping unrelated stops elsewhere) still resolve uniquely, not ambiguous", () => {
    const graph = buildLineTopologyGraph([
      ...fullLinePattern("local", [SLUSSEN, MARIATORGET, ZINKENSDAMM, HORNSTULL, MALARHOJDEN]),
      // A different pattern that skips Hornstull entirely elsewhere in its own route, but agrees
      // exactly on the Slussen<->Mariatorget edge.
      ...fullLinePattern("express", [SLUSSEN, MARIATORGET, MALARHOJDEN]),
    ]);
    expect(resolveSegmentEdges(graph, SLUSSEN, MARIATORGET)).toEqual({
      status: "RESOLVED",
      edges: new Set([edgeKey(SLUSSEN, MARIATORGET)]),
      orderedStopAreaIds: [SLUSSEN, MARIATORGET],
    });
  });
});

describe("resolveSegmentEdges: repeated-stop / loop-route handling (a bus or circular route revisiting one hub)", () => {
  // Generic, structural stop ids -- this fix must hold for any line's own topology, not just a
  // real station layout.
  it("simple linear, no repeats: segment 2<->4 on pattern 1-2-3-4 resolves to {2<->3, 3<->4}", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [1, 2, 3, 4]));
    expect(resolveSegmentEdges(graph, 2, 4)).toEqual({ status: "RESOLVED", edges: new Set([edgeKey(2, 3), edgeKey(3, 4)]), orderedStopAreaIds: [2, 3, 4] });
  });

  it("reverse query order gives the identical resolved edge set", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [1, 2, 3, 4]));
    expect(resolveSegmentEdges(graph, 4, 2)).toEqual(resolveSegmentEdges(graph, 2, 4));
  });

  it("a repeated ENDPOINT stop makes two structurally different interpretations AMBIGUOUS -- pattern 1-2-3-1-4, segment 1<->4", () => {
    // Occurrence pair (first 1, the 4) implies the vehicle travelled the whole loop 1-2-3-1-4;
    // occurrence pair (second 1, the 4) implies only the final 1-4 hop -- genuinely different edge
    // sets, so this must never silently pick one interpretation over the other.
    const graph = buildLineTopologyGraph(fullLinePattern("loop", [1, 2, 3, 1, 4]));
    expect(resolveSegmentEdges(graph, 1, 4)).toEqual({ status: "AMBIGUOUS" });
  });

  it("a repeated endpoint where every occurrence-pair interpretation happens to agree is still RESOLVED, not automatically ambiguous", () => {
    // Pattern 1-2-1-2: querying 1<->2 has FOUR occurrence-pair interpretations (indices (0,1),
    // (0,3), (2,1), (2,3)), but every one of them reduces to the exact same direction-independent
    // edge set {1<->2} -- repetition alone must never force ambiguity when the underlying edges
    // genuinely agree.
    const graph = buildLineTopologyGraph(fullLinePattern("figure-eight", [1, 2, 1, 2]));
    expect(resolveSegmentEdges(graph, 1, 2)).toEqual({ status: "RESOLVED", edges: new Set([edgeKey(1, 2)]), orderedStopAreaIds: [1, 2] });
  });

  it("a repeated stop elsewhere in the pattern that is NEITHER query endpoint does not force ambiguity", () => {
    // Stop 2 repeats in the middle of the pattern, but the query (1<->4) does not involve it --
    // only ONE occurrence of 1 and ONE occurrence of 4 exist, so there is only ever one candidate.
    const graph = buildLineTopologyGraph(fullLinePattern("hub-revisit", [1, 2, 3, 2, 4]));
    expect(resolveSegmentEdges(graph, 1, 4)).toEqual({
      status: "RESOLVED",
      edges: new Set([edgeKey(1, 2), edgeKey(2, 3), edgeKey(2, 4)]),
      orderedStopAreaIds: [1, 2, 3, 2, 4],
    });
  });

  it("within-pattern occurrence ambiguity combines correctly with cross-pattern ambiguity", () => {
    // Pattern "direct" has no repeats and cleanly resolves 1<->4 to {1<->2, 2<->4}. Pattern
    // "harmless-repeat" repeats stop 1, but (per the above) every occurrence-pair interpretation
    // within IT agrees with itself on {1<->7, 7<->4} -- so it is not internally ambiguous on its
    // own. The two patterns nonetheless genuinely disagree with EACH OTHER, and that cross-pattern
    // disagreement must still surface as AMBIGUOUS.
    const graph = buildLineTopologyGraph([...fullLinePattern("direct", [1, 2, 4]), ...fullLinePattern("harmless-repeat", [1, 7, 1, 7, 4])]);
    expect(resolveSegmentEdges(graph, 1, 4)).toEqual({ status: "AMBIGUOUS" });
  });
});

describe("resolveSegmentEdges: AMBIGUOUS -- genuine branch topology", () => {
  it("two patterns that both contain A and B but disagree on the edges between them are AMBIGUOUS, never guessed", () => {
    const graph = buildLineTopologyGraph([
      ...fullLinePattern("via-mariatorget", [SLUSSEN, MARIATORGET, MALARHOJDEN]),
      // A structurally different branch also connecting Slussen and Mälarhöjden, but via a
      // completely different intermediate stop -- a genuine two-route ambiguity.
      ...fullLinePattern("via-hornstull", [SLUSSEN, HORNSTULL, MALARHOJDEN]),
    ]);
    expect(resolveSegmentEdges(graph, SLUSSEN, MALARHOJDEN)).toEqual({ status: "AMBIGUOUS" });
  });

  it("does not arbitrarily prefer the shorter, first, or more common pattern when genuinely ambiguous", () => {
    const graph = buildLineTopologyGraph([
      ...fullLinePattern("shorter", [SLUSSEN, MALARHOJDEN]), // direct, fewer stops
      ...fullLinePattern("shorter-dup", [SLUSSEN, MALARHOJDEN]), // "more common" -- same as above
      ...fullLinePattern("shorter-dup2", [SLUSSEN, MALARHOJDEN]),
      ...fullLinePattern("longer-different-route", [SLUSSEN, MARIATORGET, MALARHOJDEN]),
    ]);
    // Even though the direct pattern is both shorter and (by trip count) more common, a
    // genuinely different edge set exists between the same two stops -- must still be AMBIGUOUS.
    expect(resolveSegmentEdges(graph, SLUSSEN, MALARHOJDEN)).toEqual({ status: "AMBIGUOUS" });
  });
});

describe("resolveSegmentEdges: UNRESOLVED", () => {
  it("no trip pattern spans both stops at all", () => {
    const graph = buildLineTopologyGraph([
      ...fullLinePattern("north-branch", [AKALLA, HUSBY, KISTA]),
      ...fullLinePattern("south-branch", [SLUSSEN, MARIATORGET, MALARHOJDEN]),
    ]);
    expect(resolveSegmentEdges(graph, AKALLA, MALARHOJDEN)).toEqual({ status: "UNRESOLVED" });
  });

  it("the same StopArea for both endpoints is UNRESOLVED, never a zero-length RESOLVED edge set", () => {
    const graph = buildLineTopologyGraph(fullLinePattern("t1", [SLUSSEN, MARIATORGET, MALARHOJDEN]));
    expect(resolveSegmentEdges(graph, SLUSSEN, SLUSSEN)).toEqual({ status: "UNRESOLVED" });
  });

  it("an empty graph resolves to UNRESOLVED, never throws", () => {
    const graph = buildLineTopologyGraph([]);
    expect(resolveSegmentEdges(graph, SLUSSEN, MARIATORGET)).toEqual({ status: "UNRESOLVED" });
  });
});

describe("edgeSetsIntersect / edgesFromOrderedStops", () => {
  it("shares an edge -> true", () => {
    const primary = edgesFromOrderedStops([SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN]);
    const affected = new Set([edgeKey(MARIATORGET, ZINKENSDAMM)]);
    expect(edgeSetsIntersect(affected, primary)).toBe(true);
  });

  it("no shared edge -> false, even when a node is shared (edge overlap, not station overlap)", () => {
    // Slussen -> Akalla (a different, unrelated corridor) never traverses Slussen<->Mariatorget,
    // even though Slussen itself is the shared boundary node -- see this feature's own spec, item 2.
    const primary = edgesFromOrderedStops([T_CENTRALEN, AKALLA]);
    const affected = edgesFromOrderedStops([T_CENTRALEN, KUNGSTRADGARDEN]);
    expect(edgeSetsIntersect(affected, primary)).toBe(false);
  });

  it("edgesFromOrderedStops of a single stop produces no edges", () => {
    expect(edgesFromOrderedStops([SLUSSEN])).toEqual(new Set());
  });
});
