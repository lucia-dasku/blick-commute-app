import { Hono } from "hono";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { onError } from "../src/middleware/errorHandler.js";
import { createLiveCommuteRoute } from "../src/routes/liveCommute.js";
import { InMemoryLiveCommuteSessionStore } from "../src/liveCommute/inMemoryLiveCommuteSessionStore.js";
import { createLiveCommuteInstallationService } from "../src/liveCommute/installationService.js";
import { InMemoryLiveActivityDeliveryStore } from "../src/liveCommute/apple/inMemoryLiveActivityDeliveryStore.js";
import { createLiveActivityDeliveryService } from "../src/liveCommute/apple/deliveryService.js";
import { createAes256GcmActivityKitTokenProtector } from "../src/liveCommute/apple/tokenProtection.js";
import {
  LiveActivityClientPublicationStateService,
} from "../src/liveCommute/apple/clientPublicationState.js";
import { InMemoryLiveActivityClientPublicationStateStore } from "../src/liveCommute/apple/inMemoryLiveActivityClientPublicationStateStore.js";

const NOW = "2026-09-13T08:00:00.000Z";
const INSTALLATION_ID = "11111111-1111-4111-8111-111111111111";
const BINDING_ID = "22222222-2222-4222-8222-222222222222";
const RAW_TOKEN = Buffer.from("private-activity-token");
const ENCODED_TOKEN = RAW_TOKEN.toString("base64url");

interface Fixture {
  readonly app: Hono;
  readonly stateStore: InMemoryLiveActivityClientPublicationStateStore;
  readonly schedule: ReturnType<typeof vi.fn<() => Promise<void>>>;
  readonly credential: string;
}

interface TestSessionResponse {
  readonly session: {
    readonly sessionId: string;
    readonly query: Record<string, unknown>;
  };
  readonly revision: number;
}

function lineSession(
  sessionId = "line-session",
  startsAt = "2026-09-13T08:30:00.000Z",
  endsAt = "2026-09-13T09:00:00.000Z",
) {
  return {
    sessionId,
    routineId: `routine-${sessionId}`,
    startsAt,
    endsAt,
    query: {
      kind: "LINE_DIRECTION",
      siteId: 9192,
      transportMode: "BUS",
      lineId: 4,
      directionCode: 1,
    },
  } as const;
}

function exactSession() {
  return {
    sessionId: "exact-session",
    routineId: "exact-routine",
    startsAt: "2026-09-13T09:00:00.000Z",
    endsAt: "2026-09-13T10:00:00.000Z",
    query: {
      kind: "EXACT_DESTINATION",
      originId: "9192",
      destinationId: "9001",
      transportModes: ["METRO", "BUS"],
      changesPreference: "BOTH",
      searchUntil: "2026-09-13T10:15:00.000Z",
    },
  } as const;
}

async function fixture(): Promise<Fixture> {
  const coreStore = new InMemoryLiveCommuteSessionStore();
  const installationService = createLiveCommuteInstallationService(coreStore, {
    now: () => new Date(NOW),
    randomBytes: () => Buffer.alloc(32, 0x31),
    randomUuid: () => INSTALLATION_ID,
  });
  const deliveryStore = new InMemoryLiveActivityDeliveryStore(coreStore);
  const deliveryService = createLiveActivityDeliveryService(
    deliveryStore,
    createAes256GcmActivityKitTokenProtector(Buffer.alloc(32, 0x44)),
    { now: () => new Date(NOW), randomUuid: () => BINDING_ID },
  );
  const stateStore = new InMemoryLiveActivityClientPublicationStateStore(coreStore);
  const clientStateService = new LiveActivityClientPublicationStateService(
    stateStore,
    () => new Date(NOW),
  );
  const schedule = vi.fn<() => Promise<void>>(async () => undefined);
  const app = new Hono().basePath("/api/v1");
  app.route(
    "/live-commute",
    createLiveCommuteRoute({
      installationService,
      deliveryService,
      clientStateService,
      ensureCycleScheduled: schedule,
    }),
  );
  app.onError(onError);
  const response = await app.request("/api/v1/live-commute/installations", {
    method: "POST",
  });
  const body = await response.json() as {
    data: { installationId: string; bearerCredential: string };
  };
  return { app, stateStore, schedule, credential: body.data.bearerCredential };
}

function headers(value: Fixture, credential = value.credential) {
  return {
    Authorization: `Bearer ${credential}`,
    "Content-Type": "application/json",
  };
}

async function postSession(value: Fixture, body: unknown = lineSession()) {
  return await value.app.request(
    `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions`,
    { method: "POST", headers: headers(value), body: JSON.stringify(body) },
  );
}

describe("iOS live commute HTTP API", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("issues the bearer credential once and never exposes its digest", async () => {
    const value = await fixture();
    expect(value.credential).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const created = await postSession(value);
    expect(created.status).toBe(201);
    const listed = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions`,
      { headers: headers(value) },
    );
    expect(listed.status).toBe(200);
    const text = await listed.text();
    expect(text).not.toContain(value.credential);
    expect(text).not.toMatch(/credentialDigest|credential_digest|protectedToken|token_digest/);
  });

  it("returns the same sanitized 401 for missing, malformed, and wrong credentials", async () => {
    const value = await fixture();
    const url = `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions`;
    for (const authorization of [undefined, "Basic nope", `Bearer ${"A".repeat(43)}`]) {
      const response = await value.app.request(url, {
        headers: authorization == null ? {} : { Authorization: authorization },
      });
      expect(response.status).toBe(401);
      expect(await response.json()).toEqual({
        schemaVersion: 1,
        error: {
          code: "AUTHENTICATION_ERROR",
          message: "Installation authentication failed",
        },
      });
    }
  });

  it("accepts both query kinds, preserves exact semantics, and allows touching windows", async () => {
    const value = await fixture();
    expect((await postSession(value)).status).toBe(201);
    expect((await postSession(value, exactSession())).status).toBe(201);

    const response = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions`,
      { headers: headers(value) },
    );
    const body = await response.json() as {
      data: { sessions: TestSessionResponse[] };
    };
    expect(body.data.sessions).toHaveLength(2);
    const exact = body.data.sessions.find(
      (session) => session.session.sessionId === "exact-session",
    );
    expect(exact?.session.query).toMatchObject({
      kind: "EXACT_DESTINATION",
      transportModes: ["METRO", "BUS"],
      changesPreference: "BOTH",
      searchMode: "NOW",
      laterJourneyCount: 0,
    });
  });

  it("maps overlap, replacement revision, cancellation, and missing-session outcomes", async () => {
    const value = await fixture();
    await postSession(value);
    const overlap = await postSession(
      value,
      lineSession(
        "overlap",
        "2026-09-13T08:45:00.000Z",
        "2026-09-13T09:15:00.000Z",
      ),
    );
    expect(overlap.status).toBe(409);

    const replace = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions/line-session`,
      {
        method: "PUT",
        headers: headers(value),
        body: JSON.stringify({
          ...lineSession(),
          sessionId: undefined,
          expectedRevision: 1,
          routineId: "changed",
        }),
      },
    );
    expect(replace.status).toBe(200);
    expect((await replace.json() as {
      data: { session: { revision: number } };
    }).data.session.revision).toBe(2);
    expect(value.schedule).toHaveBeenCalledTimes(2);

    const staleCancel = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions/line-session/cancel`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({ expectedRevision: 1 }),
      },
    );
    expect(staleCancel.status).toBe(409);
    const cancel = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/sessions/line-session/cancel`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({ expectedRevision: 2 }),
      },
    );
    expect(cancel.status).toBe(200);
    expect((await cancel.json() as { data: { status: string } }).data.status).toBe(
      "CANCELLED",
    );
  });

  it("rotates and invalidates push-to-start tokens without echoing raw token material", async () => {
    const value = await fixture();
    for (const clientGeneration of [1, 2]) {
      const response = await value.app.request(
        `/api/v1/live-commute/installations/${INSTALLATION_ID}/push-to-start-token`,
        {
          method: "POST",
          headers: headers(value),
          body: JSON.stringify({
            token: Buffer.from(`${ENCODED_TOKEN}-${clientGeneration}`).toString("base64url"),
            clientGeneration,
            environment: "SANDBOX",
          }),
        },
      );
      expect(response.status).toBe(200);
      const text = await response.text();
      expect(text).not.toContain(ENCODED_TOKEN);
      expect(text).not.toMatch(/protectedToken|ciphertext|digest|nonce|authTag/);
    }
    const invalidated = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/push-to-start-token/invalidate`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({ expectedClientGeneration: 2 }),
      },
    );
    expect(invalidated.status).toBe(200);
    expect((await invalidated.json() as {
      data: { token: { lifecycle: string } };
    }).data.token.lifecycle).toBe("INVALIDATED");
  });

  it("creates only direct bindings and handles update token, activity id, and lifecycle", async () => {
    const value = await fixture();
    await postSession(value);
    const created = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({
          sessionId: "line-session",
          sessionRevision: 1,
          strategy: "DIRECT_TOKEN",
        }),
      },
    );
    expect(created.status).toBe(201);
    const binding = (await created.json() as {
      data: { binding: { bindingId: string; strategy: string } };
    }).data.binding;
    expect(binding).toMatchObject({ bindingId: BINDING_ID, strategy: "DIRECT_TOKEN" });

    const broadcast = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({
          sessionId: "line-session",
          sessionRevision: 1,
          strategy: "BROADCAST_CHANNEL",
        }),
      },
    );
    expect(broadcast.status).toBe(400);

    const updateToken = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings/${BINDING_ID}/update-token`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({
          token: ENCODED_TOKEN,
          clientGeneration: 1,
          environment: "PRODUCTION",
        }),
      },
    );
    expect(updateToken.status).toBe(200);
    expect(await updateToken.text()).not.toContain(ENCODED_TOKEN);

    const updateInvalidated = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings/${BINDING_ID}/update-token/invalidate`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({ expectedClientGeneration: 1 }),
      },
    );
    expect(updateInvalidated.status).toBe(200);

    const attached = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings/${BINDING_ID}/apple-activity`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({ appleActivityId: "activity-1" }),
      },
    );
    expect(attached.status).toBe(200);
    const ended = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings/${BINDING_ID}/end`,
      { method: "POST", headers: headers(value), body: "{}" },
    );
    expect(ended.status).toBe(200);
    expect((await ended.json() as {
      data: { binding: { lifecycle: string } };
    }).data.binding.lifecycle).toBe("ENDED");

    const invalidationFixture = await fixture();
    await postSession(invalidationFixture);
    await invalidationFixture.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings`,
      {
        method: "POST",
        headers: headers(invalidationFixture),
        body: JSON.stringify({
          sessionId: "line-session",
          sessionRevision: 1,
          strategy: "DIRECT_TOKEN",
        }),
      },
    );
    const bindingInvalidated = await invalidationFixture.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/delivery-bindings/${BINDING_ID}/invalidate`,
      {
        method: "POST",
        headers: headers(invalidationFixture),
        body: "{}",
      },
    );
    expect(bindingInvalidated.status).toBe(200);
    expect((await bindingInvalidated.json() as {
      data: { binding: { lifecycle: string } };
    }).data.binding.lifecycle).toBe("INVALIDATED");
  });

  it("persists exact client publication state for the authenticated installation", async () => {
    const value = await fixture();
    const response = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/publication-state`,
      {
        method: "PUT",
        headers: headers(value),
        body: JSON.stringify({
          capability: "DIRECT_IOS18",
          frequentPushes: "ENABLED",
          locale: "sv",
        }),
      },
    );
    expect(response.status).toBe(200);
    expect(await value.stateStore.getState(INSTALLATION_ID)).toMatchObject({
      capability: "DIRECT_IOS18",
      frequentPushes: "ENABLED",
      locale: "sv",
    });
  });

  it("seeds after commit and makes a scheduling failure safely retryable", async () => {
    const value = await fixture();
    value.schedule.mockRejectedValueOnce(new Error("synthetic queue outage"));
    const first = await postSession(value);
    expect(first.status).toBe(503);
    expect(await first.json()).toEqual({
      schemaVersion: 1,
      error: {
        code: "SERVICE_UNAVAILABLE",
        message: "Live commute scheduling is temporarily unavailable",
      },
    });
    const retried = await postSession(value);
    expect(retried.status).toBe(200);
    expect((await retried.json() as { data: { status: string } }).data.status).toBe(
      "UNCHANGED",
    );
    expect(value.schedule).toHaveBeenCalledTimes(2);
  });

  it("rejects malformed bodies and noncanonical base64url before token persistence", async () => {
    const value = await fixture();
    const malformed = await value.app.request(
      `/api/v1/live-commute/installations/${INSTALLATION_ID}/push-to-start-token`,
      {
        method: "POST",
        headers: headers(value),
        body: JSON.stringify({
          token: "YWJj=",
          clientGeneration: 1,
          environment: "SANDBOX",
          extra: true,
        }),
      },
    );
    expect(malformed.status).toBe(400);
    expect(await malformed.text()).not.toContain("YWJj=");
  });
});
