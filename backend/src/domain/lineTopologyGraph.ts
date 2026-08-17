/**
 * Pure graph construction/query logic for one transport line's own static topology — no GTFS
 * parsing, no upstream I/O, no caching (see `services/lineTopologyDirectory.ts` for all of
 * that). Deliberately NOT a routing engine (see this feature's own spec, item 25): the only
 * question this module answers is "does exactly one trip pattern's own stop sequence span both
 * of these two StopAreas, and if so, which edges does it use between them" — never shortest-path
 * search, never transfer routing, never stitching separate patterns together to cover a span no
 * single real trip actually covers. See `resolveSegmentEdges`'s own doc for exactly why.
 */

/** One StopArea's position within one trip's own real stop sequence — already translated from
 * whatever raw GTFS `stop_id` a trip used to the StopArea id space the rest of this backend's
 * disruption-relevance code already operates in (see `services/lineTopologyDirectory.ts`'s own
 * `GtfsStopIdResolver`). `sequence` need not start at 0 or be contiguous — only its relative
 * order within the same `tripId` matters. */
export interface TripStopSequenceEntry {
  tripId: string;
  stopAreaId: number;
  sequence: number;
}

/**
 * A direction-independent edge between two adjacent StopAreas, represented as the single string
 * `"${min}:${max}"` — `"mellan A och B"` is direction-independent (see this feature's own spec,
 * case 5), so an edge is never stored, compared, or intersected with an implied direction. A
 * plain string key (rather than an `{a,b}` object pair) specifically so every edge SET below is
 * an ordinary `Set<string>` — trivially and unambiguously comparable/testable, with no risk of
 * the reference-equality footgun a `Set` of plain objects would otherwise have in JavaScript.
 */
export type StopAreaEdgeKey = string;

export function edgeKey(stopAreaA: number, stopAreaB: number): StopAreaEdgeKey {
  return stopAreaA <= stopAreaB ? `${stopAreaA}:${stopAreaB}` : `${stopAreaB}:${stopAreaA}`;
}

/** One line's own static topology — every distinct StopArea it touches, and the adjacency
 * relation between them, built from every distinct trip stop-sequence (see
 * `buildLineTopologyGraph`'s own doc for exactly how duplicate/short-turn/branch patterns are
 * handled). */
export interface LineTopologyGraph {
  nodes: ReadonlySet<number>;
  /** Deduplicated, direction-independent edges actually observed across every trip pattern. */
  edges: ReadonlySet<StopAreaEdgeKey>;
  /** The distinct trip patterns this graph was built from — kept (not discarded after merging
   * into `edges`) specifically because `resolveSegmentEdges` needs each PATTERN's own ordered
   * sub-sequence between two StopAreas, not just the flattened, pattern-agnostic adjacency set —
   * see that function's own doc for why. */
  patterns: ReadonlyArray<ReadonlyArray<number>>;
}

/**
 * Groups [entries] by `tripId`, sorts each trip's own entries by `sequence`, collapses
 * consecutive duplicate StopAreas (a real GTFS `stop_times.txt` can list the same StopArea twice
 * in a row for a stop with multiple platforms/directions at that exact position), and DEDUPLICATES
 * identical resulting sequences into distinct "patterns" — so a line with hundreds of individual
 * trips per day, the overwhelming majority sharing only a handful of genuinely distinct stopping
 * patterns (regular service, a short-turn, a branch, an express skip-stop variant), is represented
 * by that handful, not by hundreds of duplicate copies of the same pattern.
 *
 * Every consecutive pair within a pattern becomes one direction-independent edge; edges are
 * unioned across every distinct pattern (so a short-turn pattern that only covers PART of the
 * line still safely contributes its own real edges to the merged graph, without ever implying it
 * reaches the stops it doesn't include — see `resolveSegmentEdges`'s own doc for why `edges`
 * alone is NOT enough to answer "what's the affected segment between A and B", only "are these
 * two StopAreas ever directly adjacent").
 */
export function buildLineTopologyGraph(entries: readonly TripStopSequenceEntry[]): LineTopologyGraph {
  const byTrip = new Map<string, TripStopSequenceEntry[]>();
  for (const entry of entries) {
    const list = byTrip.get(entry.tripId);
    if (list) list.push(entry);
    else byTrip.set(entry.tripId, [entry]);
  }

  const nodes = new Set<number>();
  const edges = new Set<StopAreaEdgeKey>();
  const patternKeys = new Set<string>();
  const patterns: number[][] = [];

  for (const tripEntries of byTrip.values()) {
    const sorted = [...tripEntries].sort((a, b) => a.sequence - b.sequence);
    const sequence: number[] = [];
    for (const entry of sorted) {
      if (sequence[sequence.length - 1] !== entry.stopAreaId) sequence.push(entry.stopAreaId);
    }
    if (sequence.length === 0) continue;

    const patternKey = sequence.join(">");
    if (!patternKeys.has(patternKey)) {
      patternKeys.add(patternKey);
      patterns.push(sequence);
    }

    for (const stopAreaId of sequence) nodes.add(stopAreaId);
    for (let i = 0; i < sequence.length - 1; i++) edges.add(edgeKey(sequence[i]!, sequence[i + 1]!));
  }

  return { nodes, edges, patterns };
}

/**
 * `"RESOLVED"` — exactly one distinct trip pattern's own stop sequence contains BOTH [stopAreaA]
 * and [stopAreaB]; the edges between them, drawn from that one pattern's own real order, are
 * uniquely determined — `edges` is never empty (a pattern containing both stops always has at
 * least one edge between their positions, since the sequence is deduplicated of consecutive
 * repeats first).
 *
 * `"AMBIGUOUS"` — MORE than one distinct trip pattern contains both stops, and those patterns
 * disagree about the edge set between them (a genuine branch/alternate-routing case — see this
 * feature's own spec, item 8: never arbitrarily prefer one such pattern over another).
 * Patterns that agree (the ordinary case: an express pattern and a local pattern both traverse
 * the exact same edges between two shared stops, differing only in which OTHER stops they skip
 * elsewhere) do not count as a disagreement — same edge SET, not same full pattern.
 *
 * `"UNRESOLVED"` — no single trip pattern's own sequence spans both stops at all. This
 * deliberately does NOT fall back to a general shortest-path search stitching separate patterns
 * together (see this module's own top-level doc): if no real observed trip ever actually ran
 * from [stopAreaA] to [stopAreaB], asserting an affected corridor between them from the raw
 * adjacency graph alone would not be genuine trip-pattern evidence, only a graph-theoretic
 * possibility — exactly the kind of guess this feature's own spec repeatedly rules out.
 *
 * A RESOLVED result also carries [orderedStopAreaIds] — the one uniquely-determined stop sequence
 * between [stopAreaA] and [stopAreaB] (in the contributing pattern's own forward direction), used
 * by `requestedCorridor.ts`'s own boundary-alignment trust check to test whether a real travelled
 * run's own terminal stop is exactly this segment's own endpoint.
 */
export type SegmentEdgeResolution =
  | { status: "RESOLVED"; edges: ReadonlySet<StopAreaEdgeKey>; orderedStopAreaIds: readonly number[] }
  | { status: "AMBIGUOUS" }
  | { status: "UNRESOLVED" };

interface SegmentEdgeCandidate {
  edges: ReadonlySet<StopAreaEdgeKey>;
  orderedStopAreaIds: readonly number[];
}

function allIndexesOf(pattern: readonly number[], stopAreaId: number): number[] {
  const indexes: number[] = [];
  for (let i = 0; i < pattern.length; i++) if (pattern[i] === stopAreaId) indexes.push(i);
  return indexes;
}

/**
 * Every structurally distinct way [stopAreaA] and [stopAreaB] can be connected WITHIN one
 * pattern — one candidate per (occurrence of A, occurrence of B) index pair, never just each
 * stop's FIRST occurrence. A repeated stop (a loop route, or an ordinary bus revisiting one hub
 * twice) can make the same two StopAreas connectable via genuinely different real edge sets
 * depending on WHICH occurrence of each is meant: pattern `1-2-3-1-4`, querying `1<->4`, the
 * (index 0, index 4) pair implies the vehicle travelled the whole loop `1-2-3-1-4`, while the
 * (index 3, index 4) pair implies only the final `1-4` hop — neither interpretation is more
 * "correct" than the other from the pattern alone. [stopAreaA] and [stopAreaB] are always
 * distinct (the caller already rejects `stopAreaA === stopAreaB`), so every pair here spans at
 * least one real edge.
 */
function edgesBetween(pattern: readonly number[], stopAreaA: number, stopAreaB: number): SegmentEdgeCandidate[] {
  const candidates: SegmentEdgeCandidate[] = [];
  for (const indexA of allIndexesOf(pattern, stopAreaA)) {
    for (const indexB of allIndexesOf(pattern, stopAreaB)) {
      const [lo, hi] = indexA <= indexB ? [indexA, indexB] : [indexB, indexA];
      const edges = new Set<StopAreaEdgeKey>();
      for (let i = lo; i < hi; i++) edges.add(edgeKey(pattern[i]!, pattern[i + 1]!));
      candidates.push({ edges, orderedStopAreaIds: pattern.slice(lo, hi + 1) });
    }
  }
  return candidates;
}

function edgeSetIdentity(edges: ReadonlySet<StopAreaEdgeKey>): string {
  return [...edges].sort().join(",");
}

/**
 * Combines every candidate found across EVERY pattern (see `edgesBetween`'s own doc for why one
 * pattern alone can already contribute more than one candidate) into a single verdict, by the
 * SAME direction-independent edge-set-identity comparison this function has always used for
 * cross-pattern ambiguity: zero candidates found at all -> UNRESOLVED; every candidate (whichever
 * pattern or occurrence pair it came from) agrees on the exact same edge set -> RESOLVED; two or
 * more candidates disagree -> AMBIGUOUS. This means an unrelated repeated stop elsewhere in a
 * pattern — one that is NEITHER [stopAreaA] nor [stopAreaB] — never contributes extra candidates
 * and so can never by itself force ambiguity; only a repeat of [stopAreaA] and/or [stopAreaB]
 * themselves can.
 */
export function resolveSegmentEdges(graph: LineTopologyGraph, stopAreaA: number, stopAreaB: number): SegmentEdgeResolution {
  if (stopAreaA === stopAreaB) return { status: "UNRESOLVED" };

  const candidates: SegmentEdgeCandidate[] = [];
  for (const pattern of graph.patterns) {
    candidates.push(...edgesBetween(pattern, stopAreaA, stopAreaB));
  }
  if (candidates.length === 0) return { status: "UNRESOLVED" };

  const distinctIdentities = new Set(candidates.map((c) => edgeSetIdentity(c.edges)));
  if (distinctIdentities.size > 1) return { status: "AMBIGUOUS" };
  return { status: "RESOLVED", edges: candidates[0]!.edges, orderedStopAreaIds: candidates[0]!.orderedStopAreaIds };
}

/** True if [edges] (an affected/corridor edge set, direction-independent) shares at least one
 * edge with [other] — the one comparison `disruptionRelevance.ts`'s own segment-aware resolution
 * rule (item 17 of this feature's own spec) actually needs: `affectedEdges ∩ corridorEdges`. */
export function edgeSetsIntersect(edges: ReadonlySet<StopAreaEdgeKey>, other: ReadonlySet<StopAreaEdgeKey>): boolean {
  for (const key of edges) if (other.has(key)) return true;
  return false;
}

/** Builds a direction-independent edge set from an ORDERED list of StopAreas actually
 * travelled (consecutive pairs) — the shared representation both a journey's own actual PRIMARY
 * leg edges and its requested normal corridor edges are expressed in, so `edgeSetsIntersect`
 * compares like with like regardless of which side produced them. */
export function edgesFromOrderedStops(orderedStopAreaIds: readonly number[]): ReadonlySet<StopAreaEdgeKey> {
  const edges = new Set<StopAreaEdgeKey>();
  for (let i = 0; i < orderedStopAreaIds.length - 1; i++) {
    edges.add(edgeKey(orderedStopAreaIds[i]!, orderedStopAreaIds[i + 1]!));
  }
  return edges;
}
