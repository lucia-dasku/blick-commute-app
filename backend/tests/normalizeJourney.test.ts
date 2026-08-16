import { describe, expect, it } from "vitest";
import { normalizeJourney } from "../src/normalize/normalizeJourney.js";
import { isEligibleJourney, MAX_CHANGES } from "../src/services/candidateCollector.js";

/** A minimal transit (non-WALK) leg — Metro line 14 by default — with just enough shape
 * for normalizeJourney to resolve journeyId/departureTime/arrivalTime and, for these
 * tests, exercise stopIds resolution specifically. Every place defaults to SL's own real,
 * confirmed shape (see backend/fixtures/*.sample.json): `type: "platform"` with a
 * `type: "stop"` parent — override `*Type`/`parentType` to exercise the other cases
 * canonicalStopId must handle (a bare id with no type at all, a "stop" that itself carries
 * a further, non-canonical parent, a deeper chain, ...). */
function transitLeg(overrides: {
  originId?: string;
  originType?: string | null;
  originParentId?: string;
  originParentType?: string | null;
  destinationId?: string;
  destinationType?: string | null;
  destinationParentId?: string;
  destinationParentType?: string | null;
  stopSequence?: Array<{ id?: string; type?: string | null; parentId?: string; parentType?: string | null; grandparentId?: string; grandparentType?: string | null }>;
  tripId?: string;
} = {}) {
  return {
    origin: {
      id: overrides.originId ?? "platform-origin",
      name: "Origin",
      ...(overrides.originType !== null ? { type: overrides.originType ?? "platform" } : {}),
      departureTimeEstimated: "2026-08-10T08:00:00Z",
      ...(overrides.originParentId != null
        ? { parent: { id: overrides.originParentId, name: "Origin area", ...(overrides.originParentType !== null ? { type: overrides.originParentType ?? "stop" } : {}) } }
        : {}),
    },
    destination: {
      id: overrides.destinationId ?? "platform-destination",
      name: "Destination",
      ...(overrides.destinationType !== null ? { type: overrides.destinationType ?? "platform" } : {}),
      arrivalTimeEstimated: "2026-08-10T08:20:00Z",
      ...(overrides.destinationParentId != null
        ? { parent: { id: overrides.destinationParentId, name: "Destination area", ...(overrides.destinationParentType !== null ? { type: overrides.destinationParentType ?? "stop" } : {}) } }
        : {}),
    },
    transportation: {
      disassembledName: "14",
      product: { class: 2, name: "Tunnelbana" },
      destination: { name: "Somewhere" },
    },
    properties: { tripId: overrides.tripId ?? "trip-1" },
    infos: [],
    ...(overrides.stopSequence != null
      ? {
          stopSequence: overrides.stopSequence.map((s) => ({
            id: s.id,
            name: "Stop",
            ...(s.type !== null ? { type: s.type ?? "platform" } : {}),
            ...(s.parentId != null
              ? {
                  parent: {
                    id: s.parentId,
                    name: "Area",
                    ...(s.parentType !== null ? { type: s.parentType ?? "stop" } : {}),
                    ...(s.grandparentId != null
                      ? { parent: { id: s.grandparentId, name: "Region", ...(s.grandparentType !== null ? { type: s.grandparentType ?? "locality" } : {}) } }
                      : {}),
                  },
                }
              : {}),
          })),
        }
      : {}),
  };
}

/** A minimal footpath/WALK leg — SL's own product.class=99. */
function walkLeg(overrides: { duration?: number } = {}) {
  return {
    origin: { id: "walk-origin", name: "Walk origin", departureTimePlanned: "2026-08-10T08:20:00Z" },
    destination: { id: "walk-destination", name: "Walk destination", arrivalTimePlanned: "2026-08-10T08:24:00Z" },
    transportation: { product: { class: 99, name: "footpath" } },
    infos: [],
    ...(overrides.duration !== undefined ? { duration: overrides.duration } : {}),
  };
}

describe("normalizeJourney: canonical stopIds", () => {
  it("canonicalizes stopSequence entries to their parent stop-area id when present", () => {
    const raw = {
      interchanges: 0,
      legs: [
        transitLeg({
          stopSequence: [
            { id: "platform-A", parentId: "area-A" },
            { id: "platform-B", parentId: "area-B" },
          ],
        }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["area-A", "area-B"]);
  });

  it("falls back to the stop's own id when no parent stop-area is present", () => {
    const raw = {
      interchanges: 0,
      legs: [transitLeg({ stopSequence: [{ id: "platform-A" }, { id: "platform-B" }] })],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["platform-A", "platform-B"]);
  });

  it("falls back to origin/destination alone when stopSequence is absent", () => {
    const raw = {
      interchanges: 0,
      legs: [
        transitLeg({
          originId: "platform-origin",
          originParentId: "area-origin",
          destinationId: "platform-dest",
          destinationParentId: "area-dest",
        }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["area-origin", "area-dest"]);
  });

  it("drops a stopSequence entry with no resolvable id at all rather than leaving a gap", () => {
    const raw = {
      interchanges: 0,
      legs: [transitLeg({ stopSequence: [{ id: "platform-A" }, {}, { id: "platform-B" }] })],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["platform-A", "platform-B"]);
  });

  it("a WALK leg has no route-family stop identity of its own", () => {
    const raw = { interchanges: 0, legs: [transitLeg(), walkLeg({ duration: 120 }), transitLeg({ tripId: "trip-2" })] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[1]!.transportMode).toBe("WALK");
    expect(normalized.legs[1]!.stopIds).toEqual([]);
  });
});

describe("normalizeJourney: canonical stop-area resolution respects SL's own place `type`", () => {
  it("two different platforms canonicalize to the same stop when they share a stop-typed parent", () => {
    const raw = {
      interchanges: 0,
      legs: [
        transitLeg({
          stopSequence: [
            { id: "platform-A1", type: "platform", parentId: "area-A", parentType: "stop" },
            { id: "platform-A2", type: "platform", parentId: "area-A", parentType: "stop" },
          ],
        }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["area-A", "area-A"]);
  });

  it("a stop-area that itself carries a further (e.g. locality) parent canonicalizes to ITSELF, not that further parent", () => {
    // The bug this fix corrects: the old `place.parent?.id ?? place.id` logic would have
    // used the locality's id here purely because a parent object was present, regardless
    // of the fact that `place` itself already IS the canonical stop-area.
    const raw = {
      interchanges: 0,
      legs: [
        transitLeg({
          stopSequence: [
            { id: "area-slussen", type: "stop", parentId: "region-stockholm", parentType: "locality" },
            { id: "area-tcentralen", type: "stop", parentId: "region-stockholm", parentType: "locality" },
          ],
        }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    // Slussen and T-Centralen must never collapse into their shared "Stockholm" parent.
    expect(normalized.legs[0]!.stopIds).toEqual(["area-slussen", "area-tcentralen"]);
  });

  it("platform -> stop -> locality: canonicalizes to the stop, never continuing on to the locality beyond it", () => {
    const raw = {
      interchanges: 0,
      legs: [
        transitLeg({
          stopSequence: [
            { id: "platform-1", type: "platform", parentId: "area-slussen", parentType: "stop", grandparentId: "region-stockholm", grandparentType: "locality" },
            { id: "platform-2", type: "platform", parentId: "area-tcentralen", parentType: "stop", grandparentId: "region-stockholm", grandparentType: "locality" },
          ],
        }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["area-slussen", "area-tcentralen"]);
  });

  it("falls back to the place's own id when no node in the chain is ever typed as a stop-area at all", () => {
    const raw = {
      interchanges: 0,
      legs: [
        transitLeg({
          // Neither the place nor its parent carries a `type` at all -- a schema variant
          // this hasn't confirmed. Must fall back defensively, never guess a stop identity.
          stopSequence: [
            { id: "unknown-1", type: null, parentId: "unknown-parent-1", parentType: null },
            { id: "unknown-2", type: null, parentId: "unknown-parent-2", parentType: null },
          ],
        }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.legs[0]!.stopIds).toEqual(["unknown-1", "unknown-2"]);
  });
});

describe("normalizeJourney: walkingDurationSeconds", () => {
  it("sums walking duration across every WALK leg", () => {
    const raw = { interchanges: 1, legs: [transitLeg(), walkLeg({ duration: 90 }), transitLeg({ tripId: "trip-2" }), walkLeg({ duration: 30 })] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.walkingDurationSeconds).toBe(120);
  });

  it("reports walking duration as unknown (null), never zero, when a WALK leg's own duration is missing", () => {
    const raw = { interchanges: 0, legs: [transitLeg(), walkLeg()] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.walkingDurationSeconds).toBeNull();
  });

  it("reports zero walking duration, not unknown, when the journey has no WALK legs at all", () => {
    const raw = { interchanges: 0, legs: [transitLeg()] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.walkingDurationSeconds).toBe(0);
  });
});

describe("normalizeJourney: transferCount fallback", () => {
  it("trusts SL's own explicit interchanges even when it disagrees with the leg count", () => {
    const raw = {
      interchanges: 5,
      legs: [transitLeg({ tripId: "t1" }), transitLeg({ tripId: "t2" })],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.transferCount).toBe(5);
  });

  it("missing interchanges with a single transit leg derives 0", () => {
    const raw = { legs: [transitLeg()] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.transferCount).toBe(0);
  });

  it("missing interchanges with two transit legs derives 1", () => {
    const raw = { legs: [transitLeg({ tripId: "t1" }), transitLeg({ tripId: "t2" })] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.transferCount).toBe(1);
  });

  it("missing interchanges with a WALK leg plus a single transit leg derives 0 -- WALK is never counted as a transfer", () => {
    const raw = { legs: [transitLeg(), walkLeg({ duration: 60 })] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.transferCount).toBe(0);
  });

  it("missing interchanges with a WALK leg plus two transit legs derives 1 -- the WALK leg is not counted", () => {
    const raw = { legs: [transitLeg({ tripId: "t1" }), walkLeg({ duration: 60 }), transitLeg({ tripId: "t2" })] };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.transferCount).toBe(1);
  });

  it("missing interchanges with four transit legs derives 3, which MAX_CHANGES then correctly rejects", () => {
    const raw = {
      legs: [
        transitLeg({ tripId: "t1" }),
        transitLeg({ tripId: "t2" }),
        transitLeg({ tripId: "t3" }),
        transitLeg({ tripId: "t4" }),
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.transferCount).toBe(3);

    // The whole point of never undercounting: a journey that SHOULD be over the transfer
    // limit must still actually BE rejected by it, not silently slip through as if it had
    // fewer changes than it really does.
    expect(MAX_CHANGES).toBe(2);
    expect(isEligibleJourney(normalized, Date.parse("2026-08-10T00:00:00Z"))).toBe(false);
  });
});

/** A raw place node for disruptionContext-focused tests -- deliberately separate from
 * `transitLeg`'s own origin/destination/stopSequence builders above, which are tuned for
 * canonicalStopId's parent-chain resolution rather than the flatter
 * `type`/`isGlobalId`/`id` shape `platformPatternPointGid` (normalizeJourney.ts) reads. */
function place(overrides: { id?: string; type?: string; isGlobalId?: boolean; name?: string } = {}) {
  return { id: overrides.id, name: overrides.name ?? "Place", type: overrides.type, isGlobalId: overrides.isGlobalId };
}

describe("normalizeJourney: disruptionContext", () => {
  it("extracts boarding/alighting pattern-point gids and a complete stopPatternPointGids sequence for a direct platform-to-platform leg", () => {
    const raw = {
      interchanges: 0,
      legs: [
        {
          origin: { ...place({ id: "9025001000003272", type: "platform", isGlobalId: true, name: "Akalla" }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "9025001000003051", type: "platform", isGlobalId: true, name: "T-Centralen" }), arrivalTimeEstimated: "2026-08-16T08:19:00Z" },
          transportation: { disassembledName: "11", product: { class: 2, name: "Tunnelbana" }, destination: { name: "T-Centralen" } },
          properties: { tripId: "trip-1" },
          infos: [],
          stopSequence: [
            place({ id: "9025001000003272", type: "platform", isGlobalId: true, name: "Akalla" }),
            place({ id: "9025001000003262", type: "platform", isGlobalId: true, name: "Husby" }),
            place({ id: "9025001000003051", type: "platform", isGlobalId: true, name: "T-Centralen" }),
          ],
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    expect(normalized.disruptionContext).toEqual({
      version: 1,
      journeyStart: "Akalla",
      journeyEnd: "T-Centralen",
      legs: [
        {
          transportMode: "METRO",
          lineDesignation: "11",
          boardingPatternPointGid: "9025001000003272",
          alightingPatternPointGid: "9025001000003051",
          stopPatternPointGids: ["9025001000003272", "9025001000003262", "9025001000003051"],
          stopSequenceComplete: true,
        },
      ],
    });
  });

  it("marks stopSequenceComplete false, but still resolves boarding/alighting directly, when stopSequence is absent", () => {
    const raw = {
      interchanges: 0,
      legs: [
        {
          origin: { ...place({ id: "9025001000003272", type: "platform", isGlobalId: true }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "9025001000003051", type: "platform", isGlobalId: true }), arrivalTimeEstimated: "2026-08-16T08:19:00Z" },
          transportation: { disassembledName: "11", product: { class: 2, name: "Tunnelbana" } },
          properties: { tripId: "trip-1" },
          infos: [],
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    const leg = normalized.disruptionContext.legs[0]!;
    expect(leg.boardingPatternPointGid).toBe("9025001000003272");
    expect(leg.alightingPatternPointGid).toBe("9025001000003051");
    expect(leg.stopPatternPointGids).toEqual([]);
    expect(leg.stopSequenceComplete).toBe(false);
  });

  it("leaves boardingPatternPointGid undefined when the origin is a coarser type:'stop' node, even though the destination resolves fine", () => {
    // Confirmed live: Journey Planner can echo a leg's own origin as `type: "stop"` (no
    // pinned platform yet) even when the SAME physical stop appears as a resolvable
    // `type: "platform"` node elsewhere in that same trip.
    const raw = {
      interchanges: 0,
      legs: [
        {
          origin: { ...place({ id: "9021001003271000", type: "stop", isGlobalId: true, name: "Akalla" }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "9025001000003051", type: "platform", isGlobalId: true, name: "T-Centralen" }), arrivalTimeEstimated: "2026-08-16T08:19:00Z" },
          transportation: { disassembledName: "11", product: { class: 2, name: "Tunnelbana" } },
          properties: { tripId: "trip-1" },
          infos: [],
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    const leg = normalized.disruptionContext.legs[0]!;
    expect(leg.boardingPatternPointGid).toBeUndefined();
    expect(leg.alightingPatternPointGid).toBe("9025001000003051");
  });

  it("excludes a non-platform stopSequence entry from stopPatternPointGids and marks the sequence incomplete", () => {
    const raw = {
      interchanges: 0,
      legs: [
        {
          origin: { ...place({ id: "A", type: "stop", isGlobalId: true }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "C", type: "platform", isGlobalId: true }), arrivalTimeEstimated: "2026-08-16T08:19:00Z" },
          transportation: { disassembledName: "11", product: { class: 2, name: "Tunnelbana" } },
          properties: { tripId: "trip-1" },
          infos: [],
          stopSequence: [
            place({ id: "A", type: "stop", isGlobalId: true }), // origin echoed as a coarse "stop" node
            place({ id: "B", type: "platform", isGlobalId: true }),
            place({ id: "C", type: "platform", isGlobalId: true }),
          ],
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    const leg = normalized.disruptionContext.legs[0]!;
    expect(leg.stopPatternPointGids).toEqual(["B", "C"]);
    expect(leg.stopSequenceComplete).toBe(false);
  });

  it("treats isGlobalId: false as unresolvable even when type is 'platform'", () => {
    const raw = {
      interchanges: 0,
      legs: [
        {
          origin: { ...place({ id: "A", type: "platform", isGlobalId: false }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "B", type: "platform", isGlobalId: true }), arrivalTimeEstimated: "2026-08-16T08:19:00Z" },
          transportation: { disassembledName: "11", product: { class: 2, name: "Tunnelbana" } },
          properties: { tripId: "trip-1" },
          infos: [],
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    const leg = normalized.disruptionContext.legs[0]!;
    expect(leg.boardingPatternPointGid).toBeUndefined();
    expect(leg.alightingPatternPointGid).toBe("B");
  });

  it("includes a WALK leg with transportMode WALK and lineDesignation null, resolving real station-to-station transfer endpoints", () => {
    const raw = {
      interchanges: 1,
      legs: [
        {
          origin: { ...place({ id: "A", type: "platform", isGlobalId: true }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "SLUSSEN-METRO", type: "platform", isGlobalId: true }), arrivalTimeEstimated: "2026-08-16T08:10:00Z" },
          transportation: { disassembledName: "19", product: { class: 2, name: "Tunnelbana" } },
          properties: { tripId: "trip-1" },
          infos: [],
        },
        {
          origin: { ...place({ id: "SLUSSEN-METRO", type: "platform", isGlobalId: true }), departureTimePlanned: "2026-08-16T08:10:00Z" },
          destination: { ...place({ id: "SLUSSEN-BUS", type: "platform", isGlobalId: true }), arrivalTimePlanned: "2026-08-16T08:12:00Z" },
          transportation: { product: { class: 99, name: "footpath" } },
          infos: [],
          duration: 120,
        },
        {
          origin: { ...place({ id: "SLUSSEN-BUS", type: "platform", isGlobalId: true }), departureTimeEstimated: "2026-08-16T08:15:00Z" },
          destination: { ...place({ id: "D", type: "platform", isGlobalId: true }), arrivalTimeEstimated: "2026-08-16T08:30:00Z" },
          transportation: { disassembledName: "471", product: { class: 5, name: "Buss" } },
          properties: { tripId: "trip-2" },
          infos: [],
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    const walkLegContext = normalized.disruptionContext.legs[1]!;
    expect(walkLegContext.transportMode).toBe("WALK");
    expect(walkLegContext.lineDesignation).toBeNull();
    expect(walkLegContext.boardingPatternPointGid).toBe("SLUSSEN-METRO");
    expect(walkLegContext.alightingPatternPointGid).toBe("SLUSSEN-BUS");
  });

  it("does not invent a transit station for a final WALK leg ending at a street/POI place", () => {
    const raw = {
      interchanges: 0,
      legs: [
        {
          origin: { ...place({ id: "A", type: "platform", isGlobalId: true }), departureTimeEstimated: "2026-08-16T08:00:00Z" },
          destination: { ...place({ id: "B", type: "platform", isGlobalId: true }), arrivalTimeEstimated: "2026-08-16T08:10:00Z" },
          transportation: { disassembledName: "11", product: { class: 2, name: "Tunnelbana" } },
          properties: { tripId: "trip-1" },
          infos: [],
        },
        {
          origin: { ...place({ id: "B", type: "platform", isGlobalId: true }), departureTimePlanned: "2026-08-16T08:10:00Z" },
          // A real street-address/POI destination Journey Planner returns with neither
          // type: "platform" nor isGlobalId: true.
          destination: { id: "poi:some-address", name: "123 Some Street", type: "singlehouse", arrivalTimePlanned: "2026-08-16T08:15:00Z" },
          transportation: { product: { class: 99, name: "footpath" } },
          infos: [],
          duration: 300,
        },
      ],
    };
    const normalized = normalizeJourney(raw as never)!;
    const finalWalkContext = normalized.disruptionContext.legs[1]!;
    expect(finalWalkContext.alightingPatternPointGid).toBeUndefined();
  });
});
