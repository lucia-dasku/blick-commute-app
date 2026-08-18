import type { Context } from "hono";
import { AppError, isAppError } from "../lib/errors.js";
import { errorEnvelope } from "../models/common.js";

/**
 * Registered via `app.onError(...)`, which is Hono's documented mechanism for a global
 * error handler — a `try/catch`-wrapping middleware around `next()` does NOT reliably
 * see errors thrown from route handlers (Hono resolves them to its own default
 * "Internal Server Error" response before such middleware's catch block runs). This was
 * confirmed with a minimal repro during development; see docs/api-contract.md.
 *
 * Error responses never expose internals to the client:
 *  - A known `AppError`'s `message` is considered public-safe by construction — every
 *    `AppError` call site in this codebase is written to be shown to a caller — and is
 *    returned as-is.
 *  - Any other (unexpected) error returns only a generic, fixed message. The real error
 *    — including any attached `AppError.cause_` — is logged server-side via
 *    `console.error` for operators, but never serialized into the response: no upstream
 *    URLs, raw exception messages, stack traces, or upstream response bodies ever reach
 *    the client this way.
 *  - Every error response sets `Cache-Control: no-store`: an error must never be served
 *    stale from an edge cache.
 */
export function onError(err: Error, c: Context) {
  c.header("Cache-Control", "no-store");

  if (isAppError(err)) {
    if (err.retryAfter) {
      c.header("Retry-After", err.retryAfter);
    }
    if (err.cause_ !== undefined) {
      console.error(`[${err.code}] ${err.message}`, err.cause_);
    }
    return c.json(
      errorEnvelope(err.code, err.message),
      err.httpStatus as 400 | 401 | 404 | 429 | 500 | 502 | 503 | 504,
    );
  }

  console.error("Unhandled error:", err);
  return c.json(errorEnvelope("INTERNAL_ERROR", "Unexpected internal error"), 500);
}

export function notFoundHandler(c: Context) {
  c.header("Cache-Control", "no-store");
  return c.json(errorEnvelope("NOT_FOUND", `No route for ${c.req.method} ${c.req.path}`), 404);
}

export { AppError };
