import { describe, expect, it } from "vitest";
import {
  canonicalLiveCommuteQueryKey,
  canonicalizeLiveCommuteQuery,
  createLiveCommuteSession,
  type ExactDestinationLiveQueryInput,
  type LineDirectionLiveQueryInput,
  type LiveCommuteSession,
  type LiveCommuteSessionInput,
} from "../src/liveCommute/model.js";
import {
  planLiveCommuteSessions,
  prepareLiveCommutePublicationGroup,
} from "../src/liveCommute/planner.js";

const NOW = new Date("2026-09-11T07:30:00.000Z");
const STARTS_AT = new Date("2026-09-11T07:00:00.000Z");
const ENDS_AT = new Date("2026-09-11T08:00:00.000Z");

function lineQuery(
  overrides: Partial<LineDirectionLiveQueryInput> = {},
): LineDirectionLiveQueryInput {
  return {
    kind: "LINE_DIRECTION",
    siteId: 9001,
    transportMode: "BUS",
    lineId: 4,
    directionCode: 1,
    ...overrides,
  };
}

function exactQuery(
  overrides: Partial<ExactDestinationLiveQueryInput> = {},
): ExactDestinationLiveQueryInput {
  return {
    kind: "EXACT_DESTINATION",
    originId: "A=1@O=Odenplan",
    destinationId: "A=1@O=Slussen",
    transportModes: ["METRO", "BUS"],
    changesPreference: "BOTH",
    searchUntil: ENDS_AT,
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

describe("live commute session validation", () => {
  it.each([
    ["equal", STARTS_AT],
    ["earlier", new Date("2026-09-11T06:59:59.999Z")],
  ])("rejects an %s end boundary", (_name, endsAt) => {
    expect(() => session({ endsAt })).toThrow("endsAt must be later than startsAt");
  });

  it("rejects an invalid absolute timestamp", () => {
    expect(() => session({ startsAt: new Date("not-a-date") })).toThrow(
      "startsAt must be a valid absolute Date",
    );
  });

  it("rejects malformed identity and query values", () => {
    expect(() => session({ sessionId: " " })).toThrow("sessionId is invalid");
    expect(() => session({ query: lineQuery({ siteId: 0 }) })).toThrow(
      "query.siteId must be a positive safe integer",
    );
    expect(() => session({ query: exactQuery({ transportModes: [] }) })).toThrow(
      "query.transportModes must contain at least one supported mode",
    );
    expect(() =>
      session({
        query: exactQuery({ destinationId: " A=1@O=Odenplan " }),
      }),
    ).toThrow("query origin and destination must differ");
  });

  it("snapshots mutable Date inputs", () => {
    const startsAt = new Date(STARTS_AT);
    const searchUntil = new Date(ENDS_AT);
    const created = session({ startsAt, query: exactQuery({ searchUntil }) });
    startsAt.setUTCFullYear(2030);
    searchUntil.setUTCFullYear(2030);
    expect(created.startsAt.toISOString()).toBe(STARTS_AT.toISOString());
    expect(created.query.kind).toBe("EXACT_DESTINATION");
    if (created.query.kind === "EXACT_DESTINATION") {
      expect(created.query.searchUntil.toISOString()).toBe(ENDS_AT.toISOString());
    }
  });
});

describe("canonical live query identity", () => {
  it("uses a readable fixed-field line identity independent of property insertion order", () => {
    const reordered = {
      directionCode: 1,
      lineId: 4,
      transportMode: "bus",
      siteId: 9001,
      kind: "LINE_DIRECTION",
    } as const;

    expect(canonicalLiveCommuteQueryKey(reordered)).toBe(
      '{"kind":"LINE_DIRECTION","siteId":9001,"transportMode":"BUS","lineId":4,"directionCode":1}',
    );
    expect(canonicalLiveCommuteQueryKey(reordered)).toBe(
      canonicalLiveCommuteQueryKey(lineQuery()),
    );
  });

  it("preserves nullable line filters as explicit canonical semantics", () => {
    expect(canonicalizeLiveCommuteQuery(lineQuery({ lineId: null, directionCode: null }))).toEqual({
      kind: "LINE_DIRECTION",
      siteId: 9001,
      transportMode: "BUS",
      lineId: null,
      directionCode: null,
    });
  });

  it("sorts and deduplicates the order-insensitive exact-destination mode allow-list", () => {
    const first = exactQuery({ transportModes: ["BUS", "METRO", "BUS"] });
    const second = exactQuery({ transportModes: ["METRO", "BUS"] });

    expect(canonicalLiveCommuteQueryKey(first)).toBe(
      canonicalLiveCommuteQueryKey(second),
    );
    expect(canonicalLiveCommuteQueryKey(first)).toBe(
      '{"kind":"EXACT_DESTINATION","originId":"A=1@O=Odenplan","destinationId":"A=1@O=Slussen","transportModes":["METRO","BUS"],"changesPreference":"BOTH","searchUntil":"2026-09-11T08:00:00.000Z","searchMode":"NOW","laterJourneyCount":0}',
    );
  });

  it("canonicalizes equivalent absolute search boundaries", () => {
    const utc = exactQuery({ searchUntil: new Date("2026-09-11T08:00:00.000Z") });
    const offset = exactQuery({ searchUntil: new Date("2026-09-11T10:00:00.000+02:00") });
    expect(canonicalLiveCommuteQueryKey(utc)).toBe(canonicalLiveCommuteQueryKey(offset));
  });

  it("canonicalizes exact-destination fields independently of property insertion order", () => {
    const reordered = {
      searchUntil: ENDS_AT,
      changesPreference: "BOTH",
      transportModes: ["METRO", "BUS"],
      destinationId: "A=1@O=Slussen",
      originId: "A=1@O=Odenplan",
      kind: "EXACT_DESTINATION",
    } as const;
    expect(canonicalLiveCommuteQueryKey(reordered)).toBe(
      canonicalLiveCommuteQueryKey(exactQuery()),
    );
  });
});

describe("live commute planning", () => {
  it("uses a half-open active interval", () => {
    const startsNow = session({ startsAt: NOW, endsAt: ENDS_AT });
    const endsNow = session({
      sessionId: "ends-now",
      startsAt: STARTS_AT,
      endsAt: NOW,
    });

    const plan = planLiveCommuteSessions(NOW, [startsNow, endsNow]);

    expect(plan.activeSessions.map((item) => item.sessionId)).toEqual(["session-1"]);
    expect(plan.expiredSessions.map((item) => item.sessionId)).toEqual(["ends-now"]);
    expect(plan.notStartedSessions).toEqual([]);
  });

  it("partitions future, active, and expired sessions and groups only active sessions", () => {
    const future = session({
      sessionId: "future",
      startsAt: new Date("2026-09-11T07:30:00.001Z"),
      endsAt: new Date("2026-09-11T08:30:00.000Z"),
    });
    const active = session({ sessionId: "active" });
    const expired = session({
      sessionId: "expired",
      startsAt: new Date("2026-09-11T06:00:00.000Z"),
      endsAt: NOW,
    });

    const plan = planLiveCommuteSessions(NOW, [future, active, expired]);

    expect(plan.notStartedSessions.map((item) => item.sessionId)).toEqual(["future"]);
    expect(plan.activeSessions.map((item) => item.sessionId)).toEqual(["active"]);
    expect(plan.expiredSessions.map((item) => item.sessionId)).toEqual(["expired"]);
    expect(plan.acquisitionGroups).toHaveLength(1);
    expect(plan.acquisitionGroups[0]?.sessions.map((item) => item.sessionId)).toEqual(["active"]);
  });

  it("plans one acquisition for 100 identical active line queries", () => {
    const sessions = Array.from({ length: 100 }, (_, index) =>
      session({
        sessionId: `session-${index}`,
        installationId: `installation-${index}`,
        routineId: `routine-${index}`,
      }),
    );

    const plan = planLiveCommuteSessions(NOW, sessions);

    expect(plan.activeSessions).toHaveLength(100);
    expect(plan.acquisitionGroups).toHaveLength(1);
    expect(plan.acquisitionGroups[0]?.sessions).toHaveLength(100);
  });

  it("does not let session, installation, or routine identity prevent grouping", () => {
    const first = session();
    const second = session({
      sessionId: "different-session",
      installationId: "different-installation",
      routineId: "different-routine",
    });
    expect(planLiveCommuteSessions(NOW, [first, second]).acquisitionGroups).toHaveLength(1);
  });

  it("does not let different active line-session windows prevent query grouping", () => {
    const first = session();
    const second = session({
      sessionId: "different-window",
      startsAt: new Date("2026-09-11T06:30:00.000Z"),
      endsAt: new Date("2026-09-11T09:00:00.000Z"),
    });
    expect(planLiveCommuteSessions(NOW, [first, second]).acquisitionGroups).toHaveLength(1);
  });

  it.each([
    ["station", { siteId: 9002 }],
    ["line", { lineId: 5 }],
    ["line wildcard", { lineId: null }],
    ["direction", { directionCode: 2 }],
    ["direction wildcard", { directionCode: null }],
    ["mode", { transportMode: "METRO" }],
  ])("separates a different line-direction %s", (_name, queryOverride) => {
    const first = session({ sessionId: "first" });
    const second = session({
      sessionId: "second",
      query: lineQuery(queryOverride),
    });
    expect(planLiveCommuteSessions(NOW, [first, second]).acquisitionGroups).toHaveLength(2);
  });

  it("groups exact-destination mode sets with the same members in different orders", () => {
    const first = session({ sessionId: "first", query: exactQuery() });
    const second = session({
      sessionId: "second",
      query: exactQuery({ transportModes: ["BUS", "METRO"] }),
    });
    expect(planLiveCommuteSessions(NOW, [first, second]).acquisitionGroups).toHaveLength(1);
  });

  it("never groups different query kinds", () => {
    const line = session({ sessionId: "line" });
    const exact = session({ sessionId: "exact", query: exactQuery() });
    expect(planLiveCommuteSessions(NOW, [line, exact]).acquisitionGroups).toHaveLength(2);
  });

  it.each([
    ["origin", { originId: "A=1@O=Tekniska högskolan" }],
    ["destination", { destinationId: "A=1@O=Gullmarsplan" }],
    ["transport-mode allow-list", { transportModes: ["METRO"] }],
    ["changes preference", { changesPreference: "DIRECT_ONLY" }],
    ["search boundary", { searchUntil: new Date("2026-09-11T08:15:00.000Z") }],
  ] as const)("separates a different exact-destination %s", (_name, queryOverride) => {
    const first = session({ sessionId: "first", query: exactQuery() });
    const second = session({
      sessionId: "second",
      query: exactQuery(queryOverride),
    });
    expect(planLiveCommuteSessions(NOW, [first, second]).acquisitionGroups).toHaveLength(2);
  });

  it("revalidates forged session windows at the planner boundary", () => {
    const invalid = {
      ...session(),
      endsAt: STARTS_AT,
    };
    expect(() => planLiveCommuteSessions(NOW, [invalid])).toThrow(
      "endsAt must be later than startsAt",
    );
  });

  it("prevents publication when every acquired session has expired", () => {
    const acquisitionGroup = planLiveCommuteSessions(NOW, [session()]).acquisitionGroups[0];
    expect(acquisitionGroup).toBeDefined();
    expect(prepareLiveCommutePublicationGroup(ENDS_AT, acquisitionGroup!)).toBeNull();
  });

  it("removes sessions that expire during acquisition before publication", () => {
    const earlyEnd = new Date("2026-09-11T07:30:01.000Z");
    const laterEnd = new Date("2026-09-11T07:45:00.000Z");
    const acquisitionGroup = planLiveCommuteSessions(NOW, [
      session({ sessionId: "early", endsAt: earlyEnd }),
      session({ sessionId: "later", endsAt: laterEnd }),
    ]).acquisitionGroups[0];

    const publicationGroup = prepareLiveCommutePublicationGroup(earlyEnd, acquisitionGroup!);

    expect(publicationGroup?.validatedAt).toBe(earlyEnd.toISOString());
    expect(publicationGroup?.sessions.map((item) => item.sessionId)).toEqual(["later"]);
  });
});
