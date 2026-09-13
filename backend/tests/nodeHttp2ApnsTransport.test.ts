import {
  connect as connectHttp2,
  constants,
  createServer,
  sensitiveHeaders,
  type ClientHttp2Session,
  type Http2Server,
  type IncomingHttpHeaders,
  type SecureClientSessionOptions,
  type ServerHttp2Session,
  type ServerHttp2Stream,
} from "node:http2";
import { inspect } from "node:util";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ApnsMaterializedRequest,
  ApnsRequestDescription,
  RedactedApnsRequestDiagnostic,
} from "../src/liveCommute/apple/apnsProtocol.js";
import {
  BLICK_APNS_REQUEST_TIMEOUT_MILLISECONDS,
  BLICK_APNS_RESPONSE_BODY_MAX_BYTES,
  NodeHttp2ApnsTransport,
  type ApnsHttp2ConnectionFactory,
} from "../src/liveCommute/apple/nodeHttp2ApnsTransport.js";

const APNS_ID = "11111111-1111-4111-8111-111111111111";
const PROVIDER_SECRET =
  "c2Vuc2l0aXZlLWp3dC1oZWFkZXI.c2Vuc2l0aXZlLWp3dC1ib2R5.c2Vuc2l0aXZlLWp3dC1zaWduYXR1cmU";
const DEVICE_SECRET = Buffer.from("synthetic-sensitive-device-token", "utf8").toString(
  "hex",
);
const BODY_SECRET = JSON.stringify({ aps: { synthetic: "sensitive-route-marker" } });

const transports: NodeHttp2ApnsTransport[] = [];
const servers: Http2Server[] = [];
const serverSessions = new Set<ServerHttp2Session>();

afterEach(async () => {
  await Promise.all(transports.splice(0).map(async (transport) => transport.close()));
  for (const session of serverSessions) session.destroy();
  serverSessions.clear();
  await Promise.all(
    servers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          server.close(() => resolve());
        }),
    ),
  );
  vi.restoreAllMocks();
});

function syntheticRequest(
  endpoint = "https://api.sandbox.push.apple.com:443",
): ApnsRequestDescription {
  const materialized: ApnsMaterializedRequest = Object.freeze({
    endpoint,
    method: "POST",
    path: `/3/device/${DEVICE_SECRET}`,
    headers: Object.freeze({
      authorization: `bearer ${PROVIDER_SECRET}`,
      "content-type": "application/json",
      "apns-push-type": "liveactivity",
      "apns-topic": "se.blick.commute.push-type.liveactivity",
      "apns-priority": "5",
      "apns-id": APNS_ID,
    }),
    body: BODY_SECRET,
    bodyByteLength: Buffer.byteLength(BODY_SECRET, "utf8"),
  });
  const diagnostic: RedactedApnsRequestDiagnostic = Object.freeze({
    kind: "DIRECT_LIVE_ACTIVITY",
    environment: "SANDBOX",
    endpoint,
    method: "POST",
    pathTemplate: "/3/device/<redacted>",
    event: "update",
    priority: 5,
    expiration: null,
    requestId: APNS_ID,
    bodyByteLength: materialized.bodyByteLength,
  });
  return {
    kind: diagnostic.kind,
    environment: diagnostic.environment,
    endpoint,
    method: "POST",
    bodyByteLength: materialized.bodyByteLength,
    materializeForTransport: () => materialized,
    toRedactedDiagnostic: () => diagnostic,
    toJSON: () => diagnostic,
    toString: () => JSON.stringify(diagnostic),
  };
}

async function localServer(
  handler: (stream: ServerHttp2Stream, headers: IncomingHttpHeaders) => void,
): Promise<string> {
  const server = createServer();
  servers.push(server);
  server.on("session", (session) => {
    serverSessions.add(session);
    session.once("close", () => serverSessions.delete(session));
  });
  server.on("stream", handler);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  if (address == null || typeof address === "string") throw new Error("missing address");
  return `http://127.0.0.1:${address.port}`;
}

function localConnector(
  localAuthority: string,
  onConnect?: (authority: URL, options: SecureClientSessionOptions) => void,
): { readonly factory: ApnsHttp2ConnectionFactory; readonly calls: unknown[][] } {
  const calls: unknown[][] = [];
  const factory: ApnsHttp2ConnectionFactory = (authority, options) => {
    calls.push([authority, options]);
    if (typeof onConnect === "function") onConnect(authority, options);
    return connectHttp2(localAuthority) as ClientHttp2Session;
  };
  return { factory, calls };
}

describe("real Node HTTP/2 APNs transport", () => {
  it("is lazy, sends exact HTTP/2 headers/body, bounds TLS, and reuses one session", async () => {
    const received: Array<{ headers: IncomingHttpHeaders; body: string }> = [];
    const authority = await localServer((stream, headers) => {
      const chunks: Buffer[] = [];
      stream.on("data", (chunk: Buffer) => chunks.push(Buffer.from(chunk)));
      stream.on("end", () => {
        received.push({ headers, body: Buffer.concat(chunks).toString("utf8") });
        stream.respond({ ":status": 200, "apns-id": APNS_ID });
        stream.end();
      });
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    expect(BLICK_APNS_REQUEST_TIMEOUT_MILLISECONDS).toBe(15_000);
    expect(BLICK_APNS_RESPONSE_BODY_MAX_BYTES).toBe(64 * 1_024);
    expect(connector.calls).toHaveLength(0);

    const first = await transport.send(syntheticRequest());
    const second = await transport.send(syntheticRequest());

    expect(first).toMatchObject({
      outcome: "APNS_RESPONSE",
      response: { statusCode: 200, apnsId: APNS_ID },
    });
    expect(second.outcome).toBe("APNS_RESPONSE");
    expect(connector.calls).toHaveLength(1);
    const [requestedAuthority, options] = connector.calls[0]!;
    expect(String(requestedAuthority)).toBe("https://api.sandbox.push.apple.com/");
    expect(options).toMatchObject({ minVersion: "TLSv1.2" });
    expect(options).not.toMatchObject({ rejectUnauthorized: false });
    expect(received).toHaveLength(2);
    expect(received[0]!.headers).toMatchObject({
      ":method": "POST",
      ":scheme": "https",
      ":authority": "api.sandbox.push.apple.com",
      ":path": `/3/device/${DEVICE_SECRET}`,
      authorization: `bearer ${PROVIDER_SECRET}`,
      "apns-id": APNS_ID,
    });
    expect(
      (received[0]!.headers as Record<PropertyKey, unknown>)[sensitiveHeaders],
    ).toEqual(
      expect.arrayContaining([":path", "authorization"]),
    );
    expect(received.map((item) => item.body)).toEqual([BODY_SECRET, BODY_SECRET]);
  });

  it("keeps one reusable session per APNs authority", async () => {
    const authority = await localServer((stream) => {
      stream.respond({ ":status": 200, "apns-id": APNS_ID });
      stream.end();
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    expect((await transport.send(syntheticRequest())).outcome).toBe("APNS_RESPONSE");
    expect(
      (
        await transport.send(
          syntheticRequest("https://api.push.apple.com:443"),
        )
      ).outcome,
    ).toBe("APNS_RESPONSE");
    expect((await transport.send(syntheticRequest())).outcome).toBe("APNS_RESPONSE");

    expect(connector.calls).toHaveLength(2);
    expect(connector.calls.map(([requested]) => String(requested))).toEqual([
      "https://api.sandbox.push.apple.com/",
      "https://api.push.apple.com/",
    ]);
  });

  it("multiplexes concurrent requests on the reusable session without a fixed stream cap", async () => {
    const pending: ServerHttp2Stream[] = [];
    const authority = await localServer((stream) => {
      stream.on("data", () => undefined);
      pending.push(stream);
      if (pending.length !== 2) return;
      for (const current of pending) {
        current.respond({ ":status": 200, "apns-id": APNS_ID });
        current.end();
      }
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    const results = await Promise.all([
      transport.send(syntheticRequest()),
      transport.send(syntheticRequest()),
    ]);

    expect(results.map((result) => result.outcome)).toEqual([
      "APNS_RESPONSE",
      "APNS_RESPONSE",
    ]);
    expect(pending).toHaveLength(2);
    expect(connector.calls).toHaveLength(1);
  });

  it("classifies synchronous connection failure without retrying", async () => {
    const connector = vi.fn(() => {
      throw new Error("synthetic connection failure");
    });
    const transport = new NodeHttp2ApnsTransport({
      connect: connector as ApnsHttp2ConnectionFactory,
    });
    transports.push(transport);

    expect(await transport.send(syntheticRequest())).toEqual({
      outcome: "OUTCOME_UNKNOWN",
      reason: "CONNECTION_ERROR",
    });
    expect(connector).toHaveBeenCalledOnce();
  });

  it("proves invalid request materialization was not attempted", async () => {
    const connector = vi.fn(() => {
      throw new Error("connection factory must remain untouched");
    });
    const transport = new NodeHttp2ApnsTransport({
      connect: connector as ApnsHttp2ConnectionFactory,
    });
    transports.push(transport);
    const invalid = {
      ...syntheticRequest(),
      materializeForTransport: () => {
        throw new Error("synthetic materialization failure");
      },
    };

    expect(await transport.send(invalid)).toEqual({
      outcome: "NOT_ATTEMPTED",
      reason: "REQUEST_MATERIALIZATION_FAILED",
    });
    expect(connector).not.toHaveBeenCalled();
  });

  it("collects a complete bounded response for Phase 4A normalization", async () => {
    const authority = await localServer((stream) => {
      stream.respond({ ":status": 410, "apns-id": APNS_ID });
      stream.end('{"reason":"Unregistered","timestamp":1789207200123}');
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    const result = await transport.send(syntheticRequest());

    expect(result).toMatchObject({
      outcome: "APNS_RESPONSE",
      response: {
        statusCode: 410,
        reason: "Unregistered",
        tokenInvalidationTimestampMilliseconds: 1_789_207_200_123,
      },
    });
  });

  it("returns unknown on a bounded timeout and performs no automatic retry", async () => {
    let streams = 0;
    const authority = await localServer((stream) => {
      streams += 1;
      stream.on("error", () => undefined);
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({
      connect: connector.factory,
      requestTimeoutMilliseconds: 250,
    });
    transports.push(transport);

    const result = await transport.send(syntheticRequest());

    expect(result).toEqual({
      outcome: "OUTCOME_UNKNOWN",
      reason: "REQUEST_TIMEOUT",
    });
    expect(connector.calls).toHaveLength(1);
    expect(streams).toBe(1);
  });

  it("bounds response collection and resets only the oversized stream", async () => {
    const authority = await localServer((stream) => {
      stream.on("error", () => undefined);
      stream.respond({ ":status": 503, "apns-id": APNS_ID });
      stream.end("x".repeat(17));
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({
      connect: connector.factory,
      responseBodyMaxBytes: 16,
    });
    transports.push(transport);

    expect(await transport.send(syntheticRequest())).toEqual({
      outcome: "OUTCOME_UNKNOWN",
      reason: "RESPONSE_TOO_LARGE",
    });
    expect(connector.calls).toHaveLength(1);
  });

  it("reports a reset stream as unknown without reconnecting and resending", async () => {
    let streams = 0;
    const authority = await localServer((stream) => {
      streams += 1;
      stream.on("error", () => undefined);
      stream.close(constants.NGHTTP2_INTERNAL_ERROR);
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    const result = await transport.send(syntheticRequest());

    expect(result).toMatchObject({ outcome: "OUTCOME_UNKNOWN" });
    expect(["STREAM_ERROR", "UNEXPECTED_CLOSE"]).toContain(
      result.outcome === "OUTCOME_UNKNOWN" ? result.reason : "",
    );
    expect(streams).toBe(1);
    expect(connector.calls).toHaveLength(1);
  });

  it("classifies a session error, retires that session, and reconnects only on the next call", async () => {
    const clientSessions: ClientHttp2Session[] = [];
    let streams = 0;
    const authority = await localServer((stream) => {
      streams += 1;
      if (streams === 1) {
        setImmediate(() => {
          clientSessions[0]!.emit("error", new Error("synthetic session failure"));
          clientSessions[0]!.destroy();
        });
        return;
      }
      stream.respond({ ":status": 200, "apns-id": APNS_ID });
      stream.end();
    });
    const factory: ApnsHttp2ConnectionFactory = () => {
      const session = connectHttp2(authority);
      clientSessions.push(session);
      return session;
    };
    const transport = new NodeHttp2ApnsTransport({ connect: factory });
    transports.push(transport);

    expect(await transport.send(syntheticRequest())).toEqual({
      outcome: "OUTCOME_UNKNOWN",
      reason: "SESSION_ERROR",
    });
    expect(streams).toBe(1);
    expect(clientSessions).toHaveLength(1);

    expect((await transport.send(syntheticRequest())).outcome).toBe("APNS_RESPONSE");
    expect(streams).toBe(2);
    expect(clientSessions).toHaveLength(2);
  });

  it("classifies an unexpected connection close and replaces it only on a later call", async () => {
    const clientSessions: ClientHttp2Session[] = [];
    let streams = 0;
    const authority = await localServer((stream) => {
      streams += 1;
      if (streams === 1) {
        setImmediate(() => clientSessions[0]!.destroy());
        return;
      }
      stream.respond({ ":status": 200, "apns-id": APNS_ID });
      stream.end();
    });
    const factory: ApnsHttp2ConnectionFactory = () => {
      const session = connectHttp2(authority);
      clientSessions.push(session);
      return session;
    };
    const transport = new NodeHttp2ApnsTransport({ connect: factory });
    transports.push(transport);

    expect(await transport.send(syntheticRequest())).toEqual({
      outcome: "OUTCOME_UNKNOWN",
      reason: "UNEXPECTED_CLOSE",
    });
    expect(streams).toBe(1);
    expect(clientSessions).toHaveLength(1);

    expect((await transport.send(syntheticRequest())).outcome).toBe("APNS_RESPONSE");
    expect(streams).toBe(2);
    expect(clientSessions).toHaveLength(2);
  });

  it("retires a GOAWAY session while allowing a completed response and replaces it next call", async () => {
    let streams = 0;
    const authority = await localServer((stream) => {
      streams += 1;
      const session = stream.session;
      if (session == null) throw new Error("missing HTTP/2 session");
      stream.respond({ ":status": 200, "apns-id": APNS_ID });
      stream.end();
      session.goaway(constants.NGHTTP2_NO_ERROR, stream.id);
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    expect((await transport.send(syntheticRequest())).outcome).toBe("APNS_RESPONSE");
    await new Promise<void>((resolve) => setImmediate(resolve));
    expect((await transport.send(syntheticRequest())).outcome).toBe("APNS_RESPONSE");

    expect(streams).toBe(2);
    expect(connector.calls).toHaveLength(2);
  });

  it("returns an explicit unknown outcome when GOAWAY precedes any response", async () => {
    let streams = 0;
    const authority = await localServer((stream) => {
      streams += 1;
      stream.on("error", () => undefined);
      const session = stream.session;
      if (session == null) throw new Error("missing HTTP/2 session");
      session.goaway(constants.NGHTTP2_NO_ERROR, 0);
      stream.close(constants.NGHTTP2_CANCEL);
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);

    expect(await transport.send(syntheticRequest())).toEqual({
      outcome: "OUTCOME_UNKNOWN",
      reason: "GOAWAY",
    });
    expect(streams).toBe(1);
    expect(connector.calls).toHaveLength(1);
  });

  it("keeps every unknown-outcome diagnostic free of request secrets", async () => {
    const authority = await localServer((stream) => {
      stream.on("error", () => undefined);
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({
      connect: connector.factory,
      requestTimeoutMilliseconds: 250,
    });
    transports.push(transport);

    const result = await transport.send(syntheticRequest());
    for (const rendered of [JSON.stringify(result), inspect(result), String(result)]) {
      expect(rendered).not.toContain(PROVIDER_SECRET);
      expect(rendered).not.toContain(DEVICE_SECRET);
      expect(rendered).not.toContain(BODY_SECRET);
      expect(rendered).not.toContain("sensitive-route-marker");
    }
  });

  it("closes gracefully around an existing stream and accepts no later send", async () => {
    let releaseResponse: (() => void) | undefined;
    let receivedResolve: (() => void) | undefined;
    const received = new Promise<void>((resolve) => {
      receivedResolve = resolve;
    });
    const authority = await localServer((stream) => {
      stream.on("data", () => undefined);
      stream.on("end", () => {
        receivedResolve?.();
        releaseResponse = () => {
          stream.respond({ ":status": 200, "apns-id": APNS_ID });
          stream.end();
        };
      });
    });
    const connector = localConnector(authority);
    const transport = new NodeHttp2ApnsTransport({ connect: connector.factory });
    transports.push(transport);
    const pending = transport.send(syntheticRequest());
    await received;

    const closing = transport.close();
    releaseResponse?.();

    expect((await pending).outcome).toBe("APNS_RESPONSE");
    await closing;
    expect(await transport.send(syntheticRequest())).toEqual({
      outcome: "NOT_ATTEMPTED",
      reason: "TRANSPORT_CLOSED",
    });
    expect(connector.calls).toHaveLength(1);
  });
});
