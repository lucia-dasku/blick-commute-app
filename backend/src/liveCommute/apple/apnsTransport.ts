import {
  normalizeApnsTransportResponse,
  type ApnsRequestDescription,
  type NormalizedApnsTransportResponse,
  type RawApnsTransportResponse,
  type RedactedApnsRequestDiagnostic,
} from "./apnsProtocol.js";

const FAKE_SCRIPT_EXHAUSTED_MESSAGE = "Fake APNs transport response script is exhausted";
const DIAGNOSTIC_FAILURE_MESSAGE = "APNs request diagnostic could not be recorded";

export type ApnsTransportUnknownReason =
  | "CONNECTION_ERROR"
  | "SESSION_ERROR"
  | "STREAM_ERROR"
  | "REQUEST_TIMEOUT"
  | "UNEXPECTED_CLOSE"
  | "GOAWAY"
  | "RESPONSE_TOO_LARGE"
  | "INVALID_RESPONSE";

export type ApnsTransportNotAttemptedReason =
  | "REQUEST_MATERIALIZATION_FAILED"
  | "TRANSPORT_CLOSED";

export interface ApnsTransportResponseResult {
  readonly outcome: "APNS_RESPONSE";
  readonly response: NormalizedApnsTransportResponse;
}

export interface ApnsTransportUnknownResult {
  readonly outcome: "OUTCOME_UNKNOWN";
  readonly reason: ApnsTransportUnknownReason;
}

export interface ApnsTransportNotAttemptedResult {
  readonly outcome: "NOT_ATTEMPTED";
  readonly reason: ApnsTransportNotAttemptedReason;
}

export type ApnsTransportResult =
  | ApnsTransportResponseResult
  | ApnsTransportUnknownResult
  | ApnsTransportNotAttemptedResult;

/**
 * Network-independent APNs boundary. Implementations make at most one transport attempt.
 * A provable local refusal is NOT_ATTEMPTED; an absent definitive response after an attempt
 * is OUTCOME_UNKNOWN. Neither result is retried here.
 */
export interface ApnsTransport {
  send(request: ApnsRequestDescription): Promise<ApnsTransportResult>;
  close(): Promise<void>;
}

export function apnsTransportResponse(
  response: NormalizedApnsTransportResponse,
): ApnsTransportResponseResult {
  return Object.freeze({ outcome: "APNS_RESPONSE", response });
}

export function unknownApnsTransportOutcome(
  reason: ApnsTransportUnknownReason,
): ApnsTransportUnknownResult {
  return Object.freeze({ outcome: "OUTCOME_UNKNOWN", reason });
}

export function apnsTransportNotAttempted(
  reason: ApnsTransportNotAttemptedReason,
): ApnsTransportNotAttemptedResult {
  return Object.freeze({ outcome: "NOT_ATTEMPTED", reason });
}

export class FakeApnsTransportScriptExhaustedError extends Error {
  readonly code = "FAKE_APNS_TRANSPORT_SCRIPT_EXHAUSTED" as const;

  constructor() {
    super(FAKE_SCRIPT_EXHAUSTED_MESSAGE);
    this.name = "FakeApnsTransportScriptExhaustedError";
  }
}

export class ApnsRequestDiagnosticError extends Error {
  readonly code = "APNS_REQUEST_DIAGNOSTIC_FAILED" as const;

  constructor() {
    super(DIAGNOSTIC_FAILURE_MESSAGE);
    this.name = "ApnsRequestDiagnosticError";
  }
}

function cloneRedactedDiagnostic(
  diagnostic: RedactedApnsRequestDiagnostic,
): RedactedApnsRequestDiagnostic {
  try {
    const serialized = JSON.stringify(diagnostic);
    if (serialized == null) throw new Error();
    return Object.freeze(JSON.parse(serialized) as RedactedApnsRequestDiagnostic);
  } catch {
    throw new ApnsRequestDiagnosticError();
  }
}

function copiedRawResponse(
  response: RawApnsTransportResponse,
): RawApnsTransportResponse {
  const headers = response.headers == null
    ? undefined
    : Object.freeze(
        Object.fromEntries(
          Object.entries(response.headers).map(([name, value]) => [
            name,
            Array.isArray(value) ? Object.freeze([...value]) : value,
          ]),
        ),
      );
  const body =
    response.body instanceof Uint8Array
      ? Buffer.from(response.body)
      : response.body;
  return Object.freeze({
    statusCode: response.statusCode,
    headers,
    body,
  });
}

/**
 * FIFO fake that performs no request materialization and therefore never handles a
 * provider token, device token, channel identifier, or payload body. It records only
 * the description's explicitly redacted diagnostic value.
 */
export class DeterministicFakeApnsTransport implements ApnsTransport {
  readonly #responses: readonly RawApnsTransportResponse[];
  readonly #recordedDiagnostics: RedactedApnsRequestDiagnostic[] = [];
  #nextResponseIndex = 0;

  constructor(script: readonly RawApnsTransportResponse[]) {
    this.#responses = Object.freeze(
      script.map((response) => {
        const copied = copiedRawResponse(response);
        normalizeApnsTransportResponse(copied);
        return copied;
      }),
    );
  }

  get sendCount(): number {
    return this.#recordedDiagnostics.length;
  }

  recordedDiagnostics(): readonly RedactedApnsRequestDiagnostic[] {
    return Object.freeze(
      this.#recordedDiagnostics.map((diagnostic) =>
        cloneRedactedDiagnostic(diagnostic),
      ),
    );
  }

  async send(
    request: ApnsRequestDescription,
  ): Promise<ApnsTransportResult> {
    const diagnostic = cloneRedactedDiagnostic(request.toRedactedDiagnostic());
    this.#recordedDiagnostics.push(diagnostic);

    const response = this.#responses[this.#nextResponseIndex];
    if (response == null) throw new FakeApnsTransportScriptExhaustedError();
    this.#nextResponseIndex += 1;
    return apnsTransportResponse(
      normalizeApnsTransportResponse(copiedRawResponse(response)),
    );
  }

  async close(): Promise<void> {
    // The deterministic fake owns no runtime resources.
  }
}
