import { describe, expect, it } from "vitest";
import { resolveExactJourneyOriginStopArea, resolveLegScopes, scopePolicyForEffect, type ExactJourneyOriginStopArea } from "../src/domain/journeyDisruptionScope.js";
import { DISRUPTION_EFFECTS, type DisruptionEffect } from "../src/models/disruption.js";
import { JOURNEY_DISRUPTION_CONTEXT_VERSION, type JourneyDisruptionContext, type JourneyDisruptionContextLeg } from "../src/models/journeyDisruptionContext.js";
import type { Site } from "../src/models/site.js";
import type { PatternPointGid, StopPointDirectory, StopPointResolution } from "../src/services/stopPointDirectory.js";

function leg(overrides: Partial<JourneyDisruptionContextLeg> & { transportMode: string }): JourneyDisruptionContextLeg {
  return {
    lineDesignation: null,
    stopPatternPointGids: [],
    stopSequenceComplete: false,
    ...overrides,
  };
}

function context(legs: JourneyDisruptionContextLeg[]): JourneyDisruptionContext {
  return { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Start", journeyEnd: "End", legs };
}

/** A directory whose resolution table is fully controlled by the test -- any gid not listed is
 * UNRESOLVED, matching StopPointDirectory's own real "every input present exactly once" contract. */
function fakeDirectory(table: Record<string, { stopPointId: number; stopAreaId: number; stopAreaType?: string | null } | "AMBIGUOUS" | { stopAreaOnly: number }>): StopPointDirectory {
  return {
    async resolveMany(gids: readonly PatternPointGid[]) {
      const result = new Map<PatternPointGid, StopPointResolution>();
      for (const gid of gids) {
        const entry = table[gid];
        if (entry == null) result.set(gid, { status: "UNRESOLVED", patternPointGid: gid });
        else if (entry === "AMBIGUOUS") result.set(gid, { status: "AMBIGUOUS", patternPointGid: gid, stopAreaIds: [1, 2] });
        else if ("stopAreaOnly" in entry) result.set(gid, { status: "STOP_AREA_ONLY", patternPointGid: gid, stopAreaId: entry.stopAreaOnly, stopAreaType: null });
        else result.set(gid, { status: "RESOLVED", patternPointGid: gid, stopPointId: entry.stopPointId, stopAreaId: entry.stopAreaId, stopAreaType: entry.stopAreaType ?? null });
      }
      return result;
    },
  };
}

function site(siteId: number, stopAreaIds: number[]): Site {
  return { siteId, name: `Site ${siteId}`, note: null, lat: null, lon: null, stopAreaIds };
}

describe("scopePolicyForEffect", () => {
  it("maps access-point effects", () => {
    expect(scopePolicyForEffect("ACCESSIBILITY_ISSUE")).toBe("ACCESS_POINTS");
    expect(scopePolicyForEffect("STATION_ACCESS")).toBe("ACCESS_POINTS");
    expect(scopePolicyForEffect("STOP_CHANGE")).toBe("ACCESS_POINTS");
  });

  it("maps travelled-path effects", () => {
    expect(scopePolicyForEffect("DELAYS")).toBe("TRAVELLED_PATH");
    expect(scopePolicyForEffect("NO_SERVICE")).toBe("TRAVELLED_PATH");
    expect(scopePolicyForEffect("REDUCED_SERVICE")).toBe("TRAVELLED_PATH");
    expect(scopePolicyForEffect("ROUTE_CHANGE")).toBe("TRAVELLED_PATH");
    expect(scopePolicyForEffect("REPLACEMENT_SERVICE")).toBe("TRAVELLED_PATH");
    expect(scopePolicyForEffect("DISRUPTION")).toBe("TRAVELLED_PATH");
  });

  it("every single DisruptionEffect value has a defined policy -- exhaustive, so a future 10th effect cannot silently slip through", () => {
    const expected: Record<DisruptionEffect, "ACCESS_POINTS" | "TRAVELLED_PATH"> = {
      ACCESSIBILITY_ISSUE: "ACCESS_POINTS",
      STATION_ACCESS: "ACCESS_POINTS",
      STOP_CHANGE: "ACCESS_POINTS",
      DELAYS: "TRAVELLED_PATH",
      NO_SERVICE: "TRAVELLED_PATH",
      REDUCED_SERVICE: "TRAVELLED_PATH",
      ROUTE_CHANGE: "TRAVELLED_PATH",
      REPLACEMENT_SERVICE: "TRAVELLED_PATH",
      DISRUPTION: "TRAVELLED_PATH",
    };
    expect(DISRUPTION_EFFECTS.length).toBe(9);
    for (const effect of DISRUPTION_EFFECTS) {
      expect(scopePolicyForEffect(effect)).toBe(expected[effect]);
    }
  });
});

describe("resolveLegScopes — direct journey", () => {
  it("ACCESS_POINTS contains exactly boarding + alighting; TRAVELLED_PATH contains every stop", async () => {
    const directory = fakeDirectory({
      akalla: { stopPointId: 3272, stopAreaId: 3271 },
      husby: { stopPointId: 3262, stopAreaId: 3261 },
      kista: { stopPointId: 3252, stopAreaId: 3251 },
      tcentralen: { stopPointId: 3051, stopAreaId: 1051 },
    });
    const ctx = context([
      leg({
        transportMode: "METRO",
        lineDesignation: "11",
        boardingPatternPointGid: "akalla",
        alightingPatternPointGid: "tcentralen",
        stopPatternPointGids: ["akalla", "husby", "kista", "tcentralen"],
        stopSequenceComplete: true,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([3271, 1051]));
    expect(scope!.accessPoints.completeness).toBe("COMPLETE");
    expect(scope!.travelledPath.stopAreaIds).toEqual(new Set([3271, 3261, 3251, 1051]));
    expect(scope!.travelledPath.completeness).toBe("COMPLETE");

    // Kista is on the travelled path but must never appear as an access point -- the passenger
    // stays onboard through it.
    expect(scope!.accessPoints.stopAreaIds.has(3251)).toBe(false);
    expect(scope!.travelledPath.stopAreaIds.has(3251)).toBe(true);
  });

  it("TRAVELLED_PATH is PARTIAL when one intermediate platform is unresolved, but ACCESS_POINTS can still be COMPLETE", async () => {
    const directory = fakeDirectory({
      akalla: { stopPointId: 3272, stopAreaId: 3271 },
      tcentralen: { stopPointId: 3051, stopAreaId: 1051 },
      // "kista" deliberately absent -- UNRESOLVED
    });
    const ctx = context([
      leg({
        transportMode: "METRO",
        lineDesignation: "11",
        boardingPatternPointGid: "akalla",
        alightingPatternPointGid: "tcentralen",
        stopPatternPointGids: ["akalla", "kista", "tcentralen"],
        stopSequenceComplete: true,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.travelledPath.completeness).toBe("PARTIAL");
    expect(scope!.accessPoints.completeness).toBe("COMPLETE");
  });

  it("TRAVELLED_PATH is PARTIAL when stopSequenceComplete is false, even if every listed stop resolved", async () => {
    const directory = fakeDirectory({
      akalla: { stopPointId: 3272, stopAreaId: 3271 },
      tcentralen: { stopPointId: 3051, stopAreaId: 1051 },
    });
    const ctx = context([
      leg({
        transportMode: "METRO",
        lineDesignation: "11",
        boardingPatternPointGid: "akalla",
        alightingPatternPointGid: "tcentralen",
        stopPatternPointGids: [], // SL supplied no stopSequence at all
        stopSequenceComplete: false,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.travelledPath.completeness).toBe("PARTIAL");
    expect(scope!.travelledPath.stopAreaIds.size).toBe(0);
  });

  it("ACCESS_POINTS is PARTIAL when either boarding or alighting fails to resolve", async () => {
    const directory = fakeDirectory({ tcentralen: { stopPointId: 3051, stopAreaId: 1051 } });
    const ctx = context([
      leg({
        transportMode: "METRO",
        lineDesignation: "11",
        boardingPatternPointGid: "unresolvable",
        alightingPatternPointGid: "tcentralen",
        stopSequenceComplete: false,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.accessPoints.completeness).toBe("PARTIAL");
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([1051]));
  });

  it("AMBIGUOUS resolutions contribute no stop identity, same as UNRESOLVED", async () => {
    const directory = fakeDirectory({ boarding: "AMBIGUOUS", alighting: { stopPointId: 1, stopAreaId: 1 } });
    const ctx = context([
      leg({ transportMode: "METRO", lineDesignation: "11", boardingPatternPointGid: "boarding", alightingPatternPointGid: "alighting" }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.accessPoints.completeness).toBe("PARTIAL");
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([1]));
  });

  it("duplicate stops in the sequence are deduplicated without losing completeness", async () => {
    const directory = fakeDirectory({ a: { stopPointId: 1, stopAreaId: 10 }, b: { stopPointId: 2, stopAreaId: 20 } });
    const ctx = context([
      leg({
        transportMode: "METRO",
        lineDesignation: "11",
        boardingPatternPointGid: "a",
        alightingPatternPointGid: "a", // a loop route back to its own origin
        stopPatternPointGids: ["a", "b", "a"],
        stopSequenceComplete: true,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.travelledPath.stopAreaIds).toEqual(new Set([10, 20]));
    expect(scope!.travelledPath.completeness).toBe("COMPLETE");
  });
});

describe("resolveLegScopes — transfers", () => {
  it("a WALK leg is skipped entirely; each adjacent transit leg keeps its own boarding/alighting", async () => {
    const directory = fakeDirectory({
      a: { stopPointId: 1, stopAreaId: 10 },
      "slussen-metro": { stopPointId: 2, stopAreaId: 1011 },
      "slussen-bus": { stopPointId: 3, stopAreaId: 44000 },
      e: { stopPointId: 4, stopAreaId: 40 },
    });
    const ctx = context([
      leg({ transportMode: "METRO", lineDesignation: "19", boardingPatternPointGid: "a", alightingPatternPointGid: "slussen-metro" }),
      leg({ transportMode: "WALK", boardingPatternPointGid: "slussen-metro", alightingPatternPointGid: "slussen-bus" }),
      leg({ transportMode: "BUS", lineDesignation: "471", boardingPatternPointGid: "slussen-bus", alightingPatternPointGid: "e" }),
    ]);

    const scopes = await resolveLegScopes(ctx, directory);
    expect(scopes).toHaveLength(2); // the WALK leg produces no entry of its own

    const metroLeg = scopes.find((s) => s.lineDesignation === "19")!;
    const busLeg = scopes.find((s) => s.lineDesignation === "471")!;
    expect(metroLeg.accessPoints.stopAreaIds).toEqual(new Set([10, 1011]));
    expect(busLeg.accessPoints.stopAreaIds).toEqual(new Set([44000, 40]));
    // The transfer's two distinct Slussen stop areas (metro station vs bus terminal) are never
    // conflated into one -- each leg only ever sees its OWN boarding/alighting.
    expect(metroLeg.accessPoints.stopAreaIds.has(44000)).toBe(false);
    expect(busLeg.accessPoints.stopAreaIds.has(1011)).toBe(false);
  });

  it("a disruption's own line only ever matches the leg that actually uses it, never a structurally unrelated leg", async () => {
    const directory = fakeDirectory({
      a: { stopPointId: 1, stopAreaId: 10 },
      c1: { stopPointId: 2, stopAreaId: 20 },
      c2: { stopPointId: 3, stopAreaId: 20 },
      e: { stopPointId: 4, stopAreaId: 40 },
    });
    const ctx = context([
      leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "a", alightingPatternPointGid: "c1" }),
      leg({ transportMode: "METRO", lineDesignation: "14", boardingPatternPointGid: "c2", alightingPatternPointGid: "e" }),
    ]);

    const scopes = await resolveLegScopes(ctx, directory);
    const line13 = scopes.find((s) => s.lineDesignation === "13")!;
    const line14 = scopes.find((s) => s.lineDesignation === "14")!;
    // Both legs' own access points happen to include the shared transfer station (20), but
    // line13's own scope must never include line14's OTHER endpoint (40) or vice versa (10).
    expect(line13.accessPoints.stopAreaIds).toEqual(new Set([10, 20]));
    expect(line14.accessPoints.stopAreaIds).toEqual(new Set([20, 40]));
  });
});

describe("resolveExactJourneyOriginStopArea", () => {
  it("a site with zero StopAreas is UNRESOLVED", () => {
    expect(resolveExactJourneyOriginStopArea(9192, [site(9192, [])])).toEqual({ status: "UNRESOLVED" });
  });

  it("a site with exactly one StopArea is RESOLVED to it", () => {
    expect(resolveExactJourneyOriginStopArea(9001, [site(9001, [1051])])).toEqual({ status: "RESOLVED", stopAreaId: 1051 });
  });

  it("a multi-mode site (Slussen: metro 1011 + bus 44000) with more than one StopArea is AMBIGUOUS", () => {
    expect(resolveExactJourneyOriginStopArea(9192, [site(9192, [1011, 44000])])).toEqual({ status: "AMBIGUOUS", stopAreaIds: [1011, 44000] });
  });

  it("an unknown siteId is UNRESOLVED, never guessed", () => {
    expect(resolveExactJourneyOriginStopArea(9192, [site(1234, [1011])])).toEqual({ status: "UNRESOLVED" });
  });

  it("never returns the siteId itself as a StopArea id unless it independently IS one of the site's own child StopAreas", () => {
    // Slussen's own siteId (9192) must never leak into the result merely by virtue of being the
    // siteId -- only genuine child StopArea ids (1011, 44000) are ever considered.
    const result = resolveExactJourneyOriginStopArea(9192, [site(9192, [1011, 44000])]);
    expect(result).toEqual({ status: "AMBIGUOUS", stopAreaIds: [1011, 44000] });
    if (result.status === "AMBIGUOUS") {
      expect(result.stopAreaIds).not.toContain(9192);
    }
  });
});

describe("resolveLegScopes — origin fallback: exact platform resolution always wins", () => {
  // Regression for the multi-mode origin bug: Slussen boards Metro (StopArea 1011); the routine's
  // own origin site also has a Bus StopArea (44000). The exact platform mapping must never be
  // additionally broadened by the coarser site, at a multi-mode site or otherwise.
  const SLUSSEN_MULTI_MODE: ExactJourneyOriginStopArea = { status: "AMBIGUOUS", stopAreaIds: [1011, 44000] };

  it("ACCESS_POINTS contains the exact boarding StopArea and never the origin site's other StopArea", async () => {
    const directory = fakeDirectory({
      "slussen-metro": { stopPointId: 1012, stopAreaId: 1011 },
      liljeholmen: { stopPointId: 5, stopAreaId: 1294 },
    });
    const ctx = context([
      leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "slussen-metro", alightingPatternPointGid: "liljeholmen" }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory, SLUSSEN_MULTI_MODE);
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([1011, 1294]));
    expect(scope!.accessPoints.stopAreaIds.has(44000)).toBe(false);
    expect(scope!.accessPoints.completeness).toBe("COMPLETE");
  });

  it("the broad multi-mode origin fallback is never unioned in even when boarding resolution succeeds via a single-StopArea site", async () => {
    // Even the "safe to rescue" single-StopArea case must never ADD to an already-exact result.
    const directory = fakeDirectory({
      akalla: { stopPointId: 3272, stopAreaId: 3271 },
      tcentralen: { stopPointId: 3051, stopAreaId: 1051 },
    });
    const ctx = context([
      leg({ transportMode: "METRO", lineDesignation: "11", boardingPatternPointGid: "akalla", alightingPatternPointGid: "tcentralen" }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory, { status: "RESOLVED", stopAreaId: 9999 });
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([3271, 1051]));
    expect(scope!.accessPoints.stopAreaIds.has(9999)).toBe(false);
  });
});

describe("resolveLegScopes — origin fallback: single-StopArea site rescues an unresolved boarding side", () => {
  it("boarding unresolved + site has exactly one StopArea -> boarding rescued; ACCESS_POINTS COMPLETE once alighting also resolves", async () => {
    const directory = fakeDirectory({
      // "a" (the boarding platform) deliberately unresolvable -- only the origin fallback saves it.
      c: { stopPointId: 2, stopAreaId: 20 },
    });
    const ctx = context([leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "a", alightingPatternPointGid: "c" })]);

    const [scope] = await resolveLegScopes(ctx, directory, { status: "RESOLVED", stopAreaId: 9999 });
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([9999, 20]));
    expect(scope!.accessPoints.completeness).toBe("COMPLETE");
  });

  it("the single-StopArea rescue only ever applies to the journey's own FIRST transit leg", async () => {
    const directory = fakeDirectory({
      a: { stopPointId: 1, stopAreaId: 10 },
      c: { stopPointId: 2, stopAreaId: 20 },
      e: { stopPointId: 3, stopAreaId: 30 },
    });
    const ctx = context([
      leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "a", alightingPatternPointGid: "c" }),
      // second leg's own boarding ("c") already resolves exactly -- the fallback is only ever
      // consulted for the first leg regardless, but this also proves it's never applied here.
      leg({ transportMode: "METRO", lineDesignation: "14", boardingPatternPointGid: "unresolvable", alightingPatternPointGid: "e" }),
    ]);

    const scopes = await resolveLegScopes(ctx, directory, { status: "RESOLVED", stopAreaId: 9999 });
    const line14 = scopes.find((s) => s.lineDesignation === "14")!;
    expect(line14.accessPoints.stopAreaIds.has(9999)).toBe(false);
    expect(line14.accessPoints.completeness).toBe("PARTIAL");
  });
});

describe("resolveLegScopes — origin fallback: multi-StopArea site never arbitrarily picks one", () => {
  it("boarding unresolved + site has StopAreas A and B -> neither is chosen; ACCESS_POINTS is PARTIAL but keeps the verified alighting StopArea", async () => {
    const directory = fakeDirectory({ c: { stopPointId: 2, stopAreaId: 20 } });
    const ctx = context([leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "a", alightingPatternPointGid: "c" })]);

    const [scope] = await resolveLegScopes(ctx, directory, { status: "AMBIGUOUS", stopAreaIds: [1011, 44000] });
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([20])); // only the verified alighting point
    expect(scope!.accessPoints.stopAreaIds.has(1011)).toBe(false);
    expect(scope!.accessPoints.stopAreaIds.has(44000)).toBe(false);
    expect(scope!.accessPoints.completeness).toBe("PARTIAL");
  });

  it("an UNRESOLVED origin (no known StopArea at all) behaves the same way -- PARTIAL, verified alighting kept", async () => {
    const directory = fakeDirectory({ c: { stopPointId: 2, stopAreaId: 20 } });
    const ctx = context([leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "a", alightingPatternPointGid: "c" })]);

    const [scope] = await resolveLegScopes(ctx, directory, { status: "UNRESOLVED" });
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([20]));
    expect(scope!.accessPoints.completeness).toBe("PARTIAL");
  });
});

describe("resolveLegScopes — origin fallback never pollutes TRAVELLED_PATH", () => {
  it("TRAVELLED_PATH still comes only from stopSequence -- a rescuing single-StopArea fallback is never injected into it", async () => {
    const directory = fakeDirectory({
      c: { stopPointId: 2, stopAreaId: 20 },
      d: { stopPointId: 3, stopAreaId: 30 },
    });
    const ctx = context([
      leg({
        transportMode: "METRO", lineDesignation: "13",
        boardingPatternPointGid: "a", alightingPatternPointGid: "c",
        stopPatternPointGids: ["c", "d"], stopSequenceComplete: true,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory, { status: "RESOLVED", stopAreaId: 9999 });
    expect(scope!.accessPoints.stopAreaIds.has(9999)).toBe(true); // rescued ACCESS_POINTS
    expect(scope!.travelledPath.stopAreaIds.has(9999)).toBe(false); // never leaks into TRAVELLED_PATH
    expect(scope!.travelledPath.stopAreaIds).toEqual(new Set([20, 30]));
  });

  it("a multi-mode (AMBIGUOUS) origin site never broadens TRAVELLED_PATH either", async () => {
    const directory = fakeDirectory({ c: { stopPointId: 2, stopAreaId: 20 } });
    const ctx = context([
      leg({
        transportMode: "METRO", lineDesignation: "13",
        boardingPatternPointGid: "a", alightingPatternPointGid: "c",
        stopPatternPointGids: ["c"], stopSequenceComplete: true,
      }),
    ]);

    const [scope] = await resolveLegScopes(ctx, directory, { status: "AMBIGUOUS", stopAreaIds: [1011, 44000] });
    expect(scope!.travelledPath.stopAreaIds).toEqual(new Set([20]));
    expect(scope!.travelledPath.stopAreaIds.has(1011)).toBe(false);
    expect(scope!.travelledPath.stopAreaIds.has(44000)).toBe(false);
  });
});

describe("resolveLegScopes — STOP_AREA_ONLY resolutions", () => {
  it("a STOP_AREA_ONLY resolution contributes its StopArea but never a StopPoint id", async () => {
    const directory = fakeDirectory({
      a: { stopAreaOnly: 1011 },
      c: { stopPointId: 2, stopAreaId: 20 },
    });
    const ctx = context([leg({ transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "a", alightingPatternPointGid: "c" })]);

    const [scope] = await resolveLegScopes(ctx, directory);
    expect(scope!.accessPoints.stopAreaIds).toEqual(new Set([1011, 20]));
    expect(scope!.accessPoints.completeness).toBe("COMPLETE"); // StopArea-level evidence is still enough
    expect(scope!.accessPoints.stopPointIds).toEqual(new Set([2])); // only the genuinely resolved side
  });
});

describe("resolveLegScopes — no transit legs", () => {
  it("returns an empty array for an all-WALK journey rather than throwing", async () => {
    const directory = fakeDirectory({});
    const ctx = context([leg({ transportMode: "WALK", boardingPatternPointGid: "a", alightingPatternPointGid: "b" })]);
    const scopes = await resolveLegScopes(ctx, directory);
    expect(scopes).toEqual([]);
  });
});
