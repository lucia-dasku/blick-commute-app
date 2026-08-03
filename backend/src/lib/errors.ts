export type ErrorCode =
  | "VALIDATION_ERROR"
  | "UPSTREAM_ERROR"
  | "UPSTREAM_TIMEOUT"
  | "UPSTREAM_RATE_LIMITED"
  | "NOT_FOUND"
  | "INTERNAL_ERROR";

const STATUS_BY_CODE: Record<ErrorCode, number> = {
  VALIDATION_ERROR: 400,
  NOT_FOUND: 404,
  UPSTREAM_RATE_LIMITED: 503,
  UPSTREAM_TIMEOUT: 504,
  UPSTREAM_ERROR: 502,
  INTERNAL_ERROR: 500,
};

/**
 * A typed application error carrying a machine-readable code (see docs/api-contract.md).
 * Thrown from routes/services and translated into the error envelope by
 * `middleware/errorHandler.ts`.
 *
 * `message` is always considered safe to return to a client verbatim — every call site
 * in this codebase is expected to write a public-safe message here. The optional
 * `cause` is logged server-side only (see errorHandler.ts) and must never itself be
 * serialized into a response; it exists purely for operator diagnostics and must never
 * carry upstream URLs, raw response bodies, or client-supplied secrets in a form that
 * could leak if this contract is ever violated by a future call site.
 */
export class AppError extends Error {
  readonly code: ErrorCode;
  readonly httpStatus: number;
  readonly cause_?: unknown;
  /** Raw `Retry-After` header value to forward to the client, if the upstream sent one. */
  readonly retryAfter?: string;

  constructor(code: ErrorCode, message: string, options?: { cause?: unknown; retryAfter?: string }) {
    super(message);
    this.name = "AppError";
    this.code = code;
    this.httpStatus = STATUS_BY_CODE[code];
    this.cause_ = options?.cause;
    this.retryAfter = options?.retryAfter;
  }
}

export function isAppError(error: unknown): error is AppError {
  return error instanceof AppError;
}
