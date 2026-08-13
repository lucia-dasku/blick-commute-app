import { afterEach, describe, expect, it, vi } from "vitest";
import directFixture from "../fixtures/slJourneyPlannerDirect.sample.json" with { type: "json" };
import transferFixture from "../fixtures/slJourneyPlannerTransfer.sample.json" with { type: "json" };
import { createJourneyRoutes } from "../src/routes/journeys.js";
import { createSlJourneyPlannerClient } from "../src/services/slJourneyPlannerClient.js";
import type { SuccessEnvelope } from "./testHelpers.js";

interface NormalizedJourneyResponse {
  journeys: Array<{
    journeyId: string;
    transferCount: number;
    arrivalTime: string;
    firstLeg: { lineDesignation: string | null; transportMode: string };
    legs: Array<{ transportMode: string; lineDesignation: string | null }>;
    disruptions: string[];
  }>;
}

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

/** Fixed "now" for every test below — before both fixtures' departure times (16:21:42Z and
 * 16:29:24Z) — so these tests stay deterministic regardless of the real wall clock (see
 * createJourneyRoutes's own injectable `now` parameter). */
const FIXED_NOW = () => new Date("2026-08-10T16:00:00Z");

describe("SL Journey Planner live-response contract", () => {
  it("accepts the sanitized direct fixture and does not mislabel the next same-mode trip as an alternative", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const routes = createJourneyRoutes(createSlJourneyPlannerClient("https://journey-planner.fixture/v2"), FIXED_NOW);

    const response = await routes.request("/?originId=origin-global-id&destinationId=destination-global-id");

    expect(response.status).toBe(200);
    const body = (await response.json()) as SuccessEnvelope<NormalizedJourneyResponse>;
    expect(body.data.journeys).toHaveLength(1);
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual([
      "9015001001420696",
    ]);
    expect(body.data.journeys.map((journey) => journey.arrivalTime)).toEqual([
      "2026-08-10T16:22:24Z",
    ]);
    expect(body.data.journeys[0]!.firstLeg).toMatchObject({ transportMode: "METRO", lineDesignation: "14" });

    const requestedUrl = new URL(fetchMock.mock.calls[0]?.[0] as string);
    expect(requestedUrl.pathname).toBe("/v2/trips");
    expect(requestedUrl.searchParams.get("name_origin")).toBe("origin-global-id");
    expect(requestedUrl.searchParams.get("name_destination")).toBe("destination-global-id");
    expect(requestedUrl.searchParams.get("calc_number_of_trips")).toBe("3");
    // The two parameters this fix adds — see slJourneyPlannerClient.ts's own doc on why each
    // one exists.
    expect(requestedUrl.searchParams.get("calc_one_direction")).toBe("true");
    expect(requestedUrl.searchParams.get("max_changes")).toBe("2");
    expect(requestedUrl.searchParams.get("incl_mot_2")).toBe("true");
    expect(requestedUrl.searchParams.get("incl_mot_5")).toBe("true");
  });

  it("maps the selected app modes to SL inclusion parameters", async () => {
    const fetchMock = stubJourneyPlannerResponse(directFixture);
    const routes = createJourneyRoutes(createSlJourneyPlannerClient("https://journey-planner.fixture/v2"), FIXED_NOW);

    const response = await routes.request(
      "/?originId=origin-global-id&destinationId=destination-global-id&transportModes=METRO,TRAIN",
    );

    expect(response.status).toBe(200);
    const requestedUrl = new URL(fetchMock.mock.calls[0]?.[0] as string);
    expect(requestedUrl.searchParams.get("incl_mot_0")).toBe("true");
    expect(requestedUrl.searchParams.get("incl_mot_2")).toBe("true");
    expect(requestedUrl.searchParams.get("incl_mot_4")).toBe("false");
    expect(requestedUrl.searchParams.get("incl_mot_5")).toBe("false");
    expect(requestedUrl.searchParams.get("incl_mot_9")).toBe("false");
    expect(requestedUrl.searchParams.get("incl_mot_14")).toBe("true");
  });

  it("accepts the sanitized transfer fixture, composes a stable leg-trip ID, and recognizes footpaths", async () => {
    stubJourneyPlannerResponse(transferFixture);
    const routes = createJourneyRoutes(createSlJourneyPlannerClient("https://journey-planner.fixture/v2"), FIXED_NOW);

    const response = await routes.request("/?originId=origin-global-id&destinationId=destination-global-id");

    expect(response.status).toBe(200);
    const body = (await response.json()) as SuccessEnvelope<NormalizedJourneyResponse>;
    expect(body.data.journeys).toHaveLength(1);
    expect(body.data.journeys[0]).toMatchObject({
      journeyId: "9015001001420708:9015001002808030",
      transferCount: 1,
      firstLeg: { transportMode: "METRO", lineDesignation: "14" },
      disruptions: ["Sanitized fixture disruption"],
    });
    expect(body.data.journeys[0]!.legs.map((leg) => leg.transportMode)).toEqual(["METRO", "WALK", "TRAM"]);
    expect(body.data.journeys[0]!.legs.map((leg) => leg.lineDesignation)).toEqual(["14", null, "28"]);
  });
});
