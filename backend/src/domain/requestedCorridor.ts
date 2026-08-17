import type { Site } from "../models/site.js";
import type { JourneyDisruptionContext } from "../models/journeyDisruptionContext.js";
import type { PatternPointGid, StopPointResolution } from "../services/stopPointDirectory.js";
import { edgeKey, type LineTopologyGraph, type StopAreaEdgeKey } from "./lineTopologyGraph.js";

/**
 * Item 15 of this feature's own spec: a Site may contain multiple StopAreas (a multi-mode
 * complex like T-Centralen or Slussen — see `journeyDisruptionScope.ts`'s own
 * `resolveExactJourneyOriginStopArea` for the identical narrowing problem this mirrors, one
 * layer up). Narrows a resolved endpoint Site to the ONE StopArea that actually belongs to
 * [graph]'s own specific line topology — never an arbitrary first pick.
 */
export type EndpointStopAreaResolution = { status: "RESOLVED"; stopAreaId: number } | { status: "AMBIGUOUS" } | { status: "UNRESOLVED" };

export function resolveEndpointStopAreaOnLine(site: Site, graph: LineTopologyGraph): EndpointStopAreaResolution {
  const onLine = site.stopAreaIds.filter((id) => graph.nodes.has(id));
  if (onLine.length === 0) return { status: "UNRESOLVED" };
  if (onLine.length > 1) return { status: "AMBIGUOUS" };
  return { status: "RESOLVED", stopAreaId: onLine[0]! };
}

/**
 * The requested normal corridor's own ORDERED stop sequence (origin first, destination last — see
 * `LineTopologyDirectory.resolveEndpointsCorridor`'s own doc for that orientation guarantee),
 * PLUS the specific parsed affected segment's own two endpoints — trust is decided PER CANDIDATE
 * (see {@link isRequestedCorridorTrusted}'s own doc for why), never once per line, so the affected
 * endpoints are part of the trust QUESTION itself, not a separate downstream check.
 */
export interface RequestedCorridorTrustCheck {
  requestedCorridorOrderedStopAreaIds: readonly number[];
  /** PRIMARY's own real travelled runs on this SAME line, each in true chronological travel order
   * — see {@link ActualLineEdgeEvidence.orderedRuns}'s own doc. Empty when PRIMARY never uses this
   * line at all. */
  actualRunsOnThisLine: ReadonlyArray<readonly number[]>;
  affectedStopAreaA: number;
  affectedStopAreaB: number;
}

/** `run` is EXACTLY [full]'s own leading stops, position by position — never merely "every stop
 * in `run` also appears somewhere in `full`" (an unordered subset check would also accept a run
 * that skips an intermediate stop, e.g. `[A, C]` against `[A, B, C, D]`, which is NOT a real
 * prefix of this specific requested corridor even if `A<->C` happened to be an edge somewhere
 * else in the network). */
function isExactPrefix(full: readonly number[], run: readonly number[]): boolean {
  if (run.length > full.length) return false;
  for (let i = 0; i < run.length; i++) {
    if (run[i] !== full[i]) return false;
  }
  return true;
}

/** `run` is EXACTLY [full]'s own trailing stops, position by position — the mirror of
 * {@link isExactPrefix}, for the suffix/reverse-truncation case. */
function isExactSuffix(full: readonly number[], run: readonly number[]): boolean {
  if (run.length > full.length) return false;
  const offset = full.length - run.length;
  for (let i = 0; i < run.length; i++) {
    if (run[i] !== full[offset + i]) return false;
  }
  return true;
}

/**
 * Item 16 of this feature's own spec, corrected TWICE now by production-readiness review: first
 * from "shares any edge anywhere" to an unordered "edge subset + one boundary stop matches",
 * and now from THAT to genuine ORDERED prefix/suffix equality — the "requested normal corridor" is
 * powerful evidence (it survives a reroute that moved PRIMARY off the affected line entirely — see
 * this feature's own spec, item 13's Akalla -> Kungsträdgården example), but ALSO the riskiest
 * evidence to trust wrong.
 *
 * ## Why edge-subset + boundary-match was still not enough
 *
 * An unordered edge-subset check cannot distinguish a genuine REROUTE TRUNCATION from an ordinary
 * INTERNAL FRAGMENT: requested `A-B-C-D-E`, actual run `B-C` — the run's own edges (`{B<->C}`) ARE
 * a subset of the requested corridor's edges, and `C` genuinely IS one endpoint of affected
 * segment `C<->D`, so the previous rule trusted this. But nothing about `B-C` proves the passenger
 * was ever supposed to continue past `C` toward `D` and `E` — `B` and `C` might simply be this
 * leg's own ordinary boarding/alighting stops (an unremarkable transfer), entirely unconnected to
 * any disruption. Only a run that genuinely STARTS at the requested origin (a true prefix) or
 * genuinely ENDS at the requested destination (a true suffix) — and is truncated exactly at the
 * affected boundary — supports the "this is where the reroute cut the journey short" inference.
 *
 * ## The corrected rule
 *
 * For AT LEAST ONE of [actualRunsOnThisLine] (each already gap-free and in true travel order — see
 * `buildActualLegEdgesByLine`'s own doc), exactly one of:
 *
 * - **Full match**: `run` is ordered-equal to the ENTIRE [requestedCorridorOrderedStopAreaIds] —
 *   the requested corridor was travelled exactly as requested, with no truncation at all. Trusted
 *   UNCONDITIONALLY (no boundary check needed): this is a complete, gap-free, independently
 *   reconstructed account of PRIMARY's real path on this line, valid evidence for ANY candidate on
 *   it, not only ones anchored at a truncation point — see item 18 of this feature's own spec.
 * - **True prefix**: `run` is an EXACT ordered prefix of the requested corridor ({@link
 *   isExactPrefix}) AND `run`'s own LAST stop is exactly [affectedStopAreaA] or
 *   [affectedStopAreaB] — the reroute/truncation shape (Akalla -> T-Centralen, cut short exactly
 *   at the closure's own boundary).
 * - **True suffix**: `run` is an EXACT ordered suffix of the requested corridor ({@link
 *   isExactSuffix}) AND `run`'s own FIRST stop is exactly [affectedStopAreaA] or
 *   [affectedStopAreaB] — the reverse-direction equivalent (a journey that only picks up the line
 *   partway through, exactly at the affected boundary, and rides it to the requested destination).
 *
 * Everything else — including an internal fragment that starts after the requested origin AND
 * ends before the requested destination, however cleanly its own edges sit inside the corridor —
 * is NOT trusted. Ordered-sequence equality (never an edge-set subset, never sorted ids, never
 * numeric-id direction inference) is what makes this a genuine prefix/suffix check rather than an
 * approximation of one; see `LineTopologyDirectory.resolveEndpointsCorridor`'s own doc for how
 * [requestedCorridorOrderedStopAreaIds] is guaranteed oriented origin-first before it ever reaches
 * here, which this function depends on completely (reversing that orientation would silently swap
 * which runs count as a "prefix" vs a "suffix").
 */
export function isRequestedCorridorTrusted(check: RequestedCorridorTrustCheck): boolean {
  const { requestedCorridorOrderedStopAreaIds: requested, actualRunsOnThisLine, affectedStopAreaA, affectedStopAreaB } = check;
  if (requested.length === 0) return false;

  for (const run of actualRunsOnThisLine) {
    if (run.length < 2) continue; // no traversal evidence at all

    if (isExactPrefix(requested, run)) {
      if (run.length === requested.length) return true; // full match, unconditional
      const truncatedAt = run[run.length - 1]!;
      if (truncatedAt === affectedStopAreaA || truncatedAt === affectedStopAreaB) return true;
      continue; // a genuine prefix, but truncated somewhere other than this candidate's own boundary
    }

    if (isExactSuffix(requested, run)) {
      const truncatedAt = run[0]!;
      if (truncatedAt === affectedStopAreaA || truncatedAt === affectedStopAreaB) return true;
    }
  }
  return false;
}

/** `${transportMode}:${lineDesignation}` — the key `buildActualLegEdgesByLine`'s own map uses,
 * matching exactly how a deviation's own `scope.lines` entries are already compared elsewhere in
 * this feature (transport mode AND designation together, never either alone — see
 * `resolveDeviationRelevance`'s own doc). */
export function actualLegEdgesKey(transportMode: string, lineDesignation: string): string {
  return `${transportMode}:${lineDesignation}`;
}

/**
 * PRIMARY's own real travelled edges for one line — POSITIVE (overlap/confirming) evidence only.
 * See `disruptionRelevance.ts`'s own `evaluateLineSegmentEvidence` doc for the full reasoning, but
 * the short version: actual PRIMARY edges may NEVER be used to conclude a NEGATIVE (no-overlap)
 * verdict, regardless of [completeness] — Journey Planner's own CURRENT PRIMARY may already be the
 * RESULT of rerouting around the very disruption being evaluated (confirmed live: Akalla ->
 * Kungsträdgården during the Metro 11 T-Centralen<->Kungsträdgården closure reroutes onto Metro 13
 * + a walk, terminating cleanly at T-Centralen with a fully resolved, complete stop sequence that
 * simply never goes anywhere near the closed edge — "complete" here describes how well this
 * backend resolved PRIMARY's OWN current path, not whether that path reflects the passenger's
 * normally-intended route). Only an independently-reconstructed, trusted REQUESTED corridor (see
 * `isRequestedCorridorTrusted`) can ever supply that negative proof.
 *
 * `"COMPLETE"` only when EVERY contributing leg on this line (see `buildActualLegEdgesByLine`'s
 * own doc on why more than one leg can share a line) had `stopSequenceComplete === true` AND
 * every one of its own `stopPatternPointGids` resolved to at least StopArea precision — otherwise
 * `"PARTIAL"`. Still tracked (rather than dropped now that it can no longer license a negative
 * verdict) because it still changes what a caller can respond to a positive/confirming check with;
 * `"PARTIAL"` evidence's own `edges` remain real, structurally correct edges (never fabricated
 * across a gap — see `buildActualLegEdgesByLine`'s own doc) and are always safe as POSITIVE
 * evidence, independent of completeness.
 */
export interface ActualLineEdgeEvidence {
  edges: ReadonlySet<StopAreaEdgeKey>;
  completeness: "COMPLETE" | "PARTIAL";
  /** Every distinct, gap-free run of real consecutive travel on this line, in stop order — the
   * input {@link isRequestedCorridorTrusted} needs to test whether a run's own terminal boundary
   * lines up with a specific affected segment's own endpoint (see that function's own doc). A run
   * with fewer than two resolved stops carries no edge/traversal evidence and is never included.
   * Multiple legs sharing one line (see `buildActualLegEdgesByLine`'s own doc) contribute their
   * own separate runs, never bridged to each other. */
  orderedRuns: ReadonlyArray<readonly number[]>;
}

/**
 * PRIMARY's own REAL travelled edges, grouped by the exact line each transit leg used — the
 * "actual PRIMARY leg edges" item 12 of this feature's own spec describes, and the one required
 * input `isRequestedCorridorTrusted` needs for its own item 16.3 check. Deliberately computed
 * independently of `journeyDisruptionScope.ts`'s own `resolveLegScopes` (which is kept completely
 * unchanged by this feature) rather than threading a new ordered-edge field through that module's
 * existing `ScopeSet`/`ResolvedLegScope` types: [resolutions] is the exact same
 * `StopPointDirectory.resolveMany` result the route handler already computed once for that call,
 * just consulted a second, independent time here — no new upstream/cache round trip.
 *
 * ## Never bridging an unresolved PRIMARY stop
 *
 * An earlier version of this function filtered out any `stopPatternPointGids` entry whose own
 * `StopPointDirectory` resolution failed BEFORE building edges from the remaining (now
 * contiguous-looking) array — so a real Journey Planner sequence `A -> unresolved-B -> C` was
 * silently turned into edges `[A, C]`, fabricating an A<->C edge PRIMARY never actually travelled
 * (confirmed live-reproducible bug; see `requestedCorridor.test.ts`'s own gap regression tests).
 *
 * This version walks the ORIGINAL sequence position by position, tracking only the most recently
 * seen RESOLVED StopArea (`lastStopAreaId`): an unresolved position resets that tracker to `null`
 * (so nothing on either side of the gap is ever connected) and marks this leg's own completeness
 * `"PARTIAL"`, while two consecutive resolutions to the exact SAME StopArea (a platform-level
 * distinction Blick doesn't need here) collapse without creating a meaningless self-edge — never
 * fewer real edges than a naive "just look at the endpoints" approach, and never a fabricated one
 * across a genuine gap.
 *
 * Only `"RESOLVED"`/`"STOP_AREA_ONLY"` resolutions contribute a StopArea (mirroring
 * `journeyDisruptionScope.ts`'s own `resolvedStopArea` helper exactly). A WALK leg
 * (`lineDesignation` absent) contributes nothing. Multiple distinct legs sharing one line (a
 * transfer back onto the same line later in the same journey — rare but structurally possible)
 * have their own edges UNIONED, never implicitly connected to each other (the end of leg 1 is
 * never wired to the start of leg 2) — and the merged completeness is `"COMPLETE"` only when
 * EVERY contributing leg was itself complete (production-readiness review, item 10).
 */
export function buildActualLegEdgesByLine(
  context: JourneyDisruptionContext,
  resolutions: ReadonlyMap<PatternPointGid, StopPointResolution>,
): ReadonlyMap<string, ActualLineEdgeEvidence> {
  const result = new Map<string, ActualLineEdgeEvidence>();
  for (const leg of context.legs) {
    if (leg.lineDesignation == null) continue;

    const edges = new Set<StopAreaEdgeKey>();
    const runs: number[][] = [];
    let currentRun: number[] = [];
    let legComplete = leg.stopSequenceComplete;
    let lastStopAreaId: number | null = null;

    for (const gid of leg.stopPatternPointGids) {
      const resolution = resolutions.get(gid);
      const stopAreaId =
        resolution?.status === "RESOLVED" || resolution?.status === "STOP_AREA_ONLY" ? resolution.stopAreaId : null;
      if (stopAreaId == null) {
        legComplete = false;
        lastStopAreaId = null; // an unresolved position breaks continuity -- never bridge across it
        if (currentRun.length >= 2) runs.push(currentRun);
        currentRun = [];
        continue;
      }
      if (lastStopAreaId != null && stopAreaId !== lastStopAreaId) edges.add(edgeKey(lastStopAreaId, stopAreaId));
      if (stopAreaId !== lastStopAreaId) currentRun.push(stopAreaId);
      lastStopAreaId = stopAreaId;
    }
    if (currentRun.length >= 2) runs.push(currentRun);

    const key = actualLegEdgesKey(leg.transportMode, leg.lineDesignation);
    const existing = result.get(key);
    result.set(
      key,
      existing
        ? {
            edges: new Set([...existing.edges, ...edges]),
            completeness: existing.completeness === "COMPLETE" && legComplete ? "COMPLETE" : "PARTIAL",
            orderedRuns: [...existing.orderedRuns, ...runs],
          }
        : { edges, completeness: legComplete ? "COMPLETE" : "PARTIAL", orderedRuns: runs },
    );
  }
  return result;
}
