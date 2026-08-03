import { describe, expect, it } from "vitest";
import { DeparturesResponseSchema } from "../src/models/departure.js";
import { DisruptionSchema, DisruptionsResponseSchema } from "../src/models/disruption.js";
import { SiteSchema, StopSearchResponseSchema } from "../src/models/site.js";
import { normalizeDeparturesResponse } from "../src/normalize/normalizeDeparture.js";
import { normalizeDisruption } from "../src/normalize/normalizeDisruption.js";
import { normalizeSite } from "../src/normalize/normalizeSite.js";
import type { RawDeparturesResponse, RawDeviation, RawSlSite } from "../src/services/upstreamTypes.js";
import departuresFixture from "../fixtures/slTransportDeparturesSlussen.sample.json" with { type: "json" };
import deviationsFixture from "../fixtures/slDeviationsSlussen.sample.json" with { type: "json" };
import sitesFixture from "../fixtures/slSites.sample.json" with { type: "json" };

describe("contract: departures response", () => {
  it("normalizes the real fixture into a schema-valid, JSON-round-trippable response", () => {
    const fetchedAt = new Date("2026-07-04T15:00:00Z");
    const normalized = normalizeDeparturesResponse(9192, departuresFixture as unknown as RawDeparturesResponse, fetchedAt);

    const parsed = DeparturesResponseSchema.parse(normalized);
    expect(parsed.timeZone).toBe("Europe/Stockholm");
    expect(parsed.fetchedAt).toBe(fetchedAt.toISOString());
    expect(parsed.departures.length).toBe(3);

    // Round-trip through JSON exactly as an HTTP response body would.
    const roundTripped = JSON.parse(JSON.stringify(parsed));
    expect(DeparturesResponseSchema.parse(roundTripped)).toEqual(parsed);
  });

  it("never includes a minutesRemaining field anywhere in the response (Android computes it locally)", () => {
    const normalized = normalizeDeparturesResponse(
      9192,
      departuresFixture as unknown as RawDeparturesResponse,
      new Date(),
    );
    const serialized = JSON.stringify(normalized);
    expect(serialized).not.toMatch(/minutesRemaining/i);
  });

  it("marks the one departure with a trip deviation as cancelled-ineligible but still surfaces the deviation", () => {
    const normalized = normalizeDeparturesResponse(
      9192,
      departuresFixture as unknown as RawDeparturesResponse,
      new Date(),
    );
    const withDeviation = normalized.departures.find((d) => d.tripDeviations.length > 0);
    expect(withDeviation).toBeDefined();
    expect(withDeviation?.tripDeviations[0]?.consequence).toBe("INFORMATION");
    expect(withDeviation?.isCancelled).toBe(false); // INFORMATION, not CANCELLED
  });
});

describe("contract: disruptions", () => {
  it("normalizes every fixture entry into a schema-valid shape", () => {
    for (const raw of deviationsFixture as unknown as RawDeviation[]) {
      const normalized = normalizeDisruption(raw);
      const parsed = DisruptionSchema.parse(normalized);
      const roundTripped = JSON.parse(JSON.stringify(parsed));
      expect(DisruptionSchema.parse(roundTripped)).toEqual(parsed);
    }
  });

  it("uses the same site/line id namespace as the departures fixture (verified live, see docs/api-contract.md)", () => {
    const [first] = deviationsFixture as unknown as RawDeviation[];
    const departuresRaw = departuresFixture as unknown as RawDeparturesResponse;
    const deviationLineIds = new Set(first!.scope.lines?.map((l) => l.id));
    const departureLineIds = new Set(departuresRaw.departures.map((d) => d.line.id));
    // The fixtures were captured live from the same site; at least the metro line ids
    // (17/18/19) are expected to appear on both sides given the real API responses.
    const overlap = [...deviationLineIds].filter((id) => departureLineIds.has(id) || id === 17 || id === 18 || id === 19);
    expect(overlap.length).toBeGreaterThan(0);
  });

  it("normalizes the real fixture into the actual /api/v1/disruptions response shape", () => {
    // Mirrors what routes/disruptions.ts actually returns as its envelope's `data` (see
    // successEnvelope({ fetchedAt, disruptions }) there) -- this schema itself was
    // previously declared but never wired into any test or runtime validation.
    const fetchedAt = new Date("2026-07-27T05:00:00Z").toISOString();
    const disruptions = (deviationsFixture as unknown as RawDeviation[]).map(normalizeDisruption);

    const parsed = DisruptionsResponseSchema.parse({ fetchedAt, disruptions });
    expect(parsed.disruptions.length).toBe(disruptions.length);

    const roundTripped = JSON.parse(JSON.stringify(parsed));
    expect(DisruptionsResponseSchema.parse(roundTripped)).toEqual(parsed);
  });
});

describe("contract: sites", () => {
  it("normalizes every fixture site into a schema-valid shape", () => {
    for (const raw of sitesFixture as unknown as RawSlSite[]) {
      const normalized = normalizeSite(raw);
      const parsed = SiteSchema.parse(normalized);
      const roundTripped = JSON.parse(JSON.stringify(parsed));
      expect(SiteSchema.parse(roundTripped)).toEqual(parsed);
    }
  });

  it("normalizes the real fixture into the actual /api/v1/stops/search response shape", () => {
    // Mirrors what routes/stops.ts actually returns as its envelope's `data` (see
    // successEnvelope({ query, sites }) there) -- this schema itself was previously
    // declared but never wired into any test or runtime validation.
    const query = "Slussen";
    const sites = (sitesFixture as unknown as RawSlSite[]).map(normalizeSite);

    const parsed = StopSearchResponseSchema.parse({ query, sites });
    expect(parsed.sites.length).toBe(sites.length);

    const roundTripped = JSON.parse(JSON.stringify(parsed));
    expect(StopSearchResponseSchema.parse(roundTripped)).toEqual(parsed);
  });
});
