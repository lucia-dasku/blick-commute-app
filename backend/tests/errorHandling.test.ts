import { describe, expect, it } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler, AppError } from "../src/middleware/errorHandler.js";
import type { ErrorEnvelope } from "./testHelpers.js";

function buildApp(routeHandler: () => never | Promise<never>) {
  const app = new Hono();
  app.get("/boom", async () => {
    return routeHandler();
  });
  app.notFound(notFoundHandler);
  app.onError(onError);
  return app;
}

describe("error handling — no internal leakage", () => {
  it("returns an AppError's own message verbatim for a known error", async () => {
    const app = buildApp(() => {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'siteId' is required");
    });
    const res = await app.request("/boom");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.message).toBe("Query parameter 'siteId' is required");
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("returns only a generic message for an unexpected error, never the real exception text", async () => {
    const app = buildApp(() => {
      throw new TypeError("Cannot read properties of undefined (reading 'foo') at /secret/internal/path.ts:42");
    });
    const res = await app.request("/boom");
    expect(res.status).toBe(500);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("INTERNAL_ERROR");
    expect(body.error.message).toBe("Unexpected internal error");
    expect(body.error.message).not.toContain("secret");
    expect(body.error.message).not.toContain("TypeError");
    expect(JSON.stringify(body)).not.toContain("/secret/internal/path.ts");
  });

  it("never includes a stack trace in the response body for an unexpected error", async () => {
    const app = buildApp(() => {
      throw new Error("boom with a stack");
    });
    const res = await app.request("/boom");
    const raw = await res.text();
    expect(raw).not.toContain(".ts:");
    expect(raw).not.toMatch(/at\s+\S+\s+\(/); // no "at functionName (file:line:col)" stack frames
  });

  it("never leaks an AppError's attached cause (e.g. an upstream URL) into the response body", async () => {
    const app = buildApp(() => {
      throw new AppError("UPSTREAM_ERROR", "SL Transport returned an error response", {
        cause: { url: "https://transport.integration.sl.se/v1/sites/9192/departures", status: 500 },
      });
    });
    const res = await app.request("/boom");
    const raw = await res.text();
    expect(raw).not.toContain("transport.integration.sl.se");
  });

  it("sets Cache-Control: no-store on a known-error response", async () => {
    const app = buildApp(() => {
      throw new AppError("VALIDATION_ERROR", "bad request");
    });
    const res = await app.request("/boom");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
  });

  it("sets Cache-Control: no-store on an unexpected-error response", async () => {
    const app = buildApp(() => {
      throw new Error("unexpected");
    });
    const res = await app.request("/boom");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
  });

  it("sets Cache-Control: no-store on a 404 (not-found) response", async () => {
    const app = new Hono();
    app.notFound(notFoundHandler);
    app.onError(onError);
    const res = await app.request("/does-not-exist");
    expect(res.status).toBe(404);
    expect(res.headers.get("Cache-Control")).toBe("no-store");
  });

  it("forwards Retry-After on an UPSTREAM_RATE_LIMITED error", async () => {
    const app = buildApp(() => {
      throw new AppError("UPSTREAM_RATE_LIMITED", "SL Deviations rate-limited this request", {
        retryAfter: "17",
      });
    });
    const res = await app.request("/boom");
    expect(res.status).toBe(503);
    expect(res.headers.get("Retry-After")).toBe("17");
  });
});
