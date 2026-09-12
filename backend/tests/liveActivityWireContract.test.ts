import { describe, expect, it } from "vitest";
import {
  BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
  BLICK_LIVE_ACTIVITY_LINE_DEPARTURE_LIMIT,
  BLICK_LIVE_ACTIVITY_SCHEMA_VERSION,
  createBlickLiveActivityAttributes,
  mapLiveCommuteSnapshotToContentState,
} from "../src/liveCommute/apple/liveActivityWireContract.js";
import type {
  ExactDestinationLiveCommuteSnapshot,
  LineDirectionLiveCommuteSnapshot,
  LiveCommuteExactJourney,
  LiveCommuteJourneyLeg,
  LiveCommuteLineDeparture,
} from "../src/liveCommute/snapshot.js";

const PROJECTION_AT = new Date("2026-09-12T08:00:00.500Z");
const BINDING_ID = "31e40315-30a9-42f7-88c8-634374de2a66";

function epochSeconds(timestamp: string): number {
  return Math.floor(Date.parse(timestamp) / 1_000);
}

function lineDeparture(
  departureId: string,
  effectiveTime: string,
  overrides: Partial<LiveCommuteLineDeparture> = {},
): LiveCommuteLineDeparture {
  return {
    departureId,
    lineDesignation: "14",
    direction: "Northbound",
    destination: "Mörby centrum",
    scheduledTime: effectiveTime,
    expectedTime: null,
    effectiveTime,
    isCancelled: false,
    state: "EXPECTED",
    journeyState: "NORMALPROGRESS",
    predictionState: null,
    ...overrides,
  };
}

function lineSnapshot(
  departures: readonly LiveCommuteLineDeparture[],
): LineDirectionLiveCommuteSnapshot {
  return {
    kind: "LINE_DIRECTION",
    freshness: "STALE",
    sourceFetchedAt: "2026-09-12T07:59:42.987Z",
    generatedAt: "2026-09-12T07:59:45.000Z",
    departures,
  };
}

function journeyLeg(
  departureTime: string | null,
  overrides: Partial<LiveCommuteJourneyLeg> = {},
): LiveCommuteJourneyLeg {
  return {
    transportMode: "METRO",
    lineDesignation: "14",
    direction: "Mörby centrum",
    originName: "Slussen",
    destinationName: "T-Centralen",
    departureTime,
    arrivalTime: "2026-09-12T08:12:00.600Z",
    isRealtime: true,
    ...overrides,
  };
}

function exactJourney(
  journeyId: string,
  role: LiveCommuteExactJourney["role"],
  effectiveDepartureTime: string,
): LiveCommuteExactJourney {
  const firstLeg = journeyLeg(effectiveDepartureTime);
  return {
    journeyId,
    role,
    originName: "Slussen",
    destinationName: "Odenplan",
    departureTime: "2026-09-12T08:00:10.900Z",
    effectiveDepartureTime,
    arrivalTime: "2026-09-12T08:18:30.999Z",
    transferCount: role === "ALTERNATIVE" ? 1 : 0,
    firstLeg,
    legs: [
      firstLeg,
      journeyLeg("2026-09-12T08:13:00.000Z", {
        transportMode: "BUS",
        lineDesignation: "4",
      }),
    ],
  };
}

describe("Blick Live Activity static attributes", () => {
  it("establishes the exact v1 type name and builds normalized immutable attributes", () => {
    const attributes = createBlickLiveActivityAttributes({
      bindingId: `  ${BINDING_ID.toUpperCase()}  `,
      sessionRevision: 7,
      commuteKind: "LINE_DIRECTION",
    });

    expect(BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE).toBe(
      "BlickLiveActivityAttributes",
    );
    expect(BLICK_LIVE_ACTIVITY_SCHEMA_VERSION).toBe(1);
    expect(attributes).toEqual({
      schemaVersion: 1,
      bindingId: BINDING_ID,
      sessionRevision: 7,
      commuteKind: "LINE_DIRECTION",
    });
    expect(Object.isFrozen(attributes)).toBe(true);
  });

  it("rejects invalid binding, revision and commute-kind values", () => {
    expect(() =>
      createBlickLiveActivityAttributes({
        bindingId: "not-a-binding-id",
        sessionRevision: 1,
        commuteKind: "LINE_DIRECTION",
      }),
    ).toThrow("bindingId is invalid");
    expect(() =>
      createBlickLiveActivityAttributes({
        bindingId: BINDING_ID,
        sessionRevision: 0,
        commuteKind: "LINE_DIRECTION",
      }),
    ).toThrow("sessionRevision must be a positive supported integer");
    expect(() =>
      createBlickLiveActivityAttributes({
        bindingId: BINDING_ID,
        sessionRevision: 1,
        commuteKind: "OTHER" as never,
      }),
    ).toThrow("commuteKind is invalid");
  });
});

describe("Blick Live Activity LINE_DIRECTION content state", () => {
  it("removes expired rows, preserves cancellation and stale state, then presents current plus next", () => {
    const expectedTime = "2026-09-12T08:01:00.987Z";
    const snapshot = lineSnapshot([
      lineDeparture("expired", "2026-09-12T08:00:00.499Z"),
      lineDeparture("delayed", expectedTime, {
        scheduledTime: "2026-09-12T07:59:00.000Z",
        expectedTime,
        predictionState: "REALTIME",
      }),
      lineDeparture("cancelled", "2026-09-12T08:02:00.000Z", {
        isCancelled: true,
        state: "CANCELLED",
      }),
      lineDeparture("presentation-reserve", "2026-09-12T08:03:00.000Z"),
    ]);

    const contentState = mapLiveCommuteSnapshotToContentState(
      snapshot,
      PROJECTION_AT,
    );

    expect(BLICK_LIVE_ACTIVITY_LINE_DEPARTURE_LIMIT).toBe(2);
    expect(contentState).toEqual({
      schemaVersion: 1,
      commuteKind: "LINE_DIRECTION",
      freshness: "STALE",
      sourceFetchedAt: epochSeconds(snapshot.sourceFetchedAt),
      departures: [
        {
          departureId: "delayed",
          lineDesignation: "14",
          direction: "Northbound",
          destination: "Mörby centrum",
          scheduledAt: epochSeconds("2026-09-12T07:59:00.000Z"),
          expectedAt: epochSeconds(expectedTime),
          effectiveAt: epochSeconds(expectedTime),
          isCancelled: false,
          departureState: "EXPECTED",
          journeyState: "NORMALPROGRESS",
          predictionState: "REALTIME",
        },
        {
          departureId: "cancelled",
          lineDesignation: "14",
          direction: "Northbound",
          destination: "Mörby centrum",
          scheduledAt: epochSeconds("2026-09-12T08:02:00.000Z"),
          expectedAt: null,
          effectiveAt: epochSeconds("2026-09-12T08:02:00.000Z"),
          isCancelled: true,
          departureState: "CANCELLED",
          journeyState: "NORMALPROGRESS",
          predictionState: null,
        },
      ],
    });
  });

  it("uses full timestamp precision for expiry before emitting integer UNIX seconds", () => {
    const snapshot = lineSnapshot([
      lineDeparture("one-ms-old", "2026-09-12T08:00:00.499Z"),
      lineDeparture("at-boundary", "2026-09-12T08:00:00.500Z"),
    ]);

    const contentState = mapLiveCommuteSnapshotToContentState(
      snapshot,
      PROJECTION_AT,
    );

    expect(contentState.commuteKind).toBe("LINE_DIRECTION");
    if (contentState.commuteKind !== "LINE_DIRECTION") {
      throw new Error("unexpected content-state kind");
    }
    expect(contentState.departures.map((departure) => departure.departureId)).toEqual([
      "at-boundary",
    ]);
    expect(Number.isInteger(contentState.departures[0]?.effectiveAt)).toBe(true);
  });
});

describe("Blick Live Activity EXACT_DESTINATION content state", () => {
  it("keeps authoritative roles and order without promoting NEXT after PRIMARY expires", () => {
    const snapshot: ExactDestinationLiveCommuteSnapshot = {
      kind: "EXACT_DESTINATION",
      freshness: "FRESH",
      sourceFetchedAt: "2026-09-12T07:59:50.250Z",
      generatedAt: "2026-09-12T07:59:55.000Z",
      journeys: [
        exactJourney("primary", "PRIMARY", "2026-09-12T08:00:00.499Z"),
        exactJourney("alternative", "ALTERNATIVE", "2026-09-12T08:04:00.123Z"),
        exactJourney("next", "NEXT", "2026-09-12T08:06:00.456Z"),
      ],
    };
    const sourceBefore = JSON.stringify(snapshot);

    const contentState = mapLiveCommuteSnapshotToContentState(
      snapshot,
      PROJECTION_AT,
    );

    expect(contentState.commuteKind).toBe("EXACT_DESTINATION");
    if (contentState.commuteKind !== "EXACT_DESTINATION") {
      throw new Error("unexpected content-state kind");
    }
    expect(contentState.freshness).toBe("FRESH");
    expect(contentState.journeys.map(({ journeyId, role }) => [journeyId, role])).toEqual([
      ["alternative", "ALTERNATIVE"],
      ["next", "NEXT"],
    ]);
    expect(contentState.journeys[0]).toEqual({
      journeyId: "alternative",
      role: "ALTERNATIVE",
      originName: "Slussen",
      destinationName: "Odenplan",
      departureAt: epochSeconds("2026-09-12T08:00:10.900Z"),
      effectiveDepartureAt: epochSeconds("2026-09-12T08:04:00.123Z"),
      arrivalAt: epochSeconds("2026-09-12T08:18:30.999Z"),
      transferCount: 1,
      firstLeg: {
        transportMode: "METRO",
        lineDesignation: "14",
        direction: "Mörby centrum",
        originName: "Slussen",
        destinationName: "T-Centralen",
        departureAt: epochSeconds("2026-09-12T08:04:00.123Z"),
        arrivalAt: epochSeconds("2026-09-12T08:12:00.600Z"),
        isRealtime: true,
      },
    });
    expect("legs" in contentState.journeys[0]!).toBe(false);
    expect(JSON.stringify(snapshot)).toBe(sourceBefore);
    expect(contentState.journeys[0]).not.toBe(snapshot.journeys[1]);
    expect(contentState.journeys[0]?.firstLeg).not.toBe(
      snapshot.journeys[1]?.firstLeg,
    );
    expect(Object.isFrozen(contentState)).toBe(true);
    expect(Object.isFrozen(contentState.journeys)).toBe(true);
    expect(Object.isFrozen(contentState.journeys[0])).toBe(true);
    expect(Object.isFrozen(contentState.journeys[0]?.firstLeg)).toBe(true);
  });
});

describe("Blick Live Activity content-state safety", () => {
  it("is deterministic, does not mutate or retain source objects, and deeply freezes output", () => {
    const snapshot = lineSnapshot([
      lineDeparture("current", "2026-09-12T08:01:00.000Z"),
    ]);
    const sourceBefore = JSON.stringify(snapshot);

    const first = mapLiveCommuteSnapshotToContentState(snapshot, PROJECTION_AT);
    const second = mapLiveCommuteSnapshotToContentState(snapshot, PROJECTION_AT);

    expect(first).toEqual(second);
    expect(first).not.toBe(second);
    expect(JSON.stringify(snapshot)).toBe(sourceBefore);
    expect(Object.isFrozen(first)).toBe(true);
    expect(first.commuteKind).toBe("LINE_DIRECTION");
    if (first.commuteKind !== "LINE_DIRECTION") {
      throw new Error("unexpected content-state kind");
    }
    expect(first.departures).not.toBe(snapshot.departures);
    expect(first.departures[0]).not.toBe(snapshot.departures[0]);
    expect(Object.isFrozen(first.departures)).toBe(true);
    expect(Object.isFrozen(first.departures[0])).toBe(true);
  });

  it("contains only absolute protocol values and no countdown, full legs, or delivery secrets", () => {
    const snapshot = Object.assign(
      lineSnapshot([
        lineDeparture("current", "2026-09-12T08:01:00.000Z"),
      ]),
      {
        installationId: "installation-secret",
        activityKitToken: "token-secret",
      },
    );

    const serialized = JSON.stringify(
      mapLiveCommuteSnapshotToContentState(snapshot, PROJECTION_AT),
    );

    expect(serialized).not.toMatch(/countdown|minutesRemaining/i);
    expect(serialized).not.toContain("installation-secret");
    expect(serialized).not.toContain("token-secret");
    expect(serialized).not.toContain('"generatedAt"');
    expect(serialized).not.toContain('"legs"');
  });

  it("rejects invalid explicit projection and source timestamps", () => {
    expect(() =>
      mapLiveCommuteSnapshotToContentState(
        lineSnapshot([]),
        new Date(Number.NaN),
      ),
    ).toThrow("projectionAt must be a valid absolute Date");

    expect(() =>
      mapLiveCommuteSnapshotToContentState(
        lineSnapshot([
          lineDeparture("invalid", "2026-09-12T08:01:00"),
        ]),
        PROJECTION_AT,
      ),
    ).toThrow("departure.effectiveTime must be an absolute timestamp");
  });
});
