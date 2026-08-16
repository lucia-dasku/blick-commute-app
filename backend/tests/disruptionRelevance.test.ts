import { describe, expect, it } from "vitest";
import {
  resolveDeviationRelevance,
  resolveJourneyDisruptions,
  type JourneyPlannerNoticeInput,
  type RelevanceLeg,
  type VerifiedJourneyStopScope,
} from "../src/domain/disruptionRelevance.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";

function deviation(overrides: {
  id?: number;
  lines?: Array<{ designation: string; transportMode: string | null }>;
  stopAreaIds?: number[];
  header?: string;
  details?: string;
}): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: overrides.id ?? 1,
    publish: { from: null, upto: null },
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: overrides.header ?? "h", details: overrides.details ?? "d", language: "sv" }],
    scope: {
      stop_areas: overrides.stopAreaIds?.map((id) => ({ id, name: "Test", type: null })),
      lines: overrides.lines?.map((l, i) => ({
        id: i + 1,
        designation: l.designation,
        transport_mode: l.transportMode,
        name: null,
      })),
    },
  };
}

const leg = (transportMode: string, lineDesignation: string): RelevanceLeg => ({ transportMode, lineDesignation });

/** A PARTIAL scope — today's only real shape: whatever Blick could verify (in practice, just the
 * origin's own stop-area ids) is not necessarily the journey's whole stop set. */
const partial = (ids: number[]): VerifiedJourneyStopScope => ({ stopAreaIds: new Set(ids), completeness: "PARTIAL" });

/** A COMPLETE scope — not achievable with any data Blick has today (see disruptionRelevance.ts's
 * own "Known limitation" doc), but exercised here so the resolver is proven future-ready: once
 * verified destination/intermediate stop-area ids exist, this is the shape a caller would build. */
const complete = (ids: number[]): VerifiedJourneyStopScope => ({ stopAreaIds: new Set(ids), completeness: "COMPLETE" });

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
    const result = resolveDeviationRelevance(AKALLA_NO_SERVICE, [leg("METRO", "11")], null);
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });

  it("still LINE_RELEVANT even when a verified origin scope IS supplied -- no stop scope on the deviation itself to check it against", () => {
    const result = resolveDeviationRelevance(AKALLA_NO_SERVICE, [leg("METRO", "11")], partial([9192]));
    expect(result).toEqual({ relevance: "LINE_RELEVANT", matchedLineDesignations: ["11"] });
  });
});

describe("resolveDeviationRelevance — Slussen -> Liljeholmen must stay disruption-free", () => {
  it("an unrelated bus 401 delay at Slussen does not match a Metro 13/14 journey", () => {
    const bus401 = deviation({ id: 2001, lines: [{ designation: "401", transportMode: "BUS" }], stopAreaIds: [9192] });
    const primaryLegs = [leg("METRO", "13"), leg("METRO", "14")];
    expect(resolveDeviationRelevance(bus401, primaryLegs, partial([9192]))).toBeNull();
    expect(resolveDeviationRelevance(bus401, primaryLegs, null)).toBeNull();
  });

  it("shared station without line overlap cannot cause a disruption even with matching stop scope", () => {
    const bus401 = deviation({ id: 2002, lines: [{ designation: "401", transportMode: "BUS" }], stopAreaIds: [9192] });
    expect(resolveDeviationRelevance(bus401, [leg("METRO", "13")], partial([9192, 1011]))).toBeNull();
  });
});

describe("resolveDeviationRelevance — line/mode scope is required, affectedModes alone is never enough", () => {
  it("a deviation with scope.lines empty/absent never matches, however broad affectedModes might be", () => {
    const modeOnly = deviation({ id: 3001, lines: undefined, stopAreaIds: [9192] });
    expect(resolveDeviationRelevance(modeOnly, [leg("METRO", "11")], partial([9192]))).toBeNull();
  });

  it("mode matching alone (different designation) does not match", () => {
    const d = deviation({ id: 3002, lines: [{ designation: "10", transportMode: "METRO" }] });
    expect(resolveDeviationRelevance(d, [leg("METRO", "11")], null)).toBeNull();
  });

  it("designation matching alone (different mode) does not match", () => {
    const d = deviation({ id: 3003, lines: [{ designation: "11", transportMode: "BUS" }] });
    expect(resolveDeviationRelevance(d, [leg("METRO", "11")], null)).toBeNull();
  });

  it("no string/fuzzy ID matching is involved -- a Journey-Planner-shaped id never coincidentally matches a stop-area id", () => {
    // "9091001000009192" (a real Journey Planner place id observed live for Slussen) must never
    // be treated as equivalent to SL Transport siteId 9192 merely because it ends the same way --
    // this test documents that the resolver only ever compares against genuinely-supplied
    // journeyStopScope ids, never derives one from a Journey-Planner-shaped string.
    const stopScoped = deviation({ id: 3004, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [9192] });
    // Passing null here is the honest representation of "no verified stop scope available at
    // all" -- there is no code path in this module that would parse a Journey Planner id to fill
    // it in.
    expect(resolveDeviationRelevance(stopScoped, [leg("METRO", "11")], null)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });
});

describe("resolveDeviationRelevance — PARTIAL stop knowledge: non-intersection is NOT a disproof", () => {
  // The core fix: Blick's only verified stop today is the routine's own ORIGIN. A deviation
  // scoped to a DIFFERENT stop -- the destination, or any intermediate stop -- does not intersect
  // the origin, but that proves nothing about whether the destination/intermediate stop is
  // affected, because the origin is not the journey's whole stop set. Must stay LINE_RELEVANT,
  // never silently drop to UNRELATED.
  const primaryLegs = [leg("METRO", "11")];
  const sameLine = { designation: "11", transportMode: "METRO" } as const;

  it("PARTIAL scope + known stop intersects -> CONFIRMED", () => {
    const d = deviation({ id: 4001, lines: [sameLine], stopAreaIds: [9192, 44000] });
    expect(resolveDeviationRelevance(d, primaryLegs, partial([9192, 1011]))).toEqual({
      relevance: "CONFIRMED",
      matchedLineDesignations: ["11"],
    });
  });

  it("PARTIAL scope + known stop does NOT intersect -> LINE_RELEVANT, not UNRELATED", () => {
    // Regression for the exact bug reported: this used to incorrectly return null.
    const d = deviation({ id: 4002, lines: [sameLine], stopAreaIds: [44000] });
    expect(resolveDeviationRelevance(d, primaryLegs, partial([9192, 1011]))).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });

  it("real-world case: an accessibility issue scoped to the DESTINATION, with only the ORIGIN verified, stays LINE_RELEVANT", () => {
    // Akalla -> Kungsträdgården: PRIMARY uses Metro 11; the deviation is scoped to
    // Kungsträdgården (the destination) alone. Blick's only verified stop is Akalla (the
    // origin), which is a PARTIAL, not COMPLETE, view of the journey's own stops -- so the
    // absence of Akalla from the deviation's scope must never be read as proof Kungsträdgården
    // itself isn't affected. Must NOT be dropped; the compact UI can show "Line 11 disruption ·
    // Tap for details" and Routine Details can show the real accessibility message.
    const KUNGSTRADGARDEN_ACCESSIBILITY = deviation({
      id: 4010,
      header: "Hissen är ur funktion vid Kungsträdgården",
      lines: [sameLine],
      stopAreaIds: [1011], // Kungsträdgården's own stop-area id -- not Akalla's
    });
    const akallaOnly = partial([9192]); // Akalla, the verified origin -- does not intersect [1011]
    expect(resolveDeviationRelevance(KUNGSTRADGARDEN_ACCESSIBILITY, primaryLegs, akallaOnly)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });

  it("Akalla -> T-Centralen: a closure stop-scoped elsewhere on the same line also stays LINE_RELEVANT with only origin knowledge", () => {
    // With only origin knowledge, Blick cannot prove Kungsträdgården (or wherever the deviation
    // is actually scoped) is OUTSIDE this journey's complete route using verified stop ids alone
    // -- so this remains conservatively LINE_RELEVANT rather than being dropped, and the real
    // NO_SERVICE/etc effect must never be presented as confirmed for this exact segment.
    const stopScopedClosure = deviation({
      id: 4011,
      lines: [sameLine],
      stopAreaIds: [1011], // Kungsträdgården's own stop-area id -- not Akalla's
    });
    const akallaOnly = partial([9192]);
    expect(resolveDeviationRelevance(stopScopedClosure, primaryLegs, akallaOnly)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });

  it("fails closed to LINE_RELEVANT (never silently dropped) when no verified stop scope is available at all", () => {
    const d = deviation({ id: 4003, lines: [sameLine], stopAreaIds: [9192] });
    expect(resolveDeviationRelevance(d, primaryLegs, null)).toEqual({
      relevance: "LINE_RELEVANT",
      matchedLineDesignations: ["11"],
    });
  });
});

describe("resolveDeviationRelevance — COMPLETE stop knowledge: non-intersection IS a genuine disproof", () => {
  // Not achievable with any data Blick has today, but proves the resolver is future-ready: once a
  // caller can supply the journey's ENTIRE verified stop set, UNRELATED becomes reachable again
  // for a stop-scoped deviation, exactly as it should be.
  const primaryLegs = [leg("METRO", "11")];
  const sameLine = { designation: "11", transportMode: "METRO" } as const;

  it("COMPLETE scope + no intersection -> UNRELATED (null)", () => {
    const d = deviation({ id: 5001, lines: [sameLine], stopAreaIds: [44000, 55000] });
    expect(resolveDeviationRelevance(d, primaryLegs, complete([9192, 1011, 2000]))).toBeNull();
  });

  it("COMPLETE scope + intersection -> CONFIRMED", () => {
    const d = deviation({ id: 5002, lines: [sameLine], stopAreaIds: [2000] });
    expect(resolveDeviationRelevance(d, primaryLegs, complete([9192, 1011, 2000]))).toEqual({
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
    const result = resolveDeviationRelevance(d, [leg("METRO", "11"), leg("METRO", "13"), leg("METRO", "11")], null);
    expect(result?.matchedLineDesignations).toEqual(["11", "13"]);
  });
});

describe("resolveJourneyDisruptions — deduplication and merging", () => {
  it("returns only the structurally relevant deviations, deduplicated by deviation_case_id", () => {
    const relevant = deviation({ id: 7001, lines: [{ designation: "11", transportMode: "METRO" }] });
    const duplicateOfRelevant = deviation({ id: 7001, lines: [{ designation: "11", transportMode: "METRO" }], header: "dup" });
    const unrelated = deviation({ id: 7002, lines: [{ designation: "401", transportMode: "BUS" }] });
    const result = resolveJourneyDisruptions([], [relevant, duplicateOfRelevant, unrelated], [leg("METRO", "11")], null);
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
    const result = resolveJourneyDisruptions([jp], [], [leg("METRO", "11")], null);
    expect(result[0]!.relevance).toBe("CONFIRMED");
    expect(result[0]!.source).toBe("JOURNEY_PLANNER");
  });

  it("same notice from Journey Planner + SL Deviations appears once, with the richer Deviations details surviving", () => {
    const jp: JourneyPlannerNoticeInput = {
      text: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
      effect: "NO_SERVICE",
    };
    const result = resolveJourneyDisruptions([jp], [AKALLA_NO_SERVICE], [leg("METRO", "11")], null);
    expect(result).toHaveLength(1);
    const merged = result[0]!;
    expect(merged.source).toBe("SL_DEVIATIONS");
    expect(merged.id).toBe("9001");
    expect(merged.details).toBe(
      "På grund av ett tekniskt fel är trafiken på Blå linjen inställd mellan T-Centralen och Kungsträdgården.",
    );
    // Upgraded to CONFIRMED: Journey Planner's own independent attachment to PRIMARY is itself
    // confirming evidence, even though the Deviation's own structured fields alone could only
    // prove LINE_RELEVANT (no stop-area scope on this deviation).
    expect(merged.relevance).toBe("CONFIRMED");
  });

  it("a text-only Journey Planner copy must not replace a richer Deviations copy when both exist", () => {
    const jp: JourneyPlannerNoticeInput = { text: "h", effect: "DISRUPTION" };
    const richDeviation = deviation({ id: 8001, header: "h", details: "much richer real SL text", lines: [{ designation: "11", transportMode: "METRO" }] });
    const result = resolveJourneyDisruptions([jp], [richDeviation], [leg("METRO", "11")], null);
    expect(result).toHaveLength(1);
    expect(result[0]!.details).toBe("much richer real SL text");
    expect(result[0]!.id).toBe("8001");
  });

  it("two genuinely different disruptions are both preserved", () => {
    const jp: JourneyPlannerNoticeInput = { text: "Rerouted via bus", effect: "ROUTE_CHANGE" };
    const unrelatedHeadlineDeviation = deviation({ id: 8002, header: "Different real SL text", lines: [{ designation: "11", transportMode: "METRO" }] });
    const result = resolveJourneyDisruptions([jp], [unrelatedHeadlineDeviation], [leg("METRO", "11")], null);
    expect(result).toHaveLength(2);
    expect(result.map((r) => r.headline)).toEqual(["Rerouted via bus", "Different real SL text"]);
  });

  it("stable deviation ID is preserved on an unmerged SL_DEVIATIONS entry", () => {
    const d = deviation({ id: 9999, lines: [{ designation: "11", transportMode: "METRO" }] });
    const result = resolveJourneyDisruptions([], [d], [leg("METRO", "11")], null);
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

  it("journeyStopScope is forwarded unchanged to resolveDeviationRelevance for every deviation", () => {
    const d = deviation({ id: 9998, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [2000] });
    const result = resolveJourneyDisruptions([], [d], [leg("METRO", "11")], complete([2000]));
    expect(result[0]!.relevance).toBe("CONFIRMED");
  });
});
