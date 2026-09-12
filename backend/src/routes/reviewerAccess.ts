import { Hono } from "hono";
import { z } from "zod";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import {
  reviewerAttemptFingerprint,
  type ReviewerAccessAuthorizer,
  type ReviewerAccessClientFingerprinter,
} from "../reviewerAccess/reviewerAccessAuthorizer.js";
import type { ReviewerAccessRateLimiter } from "../reviewerAccess/reviewerAccessRateLimiter.js";

export const REVIEWER_ACCESS_CODE_MIN_LENGTH = 32;
export const REVIEWER_ACCESS_CODE_MAX_LENGTH = 256;
export const REVIEWER_ACCESS_REQUEST_MAX_BYTES = 1_024;
export const REVIEWER_ACCESS_OPERATION_TIMEOUT_MS = 2_000;

const RequestSchema = z.object({
  code: z.string().trim()
    .min(REVIEWER_ACCESS_CODE_MIN_LENGTH)
    .max(REVIEWER_ACCESS_CODE_MAX_LENGTH),
}).strict();

class ReviewerAccessOperationTimeout extends Error {}

function throwIfReviewerAccessTimedOut(signal: AbortSignal): void {
  if (signal.aborted) throw new ReviewerAccessOperationTimeout();
}

async function readBoundedJson(request: Request, signal: AbortSignal): Promise<unknown> {
  throwIfReviewerAccessTimedOut(signal);
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.match(/^application\/json(?:\s*;|$)/)) {
    throw new AppError("VALIDATION_ERROR", "Request body must use application/json");
  }

  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    const normalized = contentLength.trim();
    if (!/^\d+$/.test(normalized)) {
      throw new AppError("VALIDATION_ERROR", "Invalid request body length");
    }
    const declaredBytes = Number(normalized);
    if (!Number.isSafeInteger(declaredBytes) || declaredBytes > REVIEWER_ACCESS_REQUEST_MAX_BYTES) {
      throw new AppError("VALIDATION_ERROR", "Reviewer access request is too large");
    }
  }

  if (!request.body) {
    throw new AppError("VALIDATION_ERROR", "Request body must be valid JSON");
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let receivedBytes = 0;
  const cancelReader = () => {
    void reader.cancel().catch(() => {
      // The deadline response remains authoritative if stream cancellation is unsupported.
    });
  };
  signal.addEventListener("abort", cancelReader, { once: true });
  try {
    while (true) {
      const { done, value } = await reader.read();
      throwIfReviewerAccessTimedOut(signal);
      if (done) break;
      receivedBytes += value.byteLength;
      if (receivedBytes > REVIEWER_ACCESS_REQUEST_MAX_BYTES) {
        try {
          await reader.cancel();
        } catch {
          // The bounded rejection below is authoritative even if stream cancellation fails.
        }
        throw new AppError("VALIDATION_ERROR", "Reviewer access request is too large");
      }
      chunks.push(value);
    }
  } finally {
    signal.removeEventListener("abort", cancelReader);
    reader.releaseLock();
  }

  const bodyBytes = new Uint8Array(receivedBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bodyBytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  let bodyText: string;
  try {
    bodyText = new TextDecoder("utf-8", { fatal: true }).decode(bodyBytes);
  } catch {
    throw new AppError("VALIDATION_ERROR", "Request body must be valid UTF-8 JSON");
  }

  try {
    return JSON.parse(bodyText) as unknown;
  } catch {
    throw new AppError("VALIDATION_ERROR", "Request body must be valid JSON");
  }
}

async function withReviewerAccessTimeout<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  timeoutMs: number,
): Promise<T> {
  const controller = new AbortController();
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      Promise.resolve().then(() => operation(controller.signal)),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => {
          controller.abort();
          reject(new ReviewerAccessOperationTimeout());
        }, timeoutMs);
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

async function runDependency<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof ReviewerAccessOperationTimeout) throw error;
    throw new AppError("UPSTREAM_ERROR", "Reviewer access is temporarily unavailable");
  }
}

export function createReviewerAccessRoute(
  authorizer: ReviewerAccessAuthorizer | undefined,
  rateLimiter: ReviewerAccessRateLimiter | undefined,
  clientFingerprinter: ReviewerAccessClientFingerprinter | undefined,
  operationTimeoutMs = REVIEWER_ACCESS_OPERATION_TIMEOUT_MS,
) {
  const route = new Hono();

  route.use("*", async (c, next) => {
    c.header("Cache-Control", "no-store");
    await next();
  });

  route.post("/validate", async (c) => {
    try {
      return await withReviewerAccessTimeout(async (signal) => {
        const raw = await readBoundedJson(c.req.raw, signal);
        const parsed = RequestSchema.safeParse(raw);
        if (!parsed.success) {
          throw new AppError("VALIDATION_ERROR", "Invalid reviewer access request");
        }

        if (!authorizer || !rateLimiter || !clientFingerprinter) {
          throw new AppError("UPSTREAM_ERROR", "Reviewer access is temporarily unavailable");
        }

        const clientFingerprint = await runDependency(async () =>
          clientFingerprinter.fingerprint(c.req.raw));
        const allowed = await runDependency(() => rateLimiter.allow(
          reviewerAttemptFingerprint(parsed.data.code),
          clientFingerprint,
        ));
        throwIfReviewerAccessTimedOut(signal);
        if (!allowed) {
          throw new AppError("RATE_LIMITED", "Too many reviewer access attempts");
        }

        const authorized = await runDependency(() => authorizer.authorize(parsed.data.code));
        throwIfReviewerAccessTimedOut(signal);
        return c.json(successEnvelope({ authorized }));
      }, operationTimeoutMs);
    } catch (error) {
      if (error instanceof ReviewerAccessOperationTimeout) {
        throw new AppError("UPSTREAM_TIMEOUT", "Reviewer access validation timed out");
      }
      throw error;
    }
  });

  return route;
}
