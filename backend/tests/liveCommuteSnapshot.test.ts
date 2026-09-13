import { describe, expect, it } from "vitest";
import type { Departure } from "../src/models/departure.js";
import {
  LIVE_COMMUTE_LINE_DEPARTURE_LIMIT,
  buildExactDestinationLiveCommuteSnapshot,
  buildLineDirectionLiveCommuteSnapshot,
  liveCommuteSnapshotContentChanged,
  liveCommuteSnapshotFingerprint,
  reprojectStaleLiveCommuteSnapshot,
  type AuthoritativeLiveJourneySnapshotInput,
  type LiveCommuteJourneyLeg,
} from "../src/liveCommute/snapshot.js";

const NOW = new Date("2026-09-11T06:00:00.000Z");

interface DepartureOverrides extends Partial<Omit<Departure, "line">> {
  line?: Partial<Departure["line"]>;
}

function departure(id: string, secondsFromNow: number, overrides: DepartureOverrides = {}): Departure {
  const scheduledTime = new Date(NOW.getTime() + secondsFromNow * 1000).toISOString();
  const { line: lineOverrides, ...departureOverrides } = overrides;
  return {
    departureId: id,
    line: {
      id: 14,
      designation: "14",
      transportMode: "METRO",
      ...lineOverrides,
    },
    direction: "Northbound",
    directionCode: 1,
    destination: "Mörby centrum",
    via: null,
    stopArea: { id: 9192, name: "Slussen", type: "METROSTN" },
    stopPoint: { id: 101, name: "Slussen", designation: "A" },
    scheduledTime,
    expectedTime: null,
    state: "EXPECTED",
    isCancelled: false,
    journey: { id: 100, state: "NORMALPROGRESS", predictionState: null },
    tripDeviations: [],
    ...departureOverrides,
  };
}

function departureSource(departures: readonly Departure[], fetchedAt = "2026-09-11T05:59:50.000Z") {
  return { fetchedAt, departures };
}

const exactQuery = {
  transportMode: "METRO",
  lineId: 14,
  directionCode: 1,
} as const;

function leg(departureTime: string, overrides: Partial<LiveCommuteJourneyLeg> = {}): LiveCommuteJourneyLeg {
  return {
    transportMode: "METRO",
    lineDesignation: "14",
    direction: "Mörby centrum",
    originName: "Slussen",
    destinationName: "T-Centralen",
    departureTime,
    arrivalTime: new Date(Date.parse(departureTime) + 5 * 60_000).toISOString(),
    isRealtime: true,
    ...overrides,
  };
}

function roleAssignedJourney(
  journeyId: string,
  role: "PRIMARY" | "NEXT" | "ALTERNATIVE",
  secondsFromNow: number,
): AuthoritativeLiveJourneySnapshotInput {
  const departureTime = new Date(NOW.getTime() + secondsFromNow * 1000).toISOString();
  const firstLeg = leg(departureTime);
  return {
    role,
    journey: {
      journeyId,
      originName: "Slussen",
      destinationName: "Odenplan",
      departureTime,
      arrivalTime: new Date(Date.parse(departureTime) + 12 * 60_000).toISOString(),
      transferCount: role === "ALTERNATIVE" ? 1 : 0,
      firstLeg,
      legs: [firstLeg],
    },
  };
}

describe("LINE_DIRECTION live commute snapshots", () => {
  it("mirrors mode, nullable line and nullable direction filters before retaining five future candidates", () => {
    const relevant = Array.from({ length: 6 }, (_, index) =>
      departure(`relevant-${index + 1}`, (index + 1) * 60),
    );
    const snapshot = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([
        departure("wrong-mode", 1, { line: { transportMode: "BUS" } }),
        relevant[5]!,
        departure("wrong-line", 2, { line: { id: 19, designation: "19" } }),
        relevant[2]!,
        departure("expired", -1),
        relevant[0]!,
        departure("wrong-direction", 3, { directionCode: 2 }),
        relevant[4]!,
        relevant[1]!,
        relevant[3]!,
      ]),
      NOW,
    );

    expect(snapshot.departures).toHaveLength(LIVE_COMMUTE_LINE_DEPARTURE_LIMIT);
    expect(snapshot.departures.map((item) => item.departureId)).toEqual([
      "relevant-1",
      "relevant-2",
      "relevant-3",
      "relevant-4",
      "relevant-5",
    ]);
  });

  it("keeps nullable line and direction as wildcards while transport mode remains required", () => {
    const snapshot = buildLineDirectionLiveCommuteSnapshot(
      { transportMode: "METRO", lineId: null, directionCode: null },
      departureSource([
        departure("line-14-north", 60),
        departure("line-19-south", 120, {
          line: { id: 19, designation: "19" },
          directionCode: 2,
        }),
        departure("bus", 30, { line: { id: 3, designation: "3", transportMode: "BUS" } }),
      ]),
      NOW,
    );

    expect(snapshot.departures.map((item) => item.departureId)).toEqual([
      "line-14-north",
      "line-19-south",
    ]);
  });

  it("uses expected time for expiry/order and preserves a future cancellation", () => {
    const delayed = departure("delayed", -120, {
      expectedTime: new Date(NOW.getTime() + 180_000).toISOString(),
    });
    const cancelledAtNow = departure("cancelled", 0, { isCancelled: true, state: "CANCELLED" });
    const snapshot = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([delayed, cancelledAtNow]),
      NOW,
    );

    expect(snapshot.departures.map((item) => item.departureId)).toEqual(["cancelled", "delayed"]);
    expect(snapshot.departures[0]?.isCancelled).toBe(true);
    expect(snapshot.departures[1]?.effectiveTime).toBe(delayed.expectedTime);
  });

  it("contains absolute times but no persisted countdown", () => {
    const snapshot = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([departure("next", 60)]),
      NOW,
    );

    expect(snapshot.departures[0]?.scheduledTime).toBe("2026-09-11T06:01:00.000Z");
    expect(JSON.stringify(snapshot)).not.toMatch(/minutesRemaining|countdown/i);
  });

  it("ignores fetch and generation bookkeeping but detects time, cancellation and status changes", () => {
    const initialDeparture = departure("next", 600, {
      expectedTime: "2026-09-11T06:10:00.000Z",
    });
    const initial = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([initialDeparture], "2026-09-11T05:59:00.000Z"),
      NOW,
    );
    const sameContentLater = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([initialDeparture], "2026-09-11T06:00:20.000Z"),
      new Date("2026-09-11T06:00:30.000Z"),
    );
    const delayed = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([
        { ...initialDeparture, expectedTime: "2026-09-11T06:12:00.000Z" },
      ]),
      NOW,
    );
    const cancelled = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([{ ...initialDeparture, isCancelled: true }]),
      NOW,
    );
    const statusChanged = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([{
        ...initialDeparture,
        state: "ATSTOP",
        journey: { ...initialDeparture.journey, predictionState: "REALTIME" },
      }]),
      NOW,
    );

    expect(liveCommuteSnapshotFingerprint(sameContentLater)).toBe(
      liveCommuteSnapshotFingerprint(initial),
    );
    expect(liveCommuteSnapshotContentChanged(initial, sameContentLater)).toBe(false);
    expect(liveCommuteSnapshotContentChanged(initial, delayed)).toBe(true);
    expect(liveCommuteSnapshotContentChanged(initial, cancelled)).toBe(true);
    expect(liveCommuteSnapshotContentChanged(initial, statusChanged)).toBe(true);
  });

  it("preserves acquisition order when effective times are equal", () => {
    const snapshot = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([
        departure("z-departure", 60),
        departure("a-departure", 60),
      ]),
      NOW,
    );

    expect(snapshot.departures.map((item) => item.departureId)).toEqual([
      "z-departure",
      "a-departure",
    ]);
  });

  it("treats equivalent absolute timestamp representations as the same content", () => {
    const utcDeparture = departure("next", 600, {
      scheduledTime: "2026-09-11T06:10:00.000Z",
      expectedTime: "2026-09-11T06:11:00.000Z",
    });
    const offsetDeparture = {
      ...utcDeparture,
      scheduledTime: "2026-09-11T08:10:00.000+02:00",
      expectedTime: "2026-09-11T08:11:00.000+02:00",
    };
    const utcSnapshot = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([utcDeparture]),
      NOW,
    );
    const offsetSnapshot = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([offsetDeparture]),
      NOW,
    );

    expect(offsetSnapshot.departures[0]?.scheduledTime).toBe("2026-09-11T06:10:00.000Z");
    expect(offsetSnapshot.departures[0]?.expectedTime).toBe("2026-09-11T06:11:00.000Z");
    expect(liveCommuteSnapshotFingerprint(offsetSnapshot)).toBe(
      liveCommuteSnapshotFingerprint(utcSnapshot),
    );
    expect(liveCommuteSnapshotContentChanged(utcSnapshot, offsetSnapshot)).toBe(false);
  });

  it("detects rollover when the first departure expires and the reserve admits the next one", () => {
    const candidates = Array.from({ length: 6 }, (_, index) =>
      departure(`departure-${index + 1}`, index * 60),
    );
    const before = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource(candidates),
      NOW,
    );
    const after = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource(candidates),
      new Date(NOW.getTime() + 1),
    );

    expect(before.departures.map((item) => item.departureId)).toEqual([
      "departure-1",
      "departure-2",
      "departure-3",
      "departure-4",
      "departure-5",
    ]);
    expect(after.departures.map((item) => item.departureId)).toEqual([
      "departure-2",
      "departure-3",
      "departure-4",
      "departure-5",
      "departure-6",
    ]);
    expect(liveCommuteSnapshotContentChanged(before, after)).toBe(true);
  });

  it("reprojects stale data against the current clock while preserving source freshness", () => {
    const sourceFetchedAt = "2026-09-11T05:58:00.000Z";
    const fresh = buildLineDirectionLiveCommuteSnapshot(
      exactQuery,
      departureSource([departure("expired", 30), departure("still-current", 120)], sourceFetchedAt),
      NOW,
    );
    const stale = reprojectStaleLiveCommuteSnapshot(
      fresh,
      new Date("2026-09-11T06:01:00.000Z"),
    );

    expect(stale.kind).toBe("LINE_DIRECTION");
    if (stale.kind !== "LINE_DIRECTION") throw new Error("unexpected snapshot kind");
    expect(stale.freshness).toBe("STALE");
    expect(stale.sourceFetchedAt).toBe(sourceFetchedAt);
    expect(stale.departures.map((item) => item.departureId)).toEqual(["still-current"]);
  });
});

describe("EXACT_DESTINATION live commute snapshots", () => {
  it("preserves authoritative roles and order while projecting only presentation state", () => {
    const primary = roleAssignedJourney("primary", "PRIMARY", 60);
    const alternative = roleAssignedJourney("alternative", "ALTERNATIVE", 90);
    const next = roleAssignedJourney("next", "NEXT", 120);
    const snapshot = buildExactDestinationLiveCommuteSnapshot(
      { fetchedAt: new Date("2026-09-11T05:59:50.000Z"), journeys: [primary, alternative, next] },
      NOW,
    );

    expect(snapshot.journeys.map((journey) => [journey.journeyId, journey.role])).toEqual([
      ["primary", "PRIMARY"],
      ["alternative", "ALTERNATIVE"],
      ["next", "NEXT"],
    ]);
    expect(snapshot.sourceFetchedAt).toBe("2026-09-11T05:59:50.000Z");
    expect(JSON.stringify(snapshot)).not.toMatch(/disruption|walkingDuration|stopIds/i);
  });

  it("uses first-leg time for expiry and never promotes NEXT in a stale reprojection", () => {
    const fresh = buildExactDestinationLiveCommuteSnapshot(
      {
        fetchedAt: "2026-09-11T05:59:00.000Z",
        journeys: [
          roleAssignedJourney("primary", "PRIMARY", 10),
          roleAssignedJourney("next", "NEXT", 20),
        ],
      },
      NOW,
    );
    const stale = reprojectStaleLiveCommuteSnapshot(
      fresh,
      new Date("2026-09-11T06:00:11.000Z"),
    );

    expect(stale.kind).toBe("EXACT_DESTINATION");
    if (stale.kind !== "EXACT_DESTINATION") throw new Error("unexpected snapshot kind");
    expect(stale.freshness).toBe("STALE");
    expect(stale.journeys.map((journey) => [journey.journeyId, journey.role])).toEqual([
      ["next", "NEXT"],
    ]);
    expect(stale.journeys.some((journey) => journey.role === "PRIMARY")).toBe(false);
  });

  it("detects authoritative role, arrival-time and leg-structure changes", () => {
    const primary = roleAssignedJourney("journey", "PRIMARY", 60);
    const initial = buildExactDestinationLiveCommuteSnapshot(
      { fetchedAt: "2026-09-11T05:59:00.000Z", journeys: [primary] },
      NOW,
    );
    if (!("journey" in primary)) throw new Error("unexpected journey fixture");
    const roleChanged = buildExactDestinationLiveCommuteSnapshot(
      { fetchedAt: "2026-09-11T05:59:10.000Z", journeys: [{ ...primary, role: "NEXT" }] },
      NOW,
    );
    const arrivalChanged = buildExactDestinationLiveCommuteSnapshot(
      {
        fetchedAt: "2026-09-11T05:59:20.000Z",
        journeys: [{
          role: primary.role,
          journey: { ...primary.journey, arrivalTime: "2026-09-11T06:20:00.000Z" },
        }],
      },
      NOW,
    );
    const structureChanged = buildExactDestinationLiveCommuteSnapshot(
      {
        fetchedAt: "2026-09-11T05:59:30.000Z",
        journeys: [{
          role: primary.role,
          journey: {
            ...primary.journey,
            legs: [...primary.journey.legs, leg("2026-09-11T06:08:00.000Z", {
              transportMode: "BUS",
              lineDesignation: "4",
            })],
          },
        }],
      },
      NOW,
    );

    expect(liveCommuteSnapshotContentChanged(initial, roleChanged)).toBe(true);
    expect(liveCommuteSnapshotContentChanged(initial, arrivalChanged)).toBe(true);
    expect(liveCommuteSnapshotContentChanged(initial, structureChanged)).toBe(true);
  });
});
