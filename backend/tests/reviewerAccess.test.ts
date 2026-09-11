import { createHash, randomBytes } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { InMemoryBillingRateLimiter, RedisBillingRateLimiter } from "../src/billing/billingRateLimiter.js";
import type { RedisLike } from "../src/lib/redisClient.js";
import { onError } from "../src/middleware/errorHandler.js";
import {
  HmacReviewerAccessClientFingerprinter,
  reviewerAttemptFingerprint,
  Sha256ReviewerAccessAuthorizer,
  type ReviewerAccessAuthorizer,
} from "../src/reviewerAccess/reviewerAccessAuthorizer.js";
import {
  InMemoryReviewerAccessRateLimiter,
  REVIEWER_ACCESS_EMERGENCY_GLOBAL_LIMIT,
  REVIEWER_ACCESS_PER_ATTEMPT_LIMIT,
  REVIEWER_ACCESS_PER_CLIENT_LIMIT,
  RedisReviewerAccessRateLimiter,
  type ReviewerAccessRateLimiter,
} from "../src/reviewerAccess/reviewerAccessRateLimiter.js";
import {
  createReviewerAccessRoute,
  REVIEWER_ACCESS_CODE_MAX_LENGTH,
  REVIEWER_ACCESS_CODE_MIN_LENGTH,
  REVIEWER_ACCESS_REQUEST_MAX_BYTES,
} from "../src/routes/reviewerAccess.js";
import type { ErrorEnvelope, SuccessEnvelope } from "./testHelpers.js";

function newCode(): string {
  return randomBytes(32).toString("base64url");
}

function authorizerFor(code: string): ReviewerAccessAuthorizer {
  const digest = createHash("sha256").update(code, "utf8").digest("hex");
  return new Sha256ReviewerAccessAuthorizer(digest);
}

function testRoute(
  authorizer: ReviewerAccessAuthorizer | undefined,
  rateLimiter: ReviewerAccessRateLimiter | undefined = { allow: async () => true },
  timeoutMs = 100,
) {
  const clientFingerprinter = new HmacReviewerAccessClientFingerprinter("ab".repeat(32), false);
  const app = createReviewerAccessRoute(
    authorizer,
    rateLimiter,
    clientFingerprinter,
    timeoutMs,
  );
  app.onError(onError);
  return app;
}

function validate(
  app: ReturnType<typeof createReviewerAccessRoute>,
  body: string,
  headers: Record<string, string> = {},
) {
  return app.request("/validate", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body,
  });
}

describe("reviewer access authorizer", () => {
  it("accepts the configured code, trims surrounding whitespace, and rejects another code", async () => {
    const code = newCode();
    const authorizer = authorizerFor(code);

    expect(await authorizer.authorize(code)).toBe(true);
    expect(await authorizer.authorize(` \r\n${code}\t `)).toBe(true);
    expect(await authorizer.authorize(newCode())).toBe(false);
  });

  it("creates a stable, domain-separated attempt fingerprint without retaining the raw code", () => {
    const code = newCode();
    const fingerprint = reviewerAttemptFingerprint(code);

    expect(fingerprint).toMatch(/^[a-f0-9]{64}$/);
    expect(fingerprint).toBe(reviewerAttemptFingerprint(`  ${code}  `));
    expect(fingerprint).not.toBe(createHash("sha256").update(code).digest("hex"));
    expect(fingerprint).not.toContain(code);
  });

  it("creates a keyed client fingerprint from Vercel's trusted address header", () => {
    const key = "cd".repeat(32);
    const fingerprinter = new HmacReviewerAccessClientFingerprinter(key, true);
    const request = new Request("https://example.test", {
      headers: {
        "x-vercel-forwarded-for": "203.0.113.24, 10.0.0.1",
        "x-forwarded-for": "198.51.100.99",
      },
    });
    const fingerprint = fingerprinter.fingerprint(request);

    expect(fingerprint).toMatch(/^[a-f0-9]{64}$/);
    expect(fingerprint).toBe(fingerprinter.fingerprint(request));
    expect(fingerprint).not.toContain("203.0.113.24");
    expect(fingerprint).not.toBe(
      new HmacReviewerAccessClientFingerprinter("ef".repeat(32), true).fingerprint(request),
    );
  });

  it("uses one conservative fallback bucket when forwarding headers are not trustworthy", () => {
    const fingerprinter = new HmacReviewerAccessClientFingerprinter("cd".repeat(32), false);
    const first = new Request("http://localhost", {
      headers: {
        "x-vercel-forwarded-for": "203.0.113.24",
        "x-forwarded-for": "198.51.100.99",
      },
    });
    const second = new Request("http://localhost", {
      headers: {
        "x-vercel-forwarded-for": "192.0.2.18",
        "x-forwarded-for": "192.0.2.19",
      },
    });

    expect(fingerprinter.fingerprint(first)).toBe(fingerprinter.fingerprint(second));
  });

  it("falls back safely when the trusted Vercel address is missing or malformed", () => {
    const fingerprinter = new HmacReviewerAccessClientFingerprinter("cd".repeat(32), true);
    const missing = new Request("https://example.test");
    const malformed = new Request("https://example.test", {
      headers: { "x-vercel-forwarded-for": "not-an-ip-address" },
    });

    expect(fingerprinter.fingerprint(malformed)).toBe(fingerprinter.fingerprint(missing));
  });
});

describe("POST /reviewer-access/validate", () => {
  it("authorizes the configured reusable code and returns no-store", async () => {
    const code = newCode();
    const allow = vi.fn(async () => true);
    const response = await validate(
      testRoute(authorizerFor(code), { allow }),
      JSON.stringify({ code: `  ${code}\n` }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(await response.json()).toEqual({
      schemaVersion: 1,
      data: { authorized: true },
    } satisfies SuccessEnvelope<{ authorized: boolean }>);
    expect(allow).toHaveBeenCalledWith(
      reviewerAttemptFingerprint(code),
      expect.stringMatching(/^[a-f0-9]{64}$/),
    );
  });

  it("returns an explicit denied result for a wrong nonblank code", async () => {
    const response = await validate(
      testRoute(authorizerFor(newCode())),
      JSON.stringify({ code: newCode() }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(await response.json()).toMatchObject({ data: { authorized: false } });
  });

  it("is stateless and permits reuse from independent installations", async () => {
    const code = newCode();
    const authorizer = authorizerFor(code);
    const firstInstallation = testRoute(authorizer);
    const secondInstallation = testRoute(authorizer);

    const [first, second] = await Promise.all([
      validate(firstInstallation, JSON.stringify({ code })),
      validate(secondInstallation, JSON.stringify({ code })),
    ]);

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect((await first.json()) as SuccessEnvelope<{ authorized: boolean }>).toMatchObject({
      data: { authorized: true },
    });
    expect((await second.json()) as SuccessEnvelope<{ authorized: boolean }>).toMatchObject({
      data: { authorized: true },
    });
  });

  it.each(["", " ", "\r\n\t"])("rejects a blank code without calling the authorizer (%j)", async (code) => {
    const authorize = vi.fn(async () => true);
    const response = await validate(testRoute({ authorize }), JSON.stringify({ code }));

    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
  });

  it("rejects a code below the security minimum without calling the authorizer", async () => {
    const authorize = vi.fn(async () => true);
    const response = await validate(
      testRoute({ authorize }),
      JSON.stringify({ code: "x".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH - 1) }),
    );

    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
  });

  it("rejects a code over the character limit without calling the authorizer", async () => {
    const authorize = vi.fn(async () => true);
    const response = await validate(
      testRoute({ authorize }),
      JSON.stringify({ code: "x".repeat(REVIEWER_ACCESS_CODE_MAX_LENGTH + 1) }),
    );

    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
  });

  it("rejects an oversized declared Content-Length before validation", async () => {
    const authorize = vi.fn(async () => true);
    const response = await validate(
      testRoute({ authorize }),
      JSON.stringify({ code: newCode() }),
      { "Content-Length": String(REVIEWER_ACCESS_REQUEST_MAX_BYTES + 1) },
    );

    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
  });

  it("enforces the byte cap while streaming when Content-Length is absent", async () => {
    const authorize = vi.fn(async () => true);
    const code = newCode();
    const body = `${" ".repeat(REVIEWER_ACCESS_REQUEST_MAX_BYTES)}${JSON.stringify({ code })}`;
    const response = await validate(testRoute({ authorize }), body);

    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
  });

  it("rejects malformed JSON before rate limiting or authorization", async () => {
    const authorize = vi.fn(async () => true);
    const allow = vi.fn(async () => true);
    const response = await validate(testRoute({ authorize }, { allow }), "{not json");

    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect((await response.json()) as ErrorEnvelope).toEqual({
      schemaVersion: 1,
      error: { code: "VALIDATION_ERROR", message: "Request body must be valid JSON" },
    });
    expect(allow).not.toHaveBeenCalled();
    expect(authorize).not.toHaveBeenCalled();
  });

  it("rejects non-JSON content types and extra request fields", async () => {
    const code = newCode();
    const app = testRoute(authorizerFor(code));

    const wrongType = await validate(
      app,
      JSON.stringify({ code }),
      { "Content-Type": "text/plain" },
    );
    const extraField = await validate(
      app,
      JSON.stringify({ code, purchaseToken: newCode() }),
    );

    expect(wrongType.status).toBe(400);
    expect(extraField.status).toBe(400);
  });

  it("fails closed with a sanitized no-store response when configuration is absent", async () => {
    const allow = vi.fn(async () => true);
    const response = await validate(
      testRoute(undefined, { allow }),
      JSON.stringify({ code: newCode() }),
    );

    expect(response.status).toBe(502);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(allow).not.toHaveBeenCalled();
    expect((await response.json()) as ErrorEnvelope).toEqual({
      schemaVersion: 1,
      error: { code: "UPSTREAM_ERROR", message: "Reviewer access is temporarily unavailable" },
    });
  });

  it("fails closed on a rate-limiter timeout and never reaches authorization", async () => {
    const authorize = vi.fn(async () => true);
    const response = await validate(
      testRoute(
        { authorize },
        { allow: () => new Promise<boolean>(() => {}) },
        5,
      ),
      JSON.stringify({ code: newCode() }),
    );

    expect(response.status).toBe(504);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
    expect((await response.json()) as ErrorEnvelope).toMatchObject({
      error: { code: "UPSTREAM_TIMEOUT", message: "Reviewer access validation timed out" },
    });
  });

  it("fails closed on an authorization timeout", async () => {
    const response = await validate(
      testRoute(
        { authorize: () => new Promise<boolean>(() => {}) },
        { allow: async () => true },
        5,
      ),
      JSON.stringify({ code: newCode() }),
    );

    expect(response.status).toBe(504);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
  });

  it("applies one deadline across rate limiting and authorization", async () => {
    vi.useFakeTimers();
    try {
      const authorize = vi.fn(() => new Promise<boolean>((resolve) => {
        setTimeout(() => resolve(true), 60);
      }));
      const allow = vi.fn(() => new Promise<boolean>((resolve) => {
        setTimeout(() => resolve(true), 60);
      }));
      const responsePromise = validate(
        testRoute({ authorize }, { allow }, 100),
        JSON.stringify({ code: newCode() }),
      );

      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(60);
      expect(authorize).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(40);

      const response = await responsePromise;
      expect(response.status).toBe(504);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
    } finally {
      vi.useRealTimers();
    }
  });

  it("cancels a stalled request-body reader when the whole-handler deadline expires", async () => {
    vi.useFakeTimers();
    try {
      const cancel = vi.fn();
      const body = new ReadableStream<Uint8Array>({ cancel });
      const request = new Request("http://localhost/validate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
        duplex: "half",
      } as RequestInit & { duplex: "half" });
      const responsePromise = testRoute(authorizerFor(newCode()), undefined, 100).request(request);

      await vi.advanceTimersByTimeAsync(100);

      const response = await responsePromise;
      expect(response.status).toBe(504);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
      expect(cancel).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("sanitizes dependency failures and never logs or returns the submitted code", async () => {
    const code = newCode();
    const authorize = vi.fn(async () => {
      throw new Error(`internal failure while handling ${code}`);
    });
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      const response = await validate(testRoute({ authorize }), JSON.stringify({ code }));
      const responseText = await response.text();

      expect(response.status).toBe(502);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
      expect(responseText).toContain("Reviewer access is temporarily unavailable");
      expect(responseText).not.toContain(code);
      expect(responseText).not.toContain("internal failure");
      expect(errorLog).not.toHaveBeenCalled();
    } finally {
      errorLog.mockRestore();
    }
  });

  it("returns a no-store 429 and never authorizes after the dedicated quota denies an attempt", async () => {
    const authorize = vi.fn(async () => true);
    const response = await validate(
      testRoute({ authorize }, { allow: async () => false }),
      JSON.stringify({ code: newCode() }),
    );

    expect(response.status).toBe(429);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(authorize).not.toHaveBeenCalled();
  });
});

describe("reviewer access rate limiting", () => {
  it("limits repeated reviewer attempts without affecting another attempt fingerprint", async () => {
    const limiter = new InMemoryReviewerAccessRateLimiter();
    for (let attempt = 0; attempt < REVIEWER_ACCESS_PER_ATTEMPT_LIMIT; attempt += 1) {
      expect(await limiter.allow("same-attempt", "same-client")).toBe(true);
    }
    expect(await limiter.allow("same-attempt", "same-client")).toBe(false);
    expect(await limiter.allow("independent-attempt", "same-client")).toBe(true);
  });

  it("limits unique-code attempts per client without blocking another client", async () => {
    const limiter = new InMemoryReviewerAccessRateLimiter();
    for (let attempt = 0; attempt < REVIEWER_ACCESS_PER_CLIENT_LIMIT; attempt += 1) {
      expect(await limiter.allow(`attempt-${attempt}`, "same-client")).toBe(true);
    }

    expect(await limiter.allow("over-client-limit", "same-client")).toBe(false);
    expect(await limiter.allow("independent-attempt", "independent-client")).toBe(true);
  });

  it("keeps a high emergency global ceiling behind the narrower abuse buckets", async () => {
    const limiter = new InMemoryReviewerAccessRateLimiter();
    for (let attempt = 0; attempt < REVIEWER_ACCESS_EMERGENCY_GLOBAL_LIMIT; attempt += 1) {
      expect(await limiter.allow(`attempt-${attempt}`, `client-${attempt}`)).toBe(true);
    }

    expect(await limiter.allow("over-global-limit", "new-client")).toBe(false);
  });

  it("uses Redis keys wholly separate from purchase-verification counters", async () => {
    const calls: string[][] = [];
    const redis: RedisLike = {
      async get<T>() { return null as T | null; },
      async set<T>() { return "OK" as "OK" | T | null; },
      async eval<TArgs extends unknown[], TData>(_script: string, keys: string[], _args: TArgs) {
        calls.push(keys);
        return 1 as TData;
      },
    };

    await new RedisReviewerAccessRateLimiter(redis).allow(
      "reviewer-fingerprint",
      "client-fingerprint",
    );
    await new RedisBillingRateLimiter(redis).allow("billing-fingerprint");

    expect(calls[0]).toEqual([
      "reviewer-access:validate:v2:attempt:reviewer-fingerprint",
      "reviewer-access:validate:v2:client:client-fingerprint",
      "reviewer-access:validate:v2:global",
    ]);
    expect(calls[1]).toEqual([
      "billing:verify:token:billing-fingerprint",
      "billing:verify:global",
    ]);
    expect(new Set(calls.flat()).size).toBe(5);
  });

  it("does not share in-memory counters with billing verification", async () => {
    const reviewer = new InMemoryReviewerAccessRateLimiter();
    const billing = new InMemoryBillingRateLimiter();
    for (let attempt = 0; attempt < REVIEWER_ACCESS_PER_ATTEMPT_LIMIT; attempt += 1) {
      expect(await reviewer.allow("fingerprint", "client-fingerprint")).toBe(true);
    }

    expect(await reviewer.allow("fingerprint", "client-fingerprint")).toBe(false);
    expect(await billing.allow("fingerprint")).toBe(true);
  });
});

describe("reviewer access app wiring", () => {
  it("mounts the configured route in the real app factory", async () => {
    const code = newCode();
    vi.stubEnv(
      "REVIEWER_ACCESS_CODE_SHA256",
      createHash("sha256").update(code, "utf8").digest("hex"),
    );
    vi.resetModules();
    try {
      const { createApp } = await import("../src/app.js");
      const response = await createApp().request("/api/v1/reviewer-access/validate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code }),
      });

      expect(response.status).toBe(200);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
      expect(await response.json()).toMatchObject({ data: { authorized: true } });
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });

  it("keeps health available when reviewer configuration is missing", async () => {
    vi.stubEnv("REVIEWER_ACCESS_CODE_SHA256", "");
    vi.resetModules();
    try {
      const { createApp } = await import("../src/app.js");
      const app = createApp();
      const reviewerResponse = await app.request("/api/v1/reviewer-access/validate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: newCode() }),
      });
      const healthResponse = await app.request("/api/v1/health");

      expect(reviewerResponse.status).toBe(502);
      expect(reviewerResponse.headers.get("Cache-Control")).toBe("no-store");
      expect(healthResponse.status).toBe(200);
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });
});
