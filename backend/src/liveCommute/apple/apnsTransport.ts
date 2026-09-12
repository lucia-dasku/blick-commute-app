import {
  normalizeApnsTransportResponse,
  type ApnsRequestDescription,
  type NormalizedApnsTransportResponse,
  type RawApnsTransportResponse,
  type RedactedApnsRequestDiagnostic,
} from "./apnsProtocol.js";

const FAKE_SCRIPT_EXHAUSTED_MESSAGE = "Fake APNs transport response script is exhausted";
const DIAGNOSTIC_FAILURE_MESSAGE = "APNs request diagnostic could not be recorded";

/**
 * Network-independent boundary for a future APNs HTTP/2 implementation. A real
 * implementation may materialize the request's sensitive wire data immediately before
 * sending; callers and fakes should otherwise use only its redacted diagnostic surface.
 */
export interface ApnsTransport {
  send(request: ApnsRequestDescription): Promise<NormalizedApnsTransportResponse>;
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
  ): Promise<NormalizedApnsTransportResponse> {
    const diagnostic = cloneRedactedDiagnostic(request.toRedactedDiagnostic());
    this.#recordedDiagnostics.push(diagnostic);

    const response = this.#responses[this.#nextResponseIndex];
    if (response == null) throw new FakeApnsTransportScriptExhaustedError();
    this.#nextResponseIndex += 1;
    return normalizeApnsTransportResponse(copiedRawResponse(response));
  }
}
