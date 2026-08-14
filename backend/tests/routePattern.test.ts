import { describe, expect, it } from "vitest";
import { buildRoutePattern, isRouteCompatible, type RoutePattern } from "../src/domain/routePattern.js";
import { normalizeJourney } from "../src/normalize/normalizeJourney.js";

function pattern(...legs: Array<{ mode: string; stops: string[] }>): RoutePattern {
  return { legs: legs.map((leg) => ({ transportMode: leg.mode, stopIds: leg.stops })) };
}

describe("isRouteCompatible", () => {
  it("same metro corridor, different line designations -- line number is not part of RoutePattern at all", () => {
    // RoutePattern deliberately carries no line designation -- see buildRoutePattern's own
    // doc -- so this is really just "same mode, same stop sequence" from two differently
    // labelled sources, proving the comparison never looks at the line number.
    const line14 = pattern({ mode: "METRO", stops: ["A", "B", "C", "D"] });
    const line19 = pattern({ mode: "METRO", stops: ["A", "B", "C", "D"] });
    expect(isRouteCompatible(line14, line19)).toBe(true);
  });

  it("local/express variation: an ordered subsequence with matching endpoints is compatible", () => {
    const local = pattern({ mode: "METRO", stops: ["A", "B", "C", "D"] });
    const express = pattern({ mode: "METRO", stops: ["A", "C", "D"] });
    expect(isRouteCompatible(local, express)).toBe(true);
    expect(isRouteCompatible(express, local)).toBe(true);
  });

  it("same origin/destination but a different bus/metro structure is not compatible", () => {
    const metro = pattern({ mode: "METRO", stops: ["A", "B", "C", "D"] });
    const bus = pattern({ mode: "BUS", stops: ["A", "D"] });
    expect(isRouteCompatible(metro, bus)).toBe(false);
  });

  it("platform ids differ but parent stop ids are the same -- normalizeJourney already canonicalizes to parent, so this is compatible", () => {
    // Integration across normalizeJourney -> buildRoutePattern: two raw journeys boarding
    // at DIFFERENT platforms that share the same parent stop-area.
    const journeyA = normalizeJourney({
      interchanges: 0,
      legs: [
        {
          origin: { id: "platform-1", name: "Slussen spår 1", type: "platform", parent: { id: "area-slussen", name: "Slussen", type: "stop" }, departureTimeEstimated: "2026-08-10T08:00:00Z" },
          destination: { id: "platform-3", name: "T-Centralen spår 3", type: "platform", parent: { id: "area-tcentralen", name: "T-Centralen", type: "stop" }, arrivalTimeEstimated: "2026-08-10T08:10:00Z" },
          transportation: { disassembledName: "14", product: { class: 2, name: "Tunnelbana" }, destination: { name: "Fruängen" } },
          properties: { tripId: "trip-a" },
          infos: [],
          stopSequence: [
            { id: "platform-1", name: "Slussen spår 1", type: "platform", parent: { id: "area-slussen", name: "Slussen", type: "stop" } },
            { id: "platform-3", name: "T-Centralen spår 3", type: "platform", parent: { id: "area-tcentralen", name: "T-Centralen", type: "stop" } },
          ],
        },
      ],
    } as never)!;

    const journeyB = normalizeJourney({
      interchanges: 0,
      legs: [
        {
          // A DIFFERENT platform at each end, but the SAME two parent stop-areas.
          origin: { id: "platform-2", name: "Slussen spår 2", type: "platform", parent: { id: "area-slussen", name: "Slussen", type: "stop" }, departureTimeEstimated: "2026-08-10T08:05:00Z" },
          destination: { id: "platform-4", name: "T-Centralen spår 4", type: "platform", parent: { id: "area-tcentralen", name: "T-Centralen", type: "stop" }, arrivalTimeEstimated: "2026-08-10T08:15:00Z" },
          transportation: { disassembledName: "19", product: { class: 2, name: "Tunnelbana" }, destination: { name: "Hässelby strand" } },
          properties: { tripId: "trip-b" },
          infos: [],
          stopSequence: [
            { id: "platform-2", name: "Slussen spår 2", type: "platform", parent: { id: "area-slussen", name: "Slussen", type: "stop" } },
            { id: "platform-4", name: "T-Centralen spår 4", type: "platform", parent: { id: "area-tcentralen", name: "T-Centralen", type: "stop" } },
          ],
        },
      ],
    } as never)!;

    expect(isRouteCompatible(buildRoutePattern(journeyA), buildRoutePattern(journeyB))).toBe(true);
  });

  it("a different number of legs is not compatible", () => {
    const direct = pattern({ mode: "METRO", stops: ["A", "D"] });
    const withChange = pattern({ mode: "METRO", stops: ["A", "B"] }, { mode: "BUS", stops: ["B", "D"] });
    expect(isRouteCompatible(direct, withChange)).toBe(false);
  });

  it("matching stop counts but a different boarding stop is not compatible, even with the same alighting stop and mode", () => {
    const a = pattern({ mode: "METRO", stops: ["A", "C", "D"] });
    const b = pattern({ mode: "METRO", stops: ["B", "C", "D"] });
    expect(isRouteCompatible(a, b)).toBe(false);
  });

  it("matching stop counts but a different alighting stop is not compatible", () => {
    const a = pattern({ mode: "METRO", stops: ["A", "B", "C"] });
    const b = pattern({ mode: "METRO", stops: ["A", "B", "D"] });
    expect(isRouteCompatible(a, b)).toBe(false);
  });

  it("an all-WALK pattern (no public-transport legs) never matches, including against itself", () => {
    const empty = pattern();
    expect(isRouteCompatible(empty, empty)).toBe(false);
  });

  it("a leg with no resolvable stopIds at all is never considered a match", () => {
    const withStops = pattern({ mode: "METRO", stops: ["A", "B"] });
    const withoutStops = pattern({ mode: "METRO", stops: [] });
    expect(isRouteCompatible(withStops, withoutStops)).toBe(false);
  });

  it("is NOT transitive: A compatible with B, and B compatible with C, does not imply A compatible with C", () => {
    // The product spec's own counterexample -- see isRouteCompatible's own doc.
    const patternA = pattern({ mode: "METRO", stops: ["S1", "S2", "S4"] });
    const patternB = pattern({ mode: "METRO", stops: ["S1", "S2", "S3", "S4"] });
    const patternC = pattern({ mode: "METRO", stops: ["S1", "S3", "S4"] });

    expect(isRouteCompatible(patternA, patternB)).toBe(true);
    expect(isRouteCompatible(patternB, patternC)).toBe(true);
    expect(isRouteCompatible(patternA, patternC)).toBe(false);
  });
});

describe("buildRoutePattern", () => {
  it("excludes WALK legs entirely", () => {
    const journey = {
      legs: [
        { transportMode: "METRO", stopIds: ["A", "B"] },
        { transportMode: "WALK", stopIds: [] },
        { transportMode: "BUS", stopIds: ["B", "C"] },
      ],
    };
    const built = buildRoutePattern(journey);
    expect(built.legs.map((leg) => leg.transportMode)).toEqual(["METRO", "BUS"]);
  });
});
