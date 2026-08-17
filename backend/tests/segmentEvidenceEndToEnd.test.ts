import { describe, expect, it } from "vitest";
import { resolveDeviationRelevanceAsync, resolveJourneyDisruptionsAsync, type SegmentEvidenceContext } from "../src/domain/disruptionRelevance.js";
import { buildActualLegEdgesByLine } from "../src/domain/requestedCorridor.js";
import { createLineTopologyDirectory, type GtfsFeedFetchResult, type GtfsStopIdResolution, type GtfsStopIdResolver } from "../src/services/lineTopologyDirectory.js";
import type { StopAreaNameIndex } from "../src/services/stopPointDirectory.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import type { ResolvedLegScope, ScopeSet } from "../src/domain/journeyDisruptionScope.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";
import type { Site } from "../src/models/site.js";
import type { JourneyDisruptionContext, JourneyDisruptionContextLeg } from "../src/models/journeyDisruptionContext.js";
import { JOURNEY_DISRUPTION_CONTEXT_VERSION } from "../src/models/journeyDisruptionContext.js";
import type { PatternPointGid, StopPointResolution } from "../src/services/stopPointDirectory.js";

// Real live StopArea ids (verified during this feature's own investigation phase).
const AKALLA = 3271,
  HUSBY = 3261,
  KISTA = 3251,
  RADHUSET = 3061,
  TCENTRALEN = 1051,
  KUNGSTRADGARDEN = 3031;

const GTFS_ID: Record<number, string> = {
  [AKALLA]: "g-akalla",
  [HUSBY]: "g-husby",
  [KISTA]: "g-kista",
  [RADHUSET]: "g-radhuset",
  [TCENTRALEN]: "g-tcentralen",
  [KUNGSTRADGARDEN]: "g-kungstradgarden",
};
const NAME_BY_STOP_AREA: Record<number, string> = {
  [AKALLA]: "akalla",
  [HUSBY]: "husby",
  [KISTA]: "kista",
  [RADHUSET]: "rådhuset",
  [TCENTRALEN]: "t-centralen",
  [KUNGSTRADGARDEN]: "kungsträdgården",
};

const LINE_11_ROUTES = "route_id,route_short_name,route_type\nR11,11,401\n"; // 401 = Metro Service, Trafiklab's real extended route_type for SL metro
const LINE_11_TRIPS = "route_id,trip_id\nR11,t-full\n";
const FULL_LINE = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN, KUNGSTRADGARDEN];
const LINE_11_STOP_TIMES =
  "trip_id,stop_id,stop_sequence\n" + FULL_LINE.map((stopAreaId, i) => `t-full,${GTFS_ID[stopAreaId]},${i + 1}`).join("\n") + "\n";

function feedSource(): GtfsFeedFetchResult {
  return { status: "OK", files: { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES }, validators: {} };
}

function stopIdResolver(): GtfsStopIdResolver {
  const table: Record<string, number> = Object.fromEntries(Object.entries(GTFS_ID).map(([stopAreaId, gtfsId]) => [gtfsId, Number(stopAreaId)]));
  return {
    async resolveMany(gtfsStopIds) {
      const result = new Map<string, GtfsStopIdResolution>();
      for (const id of gtfsStopIds) {
        const stopAreaId = table[id];
        result.set(id, stopAreaId != null ? { status: "RESOLVED", stopAreaId } : { status: "UNRESOLVED" });
      }
      return result;
    },
  };
}

function nameIndex(): StopAreaNameIndex {
  const table: Record<string, number[]> = {};
  for (const [stopAreaId, name] of Object.entries(NAME_BY_STOP_AREA)) table[name] = [Number(stopAreaId)];
  return { async findStopAreaIdsByName(name) { return table[name] ?? []; } };
}

function freshTopologyDirectory() {
  return createLineTopologyDirectory(
    { async fetchFeedFiles() { return feedSource(); } },
    stopIdResolver(),
    nameIndex(),
    new InMemoryCache(),
    new InMemoryLock(),
    new InFlightDeduper(),
  );
}

// The real live Blue-line closure disruption (deviation_case_id 11592474, observed 2026-08-16) --
// line-scoped only, no scope.stop_areas/scope.stop_points at all (see disruptionRelevance.test.ts's
// own AKALLA_NO_SERVICE fixture for the identical real shape).
const BLUE_LINE_CLOSURE: RawDeviation = {
  version: 1,
  created: "2026-06-20T00:00:00+02:00",
  modified: null,
  deviation_case_id: 11592474,
  publish: { from: null, upto: null },
  priority: { importance_level: 2, influence_level: 3, urgency_level: 1 },
  message_variants: [
    {
      header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
      details: "Tågtrafiken på Blå linjen är helt inställd mellan T-Centralen och Kungsträdgården på grund av arbeten.",
      language: "sv",
    },
  ],
  scope: {
    stop_areas: undefined,
    stop_points: undefined,
    lines: [
      { id: 10, transport_authority: 1, designation: "10", transport_mode: "METRO", name: null },
      { id: 11, transport_authority: 1, designation: "11", transport_mode: "METRO", name: null },
    ],
  },
};

function scopeSetFromOrderedStops(stopAreaIds: number[]): ScopeSet {
  return { stopAreaIds: new Set(stopAreaIds), stopPointIds: new Set(), completeness: "COMPLETE" };
}

function legScopeFor(orderedStopAreaIds: number[]): ResolvedLegScope {
  const scope = scopeSetFromOrderedStops(orderedStopAreaIds);
  return { transportMode: "METRO", lineDesignation: "11", accessPoints: scope, travelledPath: scope };
}

function contextLeg(orderedStopAreaIds: number[]): JourneyDisruptionContextLeg {
  return {
    transportMode: "METRO",
    lineDesignation: "11",
    boardingPatternPointGid: `pp-${orderedStopAreaIds[0]}`,
    alightingPatternPointGid: `pp-${orderedStopAreaIds[orderedStopAreaIds.length - 1]}`,
    stopPatternPointGids: orderedStopAreaIds.map((id) => `pp-${id}`),
    stopSequenceComplete: true,
  };
}

function resolutionsFor(orderedStopAreaIds: number[]): Map<PatternPointGid, StopPointResolution> {
  const map = new Map<PatternPointGid, StopPointResolution>();
  for (const stopAreaId of orderedStopAreaIds) {
    map.set(`pp-${stopAreaId}`, { status: "RESOLVED", patternPointGid: `pp-${stopAreaId}`, stopPointId: stopAreaId * 10, stopAreaId, stopAreaType: "METROSTN" });
  }
  return map;
}

function site(siteId: number, stopAreaIds: number[]): Site {
  return { siteId, name: `Site ${siteId}`, note: null, lat: null, lon: null, stopAreaIds };
}

const AKALLA_SITE = site(9300, [AKALLA]);
const KUNGSTRADGARDEN_SITE = site(9340, [KUNGSTRADGARDEN]);

describe("segment-parsing relevance enhancement: the real Blue-line acceptance scenario, end to end", () => {
  it("Case 1: T-Centralen -> Akalla is UNRELATED -- shares the boundary node but crosses no affected edge", async () => {
    // Actual PRIMARY alone can no longer prove UNRELATED (production-readiness review: a
    // "complete" actual path only describes PRIMARY's own CURRENT route, which may already be the
    // result of a reroute around the very disruption being evaluated -- see Case 4/4b below). The
    // negative proof here instead comes from the REQUESTED corridor: it independently resolves to
    // the exact same T-Centralen<->Akalla path PRIMARY actually used (the ordinary, non-rerouted
    // case), trusted because that run's own boundary (T-Centralen) is exactly one endpoint of the
    // affected segment.
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: { originSite: site(9310, [TCENTRALEN]), destinationSite: AKALLA_SITE },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toBeNull();
  });

  it("Case 2: Akalla -> T-Centralen is UNRELATED -- finishes at the boundary, never traverses the affected edge", async () => {
    // Same corrected reasoning as Case 1, reversed -- direction-independence of the boundary-
    // alignment trust check.
    const actualStops = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Akalla", journeyEnd: "T-Centralen", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: { originSite: AKALLA_SITE, destinationSite: site(9310, [TCENTRALEN]) },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toBeNull();
  });

  it("Case 3: T-Centralen -> Kungsträdgården is CONFIRMED -- the direct affected segment itself", async () => {
    const actualStops = [TCENTRALEN, KUNGSTRADGARDEN];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Kungsträdgården", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("Case 4: Akalla -> Kungsträdgården is CONFIRMED even when PRIMARY was rerouted off line 11 before ever reaching the closure -- the requested-corridor proof", async () => {
    // Actual PRIMARY: line 11 only as far as T-Centralen (a genuine prefix of the requested
    // corridor), then presumably transfers away -- this leg alone never touches the closed edge.
    const actualStopsOnLine11 = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN];
    const legScopes = [legScopeFor(actualStopsOnLine11)];
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Akalla",
      journeyEnd: "Kungsträdgården",
      legs: [contextLeg(actualStopsOnLine11)],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStopsOnLine11)),
      requestedEndpoints: { originSite: AKALLA_SITE, destinationSite: KUNGSTRADGARDEN_SITE },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("Case 4b (essential, corrected): the SAME rerouted journey WITHOUT requested-corridor evidence resolves LINE_RELEVANT, never a false UNRELATED", async () => {
    // Corrected per the production-readiness review: actual PRIMARY edges may ONLY EVER be
    // positive (confirming) evidence, permanently, regardless of completeness -- a "complete"
    // actual path only describes PRIMARY's own CURRENT route, which Journey Planner may already
    // have rerouted around the very disruption being evaluated. Here PRIMARY's own real line-11
    // travel (Akalla -> T-Centralen only, then presumably transferring away) is fully resolved and
    // complete, yet never touches the closed edge -- but that is exactly what a reroute around the
    // closure would ALSO look like, so it must NOT be read as proof the closure is irrelevant.
    // Without an independently-reconstructed, trusted requested corridor to fall back on, the
    // honest answer is "unproven either way" -- LINE_RELEVANT, not UNRELATED. Case 4 above shows
    // the SAME actual evidence reaching CONFIRMED once requested-corridor evidence IS available;
    // this test isolates what happens without it. Do not "simplify" this back to UNRELATED.
    const actualStopsOnLine11 = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN];
    const legScopes = [legScopeFor(actualStopsOnLine11)];
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Akalla",
      journeyEnd: "Kungsträdgården",
      legs: [contextLeg(actualStopsOnLine11)],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStopsOnLine11)),
      // requestedEndpoints deliberately omitted.
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("Case 5: Kungsträdgården -> Akalla is CONFIRMED -- mellan A och B is direction-independent", async () => {
    const actualStops = [KUNGSTRADGARDEN, TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Kungsträdgården", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("without a SegmentEvidenceContext at all, every case stays at the pre-existing LINE_RELEVANT result (backward compatible)", async () => {
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("a stop-scoped version of the SAME deviation ignores the text parser entirely -- structured evidence already exists, never overridden", async () => {
    // The one gate item 17 of this feature's own spec requires: this enhancement must only ever
    // run for a deviation with scope.lines but NO scope.stop_areas/scope.stop_points at all.
    const stopScoped: RawDeviation = { ...BLUE_LINE_CLOSURE, scope: { ...BLUE_LINE_CLOSURE.scope, stop_areas: [{ id: KUNGSTRADGARDEN, name: "Kungsträdgården", type: null }] } };
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA]; // never reaches Kungsträdgården
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    // With COMPLETE structured scope and no intersection, the EXISTING (pre-this-feature) rule
    // already produces a genuine disproof (null) -- exactly the same result the segment parser
    // would also reach here, but for the RIGHT reason: this proves the parser path was never
    // consulted at all (it would need "mellan"-scoped topology resolution to even run), by
    // checking the outcome matches the pre-existing structured-only rule exactly.
    const result = await resolveDeviationRelevanceAsync(stopScoped, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toBeNull();
  });

  it("an ambiguous station endpoint (two same-named StopAreas on this line) stays LINE_RELEVANT, never a false UNRELATED", async () => {
    const ambiguousNameIndex: StopAreaNameIndex = {
      async findStopAreaIdsByName(name) {
        if (name === "kungsträdgården") return [KUNGSTRADGARDEN, HUSBY]; // pretend Husby shares the exact same name
        return name === "t-centralen" ? [TCENTRALEN] : name === "akalla" ? [AKALLA] : [];
      },
    };
    const ambiguousDirectory = createLineTopologyDirectory(
      { async fetchFeedFiles() { return feedSource(); } },
      stopIdResolver(),
      ambiguousNameIndex,
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: ambiguousDirectory,
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("GTFS Regional unavailable for this exact live message still resolves LINE_RELEVANT, never a crash or a guess", async () => {
    const unavailableDirectory = createLineTopologyDirectory(
      { async fetchFeedFiles() { throw new Error("TRAFIKLAB_API_KEY not configured"); } },
      stopIdResolver(),
      nameIndex(),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: unavailableDirectory,
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("resolveJourneyDisruptionsAsync processing several line-relevant deviations in one call still fetches the GTFS feed only once", async () => {
    let fetchCount = 0;
    const directory = createLineTopologyDirectory(
      { async fetchFeedFiles() { fetchCount++; return feedSource(); } },
      stopIdResolver(),
      nameIndex(),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );
    const actualStops = [TCENTRALEN, KUNGSTRADGARDEN];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Kungsträdgården", legs: [contextLeg(actualStops)] };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: directory,
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };
    const secondDeviation: RawDeviation = { ...BLUE_LINE_CLOSURE, deviation_case_id: 99999999, message_variants: [{ ...BLUE_LINE_CLOSURE.message_variants[0]!, header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården (2)" }] };

    await resolveJourneyDisruptionsAsync([], [BLUE_LINE_CLOSURE, secondDeviation], legScopes, null, segmentContext);
    expect(fetchCount).toBe(1);
  });
});

describe("the segment-parsing enhancement must never upgrade an ACCESS_POINTS-policy effect via pass-through overlap (bug repro + fix)", () => {
  // Akalla -> Kungsträdgården on the real Blue-line topology already built above: Husby and Kista
  // are ordinary pass-through stops on this journey, never boarded/alighted at. This enhancement is
  // fundamentally a TRAVELLED_PATH mechanism (it proves "the vehicle passed through the affected
  // edge") -- an ACCESSIBILITY_ISSUE/STATION_ACCESS/STOP_CHANGE affecting "mellan Husby och Kista"
  // is ACCESS_POINTS-policy (see journeyDisruptionScope.ts's own scopePolicyForEffect) and must NOT
  // be confirmed merely because the passenger's vehicle happens to travel through that segment.
  const actualStops = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN, KUNGSTRADGARDEN];
  const legScopes = [legScopeFor(actualStops)];

  function husbyKistaSegmentContext(): SegmentEvidenceContext {
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Akalla",
      journeyEnd: "Kungsträdgården",
      legs: [contextLeg(actualStops)],
    };
    return { topologyDirectory: freshTopologyDirectory(), actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)) };
  }

  function husbyKistaDeviation(caseId: number, header: string): RawDeviation {
    return { ...BLUE_LINE_CLOSURE, deviation_case_id: caseId, message_variants: [{ header, details: "", language: "sv" }] };
  }

  it.each(["ACCESSIBILITY_ISSUE", "STATION_ACCESS", "STOP_CHANGE"] as const)(
    "%s at pass-through segment Husby<->Kista is NOT confirmed by the segment resolver -- stays LINE_RELEVANT",
    async (effect) => {
      const deviation = husbyKistaDeviation(40000001, "Hiss ur funktion mellan Husby och Kista");
      const result = await resolveDeviationRelevanceAsync(deviation, effect, legScopes, null, husbyKistaSegmentContext());
      expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
    },
  );

  it("the complementary case: DELAYS (TRAVELLED_PATH) on the SAME Husby<->Kista segment IS confirmed -- proves the gate doesn't break legitimate cases", async () => {
    const deviation = husbyKistaDeviation(40000002, "Förseningar mellan Husby och Kista");
    const result = await resolveDeviationRelevanceAsync(deviation, "DELAYS", legScopes, null, husbyKistaSegmentContext());
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("an accessibility issue at the actual boarding StopArea still confirms via the pre-existing ACCESS_POINTS stop-scope logic, not the segment resolver", async () => {
    const stopScoped: RawDeviation = { ...BLUE_LINE_CLOSURE, deviation_case_id: 40000003, scope: { ...BLUE_LINE_CLOSURE.scope, stop_areas: [{ id: AKALLA, name: "Akalla", type: null }] } };
    const result = await resolveDeviationRelevanceAsync(stopScoped, "ACCESSIBILITY_ISSUE", legScopes, null, husbyKistaSegmentContext());
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });
});

describe("the ACCESS_POINTS gate holds for the real Mariatorget scenario too, proven via the segment-parsing path directly (item 7)", () => {
  // The SAME real stop ids and line disruptionRelevance.test.ts's own Mariatorget regression uses
  // (Slussen -> Mälarhöjden on Metro 13, with Mariatorget and Zinkensdamm as intermediate,
  // pass-through stops) -- built here as its own small GTFS topology fixture so the identical
  // principle is proven via the ASYNC segment-parsing path too, not merely the pre-existing
  // structured stop-scope path that file already covers end to end from real SL wording.
  const SLUSSEN = 1011,
    MARIATORGET = 1210,
    ZINKENSDAMM = 1220,
    MALARHOJDEN = 1360;
  const GREEN_GTFS_ID: Record<number, string> = {
    [SLUSSEN]: "g-slussen",
    [MARIATORGET]: "g-mariatorget",
    [ZINKENSDAMM]: "g-zinkensdamm",
    [MALARHOJDEN]: "g-malarhojden",
  };
  const GREEN_NAME: Record<number, string> = { [SLUSSEN]: "slussen", [MARIATORGET]: "mariatorget", [ZINKENSDAMM]: "zinkensdamm", [MALARHOJDEN]: "mälarhöjden" };
  const GREEN_LINE = [SLUSSEN, MARIATORGET, ZINKENSDAMM, MALARHOJDEN];

  function greenTopologyDirectory() {
    const routesCsv = "route_id,route_short_name,route_type\nR13,13,401\n";
    const tripsCsv = "route_id,trip_id\nR13,t-full\n";
    const stopTimesCsv = "trip_id,stop_id,stop_sequence\n" + GREEN_LINE.map((id, i) => `t-full,${GREEN_GTFS_ID[id]},${i + 1}`).join("\n") + "\n";
    const stopIdTable: Record<string, number> = Object.fromEntries(Object.entries(GREEN_GTFS_ID).map(([stopAreaId, gtfsId]) => [gtfsId, Number(stopAreaId)]));
    const nameTable: Record<string, number[]> = {};
    for (const [stopAreaId, name] of Object.entries(GREEN_NAME)) nameTable[name] = [Number(stopAreaId)];
    return createLineTopologyDirectory(
      {
        async fetchFeedFiles() {
          return { status: "OK", files: { routesCsv, tripsCsv, stopTimesCsv }, validators: {} };
        },
      },
      {
        async resolveMany(gtfsStopIds) {
          const result = new Map<string, GtfsStopIdResolution>();
          for (const id of gtfsStopIds) {
            const stopAreaId = stopIdTable[id];
            result.set(id, stopAreaId != null ? { status: "RESOLVED", stopAreaId } : { status: "UNRESOLVED" });
          }
          return result;
        },
      },
      {
        async findStopAreaIdsByName(name) {
          return nameTable[name] ?? [];
        },
      },
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );
  }

  function greenLegScope(): ResolvedLegScope {
    const scope = scopeSetFromOrderedStops(GREEN_LINE);
    return { transportMode: "METRO", lineDesignation: "13", accessPoints: scope, travelledPath: scope };
  }

  function greenSegmentContext(): SegmentEvidenceContext {
    const leg: JourneyDisruptionContextLeg = {
      transportMode: "METRO",
      lineDesignation: "13",
      boardingPatternPointGid: `pp-${SLUSSEN}`,
      alightingPatternPointGid: `pp-${MALARHOJDEN}`,
      stopPatternPointGids: GREEN_LINE.map((id) => `pp-${id}`),
      stopSequenceComplete: true,
    };
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Slussen", journeyEnd: "Mälarhöjden", legs: [leg] };
    return { topologyDirectory: greenTopologyDirectory(), actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(GREEN_LINE)) };
  }

  function greenDeviation(caseId: number, header: string): RawDeviation {
    return {
      ...BLUE_LINE_CLOSURE,
      deviation_case_id: caseId,
      message_variants: [{ header, details: "", language: "sv" }],
      scope: { ...BLUE_LINE_CLOSURE.scope, lines: [{ id: 13, transport_authority: 1, designation: "13", transport_mode: "METRO", name: null }] },
    };
  }

  it("an ACCESSIBILITY_ISSUE at pass-through segment Mariatorget<->Zinkensdamm is NOT confirmed by the segment resolver -- stays LINE_RELEVANT", async () => {
    const deviation = greenDeviation(50000001, "Hiss ur funktion mellan Mariatorget och Zinkensdamm");
    const result = await resolveDeviationRelevanceAsync(deviation, "ACCESSIBILITY_ISSUE", [greenLegScope()], null, greenSegmentContext());
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["13"] });
  });

  it("the complementary case: DELAYS on the SAME Mariatorget<->Zinkensdamm segment IS confirmed", async () => {
    const deviation = greenDeviation(50000002, "Förseningar mellan Mariatorget och Zinkensdamm");
    const result = await resolveDeviationRelevanceAsync(deviation, "DELAYS", [greenLegScope()], null, greenSegmentContext());
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["13"] });
  });
});

describe("bug repro (item E): same designation, different transport mode must never be conflated in segment evidence", () => {
  it("a METRO 13 deviation only ever queries topology for METRO 13, never for BUS 13 sharing the same designation", async () => {
    // legScopes deliberately lists BUS 13 FIRST -- if the async segment layer reconstructs its
    // matched line from `matchedLineDesignations` (a bare designation string, mode already lost)
    // via a designation-only `legScopes.find(...)`, it would find BUS 13 (the first array match)
    // even though the deviation's own scope.lines only actually matched METRO 13.
    const legScopes: ResolvedLegScope[] = [
      { transportMode: "BUS", lineDesignation: "13", accessPoints: emptyScope(), travelledPath: emptyScope() },
      { transportMode: "METRO", lineDesignation: "13", accessPoints: emptyScope(), travelledPath: emptyScope() },
    ];
    const deviation: RawDeviation = {
      ...BLUE_LINE_CLOSURE,
      deviation_case_id: 30000001,
      scope: {
        ...BLUE_LINE_CLOSURE.scope,
        lines: [{ id: 13, transport_authority: 1, designation: "13", transport_mode: "METRO", name: null }],
      },
    };

    const queriedPairs: Array<{ transportMode: string; lineDesignation: string }> = [];
    const spyTopologyDirectory: SegmentEvidenceContext["topologyDirectory"] = {
      async resolveSegment(transportMode, lineDesignation) {
        queriedPairs.push({ transportMode, lineDesignation });
        return { status: "UNRESOLVED" };
      },
      async resolveEndpointsCorridor(transportMode, lineDesignation) {
        queriedPairs.push({ transportMode, lineDesignation });
        return { status: "UNRESOLVED" };
      },
    };
    const segmentContext: SegmentEvidenceContext = { topologyDirectory: spyTopologyDirectory, actualLegEdgesByLine: new Map() };

    await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);

    expect(queriedPairs.some((p) => p.transportMode === "BUS" && p.lineDesignation === "13")).toBe(false);
    expect(queriedPairs.every((p) => p.transportMode === "METRO" && p.lineDesignation === "13")).toBe(true);
    expect(queriedPairs.length).toBeGreaterThan(0);
  });

  it("(symmetric case) a BUS 13 deviation only ever queries topology for BUS 13, never for METRO 13 sharing the same designation", async () => {
    // legScopes deliberately lists METRO 13 FIRST this time -- proving the fix isn't merely
    // "coincidentally correct because of array order" in one direction only.
    const legScopes: ResolvedLegScope[] = [
      { transportMode: "METRO", lineDesignation: "13", accessPoints: emptyScope(), travelledPath: emptyScope() },
      { transportMode: "BUS", lineDesignation: "13", accessPoints: emptyScope(), travelledPath: emptyScope() },
    ];
    const deviation: RawDeviation = {
      ...BLUE_LINE_CLOSURE,
      deviation_case_id: 30000002,
      scope: {
        ...BLUE_LINE_CLOSURE.scope,
        lines: [{ id: 13, transport_authority: 1, designation: "13", transport_mode: "BUS", name: null }],
      },
    };

    const queriedPairs: Array<{ transportMode: string; lineDesignation: string }> = [];
    const spyTopologyDirectory: SegmentEvidenceContext["topologyDirectory"] = {
      async resolveSegment(transportMode, lineDesignation) {
        queriedPairs.push({ transportMode, lineDesignation });
        return { status: "UNRESOLVED" };
      },
      async resolveEndpointsCorridor(transportMode, lineDesignation) {
        queriedPairs.push({ transportMode, lineDesignation });
        return { status: "UNRESOLVED" };
      },
    };
    const segmentContext: SegmentEvidenceContext = { topologyDirectory: spyTopologyDirectory, actualLegEdgesByLine: new Map() };

    await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);

    expect(queriedPairs.some((p) => p.transportMode === "METRO" && p.lineDesignation === "13")).toBe(false);
    expect(queriedPairs.every((p) => p.transportMode === "BUS" && p.lineDesignation === "13")).toBe(true);
    expect(queriedPairs.length).toBeGreaterThan(0);
  });
});

function emptyScope(): ScopeSet {
  return { stopAreaIds: new Set(), stopPointIds: new Set(), completeness: "PARTIAL" };
}

describe("evaluateLineSegmentEvidence: UNRELATED is deliberately harder to prove than CONFIRMED", () => {
  // An earlier version of this logic only collected candidates that resolved cleanly and silently
  // dropped AMBIGUOUS/UNRESOLVED ones -- so "one candidate resolves and doesn't overlap" was
  // enough to reach UNRELATED even when ANOTHER candidate in the same message could not be
  // resolved at all (and might have been the real, relevant segment). These tests pin the
  // corrected candidate-completeness tracking directly against the real Blue-line topology fixture
  // this file already builds above.
  function multiSegmentDeviation(caseId: number, header: string, details: string): RawDeviation {
    return { ...BLUE_LINE_CLOSURE, deviation_case_id: caseId, message_variants: [{ header, details, language: "sv" }] };
  }

  const JOURNEY_TCENTRALEN_KUNGSTRADGARDEN = [TCENTRALEN, KUNGSTRADGARDEN];

  function segmentContextFor(actualStops: number[], journeyStart: string, journeyEnd: string): SegmentEvidenceContext {
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart, journeyEnd, legs: [contextLeg(actualStops)] };
    return { topologyDirectory: freshTopologyDirectory(), actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)) };
  }

  it("(a) two segments resolved from the SAME field, journey overlaps only the second -- CONFIRMED", async () => {
    const deviation = multiSegmentDeviation(
      20000001,
      "Inställd trafik mellan Akalla och Husby. Dessutom inställd trafik mellan T-Centralen och Kungsträdgården.",
      "Se sl.se för mer information.",
    );
    const legScopes = [legScopeFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN)];
    const segmentContext = segmentContextFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN, "T-Centralen", "Kungsträdgården");

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("(b) one resolved-non-overlapping segment plus one unresolved segment -- LINE_RELEVANT, explicitly NOT UNRELATED", async () => {
    const deviation = multiSegmentDeviation(
      20000002,
      "Inställd trafik mellan Akalla och Husby. Dessutom inställd trafik mellan Odenplan och S:t Eriksplan.",
      "Se sl.se för mer information.",
    );
    const legScopes = [legScopeFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN)];
    const segmentContext = segmentContextFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN, "T-Centralen", "Kungsträdgården");

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    // Akalla<->Husby resolves cleanly and does not overlap T-Centralen<->Kungsträdgården -- but
    // Odenplan<->S:t Eriksplan cannot be resolved on this line's own topology/name index at all
    // (unknown names). An unresolved candidate is not proof of absence: the result must stay
    // LINE_RELEVANT, never the false UNRELATED the earlier, buggy version of this logic would
    // have produced by silently dropping the unresolved candidate.
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("(c) one resolved-overlapping segment plus one unresolved segment -- CONFIRMED, positive evidence is sufficient on its own", async () => {
    const deviation = multiSegmentDeviation(
      20000003,
      "Inställd trafik mellan T-Centralen och Kungsträdgården. Dessutom inställd trafik mellan Odenplan och S:t Eriksplan.",
      "Se sl.se för mer information.",
    );
    const legScopes = [legScopeFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN)];
    const segmentContext = segmentContextFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN, "T-Centralen", "Kungsträdgården");

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    // The genuine overlap on the first segment is sufficient on its own -- the second, unresolved
    // candidate cannot withdraw it.
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("(d, corrected) all segments resolved, none overlap actual PRIMARY, but no requested corridor exists to prove UNRELATED -- LINE_RELEVANT", async () => {
    // Both segments (Akalla<->Husby, Husby<->Kista) are confidently and unambiguously resolved,
    // and neither overlaps actual PRIMARY's own T-Centralen<->Kungsträdgården journey -- but per
    // the corrected evidence hierarchy, actual PRIMARY edges alone (however cleanly resolved) can
    // never prove UNRELATED. Both affected segments also sit nowhere near this journey's own
    // corridor at all (not even a shared boundary node), so no requested corridor could ever be
    // trusted with respect to either one -- the honest result is the conservative LINE_RELEVANT,
    // not a false disproof.
    const deviation = multiSegmentDeviation(
      20000004,
      "Inställd trafik mellan Akalla och Husby. Dessutom inställd trafik mellan Husby och Kista.",
      "Se sl.se för mer information.",
    );
    const legScopes = [legScopeFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN)];
    const segmentContext = segmentContextFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN, "T-Centralen", "Kungsträdgården");

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("(e) a segment named only in details survives and confirms, even though the header's own segment does not overlap", async () => {
    // Proves the parser fix (header+details unioned, never short-circuited) and the
    // evidence-completeness fix work together: the OLD header-priority parser would have surfaced
    // ONLY "Akalla<->Husby" here (resolves, no overlap) and wrongly concluded UNRELATED, silently
    // hiding the real T-Centralen<->Kungsträdgården closure stated only in details.
    const deviation = multiSegmentDeviation(
      20000005,
      "Inställd trafik mellan Akalla och Husby.",
      "Dessutom inställd trafik mellan T-Centralen och Kungsträdgården.",
    );
    const legScopes = [legScopeFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN)];
    const segmentContext = segmentContextFor(JOURNEY_TCENTRALEN_KUNGSTRADGARDEN, "T-Centralen", "Kungsträdgården");

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });
});

describe("evaluateLineSegmentEvidence: actual-PRIMARY-edge completeness no longer affects negative proof at all (corrected; items 37-39/45)", () => {
  it("(item 38/45) resolved affected segment + PARTIAL actual corridor + no overlap -> LINE_RELEVANT, never UNRELATED", async () => {
    // Same structural shape as Case 1 (T-Centralen -> Akalla, genuinely does not cross the closed
    // edge) but the leg is marked stopSequenceComplete=false. Under the corrected evidence
    // hierarchy this distinction no longer matters at all: actual PRIMARY edges can NEVER prove
    // UNRELATED by themselves, complete or not (see requestedCorridor.ts's own
    // ActualLineEdgeEvidence doc) -- and no requested corridor is supplied here either.
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const incompleteLeg: JourneyDisruptionContextLeg = { ...contextLeg(actualStops), stopSequenceComplete: false };
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "T-Centralen",
      journeyEnd: "Akalla",
      legs: [incompleteLeg],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("(item 39) resolved affected segment + PARTIAL actual corridor + known overlap -> CONFIRMED (positive evidence from a partial path still counts)", async () => {
    const actualStops = [TCENTRALEN, KUNGSTRADGARDEN];
    const legScopes = [legScopeFor(actualStops)];
    const incompleteLeg: JourneyDisruptionContextLeg = { ...contextLeg(actualStops), stopSequenceComplete: false };
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "T-Centralen",
      journeyEnd: "Kungsträdgården",
      legs: [incompleteLeg],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });

  it("a PARTIAL actual leg does not prevent the requested-corridor trust check from still working (item 13 requirement 5: trust needs only ONE real edge, not completeness)", async () => {
    // The reroute scenario (Case 4), but the actual line-11 leg is deliberately marked
    // stopSequenceComplete=false -- isRequestedCorridorTrusted must still trust the requested
    // corridor from the raw actual edges alone, regardless of their own completeness.
    const actualStopsOnLine11 = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN];
    const legScopes = [legScopeFor(actualStopsOnLine11)];
    const incompleteLeg: JourneyDisruptionContextLeg = { ...contextLeg(actualStopsOnLine11), stopSequenceComplete: false };
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Akalla",
      journeyEnd: "Kungsträdgården",
      legs: [incompleteLeg],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStopsOnLine11)),
      requestedEndpoints: { originSite: AKALLA_SITE, destinationSite: KUNGSTRADGARDEN_SITE },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
  });
});

describe("SegmentEvidenceContext.requestedEndpoints: lazy provider support (item 22)", () => {
  it("a function provider is invoked and its result used exactly like an already-resolved value", async () => {
    const actualStopsOnLine11 = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN];
    const legScopes = [legScopeFor(actualStopsOnLine11)];
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Akalla",
      journeyEnd: "Kungsträdgården",
      legs: [contextLeg(actualStopsOnLine11)],
    };
    let callCount = 0;
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStopsOnLine11)),
      requestedEndpoints: async () => {
        callCount++;
        return { originSite: AKALLA_SITE, destinationSite: KUNGSTRADGARDEN_SITE };
      },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
    expect(callCount).toBeGreaterThan(0);
  });

  it("a function provider returning null behaves exactly like requestedEndpoints being absent", async () => {
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "T-Centralen",
      journeyEnd: "Akalla",
      legs: [contextLeg(actualStops)],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: async () => null,
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    // No requested corridor at all (the provider resolved to null, same as omitting it entirely)
    // and actual PRIMARY edges alone can never prove UNRELATED -- LINE_RELEVANT, matching Case 4b.
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });
});

describe("end to end: an internal actual fragment cannot leak false corridor trust through the real resolver (item 24)", () => {
  it("requested Akalla..Kungsträdgården (A-B-C-D-E-F), actual fragment Husby<->Kista only (an internal, mid-journey transfer), affected Kista<->Rådhuset -- LINE_RELEVANT, never a false CONFIRMED or UNRELATED", async () => {
    // Mirrors the abstract bug-repro shape exactly (requested A-B-C-D-E, actual B-C, affected
    // C-D) but through the REAL resolveDeviationRelevanceAsync pipeline end to end, proving the
    // internal-fragment false-trust bug cannot leak through real segment parsing + real topology
    // resolution + the real requested-corridor lookup -- not merely isRequestedCorridorTrusted in
    // isolation.
    const fragmentStops = [HUSBY, KISTA]; // neither the requested origin (Akalla) nor destination (Kungsträdgården)
    const legScopes = [legScopeFor(fragmentStops)];
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Husby",
      journeyEnd: "Kista",
      legs: [contextLeg(fragmentStops)],
    };
    const deviation: RawDeviation = {
      ...BLUE_LINE_CLOSURE,
      deviation_case_id: 60000001,
      message_variants: [{ header: "Inställd trafik mellan Kista och Rådhuset", details: "", language: "sv" }],
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(fragmentStops)),
      requestedEndpoints: { originSite: AKALLA_SITE, destinationSite: KUNGSTRADGARDEN_SITE },
    };

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });
});

describe("lazy requested-endpoint resolution: the provider must only ever be invoked when corridor evidence could actually change the outcome", () => {
  function countingRequestedEndpoints(callCount: { value: number }): () => Promise<{ originSite: Site; destinationSite: Site } | null> {
    return async () => {
      callCount.value++;
      return { originSite: AKALLA_SITE, destinationSite: KUNGSTRADGARDEN_SITE };
    };
  }

  it("BUG REPRO: actual PRIMARY already contains the affected edge directly -- CONFIRMED must not touch the requested-endpoints provider at all", async () => {
    // requested A -> D shape: actual Akalla-Husby-Kista-Rådhuset (edges A-B, B-C, C-D), affected
    // Husby<->Kista (edge B-C) -- direct overlap alone is already sufficient positive evidence
    // (production-readiness review, item 11), so resolving requested endpoints/corridor here is
    // pure wasted work.
    const actualStops = [AKALLA, HUSBY, KISTA, RADHUSET];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Akalla", journeyEnd: "Rådhuset", legs: [contextLeg(actualStops)] };
    const deviation: RawDeviation = { ...BLUE_LINE_CLOSURE, deviation_case_id: 70000001, message_variants: [{ header: "Inställd trafik mellan Husby och Kista", details: "", language: "sv" }] };
    const callCount = { value: 0 };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: countingRequestedEndpoints(callCount),
    };

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
    expect(callCount.value).toBe(0);
  });

  it("every parsed candidate is unresolved -- LINE_RELEVANT, zero requested-endpoint calls", async () => {
    const actualStops = [AKALLA, HUSBY, KISTA, RADHUSET];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Akalla", journeyEnd: "Rådhuset", legs: [contextLeg(actualStops)] };
    const deviation: RawDeviation = { ...BLUE_LINE_CLOSURE, deviation_case_id: 70000002, message_variants: [{ header: "Inställd trafik mellan Odenplan och S:t Eriksplan", details: "", language: "sv" }] };
    const callCount = { value: 0 };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: countingRequestedEndpoints(callCount),
    };

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
    expect(callCount.value).toBe(0);
  });

  it("a candidate resolves but PRIMARY never uses this line at all (no actual same-line run exists) -- requested-corridor trust could never succeed, so LINE_RELEVANT with zero requested-endpoint calls", async () => {
    // The deviation's own line (11) matches, but actualLegEdgesByLine has no entry at all for
    // METRO:11 -- isRequestedCorridorTrusted can never trust a corridor with zero actual runs to
    // check, regardless of what the corridor itself would turn out to be, so resolving requested
    // endpoints here would be pure wasted work.
    const legScopes = [legScopeFor([AKALLA, HUSBY, KISTA, RADHUSET])];
    const deviation: RawDeviation = { ...BLUE_LINE_CLOSURE, deviation_case_id: 70000003, message_variants: [{ header: "Inställd trafik mellan Husby och Kista", details: "", language: "sv" }] };
    const callCount = { value: 0 };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: new Map(), // PRIMARY never touches line 11 at all
      requestedEndpoints: countingRequestedEndpoints(callCount),
    };

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
    expect(callCount.value).toBe(0);
  });

  it("a resolved, non-overlapping candidate genuinely needs requested-corridor proof -- the provider is invoked exactly once", async () => {
    // Reuses Case 1's own real shape (T-Centralen -> Akalla, UNRELATED via the requested corridor)
    // but counts provider invocations directly.
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };
    const callCount = { value: 0 };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: async () => {
        callCount.value++;
        return { originSite: site(9310, [TCENTRALEN]), destinationSite: AKALLA_SITE };
      },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toBeNull(); // UNRELATED
    expect(callCount.value).toBe(1);
  });

  it("requested corridor confirms one resolved candidate while a SECOND candidate stays unresolved -- CONFIRMED, provider invoked exactly once (never the incorrect 'any unresolved candidate skips the corridor' optimization)", async () => {
    // Header names two segments: Husby<->Kista (resolves, no DIRECT actual overlap here -- actual
    // PRIMARY is only Akalla -> T-Centralen on this rerouted journey) and an unknown pair (stays
    // UNRESOLVED). The requested corridor (Akalla -> Kungsträdgården) trusts the actual prefix at
    // its own T-Centralen boundary and confirms the OTHER segment, T-Centralen<->Kungsträdgården,
    // via corridor overlap -- positive evidence must win even with a sibling candidate unresolved.
    const actualStopsOnLine11 = [AKALLA, HUSBY, KISTA, RADHUSET, TCENTRALEN];
    const legScopes = [legScopeFor(actualStopsOnLine11)];
    const context: JourneyDisruptionContext = {
      version: JOURNEY_DISRUPTION_CONTEXT_VERSION,
      journeyStart: "Akalla",
      journeyEnd: "Kungsträdgården",
      legs: [contextLeg(actualStopsOnLine11)],
    };
    const deviation: RawDeviation = {
      ...BLUE_LINE_CLOSURE,
      deviation_case_id: 70000004,
      message_variants: [
        {
          header: "Inställd trafik mellan Odenplan och S:t Eriksplan. Dessutom inställd trafik mellan T-Centralen och Kungsträdgården.",
          details: "",
          language: "sv",
        },
      ],
    };
    const callCount = { value: 0 };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStopsOnLine11)),
      requestedEndpoints: countingRequestedEndpoints(callCount),
    };

    const result = await resolveDeviationRelevanceAsync(deviation, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: ["11"] });
    expect(callCount.value).toBe(1);
  });

  it("multiple candidates within one deviation, and multiple deviations within one request, reuse a single memoized endpoint resolution", async () => {
    // Mirrors the existing 'GTFS feed fetched only once' test, but for requested-endpoint calls:
    // TWO deviations, each needing requested-corridor proof on the SAME line, must still only
    // trigger the underlying provider once -- exactly what routes/journeyDisruptions.ts's own
    // memoized getRequestedEndpoints closure guarantees in production; this proves the evaluation
    // order doesn't defeat that memoization by calling the provider from multiple, uncoordinated
    // spots.
    const actualStops = [TCENTRALEN, RADHUSET, KISTA, HUSBY, AKALLA];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "T-Centralen", journeyEnd: "Akalla", legs: [contextLeg(actualStops)] };

    let callCount = 0;
    let resolvedOnce: Promise<{ originSite: Site; destinationSite: Site } | null> | undefined;
    const memoizedProvider = (): Promise<{ originSite: Site; destinationSite: Site } | null> => {
      resolvedOnce ??= (async () => {
        callCount++;
        return { originSite: site(9310, [TCENTRALEN]), destinationSite: AKALLA_SITE };
      })();
      return resolvedOnce;
    };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: memoizedProvider,
    };
    const secondDeviation: RawDeviation = {
      ...BLUE_LINE_CLOSURE,
      deviation_case_id: 70000005,
      message_variants: [{ ...BLUE_LINE_CLOSURE.message_variants[0]!, header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården (2)" }],
    };

    const results = await resolveJourneyDisruptionsAsync([], [BLUE_LINE_CLOSURE, secondDeviation], legScopes, null, segmentContext);
    expect(results).toHaveLength(0); // both deviations are UNRELATED (T-Centralen -> Akalla never crosses the closure) -- filtered out, but the underlying endpoint provider must still be memoized
    expect(callCount).toBe(1);
  });

  it("complete negative proof (UNRELATED) requires the requested corridor -- provider invoked exactly once", async () => {
    // Actual PRIMARY only ever reaches as far as Rådhuset (never touches T-Centralen or
    // Kungsträdgården at all), so the affected T-Centralen<->Kungsträdgården segment (the real
    // BLUE_LINE_CLOSURE header) has no DIRECT actual overlap to short-circuit on -- the negative
    // proof can only come from the requested corridor (Akalla -> Rådhuset, which exactly matches
    // actual PRIMARY's own full run: Case A, an unconditionally trusted full match).
    const actualStops = [AKALLA, HUSBY, KISTA, RADHUSET];
    const legScopes = [legScopeFor(actualStops)];
    const context: JourneyDisruptionContext = { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Akalla", journeyEnd: "Rådhuset", legs: [contextLeg(actualStops)] };
    const callCount = { value: 0 };
    const segmentContext: SegmentEvidenceContext = {
      topologyDirectory: freshTopologyDirectory(),
      actualLegEdgesByLine: buildActualLegEdgesByLine(context, resolutionsFor(actualStops)),
      requestedEndpoints: async () => {
        callCount.value++;
        return { originSite: AKALLA_SITE, destinationSite: site(9311, [RADHUSET]) };
      },
    };

    const result = await resolveDeviationRelevanceAsync(BLUE_LINE_CLOSURE, "NO_SERVICE", legScopes, null, segmentContext);
    expect(result).toBeNull(); // UNRELATED
    expect(callCount.value).toBe(1);
  });
});
