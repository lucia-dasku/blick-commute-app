import { inspect } from "node:util";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ApnsRequestDescription,
  RawApnsTransportResponse,
  RedactedApnsRequestDiagnostic,
} from "../src/liveCommute/apple/apnsProtocol.js";
import {
  DeterministicFakeApnsTransport,
  FakeApnsTransportScriptExhaustedError,
} from "../src/liveCommute/apple/apnsTransport.js";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function directDiagnostic(): RedactedApnsRequestDiagnostic {
  return {
    kind: "DIRECT_LIVE_ACTIVITY",
    environment: "SANDBOX",
    endpoint: "https://api.sandbox.push.apple.com:443",
    method: "POST",
    pathTemplate: "/3/device/<redacted>",
    event: "update",
    priority: 5,
    expiration: null,
    requestId: null,
    bodyByteLength: 128,
  };
}

function syntheticRequest(
  diagnostic: RedactedApnsRequestDiagnostic = directDiagnostic(),
): {
  readonly request: ApnsRequestDescription;
  readonly materializeForTransport: ReturnType<typeof vi.fn>;
} {
  const materializeForTransport = vi.fn(() => {
    throw new Error("sensitive wire request must not be materialized by the fake");
  });
  return {
    request: {
      materializeForTransport,
      toRedactedDiagnostic: () => diagnostic,
    } as unknown as ApnsRequestDescription,
    materializeForTransport,
  };
}

describe("deterministic fake APNs transport", () => {
  it("returns scripted normalized responses in FIFO order", async () => {
    const fake = new DeterministicFakeApnsTransport([
      {
        statusCode: 200,
        headers: { "apns-id": "123e4567-e89b-12d3-a456-426614174000" },
      },
      {
        statusCode: 503,
        body: '{"reason":"ServiceUnavailable"}',
      },
    ]);
    const { request } = syntheticRequest();

    const first = await fake.send(request);
    const second = await fake.send(request);

    expect(first).toMatchObject({
      outcome: "APNS_RESPONSE",
      response: { statusCode: 200 },
    });
    expect(second).toMatchObject({
      outcome: "APNS_RESPONSE",
      response: {
        statusCode: 503,
        reason: "ServiceUnavailable",
      },
    });
    expect(fake.sendCount).toBe(2);
  });

  it("never materializes wire secrets or invokes fetch while recording only redacted diagnostics", async () => {
    const secret = "synthetic-provider-token-and-device-token-marker";
    const fetchSpy = vi.fn(async () => {
      throw new Error("unexpected network access");
    });
    vi.stubGlobal("fetch", fetchSpy);
    const safeDiagnostic = directDiagnostic();
    const { request, materializeForTransport } = syntheticRequest(safeDiagnostic);
    const sensitiveRequest = request as unknown as {
      materializeForTransport(): unknown;
      sensitiveMarker?: string;
    };
    sensitiveRequest.sensitiveMarker = secret;
    const fake = new DeterministicFakeApnsTransport([{ statusCode: 200 }]);

    await fake.send(request);
    const recorded = fake.recordedDiagnostics();

    expect(materializeForTransport).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(recorded).toEqual([safeDiagnostic]);
    expect(JSON.stringify(recorded)).not.toContain(secret);
  });

  it("defensively copies scripted headers and bodies before later normalization", async () => {
    const headers: Record<string, string> = { "apns-id": "original-request-id" };
    const body = Buffer.from(
      '{"reason":"Unregistered","timestamp":1789123456000}',
      "utf8",
    );
    const raw: RawApnsTransportResponse = {
      statusCode: 410,
      headers,
      body,
    };
    const fake = new DeterministicFakeApnsTransport([raw]);
    headers["apns-id"] = "mutated-request-id";
    body.fill(0);

    const result = await fake.send(syntheticRequest().request);

    expect(result).toMatchObject({
      outcome: "APNS_RESPONSE",
      response: {
        statusCode: 410,
        reason: "Unregistered",
        tokenInvalidationTimestampMilliseconds: 1_789_123_456_000,
      },
    });
  });

  it("returns defensive diagnostic snapshots rather than its internal recording", async () => {
    const mutableDiagnostic = {
      ...directDiagnostic(),
    } as { -readonly [Key in keyof RedactedApnsRequestDiagnostic]: RedactedApnsRequestDiagnostic[Key] };
    const fake = new DeterministicFakeApnsTransport([{ statusCode: 200 }]);
    await fake.send(syntheticRequest(mutableDiagnostic).request);
    mutableDiagnostic.endpoint = "mutated-after-send";

    const firstRead = fake.recordedDiagnostics();
    const secondRead = fake.recordedDiagnostics();
    expect(firstRead).toEqual([directDiagnostic()]);
    expect(Object.isFrozen(firstRead)).toBe(true);
    expect(Object.isFrozen(firstRead[0])).toBe(true);
    expect(secondRead).toEqual([directDiagnostic()]);
    expect(secondRead).not.toBe(firstRead);
    expect(secondRead[0]).not.toBe(firstRead[0]);
  });

  it("fails deterministically and without request material when its script is exhausted", async () => {
    const secret = "synthetic-exhausted-request-secret";
    const fake = new DeterministicFakeApnsTransport([]);
    const { request, materializeForTransport } = syntheticRequest();
    (request as unknown as { secret?: string }).secret = secret;

    const failure = await fake.send(request).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(FakeApnsTransportScriptExhaustedError);
    expect(failure).toMatchObject({
      code: "FAKE_APNS_TRANSPORT_SCRIPT_EXHAUSTED",
      message: "Fake APNs transport response script is exhausted",
    });
    expect(inspect(failure)).not.toContain(secret);
    expect(materializeForTransport).not.toHaveBeenCalled();
    expect(fake.sendCount).toBe(1);
  });
});
