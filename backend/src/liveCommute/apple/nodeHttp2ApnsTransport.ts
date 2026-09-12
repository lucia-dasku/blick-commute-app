import {
  connect as connectHttp2,
  constants,
  sensitiveHeaders,
  type ClientHttp2Session,
  type ClientHttp2Stream,
  type IncomingHttpHeaders,
  type OutgoingHttpHeaders,
  type SecureClientSessionOptions,
} from "node:http2";
import type {
  ApnsMaterializedRequest,
  ApnsRequestDescription,
  RawApnsTransportHeaderValue,
  RawApnsTransportResponse,
} from "./apnsProtocol.js";
import { normalizeApnsTransportResponse } from "./apnsProtocol.js";
import {
  apnsTransportNotAttempted,
  apnsTransportResponse,
  unknownApnsTransportOutcome,
  type ApnsTransport,
  type ApnsTransportResult,
  type ApnsTransportUnknownReason,
} from "./apnsTransport.js";

export const BLICK_APNS_REQUEST_TIMEOUT_MILLISECONDS = 15_000;
export const BLICK_APNS_RESPONSE_BODY_MAX_BYTES = 64 * 1_024;

const MAX_CONFIGURED_REQUEST_TIMEOUT_MILLISECONDS = 5 * 60 * 1_000;
const MAX_CONFIGURED_RESPONSE_BODY_BYTES = 8 * 1_024 * 1_024;

export type ApnsHttp2ConnectionFactory = (
  authority: URL,
  options: SecureClientSessionOptions,
) => ClientHttp2Session;

export interface NodeHttp2ApnsTransportOptions {
  readonly connect?: ApnsHttp2ConnectionFactory;
  readonly requestTimeoutMilliseconds?: number;
  readonly responseBodyMaxBytes?: number;
}

interface SessionSlot {
  readonly authority: string;
  readonly session: ClientHttp2Session;
  connected: boolean;
  unusable: boolean;
  failureReason: ApnsTransportUnknownReason | undefined;
}

function configuredPositiveInteger(
  value: number,
  maximum: number,
  field: string,
): number {
  if (!Number.isSafeInteger(value) || value <= 0 || value > maximum) {
    throw new RangeError(`${field} is invalid`);
  }
  return value;
}

function containsControlCharacter(value: string, allowHorizontalTab = false): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.charCodeAt(index);
    if (
      codePoint === 0x7f ||
      (codePoint <= 0x1f && (!allowHorizontalTab || codePoint !== 0x09))
    ) {
      return true;
    }
  }
  return false;
}

function validatedMaterializedRequest(
  description: ApnsRequestDescription,
): { readonly request: ApnsMaterializedRequest; readonly endpoint: URL } {
  const request = description.materializeForTransport();
  if (request == null || typeof request !== "object") throw new Error();
  const endpoint = new URL(request.endpoint);
  if (
    endpoint.protocol !== "https:" ||
    endpoint.username !== "" ||
    endpoint.password !== "" ||
    endpoint.pathname !== "/" ||
    endpoint.search !== "" ||
    endpoint.hash !== "" ||
    endpoint.origin !== new URL(description.endpoint).origin ||
    (request.method !== "POST" &&
      request.method !== "GET" &&
      request.method !== "DELETE") ||
    typeof request.path !== "string" ||
    !request.path.startsWith("/") ||
    containsControlCharacter(request.path) ||
    request.headers == null ||
    typeof request.headers !== "object" ||
    (request.body !== null && typeof request.body !== "string") ||
    !Number.isSafeInteger(request.bodyByteLength) ||
    request.bodyByteLength < 0 ||
    request.bodyByteLength !==
      (request.body == null ? 0 : Buffer.byteLength(request.body, "utf8"))
  ) {
    throw new Error();
  }
  for (const [name, value] of Object.entries(request.headers)) {
    if (
      name.length === 0 ||
      name.startsWith(":") ||
      name !== name.toLowerCase() ||
      typeof value !== "string" ||
      containsControlCharacter(name) ||
      containsControlCharacter(value, true)
    ) {
      throw new Error();
    }
  }
  return Object.freeze({ request, endpoint });
}

function requestHeaders(
  request: ApnsMaterializedRequest,
  endpoint: URL,
): OutgoingHttpHeaders {
  const headers: OutgoingHttpHeaders = {
    ":method": request.method,
    ":scheme": "https",
    ":authority": endpoint.host,
    ":path": request.path,
    ...request.headers,
  };
  const neverIndex = [":path", "authorization"];
  if (Object.hasOwn(request.headers, "apns-channel-id")) {
    neverIndex.push("apns-channel-id");
  }
  (headers as Record<PropertyKey, unknown>)[sensitiveHeaders] = neverIndex;
  return headers;
}

function copiedResponseHeaders(
  headers: IncomingHttpHeaders,
): Readonly<Record<string, RawApnsTransportHeaderValue>> {
  const copied: Record<string, RawApnsTransportHeaderValue> = {};
  for (const [name, value] of Object.entries(headers)) {
    if (name.startsWith(":")) continue;
    copied[name] = Array.isArray(value) ? Object.freeze([...value]) : value;
  }
  return Object.freeze(copied);
}

/**
 * A lazy, reusable Node HTTP/2 transport. It never retries a request and never creates a
 * connection until send() is called explicitly.
 */
export class NodeHttp2ApnsTransport implements ApnsTransport {
  readonly #connect: ApnsHttp2ConnectionFactory;
  readonly #requestTimeoutMilliseconds: number;
  readonly #responseBodyMaxBytes: number;
  readonly #sessions = new Map<string, SessionSlot>();
  readonly #allSessions = new Set<ClientHttp2Session>();
  #closed = false;
  #closePromise: Promise<void> | undefined;

  constructor(options: NodeHttp2ApnsTransportOptions = {}) {
    if (options.connect != null && typeof options.connect !== "function") {
      throw new TypeError("APNs HTTP/2 connection factory is invalid");
    }
    this.#connect =
      options.connect ??
      ((authority, connectionOptions) =>
        connectHttp2(authority, connectionOptions));
    this.#requestTimeoutMilliseconds = configuredPositiveInteger(
      options.requestTimeoutMilliseconds ??
        BLICK_APNS_REQUEST_TIMEOUT_MILLISECONDS,
      MAX_CONFIGURED_REQUEST_TIMEOUT_MILLISECONDS,
      "APNs request timeout",
    );
    this.#responseBodyMaxBytes = configuredPositiveInteger(
      options.responseBodyMaxBytes ?? BLICK_APNS_RESPONSE_BODY_MAX_BYTES,
      MAX_CONFIGURED_RESPONSE_BODY_BYTES,
      "APNs response-body limit",
    );
  }

  async send(requestDescription: ApnsRequestDescription): Promise<ApnsTransportResult> {
    if (this.#closed) return apnsTransportNotAttempted("TRANSPORT_CLOSED");

    let materialized: ApnsMaterializedRequest;
    let endpoint: URL;
    try {
      ({ request: materialized, endpoint } =
        validatedMaterializedRequest(requestDescription));
    } catch {
      return apnsTransportNotAttempted("REQUEST_MATERIALIZATION_FAILED");
    }

    const acquired = this.#sessionFor(endpoint);
    if ("outcome" in acquired) return acquired;
    const slot = acquired;

    let stream: ClientHttp2Stream;
    try {
      stream = slot.session.request(requestHeaders(materialized, endpoint));
    } catch {
      this.#retire(slot, slot.failureReason ?? "SESSION_ERROR");
      return unknownApnsTransportOutcome(slot.failureReason ?? "SESSION_ERROR");
    }

    return await new Promise<ApnsTransportResult>((resolve) => {
      let settled = false;
      let responseStatus: number | undefined;
      let responseHeaders: Readonly<Record<string, RawApnsTransportHeaderValue>> =
        Object.freeze({});
      const chunks: Buffer[] = [];
      let responseBytes = 0;

      const settle = (result: ApnsTransportResult): void => {
        if (settled) return;
        settled = true;
        clearTimeout(deadline);
        resolve(result);
      };
      const unknown = (reason: ApnsTransportUnknownReason): void => {
        settle(unknownApnsTransportOutcome(reason));
      };
      const deadline = setTimeout(() => {
        unknown(slot.failureReason ?? "REQUEST_TIMEOUT");
        try {
          stream.close(constants.NGHTTP2_CANCEL);
        } catch {
          // The sanitized result is already settled.
        }
      }, this.#requestTimeoutMilliseconds);

      stream.once("response", (headers) => {
        const status = headers[":status"];
        responseStatus = typeof status === "number" ? status : undefined;
        responseHeaders = copiedResponseHeaders(headers);
      });
      stream.on("data", (chunk: Buffer | Uint8Array | string) => {
        if (settled) return;
        const copied = Buffer.isBuffer(chunk) ? Buffer.from(chunk) : Buffer.from(chunk);
        responseBytes += copied.byteLength;
        if (responseBytes > this.#responseBodyMaxBytes) {
          unknown("RESPONSE_TOO_LARGE");
          try {
            stream.close(constants.NGHTTP2_CANCEL);
          } catch {
            // The sanitized result is already settled.
          }
          return;
        }
        chunks.push(copied);
      });
      stream.once("end", () => {
        if (settled) return;
        if (responseStatus == null) {
          unknown(
            slot.failureReason ??
              (slot.session.closed || slot.session.destroyed
                ? "UNEXPECTED_CLOSE"
                : "INVALID_RESPONSE"),
          );
          return;
        }
        const raw: RawApnsTransportResponse = {
          statusCode: responseStatus,
          headers: responseHeaders,
          body: Buffer.concat(chunks, responseBytes),
        };
        try {
          settle(apnsTransportResponse(normalizeApnsTransportResponse(raw)));
        } catch {
          unknown("INVALID_RESPONSE");
        }
      });
      stream.once("error", () => {
        unknown(slot.failureReason ?? "STREAM_ERROR");
      });
      stream.once("aborted", () => {
        unknown(slot.failureReason ?? "STREAM_ERROR");
      });
      stream.once("close", () => {
        if (!settled) unknown(slot.failureReason ?? "UNEXPECTED_CLOSE");
      });

      try {
        stream.end(materialized.body ?? undefined);
      } catch {
        unknown(slot.failureReason ?? "STREAM_ERROR");
        try {
          stream.close(constants.NGHTTP2_CANCEL);
        } catch {
          // The sanitized result is already settled.
        }
      }
    });
  }

  close(): Promise<void> {
    if (this.#closePromise != null) return this.#closePromise;
    this.#closed = true;
    this.#sessions.clear();
    const sessions = [...this.#allSessions];
    this.#closePromise = Promise.all(
      sessions.map(
        (session) =>
          new Promise<void>((resolve) => {
            if (session.closed || session.destroyed) {
              resolve();
              return;
            }
            session.once("close", resolve);
            try {
              session.close();
            } catch {
              try {
                session.destroy();
              } finally {
                resolve();
              }
            }
          }),
      ),
    ).then(() => undefined);
    return this.#closePromise;
  }

  #sessionFor(endpoint: URL): SessionSlot | ApnsTransportResult {
    const authority = endpoint.origin;
    const current = this.#sessions.get(authority);
    if (
      current != null &&
      !current.unusable &&
      !current.session.closed &&
      !current.session.destroyed
    ) {
      return current;
    }
    if (current != null) this.#sessions.delete(authority);

    let session: ClientHttp2Session;
    try {
      session = this.#connect(new URL(authority), { minVersion: "TLSv1.2" });
    } catch {
      return unknownApnsTransportOutcome("CONNECTION_ERROR");
    }
    const slot: SessionSlot = {
      authority,
      session,
      connected: !session.connecting,
      unusable: false,
      failureReason: undefined,
    };
    this.#sessions.set(authority, slot);
    this.#allSessions.add(session);
    session.once("connect", () => {
      slot.connected = true;
    });
    session.on("error", () => {
      this.#retire(slot, slot.connected ? "SESSION_ERROR" : "CONNECTION_ERROR");
    });
    session.once("goaway", () => {
      this.#retire(slot, "GOAWAY");
    });
    session.once("close", () => {
      this.#retire(
        slot,
        slot.failureReason ??
          (slot.connected ? "UNEXPECTED_CLOSE" : "CONNECTION_ERROR"),
      );
      this.#allSessions.delete(session);
    });
    if (session.closed || session.destroyed) {
      this.#retire(slot, "CONNECTION_ERROR");
      return unknownApnsTransportOutcome("CONNECTION_ERROR");
    }
    return slot;
  }

  #retire(slot: SessionSlot, reason: ApnsTransportUnknownReason): void {
    slot.unusable = true;
    slot.failureReason ??= reason;
    if (this.#sessions.get(slot.authority) === slot) {
      this.#sessions.delete(slot.authority);
    }
  }
}
