import { afterEach, describe, expect, it, vi } from "vitest";
import directFixture from "../fixtures/slJourneyPlannerDirect.sample.json" with { type: "json" };
import transferFixture from "../fixtures/slJourneyPlannerTransfer.sample.json" with { type: "json" };
import { createSlJourneyPlannerClient } from "../src/services/slJourneyPlannerClient.js";

/**
 * Pure client-level tests: request construction (URL/params) and raw response parsing
 * only — never through `createJourneyRoutes`. Route-level PRIMARY/NEXT/ALTERNATIVE
 * selection behavior belongs to journeys.test.ts; this file only proves the client talks
 * to SL Journey Planner v2 the way its own OpenAPI spec (trafiklab.se/openapi/
 * sl-journey-planner.json) requires.
 */

function stubJourneyPlannerResponse(body: unknown) {
  const response = {
    status: 200,
    ok: true,
    headers: new Headers(),
    json: async () => body,
  } as unknown as Response;
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

/** 2026-08-10T16:00:00Z is 18:00 in Europe/Stockholm (CEST, UTC+2 in August). */
const ANCHOR = new Date("2026-08-10T16:00:00Z");

describe("SL Journey Planner client: request construction", () => {
  it("builds the /trips request with the documented parameters", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    await client.trips({ originId: "origin-global-id", destinationId: "destination-global-id", maxChanges: 2, departureAt: ANCHOR });

    const requestedUrl = new URL(fetchMock.mock.calls[0]?.[0] as string);
    expect(requestedUrl.pathname).toBe("/v2/trips");
    expect(requestedUrl.searchParams.get("name_origin")).toBe("origin-global-id");
    expect(requestedUrl.searchParams.get("name_destination")).toBe("destination-global-id");
    expect(requestedUrl.searchParams.get("calc_number_of_trips")).toBe("3");
    expect(requestedUrl.searchParams.get("calc_one_direction")).toBe("true");
    expect(requestedUrl.searchParams.get("max_changes")).toBe("2");
    expect(requestedUrl.searchParams.get("route_type")).toBe("leasttime");
    expect(requestedUrl.searchParams.get("itd_date")).toBe("20260810");
    expect(requestedUrl.searchParams.get("itd_time")).toBe("1800");
    expect(requestedUrl.searchParams.get("itd_trip_date_time_dep_arr")).toBe("dep");
  });

  it("forwards the requested maxChanges verbatim, per call", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    await client.trips({ originId: "o", destinationId: "d", maxChanges: 0, departureAt: ANCHOR });
    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR });

    const maxChangesRequested = fetchMock.mock.calls.map((call) => new URL(call[0] as string).searchParams.get("max_changes"));
    expect(maxChangesRequested).toEqual(["0", "2"]);
  });

  it("serializes departureAt against Europe/Stockholm wall-clock time, never the server process's own timezone", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    // Winter (CET, UTC+1): 2026-01-15T10:00:00Z is 11:00 in Stockholm.
    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: new Date("2026-01-15T10:00:00Z") });

    const requestedUrl = new URL(fetchMock.mock.calls[0]?.[0] as string);
    expect(requestedUrl.searchParams.get("itd_date")).toBe("20260115");
    expect(requestedUrl.searchParams.get("itd_time")).toBe("1100");
  });

  it("maps the selected app modes to SL inclusion parameters", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR, transportModes: ["METRO", "TRAIN"] });

    const requestedUrl = new URL(fetchMock.mock.calls[0]?.[0] as string);
    expect(requestedUrl.searchParams.get("incl_mot_0")).toBe("true");
    expect(requestedUrl.searchParams.get("incl_mot_2")).toBe("true");
    expect(requestedUrl.searchParams.get("incl_mot_4")).toBe("false");
    expect(requestedUrl.searchParams.get("incl_mot_5")).toBe("false");
    expect(requestedUrl.searchParams.get("incl_mot_9")).toBe("false");
    expect(requestedUrl.searchParams.get("incl_mot_14")).toBe("true");
  });

  it("defaults to every regular transport mode when transportModes is omitted", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR });

    const requestedUrl = new URL(fetchMock.mock.calls[0]?.[0] as string);
    expect(requestedUrl.searchParams.get("incl_mot_2")).toBe("true");
    expect(requestedUrl.searchParams.get("incl_mot_5")).toBe("true");
  });

  it("sends name_via/type_via only when a via stop is requested", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR });
    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR, viaStopId: "via-stop-id" });

    const [withoutVia, withVia] = fetchMock.mock.calls.map((call) => new URL(call[0] as string));
    expect(withoutVia!.searchParams.has("name_via")).toBe(false);
    expect(withVia!.searchParams.get("type_via")).toBe("any");
    expect(withVia!.searchParams.get("name_via")).toBe("via-stop-id");
  });

  it("respects an explicit routeType override", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR, routeType: "leastinterchange" });

    expect(new URL(fetchMock.mock.calls[0]?.[0] as string).searchParams.get("route_type")).toBe("leastinterchange");
  });
});

describe("SL Journey Planner client: raw response parsing", () => {
  it("accepts the sanitized direct fixture and returns its journeys", async () => {
    stubJourneyPlannerResponse(directFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    const journeys = await client.trips({ originId: "o", destinationId: "d", maxChanges: 0, departureAt: ANCHOR });

    expect(journeys).toHaveLength(3);
    expect(journeys.map((j) => j.legs[0]!.properties?.tripId)).toEqual([
      "9015001001420696",
      "9015001001320697",
      "9015001001420698",
    ]);
  });

  it("accepts the sanitized transfer fixture, including its footpath leg", async () => {
    stubJourneyPlannerResponse(transferFixture);
    const client = createSlJourneyPlannerClient("https://journey-planner.fixture/v2");

    const journeys = await client.trips({ originId: "o", destinationId: "d", maxChanges: 2, departureAt: ANCHOR });

    expect(journeys).toHaveLength(1);
    expect(journeys[0]!.interchanges).toBe(1);
    expect(journeys[0]!.legs).toHaveLength(3);
    expect(journeys[0]!.legs[1]!.transportation?.product?.class).toBe(99);
  });
});
