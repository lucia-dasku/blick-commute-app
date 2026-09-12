import { describe, expect, it } from "vitest";
import { liveCommutePublicationKey } from "../src/liveCommute/model.js";
import type { LiveCommuteSnapshot } from "../src/liveCommute/snapshot.js";
import { createLiveCommuteInstallationService } from "../src/liveCommute/installationService.js";
import { InMemoryLiveCommuteSessionStore } from "../src/liveCommute/inMemoryLiveCommuteSessionStore.js";
import {
  apnsTransportNotAttempted,
  apnsTransportResponse,
  unknownApnsTransportOutcome,
  type ApnsTransport,
  type ApnsTransportResult,
} from "../src/liveCommute/apple/apnsTransport.js";
import { normalizeApnsTransportResponse } from "../src/liveCommute/apple/apnsProtocol.js";
import type {
  ApnsProviderTokenLease,
} from "../src/liveCommute/apple/apnsProviderTokenCache.js";
import { LazyApnsProviderTokenCache } from "../src/liveCommute/apple/apnsProviderTokenCache.js";
import type { SensitiveApnsProviderToken } from "../src/liveCommute/apple/apnsProviderToken.js";
import {
  createLiveActivityDeliveryResolver,
  type LiveActivityDeliveryResolver,
} from "../src/liveCommute/apple/deliveryResolver.js";
import { createLiveActivityDeliveryService } from "../src/liveCommute/apple/deliveryService.js";
import { InMemoryLiveActivityDeliveryStore } from "../src/liveCommute/apple/inMemoryLiveActivityDeliveryStore.js";
import { InMemoryLiveActivityDispatchStore } from "../src/liveCommute/apple/inMemoryLiveActivityDispatchStore.js";
import {
  createLiveActivityDirectDispatcher,
  type LiveActivityDirectDispatchInput,
} from "../src/liveCommute/apple/directDispatcher.js";
import { createAes256GcmActivityKitTokenProtector } from "../src/liveCommute/apple/tokenProtection.js";
import type { AuthoritativeReadyLiveCommutePublication } from "../src/liveCommute/apple/deliveryPlan.js";

const INITIAL_NOW = new Date("2026-09-12T06:00:00.000Z");
const ACTIVE_AT = new Date("2026-09-12T07:30:00.000Z");
const STARTS_AT = new Date("2026-09-12T07:00:00.000Z");
const ENDS_AT = new Date("2026-09-12T09:00:00.000Z");
const INSTALLATION_ID = "00000000-0000-4000-8000-000000000001";
const BINDING_ID = "10000000-0000-4000-8000-000000000001";
const DISPATCH_IDS = [
  "20000000-0000-4000-8000-000000000001",
  "20000000-0000-4000-8000-000000000002",
  "20000000-0000-4000-8000-000000000003",
  "20000000-0000-4000-8000-000000000004",
] as const;
const APNS_IDS = [
  "30000000-0000-4000-8000-000000000001",
  "30000000-0000-4000-8000-000000000002",
  "30000000-0000-4000-8000-000000000003",
  "30000000-0000-4000-8000-000000000004",
] as const;
const RESPONSE_ID = "40000000-0000-4000-8000-000000000001";
const PUSH_TOKEN = Buffer.from("synthetic-push-to-start-token-0001", "utf8");
const UPDATE_TOKEN_ONE = Buffer.from("synthetic-update-token-generation-one", "utf8");
const UPDATE_TOKEN_TWO = Buffer.from("synthetic-update-token-generation-two", "utf8");
const QUERY = Object.freeze({
  kind: "LINE_DIRECTION" as const,
  siteId: 9192,
  transportMode: "BUS",
  lineId: 57,
  directionCode: 1,
});

const PROVIDER_TOKEN: SensitiveApnsProviderToken = Object.freeze({
  issuedAt: Math.floor(ACTIVE_AT.getTime() / 1_000),
  revealForAuthorization: () => "synthetic.header.signature",
  toJSON: () => "[REDACTED]",
  toString: () => "[REDACTED]",
});

function successfulResponse(): ApnsTransportResult {
  return apnsTransportResponse(
    normalizeApnsTransportResponse({
      statusCode: 200,
      headers: { "apns-id": RESPONSE_ID },
    }),
  );
}

function response(statusCode: number, reason: string): ApnsTransportResult {
  return apnsTransportResponse(
    normalizeApnsTransportResponse({
      statusCode,
      body: JSON.stringify({ reason }),
    }),
  );
}

class RecordingTransport implements ApnsTransport {
  readonly diagnostics: unknown[] = [];
  #index = 0;

  constructor(
    private readonly results: readonly ApnsTransportResult[],
    private readonly beforeResponse?: (sendIndex: number) => Promise<void>,
  ) {}

  get sendCount(): number {
    return this.#index;
  }

  async send(request: Parameters<ApnsTransport["send"]>[0]) {
    const index = this.#index;
    this.#index += 1;
    this.diagnostics.push(request.toRedactedDiagnostic());
    await this.beforeResponse?.(index);
    const result = this.results[index];
    if (result == null) throw new Error("synthetic transport script exhausted");
    return result;
  }

  async close(): Promise<void> {}
}

class ProviderCacheStub {
  readonly lease: ApnsProviderTokenLease = Object.freeze({
    generation: 1,
    issuedAt: PROVIDER_TOKEN.issuedAt,
    token: PROVIDER_TOKEN,
  });
  getCount = 0;
  invalidateCount = 0;
  readonly returnedLeases: ApnsProviderTokenLease[] = [];

  getToken(): ApnsProviderTokenLease {
    this.getCount += 1;
    this.returnedLeases.push(this.lease);
    return this.lease;
  }

  invalidateIfCurrent(lease: ApnsProviderTokenLease): boolean {
    if (lease !== this.lease) return false;
    this.invalidateCount += 1;
    return true;
  }
}

function snapshot(): LiveCommuteSnapshot {
  return {
    kind: "LINE_DIRECTION",
    freshness: "FRESH",
    sourceFetchedAt: "2026-09-12T07:29:30.000Z",
    generatedAt: "2026-09-12T07:29:31.000Z",
    departures: [
      {
        departureId: "departure-1",
        lineDesignation: "57",
        direction: "Centralen",
        destination: "Centralen",
        scheduledTime: "2026-09-12T08:00:00.000Z",
        expectedTime: "2026-09-12T08:01:00.000Z",
        effectiveTime: "2026-09-12T08:01:00.000Z",
        isCancelled: false,
        state: "EXPECTED",
        journeyState: "NORMAL",
        predictionState: "REALTIME",
      },
    ],
  };
}

function publication(installationId: string): AuthoritativeReadyLiveCommutePublication {
  return {
    status: "READY",
    group: {
      key: liveCommutePublicationKey(QUERY),
      acquisitionKey: "synthetic-acquisition" as never,
      query: QUERY,
      sessions: [],
      validatedAt: ACTIVE_AT.toISOString(),
    },
    snapshot: snapshot(),
    contentChanged: true,
    sessionVersions: [
      { installationId, sessionId: "occurrence-1", revision: 1 },
    ],
    authorityCheckCompletedAt: ACTIVE_AT.toISOString(),
  } as AuthoritativeReadyLiveCommutePublication;
}

function nextValue(values: readonly string[], index: number): string {
  const value = values[index];
  if (value == null) throw new Error("synthetic UUID fixture exhausted");
  return value;
}

function harness() {
  let now = new Date(INITIAL_NOW);
  let nonceSeed = 1;
  const sessionStore = new InMemoryLiveCommuteSessionStore();
  const deliveryStore = new InMemoryLiveActivityDeliveryStore(sessionStore);
  const protector = createAes256GcmActivityKitTokenProtector(
    Uint8Array.from({ length: 32 }, (_, index) => index + 1),
    { randomBytes: (size) => Buffer.alloc(size, nonceSeed++) },
  );
  const installationService = createLiveCommuteInstallationService(sessionStore, {
    now: () => new Date(now),
    randomUuid: () => INSTALLATION_ID,
    randomBytes: (size) => Buffer.alloc(size, 0x51),
  });
  const deliveryService = createLiveActivityDeliveryService(
    deliveryStore,
    protector,
    {
      now: () => new Date(now),
      randomUuid: () => BINDING_ID,
    },
  );
  const resolver = createLiveActivityDeliveryResolver(deliveryStore, protector, {
    now: () => new Date(now),
  });
  const dispatchStore = new InMemoryLiveActivityDispatchStore(deliveryStore);

  return {
    sessionStore,
    deliveryStore,
    installationService,
    deliveryService,
    resolver,
    dispatchStore,
    now: () => new Date(now),
    setNow(value: Date) {
      now = new Date(value);
    },
  };
}

async function establish(
  value: ReturnType<typeof harness>,
  strategy: "DIRECT_TOKEN" | "BROADCAST_CHANNEL" = "DIRECT_TOKEN",
) {
  const authentication = await value.installationService.registerInstallation();
  await value.installationService.registerSession(authentication, {
    sessionId: "occurrence-1",
    routineId: "routine-1",
    startsAt: STARTS_AT,
    endsAt: ENDS_AT,
    query: QUERY,
  });
  const binding = await value.deliveryService.createDeliveryBinding(
    authentication,
    { sessionId: "occurrence-1", sessionRevision: 1, strategy },
  );
  await value.deliveryService.registerPushToStartToken(authentication, {
    token: PUSH_TOKEN,
    clientGeneration: 1,
    environment: "SANDBOX",
  });
  value.setNow(ACTIVE_AT);
  return { authentication, binding: binding.binding };
}

async function addUpdateToken(
  value: ReturnType<typeof harness>,
  authentication: Awaited<ReturnType<ReturnType<typeof harness>["installationService"]["registerInstallation"]>>,
  bindingId: string,
  generation: 1 | 2,
) {
  return await value.deliveryService.registerUpdateToken(authentication, {
    bindingId,
    token: generation === 1 ? UPDATE_TOKEN_ONE : UPDATE_TOKEN_TWO,
    clientGeneration: generation,
    environment: "SANDBOX",
  });
}

function createDispatcher(
  value: ReturnType<typeof harness>,
  resolver: LiveActivityDeliveryResolver,
  transport: ApnsTransport,
  cache: Pick<LazyApnsProviderTokenCache, "getToken" | "invalidateIfCurrent">,
) {
  let uuidIndex = 0;
  const uuids = DISPATCH_IDS.flatMap((dispatchId, index) => [dispatchId, APNS_IDS[index]!]);
  return createLiveActivityDirectDispatcher({
    store: value.dispatchStore,
    resolver,
    providerTokenCache: cache,
    transport,
    bundleId: "se.blick.commute",
    now: value.now,
    createUuid: () => nextValue(uuids, uuidIndex++),
  });
}

function commonInput(
  installationId: string,
  operation: LiveActivityDirectDispatchInput["operation"],
  generatedAt: Date,
): Omit<LiveActivityDirectDispatchInput, "operation"> {
  return {
    installationId,
    bindingId: BINDING_ID,
    sessionRevision: 1,
    publication: publication(installationId),
    generatedAt,
    priority: 5,
  } as Omit<LiveActivityDirectDispatchInput, "operation">;
}

describe("one-shot Live Activity direct dispatcher", () => {
  it("sends START, UPDATE, and END exactly once with durable safe correlations", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([
      successfulResponse(),
      successfulResponse(),
      successfulResponse(),
    ]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const start = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "START", ACTIVE_AT),
      operation: "START",
      mode: { kind: "DIRECT_IOS_18" },
      alert: { title: "Commute", body: "Live commute started" },
    });
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const update = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "DIRECT_UPDATE",
        new Date(ACTIVE_AT.getTime() + 1_000),
      ),
      operation: "DIRECT_UPDATE",
      staleAt: new Date(ACTIVE_AT.getTime() + 60_000),
    });
    const end = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "DIRECT_END",
        new Date(ACTIVE_AT.getTime() + 2_000),
      ),
      operation: "DIRECT_END",
      dismissalAt: new Date(ACTIVE_AT.getTime() + 2_000),
    });

    expect([start, update, end]).toMatchObject([
      { outcome: "RECORDED", attempt: { operation: "START", state: "ACCEPTED" } },
      {
        outcome: "RECORDED",
        attempt: { operation: "DIRECT_UPDATE", state: "ACCEPTED" },
      },
      {
        outcome: "RECORDED",
        attempt: { operation: "DIRECT_END", state: "ACCEPTED" },
      },
    ]);
    expect(transport.sendCount).toBe(3);
    expect(transport.diagnostics).toMatchObject([
      { event: "start", pathTemplate: "/3/device/<redacted>" },
      { event: "update", pathTemplate: "/3/device/<redacted>" },
      { event: "end", pathTemplate: "/3/device/<redacted>" },
    ]);
    expect(cache.getCount).toBe(3);
    for (const result of [start, update, end]) {
      if (result.outcome !== "RECORDED") throw new Error("expected dispatch record");
      expect(result.attempt.payloadFingerprint).toMatch(/^[0-9a-f]{64}$/);
      expect(result.attempt.apnsRequestId).toMatch(/^[0-9a-f-]{36}$/);
      expect(result.attempt.postSendAuthority).toBe("MATCHED");
      expect(JSON.stringify(result)).not.toContain("synthetic-update-token");
      expect(JSON.stringify(result)).not.toContain("synthetic.header.signature");
    }
  });

  it("blocks duplicate START and later UPDATE work after terminal END intent", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([
      successfulResponse(),
      unknownApnsTransportOutcome("GOAWAY"),
    ]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "START", ACTIVE_AT),
      operation: "START",
      mode: { kind: "DIRECT_LEGACY" },
      alert: { title: "Commute", body: "Live commute started" },
    });
    const duplicate = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "START",
        new Date(ACTIVE_AT.getTime() + 1_000),
      ),
      operation: "START",
      mode: { kind: "DIRECT_LEGACY" },
      alert: { title: "Commute", body: "Live commute started" },
    });
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const ended = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "DIRECT_END",
        new Date(ACTIVE_AT.getTime() + 2_000),
      ),
      operation: "DIRECT_END",
    });
    const staleUpdate = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "DIRECT_UPDATE",
        new Date(ACTIVE_AT.getTime() + 3_000),
      ),
      operation: "DIRECT_UPDATE",
    });

    expect(duplicate).toMatchObject({
      outcome: "NOT_RESERVED",
      reservation: { status: "START_BLOCKED" },
    });
    expect(ended).toMatchObject({
      outcome: "RECORDED",
      attempt: {
        operation: "DIRECT_END",
        state: "OUTCOME_UNKNOWN",
        retryAdvice: "OUTCOME_UNKNOWN",
      },
    });
    expect(staleUpdate).toMatchObject({
      outcome: "NOT_RESERVED",
      reservation: { status: "TERMINAL_INTENT" },
    });
    expect(transport.sendCount).toBe(2);
  });

  it("re-resolves after reservation and aborts cancellation with zero APNs traffic", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([successfulResponse()]);
    const cancellingResolver: LiveActivityDeliveryResolver = {
      resolveStartTarget: value.resolver.resolveStartTarget.bind(value.resolver),
      resolveUpdateTarget: async (input) => {
        await value.installationService.cancelSession(authentication, {
          sessionId: "occurrence-1",
          expectedRevision: 1,
        });
        return await value.resolver.resolveUpdateTarget(input);
      },
    } as LiveActivityDeliveryResolver;
    const dispatcher = createDispatcher(value, cancellingResolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });

    expect(result).toMatchObject({
      outcome: "NOT_SENT",
      reason: "TARGET_NO_LONGER_AUTHORIZED",
      abortRecorded: true,
      attempt: { state: "ABORTED" },
    });
    expect(transport.sendCount).toBe(0);
    expect(cache.getCount).toBe(0);
  });

  it("performs the final transactional claim after resolution and catches token rotation", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([successfulResponse()]);
    const rotatingResolver: LiveActivityDeliveryResolver = {
      resolveStartTarget: value.resolver.resolveStartTarget.bind(value.resolver),
      resolveUpdateTarget: async (input) => {
        const target = await value.resolver.resolveUpdateTarget(input);
        await addUpdateToken(value, authentication, BINDING_ID, 2);
        return target;
      },
    } as LiveActivityDeliveryResolver;
    const dispatcher = createDispatcher(value, rotatingResolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });

    expect(result).toMatchObject({
      outcome: "NOT_SENT",
      reason: "ABORTED_AT_CLAIM",
      attempt: { state: "ABORTED" },
    });
    expect(transport.sendCount).toBe(0);
  });

  it("aborts exact-session replacement during the post-reservation target check", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([successfulResponse()]);
    const replacingResolver: LiveActivityDeliveryResolver = {
      resolveStartTarget: value.resolver.resolveStartTarget.bind(value.resolver),
      resolveUpdateTarget: async (input) => {
        await value.installationService.replaceSession(authentication, {
          sessionId: "occurrence-1",
          routineId: "routine-1",
          startsAt: STARTS_AT,
          endsAt: ENDS_AT,
          query: { ...QUERY, lineId: 3, directionCode: 2 },
          expectedRevision: 1,
        });
        return await value.resolver.resolveUpdateTarget(input);
      },
    } as LiveActivityDeliveryResolver;
    const dispatcher = createDispatcher(value, replacingResolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });

    expect(result).toMatchObject({
      outcome: "NOT_SENT",
      reason: "TARGET_NO_LONGER_AUTHORIZED",
      abortRecorded: true,
      attempt: { state: "ABORTED" },
    });
    expect(transport.sendCount).toBe(0);
    expect(cache.getCount).toBe(0);
  });

  it("never lets a delayed terminal response invalidate a newer token generation", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport(
      [response(410, "Unregistered")],
      async () => {
        await addUpdateToken(value, authentication, BINDING_ID, 2);
      },
    );
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });
    const latest = await value.deliveryStore.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => await transaction.getLatestUpdateToken(BINDING_ID),
    );

    expect(result).toMatchObject({
      outcome: "RECORDED",
      attempt: {
        state: "REJECTED",
        retryAdvice: "PERMANENT_DESTINATION_FAILURE",
        tokenInvalidationOutcome: "GENERATION_NO_LONGER_CURRENT",
      },
    });
    expect(latest).toMatchObject({ clientGeneration: 2, lifecycle: "CURRENT" });
    expect(transport.sendCount).toBe(1);
  });

  it("invalidates only the exact terminal destination generation", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([response(400, "BadDeviceToken")]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });
    const latest = await value.deliveryStore.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => await transaction.getLatestUpdateToken(BINDING_ID),
    );

    expect(result).toMatchObject({
      outcome: "RECORDED",
      attempt: {
        state: "REJECTED",
        tokenInvalidationOutcome: "INVALIDATED_EXACT_GENERATION",
      },
    });
    expect(latest).toMatchObject({ clientGeneration: 1, lifecycle: "INVALIDATED" });
    expect(cache.invalidateCount).toBe(0);
  });

  it("marks authority changes after APNs acceptance without undoing the send", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([successfulResponse()], async () => {
      await value.installationService.cancelSession(authentication, {
        sessionId: "occurrence-1",
        expectedRevision: 1,
      });
    });
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });

    expect(result).toMatchObject({
      outcome: "RECORDED",
      attempt: { state: "ACCEPTED", postSendAuthority: "CHANGED" },
    });
    expect(transport.sendCount).toBe(1);
  });

  it("classifies provider auth, throttling, and server retry without automatic resend", async () => {
    const cases = [
      {
        apns: response(403, "ExpiredProviderToken"),
        state: "RETRYABLE",
        advice: "REFRESH_PROVIDER_TOKEN",
        invalidations: 1,
        hasNotBefore: false,
      },
      {
        apns: response(403, "InvalidProviderToken"),
        state: "REJECTED",
        advice: "OPERATOR_CONFIGURATION_REQUIRED",
        invalidations: 0,
        hasNotBefore: false,
      },
      {
        apns: response(429, "TooManyProviderTokenUpdates"),
        state: "REJECTED",
        advice: "OPERATOR_CONFIGURATION_REQUIRED",
        invalidations: 0,
        hasNotBefore: false,
      },
      {
        apns: response(429, "TooManyRequests"),
        state: "RETRYABLE",
        advice: "RETRY_THROTTLED",
        invalidations: 0,
        hasNotBefore: false,
      },
      {
        apns: response(413, "PayloadTooLarge"),
        state: "REJECTED",
        advice: "PERMANENT_PAYLOAD_FAILURE",
        invalidations: 0,
        hasNotBefore: false,
      },
      {
        apns: response(503, "ServiceUnavailable"),
        state: "RETRYABLE",
        advice: "RETRY_AFTER_APPLE_BACKOFF",
        invalidations: 0,
        hasNotBefore: true,
      },
      {
        apns: response(503, "ExpiredProviderToken"),
        state: "RETRYABLE",
        advice: "RETRY_AFTER_APPLE_BACKOFF",
        invalidations: 0,
        hasNotBefore: true,
      },
    ] as const;

    for (const testCase of cases) {
      const value = harness();
      const { authentication } = await establish(value);
      await addUpdateToken(value, authentication, BINDING_ID, 1);
      const cache = new ProviderCacheStub();
      const transport = new RecordingTransport([testCase.apns]);
      const dispatcher = createDispatcher(value, value.resolver, transport, cache);

      const result = await dispatcher.dispatch({
        ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
        operation: "DIRECT_UPDATE",
      });

      expect(result).toMatchObject({
        outcome: "RECORDED",
        attempt: {
          state: testCase.state,
          retryAdvice: testCase.advice,
          tokenInvalidationOutcome: "NOT_APPLICABLE",
        },
      });
      if (result.outcome !== "RECORDED") throw new Error("expected dispatch record");
      expect(result.attempt.retryNotBefore == null).toBe(!testCase.hasNotBefore);
      if (testCase.hasNotBefore) {
        expect(result.attempt.retryNotBefore).toEqual(
          new Date(ACTIVE_AT.getTime() + 15 * 60 * 1_000),
        );
      }
      expect(cache.invalidateCount).toBe(testCase.invalidations);
      expect(transport.sendCount).toBe(1);
    }
  });

  it("allows a newer UPDATE after an unknown connection outcome and reuses the provider lease", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([
      unknownApnsTransportOutcome("CONNECTION_ERROR"),
      successfulResponse(),
    ]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const first = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });
    expect(first).toMatchObject({
      outcome: "RECORDED",
      attempt: { state: "OUTCOME_UNKNOWN", retryAdvice: "OUTCOME_UNKNOWN" },
    });
    expect(transport.sendCount).toBe(1);

    const second = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "DIRECT_UPDATE",
        new Date(ACTIVE_AT.getTime() + 1_000),
      ),
      operation: "DIRECT_UPDATE",
    });
    expect(second).toMatchObject({
      outcome: "RECORDED",
      attempt: { state: "ACCEPTED" },
    });
    expect(transport.sendCount).toBe(2);
    expect(cache.returnedLeases).toEqual([cache.lease, cache.lease]);
    expect(cache.invalidateCount).toBe(0);
  });

  it("records a proven local no-send as ABORTED without freezing a later START", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([
      apnsTransportNotAttempted("TRANSPORT_CLOSED"),
      successfulResponse(),
    ]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const first = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "START", ACTIVE_AT),
      operation: "START",
      mode: { kind: "DIRECT_LEGACY" },
      alert: { title: "Commute", body: "Live commute started" },
    });
    const second = await dispatcher.dispatch({
      ...commonInput(
        authentication.installationId,
        "START",
        new Date(ACTIVE_AT.getTime() + 1_000),
      ),
      operation: "START",
      mode: { kind: "DIRECT_LEGACY" },
      alert: { title: "Commute", body: "Live commute started" },
    });

    expect(first).toMatchObject({
      outcome: "NOT_SENT",
      reason: "TRANSPORT_CLOSED",
      networkAttempted: false,
      abortRecorded: true,
      attempt: { state: "ABORTED" },
    });
    expect(second).toMatchObject({
      outcome: "RECORDED",
      networkAttempted: true,
      attempt: { state: "ACCEPTED" },
    });
    expect(transport.sendCount).toBe(2);
  });

  it("durably records the provider-cache 20-minute refresh floor without sending", async () => {
    const value = harness();
    const { authentication } = await establish(value);
    await addUpdateToken(value, authentication, BINDING_ID, 1);
    const cache = new LazyApnsProviderTokenCache(
      {
        sign: ({ issuedAt }) => {
          const seconds = typeof issuedAt === "number"
            ? issuedAt
            : Math.floor(issuedAt.getTime() / 1_000);
          return Object.freeze({
            issuedAt: seconds,
            revealForAuthorization: () => "synthetic.header.signature",
            toJSON: () => "[REDACTED]",
            toString: () => "[REDACTED]",
          });
        },
      },
      { now: value.now },
    );
    cache.getToken();
    cache.invalidate();
    const transport = new RecordingTransport([successfulResponse()]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "DIRECT_UPDATE", ACTIVE_AT),
      operation: "DIRECT_UPDATE",
    });

    expect(result).toMatchObject({
      outcome: "NOT_SENT",
      reason: "PROVIDER_TOKEN_UNAVAILABLE",
      abortRecorded: true,
      attempt: {
        state: "ABORTED",
        retryAdvice: "REFRESH_PROVIDER_TOKEN",
        retryNotBefore: new Date(ACTIVE_AT.getTime() + 20 * 60 * 1_000),
      },
    });
    expect(transport.sendCount).toBe(0);
  });

  it("refuses broadcast bindings before signing or transport", async () => {
    const value = harness();
    const { authentication } = await establish(value, "BROADCAST_CHANNEL");
    const cache = new ProviderCacheStub();
    const transport = new RecordingTransport([successfulResponse()]);
    const dispatcher = createDispatcher(value, value.resolver, transport, cache);

    const result = await dispatcher.dispatch({
      ...commonInput(authentication.installationId, "START", ACTIVE_AT),
      operation: "START",
      mode: { kind: "DIRECT_IOS_18" },
      alert: { title: "Commute", body: "Live commute started" },
    });

    expect(result).toMatchObject({
      outcome: "NOT_RESERVED",
      reservation: { status: "NOT_DIRECT" },
    });
    expect(cache.getCount).toBe(0);
    expect(transport.sendCount).toBe(0);
  });
});
