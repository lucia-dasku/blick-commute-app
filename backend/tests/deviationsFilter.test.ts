import { describe, expect, it } from "vitest";
import { matchesDeviationsQuery, resolveSiteStopAreaIds } from "../src/services/deviationsFilter.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";
import type { Site } from "../src/models/site.js";

const now = new Date("2026-07-28T08:00:00Z");

function deviation(overrides: {
  stopAreaIds?: number[];
  lineId?: number;
  transportMode?: string | null;
  from?: string | null;
  upto?: string | null;
}): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: 1,
    publish: { from: overrides.from ?? null, upto: overrides.upto ?? null },
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: "h", details: "d", language: "sv" }],
    scope: {
      stop_areas: overrides.stopAreaIds?.map((id) => ({ id, name: "Test", type: null })),
      lines:
        overrides.lineId != null || overrides.transportMode !== undefined
          ? [{ id: overrides.lineId ?? 1, designation: "1", transport_mode: overrides.transportMode ?? null, name: null }]
          : undefined,
    },
  };
}

function site(siteId: number, stopAreaIds: number[]): Site {
  return { siteId, name: "Test site", note: null, lat: null, lon: null, stopAreaIds };
}

describe("resolveSiteStopAreaIds", () => {
  it("includes the site's own ID plus every child stop-area ID", () => {
    const sites = [site(9192, [1011, 11002])];
    const result = resolveSiteStopAreaIds(9192, sites);
    expect(result).toEqual(new Set([9192, 1011, 11002]));
  });

  it("falls back to just {siteId} when the site is not found in the directory", () => {
    const result = resolveSiteStopAreaIds(9192, []);
    expect(result).toEqual(new Set([9192]));
  });
});

describe("matchesDeviationsQuery — siteId", () => {
  it("matches a deviation scoped to one of the site's own child stop areas", () => {
    const d = deviation({ stopAreaIds: [1011] });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, new Set([9192, 1011]), now)).toBe(true);
  });

  it("matches a deviation scoped directly to the site's own ID", () => {
    const d = deviation({ stopAreaIds: [9192] });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, new Set([9192]), now)).toBe(true);
  });

  it("does not match a deviation scoped to an unrelated stop area", () => {
    const d = deviation({ stopAreaIds: [44000] });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, new Set([9192, 1011]), now)).toBe(false);
  });

  it("does not match a deviation with no scope.stop_areas at all", () => {
    const d = deviation({});
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, new Set([9192, 1011]), now)).toBe(false);
  });
});

describe("matchesDeviationsQuery — lineId", () => {
  const siteStopAreaIds = new Set([9192]);

  it("is not applied when the query omits lineId", () => {
    const d = deviation({ stopAreaIds: [9192], lineId: 17 });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("matches when scope.lines contains the requested lineId", () => {
    const d = deviation({ stopAreaIds: [9192], lineId: 17 });
    expect(matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("does not match when scope.lines lacks the requested lineId", () => {
    const d = deviation({ stopAreaIds: [9192], lineId: 18 });
    expect(matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, future: false }, siteStopAreaIds, now)).toBe(false);
  });
});

describe("matchesDeviationsQuery — transportMode", () => {
  const siteStopAreaIds = new Set([9192]);

  it("is not applied when the query omits transportMode", () => {
    const d = deviation({ stopAreaIds: [9192], transportMode: "METRO" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("matches when scope.lines contains the requested mode", () => {
    const d = deviation({ stopAreaIds: [9192], transportMode: "METRO" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, transportMode: "METRO", future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("does not match when scope.lines has a different mode", () => {
    const d = deviation({ stopAreaIds: [9192], transportMode: "BUS" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, transportMode: "METRO", future: false }, siteStopAreaIds, now)).toBe(false);
  });
});

describe("matchesDeviationsQuery — validity period and 'future'", () => {
  const siteStopAreaIds = new Set([9192]);

  it("matches a currently-valid deviation (from in the past, upto in the future)", () => {
    const d = deviation({ stopAreaIds: [9192], from: "2026-07-01T00:00:00+02:00", upto: "2026-08-01T00:00:00+02:00" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("matches a deviation with no publish window at all (always valid)", () => {
    const d = deviation({ stopAreaIds: [9192] });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("always excludes an EXPIRED deviation, regardless of future", () => {
    const d = deviation({ stopAreaIds: [9192], upto: "2026-07-01T00:00:00+02:00" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(false);
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: true }, siteStopAreaIds, now)).toBe(false);
  });

  it("excludes a NOT-YET-STARTED deviation when future=false", () => {
    const d = deviation({ stopAreaIds: [9192], from: "2026-08-01T00:00:00+02:00" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(false);
  });

  it("includes a NOT-YET-STARTED deviation when future=true", () => {
    const d = deviation({ stopAreaIds: [9192], from: "2026-08-01T00:00:00+02:00" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: true }, siteStopAreaIds, now)).toBe(true);
  });

  it("a deviation exactly at its upto instant is treated as still valid, not yet expired", () => {
    const d = deviation({ stopAreaIds: [9192], upto: now.toISOString() });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(true);
  });

  it("a deviation exactly at its from instant is treated as already started", () => {
    const d = deviation({ stopAreaIds: [9192], from: now.toISOString() });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(true);
  });
});

describe("matchesDeviationsQuery — filters combine as AND", () => {
  it("requires siteId, lineId, transportMode and validity to all match at once", () => {
    const siteStopAreaIds = new Set([9192, 1011]);
    const d = deviation({
      stopAreaIds: [1011],
      lineId: 17,
      transportMode: "METRO",
      from: "2026-07-01T00:00:00+02:00",
      upto: "2026-08-01T00:00:00+02:00",
    });
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, transportMode: "METRO", future: false }, siteStopAreaIds, now),
    ).toBe(true);
    // Wrong lineId alone is enough to exclude it, even though everything else matches.
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, lineId: 18, transportMode: "METRO", future: false }, siteStopAreaIds, now),
    ).toBe(false);
  });
});
