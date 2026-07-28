import type { ZodType } from "zod";
import { config } from "../config/env.js";
import { AppError } from "./errors.js";
import { isValidRetryAfterValue } from "./retryAfter.js";

export interface FetchUpstreamJsonOptions {
  /** Human-readable upstream name for error messages/logs, e.g. "SL Transport". Never a URL. */
  upstreamName: string;
  /** Overrides `config.upstreamTimeoutMs` for this call, mainly for tests. */
  timeoutMs?: number;
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
  schema: ZodType<T>,
  options: FetchUpstreamJsonOptions,
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? config.upstreamTimeoutMs;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    let response: Response;
    try {
      response = await fetch(url, {
        headers: { Accept: "application/json" },
        signal: controller.signal,
      });
    } catch (cause) {
      if (controller.signal.aborted) {
        throw new AppError(
          "UPSTREAM_TIMEOUT",
          `${options.upstreamName} did not complete within ${timeoutMs}ms`,
          { cause },
        );
      }
      // Any non-abort failure (DNS, connection reset, TLS error, etc.) is an ordinary
      // upstream failure, not a timeout — do not misclassify it as one.
      throw new AppError("UPSTREAM_ERROR", `Failed to reach ${options.upstreamName}`, { cause });
    }

    if (response.status === 429) {
      const rawRetryAfter = response.headers.get("Retry-After");
      const retryAfter =
        rawRetryAfter != null && isValidRetryAfterValue(rawRetryAfter) ? rawRetryAfter : undefined;
      throw new AppError(
        "UPSTREAM_RATE_LIMITED",
        `${options.upstreamName} rate-limited this request`,
        { retryAfter },
      );
    }

    if (!response.ok) {
      throw new AppError(
        "UPSTREAM_ERROR",
        `${options.upstreamName} returned an error response`,
        { cause: { status: response.status } },
      );
    }

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
