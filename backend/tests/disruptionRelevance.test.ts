import { describe, expect, it } from "vitest";
import {
  deviationOverlapsJourneyWindow,
  resolveDeviationRelevance,
  resolveJourneyDisruptions,
  type JourneyPlannerNoticeInput,
  type JourneyTimeWindow,
} from "../src/domain/disruptionRelevance.js";
import type { ResolvedLegScope, ScopeSet } from "../src/domain/journeyDisruptionScope.js";
import type { DisruptionEffect } from "../src/models/disruption.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";

function deviation(overrides: {
  id?: number;
  lines?: Array<{ designation: string; transportMode: string | null }>;
  stopAreaIds?: number[];
  stopPointIds?: number[];
  header?: string;
  details?: string;
  publishFrom?: string | null;
  publishUpto?: string | null;
}): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: overrides.id ?? 1,
    publish: { from: overrides.publishFrom ?? null, upto: overrides.publishUpto ?? null },
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: overrides.header ?? "h", details: overrides.details ?? "d", language: "sv" }],
    scope: {
      stop_areas: overrides.stopAreaIds?.map((id) => ({ id, name: "Test", type: null })),
      stop_points: overrides.stopPointIds?.map((id) => ({ id, name: "Test" })),
      lines: overrides.lines?.map((l, i) => ({
        id: i + 1,
        designation: l.designation,
        transport_mode: l.transportMode,
        name: null,
      })),
    },
  };
}

/** Mirrors the OLD (pre-`disruptionContext`) undifferentiated `VerifiedJourneyStopScope` shape:
 * the SAME stopAreaIds/completeness applied to BOTH `accessPoints` and `travelledPath` — used to
 * reproduce every pre-existing regression scenario unchanged under the new per-scope-kind API,
 * since the effect/scope-kind distinction did not exist before this feature. */
function uniformLegScope(transportMode: string, lineDesignation: string, stopAreaIds: number[], completeness: "PARTIAL" | "COMPLETE"): ResolvedLegScope {
  const scope: ScopeSet = { stopAreaIds: new Set(stopAreaIds), stopPointIds: new Set(), completeness };
  return { transportMode, lineDesignation, accessPoints: scope, travelledPath: scope };
}

/** A leg with no verified stop evidence of any kind — the honest "no journeyStopScope supplied"
 * equivalent under the new API. */
function bareLeg(transportMode: string, lineDesignation: string): ResolvedLegScope {
  return uniformLegScope(transportMode, lineDesignation, [], "PARTIAL");
}

function accessTravelLegScope(
  transportMode: string,
  lineDesignation: string,
  accessPoints: { stopAreaIds?: number[]; stopPointIds?: number[]; completeness: "PARTIAL" | "COMPLETE" },
  travelledPath: { stopAreaIds?: number[]; stopPointIds?: number[]; completeness: "PARTIAL" | "COMPLETE" },
): ResolvedLegScope {
  return {
    transportMode,
    lineDesignation,
    accessPoints: { stopAreaIds: new Set(accessPoints.stopAreaIds ?? []), stopPointIds: new Set(accessPoints.stopPointIds ?? []), completeness: accessPoints.completeness },
    travelledPath: { stopAreaIds: new Set(travelledPath.stopAreaIds ?? []), stopPointIds: new Set(travelledPath.stopPointIds ?? []), completeness: travelledPath.completeness },
  };
}

const GENERIC_EFFECT: DisruptionEffect = "DISRUPTION"; // TRAVELLED_PATH policy, used where the ACCESS_POINTS/TRAVELLED_PATH distinction is irrelevant to the test

const AKALLA_NO_SERVICE = deviation({
  id: 9001,
  header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
  details: "På grund av ett tekniskt fel är trafiken på Blå linjen inställd mellan T-Centralen och Kungsträdgården.",
  lines: [
    { designation: "10", transportMode: "METRO" },
    { designation: "11", transportMode: "METRO" },
  ],
  stopAreaIds: [],
});

describe("resolveDeviationRelevance — the confirmed Akalla -> Kungsträdgården line-closure regression", () => {
  it("resolves LINE_RELEVANT (not CONFIRMED): line matches, but no stop-area evidence exists at all", () => {
    const result = resolveDeviationRelevance(AKALLA_NO_SERVICE, GENERIC_EFFECT, [bareLeg("METRO", "11")], null);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("still LINE_RELEVANT even when a verified origin scope IS supplied -- no stop scope on the deviation itself to check it against", () => {
    const result = resolveDeviationRelevance(AKALLA_NO_SERVICE, GENERIC_EFFECT, [uniformLegScope("METRO", "11", [9192], "PARTIAL")], null);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });
});

describe("resolveDeviationRelevance — Slussen -> Liljeholmen must stay disruption-free", () => {
  it("an unrelated bus 401 delay at Slussen does not match a Metro 13/14 journey", () => {
    const bus401 = deviation({ id: 2001, lines: [{ designation: "401", transportMode: "BUS" }], stopAreaIds: [9192] });
    const primaryLegs = [uniformLegScope("METRO", "13", [9192], "PARTIAL"), bareLeg("METRO", "14")];
    expect(resolveDeviationRelevance(bus401, GENERIC_EFFECT, primaryLegs, null)).toBeNull();
    expect(resolveDeviationRelevance(bus401, GENERIC_EFFECT, [bareLeg("METRO", "13"), bareLeg("METRO", "14")], null)).toBeNull();
  });

  it("shared station without line overlap cannot cause a disruption even with matching stop scope", () => {
    const bus401 = deviation({ id: 2002, lines: [{ designation: "401", transportMode: "BUS" }], stopAreaIds: [9192] });
    expect(resolveDeviationRelevance(bus401, GENERIC_EFFECT, [uniformLegScope("METRO", "13", [9192, 1011], "PARTIAL")], null)).toBeNull();
  });
});

describe("resolveDeviationRelevance — line/mode scope is required, affectedModes alone is never enough", () => {
  it("mode matching alone (different designation) does not match", () => {
    const d = deviation({ id: 3002, lines: [{ designation: "10", transportMode: "METRO" }] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [bareLeg("METRO", "11")], null)).toBeNull();
  });

  it("designation matching alone (different mode) does not match", () => {
    const d = deviation({ id: 3003, lines: [{ designation: "11", transportMode: "BUS" }] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [bareLeg("METRO", "11")], null)).toBeNull();
  });

  it("no string/fuzzy ID matching is involved -- a Journey-Planner-shaped id never coincidentally matches a stop-area id", () => {
    const stopScoped = deviation({ id: 3004, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [9192] });
    expect(resolveDeviationRelevance(stopScoped, GENERIC_EFFECT, [bareLeg("METRO", "11")], null)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });
});

describe("resolveDeviationRelevance — PARTIAL stop knowledge: non-intersection is NOT a disproof", () => {
  const sameLine = { designation: "11", transportMode: "METRO" } as const;

  it("PARTIAL scope + known stop intersects -> CONFIRMED", () => {
    const d = deviation({ id: 4001, lines: [sameLine], stopAreaIds: [9192, 44000] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [uniformLegScope("METRO", "11", [9192, 1011], "PARTIAL")], null)).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });

  it("PARTIAL scope + known stop does NOT intersect -> LINE_RELEVANT, not UNRELATED", () => {
    const d = deviation({ id: 4002, lines: [sameLine], stopAreaIds: [44000] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [uniformLegScope("METRO", "11", [9192, 1011], "PARTIAL")], null)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });

  it("fails closed to LINE_RELEVANT (never silently dropped) when no verified stop scope is available at all", () => {
    const d = deviation({ id: 4003, lines: [sameLine], stopAreaIds: [9192] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [bareLeg("METRO", "11")], null)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });
});

describe("resolveDeviationRelevance — COMPLETE stop knowledge: non-intersection IS a genuine disproof", () => {
  const sameLine = { designation: "11", transportMode: "METRO" } as const;

  it("COMPLETE scope + no intersection -> UNRELATED (null)", () => {
    const d = deviation({ id: 5001, lines: [sameLine], stopAreaIds: [44000, 55000] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [uniformLegScope("METRO", "11", [9192, 1011, 2000], "COMPLETE")], null)).toBeNull();
  });

  it("COMPLETE scope + intersection -> CONFIRMED", () => {
    const d = deviation({ id: 5002, lines: [sameLine], stopAreaIds: [2000] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, [uniformLegScope("METRO", "11", [9192, 1011, 2000], "COMPLETE")], null)).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });
});

describe("resolveDeviationRelevance — multiple matched legs", () => {
  it("collects every distinct matching line designation, deduplicated", () => {
    const d = deviation({
      id: 6001,
      lines: [
        { designation: "11", transportMode: "METRO" },
        { designation: "13", transportMode: "METRO" },
      ],
    });
    const result = resolveDeviationRelevance(d, GENERIC_EFFECT, [bareLeg("METRO", "11"), bareLeg("METRO", "13"), bareLeg("METRO", "11")], null);
    expect(result?.matchedLineDesignations).toEqual(["11", "13"]);
  });
});

describe("resolveDeviationRelevance — ACCESS_POINTS vs TRAVELLED_PATH: the real acceptance scenario", () => {
  // Akalla -> T-Centralen on Metro 11, matching the live Journey Planner <-> SL Transport
  // cross-reference: ACCESS_POINTS = {Akalla, T-Centralen}; TRAVELLED_PATH additionally includes
  // Kista (an intermediate stop the passenger stays onboard through).
  const AKALLA_TO_TCENTRALEN = accessTravelLegScope(
    "METRO",
    "11",
    { stopAreaIds: [3271, 1051], completeness: "COMPLETE" }, // Akalla, T-Centralen
    { stopAreaIds: [3271, 3261, 3251, 1051], completeness: "COMPLETE" }, // + Husby, Kista
  );

  it("an accessibility issue at Kungsträdgården (never on this journey's own route) is UNRELATED", () => {
    const kungstradgardenLift = deviation({ id: 12203432, header: "Avstängd hiss vid Kungsträdgården", lines: [{ designation: "10", transportMode: "METRO" }, { designation: "11", transportMode: "METRO" }], stopAreaIds: [3031] });
    expect(resolveDeviationRelevance(kungstradgardenLift, "ACCESSIBILITY_ISSUE", [AKALLA_TO_TCENTRALEN], null)).toBeNull();
  });

  it("an accessibility issue at T-Centralen (this journey's own destination) is CONFIRMED", () => {
    const tcentralenLift = deviation({ id: 12285237, header: "Avstängd hiss vid T-Centralen", lines: [{ designation: "10", transportMode: "METRO" }, { designation: "11", transportMode: "METRO" }], stopAreaIds: [1051] });
    expect(resolveDeviationRelevance(tcentralenLift, "ACCESSIBILITY_ISSUE", [AKALLA_TO_TCENTRALEN], null)).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });

  it("an accessibility issue at Akalla (this journey's own origin) is CONFIRMED", () => {
    const akallaLift = deviation({ id: 90001, header: "Avstängd hiss vid Akalla", lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [3271] });
    expect(resolveDeviationRelevance(akallaLift, "ACCESSIBILITY_ISSUE", [AKALLA_TO_TCENTRALEN], null)).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });

  it("an accessibility issue at Kista (an intermediate, stayed-onboard stop) is UNRELATED, even though Kista IS on the travelled path", () => {
    const kistaLift = deviation({ id: 90002, header: "Avstängd hiss vid Kista", lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [3251] });
    expect(resolveDeviationRelevance(kistaLift, "ACCESSIBILITY_ISSUE", [AKALLA_TO_TCENTRALEN], null)).toBeNull();
  });

  it("a delay at Kista on the SAME line IS CONFIRMED -- DELAYS uses TRAVELLED_PATH, not ACCESS_POINTS", () => {
    const kistaDelay = deviation({ id: 90003, header: "Metro 11 försenat vid Kista", lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [3251] });
    expect(resolveDeviationRelevance(kistaDelay, "DELAYS", [AKALLA_TO_TCENTRALEN], null)).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });

  it("the same Kista delay on an unrelated line/mode is UNRELATED", () => {
    const kistaDelayOtherLine = deviation({ id: 90004, lines: [{ designation: "1", transportMode: "BUS" }], stopAreaIds: [3251] });
    expect(resolveDeviationRelevance(kistaDelayOtherLine, "DELAYS", [AKALLA_TO_TCENTRALEN], null)).toBeNull();
  });
});

describe("resolveDeviationRelevance — stop-only deviations (no scope.lines at all)", () => {
  it("a stop-scoped accessibility deviation with no lines becomes CONFIRMED when it intersects a COMPLETE access-point scope", () => {
    const stopOnly = deviation({ id: 70001, header: "Avstängd hiss vid T-Centralen", lines: undefined, stopAreaIds: [1051] });
    const leg = accessTravelLegScope("METRO", "11", { stopAreaIds: [1051], completeness: "COMPLETE" }, { stopAreaIds: [1051], completeness: "COMPLETE" });
    expect(resolveDeviationRelevance(stopOnly, "ACCESSIBILITY_ISSUE", [leg], null)).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: [] });
  });

  it("a stop-only deviation outside a COMPLETE relevant scope is UNRELATED", () => {
    const stopOnly = deviation({ id: 70002, lines: undefined, stopAreaIds: [9999] });
    const leg = accessTravelLegScope("METRO", "11", { stopAreaIds: [1051], completeness: "COMPLETE" }, { stopAreaIds: [1051], completeness: "COMPLETE" });
    expect(resolveDeviationRelevance(stopOnly, "ACCESSIBILITY_ISSUE", [leg], null)).toBeNull();
  });

  it("a stop-only deviation is UNRELATED (never LINE_RELEVANT) when the relevant scope is only PARTIAL and does not intersect", () => {
    // The key asymmetry from the line-matched case: LINE_RELEVANT specifically means "a line
    // matched but the stop is uncertain" -- there is no line here at all, so this must fail
    // closed rather than manufacture a LINE_RELEVANT warning with no line evidence behind it.
    const stopOnly = deviation({ id: 70003, lines: undefined, stopAreaIds: [9999] });
    const leg = accessTravelLegScope("METRO", "11", { stopAreaIds: [1051], completeness: "PARTIAL" }, { stopAreaIds: [1051], completeness: "PARTIAL" });
    expect(resolveDeviationRelevance(stopOnly, "ACCESSIBILITY_ISSUE", [leg], null)).toBeNull();
  });

  it("fails closed (UNRELATED) when there is neither a line nor any stop scope at all", () => {
    const nothing = deviation({ id: 70004, lines: undefined, stopAreaIds: undefined });
    expect(resolveDeviationRelevance(nothing, "ACCESSIBILITY_ISSUE", [bareLeg("METRO", "11")], null)).toBeNull();
  });

  it("matches via scope.stop_points intersection, independent of scope.stop_areas", () => {
    const stopPointOnly = deviation({ id: 70005, lines: undefined, stopPointIds: [40171] });
    const leg = accessTravelLegScope("BUS", "471", { stopPointIds: [40171], completeness: "COMPLETE" }, { stopPointIds: [40171], completeness: "COMPLETE" });
    expect(resolveDeviationRelevance(stopPointOnly, "STOP_CHANGE", [leg], null)).toEqual({ relevance: "CONFIRMED", matchedLineDesignations: [] });
  });
});

describe("resolveDeviationRelevance — a StopPoint-ambiguous (StopArea-only) journey scope", () => {
  // Mirrors StopPointDirectory's own STOP_AREA_ONLY resolution (see stopPointDirectory.test.ts):
  // journeyDisruptionScope.ts never adds an ambiguous StopPoint id to a leg's own stopPointIds
  // set, so a leg's resolved scope here has stopAreaIds populated but stopPointIds empty.
  const stopAreaOnlyLeg = accessTravelLegScope(
    "METRO", "11",
    { stopAreaIds: [1011], stopPointIds: [], completeness: "COMPLETE" },
    { stopAreaIds: [1011], stopPointIds: [], completeness: "COMPLETE" },
  );

  it("a scope.stop_points-only deviation cannot be CONFIRMED from an ambiguous StopPoint identity", () => {
    // The deviation is scoped to StopPoint 1012 -- but this journey's own leg was never able to
    // prove any single StopPoint id (only its StopArea), so there is nothing to intersect against.
    const stopPointOnly = deviation({ id: 90101, lines: [{ designation: "11", transportMode: "METRO" }], stopPointIds: [1012] });
    expect(resolveDeviationRelevance(stopPointOnly, "ACCESSIBILITY_ISSUE", [stopAreaOnlyLeg], null)).toBeNull();
  });

  it("a scope.stop_areas-scoped deviation can still be CONFIRMED when StopArea identity remains uniquely resolved", () => {
    const stopAreaScoped = deviation({ id: 90102, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [1011] });
    expect(resolveDeviationRelevance(stopAreaScoped, "ACCESSIBILITY_ISSUE", [stopAreaOnlyLeg], null)).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });
});

describe("resolveDeviationRelevance — temporal relevance", () => {
  const sameLine = { designation: "11", transportMode: "METRO" } as const;
  const primaryLegs = [bareLeg("METRO", "11")];
  const window: JourneyTimeWindow = { departureTime: "2026-08-16T10:00:00Z", arrivalTime: "2026-08-16T10:20:00Z" };

  it("a deviation ending before the journey departs is UNRELATED", () => {
    const d = deviation({ id: 80001, lines: [sameLine], publishUpto: "2026-08-16T09:00:00Z" });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, primaryLegs, window)).toBeNull();
  });

  it("a deviation starting after the journey arrives is UNRELATED", () => {
    const d = deviation({ id: 80002, lines: [sameLine], publishFrom: "2026-08-16T11:00:00Z" });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, primaryLegs, window)).toBeNull();
  });

  it("a deviation whose validity overlaps the journey interval is considered", () => {
    const d = deviation({ id: 80003, lines: [sameLine], publishFrom: "2026-08-16T09:50:00Z", publishUpto: "2026-08-16T10:10:00Z" });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, primaryLegs, window)).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("open-ended validity (no publish.from/upto) is always considered", () => {
    const d = deviation({ id: 80004, lines: [sameLine] });
    expect(resolveDeviationRelevance(d, GENERIC_EFFECT, primaryLegs, window)).not.toBeNull();
  });

  it("a null journeyWindow (no departureTime/arrivalTime supplied) skips the temporal check entirely", () => {
    const expired = deviation({ id: 80005, lines: [sameLine], publishUpto: "2020-01-01T00:00:00Z" });
    expect(resolveDeviationRelevance(expired, GENERIC_EFFECT, primaryLegs, null)).not.toBeNull();
  });

  it("deviationOverlapsJourneyWindow directly: exact same rules", () => {
    expect(deviationOverlapsJourneyWindow(deviation({ publishUpto: "2026-08-16T09:59:59Z" }), window)).toBe(false);
    expect(deviationOverlapsJourneyWindow(deviation({ publishFrom: "2026-08-16T10:20:01Z" }), window)).toBe(false);
    expect(deviationOverlapsJourneyWindow(deviation({}), window)).toBe(true);
    expect(deviationOverlapsJourneyWindow(deviation({}), null)).toBe(true);
  });
});

describe("resolveJourneyDisruptions — deduplication and merging", () => {
  it("returns only the structurally relevant deviations, deduplicated by deviation_case_id", () => {
    const relevant = deviation({ id: 7001, lines: [{ designation: "11", transportMode: "METRO" }] });
    const duplicateOfRelevant = deviation({ id: 7001, lines: [{ designation: "11", transportMode: "METRO" }], header: "dup" });
    const unrelated = deviation({ id: 7002, lines: [{ designation: "401", transportMode: "BUS" }] });
    const result = resolveJourneyDisruptions([], [relevant, duplicateOfRelevant, unrelated], [bareLeg("METRO", "11")], null);
    expect(result).toHaveLength(1);
    expect(result[0]!.id).toBe("7001");
    expect(result[0]!.headline).toBe("h"); // first occurrence wins
  });

  it("existing Journey Planner notices still work unchanged when Deviations contains nothing", () => {
    const jp: JourneyPlannerNoticeInput = { text: "Rerouted via bus", effect: "ROUTE_CHANGE" };
    const result = resolveJourneyDisruptions([jp], [], [], null);
    expect(result).toEqual([
      { headline: "Rerouted via bus", effect: "ROUTE_CHANGE", relevance: "CONFIRMED", source: "JOURNEY_PLANNER", matchedLineDesignations: [] },
    ]);
  });

  it("a Journey Planner notice attached directly to PRIMARY is CONFIRMED even with no matching Deviation", () => {
    const jp: JourneyPlannerNoticeInput = { text: "Hissen är ur funktion.", effect: "ACCESSIBILITY_ISSUE" };
    const result = resolveJourneyDisruptions([jp], [], [bareLeg("METRO", "11")], null);
    expect(result[0]!.relevance).toBe("CONFIRMED");
    expect(result[0]!.source).toBe("JOURNEY_PLANNER");
  });

  it("same notice from Journey Planner + SL Deviations appears once, with the richer Deviations details surviving", () => {
    const jp: JourneyPlannerNoticeInput = {
      text: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
      effect: "NO_SERVICE",
    };
    const result = resolveJourneyDisruptions([jp], [AKALLA_NO_SERVICE], [bareLeg("METRO", "11")], null);
    expect(result).toHaveLength(1);
    const merged = result[0]!;
    expect(merged.source).toBe("SL_DEVIATIONS");
    expect(merged.id).toBe("9001");
    expect(merged.details).toBe(
      "På grund av ett tekniskt fel är trafiken på Blå linjen inställd mellan T-Centralen och Kungsträdgården.",
    );
    expect(merged.relevance).toBe("CONFIRMED");
  });

  it("a text-only Journey Planner copy must not replace a richer Deviations copy when both exist", () => {
    const jp: JourneyPlannerNoticeInput = { text: "h", effect: "DISRUPTION" };
    const richDeviation = deviation({ id: 8001, header: "h", details: "much richer real SL text", lines: [{ designation: "11", transportMode: "METRO" }] });
    const result = resolveJourneyDisruptions([jp], [richDeviation], [bareLeg("METRO", "11")], null);
    expect(result).toHaveLength(1);
    expect(result[0]!.details).toBe("much richer real SL text");
    expect(result[0]!.id).toBe("8001");
  });

  it("two genuinely different disruptions are both preserved", () => {
    const jp: JourneyPlannerNoticeInput = { text: "Rerouted via bus", effect: "ROUTE_CHANGE" };
    const unrelatedHeadlineDeviation = deviation({ id: 8002, header: "Different real SL text", lines: [{ designation: "11", transportMode: "METRO" }] });
    const result = resolveJourneyDisruptions([jp], [unrelatedHeadlineDeviation], [bareLeg("METRO", "11")], null);
    expect(result).toHaveLength(2);
    expect(result.map((r) => r.headline)).toEqual(["Rerouted via bus", "Different real SL text"]);
  });

  it("stable deviation ID is preserved on an unmerged SL_DEVIATIONS entry", () => {
    const d = deviation({ id: 9999, lines: [{ designation: "11", transportMode: "METRO" }] });
    const result = resolveJourneyDisruptions([], [d], [bareLeg("METRO", "11")], null);
    expect(result[0]!.id).toBe("9999");
  });

  it("an empty result from both sources combines to empty", () => {
    expect(resolveJourneyDisruptions([], [], [], null)).toEqual([]);
  });

  it("duplicate Journey Planner notices with identical text are deduplicated", () => {
    const a: JourneyPlannerNoticeInput = { text: "Delayed", effect: "DELAYS" };
    const b: JourneyPlannerNoticeInput = { text: "Delayed", effect: "DELAYS" };
    expect(resolveJourneyDisruptions([a, b], [], [], null)).toHaveLength(1);
  });

  it("legScopes are forwarded unchanged to resolveDeviationRelevance for every deviation", () => {
    const d = deviation({ id: 9998, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [2000] });
    const result = resolveJourneyDisruptions([], [d], [uniformLegScope("METRO", "11", [2000], "COMPLETE")], null);
    expect(result[0]!.relevance).toBe("CONFIRMED");
  });

  it("NEXT/ALTERNATIVE data never affects PRIMARY's own disruption scope -- only whatever legScopes the caller supplies is ever consulted", () => {
    // legScopes here represents PRIMARY alone; a deviation matching a completely different line
    // (as if it belonged to some other journey's own NEXT/ALTERNATIVE) must not match.
    const otherJourneyLine = deviation({ id: 9997, lines: [{ designation: "99", transportMode: "BUS" }] });
    const result = resolveJourneyDisruptions([], [otherJourneyLine], [bareLeg("METRO", "11")], null);
    expect(result).toEqual([]);
  });

  it("a journeyWindow filters out an expired deviation even when everything else about it matches", () => {
    const window: JourneyTimeWindow = { departureTime: "2026-08-16T10:00:00Z", arrivalTime: "2026-08-16T10:20:00Z" };
    const expired = deviation({ id: 9996, lines: [{ designation: "11", transportMode: "METRO" }], publishUpto: "2020-01-01T00:00:00Z" });
    const result = resolveJourneyDisruptions([], [expired], [bareLeg("METRO", "11")], window);
    expect(result).toEqual([]);
  });
});
