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

  it("does not match a deviation with no scope.stop_areas at all when the query has no line/mode to fall back on", () => {
    const d = deviation({});
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, new Set([9192, 1011]), now)).toBe(false);
  });

  it("a deviation scoped to an unrelated station is still excluded even if its line/mode also matches the query", () => {
    // Regression guard: having scope.stop_areas at all must always take the siteId
    // path, never fall through to the line-only bypass just because scope.lines also
    // happens to match -- otherwise "unrelated station" exclusion would break for any
    // deviation that also carries line info.
    const d = deviation({ stopAreaIds: [44000], lineId: 17, transportMode: "METRO" });
    expect(
      matchesDeviationsQuery(
        d,
        { siteId: 9192, lineId: 17, transportMode: "METRO", future: false },
        new Set([9192, 1011]),
        now,
      ),
    ).toBe(false);
  });
});

describe("matchesDeviationsQuery — line-only deviations (no scope.stop_areas)", () => {
  const siteStopAreaIds = new Set([9192, 1011]);

  it("is included when the requested lineId and transportMode both match", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, transportMode: "METRO", future: false }, siteStopAreaIds, now),
    ).toBe(true);
  });

  it("stays excluded when only lineId is requested (no transportMode)", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, future: false }, siteStopAreaIds, now)).toBe(false);
  });

  it("stays excluded when only transportMode is requested (no lineId)", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, transportMode: "METRO", future: false }, siteStopAreaIds, now),
    ).toBe(false);
  });

  it("stays excluded when neither lineId nor transportMode is requested", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    expect(matchesDeviationsQuery(d, { siteId: 9192, future: false }, siteStopAreaIds, now)).toBe(false);
  });

  it("is excluded when lineId matches but transportMode does not", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, transportMode: "BUS", future: false }, siteStopAreaIds, now),
    ).toBe(false);
  });

  it("is excluded when transportMode matches but lineId does not", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, lineId: 18, transportMode: "METRO", future: false }, siteStopAreaIds, now),
    ).toBe(false);
  });

  it("matches regardless of siteId, since a line-only deviation has no station to compare siteId against", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO" });
    const unrelatedSiteStopAreaIds = new Set([1]);
    expect(
      matchesDeviationsQuery(
        d,
        { siteId: 999999, lineId: 17, transportMode: "METRO", future: false },
        unrelatedSiteStopAreaIds,
        now,
      ),
    ).toBe(true);
  });

  it("still applies the validity/future window to a matched line-only deviation", () => {
    const d = deviation({ lineId: 17, transportMode: "METRO", upto: "2026-07-01T00:00:00+02:00" });
    expect(
      matchesDeviationsQuery(d, { siteId: 9192, lineId: 17, transportMode: "METRO", future: false }, siteStopAreaIds, now),
    ).toBe(false);
  });
});

describe("matchesDeviationsQuery — station-specific deviations (regression, unaffected by the line-only fallback)", () => {
  const siteStopAreaIds = new Set([9192, 1011]);

  it("still requires a siteId match even when lineId/transportMode are also requested and match", () => {
    const stationDeviation = deviation({ stopAreaIds: [1011], lineId: 17, transportMode: "METRO" });
    expect(
      matchesDeviationsQuery(
        stationDeviation,
        { siteId: 9192, lineId: 17, transportMode: "METRO", future: false },
        siteStopAreaIds,
        now,
      ),
    ).toBe(true);
  });

  it("is unaffected by an unrelated site's stop-area IDs, regardless of any line/mode overlap", () => {
    const stationDeviation = deviation({ stopAreaIds: [1011], lineId: 17, transportMode: "METRO" });
    const otherSiteStopAreaIds = new Set([9193]);
    expect(
      matchesDeviationsQuery(
        stationDeviation,
        { siteId: 9193, lineId: 17, transportMode: "METRO", future: false },
        otherSiteStopAreaIds,
        now,
      ),
    ).toBe(false);
  });
});

describe("matchesDeviationsQuery — combined station and line-only deviations in the same query", () => {
  it("selects the station-specific match by siteId and the line-only match by lineId/transportMode, and drops the rest", () => {
    const siteStopAreaIds = new Set([9192, 1011]);
    const query = { siteId: 9192, lineId: 17, transportMode: "METRO", future: false } as const;

    const stationMatch = deviation({ stopAreaIds: [1011], lineId: 17, transportMode: "METRO" });
    const lineOnlyMatch = deviation({ lineId: 17, transportMode: "METRO" });
    const unrelatedStation = deviation({ stopAreaIds: [44000], lineId: 17, transportMode: "METRO" });
    const lineOnlyNonMatch = deviation({ lineId: 18, transportMode: "METRO" });

    const results = [stationMatch, lineOnlyMatch, unrelatedStation, lineOnlyNonMatch].map((d) =>
      matchesDeviationsQuery(d, query, siteStopAreaIds, now),
    );

    expect(results).toEqual([true, true, false, false]);
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
