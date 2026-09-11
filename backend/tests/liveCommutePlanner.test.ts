import { describe, expect, it } from "vitest";
import {
  canonicalizeAcquisitionQuery,
  canonicalizePublicationQuery,
  createLiveCommuteSession,
  liveCommuteAcquisitionKey,
  liveCommutePublicationKey,
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
      session({ query: exactQuery({ destinationId: " A=1@O=Odenplan " }) }),
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

describe("live commute acquisition and publication identity", () => {
  it("uses only the site for LINE_DIRECTION acquisition", () => {
    expect(canonicalizeAcquisitionQuery(lineQuery())).toEqual({
      kind: "LINE_DIRECTION",
      siteId: 9001,
    });
    expect(liveCommuteAcquisitionKey(lineQuery())).toBe(
      '{"kind":"LINE_DIRECTION","siteId":9001}',
    );
    expect(
      liveCommuteAcquisitionKey(
        lineQuery({ transportMode: "METRO", lineId: null, directionCode: null }),
      ),
    ).toBe(liveCommuteAcquisitionKey(lineQuery()));
  });

  it("retains the complete LINE_DIRECTION filter for publication", () => {
    expect(canonicalizePublicationQuery(lineQuery())).toEqual({
      kind: "LINE_DIRECTION",
      siteId: 9001,
      transportMode: "BUS",
      lineId: 4,
      directionCode: 1,
    });
    expect(liveCommutePublicationKey(lineQuery())).toBe(
      '{"kind":"LINE_DIRECTION","siteId":9001,"transportMode":"BUS","lineId":4,"directionCode":1}',
    );
  });

  it("preserves nullable line and direction wildcards in publication identity", () => {
    expect(
      canonicalizePublicationQuery(lineQuery({ lineId: null, directionCode: null })),
    ).toEqual({
      kind: "LINE_DIRECTION",
      siteId: 9001,
      transportMode: "BUS",
      lineId: null,
      directionCode: null,
    });
    expect(
      liveCommutePublicationKey(lineQuery({ lineId: null, directionCode: null })),
    ).not.toBe(liveCommutePublicationKey(lineQuery()));
  });

  it("sorts and deduplicates the exact-destination mode allow-list for both keys", () => {
    const first = exactQuery({ transportModes: ["BUS", "METRO", "BUS"] });
    const second = exactQuery({ transportModes: ["METRO", "BUS"] });

    expect(liveCommuteAcquisitionKey(first)).toBe(liveCommuteAcquisitionKey(second));
    expect(liveCommutePublicationKey(first)).toBe(liveCommutePublicationKey(second));
    expect(liveCommuteAcquisitionKey(first)).toBe(
      '{"kind":"EXACT_DESTINATION","originId":"A=1@O=Odenplan","destinationId":"A=1@O=Slussen","transportModes":["METRO","BUS"],"changesPreference":"BOTH","searchUntil":"2026-09-11T08:00:00.000Z","searchMode":"NOW","laterJourneyCount":0}',
    );
  });

  it("canonicalizes equivalent exact-destination absolute boundaries", () => {
    const utc = exactQuery({ searchUntil: new Date("2026-09-11T08:00:00.000Z") });
    const offset = exactQuery({ searchUntil: new Date("2026-09-11T10:00:00.000+02:00") });
    expect(liveCommuteAcquisitionKey(utc)).toBe(liveCommuteAcquisitionKey(offset));
    expect(liveCommutePublicationKey(utc)).toBe(liveCommutePublicationKey(offset));
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

  it("groups only active sessions", () => {
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
    expect(plan.publicationGroups).toHaveLength(1);
  });

  it("plans one site acquisition and several publication states for 100 mixed line sessions", () => {
    const variants = [
      lineQuery(),
      lineQuery({ transportMode: "METRO", lineId: null, directionCode: null }),
      lineQuery({ lineId: 5 }),
      lineQuery({ directionCode: 2 }),
    ];
    const sessions = Array.from({ length: 100 }, (_, index) =>
      session({
        sessionId: `session-${index}`,
        installationId: `installation-${index}`,
        routineId: `routine-${index}`,
        query: variants[index % variants.length]!,
      }),
    );

    const plan = planLiveCommuteSessions(NOW, sessions);

    expect(plan.activeSessions).toHaveLength(100);
    expect(plan.acquisitionGroups).toHaveLength(1);
    expect(plan.acquisitionGroups[0]?.sessions).toHaveLength(100);
    expect(plan.acquisitionGroups[0]?.publicationGroups).toHaveLength(4);
    expect(plan.publicationGroups).toHaveLength(4);
  });

  it.each([
    ["mode", { transportMode: "METRO" }],
    ["line", { lineId: 5 }],
    ["line wildcard", { lineId: null }],
    ["direction", { directionCode: 2 }],
    ["direction wildcard", { directionCode: null }],
  ])("shares same-site %s acquisition but separates publication", (_name, queryOverride) => {
    const plan = planLiveCommuteSessions(NOW, [
      session({ sessionId: "first" }),
      session({ sessionId: "second", query: lineQuery(queryOverride) }),
    ]);

    expect(plan.acquisitionGroups).toHaveLength(1);
    expect(plan.publicationGroups).toHaveLength(2);
  });

  it("separates different sites for both acquisition and publication", () => {
    const plan = planLiveCommuteSessions(NOW, [
      session({ sessionId: "first" }),
      session({ sessionId: "second", query: lineQuery({ siteId: 9002 }) }),
    ]);

    expect(plan.acquisitionGroups).toHaveLength(2);
    expect(plan.publicationGroups).toHaveLength(2);
  });

  it("groups identical complete publication queries despite session identity and windows", () => {
    const plan = planLiveCommuteSessions(NOW, [
      session(),
      session({
        sessionId: "different-session",
        installationId: "different-installation",
        routineId: "different-routine",
        startsAt: new Date("2026-09-11T06:30:00.000Z"),
        endsAt: new Date("2026-09-11T09:00:00.000Z"),
      }),
    ]);

    expect(plan.acquisitionGroups).toHaveLength(1);
    expect(plan.publicationGroups).toHaveLength(1);
    expect(plan.publicationGroups[0]?.sessions).toHaveLength(2);
  });

  it("groups equivalent exact requests", () => {
    const plan = planLiveCommuteSessions(NOW, [
      session({ sessionId: "first", query: exactQuery() }),
      session({
        sessionId: "second",
        query: exactQuery({ transportModes: ["BUS", "METRO"] }),
      }),
    ]);
    expect(plan.acquisitionGroups).toHaveLength(1);
    expect(plan.publicationGroups).toHaveLength(1);
  });

  it.each([
    ["origin", { originId: "A=1@O=Tekniska högskolan" }],
    ["destination", { destinationId: "A=1@O=Gullmarsplan" }],
    ["transport-mode allow-list", { transportModes: ["METRO"] }],
    ["changes preference", { changesPreference: "DIRECT_ONLY" }],
    ["search boundary", { searchUntil: new Date("2026-09-11T08:15:00.000Z") }],
  ] as const)("separates a materially different exact %s acquisition", (_name, queryOverride) => {
    const plan = planLiveCommuteSessions(NOW, [
      session({ sessionId: "first", query: exactQuery() }),
      session({ sessionId: "second", query: exactQuery(queryOverride) }),
    ]);
    expect(plan.acquisitionGroups).toHaveLength(2);
    expect(plan.publicationGroups).toHaveLength(2);
  });

  it("never groups different query kinds", () => {
    const plan = planLiveCommuteSessions(NOW, [
      session({ sessionId: "line" }),
      session({ sessionId: "exact", query: exactQuery() }),
    ]);
    expect(plan.acquisitionGroups).toHaveLength(2);
    expect(plan.publicationGroups).toHaveLength(2);
  });

  it("revalidates forged session windows at the planner boundary", () => {
    const invalid = { ...session(), endsAt: STARTS_AT };
    expect(() => planLiveCommuteSessions(NOW, [invalid])).toThrow(
      "endsAt must be later than startsAt",
    );
  });

  it("prevents publication when every session expires during acquisition", () => {
    const publication = planLiveCommuteSessions(NOW, [session()]).publicationGroups[0];
    expect(publication).toBeDefined();
    expect(prepareLiveCommutePublicationGroup(ENDS_AT, publication!)).toBeNull();
  });

  it("removes only sessions that expire during acquisition", () => {
    const earlyEnd = new Date("2026-09-11T07:30:01.000Z");
    const laterEnd = new Date("2026-09-11T07:45:00.000Z");
    const publication = planLiveCommuteSessions(NOW, [
      session({ sessionId: "early", endsAt: earlyEnd }),
      session({ sessionId: "later", endsAt: laterEnd }),
    ]).publicationGroups[0];

    const ready = prepareLiveCommutePublicationGroup(earlyEnd, publication!);

    expect(ready?.validatedAt).toBe(earlyEnd.toISOString());
    expect(ready?.sessions.map((item) => item.sessionId)).toEqual(["later"]);
  });
});
