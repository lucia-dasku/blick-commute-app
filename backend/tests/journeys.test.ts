import { describe, expect, it } from "vitest";
import { Hono } from "hono";
import { createJourneyRoutes } from "../src/routes/journeys.js";
import { onError } from "../src/middleware/errorHandler.js";
import type { SlJourneyPlannerClient } from "../src/services/slJourneyPlannerClient.js";
import type { SuccessEnvelope } from "./testHelpers.js";

interface JourneyResponse {
  journeys: Array<{
    journeyId: string;
    transferCount: number;
    firstLeg: { lineDesignation: string | null };
    legs: Array<{ lineDesignation: string | null; disruptions: string[] }>;
    disruptions: string[];
  }>;
}

interface LocationSearchResponse {
  locations: Array<{ id: string; name: string }>;
}

function rawJourney(id: string, departure: string, arrival: string, interchanges = 0) {
  return {
    tripId: id,
    interchanges,
    legs: [{
      origin: { name: "T-Centralen", departureTimeEstimated: departure },
      destination: { name: "Mariatorget", arrivalTimeEstimated: arrival },
      transportation: {
        disassembledName: id === "fast" ? "14" : "135",
        product: { class: id === "fast" ? 2 : 5, name: id === "fast" ? "Tunnelbana" : "Buss" },
        destination: { name: "Mariatorget" },
      },
      infos: [],
    }],
  };
}

describe("journey routes", () => {
  it("ranks by final arrival rather than first departure", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() { return []; },
      async trips() {
        return [
          rawJourney("slow", "2026-08-10T08:02:00Z", "2026-08-10T08:31:00Z"),
          rawJourney("fast", "2026-08-10T08:04:00Z", "2026-08-10T08:23:00Z"),
        ];
      },
    };
    const response = await createJourneyRoutes(client).request("/?originId=origin&destinationId=destination");
    expect(response.status).toBe(200);
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey: { journeyId: string }) => journey.journeyId)).toEqual(["fast", "slow"]);
    expect(body.data.journeys[0]!.firstLeg.lineDesignation).toBe("14");
  });

  it("passes selected modes upstream and removes journeys using an unselected public mode", async () => {
    let receivedModes: readonly string[] | undefined;
    const client: SlJourneyPlannerClient = {
      async searchStops() { return []; },
      async trips(_originId, _destinationId, transportModes) {
        receivedModes = transportModes;
        return [
          rawJourney("slow", "2026-08-10T08:02:00Z", "2026-08-10T08:31:00Z"),
          rawJourney("fast", "2026-08-10T08:04:00Z", "2026-08-10T08:23:00Z"),
        ];
      },
    };

    const response = await createJourneyRoutes(client).request(
      "/?originId=origin&destinationId=destination&transportModes=METRO",
    );
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

    expect(response.status).toBe(200);
    expect(receivedModes).toEqual(["METRO"]);
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["fast"]);
  });

  it("uses one fallback search to find a genuine different-mode alternative", async () => {
    const receivedModes: string[][] = [];
    const client: SlJourneyPlannerClient = {
      async searchStops() { return []; },
      async trips(_originId, _destinationId, transportModes) {
        receivedModes.push([...(transportModes ?? [])]);
        if (transportModes?.length === 1 && transportModes[0] === "BUS") {
          return [rawJourney("bus-alternative", "2026-08-10T08:03:00Z", "2026-08-10T08:29:00Z")];
        }
        return [
          rawJourney("fast", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z"),
          rawJourney("fast", "2026-08-10T08:05:00Z", "2026-08-10T08:25:00Z"),
        ];
      },
    };

    const response = await createJourneyRoutes(client).request(
      "/?originId=origin&destinationId=destination&transportModes=METRO,BUS",
    );
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["fast", "bus-alternative"]);
    expect(receivedModes).toEqual([["METRO", "BUS"], ["BUS"]]);
  });

  it("rejects an empty or unknown transport selection", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() { return []; },
      async trips() { return []; },
    };

    const app = new Hono();
    app.onError(onError);
    app.route("/", createJourneyRoutes(client));

    expect((await app.request(
      "/?originId=origin&destinationId=destination&transportModes=TAXI",
    )).status).toBe(400);
    expect((await app.request(
      "/?originId=origin&destinationId=destination&transportModes=,",
    )).status).toBe(400);
  });

  it("uses Journey Planner location identifiers from stop search", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() { return [{ id: "9091001000009001", name: "Stockholm, T-Centralen", disassembledName: "T-Centralen", type: "stop" }]; },
      async trips() { return []; },
    };
    const response = await createJourneyRoutes(client).request("/locations/search?query=centralen");
    const body = (await response.json()) as SuccessEnvelope<LocationSearchResponse>;
    expect(body.data.locations[0]).toEqual({ id: "9091001000009001", name: "T-Centralen" });
  });

  it("normalizes complete transfer journeys and their disruption information", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() { return []; },
      async trips() {
        return [{
          tripId: "transfer",
          interchanges: 1,
          legs: [
            {
              origin: { name: "T-Centralen", departureTimeEstimated: "2026-08-10T08:04:00Z" },
              destination: { name: "Slussen", arrivalTimeEstimated: "2026-08-10T08:09:00Z" },
              transportation: {
                disassembledName: "14",
                product: { class: 2, name: "Tunnelbana" },
                destination: { name: "Norsborg" },
              },
              infos: [{ content: "Lift unavailable" }],
            },
            {
              origin: { name: "Slussen", departureTimePlanned: "2026-08-10T08:14:00Z" },
              destination: { name: "Nacka", arrivalTimePlanned: "2026-08-10T08:28:00Z" },
              transportation: {
                disassembledName: "409",
                product: { class: 5, name: "Buss" },
                destination: { name: "Nacka" },
              },
              infos: [],
            },
          ],
        }];
      },
    };

    const response = await createJourneyRoutes(client).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    const journey = body.data.journeys[0]!;

    expect(journey.transferCount).toBe(1);
    expect(journey.firstLeg.lineDesignation).toBe("14");
    expect(journey.legs.map((leg) => leg.lineDesignation)).toEqual(["14", "409"]);
    expect(journey.disruptions).toEqual(["Lift unavailable"]);
  });
});
