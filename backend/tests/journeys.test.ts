import { describe, expect, it } from "vitest";
import { Hono } from "hono";
import { createJourneyRoutes, type JourneyAcquisitionMetrics } from "../src/routes/journeys.js";
import { onError } from "../src/middleware/errorHandler.js";
import { MAX_ACQUISITION_BATCHES, MAX_CHANGES } from "../src/services/candidateCollector.js";
import type { RawJourneyPlannerJourney, SlJourneyPlannerClient, TripsRequest } from "../src/services/slJourneyPlannerClient.js";
import type { SuccessEnvelope } from "./testHelpers.js";

/** Captures every metrics event `createJourneyRoutes` emits, for tests that need to assert
 * on acquisition behaviour (retargets, call-phase breakdown, budget exhaustion) that is not
 * otherwise observable from the public response shape -- see this route's own
 * `JourneyAcquisitionMetrics` doc. */
function metricsSpy(): { emit: (metrics: JourneyAcquisitionMetrics) => void; events: JourneyAcquisitionMetrics[] } {
  const events: JourneyAcquisitionMetrics[] = [];
  return { emit: (metrics) => events.push(metrics), events };
}

interface JourneyResponse {
  fetchedAt: string;
  journeyContext: "LIVE" | "PLANNED";
  searchMode: "NOW" | "LEAVE_AT" | "ARRIVE_BY";
  requestedDateTime: string | null;
  journeys: Array<{
    journeyId: string;
    role: string;
    departureTime: string;
    arrivalTime: string;
    transferCount: number;
    firstLeg: { lineDesignation: string | null };
    legs: Array<{ lineDesignation: string | null; disruptions: string[] }>;
    disruptions: string[];
    disruptionNotices: Array<{ text: string; effect: string }>;
  }>;
}

interface LocationSearchResponse {
  locations: Array<{ id: string; name: string }>;
}

/** Fixed "now" for every test below that uses the 2026-08-10 mock timestamps — deliberately
 * before all of them, so these tests stay deterministic regardless of the real wall clock (see
 * createJourneyRoutes's own injectable `now` parameter). */
const FIXED_NOW = () => new Date("2026-08-10T07:00:00Z");

/**
 * Every journey shares the SAME origin/destination stop ids by default ("origin-stop" /
 * "destination-stop") -- this is what makes two same-mode journeys built by this helper
 * resolve to the same RoutePattern (see backend/src/domain/routePattern.ts), and two
 * different-mode ones resolve to different families, without every test having to spell
 * out realistic `stopSequence` data of its own. [mode] defaults to the same "fast" =>
 * metro, everything else => bus rule every existing call site already relies on.
 */
function rawJourney(
  id: string,
  departure: string,
  arrival: string,
  interchanges = 0,
  mode: "metro" | "bus" = id === "fast" ? "metro" : "bus",
): RawJourneyPlannerJourney {
  return {
    tripId: id,
    interchanges,
    legs: [
      {
        origin: { id: "origin-stop", name: "T-Centralen", departureTimeEstimated: departure },
        destination: { id: "destination-stop", name: "Mariatorget", arrivalTimeEstimated: arrival },
        transportation: {
          disassembledName: mode === "metro" ? "14" : "135",
          product: { class: mode === "metro" ? 2 : 5, name: mode === "metro" ? "Tunnelbana" : "Buss" },
          destination: { name: "Mariatorget" },
        },
        infos: [],
      },
    ],
  } as unknown as RawJourneyPlannerJourney;
}

/** Serves an exact, hand-scripted sequence of raw-journey batches, one per call — used only
 * where a test needs precise control over what EACH numbered acquisition call returns (see
 * the "reclassification" test below); a call past the end of the script returns an empty
 * array. Every other test uses `worldClient` instead. */
function scriptedClient(batches: RawJourneyPlannerJourney[][]): { client: SlJourneyPlannerClient; requests: TripsRequest[] } {
  const requests: TripsRequest[] = [];
  let callIndex = 0;
  const client: SlJourneyPlannerClient = {
    async searchStops() {
      return [];
    },
    async trips(request) {
      requests.push(request);
      const batch = batches[callIndex] ?? [];
      callIndex++;
      return batch;
    },
  };
  return { client, requests };
}

interface WorldJourney {
  id: string;
  departure: string;
  arrival: string;
  interchanges?: number;
  mode?: "metro" | "bus";
}

/** A small fake SL that answers every request the way real SL is documented to: the
 * (up to) 3 candidates in [journeys] whose departure is at/after the requested
 * `departureAt`, matching the requested `transportModes`/`maxChanges`, closest-departure
 * first — mirroring `calc_number_of_trips=3` and `calc_one_direction=true`. Records every
 * request it received for assertions. */
function worldClient(journeys: WorldJourney[]): { client: SlJourneyPlannerClient; requests: TripsRequest[] } {
  const requests: TripsRequest[] = [];
  const client: SlJourneyPlannerClient = {
    async searchStops() {
      return [];
    },
    async trips(request) {
      requests.push(request);
      const allowedModes = request.transportModes != null ? new Set<string>(request.transportModes) : null;
      const matches = journeys
        .filter((j) =>
          request.dateTimeMode === "ARRIVAL"
            ? Date.parse(j.arrival) <= request.departureAt.getTime()
            : Date.parse(j.departure) >= request.departureAt.getTime(),
        )
        .filter((j) => (j.interchanges ?? 0) <= request.maxChanges)
        .filter((j) => allowedModes == null || allowedModes.has((j.mode ?? "metro") === "metro" ? "METRO" : "BUS"))
        .sort((a, b) =>
          request.dateTimeMode === "ARRIVAL"
            ? Date.parse(b.arrival) - Date.parse(a.arrival)
            : Date.parse(a.departure) - Date.parse(b.departure),
        )
        .slice(0, 3);
      return matches.map((j) => rawJourney(j.id, j.departure, j.arrival, j.interchanges ?? 0, j.mode ?? "metro"));
    },
  };
  return { client, requests };
}

describe("journey routes", () => {
  describe("planned-time contract", () => {
    it("keeps an omitted search mode backward-compatible as a LIVE NOW request", async () => {
      const { client, requests } = worldClient([
        { id: "now", departure: "2026-08-10T07:05:00Z", arrival: "2026-08-10T07:20:00Z", mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeyContext).toBe("LIVE");
      expect(body.data.searchMode).toBe("NOW");
      expect(body.data.requestedDateTime).toBeNull();
      expect(requests[0]!.dateTimeMode).toBe("DEPARTURE");
      expect(requests[0]!.departureAt.toISOString()).toBe(FIXED_NOW().toISOString());
    });

    it("issues one future departure search for LEAVE_AT and preserves planned role selection", async () => {
      const { client, requests } = worldClient([
        { id: "too-early", departure: "2026-08-10T09:59:00Z", arrival: "2026-08-10T10:15:00Z", mode: "metro" },
        { id: "primary", departure: "2026-08-10T10:00:00Z", arrival: "2026-08-10T10:20:00Z", mode: "metro" },
        { id: "alternative", departure: "2026-08-10T10:05:00Z", arrival: "2026-08-10T10:25:00Z", mode: "bus" },
        { id: "next", departure: "2026-08-10T10:10:00Z", arrival: "2026-08-10T10:30:00Z", mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchMode=LEAVE_AT&requestedDateTime=2026-08-10T12:00:00%2B02:00",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(response.status).toBe(200);
      expect(body.data.journeyContext).toBe("PLANNED");
      expect(body.data.searchMode).toBe("LEAVE_AT");
      expect(body.data.requestedDateTime).toBe("2026-08-10T10:00:00.000Z");
      expect(requests).toHaveLength(1);
      expect(requests[0]!.dateTimeMode).toBe("DEPARTURE");
      expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T10:00:00.000Z");
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "alternative", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
    });

    it("uses one structural arrival search and makes the latest equal-quality deadline-safe proposal PRIMARY", async () => {
      const { client, requests } = scriptedClient([
        [
          rawJourney("arrive-1808", "2026-08-10T15:57:42Z", "2026-08-10T16:08:06Z", 0, "metro"),
          rawJourney("arrive-1816", "2026-08-10T16:05:42Z", "2026-08-10T16:16:06Z", 0, "metro"),
          rawJourney("arrive-1823", "2026-08-10T16:13:12Z", "2026-08-10T16:23:36Z", 0, "metro"),
          rawJourney("after-deadline", "2026-08-10T16:20:00Z", "2026-08-10T16:31:00Z", 0, "metro"),
        ],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchMode=ARRIVE_BY&requestedDateTime=2026-08-10T18:30:00%2B02:00",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(response.status).toBe(200);
      expect(body.data.journeyContext).toBe("PLANNED");
      expect(body.data.searchMode).toBe("ARRIVE_BY");
      expect(requests).toHaveLength(1);
      expect(requests[0]!.dateTimeMode).toBe("ARRIVAL");
      expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T16:30:00.000Z");
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["arrive-1823"]);
      expect(body.data.journeys[0]).toMatchObject({
        role: "PRIMARY",
        departureTime: "2026-08-10T16:13:12Z",
        arrivalTime: "2026-08-10T16:23:36Z",
      });
      expect(body.data.journeys.some((journey) => journey.journeyId === "after-deadline")).toBe(false);
    });

    it("validates mode and timestamp combinations without calling SL", async () => {
      const { client, requests } = scriptedClient([]);
      const app = new Hono();
      app.onError(onError);
      app.route("/", createJourneyRoutes(client, FIXED_NOW));
      const base = "/?originId=origin&destinationId=destination";

      const invalidUrls = [
        `${base}&searchMode=LATER`,
        `${base}&searchMode=LEAVE_AT`,
        `${base}&searchMode=ARRIVE_BY&requestedDateTime=2026-08-10T12:30:00`,
        `${base}&searchMode=LEAVE_AT&requestedDateTime=not-a-date`,
        `${base}&searchMode=ARRIVE_BY&requestedDateTime=2026-02-30T12:30:00Z`,
        `${base}&searchMode=LEAVE_AT&requestedDateTime=2026-08-10T06:59:00Z`,
        `${base}&searchMode=LEAVE_AT&requestedDateTime=2026-08-10T10:00:30Z`,
        `${base}&searchMode=NOW&requestedDateTime=2026-08-10T10:00:00Z`,
        `${base}&searchMode=ARRIVE_BY&requestedDateTime=2026-08-10T10:30:00Z&searchUntil=2026-08-10T11:00:00Z`,
      ];

      for (const url of invalidUrls) expect((await app.request(url)).status).toBe(400);
      expect(requests).toHaveLength(0);
    });

    it("keeps planned mode and minute in distinct request identities", async () => {
      const { client, requests } = scriptedClient([[], [], []]);
      const routes = createJourneyRoutes(client, FIXED_NOW);
      await routes.request(
        "/?originId=origin&destinationId=destination&searchMode=LEAVE_AT&requestedDateTime=2026-08-10T10:00:00Z",
      );
      await routes.request(
        "/?originId=origin&destinationId=destination&searchMode=ARRIVE_BY&requestedDateTime=2026-08-10T10:00:00Z",
      );
      await routes.request(
        "/?originId=origin&destinationId=destination&searchMode=ARRIVE_BY&requestedDateTime=2026-08-10T11:00:00Z",
      );

      expect(requests.map((request) => [request.dateTimeMode, request.departureAt.toISOString()])).toEqual([
        ["DEPARTURE", "2026-08-10T10:00:00.000Z"],
        ["ARRIVAL", "2026-08-10T10:00:00.000Z"],
        ["ARRIVAL", "2026-08-10T11:00:00.000Z"],
      ]);
    });
  });

  it("passes selected modes upstream and removes journeys using an unselected public mode", async () => {
    const { client, requests } = worldClient([
      { id: "slow", departure: "2026-08-10T08:02:00Z", arrival: "2026-08-10T08:31:00Z", mode: "metro" },
      { id: "fast", departure: "2026-08-10T08:04:00Z", arrival: "2026-08-10T08:23:00Z", mode: "bus" },
    ]);

    const response = await createJourneyRoutes(client, FIXED_NOW).request(
      "/?originId=origin&destinationId=destination&transportModes=METRO",
    );
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

    expect(response.status).toBe(200);
    expect(requests[0]!.transportModes).toEqual(["METRO"]);
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["slow"]);
  });

  it("rejects an empty or unknown transport selection", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        return [];
      },
    };

    const app = new Hono();
    app.onError(onError);
    app.route("/", createJourneyRoutes(client));

    expect((await app.request("/?originId=origin&destinationId=destination&transportModes=TAXI")).status).toBe(400);
    expect((await app.request("/?originId=origin&destinationId=destination&transportModes=,")).status).toBe(400);
  });

  it("uses Journey Planner location identifiers from stop search", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [{ id: "9091001000009001", name: "Stockholm, T-Centralen", disassembledName: "T-Centralen", type: "stop" }];
      },
      async trips() {
        return [];
      },
    };
    const response = await createJourneyRoutes(client).request("/locations/search?query=centralen");
    const body = (await response.json()) as SuccessEnvelope<LocationSearchResponse>;
    expect(body.data.locations[0]).toEqual({ id: "9091001000009001", name: "T-Centralen" });
  });

  it("normalizes complete transfer journeys and their disruption information", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        return [
          {
            tripId: "transfer",
            interchanges: 1,
            legs: [
              {
                origin: { id: "origin-stop", name: "T-Centralen", departureTimeEstimated: "2026-08-10T08:04:00Z" },
                destination: { id: "slussen", name: "Slussen", arrivalTimeEstimated: "2026-08-10T08:09:00Z" },
                transportation: {
                  disassembledName: "14",
                  product: { class: 2, name: "Tunnelbana" },
                  destination: { name: "Norsborg" },
                },
                infos: [{ content: "Lift unavailable" }],
              },
              {
                origin: { id: "slussen", name: "Slussen", departureTimePlanned: "2026-08-10T08:14:00Z" },
                destination: { id: "nacka", name: "Nacka", arrivalTimePlanned: "2026-08-10T08:28:00Z" },
                transportation: {
                  disassembledName: "409",
                  product: { class: 5, name: "Buss" },
                  destination: { name: "Nacka" },
                },
                infos: [],
              },
            ],
          } as unknown as RawJourneyPlannerJourney,
        ];
      },
    };

    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    const journey = body.data.journeys[0]!;

    expect(journey.role).toBe("PRIMARY");
    expect(journey.transferCount).toBe(1);
    expect(journey.firstLeg.lineDesignation).toBe("14");
    expect(journey.legs.map((leg) => leg.lineDesignation)).toEqual(["14", "409"]);
    expect(journey.disruptions).toEqual(["Lift unavailable"]);
    // "Lift unavailable" is English, not Swedish, so none of the classifier's Swedish rules
    // can confidently match it -- the conservative DISRUPTION fallback is correct here, not a
    // guess (see classifyDisruptionEffect.ts's own doc).
    expect(journey.disruptionNotices).toEqual([{ text: "Lift unavailable", effect: "DISRUPTION" }]);
  });

  describe("disruptionNotices", () => {
    function transferJourneyWithInfos(firstLegInfos: unknown[], secondLegInfos: unknown[] = []): RawJourneyPlannerJourney {
      return {
        tripId: "with-notices",
        interchanges: 1,
        legs: [
          {
            origin: { id: "origin-stop", name: "T-Centralen", departureTimeEstimated: "2026-08-10T08:04:00Z" },
            destination: { id: "slussen", name: "Slussen", arrivalTimeEstimated: "2026-08-10T08:09:00Z" },
            transportation: { disassembledName: "14", product: { class: 2, name: "Tunnelbana" }, destination: { name: "Norsborg" } },
            infos: firstLegInfos,
          },
          {
            origin: { id: "slussen", name: "Slussen", departureTimePlanned: "2026-08-10T08:14:00Z" },
            destination: { id: "nacka", name: "Nacka", arrivalTimePlanned: "2026-08-10T08:28:00Z" },
            transportation: { disassembledName: "409", product: { class: 5, name: "Buss" }, destination: { name: "Nacka" } },
            infos: secondLegInfos,
          },
        ],
      } as unknown as RawJourneyPlannerJourney;
    }

    async function journeyFor(raw: RawJourneyPlannerJourney) {
      const client: SlJourneyPlannerClient = {
        async searchStops() {
          return [];
        },
        async trips() {
          return [raw];
        },
      };
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      return body.data.journeys[0]!;
    }

    it("classifies recognizable Swedish Journey Planner text with the existing classifier", async () => {
      const journey = await journeyFor(transferJourneyWithInfos([{ content: "Hissen vid Slussen är ur funktion." }]));
      expect(journey.disruptionNotices).toEqual([
        { text: "Hissen vid Slussen är ur funktion.", effect: "ACCESSIBILITY_ISSUE" },
      ]);
    });

    it("deduplicates identical notice text repeated across legs", async () => {
      const journey = await journeyFor(
        transferJourneyWithInfos([{ content: "Trafikinformation: se sl.se." }], [{ content: "Trafikinformation: se sl.se." }]),
      );
      expect(journey.disruptionNotices).toEqual([{ text: "Trafikinformation: se sl.se.", effect: "DISRUPTION" }]);
    });

    it("detects a notice attached only to a later leg", async () => {
      const journey = await journeyFor(transferJourneyWithInfos([], [{ content: "Bussen är omledd." }]));
      expect(journey.disruptionNotices).toEqual([{ text: "Bussen är omledd.", effect: "ROUTE_CHANGE" }]);
    });

    it("preserves multiple genuinely different notices, in leg order", async () => {
      const journey = await journeyFor(
        transferJourneyWithInfos([{ content: "Hissen är ur funktion." }], [{ content: "Bussen är omledd." }]),
      );
      expect(journey.disruptionNotices).toEqual([
        { text: "Hissen är ur funktion.", effect: "ACCESSIBILITY_ISSUE" },
        { text: "Bussen är omledd.", effect: "ROUTE_CHANGE" },
      ]);
    });

    it("returns an empty list when no leg carries any disruption info", async () => {
      const journey = await journeyFor(transferJourneyWithInfos([], []));
      expect(journey.disruptionNotices).toEqual([]);
    });
  });

  // ---- Expired-journey rejection (2026-08-10 22:12 production incident: a bus that arrived
  // at 19:45 was shown as "fastest" and a metro that arrived at 22:10 as "alternative") ----

  const NOW_22_12 = () => new Date("2026-08-10T22:12:00Z");

  it("rejects a bus that already arrived at 19:45 and a metro that already arrived at 22:10, leaving no journeys", async () => {
    const { client } = worldClient([
      { id: "bus", departure: "2026-08-10T19:30:00Z", arrival: "2026-08-10T19:45:00Z", interchanges: 3, mode: "bus" },
      { id: "fast", departure: "2026-08-10T21:55:00Z", arrival: "2026-08-10T22:10:00Z", mode: "metro" },
    ]);
    const response = await createJourneyRoutes(client, NOW_22_12).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(response.status).toBe(200);
    expect(body.data.journeys).toEqual([]);
  });

  it("a genuinely upcoming journey is unaffected and becomes PRIMARY even alongside expired ones", async () => {
    const { client } = worldClient([
      { id: "bus", departure: "2026-08-10T19:30:00Z", arrival: "2026-08-10T19:45:00Z", interchanges: 3, mode: "bus" },
      { id: "fast", departure: "2026-08-10T22:15:00Z", arrival: "2026-08-10T22:35:00Z", mode: "metro" },
    ]);
    const response = await createJourneyRoutes(client, NOW_22_12).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["fast"]);
    expect(body.data.journeys[0]!.role).toBe("PRIMARY");
  });

  it("expired results are rejected before PRIMARY selection, not merely out-ranked", async () => {
    const { client } = worldClient([
      // Earliest arrival of the two, but already departed and arrived -- must never become
      // PRIMARY purely because it would otherwise sort first by arrival time.
      { id: "expired", departure: "2026-08-10T19:30:00Z", arrival: "2026-08-10T19:45:00Z", mode: "metro" },
      { id: "fast", departure: "2026-08-10T22:15:00Z", arrival: "2026-08-10T22:35:00Z", mode: "metro" },
    ]);
    const response = await createJourneyRoutes(client, NOW_22_12).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["fast"]);
  });

  // ---- MAX_CHANGES=2 enforcement (defense-in-depth: enforced again here even though
  // slJourneyPlannerClient.ts also asks SL for a max_changes ceiling upstream) ----

  it("allows a direct (zero-change) journey", async () => {
    const { client } = worldClient([{ id: "candidate", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:20:00Z", interchanges: 0 }]);
    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["candidate"]);
  });

  it("allows a one-change journey", async () => {
    const { client } = worldClient([{ id: "candidate", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:20:00Z", interchanges: 1 }]);
    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["candidate"]);
  });

  it("allows a two-change journey", async () => {
    const { client } = worldClient([{ id: "candidate", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:20:00Z", interchanges: 2 }]);
    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["candidate"]);
  });

  it("rejects a three-change journey even if the upstream response unexpectedly includes one", async () => {
    const { client } = worldClient([{ id: "candidate", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:10:00Z", interchanges: 3 }]);
    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys).toEqual([]);
  });

  it("a three-change journey never wins PRIMARY, even with the earliest arrival, alongside a valid two-change journey", async () => {
    const { client } = worldClient([
      { id: "three-changes", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:10:00Z", interchanges: 3 },
      { id: "two-changes", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:20:00Z", interchanges: 2, mode: "bus" },
    ]);
    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["two-changes"]);
  });

  // ---- searchUntil: the caller's own routine-occurrence boundary ----

  it("rejects a malformed searchUntil", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        return [];
      },
    };
    const app = new Hono();
    app.onError(onError);
    app.route("/", createJourneyRoutes(client, FIXED_NOW));

    const response = await app.request("/?originId=origin&destinationId=destination&searchUntil=not-a-date");
    expect(response.status).toBe(400);
  });

  it("fails closed when searchUntil is absent: answers from the initial acquisition alone rather than searching unboundedly", async () => {
    const { client, requests } = worldClient([
      { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
      // A genuine same-family NEXT exists, but far enough out that it is not among the
      // initial batch's own closest 3 unless a forward search goes looking for it.
      { id: "filler-a", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T19:20:00Z", mode: "bus" },
      { id: "filler-b", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T19:25:00Z", mode: "bus" },
      { id: "would-be-next", departure: "2026-08-10T19:10:00Z", arrival: "2026-08-10T19:13:00Z", mode: "metro" },
    ]);

    const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
    const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

    expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary"]);
    expect(body.data.journeys[0]!.role).toBe("PRIMARY");
    // Exactly one request -- no forward search was ever attempted without a boundary to
    // search within.
    expect(requests).toHaveLength(1);
  });

  // ---- PRIMARY / NEXT / ALTERNATIVE role assignment -- route family + Pareto dominance,
  // never a minute-based threshold. FIXED_NOW (07:00) is well before every departure below. ----

  describe("PRIMARY / NEXT / ALTERNATIVE role assignment", () => {
    it("a detour between two frequent direct departures is Pareto-dominated and never appears", async () => {
      // The product spec's own worked example: metro 18:35, a one-change detour at 18:36,
      // metro 18:39 -- the later, faster, simpler metro dominates the detour outright.
      const { client, requests } = worldClient([
        { id: "metro-1835", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
        { id: "detour-1836", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 1, mode: "bus" },
        { id: "metro-1839", departure: "2026-08-10T18:39:00Z", arrival: "2026-08-10T18:42:00Z", mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["metro-1835", "metro-1839"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      // Four requests: the detour is eliminated by dominance from the very first batch, but
      // the ALTERNATIVE search still actively searches the (PRIMARY, NEXT) interval -- an
      // already-resolvable-looking pool is never trusted without requests genuinely anchored
      // inside that interval (see journeys.ts's own doc). The interval search itself takes
      // three requests: cursor advancement probes the bucket containing the earliest new
      // departure directly before ever advancing past it (see
      // CandidateCollector.acquireUntil's own doc) -- the first interval request (18:35)
      // finds the detour's own 18:36 as its earliest new departure, so the second request
      // probes 18:36 itself (never 18:37, a full minute past it); THAT request finds
      // metro-1839's 18:39 as its own earliest new departure, so a third request probes
      // 18:39 directly too -- only once that bucket has actually been queried does the
      // cursor fall back past it and exceed the (18:35, 18:39) interval, ending the search.
      expect(requests).toHaveLength(4);
    });

    it("a direct (zero-change) bus qualifies as ALTERNATIVE -- transferCount === 0 no longer disqualifies it", async () => {
      const { client, requests } = worldClient([
        { id: "metro-2230", departure: "2026-08-10T22:30:00Z", arrival: "2026-08-10T23:00:00Z", mode: "metro" },
        { id: "direct-bus-2250", departure: "2026-08-10T22:50:00Z", arrival: "2026-08-10T23:40:00Z", interchanges: 0, mode: "bus" },
        { id: "metro-2330", departure: "2026-08-10T23:30:00Z", arrival: "2026-08-11T00:00:00Z", mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["metro-2230", "direct-bus-2250", "metro-2330"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
      // Four requests: even though the initial batch's own pool already resolves to a
      // qualifying ALTERNATIVE, that is never trusted on its own -- the interval is still
      // actively searched before `selectAlternative` is treated as final, taking three
      // requests (not one): the first interval request (22:30) finds the bus's own 22:50
      // as its earliest new departure, so the second request probes 22:50 itself (never
      // 22:51, a full minute past it); THAT request finds metro-2330's 23:30 as its own
      // earliest new departure, so a third request probes 23:30 directly too, before the
      // cursor finally falls back past it and exceeds the (22:30, 23:30) interval.
      expect(requests).toHaveLength(4);
    });

    it("a candidate arriving exactly when NEXT arrives is rejected -- the advantage must be strict", async () => {
      const { client } = worldClient([
        { id: "metro-2230", departure: "2026-08-10T22:30:00Z", arrival: "2026-08-10T23:00:00Z", mode: "metro" },
        { id: "metro-2330", departure: "2026-08-10T23:30:00Z", arrival: "2026-08-11T00:00:00Z", mode: "metro" },
        { id: "bus-tied-arrival", departure: "2026-08-10T22:50:00Z", arrival: "2026-08-11T00:00:00Z", interchanges: 0, mode: "bus" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["metro-2230", "metro-2330"]);
    });

    it("a 2-change candidate qualifies as ALTERNATIVE -- up to MAX_CHANGES is allowed", async () => {
      const { client } = worldClient([
        { id: "metro-2230", departure: "2026-08-10T22:30:00Z", arrival: "2026-08-10T23:00:00Z", mode: "metro" },
        { id: "metro-2330", departure: "2026-08-10T23:30:00Z", arrival: "2026-08-11T00:00:00Z", mode: "metro" },
        { id: "bus-two-changes", departure: "2026-08-10T22:50:00Z", arrival: "2026-08-10T23:40:00Z", interchanges: 2, mode: "bus" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["metro-2230", "bus-two-changes", "metro-2330"]);
    });

    it("same route family, different line designations: the second departure is NEXT even though nothing else ties them but the corridor", async () => {
      // rawJourney's own line designation is derived purely from `mode`, not `id` -- both
      // "primary" and "later" below are METRO on the same origin/destination stops, i.e.
      // the same RoutePattern, exactly like two different line numbers through one corridor.
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", mode: "metro" },
        { id: "later", departure: "2026-08-10T18:50:00Z", arrival: "2026-08-10T19:05:00Z", mode: "metro" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "later"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
    });

    // ---- Acquisition: SL's 1-3-result limitation, solved with targeted forward batches ----

    it("a same-family NEXT missing from the initial batch is found via a second, targeted request; an unrelated journey never becomes NEXT merely by position", async () => {
      const { client, requests } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
        // Two unrelated (different-family) journeys crowd out the genuine same-family NEXT
        // from the initial batch's own closest-3 window -- and arrive too late to ever
        // qualify as an ALTERNATIVE either, so this test isolates NEXT-acquisition alone.
        { id: "unrelated-a", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T19:20:00Z", mode: "bus" },
        { id: "unrelated-b", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T19:25:00Z", mode: "bus" },
        { id: "genuine-next", departure: "2026-08-10T19:10:00Z", arrival: "2026-08-10T19:13:00Z", mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-10T20:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      const roleById = Object.fromEntries(body.data.journeys.map((j) => [j.journeyId, j.role]));
      expect(roleById["primary"]).toBe("PRIMARY");
      expect(roleById["genuine-next"]).toBe("NEXT");
      expect(roleById["unrelated-a"]).toBeUndefined();
      expect(roleById["unrelated-b"]).toBeUndefined();
      // More than one request was needed to find it.
      expect(requests.length).toBeGreaterThan(1);
    });

    it("a candidate departing at or after NEXT's own departure is never considered for ALTERNATIVE, even if a broader search happens to return it", async () => {
      const { client } = scriptedClient([
        [rawJourney("primary", "2026-08-10T22:30:00Z", "2026-08-10T23:00:00Z", 0, "metro")],
        [rawJourney("next", "2026-08-10T23:30:00Z", "2026-08-11T00:00:00Z", 0, "metro")],
        // Departs AFTER NEXT's own departure -- must never qualify, however it arrives.
        // Arrives AFTER NEXT's own arrival too, deliberately, so it does not itself
        // Pareto-dominate NEXT out of the pool -- this test is about the ALTERNATIVE
        // boundary rule specifically, not about dominance.
        [rawJourney("too-late", "2026-08-10T23:35:00Z", "2026-08-11T00:10:00Z", 0, "bus")],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T01:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
    });

    it("an alternative that qualified against an earlier NEXT is invalidated once a later batch reclassifies NEXT to an earlier departure", async () => {
      // The product spec's own reclassification example: PRIMARY 22:30, an initially-known
      // NEXT at 23:30 -- a first broader ALTERNATIVE batch finds a bus that genuinely
      // qualifies against THAT NEXT (arrives well before 00:00). Acquisition must not stop
      // there: a further batch discovers a same-family 23:00 departure, which must become
      // the new NEXT -- after which the earlier bus no longer qualifies (it arrives after
      // the NEW NEXT's own arrival), while a second, genuinely-qualifying bus discovered in
      // that SAME later batch correctly becomes ALTERNATIVE instead.
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch: only PRIMARY.
        [rawJourney("primary", "2026-08-10T22:30:00Z", "2026-08-10T23:00:00Z", 0, "metro")],
        // 2) Targeted NEXT search (metro-only): establishes an initial NEXT at 23:30.
        [rawJourney("original-next", "2026-08-10T23:30:00Z", "2026-08-11T00:00:00Z", 0, "metro")],
        // 3) ALTERNATIVE search's first batch: a bus that qualifies against the CURRENT
        // (23:30-arriving-00:00) NEXT -- must not be trusted as final yet.
        [rawJourney("would-be-alternative", "2026-08-10T22:45:00Z", "2026-08-10T23:50:00Z", 0, "bus")],
        // 4) ALTERNATIVE search continues (never stopping merely because #3 qualified) and
        // finds BOTH a same-family reclassifying journey AND a genuinely better bus in the
        // same batch.
        [
          rawJourney("reclassified-next", "2026-08-10T23:00:00Z", "2026-08-10T23:20:00Z", 0, "metro"),
          rawJourney("better-alternative", "2026-08-10T22:52:00Z", "2026-08-10T23:15:00Z", 1, "bus"),
        ],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T06:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "better-alternative", "reclassified-next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
      // Neither the superseded original NEXT nor the invalidated earlier alternative may
      // ever appear -- `selectAlternative` only ever ran once, after acquisition finished,
      // against the fully reconciled pool.
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("original-next");
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("would-be-alternative");
      // Five requests, not four: batch #4's own earliest-strictly-after-its-bucket departure
      // is better-alternative's own 22:52 (earlier than reclassified-next's 23:00, even
      // though reclassified-next is what the pool ends up using as NEXT) -- cursor
      // advancement is deliberately conservative (see CandidateCollector.acquireUntil's own
      // doc), so a fifth request is genuinely attempted, anchored at 22:53, still within the
      // freshly-shrunk (PRIMARY, reclassified-next) interval. The script has nothing left to
      // offer there, so this fifth request comes back empty and acquisition correctly stops
      // -- proving the search keeps thoroughly probing the shrunk interval rather than
      // settling for the first batch that happens to already contain a good answer.
      expect(requests).toHaveLength(5);
    });

    it("a later batch discovers a better alternative that Pareto-dominates an earlier batch's qualifying candidate", async () => {
      // No reclassification involved this time -- NEXT is fixed from the very first batch.
      // The point is narrower: acquisition must not stop merely because batch 1 already
      // found something that qualifies as ALTERNATIVE; a further batch can still supersede
      // it with a dominating candidate before `selectAlternative` is ever evaluated.
      const { client, requests } = scriptedClient([
        // 1) Initial batch: PRIMARY and NEXT both already in the same family.
        [
          rawJourney("primary", "2026-08-10T22:30:00Z", "2026-08-10T23:00:00Z", 0, "metro"),
          rawJourney("next", "2026-08-10T23:30:00Z", "2026-08-11T00:00:00Z", 0, "metro"),
        ],
        // 2) ALTERNATIVE search's first batch: a qualifying-but-weak bus.
        [rawJourney("weak-alternative", "2026-08-10T22:40:00Z", "2026-08-10T23:25:00Z", 0, "bus")],
        // 3) A further batch finds a bus that Pareto-dominates the weak one outright
        // (later departure, earlier arrival, same transfer count).
        [rawJourney("better-alternative", "2026-08-10T22:55:00Z", "2026-08-10T23:10:00Z", 0, "bus")],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T06:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "better-alternative", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("weak-alternative");
      // Initial batch, then the ALTERNATIVE search's own two forward batches, then one more
      // that finds SL genuinely has nothing further to offer.
      expect(requests).toHaveLength(4);
    });

    it("PRIMARY and NEXT both already in the initial batch, with a searchUntil boundary: one request to establish them, one more to confirm there is no alternative", async () => {
      const { client, requests } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", mode: "metro" },
        { id: "next", departure: "2026-08-10T18:50:00Z", arrival: "2026-08-10T19:05:00Z", mode: "metro" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-10T20:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      // Unlike the old gap-threshold design, there is no shortcut that skips the
      // alternative check purely because PRIMARY/NEXT were close together -- the search
      // always looks, bounded to the (PRIMARY, NEXT) interval, and here correctly finds
      // nothing (no other candidate exists in this world at all). Three requests: the
      // initial batch, then the interval search's own first request (18:35) finds NEXT's
      // own 18:50 as its earliest new departure and probes that bucket directly (never
      // jumping a full minute past it), before falling back past it and exceeding the
      // interval.
      expect(requests).toHaveLength(3);
    });

    it("with no searchUntil at all, the ALTERNATIVE search still runs once PRIMARY and NEXT are already known -- NEXT's own departure is a sufficient boundary by itself", async () => {
      const { client, requests } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", mode: "metro" },
        { id: "next", departure: "2026-08-10T18:50:00Z", arrival: "2026-08-10T19:05:00Z", mode: "metro" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      // `searchUntil` bounds NEXT-discovery only, when NEXT is not yet known -- it was never
      // needed here (PRIMARY/NEXT both came from the initial batch), yet the ALTERNATIVE
      // search still runs, using NEXT's own departure as its natural boundary. Three
      // requests, for the same reason as the identical-world searchUntil test above:
      // probing NEXT's own bucket directly before falling back past it.
      expect(requests).toHaveLength(3);
    });

    // ---- SL minute-precision acquisition: itd_time is HHMM-only, so a targeted search
    // anchor must never skip PRIMARY's own request minute (see stockholmTime.ts's own
    // floorToStockholmRequestMinute/nextStockholmRequestMinute doc). ----

    it("PRIMARY departing at 18:35:05 with NEXT at 18:35:40 in the SAME request minute is still found -- the targeted anchor must not skip PRIMARY's own minute", async () => {
      // Unlike worldClient, this fake mirrors SL's own whole-minute request precision
      // directly: it only looks at the REQUESTED minute (floored), never exact seconds --
      // exactly like real SL's `itd_time` parameter. A targeted search anchored even one
      // minute late (the pre-fix `latestDeparture + 60s` anchor) would floor to 18:36 and
      // permanently miss both PRIMARY's own 18:35:05 and NEXT's 18:35:40.
      const journeys = [
        { id: "filler-a", departure: "2026-08-10T10:00:00Z", arrival: "2026-08-10T23:00:00Z", mode: "bus" as const },
        { id: "filler-b", departure: "2026-08-10T14:00:00Z", arrival: "2026-08-10T23:30:00Z", mode: "bus" as const },
        { id: "primary", departure: "2026-08-10T18:35:05Z", arrival: "2026-08-10T18:40:00Z", mode: "metro" as const },
        { id: "next", departure: "2026-08-10T18:35:40Z", arrival: "2026-08-10T18:42:00Z", mode: "metro" as const },
      ];
      const requests: TripsRequest[] = [];
      const client: SlJourneyPlannerClient = {
        async searchStops() {
          return [];
        },
        async trips(request) {
          requests.push(request);
          const requestedMinuteFloorMillis = Math.floor(request.departureAt.getTime() / 60_000) * 60_000;
          const allowedModes = request.transportModes != null ? new Set<string>(request.transportModes) : null;
          const matches = journeys
            .filter((j) => Date.parse(j.departure) >= requestedMinuteFloorMillis)
            .filter((j) => allowedModes == null || allowedModes.has(j.mode === "metro" ? "METRO" : "BUS"))
            .sort((a, b) => Date.parse(a.departure) - Date.parse(b.departure))
            .slice(0, 3);
          return matches.map((j) => rawJourney(j.id, j.departure, j.arrival, 0, j.mode));
        },
      };

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-10T20:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      const roleById = Object.fromEntries(body.data.journeys.map((j) => [j.journeyId, j.role]));
      expect(roleById["primary"]).toBe("PRIMARY");
      expect(roleById["next"]).toBe("NEXT");
      expect(body.data.journeys).toHaveLength(2);
    });

    // ---- Global Pareto dominance must never run before PRIMARY/NEXT selection: NEXT means
    // "the soonest route-compatible departure", a different question from "objectively best
    // overall", and applying dominance globally can silently eliminate the correct answer to
    // the first question in favor of the second. Dominance remains valid, but only once
    // scoped to ALTERNATIVE candidates already confined to the PRIMARY-to-NEXT interval (see
    // backend/src/domain/journeyRoles.ts's own doc). ----

    it("a later same-family departure that arrives earlier does not remove the true earlier NEXT", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
        { id: "true-next", departure: "2026-08-10T18:39:00Z", arrival: "2026-08-10T18:50:00Z", mode: "metro" },
        // Departs later than true-next but arrives sooner -- Pareto-dominates true-next
        // outright, but must never suppress it as NEXT: NEXT means the soonest departure
        // you can catch on this route, not the objectively best same-family journey.
        { id: "faster-but-later", departure: "2026-08-10T18:42:00Z", arrival: "2026-08-10T18:45:00Z", mode: "metro" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      const roleById = Object.fromEntries(body.data.journeys.map((j) => [j.journeyId, j.role]));
      expect(roleById["primary"]).toBe("PRIMARY");
      expect(roleById["true-next"]).toBe("NEXT");
      // Same family as PRIMARY, so it can never be ALTERNATIVE either -- it must not
      // appear anywhere in the response.
      expect(roleById["faster-but-later"]).toBeUndefined();
    });

    it("a route-incompatible journey departing after NEXT cannot remove NEXT via dominance", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
        { id: "next", departure: "2026-08-10T18:39:00Z", arrival: "2026-08-10T18:42:00Z", mode: "metro" },
        // Departs AFTER next and arrives sooner -- Pareto-dominates next under a global
        // comparison, but is a different route entirely (bus), so it could never legally
        // become NEXT itself. It must never be allowed to eliminate the genuine NEXT either.
        { id: "unrelated-bus", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:41:00Z", mode: "bus" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
    });

    it("a route-incompatible journey inside the PRIMARY-to-NEXT interval can still become ALTERNATIVE", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
        { id: "next", departure: "2026-08-10T18:45:00Z", arrival: "2026-08-10T18:55:00Z", mode: "metro" },
        // Departs strictly between PRIMARY and NEXT and arrives strictly before NEXT --
        // exactly the ALTERNATIVE eligibility window, unlike the previous test's bus,
        // which departed after NEXT.
        { id: "alt-bus", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:50:00Z", mode: "bus" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "alt-bus", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
    });

    it("dominance still eliminates an objectively inferior ALTERNATIVE candidate inside the PRIMARY-to-NEXT interval", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
        { id: "next", departure: "2026-08-10T19:00:00Z", arrival: "2026-08-10T19:20:00Z", mode: "metro" },
        { id: "weak-bus", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T19:10:00Z", mode: "bus" },
        // Departs later than weak-bus and arrives sooner, same transfer count -- Pareto-
        // dominates weak-bus outright, both otherwise qualifying as ALTERNATIVE.
        { id: "strong-bus", departure: "2026-08-10T18:45:00Z", arrival: "2026-08-10T18:55:00Z", mode: "bus" },
      ]);
      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-10T20:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "strong-bus", "next"]);
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("weak-bus");
    });
  });

  // ---- Candidate pool upsert: a journey already known from an earlier batch has its
  // entry REPLACED by a later batch's fresher representation, never ignored as a
  // duplicate -- and acquisition never stops merely because a batch repeats an
  // already-seen set of journey ids (see backend/src/services/candidateCollector.ts's own
  // doc). ----

  describe("realtime updates to already-known candidates, and repeated best-match responses", () => {
    it("a realtime update to an already-known candidate can change NEXT -- the stale first-seen departure never survives", async () => {
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch: PRIMARY and an initial same-family NEXT candidate ("x").
        [
          rawJourney("primary", "2026-08-10T18:35:00Z", "2026-08-10T18:38:00Z", 0, "metro"),
          rawJourney("x", "2026-08-10T18:39:00Z", "2026-08-10T18:45:00Z", 0, "metro"),
        ],
        // 2) ALTERNATIVE search's first batch: a realtime update delays "x" to 18:43, and
        // a second, previously-undiscovered same-family departure ("y") turns up at
        // 18:41 -- earlier than the now-delayed "x".
        [
          rawJourney("x", "2026-08-10T18:43:00Z", "2026-08-10T18:49:00Z", 0, "metro"),
          rawJourney("y", "2026-08-10T18:41:00Z", "2026-08-10T18:44:00Z", 0, "metro"),
        ],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "y"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      // "x"'s stale first-seen departure (18:39, which would have beaten "y"'s 18:41) never
      // survives -- selection only ever sees its updated, later departure (18:43), so "y"
      // correctly wins as the genuinely earliest compatible departure.
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("x");
      // Three requests: initial, then the ALTERNATIVE search's own first request (18:35)
      // finds "y"'s own 18:41 as the earliest new departure (earlier than "x"'s
      // now-delayed 18:43) and probes that bucket directly; a third, empty response there
      // confirms nothing more remains inside the freshly-shrunk interval.
      expect(requests).toHaveLength(3);
    });

    it("a realtime update to NEXT's own arrival can invalidate an already-qualifying ALTERNATIVE", async () => {
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch: PRIMARY, and an initial NEXT scheduled to arrive the
        // following day.
        [
          rawJourney("primary", "2026-08-10T22:30:00Z", "2026-08-10T23:00:00Z", 0, "metro"),
          rawJourney("next-cand", "2026-08-10T23:30:00Z", "2026-08-11T00:00:00Z", 0, "metro"),
        ],
        // 2) ALTERNATIVE search's first batch: a bus that genuinely qualifies against the
        // CURRENT (next-day-arriving) NEXT.
        [rawJourney("alt-cand", "2026-08-10T22:50:00Z", "2026-08-10T23:40:00Z", 0, "bus")],
        // 3) A realtime update to NEXT's own arrival -- it now arrives at 23:35, well
        // before the bus's own 23:40.
        [rawJourney("next-cand", "2026-08-10T23:30:00Z", "2026-08-10T23:35:00Z", 0, "metro")],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "next-cand"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      expect(body.data.journeys.find((journey) => journey.journeyId === "next-cand")!.arrivalTime).toBe("2026-08-10T23:35:00Z");
      // The bus no longer qualifies: 23:40 is not strictly before NEXT's own UPDATED 23:35
      // arrival -- its stale qualification against the old, next-day-arriving NEXT never
      // survives.
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("alt-cand");
      // Four requests: initial, then the interval search's own first request (22:30) finds
      // the bus's own 22:50 as the earliest new departure and probes that bucket directly;
      // that request finds next-cand's own 23:30 as ITS earliest new departure, so a third
      // request probes 23:30 directly too (discovering the realtime arrival update); a
      // fourth, empty response then confirms nothing more remains.
      expect(requests).toHaveLength(4);
    });

    it("repeated best-match batches (the same two journeys, twice) do not prevent a later batch from discovering a genuinely new one", async () => {
      const xyBatch = () => [
        rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T19:25:00Z", 0, "bus"),
        rawJourney("y", "2026-08-10T18:42:00Z", "2026-08-10T19:26:00Z", 0, "bus"),
      ];
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch: PRIMARY and NEXT both already established.
        [
          rawJourney("primary", "2026-08-10T18:35:00Z", "2026-08-10T18:38:00Z", 0, "metro"),
          rawJourney("next", "2026-08-10T19:00:00Z", "2026-08-10T19:20:00Z", 0, "metro"),
        ],
        // 2) ALTERNATIVE search's first batch: two buses, neither actually qualifying
        // (both arrive after NEXT's own 19:20).
        xyBatch(),
        // 3) SL repeats the EXACT same answer, even though the cursor genuinely advanced --
        // must never be mistaken for "there is nothing left to find here".
        xyBatch(),
        // 4) A further batch finally reveals a genuinely qualifying alternative.
        [rawJourney("z", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z", 0, "bus")],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "z", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
      // Five requests: initial, then four ALTERNATIVE-search batches (X/Y, the identical
      // repeat, the batch revealing Z, and one final empty response confirming nothing
      // more remains) -- the repeat in the middle must never have cut this search short.
      expect(requests).toHaveLength(5);
    });

    it("a realtime update to the current PRIMARY's own arrival can hand PRIMARY to an existing, unchanged candidate", async () => {
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch: "a" (metro) initially has the best arrival and wins
        // PRIMARY; "b" (bus, a different family) initially loses.
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:40:00Z", 0, "metro"),
          rawJourney("b", "2026-08-10T18:36:00Z", "2026-08-10T18:50:00Z", 0, "bus"),
        ],
        // 2) Targeted NEXT search (metro-only, since "a" is currently PRIMARY): a realtime
        // update reports "a" now arriving much later -- worse than "b"'s own unchanged
        // arrival.
        [rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:58:00Z", 0, "metro")],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-10T20:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      // "b" never moved at all -- it takes over PRIMARY purely because "a"'s own realtime
      // data got worse, exactly as if "b" had just been freshly discovered.
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["b"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY"]);
      expect(requests).toHaveLength(3);
    });

    it("an ALTERNATIVE candidate with unknown walking flows through normalization and selection safely, without displacing a genuinely better known-walking candidate", async () => {
      // The decisive proof that unknown walking blocks a false dominance claim lives in
      // dominance.test.ts: dominance and the final lexicographic ranking are mathematically
      // consistent (see journeyRoles.ts's own selectPrimary doc), so whichever candidate
      // legitimately ranks best always wins here regardless of whether dominance runs
      // first. This test instead confirms the unknown-walking data path itself is safe
      // end-to-end -- it normalizes, survives acquisition, and never corrupts or displaces
      // the genuinely better known-walking candidate's own win.
      const knownWalkingAlt = rawJourney("alt-known-walking", "2026-08-10T18:40:00Z", "2026-08-10T18:45:00Z", 0, "bus");
      const unknownWalkingAlt = {
        tripId: "alt-unknown-walking",
        interchanges: 0,
        legs: [
          {
            origin: { id: "origin-stop", name: "T-Centralen", departureTimeEstimated: "2026-08-10T18:42:00Z" },
            destination: { id: "walk-junction", name: "Junction", arrivalTimeEstimated: "2026-08-10T18:42:00Z" },
            transportation: { product: { class: 99, name: "Gång" }, destination: { name: "Junction" } },
            infos: [],
            // `duration` deliberately omitted -- this is what makes walkingDurationSeconds
            // resolve to null (genuinely unknown) rather than a known zero.
          },
          {
            origin: { id: "walk-junction", name: "Junction", departureTimeEstimated: "2026-08-10T18:42:00Z" },
            destination: { id: "destination-stop", name: "Mariatorget", arrivalTimeEstimated: "2026-08-10T18:50:00Z" },
            transportation: { disassembledName: "135", product: { class: 5, name: "Buss" }, destination: { name: "Mariatorget" } },
            infos: [],
          },
        ],
      } as unknown as RawJourneyPlannerJourney;

      const { client, requests } = scriptedClient([
        [
          rawJourney("primary", "2026-08-10T18:35:00Z", "2026-08-10T18:38:00Z", 0, "metro"),
          rawJourney("next", "2026-08-10T19:00:00Z", "2026-08-10T19:20:00Z", 0, "metro"),
        ],
        [knownWalkingAlt, unknownWalkingAlt],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "alt-known-walking", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
      expect(requests).toHaveLength(3);
    });
  });

  // ---- PRIMARY retargeting: the acquisition state machine must never keep searching for
  // NEXT/ALTERNATIVE against a PRIMARY that has since been superseded -- see
  // backend/src/routes/journeys.ts's own `resolveSelection` doc. ----

  describe("PRIMARY retargeting during NEXT_DISCOVERY", () => {
    it("PRIMARY changes during NEXT acquisition -- the old targeting is abandoned and a NEXT is found for the NEW primary", async () => {
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch: "a" (metro) initially wins PRIMARY; "b" (bus) initially
        // loses.
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:40:00Z", 0, "metro"),
          rawJourney("b", "2026-08-10T18:36:00Z", "2026-08-10T18:50:00Z", 0, "bus"),
        ],
        // 2) Targeted NEXT search (metro-only, since "a" is PRIMARY): a realtime update
        // reports "a" now arriving worse than "b" -- "b" must take over PRIMARY.
        [rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:55:00Z", 0, "metro")],
        // 3) Retargeted NEXT search (bus-only, now that "b" is PRIMARY): reveals a genuine
        // same-family NEXT for "b".
        [rawJourney("b2", "2026-08-10T18:50:00Z", "2026-08-10T19:00:00Z", 0, "bus")],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["b", "b2"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      // Neither "a" nor a metro-targeted NEXT ever appears -- the old targeting was fully
      // abandoned, not merely raced against the new one.
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("a");
      // Four requests: initial, the abandoned metro-targeted search (1 request before
      // retargeting), the bus-targeted search for "b" (1 request, finds "b2"
      // immediately), then one ALTERNATIVE-interval request that finds nothing further.
      expect(requests).toHaveLength(4);
    });

    it("PRIMARY changes multiple times (A -> B -> C) across successive realtime batches, always retargeting to the latest current PRIMARY", async () => {
      const { client, requests } = scriptedClient([
        // 1) Initial: "a" (metro, best), "b" and "c" (both bus, worse) all present.
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:40:00Z", 0, "metro"),
          rawJourney("b", "2026-08-10T18:36:00Z", "2026-08-10T18:50:00Z", 0, "bus"),
          rawJourney("c", "2026-08-10T18:37:00Z", "2026-08-10T19:00:00Z", 0, "bus"),
        ],
        // 2) Targeted NEXT search for "a" (metro): realtime update makes "a" worse than
        // "b" -- "b" becomes PRIMARY.
        [rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:55:00Z", 0, "metro")],
        // 3) Retargeted NEXT search for "b" (bus): reveals a BRAND NEW bus journey that
        // arrives even better than "b" -- "c" (already in the pool, unchanged) is NOT
        // involved in this step at all, proving retargeting reacts to realtime updates,
        // not merely newly-discovered candidates.
        [rawJourney("d", "2026-08-10T18:40:00Z", "2026-08-10T18:45:00Z", 0, "bus")],
        // 4) Retargeted NEXT search for "d" (bus): reveals a genuine same-family NEXT.
        [rawJourney("d2", "2026-08-10T18:50:00Z", "2026-08-10T18:55:00Z", 0, "bus")],
      ]);

      const spy = metricsSpy();
      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["d", "d2"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("a");
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("b");
      // The shared collector's own pool/budget are never reset across retargets -- "c",
      // discovered in the very first batch, is still sitting in the pool the whole time,
      // simply never winning any role.
      expect(spy.events).toHaveLength(1);
      expect(spy.events[0]!.primaryRetargets).toBe(2);
      expect(spy.events[0]!.primaryChanged).toBe(true);
      expect(spy.events[0]!.budgetExhausted).toBe(false);
      // Five requests: initial, the abandoned metro search for "a", the abandoned bus
      // search for "b" (finds "d" instead), the bus search for "d" (finds "d2"
      // immediately), then one ALTERNATIVE-interval request that finds nothing further.
      expect(requests).toHaveLength(5);
    });

    it("the SAME journey staying PRIMARY across a realtime update does not trigger an unnecessary retarget", async () => {
      const { client, requests } = scriptedClient([
        [rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:40:00Z", 0, "metro")],
        // Targeted NEXT search: "a" itself gets a minor realtime update (still the SAME
        // journey id, still wins PRIMARY outright) while ALSO revealing its own NEXT in
        // the very same batch.
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:42:00Z", 0, "metro"),
          rawJourney("a2", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z", 0, "metro"),
        ],
      ]);

      const spy = metricsSpy();
      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["a", "a2"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      expect(body.data.journeys.find((journey) => journey.journeyId === "a")!.arrivalTime).toBe("2026-08-10T18:42:00Z");
      expect(spy.events[0]!.primaryRetargets).toBe(0);
      expect(spy.events[0]!.primaryChanged).toBe(false);
      // Three requests: initial, the NEXT search (found immediately, no wasted restart),
      // then one ALTERNATIVE-interval request that finds nothing further -- exactly what
      // a non-retargeting run would need, proving the same-id update cost nothing extra.
      expect(requests).toHaveLength(3);
    });

    it("budget exhausted after a retarget returns the safely-established current state, never resurrecting the old PRIMARY/NEXT", async () => {
      let callCount = 0;
      const client: SlJourneyPlannerClient = {
        async searchStops() {
          return [];
        },
        async trips(request) {
          callCount++;
          if (callCount === 1) {
            return [
              rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:40:00Z", 0, "metro"),
              rawJourney("b", "2026-08-10T18:36:00Z", "2026-08-10T18:50:00Z", 0, "bus"),
            ];
          }
          if (callCount === 2) {
            // Targeted NEXT search for "a": realtime update makes "a" worse than "b".
            return [rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:55:00Z", 0, "metro")];
          }
          // Retargeted NEXT search for "b" (bus, boarding at origin-stop): every further
          // response is a genuine, eligible, always-advancing bus journey that can NEVER
          // qualify as "b"'s own NEXT -- a different route entirely (a different
          // boarding stop), so `isRouteCompatible` always rejects it. Real forward
          // progress every time, so only the shared budget can end this. Arrival is
          // pinned comfortably worse than "b"'s own fixed 18:50 (never derived from the
          // advancing departure) so a filler can never accidentally win PRIMARY outright
          // via `selectPrimary`'s own whole-pool arrival comparison -- that would be a
          // genuine, correct retarget in its own right, just not the one this test means
          // to exercise.
          const departure = new Date(request.departureAt.getTime() + 60_000);
          return [
            {
              tripId: `filler-${callCount}`,
              interchanges: 0,
              legs: [
                {
                  origin: { id: "other-stop", name: "Other", departureTimeEstimated: departure.toISOString() },
                  destination: {
                    id: "other-destination",
                    name: "OtherDest",
                    arrivalTimeEstimated: "2026-08-10T23:00:00Z",
                  },
                  transportation: { disassembledName: "999", product: { class: 5, name: "Buss" }, destination: { name: "OtherDest" } },
                  infos: [],
                },
              ],
            } as unknown as RawJourneyPlannerJourney,
          ];
        },
      };

      const spy = metricsSpy();
      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-15T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      // "b" is returned alone -- never the stale "a", and never a fabricated NEXT
      // conjured merely to fill a second slot.
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["b"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY"]);
      expect(callCount).toBe(MAX_ACQUISITION_BATCHES);
      expect(spy.events[0]!.slCalls).toBe(MAX_ACQUISITION_BATCHES);
      expect(spy.events[0]!.primaryRetargets).toBe(1);
      expect(spy.events[0]!.budgetExhausted).toBe(true);
    });
  });

  describe("PRIMARY retargeting during ALTERNATIVE_INTERVAL_DISCOVERY", () => {
    it("PRIMARY changes during an ALTERNATIVE scan and the new PRIMARY has no NEXT yet: returns to NEXT discovery, finds NEXT, then evaluates ALTERNATIVE against the new pair", async () => {
      const { client, requests } = scriptedClient([
        // 1) Initial: PRIMARY "a" and its own same-family NEXT "a2" both already known.
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:38:00Z", 0, "metro"),
          rawJourney("a2", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z", 0, "metro"),
        ],
        // 2) ALTERNATIVE search's first batch: a bus that arrives even earlier than "a"
        // -- it must take over PRIMARY outright.
        [rawJourney("b", "2026-08-10T18:36:00Z", "2026-08-10T18:37:00Z", 0, "bus")],
        // 3) Retargeted NEXT search for "b" (bus): reveals a genuine same-family NEXT.
        [rawJourney("b2", "2026-08-10T18:42:00Z", "2026-08-10T18:47:00Z", 0, "bus")],
      ]);

      const spy = metricsSpy();
      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["b", "b2"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      // Neither "a" nor "a2" survive in any role -- both were fully superseded, not raced
      // against the new PRIMARY/NEXT pair.
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("a");
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("a2");
      expect(spy.events[0]!.primaryRetargets).toBe(1);
      // Four requests: initial, the ALTERNATIVE scan for (a, a2) that discovers "b" and
      // retargets, the NEXT search for "b" that finds "b2" immediately, then one final
      // ALTERNATIVE-interval request for the NEW (b, b2) pair that finds nothing further.
      expect(requests).toHaveLength(4);
    });

    it("PRIMARY changes during an ALTERNATIVE scan and the new PRIMARY already has a NEXT in the pool: no unnecessary NEXT acquisition", async () => {
      const { client, requests } = scriptedClient([
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:38:00Z", 0, "metro"),
          rawJourney("a2", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z", 0, "metro"),
        ],
        // ALTERNATIVE search's first batch reveals BOTH the new PRIMARY ("b") AND its own
        // same-family NEXT ("b2") in the very same response -- the state machine must
        // recognise "b" already has a NEXT and skip NEXT_DISCOVERY entirely.
        [
          rawJourney("b", "2026-08-10T18:36:00Z", "2026-08-10T18:37:00Z", 0, "bus"),
          rawJourney("b2", "2026-08-10T18:42:00Z", "2026-08-10T18:47:00Z", 0, "bus"),
        ],
      ]);

      const spy = metricsSpy();
      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["b", "b2"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
      expect(spy.events[0]!.primaryRetargets).toBe(1);
      // No NEXT-phase calls at all -- "b2" was already sitting in the pool the moment "b"
      // took over, so the state machine goes straight back to ALTERNATIVE_INTERVAL_
      // DISCOVERY for the new pair instead of wastefully searching for a NEXT it already
      // has.
      expect(spy.events[0]!.nextCalls).toBe(0);
      // Three requests: initial, the ALTERNATIVE scan that discovers both "b" and "b2"
      // and retargets, then one final ALTERNATIVE-interval request for the new (b, b2)
      // pair that finds nothing further.
      expect(requests).toHaveLength(3);
    });

    it("a former ALTERNATIVE candidate whose realtime update makes it the best journey overall becomes PRIMARY, never staying labelled ALTERNATIVE", async () => {
      const { client, requests } = scriptedClient([
        [
          rawJourney("a", "2026-08-10T18:35:00Z", "2026-08-10T18:40:00Z", 0, "metro"),
          rawJourney("a2", "2026-08-10T18:50:00Z", "2026-08-10T18:55:00Z", 0, "metro"),
        ],
        // ALTERNATIVE search's first batch: a bus that (for now) merely qualifies as a
        // plain alternative -- worse than "a", better than "a2".
        [rawJourney("alt", "2026-08-10T18:36:00Z", "2026-08-10T18:45:00Z", 0, "bus")],
        // A realtime update to that SAME bus reports it now arriving BETTER than "a" --
        // it must take over PRIMARY outright, not merely remain a strong alternative.
        [rawJourney("alt", "2026-08-10T18:36:00Z", "2026-08-10T18:38:00Z", 0, "bus")],
      ]);

      const spy = metricsSpy();
      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["alt"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY"]);
      expect(spy.events[0]!.primaryRetargets).toBe(1);
      expect(requests).toHaveLength(4);
    });
  });

  describe("query-scoped probe tracking across acquisition phases", () => {
    it("an ALTERNATIVE-phase bus at a minute the narrower NEXT-phase search already probed is still discovered -- the regression scenario", async () => {
      // PRIMARY metro 22:30; two bus fillers crowd the genuine same-family NEXT (metro
      // 23:30) out of the initial batch's own closest-3 window, forcing a targeted
      // NEXT_DISCOVERY search (metro-only). That search's own first request (22:30)
      // reports an ineligible metro DECOY at 22:50 (too many changes) purely to pull its
      // cursor there -- never upserted, but still real cursor-advancement information, so
      // the search's own SECOND request genuinely queries 22:50 + METRO + maxChanges=0,
      // exactly matching the reported bug. That request finally reveals the real NEXT.
      //
      // The ALTERNATIVE search that follows starts from PRIMARY's own floor again and,
      // via an analogous ineligible BROAD decoy at 22:50, ALSO ends up querying 22:50 --
      // this time with the full allowed mode set. Under the old, minute-only probe
      // tracking this second 22:50 request would have been wrongly skipped as "already
      // probed"; the bus it alone can reveal would have been permanently missed.
      const { client, requests } = scriptedClient([
        // 1) Initial broad batch.
        [
          rawJourney("metro-2230", "2026-08-10T22:30:00Z", "2026-08-10T23:00:00Z", 0, "metro"),
          rawJourney("filler-a", "2026-08-10T22:31:00Z", "2026-08-10T23:50:00Z", 0, "bus"),
          rawJourney("filler-b", "2026-08-10T22:32:00Z", "2026-08-10T23:55:00Z", 0, "bus"),
        ],
        // 2) NEXT_DISCOVERY req 1 (metro-only, anchored 22:30): an ineligible decoy at
        // 22:50 pulls the cursor there without being upserted.
        [rawJourney("metro-decoy", "2026-08-10T22:50:00Z", "2026-08-10T23:00:00Z", 5, "metro")],
        // 3) NEXT_DISCOVERY req 2 (metro-only, anchored 22:50): the genuine NEXT.
        [rawJourney("metro-2330", "2026-08-10T23:30:00Z", "2026-08-11T00:00:00Z", 0, "metro")],
        // 4) ALTERNATIVE req 1 (broad, anchored 22:30): an ineligible decoy, again at
        // 22:50, pulls the cursor there -- this time under the FULL allowed mode set, a
        // genuinely different query from batch 2's metro-only one at the SAME minute.
        [rawJourney("bus-decoy", "2026-08-10T22:50:00Z", "2026-08-10T23:40:00Z", 5, "bus")],
        // 5) ALTERNATIVE req 2 (broad, anchored 22:50): the genuine bus.
        [rawJourney("bus-2250", "2026-08-10T22:50:00Z", "2026-08-10T23:40:00Z", 0, "bus")],
        // 6) ALTERNATIVE req 3 (broad, anchored 22:51): nothing further remains.
        [],
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T02:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["metro-2230", "bus-2250", "metro-2330"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "ALTERNATIVE", "NEXT"]);
      // The ineligible decoys never leak into the response, and neither bus filler beats
      // the genuine bus (both are Pareto-dominated by it).
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("metro-decoy");
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("bus-decoy");
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("filler-a");
      expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain("filler-b");

      // Proof the scenario genuinely exercised the fix: 22:50 really was queried twice,
      // under two DIFFERENT transport-mode scopes.
      expect(requests).toHaveLength(6);
      const at2250 = requests.filter((r) => r.departureAt.toISOString() === "2026-08-10T22:50:00.000Z");
      expect(at2250).toHaveLength(2);
      expect(at2250.some((r) => !(r.transportModes ?? []).includes("BUS"))).toBe(true);
      expect(at2250.some((r) => (r.transportModes ?? []).includes("BUS"))).toBe(true);
    });
  });

  describe("journey_acquisition_metrics instrumentation", () => {
    it("counts initial, NEXT, and ALTERNATIVE SL calls separately in a normal (non-retargeting) run", async () => {
      const { client } = scriptedClient([
        [rawJourney("primary", "2026-08-10T18:35:00Z", "2026-08-10T18:38:00Z", 0, "metro")],
        [rawJourney("next", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z", 0, "metro")],
      ]);
      const spy = metricsSpy();

      await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=origin&destinationId=destination&searchUntil=2026-08-11T00:00:00Z",
      );

      expect(spy.events).toHaveLength(1);
      const metrics = spy.events[0]!;
      expect(metrics.event).toBe("journey_acquisition_metrics");
      expect(metrics.initialCalls).toBe(1);
      expect(metrics.nextCalls).toBe(1);
      expect(metrics.alternativeCalls).toBe(1);
      expect(metrics.slCalls).toBe(3);
      expect(metrics.primaryRetargets).toBe(0);
      expect(metrics.primaryChanged).toBe(false);
      expect(metrics.budgetExhausted).toBe(false);
    });

    it("never includes origin/destination identifiers, or journey identities, in the structured metrics payload", async () => {
      const { client } = worldClient([
        { id: "distinctive-journey-id-xyz", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
      ]);
      const spy = metricsSpy();

      await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
        "/?originId=secret-origin-stop-id-12345&destinationId=secret-destination-stop-id-67890",
      );

      expect(spy.events).toHaveLength(1);
      const serialized = JSON.stringify(spy.events[0]);
      expect(serialized).not.toContain("secret-origin-stop-id-12345");
      expect(serialized).not.toContain("secret-destination-stop-id-67890");
      expect(serialized).not.toContain("distinctive-journey-id-xyz");
      // Counts and booleans only -- no station names, stop ids, or journey payloads.
      expect(Object.keys(spy.events[0]!).sort()).toEqual(
        [
          "alternativeCalls",
          "budgetExhausted",
          "event",
          "initialCalls",
          "nextCalls",
          "primaryChanged",
          "primaryDiscoveryCalls",
          "primaryRetargets",
          "slCalls",
        ].sort(),
      );
    });

    it("does not expose acquisition metrics anywhere in the public API response", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
      ]);
      const spy = metricsSpy();

      const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request("/?originId=origin&destinationId=destination");
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(Object.keys(body.data).sort()).toEqual([
        "fetchedAt",
        "journeyContext",
        "journeys",
        "requestedDateTime",
        "searchMode",
      ]);
      const serializedResponse = JSON.stringify(body);
      expect(serializedResponse).not.toContain("slCalls");
      expect(serializedResponse).not.toContain("journey_acquisition_metrics");
      // The metrics sink still ran, entirely separately from the response body.
      expect(spy.events).toHaveLength(1);
    });

    it("defaults to a structured console.log line when no emitMetrics override is supplied, without throwing", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination");

      expect(response.status).toBe(200);
    });
  });

  // ---- changesPreference: DIRECT_ONLY / BOTH / WITH_CHANGES_ONLY narrow the WHOLE eligible
  // candidate pool inside CandidateCollector.fetchBatch, before PRIMARY/NEXT/ALTERNATIVE are
  // ever selected (see createJourneyRoutes's own "Changes preference" doc) -- so these tests
  // prove real re-ranking over a genuinely smaller eligible set, never a fixed BOTH-ranked
  // result with disallowed rows merely hidden from the response afterward. ----

  describe("changesPreference", () => {
    it("rejects an unrecognized changesPreference value", async () => {
      const client: SlJourneyPlannerClient = {
        async searchStops() {
          return [];
        },
        async trips() {
          return [];
        },
      };
      const app = new Hono();
      app.onError(onError);
      app.route("/", createJourneyRoutes(client, FIXED_NOW));

      const response = await app.request("/?originId=origin&destinationId=destination&changesPreference=NONSENSE");
      expect(response.status).toBe(400);
    });

    it("an absent changesPreference behaves exactly like an explicit BOTH", async () => {
      const world = [
        { id: "fast-with-changes", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 1, mode: "bus" as const },
        { id: "direct-slower", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:45:00Z", interchanges: 0, mode: "metro" as const },
      ];
      const { client: absentClient } = worldClient(world);
      const { client: bothClient } = worldClient(world);

      const absentResponse = await createJourneyRoutes(absentClient, FIXED_NOW).request("/?originId=origin&destinationId=destination");
      const bothResponse = await createJourneyRoutes(bothClient, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=BOTH",
      );
      const absentBody = (await absentResponse.json()) as SuccessEnvelope<JourneyResponse>;
      const bothBody = (await bothResponse.json()) as SuccessEnvelope<JourneyResponse>;

      expect(bothBody.data.journeys.map((journey) => journey.journeyId)).toEqual(
        absentBody.data.journeys.map((journey) => journey.journeyId),
      );
      // The earliest-arrival journey overall wins PRIMARY regardless of its own transfer
      // count -- BOTH (explicit or defaulted) never narrows the pool at all.
      expect(absentBody.data.journeys[0]!.journeyId).toBe("fast-with-changes");
    });

    it("DIRECT_ONLY excludes a with-changes journey from PRIMARY selection entirely, promoting the genuinely best direct one instead", async () => {
      const { client } = worldClient([
        // Earliest arrival overall, but requires a change -- must never win PRIMARY, or
        // appear anywhere in the response, under DIRECT_ONLY.
        { id: "fast-with-changes", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 1, mode: "bus" },
        { id: "direct-slower", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:45:00Z", interchanges: 0, mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      // The direct journey is correctly promoted to PRIMARY -- proof this is a genuinely
      // smaller pool being re-ranked, not a BOTH-ranked result with the disallowed row
      // merely hidden afterward (which would have left "fast-with-changes" as PRIMARY, or
      // removed it from an otherwise BOTH-shaped list leaving no PRIMARY at all).
      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["direct-slower"]);
      expect(body.data.journeys[0]!.role).toBe("PRIMARY");
      expect(body.data.journeys[0]!.transferCount).toBe(0);
      expect(body.data.journeys.every((journey) => journey.transferCount === 0)).toBe(true);
    });

    it("DIRECT_ONLY with no direct journey available in the world returns nothing, never a with-changes fallback", async () => {
      const { client } = worldClient([
        { id: "only-option", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 1, mode: "bus" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys).toEqual([]);
    });

    it("DIRECT_ONLY correctly derives NEXT from the direct-only pool, skipping a closer-departing with-changes candidate", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 0, mode: "metro" },
        // Departs before the genuine direct NEXT and would otherwise win NEXT (earliest
        // compatible departure) -- must never be selected under DIRECT_ONLY.
        { id: "closer-with-changes", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:55:00Z", interchanges: 1, mode: "bus" },
        { id: "direct-next", departure: "2026-08-10T18:50:00Z", arrival: "2026-08-10T19:05:00Z", interchanges: 0, mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY&searchUntil=2026-08-10T20:00:00Z",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "direct-next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
    });

    it("DIRECT_ONLY excludes a route-incompatible with-changes journey that would otherwise qualify as ALTERNATIVE", async () => {
      const { client } = worldClient([
        { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 0, mode: "metro" },
        { id: "next", departure: "2026-08-10T18:45:00Z", arrival: "2026-08-10T18:55:00Z", interchanges: 0, mode: "metro" },
        // Departs strictly between primary/next and arrives strictly before next -- the
        // exact ALTERNATIVE eligibility window (see the equivalent BOTH-mode test above),
        // but requires a change, so DIRECT_ONLY must never let it through.
        { id: "alt-with-changes", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 1, mode: "bus" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "next"]);
      expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
    });

    it("WITH_CHANGES_ONLY excludes a direct journey from PRIMARY selection entirely, promoting the genuinely best with-changes one instead", async () => {
      const { client } = worldClient([
        // Earliest arrival overall, but direct -- must never win PRIMARY, or appear
        // anywhere in the response, under WITH_CHANGES_ONLY.
        { id: "fast-direct", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 0, mode: "metro" },
        { id: "slower-with-changes", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:45:00Z", interchanges: 1, mode: "bus" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["slower-with-changes"]);
      expect(body.data.journeys[0]!.role).toBe("PRIMARY");
      expect(body.data.journeys[0]!.transferCount).toBe(1);
      expect(body.data.journeys.every((journey) => journey.transferCount >= 1)).toBe(true);
    });

    it("WITH_CHANGES_ONLY with no with-changes journey available in the world returns nothing, never a direct fallback", async () => {
      const { client } = worldClient([
        { id: "only-option", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 0, mode: "metro" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys).toEqual([]);
    });

    it("WITH_CHANGES_ONLY still enforces the existing MAX_CHANGES=2 ceiling", async () => {
      const { client } = worldClient([
        { id: "three-changes", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 3, mode: "bus" },
        { id: "two-changes", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:45:00Z", interchanges: 2, mode: "bus" },
      ]);

      const response = await createJourneyRoutes(client, FIXED_NOW).request(
        "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY",
      );
      const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

      expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["two-changes"]);
    });
  });

  // ---- Candidate crowd-out regressions: SL only ever returns up to 3 best-match trips per
  // request, with no notion of changesPreference at all -- an unfiltered request can let 3
  // disallowed candidates fill every slot and silently crowd a genuinely eligible one out of
  // the batch entirely before Blick's own pool-level filter ever sees it. See
  // createJourneyRoutes's own "Changes preference" doc for the two complementary fixes these
  // tests prove: DIRECT_ONLY narrows the SL REQUEST itself (requestMaxChanges), while
  // WITH_CHANGES_ONLY (which cannot be narrowed the same way -- SL has no "minimum changes"
  // parameter) instead keeps searching forward via resolveSelection's own PRIMARY_DISCOVERY
  // phase. ----

  describe("candidate crowd-out regressions", () => {
    describe("DIRECT_ONLY", () => {
      it("requests max_changes=0 from SL for the initial batch", async () => {
        const { client, requests } = worldClient([
          { id: "direct", departure: "2026-08-10T08:00:00Z", arrival: "2026-08-10T08:20:00Z", interchanges: 0 },
        ]);

        await createJourneyRoutes(client, FIXED_NOW).request("/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY");

        expect(requests[0]!.maxChanges).toBe(0);
      });

      it("discovers a direct journey crowded out of SL's own top-3 by three earlier transfer journeys, never letting a transfer journey leak into the result", async () => {
        const { client, requests } = worldClient([
          // Under an UNFILTERED (maxChanges=2) request these three -- all earlier-departing
          // than "direct" -- would be exactly SL's own top-3 best-match picks, crowding
          // "direct" out of the batch entirely before Blick's own filter ever saw it. This
          // is the production example from this fix's own bug report.
          { id: "transfer-1", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 1, mode: "bus" },
          { id: "transfer-2", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:51:00Z", interchanges: 1, mode: "bus" },
          { id: "transfer-3", departure: "2026-08-10T18:37:00Z", arrival: "2026-08-10T18:52:00Z", interchanges: 1, mode: "bus" },
          { id: "direct", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:55:00Z", interchanges: 0, mode: "metro" },
        ]);

        const response = await createJourneyRoutes(client, FIXED_NOW).request(
          "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        // Proof this genuinely exercises max_changes=0, not merely a lucky ranking: had the
        // request asked broadly, worldClient's own top-3-by-departure would have been
        // transfer-1/2/3, exactly the crowd-out this fix prevents.
        expect(requests[0]!.maxChanges).toBe(0);
        expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["direct"]);
        expect(body.data.journeys[0]!.role).toBe("PRIMARY");
        expect(body.data.journeys.every((journey) => journey.transferCount === 0)).toBe(true);
        for (const id of ["transfer-1", "transfer-2", "transfer-3"]) {
          expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain(id);
        }
      });

      it("a later direct journey becomes NEXT once the crowded-out PRIMARY is correctly discovered, with every request (initial and targeted) asking for max_changes=0", async () => {
        const { client, requests } = worldClient([
          { id: "transfer-1", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 1, mode: "bus" },
          { id: "transfer-2", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:51:00Z", interchanges: 1, mode: "bus" },
          { id: "transfer-3", departure: "2026-08-10T18:37:00Z", arrival: "2026-08-10T18:52:00Z", interchanges: 1, mode: "bus" },
          { id: "direct-primary", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:55:00Z", interchanges: 0, mode: "metro" },
          { id: "direct-next", departure: "2026-08-10T18:55:00Z", arrival: "2026-08-10T19:10:00Z", interchanges: 0, mode: "metro" },
        ]);

        const response = await createJourneyRoutes(client, FIXED_NOW).request(
          "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY&searchUntil=2026-08-10T20:00:00Z",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["direct-primary", "direct-next"]);
        expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
        expect(body.data.journeys.every((journey) => journey.transferCount === 0)).toBe(true);
        expect(requests.length).toBeGreaterThan(1);
        expect(requests.every((request) => request.maxChanges === 0)).toBe(true);
      });

      it("never lets a transfer journey qualify as ALTERNATIVE -- the ALTERNATIVE search itself also requests max_changes=0", async () => {
        const { client, requests } = worldClient([
          { id: "primary", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", interchanges: 0, mode: "metro" },
          { id: "next", departure: "2026-08-10T18:45:00Z", arrival: "2026-08-10T18:55:00Z", interchanges: 0, mode: "metro" },
          // Departs strictly between primary/next and arrives strictly before next -- the
          // exact ALTERNATIVE eligibility window -- but requires a change, so it must never
          // be returned by SL for this search at all under DIRECT_ONLY.
          { id: "alt-with-changes", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 1, mode: "bus" },
        ]);

        const response = await createJourneyRoutes(client, FIXED_NOW).request(
          "/?originId=origin&destinationId=destination&changesPreference=DIRECT_ONLY",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["primary", "next"]);
        expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
        expect(requests.every((request) => request.maxChanges === 0)).toBe(true);
      });
    });

    describe("WITH_CHANGES_ONLY", () => {
      it("continues bounded discovery and finds a with-changes journey when the initial batch's top-3 SL results are all direct, across multiple direct-only batches", async () => {
        const { client, requests } = worldClient([
          // Three direct decoys, each earlier-departing than the eligible with-changes
          // journey -- crowds it out of not just the initial batch, but a SECOND forward
          // probe too (see this test's own request-count assertion below), before a third
          // probe finally narrows past all three.
          { id: "direct-1", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 0, mode: "metro" },
          { id: "direct-2", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:51:00Z", interchanges: 0, mode: "metro" },
          { id: "direct-3", departure: "2026-08-10T18:37:00Z", arrival: "2026-08-10T18:52:00Z", interchanges: 0, mode: "metro" },
          { id: "with-changes", departure: "2026-08-10T18:40:00Z", arrival: "2026-08-10T19:10:00Z", interchanges: 1, mode: "bus" },
        ]);
        const spy = metricsSpy();

        const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
          "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY&searchUntil=2026-08-10T20:00:00Z",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["with-changes"]);
        expect(body.data.journeys[0]!.role).toBe("PRIMARY");
        expect(body.data.journeys.every((journey) => journey.transferCount >= 1)).toBe(true);
        for (const id of ["direct-1", "direct-2", "direct-3"]) {
          expect(body.data.journeys.map((journey) => journey.journeyId)).not.toContain(id);
        }
        // The bounded PRIMARY-discovery search genuinely ran and spent real budget finding
        // it -- this did not come from the initial batch alone.
        expect(spy.events[0]!.primaryDiscoveryCalls).toBeGreaterThan(0);
        // Every request up through the one that actually found "with-changes" (the initial
        // batch plus PRIMARY_DISCOVERY's own targeted ones) asked SL for the full
        // MAX_CHANGES ceiling (2), never 0 -- unlike DIRECT_ONLY, WITH_CHANGES_ONLY cannot
        // narrow the request itself, only keep searching until the response contains
        // something eligible. (The NEXT_DISCOVERY search that follows, once "with-changes"
        // itself becomes PRIMARY, correctly narrows to ITS OWN transferCount of 1 instead --
        // unrelated, pre-existing, same-route-family targeting, not asserted on here.)
        const discoveryRequests = requests.slice(0, spy.events[0]!.initialCalls + spy.events[0]!.primaryDiscoveryCalls);
        expect(discoveryRequests.every((request) => request.maxChanges === 2)).toBe(true);
        // Genuinely multiple real batches were needed (never a single lucky one), and no two
        // ever repeat the exact same request-minute bucket -- PRIMARY_DISCOVERY's own first
        // probe (requestedAt's bucket) is a genuine duplicate of the initial batch and is
        // skipped rather than counted here.
        expect(requests.length).toBeGreaterThan(2);
        const buckets = requests.map((request) => request.departureAt.getTime());
        expect(new Set(buckets).size).toBe(buckets.length);
      });

      it("the search horizon is exhausted with no with-changes journey anywhere -- a legitimate empty result, never a crash or a direct fallback", async () => {
        const { client } = worldClient([
          { id: "direct-only", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 0, mode: "metro" },
        ]);

        const response = await createJourneyRoutes(client, FIXED_NOW).request(
          "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY&searchUntil=2026-08-10T19:00:00Z",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        expect(body.data.journeys).toEqual([]);
      });

      it("fails closed with no PRIMARY-discovery search at all when there is no searchUntil boundary to search within", async () => {
        const { client, requests } = worldClient([
          { id: "direct-only", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 0, mode: "metro" },
        ]);

        const response = await createJourneyRoutes(client, FIXED_NOW).request(
          "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        expect(body.data.journeys).toEqual([]);
        // Only the one, single initial request -- never an unbounded forward search invented
        // in the absence of a real boundary, mirroring NEXT_DISCOVERY's own identical rule.
        expect(requests).toHaveLength(1);
      });

      it("PRIMARY-discovery terminates safely once the shared request budget is exhausted, never finding a with-changes journey that doesn't exist", async () => {
        let callCount = 0;
        const client: SlJourneyPlannerClient = {
          async searchStops() {
            return [];
          },
          async trips(request) {
            callCount++;
            // Every response is a genuine, always-advancing DIRECT journey -- real forward
            // progress every time (never a repeated or genuinely empty response, so only
            // the shared budget can end this search), and never a with-changes one, so this
            // world genuinely has nothing WITH_CHANGES_ONLY could ever find.
            const departure = new Date(request.departureAt.getTime() + 60_000);
            return [rawJourney(`direct-${callCount}`, departure.toISOString(), "2026-08-15T00:00:00Z", 0, "metro")];
          },
        };
        const spy = metricsSpy();

        const response = await createJourneyRoutes(client, FIXED_NOW, spy.emit).request(
          "/?originId=origin&destinationId=destination&changesPreference=WITH_CHANGES_ONLY&searchUntil=2026-08-20T00:00:00Z",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        expect(body.data.journeys).toEqual([]);
        expect(callCount).toBe(MAX_ACQUISITION_BATCHES);
        expect(spy.events[0]!.slCalls).toBe(MAX_ACQUISITION_BATCHES);
        expect(spy.events[0]!.budgetExhausted).toBe(true);
      });
    });

    describe("BOTH", () => {
      it("existing PRIMARY/NEXT/ALTERNATIVE selection is unaffected by this fix -- requestMaxChanges(BOTH) is still exactly MAX_CHANGES", async () => {
        const { client, requests } = worldClient([
          { id: "metro-1835", departure: "2026-08-10T18:35:00Z", arrival: "2026-08-10T18:38:00Z", mode: "metro" },
          { id: "detour-1836", departure: "2026-08-10T18:36:00Z", arrival: "2026-08-10T18:50:00Z", interchanges: 1, mode: "bus" },
          { id: "metro-1839", departure: "2026-08-10T18:39:00Z", arrival: "2026-08-10T18:42:00Z", mode: "metro" },
        ]);

        const response = await createJourneyRoutes(client, FIXED_NOW).request(
          "/?originId=origin&destinationId=destination&changesPreference=BOTH",
        );
        const body = (await response.json()) as SuccessEnvelope<JourneyResponse>;

        // Same outcome as the equivalent unfiltered (no changesPreference at all) test above:
        // the later, faster, simpler metro Pareto-dominates the one-change detour outright.
        expect(body.data.journeys.map((journey) => journey.journeyId)).toEqual(["metro-1835", "metro-1839"]);
        expect(body.data.journeys.map((journey) => journey.role)).toEqual(["PRIMARY", "NEXT"]);
        expect(requests[0]!.maxChanges).toBe(MAX_CHANGES);
      });
    });
  });
});
