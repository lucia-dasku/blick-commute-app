import { describe, expect, it } from "vitest";
import departuresFixture from "../fixtures/slTransportDeparturesSlussen.sample.json" with { type: "json" };
import { runLiveCommuteTick } from "../src/liveCommute/engine.js";
import {
  createLiveCommuteSession,
  type ExactDestinationLiveQueryInput,
  type LineDirectionLiveQueryInput,
  type LiveCommuteSession,
  type LiveCommuteSessionInput,
  type PublicationKey,
} from "../src/liveCommute/model.js";
import type { LiveCommutePreviousSnapshotSource } from "../src/liveCommute/ports.js";
import type { LiveCommuteSnapshot } from "../src/liveCommute/snapshot.js";
import type {
  RawJourneyPlannerJourney,
  SlJourneyPlannerClient,
  TripsRequest,
} from "../src/services/slJourneyPlannerClient.js";
import type { SlTransportClient } from "../src/services/slTransportClient.js";
import type { RawDeparturesResponse } from "../src/services/upstreamTypes.js";

const NOW = new Date("2026-07-04T15:32:00.000Z");
const STARTS_AT = new Date("2026-07-04T15:00:00.000Z");
const ENDS_AT = new Date("2026-07-04T16:30:00.000Z");

function lineQuery(
  overrides: Partial<LineDirectionLiveQueryInput> = {},
): LineDirectionLiveQueryInput {
  return {
    kind: "LINE_DIRECTION",
    siteId: 9192,
    transportMode: "BUS",
    lineId: 57,
    directionCode: 2,
    ...overrides,
  };
}

function exactQuery(
  overrides: Partial<ExactDestinationLiveQueryInput> = {},
): ExactDestinationLiveQueryInput {
  return {
    kind: "EXACT_DESTINATION",
    originId: "origin",
    destinationId: "destination",
    transportModes: ["METRO", "BUS"],
    changesPreference: "BOTH",
    searchUntil: NOW,
    ...overrides,
  };
}

function session(
  overrides: Partial<LiveCommuteSessionInput> = {},
): LiveCommuteSession {
  return createLiveCommuteSession({
    sessionId: "session-1",
    installationId: "installation-1",
    routineId: "routine-1",
    startsAt: STARTS_AT,
    endsAt: ENDS_AT,
    query: lineQuery(),
    ...overrides,
  });
}

function fixedClock(value: Date): () => Date {
  return () => new Date(value);
}

function sequenceClock(values: readonly Date[]): () => Date {
  let index = 0;
  return () => {
    const value = values[Math.min(index, values.length - 1)]!;
    index++;
    return new Date(value);
  };
}

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
  readonly reject: (reason: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

function gatedTransportWorld(
  response: RawDeparturesResponse = departuresFixture as unknown as RawDeparturesResponse,
): {
  readonly client: SlTransportClient;
  readonly calls: number[];
  resolve(siteId: number): void;
  reject(siteId: number, reason: unknown): void;
} {
  const calls: number[] = [];
  const gates = new Map<number, Deferred<RawDeparturesResponse>>();
  return {
    calls,
    client: {
      async fetchAllSites() {
        return [];
      },
      async fetchStopPoints() {
        return [];
      },
      fetchDepartures(siteId) {
        calls.push(siteId);
        const gate = deferred<RawDeparturesResponse>();
        gates.set(siteId, gate);
        return gate.promise;
      },
    },
    resolve(siteId) {
      const gate = gates.get(siteId);
      if (gate == null) throw new Error(`site ${siteId} was not requested`);
      gate.resolve(response);
    },
    reject(siteId, reason) {
      const gate = gates.get(siteId);
      if (gate == null) throw new Error(`site ${siteId} was not requested`);
      gate.reject(reason);
    },
  };
}

function transportWorld(options: {
  failSites?: ReadonlySet<number>;
  response?: RawDeparturesResponse;
} = {}): { client: SlTransportClient; calls: number[] } {
  const calls: number[] = [];
  const response =
    options.response ??
    (departuresFixture as unknown as RawDeparturesResponse);
  return {
    calls,
    client: {
      async fetchAllSites() {
        return [];
      },
      async fetchStopPoints() {
        return [];
      },
      async fetchDepartures(siteId) {
        calls.push(siteId);
        if (options.failSites?.has(siteId)) throw new Error("upstream unavailable");
        return response;
      },
    },
  };
}

function unusedJourneyClient(): SlJourneyPlannerClient {
  return {
    async searchStops() {
      return [];
    },
    async trips() {
      throw new Error("journey acquisition was not expected");
    },
  };
}

function rawJourney(
  id: string,
  departure: string,
  arrival: string,
  mode: "metro" | "bus",
): RawJourneyPlannerJourney {
  return {
    tripId: id,
    interchanges: 0,
    legs: [
      {
        origin: {
          id: "origin-stop",
          name: "T-Centralen",
          departureTimeEstimated: departure,
        },
        destination: {
          id: "destination-stop",
          name: "Mariatorget",
          arrivalTimeEstimated: arrival,
        },
        transportation: {
          disassembledName: mode === "metro" ? "14" : "135",
          product: {
            class: mode === "metro" ? 2 : 5,
            name: mode === "metro" ? "Tunnelbana" : "Buss",
          },
          destination: { name: "Mariatorget" },
        },
        infos: [],
      },
    ],
  } as unknown as RawJourneyPlannerJourney;
}

function journeyWorld(
  firstBatch: readonly RawJourneyPlannerJourney[],
): { client: SlJourneyPlannerClient; requests: TripsRequest[] } {
  const requests: TripsRequest[] = [];
  return {
    requests,
    client: {
      async searchStops() {
        return [];
      },
      async trips(request) {
        requests.push(request);
        return requests.length === 1 ? [...firstBatch] : [];
      },
    },
  };
}

function previousSource(
  entries: readonly (readonly [PublicationKey, LiveCommuteSnapshot])[],
): LiveCommutePreviousSnapshotSource {
  const snapshots = new Map(entries);
  return {
    getPreviousSnapshot(key) {
      return snapshots.get(key);
    },
  };
}

describe("one live-commute tick", () => {
  it("acquires one site response for 100 sessions and projects distinct publication states", async () => {
    const sourceBefore = JSON.stringify(departuresFixture);
    const variants = [
      lineQuery(),
      lineQuery({ transportMode: "METRO", lineId: null, directionCode: null }),
      lineQuery({ lineId: 3, directionCode: 1 }),
      lineQuery({ lineId: null, directionCode: null }),
    ];
    const sessions = Array.from({ length: 100 }, (_, index) =>
      session({
        sessionId: `session-${index}`,
        installationId: `installation-${index}`,
        routineId: `routine-${index}`,
        query: variants[index % variants.length]!,
      }),
    );
    const transport = transportWorld();

    const result = await runLiveCommuteTick({
      sessions,
      now: fixedClock(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(transport.calls).toEqual([9192]);
    expect(result.acquisitions).toHaveLength(1);
    expect(result.acquisitions[0]?.status).toBe("ACQUIRED");
    expect(result.publications).toHaveLength(4);
    expect(
      result.publications
        .filter((publication) => publication.status === "READY")
        .map((publication) => publication.snapshot.kind === "LINE_DIRECTION"
          ? publication.snapshot.departures.length
          : -1)
        .sort(),
    ).toEqual([1, 1, 1, 2]);

    const lineSnapshots = result.publications.flatMap((publication) =>
      publication.status === "READY" &&
      publication.group.query.kind === "LINE_DIRECTION" &&
      publication.snapshot.kind === "LINE_DIRECTION"
        ? [{ query: publication.group.query, snapshot: publication.snapshot }]
        : [],
    );
    const line57 = lineSnapshots.find(({ query }) => query.lineId === 57);
    const wildcard = lineSnapshots.find(
      ({ query }) => query.lineId == null && query.directionCode == null && query.transportMode === "BUS",
    );
    expect(line57).toBeDefined();
    expect(wildcard).toBeDefined();
    const sharedDepartureId = line57?.snapshot.departures[0]?.departureId;
    expect(sharedDepartureId).toBeDefined();
    expect(wildcard?.snapshot.departures.find(({ departureId }) => departureId === sharedDepartureId)).not.toBe(
      line57?.snapshot.departures[0],
    );
    expect(Object.isFrozen(line57?.snapshot)).toBe(true);
    expect(Object.isFrozen(line57?.snapshot.departures)).toBe(true);
    expect(JSON.stringify(departuresFixture)).toBe(sourceBefore);
  });

  it("does not share departure acquisition across different sites", async () => {
    const transport = transportWorld();
    const result = await runLiveCommuteTick({
      sessions: [
        session({ sessionId: "site-1" }),
        session({ sessionId: "site-2", query: lineQuery({ siteId: 9300 }) }),
      ],
      now: fixedClock(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(transport.calls.sort()).toEqual([9192, 9300]);
    expect(result.acquisitions).toHaveLength(2);
  });

  it("makes zero acquisitions for future and expired sessions", async () => {
    const transport = transportWorld();
    const result = await runLiveCommuteTick({
      sessions: [
        session({
          sessionId: "future",
          startsAt: new Date("2026-07-04T15:33:00.000Z"),
        }),
        session({
          sessionId: "expired",
          startsAt: new Date("2026-07-04T14:00:00.000Z"),
          endsAt: NOW,
        }),
      ],
      now: fixedClock(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(transport.calls).toEqual([]);
    expect(result.acquisitions).toEqual([]);
    expect(result.publications).toEqual([]);
  });

  it("rechecks lifecycle after acquisition and omits a newly expired session", async () => {
    const transport = transportWorld();
    const endsDuringRequest = new Date("2026-07-04T15:32:00.500Z");
    const result = await runLiveCommuteTick({
      sessions: [session({ endsAt: endsDuringRequest })],
      now: sequenceClock([NOW, endsDuringRequest]),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(transport.calls).toEqual([9192]);
    expect(result.acquisitions[0]?.status).toBe("ACQUIRED");
    expect(result.publications).toEqual([]);
  });

  it("drops a departure that expires while its acquisition is in flight", async () => {
    const transport = transportWorld();
    const afterDeparture = new Date("2026-07-04T15:35:00.000Z");
    const result = await runLiveCommuteTick({
      sessions: [session()],
      now: sequenceClock([NOW, afterDeparture]),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });
    const publication = result.publications[0];

    expect(publication?.status).toBe("READY");
    if (
      publication == null ||
      publication.status !== "READY" ||
      publication.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected line publication");
    }
    expect(publication.group.validatedAt).toBe(afterDeparture.toISOString());
    expect(publication.snapshot.generatedAt).toBe(afterDeparture.toISOString());
    expect(publication.snapshot.departures).toEqual([]);
  });

  it("projects every group at the final clock after a slower sibling acquisition", async () => {
    const transport = gatedTransportWorld();
    const fastCompletion = new Date("2026-07-04T15:32:10.000Z");
    const finalPublication = new Date("2026-07-04T15:35:00.000Z");
    let currentTime = NOW;
    const clockReads: string[] = [];
    const pending = runLiveCommuteTick({
      sessions: [
        session({ sessionId: "fast" }),
        session({
          sessionId: "slow",
          query: lineQuery({ siteId: 9300, lineId: 3, directionCode: 1 }),
        }),
      ],
      now: () => {
        clockReads.push(currentTime.toISOString());
        return new Date(currentTime);
      },
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(transport.calls).toEqual([9192, 9300]);
    currentTime = fastCompletion;
    transport.resolve(9192);
    await Promise.resolve();
    await Promise.resolve();
    expect(clockReads).toContain(fastCompletion.toISOString());

    currentTime = finalPublication;
    transport.resolve(9300);
    const result = await pending;
    const fast = result.publications.find(
      (publication) => publication.group.query.kind === "LINE_DIRECTION" && publication.group.query.siteId === 9192,
    );
    const slow = result.publications.find(
      (publication) => publication.group.query.kind === "LINE_DIRECTION" && publication.group.query.siteId === 9300,
    );

    expect(fast?.status).toBe("READY");
    expect(slow?.status).toBe("READY");
    if (
      fast == null ||
      fast.status !== "READY" ||
      fast.snapshot.kind !== "LINE_DIRECTION" ||
      slow == null ||
      slow.status !== "READY" ||
      slow.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected line publications");
    }
    expect(fast.snapshot.generatedAt).toBe(finalPublication.toISOString());
    expect(fast.snapshot.sourceFetchedAt).toBe(fastCompletion.toISOString());
    expect(fast.snapshot.departures).toEqual([]);
    expect(slow.snapshot.departures).toHaveLength(1);
    expect(result.acquisitions.find(({ key }) => key === fast.group.acquisitionKey)?.completedAt).toBe(
      fastCompletion.toISOString(),
    );
  });

  it("omits a fast group's session if it ends while a slower sibling is still acquiring", async () => {
    const transport = gatedTransportWorld();
    const fastCompletion = new Date("2026-07-04T15:32:10.000Z");
    const finalPublication = new Date("2026-07-04T15:35:00.000Z");
    let currentTime = NOW;
    const pending = runLiveCommuteTick({
      sessions: [
        session({
          sessionId: "fast",
          endsAt: new Date("2026-07-04T15:34:00.000Z"),
        }),
        session({
          sessionId: "slow",
          query: lineQuery({ siteId: 9300, lineId: 3, directionCode: 1 }),
        }),
      ],
      now: () => new Date(currentTime),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    currentTime = fastCompletion;
    transport.resolve(9192);
    await Promise.resolve();
    await Promise.resolve();
    currentTime = finalPublication;
    transport.resolve(9300);
    const result = await pending;

    expect(result.acquisitions).toHaveLength(2);
    expect(result.publications).toHaveLength(1);
    expect(result.publications[0]?.group.sessions.map(({ sessionId }) => sessionId)).toEqual([
      "slow",
    ]);
  });

  it("isolates one failed acquisition from an unrelated successful site", async () => {
    const transport = transportWorld({ failSites: new Set([9300]) });
    const result = await runLiveCommuteTick({
      sessions: [
        session({ sessionId: "working" }),
        session({ sessionId: "failed", query: lineQuery({ siteId: 9300 }) }),
      ],
      now: fixedClock(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(result.acquisitions.map((acquisition) => acquisition.status).sort()).toEqual([
      "ACQUIRED",
      "ACQUISITION_FAILED",
    ]);
    expect(result.publications.map((publication) => publication.status).sort()).toEqual([
      "ACQUISITION_FAILED",
      "READY",
    ]);
    expect(JSON.stringify(result)).not.toContain("upstream unavailable");
  });

  it("keeps fresh snapshots ready when optional publication history fails", async () => {
    const transport = transportWorld();
    let historyReads = 0;
    const result = await runLiveCommuteTick({
      sessions: [
        session({ sessionId: "line-57" }),
        session({
          sessionId: "line-3",
          query: lineQuery({ lineId: 3, directionCode: 1 }),
        }),
      ],
      now: fixedClock(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: {
        getPreviousSnapshot() {
          historyReads++;
          if (historyReads === 1) throw new Error("history unavailable");
          return undefined;
        },
      },
    });

    expect(result.acquisitions.map(({ status }) => status)).toEqual(["ACQUIRED"]);
    expect(result.publications.map(({ status }) => status)).toEqual(["READY", "READY"]);
    expect(result.publications.every(
      (publication) => publication.status === "READY" && publication.contentChanged,
    )).toBe(true);
  });

  it("reprojects a previous snapshot as stale after a failed acquisition", async () => {
    const firstTransport = transportWorld();
    const first = await runLiveCommuteTick({
      sessions: [session({ query: lineQuery({ lineId: null, directionCode: null }) })],
      now: fixedClock(NOW),
      transportClient: firstTransport.client,
      journeyClient: unusedJourneyClient(),
    });
    const ready = first.publications[0];
    expect(ready?.status).toBe("READY");
    if (ready == null || ready.status !== "READY") throw new Error("expected fresh publication");

    const later = new Date("2026-07-04T15:35:00.000Z");
    const failingTransport = transportWorld({ failSites: new Set([9192]) });
    const second = await runLiveCommuteTick({
      sessions: [session({ query: lineQuery({ lineId: null, directionCode: null }) })],
      now: fixedClock(later),
      transportClient: failingTransport.client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[ready.group.key, ready.snapshot]]),
    });
    const stale = second.publications[0];

    expect(stale?.status).toBe("READY_STALE");
    if (stale == null || stale.status !== "READY_STALE") {
      throw new Error("expected stale publication");
    }
    expect(stale.snapshot.sourceFetchedAt).toBe(ready.snapshot.sourceFetchedAt);
    expect(stale.snapshot.freshness).toBe("STALE");
    expect(stale.snapshot.kind).toBe("LINE_DIRECTION");
    if (stale.snapshot.kind === "LINE_DIRECTION") {
      expect(stale.snapshot.departures.every(
        (departure) => Date.parse(departure.effectiveTime) >= later.getTime(),
      )).toBe(true);
    }
  });

  it("detects a freshness-only transition without changing timetable content", async () => {
    const line = session({ query: lineQuery({ lineId: null, directionCode: null }) });
    const firstTransport = transportWorld();
    const first = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(NOW),
      transportClient: firstTransport.client,
      journeyClient: unusedJourneyClient(),
    });
    const ready = first.publications[0];
    if (ready == null || ready.status !== "READY") throw new Error("expected fresh publication");

    const failingTransport = transportWorld({ failSites: new Set([9192]) });
    const second = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(NOW),
      transportClient: failingTransport.client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[ready.group.key, ready.snapshot]]),
    });
    const stale = second.publications[0];

    expect(stale?.status).toBe("READY_STALE");
    if (
      stale == null ||
      stale.status !== "READY_STALE" ||
      stale.snapshot.kind !== "LINE_DIRECTION" ||
      ready.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected stale line publication");
    }
    expect(stale.snapshot.departures).toEqual(ready.snapshot.departures);
    expect(stale.snapshot.sourceFetchedAt).toBe(ready.snapshot.sourceFetchedAt);
    expect(stale.contentChanged).toBe(true);
  });

  it("does not use fallback stored under a different complete publication key", async () => {
    const original = session({ query: lineQuery({ lineId: null, directionCode: null }) });
    const first = await runLiveCommuteTick({
      sessions: [original],
      now: fixedClock(NOW),
      transportClient: transportWorld().client,
      journeyClient: unusedJourneyClient(),
    });
    const ready = first.publications[0];
    if (ready == null || ready.status !== "READY") throw new Error("expected fresh publication");

    const differentPublication = session({
      query: lineQuery({ lineId: 57, directionCode: 2 }),
    });
    const failed = await runLiveCommuteTick({
      sessions: [differentPublication],
      now: fixedClock(NOW),
      transportClient: transportWorld({ failSites: new Set([9192]) }).client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[ready.group.key, ready.snapshot]]),
    });

    expect(failed.publications.map(({ status }) => status)).toEqual([
      "ACQUISITION_FAILED",
    ]);
  });

  it("lets a fresh empty result replace history and never resurrects the older rows", async () => {
    const line = session({ query: lineQuery({ lineId: null, directionCode: null }) });
    const first = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(NOW),
      transportClient: transportWorld().client,
      journeyClient: unusedJourneyClient(),
    });
    const populated = first.publications[0];
    if (
      populated == null ||
      populated.status !== "READY" ||
      populated.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected populated line publication");
    }
    expect(populated.snapshot.departures.length).toBeGreaterThan(0);

    const emptyTime = new Date("2026-07-04T15:32:30.000Z");
    const emptyResponse: RawDeparturesResponse = { departures: [], stop_deviations: [] };
    const second = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(emptyTime),
      transportClient: transportWorld({ response: emptyResponse }).client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[populated.group.key, populated.snapshot]]),
    });
    const empty = second.publications[0];
    if (
      empty == null ||
      empty.status !== "READY" ||
      empty.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected fresh empty publication");
    }
    expect(empty.snapshot.departures).toEqual([]);
    expect(empty.contentChanged).toBe(true);

    const failedTime = new Date("2026-07-04T15:32:45.000Z");
    const third = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(failedTime),
      transportClient: transportWorld({ failSites: new Set([9192]) }).client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[empty.group.key, empty.snapshot]]),
    });
    const staleEmpty = third.publications[0];

    expect(staleEmpty?.status).toBe("READY_STALE");
    if (
      staleEmpty == null ||
      staleEmpty.status !== "READY_STALE" ||
      staleEmpty.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected stale empty publication");
    }
    expect(staleEmpty.snapshot.departures).toEqual([]);
    expect(staleEmpty.snapshot.freshness).toBe("STALE");
  });

  it("re-filters a failed fast group's fallback after a slower sibling finishes", async () => {
    const fallbackSession = session({
      sessionId: "fallback",
      query: lineQuery({ lineId: null, directionCode: null }),
    });
    const initial = await runLiveCommuteTick({
      sessions: [fallbackSession],
      now: fixedClock(NOW),
      transportClient: transportWorld().client,
      journeyClient: unusedJourneyClient(),
    });
    const ready = initial.publications[0];
    if (
      ready == null ||
      ready.status !== "READY" ||
      ready.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected fresh fallback source");
    }
    expect(ready.snapshot.departures).toHaveLength(2);

    const transport = gatedTransportWorld();
    const fastFailure = new Date("2026-07-04T15:35:00.000Z");
    const finalPublication = new Date("2026-07-04T15:39:00.000Z");
    expect(
      ready.snapshot.departures.some(({ effectiveTime }) => {
        const time = new Date(effectiveTime).getTime();
        return time >= fastFailure.getTime() && time < finalPublication.getTime();
      }),
    ).toBe(true);
    let currentTime = NOW;
    const pending = runLiveCommuteTick({
      sessions: [
        fallbackSession,
        session({
          sessionId: "slow",
          query: lineQuery({ siteId: 9300, lineId: 3, directionCode: 1 }),
        }),
      ],
      now: () => new Date(currentTime),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[ready.group.key, ready.snapshot]]),
    });

    currentTime = fastFailure;
    transport.reject(9192, new Error("upstream unavailable"));
    await Promise.resolve();
    await Promise.resolve();
    currentTime = finalPublication;
    transport.resolve(9300);
    const result = await pending;
    const stale = result.publications.find(
      (publication) => publication.group.query.kind === "LINE_DIRECTION" && publication.group.query.siteId === 9192,
    );

    expect(stale?.status).toBe("READY_STALE");
    if (
      stale == null ||
      stale.status !== "READY_STALE" ||
      stale.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected stale fallback publication");
    }
    expect(stale.snapshot.generatedAt).toBe(finalPublication.toISOString());
    expect(stale.snapshot.sourceFetchedAt).toBe(ready.snapshot.sourceFetchedAt);
    expect(stale.snapshot.departures).toEqual([]);
    expect(
      result.acquisitions.find(({ key }) => key === stale.group.acquisitionKey)?.completedAt,
    ).toBe(fastFailure.toISOString());
  });

  it("shares acquisition only within a tick, not across overlapping ticks", async () => {
    const gate = deferred<RawDeparturesResponse>();
    let calls = 0;
    const transport: SlTransportClient = {
      async fetchAllSites() {
        return [];
      },
      async fetchStopPoints() {
        return [];
      },
      fetchDepartures() {
        calls++;
        return gate.promise;
      },
    };
    const input = {
      sessions: [session()],
      now: fixedClock(NOW),
      transportClient: transport,
      journeyClient: unusedJourneyClient(),
    };

    const first = runLiveCommuteTick(input);
    const second = runLiveCommuteTick(input);
    expect(calls).toBe(2);
    gate.resolve(departuresFixture as unknown as RawDeparturesResponse);
    const results = await Promise.all([first, second]);

    expect(results.map((result) => result.acquisitions[0]?.status)).toEqual([
      "ACQUIRED",
      "ACQUIRED",
    ]);
    expect(calls).toBe(2);
  });

  it("ignores new freshness bookkeeping when fresh transit content is identical", async () => {
    const line = session();
    const firstTransport = transportWorld();
    const first = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(NOW),
      transportClient: firstTransport.client,
      journeyClient: unusedJourneyClient(),
    });
    const ready = first.publications[0];
    if (ready == null || ready.status !== "READY") throw new Error("expected fresh publication");

    const nextTick = new Date("2026-07-04T15:32:30.000Z");
    const secondTransport = transportWorld();
    const second = await runLiveCommuteTick({
      sessions: [line],
      now: fixedClock(nextTick),
      transportClient: secondTransport.client,
      journeyClient: unusedJourneyClient(),
      previousSnapshots: previousSource([[ready.group.key, ready.snapshot]]),
    });

    expect(second.publications[0]?.status).toBe("READY");
    expect(
      second.publications[0]?.status === "READY"
        ? second.publications[0].contentChanged
        : undefined,
    ).toBe(false);
  });

  it("shares one authoritative exact acquisition and preserves roles without disruption enrichment", async () => {
    const journeyNow = new Date("2026-08-10T07:00:00.000Z");
    const batch = [
      rawJourney("primary", "2026-08-10T07:05:00.000Z", "2026-08-10T07:15:00.000Z", "metro"),
      rawJourney("alternative", "2026-08-10T07:10:00.000Z", "2026-08-10T07:25:00.000Z", "bus"),
      rawJourney("next", "2026-08-10T07:15:00.000Z", "2026-08-10T07:30:00.000Z", "metro"),
    ] as const;
    const journeys = journeyWorld(batch);
    const exactSession = (id: string) =>
      session({
        sessionId: id,
        startsAt: new Date("2026-08-10T06:00:00.000Z"),
        endsAt: new Date("2026-08-10T09:00:00.000Z"),
        query: exactQuery({ searchUntil: journeyNow }),
      });
    const transport = transportWorld();
    const singleSessionJourneys = journeyWorld(batch);
    await runLiveCommuteTick({
      sessions: [exactSession("single")],
      now: fixedClock(journeyNow),
      transportClient: transport.client,
      journeyClient: singleSessionJourneys.client,
    });

    const result = await runLiveCommuteTick({
      sessions: [exactSession("first"), exactSession("second")],
      now: fixedClock(journeyNow),
      transportClient: transport.client,
      journeyClient: journeys.client,
    });

    expect(result.acquisitions).toHaveLength(1);
    expect(result.publications).toHaveLength(1);
    expect(journeys.requests).toHaveLength(singleSessionJourneys.requests.length);
    expect(transport.calls).toEqual([]);
    const publication = result.publications[0];
    expect(publication?.status).toBe("READY");
    if (
      publication == null ||
      publication.status !== "READY" ||
      publication.snapshot.kind !== "EXACT_DESTINATION"
    ) {
      throw new Error("expected exact publication");
    }
    expect(publication.snapshot.journeys.map(({ journeyId, role }) => [journeyId, role])).toEqual([
      ["primary", "PRIMARY"],
      ["alternative", "ALTERNATIVE"],
      ["next", "NEXT"],
    ]);
  });

  it("uses an exact stale fallback without promoting NEXT after PRIMARY expires", async () => {
    const journeyNow = new Date("2026-08-10T07:00:00.000Z");
    const exactSession = session({
      startsAt: new Date("2026-08-10T06:00:00.000Z"),
      endsAt: new Date("2026-08-10T09:00:00.000Z"),
      query: exactQuery({ searchUntil: journeyNow }),
    });
    const firstJourneys = journeyWorld([
      rawJourney("primary", "2026-08-10T07:05:00.000Z", "2026-08-10T07:15:00.000Z", "metro"),
      rawJourney("next", "2026-08-10T07:15:00.000Z", "2026-08-10T07:30:00.000Z", "metro"),
    ]);
    const transport = transportWorld();
    const first = await runLiveCommuteTick({
      sessions: [exactSession],
      now: fixedClock(journeyNow),
      transportClient: transport.client,
      journeyClient: firstJourneys.client,
    });
    const ready = first.publications[0];
    if (ready == null || ready.status !== "READY") throw new Error("expected fresh publication");

    const failedJourneys: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        throw new Error("upstream unavailable");
      },
    };
    const afterPrimary = new Date("2026-08-10T07:06:00.000Z");
    const second = await runLiveCommuteTick({
      sessions: [exactSession],
      now: fixedClock(afterPrimary),
      transportClient: transport.client,
      journeyClient: failedJourneys,
      previousSnapshots: previousSource([[ready.group.key, ready.snapshot]]),
    });
    const stale = second.publications[0];

    expect(stale?.status).toBe("READY_STALE");
    if (
      stale == null ||
      stale.status !== "READY_STALE" ||
      stale.snapshot.kind !== "EXACT_DESTINATION"
    ) {
      throw new Error("expected stale exact publication");
    }
    expect(stale.snapshot.sourceFetchedAt).toBe(ready.snapshot.sourceFetchedAt);
    expect(stale.snapshot.journeys.map(({ journeyId, role }) => [journeyId, role])).toEqual([
      ["next", "NEXT"],
    ]);
  });
});
