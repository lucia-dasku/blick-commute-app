import type { ZodType, ZodTypeDef } from "zod";
import { config } from "../config/env.js";
import { AppError } from "./errors.js";
import { isValidRetryAfterValue } from "./retryAfter.js";
import { parseLosslessJson } from "./losslessJson.js";

export interface FetchUpstreamJsonOptions {
  /** Human-readable upstream name for error messages/logs, e.g. "SL Transport". Never a URL. */
  upstreamName: string;
  /** Overrides `config.upstreamTimeoutMs` for this call, mainly for tests. */
  timeoutMs?: number;
}

/**
 * Fetches `url` and enforces a request timeout via `AbortController`, mapping the response's
 * HTTP status to the documented error codes — the shared first half of both `fetchUpstreamJson`
 * and `fetchUpstreamJsonLossless` below, which differ only in how they read/parse the response
 * BODY once this has already produced a `2xx` `Response`. Never called directly outside this
 * file. See `fetchUpstreamJson`'s own doc for the full documented error mapping this covers.
 */
async function fetchUpstreamOkResponse(
  url: string,
  controller: AbortController,
  timeoutMs: number,
  upstreamName: string,
): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal: controller.signal,
    });
  } catch (cause) {
    if (controller.signal.aborted) {
      throw new AppError("UPSTREAM_TIMEOUT", `${upstreamName} did not complete within ${timeoutMs}ms`, { cause });
    }
    // Any non-abort failure (DNS, connection reset, TLS error, etc.) is an ordinary
    // upstream failure, not a timeout — do not misclassify it as one.
    throw new AppError("UPSTREAM_ERROR", `Failed to reach ${upstreamName}`, { cause });
  }

  if (response.status === 429) {
    const rawRetryAfter = response.headers.get("Retry-After");
    const retryAfter = rawRetryAfter != null && isValidRetryAfterValue(rawRetryAfter) ? rawRetryAfter : undefined;
    throw new AppError("UPSTREAM_RATE_LIMITED", `${upstreamName} rate-limited this request`, { retryAfter });
  }

  if (!response.ok) {
    throw new AppError("UPSTREAM_ERROR", `${upstreamName} returned an error response`, { cause: { status: response.status } });
  }

  return response;
}

/**
 * Fetches `url`, enforces a request timeout via `AbortController`, maps the response's
 * HTTP status to the documented error codes, and runtime-validates the JSON body
 * against `schema` before returning it. See docs/api-contract.md, "Upstream networking"
 * and "Runtime validation".
 *
 * The timeout budget covers the WHOLE operation — waiting for response headers AND
 * reading/parsing the response body — not just the initial `fetch()` call. The same
 * `AbortController` stays live (and its timer uncleared) until either the body has been
 * fully read or an error path returns; a response whose headers arrive promptly but
 * whose body then stalls still aborts and reports `UPSTREAM_TIMEOUT`, rather than
 * hanging indefinitely past the configured timeout.
 *
 * Error mapping (never leaks `url`, response bodies, or raw exceptions to the client —
 * only `AppError`'s own public-safe `message` reaches the caller; the real cause is
 * attached via `options.cause` for server-side logging only, see errorHandler.ts):
 *  - The request does not complete within the timeout (whether waiting for headers or
 *    for the body to finish arriving) -> `UPSTREAM_TIMEOUT` (504).
 *  - Any other network-level failure (DNS, connection reset, etc.) -> `UPSTREAM_ERROR` (502).
 *  - HTTP 429 -> `UPSTREAM_RATE_LIMITED` (503), preserving `Retry-After` only when it is a
 *    valid non-negative delay-seconds value or a valid HTTP-date; an invalid value is
 *    omitted rather than forwarded (see src/lib/retryAfter.ts).
 *  - Any other non-2xx HTTP status -> `UPSTREAM_ERROR` (502).
 *  - A response body that isn't valid JSON (and wasn't a timeout) -> `UPSTREAM_ERROR` (502).
 *  - A response body that is valid JSON but fails `schema` validation -> `UPSTREAM_ERROR` (502).
 */
export async function fetchUpstreamJson<T>(
  url: string,
  // The third ("Input") type param is deliberately loosened to `any` rather than left to
  // default to `T` (the Output type): a schema using `.transform()`/`.pipe()` (see
  // upstreamTypes.ts's own RawStopPointSchema, which coerces id/stop_area.id from the lossless
  // parser's string output to a real number) has a genuinely different Input type from its
  // Output type, and TypeScript's structural check on ZodType's Input parameter would otherwise
  // reject passing such a schema in here at all -- even though passing it is exactly what these
  // two functions are for. This widens ONLY the compile-time parameter type; it changes no
  // runtime behavior and every existing (non-transforming) schema already satisfies it trivially.
  schema: ZodType<T, ZodTypeDef, unknown>,
  options: FetchUpstreamJsonOptions,
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? config.upstreamTimeoutMs;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetchUpstreamOkResponse(url, controller, timeoutMs, options.upstreamName);

    // The timeout/abort signal is still live here — reading and parsing the body is
    // covered by the same budget as waiting for headers was. A stalled body stream
    // aborts just like a stalled header wait would.
    let json: unknown;
    try {
      json = await response.json();
    } catch (cause) {
      if (controller.signal.aborted) {
        throw new AppError(
          "UPSTREAM_TIMEOUT",
          `${options.upstreamName} did not complete within ${timeoutMs}ms (timed out while receiving the response body)`,
          { cause },
        );
      }
      throw new AppError(
        "UPSTREAM_ERROR",
        `${options.upstreamName} returned a response that was not valid JSON`,
        { cause },
      );
    }

    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new AppError(
        "UPSTREAM_ERROR",
        `${options.upstreamName} returned data that did not match the expected shape`,
        { cause: parsed.error },
      );
    }

    return parsed.data;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Identical contract to `fetchUpstreamJson` above (same timeout/network/429/non-2xx error
 * mapping — both share `fetchUpstreamOkResponse`), except the body is read as TEXT and parsed
 * with `parseLosslessJson` instead of `response.json()` — every JSON number in the body comes
 * back as its exact source digit string, never a JS `number` that could silently round a value
 * beyond `Number.MAX_SAFE_INTEGER`. See `lib/losslessJson.ts`'s own doc for why this matters and
 * why it is a genuinely separate function rather than a flag on `fetchUpstreamJson`: every OTHER
 * upstream call in this codebase is unaffected, unchanged, and keeps using the ordinary,
 * already-proven `response.json()` path.
 *
 * The ONLY caller today is `slTransportClient.ts`'s `fetchStopPoints()`.
 */
export async function fetchUpstreamJsonLossless<T>(
  url: string,
  // The third ("Input") type param is deliberately loosened to `any` rather than left to
  // default to `T` (the Output type): a schema using `.transform()`/`.pipe()` (see
  // upstreamTypes.ts's own RawStopPointSchema, which coerces id/stop_area.id from the lossless
  // parser's string output to a real number) has a genuinely different Input type from its
  // Output type, and TypeScript's structural check on ZodType's Input parameter would otherwise
  // reject passing such a schema in here at all -- even though passing it is exactly what these
  // two functions are for. This widens ONLY the compile-time parameter type; it changes no
  // runtime behavior and every existing (non-transforming) schema already satisfies it trivially.
  schema: ZodType<T, ZodTypeDef, unknown>,
  options: FetchUpstreamJsonOptions,
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? config.upstreamTimeoutMs;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetchUpstreamOkResponse(url, controller, timeoutMs, options.upstreamName);

    let text: string;
    try {
      text = await response.text();
    } catch (cause) {
      if (controller.signal.aborted) {
        throw new AppError(
          "UPSTREAM_TIMEOUT",
          `${options.upstreamName} did not complete within ${timeoutMs}ms (timed out while receiving the response body)`,
          { cause },
        );
      }
      throw new AppError("UPSTREAM_ERROR", `Failed to read the response from ${options.upstreamName}`, { cause });
    }

    let json: unknown;
    try {
      json = parseLosslessJson(text);
    } catch (cause) {
      // A stalled body triggers the abort during response.text() above, never here — this
      // catch is reached only once the full body was already read successfully, so a parse
      // failure here is always a genuine malformed-JSON response, never a timeout.
      throw new AppError(
        "UPSTREAM_ERROR",
        `${options.upstreamName} returned a response that was not valid JSON`,
        { cause },
      );
    }

    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new AppError(
        "UPSTREAM_ERROR",
        `${options.upstreamName} returned data that did not match the expected shape`,
        { cause: parsed.error },
      );
    }

    return parsed.data;
  } finally {
    clearTimeout(timer);
  }
}
