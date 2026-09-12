import { describe, expect, it } from "vitest";
import { liveCommutePublicationKey, type PublicationKey } from "../src/liveCommute/model.js";
import type { LiveCommuteSnapshot } from "../src/liveCommute/snapshot.js";
import type {
  InternalBroadcastLiveActivityUpdateTarget,
  InternalDirectLiveActivityUpdateTarget,
  InternalLiveActivityStartTarget,
} from "../src/liveCommute/apple/deliveryResolver.js";
import {
  buildBroadcastLiveActivityEndPlan,
  buildBroadcastLiveActivityUpdatePlan,
  buildDirectLiveActivityEndPlan,
  buildDirectLiveActivityUpdatePlan,
  buildLiveActivityStartPlan,
  type AuthoritativeReadyLiveCommutePublication,
} from "../src/liveCommute/apple/deliveryPlan.js";
import type { SensitiveApnsProviderToken } from "../src/liveCommute/apple/apnsProviderToken.js";

const GENERATED_AT = new Date("2026-09-12T10:00:00.900Z");
const RESOLVED_AT = new Date("2026-09-12T09:59:59.000Z");
const BINDING_ID = "db04e3cd-979f-43ca-8762-08743a2f8309";
const APNS_ID = "0ce0b68e-27e4-4c72-b496-67ba139fa847";
const REQUEST_ID = "950fb1ec-9e50-4a1e-9ec5-e798f0049648";
const RAW_PROVIDER_TOKEN = "synthetic.header.signature";
const DIRECT_TOKEN = Buffer.from("synthetic-direct-activity-token", "utf8");
const CHANNEL_ID = Buffer.from("synthetic-channel", "utf8").toString("base64");
const SESSION_VERSION = Object.freeze({
  installationId: "installation-1",
  sessionId: "occurrence-1",
  revision: 7,
});

const QUERY = Object.freeze({
  kind: "LINE_DIRECTION" as const,
  siteId: 9001,
  transportMode: "BUS",
  lineId: 4,
  directionCode: 1,
});
const PUBLICATION_KEY = liveCommutePublicationKey(QUERY);

function lineSnapshot(): LiveCommuteSnapshot {
  return {
    kind: "LINE_DIRECTION",
    freshness: "FRESH",
    sourceFetchedAt: "2026-09-12T09:59:30.000Z",
    generatedAt: "2026-09-12T09:59:31.000Z",
    departures: [
      {
        departureId: "expired",
        lineDesignation: "4",
        direction: "North",
        destination: "Expired destination",
        scheduledTime: "2026-09-12T09:59:00.000Z",
        expectedTime: null,
        effectiveTime: "2026-09-12T09:59:00.000Z",
        isCancelled: false,
        state: "EXPECTED",
        journeyState: "NORMAL",
        predictionState: null,
      },
      {
        departureId: "current",
        lineDesignation: "4",
        direction: "North",
        destination: "Centralen",
        scheduledTime: "2026-09-12T10:02:00.000Z",
        expectedTime: "2026-09-12T10:03:00.000Z",
        effectiveTime: "2026-09-12T10:03:00.000Z",
        isCancelled: false,
        state: "EXPECTED",
        journeyState: "NORMAL",
        predictionState: "REALTIME",
      },
    ],
  };
}

function publication(
  overrides: Partial<AuthoritativeReadyLiveCommutePublication> = {},
): AuthoritativeReadyLiveCommutePublication {
  return {
    status: "READY",
    group: {
      key: PUBLICATION_KEY,
      acquisitionKey: "synthetic-acquisition" as never,
      query: QUERY,
      sessions: [],
      validatedAt: GENERATED_AT.toISOString(),
    },
    snapshot: lineSnapshot(),
    contentChanged: true,
    sessionVersions: [SESSION_VERSION],
    authorityCheckCompletedAt: GENERATED_AT.toISOString(),
    ...overrides,
  } as AuthoritativeReadyLiveCommutePublication;
}

const PROVIDER_TOKEN: SensitiveApnsProviderToken = Object.freeze({
  issuedAt: 1_789_207_100,
  revealForAuthorization: () => RAW_PROVIDER_TOKEN,
  toJSON: () => "[REDACTED]",
  toString: () => "[REDACTED]",
});

function startTarget(
  strategy: "DIRECT_TOKEN" | "BROADCAST_CHANNEL" = "DIRECT_TOKEN",
): InternalLiveActivityStartTarget {
  return {
    kind: "START",
    bindingId: BINDING_ID,
    sessionVersion: SESSION_VERSION,
    strategy,
    publicationKey: PUBLICATION_KEY,
    resolvedAt: RESOLVED_AT,
    environment: "SANDBOX",
    pushToStartToken: Buffer.from(DIRECT_TOKEN),
    tokenGeneration: { clientGeneration: 3, serverRevision: 5 },
    broadcastChannelRequirement:
      strategy === "DIRECT_TOKEN" ? "NONE" : "APNS_CHANNEL_REQUIRED",
  };
}

function directTarget(): InternalDirectLiveActivityUpdateTarget {
  return {
    kind: "DIRECT_UPDATE",
    bindingId: BINDING_ID,
    sessionVersion: SESSION_VERSION,
    strategy: "DIRECT_TOKEN",
    publicationKey: PUBLICATION_KEY,
    resolvedAt: RESOLVED_AT,
    environment: "PRODUCTION",
    updateToken: Buffer.from(DIRECT_TOKEN),
    tokenGeneration: { clientGeneration: 9, serverRevision: 11 },
  };
}

function broadcastTarget(): InternalBroadcastLiveActivityUpdateTarget {
  return {
    kind: "BROADCAST_CHANNEL_REQUIRED",
    bindingId: BINDING_ID,
    sessionVersion: SESSION_VERSION,
    strategy: "BROADCAST_CHANNEL",
    publicationKey: PUBLICATION_KEY,
    resolvedAt: RESOLVED_AT,
  };
}

function parsedBody(plan: { request: { materializeForTransport(): { body: string | null } } }) {
  const body = plan.request.materializeForTransport().body;
  if (body == null) throw new Error("expected a request body");
  return JSON.parse(body) as {
    aps: Record<string, unknown> & {
      event: string;
      "content-state": { departures: { departureId: string }[] };
    };
  };
}

function thrownBy(callback: () => unknown): unknown {
  try {
    callback();
  } catch (error) {
    return error;
  }
  throw new Error("expected callback to throw");
}

describe("Live Activity delivery request planning", () => {
  it("correlates a direct start target, reprojects expiry, and builds but does not send", () => {
    const target = startTarget();
    const plan = buildLiveActivityStartPlan({
      target,
      publication: publication(),
      generatedAt: GENERATED_AT,
      bundleId: "se.blick.commute",
      providerToken: PROVIDER_TOKEN,
      priority: 5,
      expiration: 0,
      apnsId: APNS_ID,
      mode: { kind: "DIRECT_LEGACY" },
      alert: { title: "Synthetic title", body: "Synthetic body" },
    });

    expect(plan.kind).toBe("START");
    expect(plan.eventTimestamp).toBe(1_789_207_200);
    expect(plan.correlation).toEqual({
      bindingId: BINDING_ID,
      sessionVersion: SESSION_VERSION,
      tokenGeneration: { clientGeneration: 3, serverRevision: 5 },
    });
    const request = plan.request.materializeForTransport();
    expect(request.endpoint).toBe("https://api.sandbox.push.apple.com:443");
    expect(request.path).toBe(`/3/device/${DIRECT_TOKEN.toString("hex")}`);
    expect(request.headers["apns-topic"]).toBe(
      "se.blick.commute.push-type.liveactivity",
    );
    expect(request.headers.authorization).toBe(`bearer ${RAW_PROVIDER_TOKEN}`);
    const body = parsedBody(plan);
    expect(body.aps.event).toBe("start");
    expect(body.aps["content-state"].departures).toEqual([
      expect.objectContaining({ departureId: "current" }),
    ]);
    expect(body.aps.attributes).toEqual({
      schemaVersion: 1,
      bindingId: BINDING_ID,
      sessionRevision: 7,
      commuteKind: "LINE_DIRECTION",
    });
    expect(target.tokenGeneration).toEqual({ clientGeneration: 3, serverRevision: 5 });
  });

  it("builds direct update and end requests with generation correlation", () => {
    const update = buildDirectLiveActivityUpdatePlan({
      target: directTarget(),
      publication: publication(),
      generatedAt: GENERATED_AT,
      staleAt: 1_789_207_260,
      bundleId: "se.blick.commute",
      providerToken: PROVIDER_TOKEN,
      priority: 10,
    });
    expect(update.kind).toBe("DIRECT_UPDATE");
    expect(update.correlation.tokenGeneration).toEqual({
      clientGeneration: 9,
      serverRevision: 11,
    });
    expect(parsedBody(update).aps).toMatchObject({
      event: "update",
      "stale-date": 1_789_207_260,
    });

    const end = buildDirectLiveActivityEndPlan({
      target: directTarget(),
      publication: publication(),
      generatedAt: GENERATED_AT,
      dismissalAt: 1_789_207_100,
      bundleId: "se.blick.commute",
      providerToken: PROVIDER_TOKEN,
      priority: 5,
    });
    expect(end.kind).toBe("DIRECT_END");
    expect(parsedBody(end).aps).toMatchObject({
      event: "end",
      "dismissal-date": 1_789_207_100,
    });
  });

  it("builds broadcast update/end descriptions without calling a transport", () => {
    const common = {
      target: broadcastTarget(),
      publication: publication(),
      generatedAt: GENERATED_AT,
      environment: "PRODUCTION" as const,
      bundleId: "se.blick.commute",
      providerToken: PROVIDER_TOKEN,
      channelId: CHANNEL_ID,
      priority: 1 as const,
      expiration: 1_789_207_500,
      requestId: REQUEST_ID,
    };
    const update = buildBroadcastLiveActivityUpdatePlan(common);
    expect(update.kind).toBe("BROADCAST_UPDATE");
    expect(update.correlation.tokenGeneration).toBeNull();
    const raw = update.request.materializeForTransport();
    expect(raw.endpoint).toBe("https://api-broadcast.push.apple.com:443");
    expect(raw.path).toBe("/4/broadcasts/apps/se.blick.commute");
    expect(raw.headers["apns-channel-id"]).toBe(CHANNEL_ID);
    expect(raw.headers["apns-priority"]).toBe("1");
    expect(parsedBody(update).aps.event).toBe("update");

    const end = buildBroadcastLiveActivityEndPlan({
      ...common,
      dismissalAt: 1_789_207_450,
    });
    expect(end.kind).toBe("BROADCAST_END");
    expect(parsedBody(end).aps.event).toBe("end");
    const diagnostic = JSON.stringify(end);
    expect(diagnostic).not.toContain(RAW_PROVIDER_TOKEN);
    expect(diagnostic).not.toContain(CHANNEL_ID);
    expect(diagnostic).not.toContain(DIRECT_TOKEN.toString("hex"));
  });

  it("requires the start capability to match the Phase 3B binding strategy", () => {
    expect(
      thrownBy(() =>
        buildLiveActivityStartPlan({
        target: startTarget("DIRECT_TOKEN"),
        publication: publication(),
        generatedAt: GENERATED_AT,
        bundleId: "se.blick.commute",
        providerToken: PROVIDER_TOKEN,
        priority: 5,
        mode: { kind: "BROADCAST_CHANNEL", channelId: CHANNEL_ID },
        alert: { title: "Synthetic title", body: "Synthetic body" },
        }),
      ),
    ).toMatchObject({ code: "START_MODE_MISMATCH" });

    const directI18Start = buildLiveActivityStartPlan({
      target: startTarget("DIRECT_TOKEN"),
      publication: publication(),
      generatedAt: GENERATED_AT,
      bundleId: "se.blick.commute",
      providerToken: PROVIDER_TOKEN,
      priority: 5,
      mode: { kind: "DIRECT_IOS_18" },
      alert: { title: "Synthetic title", body: "Synthetic body" },
    });
    expect(parsedBody(directI18Start).aps["input-push-token"]).toBe(1);

    expect(
      thrownBy(() =>
        buildLiveActivityStartPlan({
          target: startTarget("BROADCAST_CHANNEL"),
          publication: publication(),
          generatedAt: GENERATED_AT,
          bundleId: "se.blick.commute",
          providerToken: PROVIDER_TOKEN,
          priority: 5,
          mode: { kind: "DIRECT_LEGACY" },
          alert: { title: "Synthetic title", body: "Synthetic body" },
        }),
      ),
    ).toMatchObject({ code: "START_MODE_MISMATCH" });

    const broadcastStart = buildLiveActivityStartPlan({
      target: startTarget("BROADCAST_CHANNEL"),
      publication: publication(),
      generatedAt: GENERATED_AT,
      bundleId: "se.blick.commute",
      providerToken: PROVIDER_TOKEN,
      priority: 5,
      mode: { kind: "BROADCAST_CHANNEL", channelId: CHANNEL_ID },
      alert: { title: "Synthetic title", body: "Synthetic body" },
    });
    expect(parsedBody(broadcastStart).aps["input-push-channel"]).toBe(CHANNEL_ID);
    expect(broadcastStart.request.kind).toBe("DIRECT_LIVE_ACTIVITY");
  });

  it("fails closed when publication key, session revision, or commute kind differs", () => {
    const target = directTarget();
    const build = (value: AuthoritativeReadyLiveCommutePublication) =>
      buildDirectLiveActivityUpdatePlan({
        target,
        publication: value,
        generatedAt: GENERATED_AT,
        bundleId: "se.blick.commute",
        providerToken: PROVIDER_TOKEN,
        priority: 5,
      });

    expect(
      thrownBy(() =>
        build(
        publication({
          group: {
            ...publication().group,
            key: "different-publication" as PublicationKey,
          },
        }),
        ),
      ),
    ).toMatchObject({ code: "PUBLICATION_TARGET_MISMATCH" });
    expect(
      thrownBy(() =>
        build(
        publication({
          sessionVersions: [{ ...SESSION_VERSION, revision: 8 }],
        }),
        ),
      ),
    ).toMatchObject({ code: "PUBLICATION_TARGET_MISMATCH" });
    expect(
      thrownBy(() =>
        build(
        publication({
          group: {
            ...publication().group,
            query: {
              kind: "EXACT_DESTINATION",
              originId: "origin",
              destinationId: "destination",
              transportModes: ["BUS"],
              changesPreference: "BOTH",
              searchUntil: "2026-09-12T11:00:00.000Z",
              searchMode: "NOW",
              laterJourneyCount: 0,
            },
          },
        }),
        ),
      ),
    ).toMatchObject({ code: "PUBLICATION_TARGET_MISMATCH" });
  });
});
