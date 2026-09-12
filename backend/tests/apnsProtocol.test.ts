import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import {
  buildActivityKitEndPayload,
  buildActivityKitStartPayload,
  buildActivityKitUpdatePayload,
  type BuiltActivityKitPayload,
} from "../src/liveCommute/apple/activityKitPayload.js";
import {
  APNS_ENVIRONMENT_ENDPOINTS,
  apnsEnvironmentEndpoints,
  classifyApnsBroadcastResponse,
  classifyApnsChannelResponse,
  classifyApnsDeviceResponse,
  createApnsChannelCreateRequestDescription,
  createApnsChannelDeleteRequestDescription,
  createApnsChannelListRequestDescription,
  createApnsChannelReadRequestDescription,
  createBroadcastLiveActivityRequestDescription,
  createDirectLiveActivityRequestDescription,
  normalizeApnsTransportResponse,
  type ApnsRequestDescription,
  type RawApnsTransportResponse,
} from "../src/liveCommute/apple/apnsProtocol.js";
import {
  createBlickLiveActivityAttributes,
  type BlickLiveActivityLineContentStateV1,
} from "../src/liveCommute/apple/liveActivityWireContract.js";

const BUNDLE_ID = "se.blick.app";
const PROVIDER_TOKEN = "c2Vuc2l0aXZlLWp3dC1oZWFkZXI.c2Vuc2l0aXZlLWp3dC1ib2R5.c2Vuc2l0aXZlLWp3dC1zaWduYXR1cmU";
const DEVICE_TOKEN = Buffer.from("synthetic-sensitive-device-token", "utf8");
const DEVICE_TOKEN_HEX = DEVICE_TOKEN.toString("hex");
const CHANNEL_ID = Buffer.from("synthetic-sensitive-channel", "utf8").toString(
  "base64",
);
const SECOND_CHANNEL_ID = Buffer.from("second-sensitive-channel", "utf8").toString(
  "base64",
);
const APNS_ID = "11111111-1111-4111-8111-111111111111";
const REQUEST_ID = "22222222-2222-4222-8222-222222222222";
const UNIQUE_ID = "development-unique-id";
const GENERATED_AT = 1_789_207_200;

function contentState(
  destination = "Stockholm Central",
): BlickLiveActivityLineContentStateV1 {
  return Object.freeze({
    schemaVersion: 1,
    commuteKind: "LINE_DIRECTION",
    freshness: "FRESH",
    sourceFetchedAt: GENERATED_AT - 10,
    departures: Object.freeze([
      Object.freeze({
        departureId: "departure-1",
        lineDesignation: "41",
        direction: "Northbound",
        destination,
        scheduledAt: GENERATED_AT + 300,
        expectedAt: GENERATED_AT + 330,
        effectiveAt: GENERATED_AT + 330,
        isCancelled: false,
        departureState: "EXPECTED",
        journeyState: "NORMAL",
        predictionState: "REALTIME",
      }),
    ]),
  });
}

function attributes() {
  return createBlickLiveActivityAttributes({
    bindingId: "68fdf0ad-e5b4-4cf1-a429-4b61a4144238",
    sessionRevision: 7,
    commuteKind: "LINE_DIRECTION",
  });
}

function startPayload() {
  return buildActivityKitStartPayload({
    generatedAt: GENERATED_AT,
    contentState: contentState(),
    attributes: attributes(),
    alert: { title: "Commute started", body: "Departure monitoring is active" },
    mode: { kind: "DIRECT_IOS_18" },
  });
}

function updatePayload() {
  return buildActivityKitUpdatePayload({
    generatedAt: GENERATED_AT,
    contentState: contentState(),
    attributes: attributes(),
    staleAt: GENERATED_AT + 120,
  });
}

function endPayload() {
  return buildActivityKitEndPayload({
    generatedAt: GENERATED_AT,
    contentState: contentState(),
    attributes: attributes(),
    dismissalAt: GENERATED_AT - 1,
  });
}

function directDescription(
  payload: BuiltActivityKitPayload = startPayload(),
): ApnsRequestDescription {
  return createDirectLiveActivityRequestDescription({
    environment: "SANDBOX",
    bundleId: BUNDLE_ID,
    providerToken: PROVIDER_TOKEN,
    deviceToken: DEVICE_TOKEN,
    payload,
    priority: 10,
    expiration: 0,
    apnsId: APNS_ID,
    collapseId: "commute-occurrence-1",
  });
}

function rawResponse(
  statusCode: number,
  headers: RawApnsTransportResponse["headers"] = {},
  body: RawApnsTransportResponse["body"] = null,
): RawApnsTransportResponse {
  return { statusCode, headers, body };
}

function expectNoSecrets(value: unknown): void {
  const rendered = typeof value === "string" ? value : JSON.stringify(value);
  expect(rendered).not.toContain(PROVIDER_TOKEN);
  expect(rendered).not.toContain(DEVICE_TOKEN_HEX);
  expect(rendered).not.toContain(CHANNEL_ID);
  expect(rendered).not.toContain(SECOND_CHANNEL_ID);
}

describe("APNs request descriptions", () => {
  it("selects isolated environment endpoints", () => {
    expect(apnsEnvironmentEndpoints("SANDBOX")).toEqual({
      directDevice: "https://api.sandbox.push.apple.com:443",
      broadcast: "https://api-broadcast.sandbox.push.apple.com:443",
      channelManagement:
        "https://api-manage-broadcast.sandbox.push.apple.com:2195",
    });
    expect(apnsEnvironmentEndpoints("PRODUCTION")).toEqual({
      directDevice: "https://api.push.apple.com:443",
      broadcast: "https://api-broadcast.push.apple.com:443",
      channelManagement: "https://api-manage-broadcast.push.apple.com:2196",
    });
    expect(Object.isFrozen(APNS_ENVIRONMENT_ENDPOINTS.SANDBOX)).toBe(true);
    expect(() => apnsEnvironmentEndpoints("STAGING" as never)).toThrow(
      "environment is invalid",
    );
  });

  it("describes a direct push without exposing its wire secrets by default", () => {
    const request = directDescription();
    const materialized = request.materializeForTransport();

    expect(materialized).toEqual({
      endpoint: "https://api.sandbox.push.apple.com:443",
      method: "POST",
      path: `/3/device/${DEVICE_TOKEN_HEX}`,
      headers: {
        authorization: `bearer ${PROVIDER_TOKEN}`,
        "content-type": "application/json",
        "apns-push-type": "liveactivity",
        "apns-topic": `${BUNDLE_ID}.push-type.liveactivity`,
        "apns-priority": "10",
        "apns-expiration": "0",
        "apns-id": APNS_ID,
        "apns-collapse-id": "commute-occurrence-1",
      },
      body: startPayload().serialized,
      bodyByteLength: startPayload().utf8ByteLength,
    });
    expect(request.toRedactedDiagnostic()).toEqual({
      kind: "DIRECT_LIVE_ACTIVITY",
      environment: "SANDBOX",
      endpoint: "https://api.sandbox.push.apple.com:443",
      method: "POST",
      pathTemplate: "/3/device/<redacted>",
      event: "start",
      priority: 10,
      expiration: 0,
      requestId: APNS_ID,
      bodyByteLength: startPayload().utf8ByteLength,
    });
    expect(Object.keys(request)).toEqual([]);
    expectNoSecrets(request);
    expectNoSecrets(String(request));
    expectNoSecrets(inspect(request));
  });

  it("accepts stale-date for update but rejects it for start and end", () => {
    expect(() => directDescription(updatePayload())).not.toThrow();

    for (const payload of [startPayload(), endPayload()]) {
      const value = {
        aps: {
          ...payload.value.aps,
          "stale-date": GENERATED_AT + 120,
        },
      };
      const serialized = JSON.stringify(value);
      const forged = {
        ...payload,
        value,
        serialized,
        utf8ByteLength: Buffer.byteLength(serialized, "utf8"),
      } as BuiltActivityKitPayload;
      expect(() => directDescription(forged)).toThrow(
        "stale-date is only valid for update events",
      );
    }
  });

  it("describes broadcast updates and ends with a bare bundle path", () => {
    for (const payload of [updatePayload(), endPayload()]) {
      const request = createBroadcastLiveActivityRequestDescription({
        environment: "PRODUCTION",
        bundleId: BUNDLE_ID,
        providerToken: PROVIDER_TOKEN,
        channelId: CHANNEL_ID,
        payload,
        priority: 1,
        expiration: GENERATED_AT + 180,
        requestId: REQUEST_ID,
      });
      const materialized = request.materializeForTransport();
      expect(materialized.endpoint).toBe(
        "https://api-broadcast.push.apple.com:443",
      );
      expect(materialized.path).toBe(`/4/broadcasts/apps/${BUNDLE_ID}`);
      expect(materialized.path).not.toContain("push-type.liveactivity");
      expect(materialized.headers).toMatchObject({
        authorization: `bearer ${PROVIDER_TOKEN}`,
        "apns-channel-id": CHANNEL_ID,
        "apns-expiration": String(GENERATED_AT + 180),
        "apns-priority": "1",
        "apns-push-type": "liveactivity",
        "apns-request-id": REQUEST_ID,
      });
      expect(request.toRedactedDiagnostic().event).toBe(payload.value.aps.event);
      expectNoSecrets(request);
      expectNoSecrets(inspect(request));
    }
  });

  it("rejects remote starts on the broadcast endpoint", () => {
    expect(() =>
      createBroadcastLiveActivityRequestDescription({
        environment: "SANDBOX",
        bundleId: BUNDLE_ID,
        providerToken: PROVIDER_TOKEN,
        channelId: CHANNEL_ID,
        payload: startPayload(),
        priority: 10,
        expiration: 0,
      }),
    ).toThrow("event is invalid for this APNs request");
  });

  it("reverifies the authoritative serialized payload at the request boundary", () => {
    const built = updatePayload();
    const forged = {
      ...built,
      serialized: `${built.serialized} `,
    } as BuiltActivityKitPayload;
    expect(() => directDescription(forged)).toThrow(
      "payload representation is inconsistent",
    );

    const invalidBytes = {
      ...built,
      utf8ByteLength: built.utf8ByteLength + 1,
    } as BuiltActivityKitPayload;
    expect(() => directDescription(invalidBytes)).toThrow(
      "payload byte length is invalid",
    );
  });

  it("describes create, read, delete, and list channel operations exactly", () => {
    const shared = {
      environment: "SANDBOX" as const,
      bundleId: BUNDLE_ID,
      providerToken: PROVIDER_TOKEN,
      requestId: REQUEST_ID,
    };
    const create = createApnsChannelCreateRequestDescription({
      ...shared,
      messageStoragePolicy: 1,
    });
    expect(create.materializeForTransport()).toMatchObject({
      endpoint: "https://api-manage-broadcast.sandbox.push.apple.com:2195",
      method: "POST",
      path: `/1/apps/${BUNDLE_ID}/channels`,
      body: JSON.stringify({
        "message-storage-policy": 1,
        "push-type": "LiveActivity",
      }),
    });
    expect(create.materializeForTransport().headers).toEqual({
      authorization: `bearer ${PROVIDER_TOKEN}`,
      "apns-request-id": REQUEST_ID,
      "content-type": "application/json",
    });

    const read = createApnsChannelReadRequestDescription({
      ...shared,
      channelId: CHANNEL_ID,
    });
    const remove = createApnsChannelDeleteRequestDescription({
      ...shared,
      channelId: CHANNEL_ID,
    });
    expect(read.materializeForTransport()).toMatchObject({
      method: "GET",
      path: `/1/apps/${BUNDLE_ID}/channels`,
      body: null,
    });
    expect(remove.materializeForTransport()).toMatchObject({
      method: "DELETE",
      path: `/1/apps/${BUNDLE_ID}/channels`,
      body: null,
    });
    expect(read.materializeForTransport().headers["apns-channel-id"]).toBe(
      CHANNEL_ID,
    );

    const list = createApnsChannelListRequestDescription(shared);
    expect(list.materializeForTransport()).toMatchObject({
      method: "GET",
      path: `/1/apps/${BUNDLE_ID}/all-channels`,
      body: null,
    });
    for (const request of [create, read, remove, list]) {
      expectNoSecrets(request);
      expectNoSecrets(inspect(request));
    }
  });

  it("rejects unsafe request values without reflecting credentials in errors", () => {
    const invalidJwt = `${PROVIDER_TOKEN}\r\nleak`;
    const invalidChannel = `${CHANNEL_ID}\r\nleak`;
    const errors: unknown[] = [];
    try {
      createDirectLiveActivityRequestDescription({
        environment: "SANDBOX",
        bundleId: BUNDLE_ID,
        providerToken: invalidJwt,
        deviceToken: DEVICE_TOKEN,
        payload: updatePayload(),
        priority: 10,
      });
    } catch (error) {
      errors.push(error);
    }
    try {
      createApnsChannelReadRequestDescription({
        environment: "SANDBOX",
        bundleId: BUNDLE_ID,
        providerToken: PROVIDER_TOKEN,
        channelId: invalidChannel,
      });
    } catch (error) {
      errors.push(error);
    }
    expect(errors).toHaveLength(2);
    for (const error of errors) {
      const message = String(error);
      expect(message).not.toContain(invalidJwt);
      expect(message).not.toContain(invalidChannel);
      expect(message).not.toContain(PROVIDER_TOKEN);
      expect(message).not.toContain(CHANNEL_ID);
    }
    expect(() =>
      createDirectLiveActivityRequestDescription({
        environment: "SANDBOX",
        bundleId: "se/blick/app",
        providerToken: PROVIDER_TOKEN,
        deviceToken: DEVICE_TOKEN,
        payload: updatePayload(),
        priority: 10,
      }),
    ).toThrow("bundle ID is invalid");
    expect(() =>
      createDirectLiveActivityRequestDescription({
        environment: "SANDBOX",
        bundleId: BUNDLE_ID,
        providerToken: PROVIDER_TOKEN,
        deviceToken: DEVICE_TOKEN,
        payload: updatePayload(),
        priority: 1 as never,
      }),
    ).toThrow("priority must be 5 or 10");
    expect(() =>
      createApnsChannelReadRequestDescription({
        environment: "SANDBOX",
        bundleId: BUNDLE_ID,
        providerToken: PROVIDER_TOKEN,
        channelId: "A=",
      }),
    ).toThrow("channel ID is invalid");
  });
});

describe("APNs response normalization and classification", () => {
  it("normalizes header casing and preserves an exact 410 millisecond timestamp", () => {
    const response = normalizeApnsTransportResponse(
      rawResponse(
        410,
        { "APNS-ID": APNS_ID, "Apns-Unique-Id": UNIQUE_ID },
        Buffer.from(
          JSON.stringify({
            reason: "Unregistered",
            timestamp: 1_789_207_200_123,
          }),
          "utf8",
        ),
      ),
    );

    expect(response.toRedactedDiagnostic()).toEqual({
      statusCode: 410,
      apnsId: APNS_ID,
      apnsRequestId: null,
      apnsUniqueId: UNIQUE_ID,
      reason: "Unregistered",
      tokenInvalidationTimestampMilliseconds: 1_789_207_200_123,
      bodyState: "JSON",
      validationIssues: [],
    });
    expect(classifyApnsDeviceResponse(response)).toEqual({
      target: "DEVICE",
      disposition: "DEVICE_TOKEN_INVALID",
      statusCode: 410,
      requestId: APNS_ID,
      uniqueId: UNIQUE_ID,
      reason: "Unregistered",
      retry: "NO_RETRY",
      tokenInvalidationTimestampMilliseconds: 1_789_207_200_123,
    });
  });

  it("keeps invalid response content out of diagnostics and fails malformed success", () => {
    const secretBody = `not-json-${CHANNEL_ID}`;
    const response = normalizeApnsTransportResponse(
      rawResponse(200, { "apns-id": APNS_ID }, secretBody),
    );
    expect(response.bodyState).toBe("INVALID_JSON");
    expect(response.validationIssues).toContain("INVALID_RESPONSE_BODY");
    expect(classifyApnsDeviceResponse(response).disposition).toBe(
      "PROTOCOL_ERROR",
    );
    expectNoSecrets(response);
    expectNoSecrets(inspect(response));
  });

  it("distinguishes device authentication, throttling, and server failures", () => {
    const cases = [
      [403, "ExpiredProviderToken", "AUTHENTICATION_ERROR", "AFTER_CORRECTION"],
      [429, "TooManyRequests", "THROTTLED", "WITH_DELAY"],
      [503, "Shutdown", "TRANSIENT_SERVER_ERROR", "AFTER_15_MINUTES"],
      [599, "SyntheticServerFailure", "TRANSIENT_SERVER_ERROR", "AFTER_15_MINUTES"],
    ] as const;
    for (const [status, reason, disposition, retry] of cases) {
      const classified = classifyApnsDeviceResponse(
        normalizeApnsTransportResponse(
          rawResponse(status, { "apns-id": APNS_ID }, JSON.stringify({ reason })),
        ),
      );
      expect(classified).toMatchObject({ disposition, retry, reason });
    }
  });

  it("classifies direct acceptance, BadDeviceToken, oversized payload, and unknown status", () => {
    const cases = [
      [
        200,
        null,
        "ACCEPTED",
        "NO_RETRY",
      ],
      [
        400,
        "BadDeviceToken",
        "DEVICE_TOKEN_INVALID",
        "NO_RETRY",
      ],
      [
        413,
        "PayloadTooLarge",
        "REQUEST_REJECTED",
        "NO_RETRY",
      ],
      [
        399,
        "SyntheticFutureReason",
        "PROTOCOL_ERROR",
        "NO_RETRY",
      ],
    ] as const;
    for (const [statusCode, reason, disposition, retry] of cases) {
      const response = normalizeApnsTransportResponse(
        rawResponse(
          statusCode,
          { "apns-id": APNS_ID },
          reason == null ? undefined : JSON.stringify({ reason }),
        ),
      );
      expect(classifyApnsDeviceResponse(response)).toMatchObject({
        disposition,
        retry,
        reason,
      });
    }
  });

  it("does not trust terminal reason strings paired with the wrong HTTP status", () => {
    const device = classifyApnsDeviceResponse(
      normalizeApnsTransportResponse(
        rawResponse(
          520,
          { "apns-id": APNS_ID },
          JSON.stringify({ reason: "BadDeviceToken" }),
        ),
      ),
    );
    expect(device).toMatchObject({
      disposition: "TRANSIENT_SERVER_ERROR",
      retry: "AFTER_15_MINUTES",
    });

    const broadcast = classifyApnsBroadcastResponse(
      normalizeApnsTransportResponse(
        rawResponse(
          598,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({ reason: "ChannelNotRegistered" }),
        ),
      ),
    );
    expect(broadcast).toMatchObject({
      disposition: "TRANSIENT_SERVER_ERROR",
      retry: "AFTER_15_MINUTES",
    });

    const channel = classifyApnsChannelResponse(
      "READ",
      normalizeApnsTransportResponse(
        rawResponse(
          599,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({ reason: "FeatureNotEnabled" }),
        ),
      ),
    );
    expect(channel).toMatchObject({
      disposition: "TRANSIENT_SERVER_ERROR",
      retry: "AFTER_15_MINUTES",
    });
  });

  it("distinguishes broadcast acceptance, invalid channels, and disabled capability", () => {
    const accepted = classifyApnsBroadcastResponse(
      normalizeApnsTransportResponse(
        rawResponse(200, {
          "apns-request-id": REQUEST_ID,
          "apns-unique-id": UNIQUE_ID,
        }),
      ),
    );
    expect(accepted).toEqual({
      target: "BROADCAST",
      disposition: "ACCEPTED",
      statusCode: 200,
      requestId: REQUEST_ID,
      uniqueId: UNIQUE_ID,
      reason: null,
      retry: "NO_RETRY",
    });

    const missingUniqueId = classifyApnsBroadcastResponse(
      normalizeApnsTransportResponse(
        rawResponse(200, { "apns-request-id": REQUEST_ID }),
      ),
    );
    expect(missingUniqueId.disposition).toBe("PROTOCOL_ERROR");

    const invalid = classifyApnsBroadcastResponse(
      normalizeApnsTransportResponse(
        rawResponse(
          400,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({ reason: "ChannelNotRegistered" }),
        ),
      ),
    );
    expect(invalid.disposition).toBe("CHANNEL_INVALID");

    const disabled = classifyApnsBroadcastResponse(
      normalizeApnsTransportResponse(
        rawResponse(
          400,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({ reason: "FeatureNotEnabled" }),
        ),
      ),
    );
    expect(disabled.disposition).toBe("FEATURE_DISABLED");
  });

  it("normalizes every successful channel-operation response", () => {
    const create = classifyApnsChannelResponse(
      "CREATE",
      normalizeApnsTransportResponse(
        rawResponse(201, {
          "apns-request-id": REQUEST_ID,
          "apns-channel-id": CHANNEL_ID,
        }),
      ),
    );
    expect(create.disposition).toBe("SUCCEEDED");
    expect(create.materializeResult()).toEqual({
      operation: "CREATE",
      channelId: CHANNEL_ID,
    });
    expectNoSecrets(create);
    expectNoSecrets(inspect(create));

    const read = classifyApnsChannelResponse(
      "READ",
      normalizeApnsTransportResponse(
        rawResponse(
          200,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({
            "message-storage-policy": 1,
            "push-type": "LiveActivity",
          }),
        ),
      ),
    );
    expect(read.materializeResult()).toEqual({
      operation: "READ",
      configuration: { messageStoragePolicy: 1, pushType: "LiveActivity" },
    });

    const remove = classifyApnsChannelResponse(
      "DELETE",
      normalizeApnsTransportResponse(
        rawResponse(204, { "apns-request-id": REQUEST_ID }),
      ),
    );
    expect(remove.materializeResult()).toEqual({ operation: "DELETE" });

    const list = classifyApnsChannelResponse(
      "LIST",
      normalizeApnsTransportResponse(
        rawResponse(
          200,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({ channels: [CHANNEL_ID, SECOND_CHANNEL_ID] }),
        ),
      ),
    );
    expect(list.materializeResult()).toEqual({
      operation: "LIST",
      channelIds: [CHANNEL_ID, SECOND_CHANNEL_ID],
    });
    expectNoSecrets(list);
    expectNoSecrets(inspect(list));
  });

  it("distinguishes channel exhaustion and malformed success", () => {
    const exhausted = classifyApnsChannelResponse(
      "CREATE",
      normalizeApnsTransportResponse(
        rawResponse(
          400,
          { "apns-request-id": REQUEST_ID },
          JSON.stringify({ reason: "CannotCreateChannelConfig" }),
        ),
      ),
    );
    expect(exhausted).toMatchObject({
      disposition: "CHANNEL_LIMIT_REACHED",
      retry: "AFTER_CORRECTION",
    });

    const malformed = classifyApnsChannelResponse(
      "CREATE",
      normalizeApnsTransportResponse(
        rawResponse(201, { "apns-request-id": REQUEST_ID }),
      ),
    );
    expect(malformed.disposition).toBe("PROTOCOL_ERROR");
    expect(malformed.materializeResult()).toBeNull();
  });

  it("keeps channel authentication, throttling, server, and invalid-ID outcomes distinct", () => {
    const cases = [
      [403, "ExpiredProviderToken", "AUTHENTICATION_ERROR"],
      [429, "TooManyRequests", "THROTTLED"],
      [500, "InternalServerError", "TRANSIENT_SERVER_ERROR"],
      [400, "BadChannelId", "CHANNEL_INVALID"],
    ] as const;
    for (const [statusCode, reason, disposition] of cases) {
      const classified = classifyApnsChannelResponse(
        "READ",
        normalizeApnsTransportResponse(
          rawResponse(
            statusCode,
            { "apns-request-id": REQUEST_ID },
            JSON.stringify({ reason }),
          ),
        ),
      );
      expect(classified).toMatchObject({ disposition, reason });
    }
  });
});
