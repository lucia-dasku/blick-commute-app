import { describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { fetchUpstreamJson } from "../src/lib/upstreamFetch.js";
import { AppError, isAppError } from "../src/lib/errors.js";

const TestSchema = z.object({ ok: z.boolean() });

async function expectAppError(promise: Promise<unknown>): Promise<AppError> {
  try {
    await promise;
  } catch (err) {
    if (isAppError(err)) return err;
    throw err;
  }
  throw new Error("expected the promise to reject with an AppError, but it resolved");
}

describe("fetchUpstreamJson — networking behaviour", () => {
  it("resolves normally when the response is ok and schema-valid", async () => {
    const fakeResponse = {
      status: 200,
      ok: true,
      headers: new Headers(),
      json: async () => ({ ok: true }),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const result = await fetchUpstreamJson("https://example.invalid/x", TestSchema, {
        upstreamName: "Test Upstream",
      });
      expect(result).toEqual({ ok: true });
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("maps an actual timeout (abort) to UPSTREAM_TIMEOUT (504), not a generic failure", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        return new Promise((_resolve, reject) => {
          const signal = init.signal as AbortSignal;
          signal.addEventListener("abort", () => {
            const err = new DOMException("The operation was aborted", "AbortError");
            reject(err);
          });
        });
      }),
    );
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, {
          upstreamName: "Test Upstream",
          timeoutMs: 10,
        }),
      );
      expect(err.code).toBe("UPSTREAM_TIMEOUT");
      expect(err.httpStatus).toBe(504);
      // The message must never contain the upstream URL.
      expect(err.message).not.toContain("example.invalid");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("does NOT classify an ordinary (non-abort) network failure as a timeout", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("fetch failed: getaddrinfo ENOTFOUND")),
    );
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.code).toBe("UPSTREAM_ERROR");
      expect(err.httpStatus).toBe(502);
      expect(err.message).not.toContain("example.invalid");
      expect(err.message).not.toContain("ENOTFOUND");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("maps HTTP 429 to UPSTREAM_RATE_LIMITED (503) and preserves a valid (delay-seconds) Retry-After header", async () => {
    const fakeResponse = {
      status: 429,
      ok: false,
      headers: new Headers({ "Retry-After": "42" }),
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.code).toBe("UPSTREAM_RATE_LIMITED");
      expect(err.httpStatus).toBe(503);
      expect(err.retryAfter).toBe("42");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("preserves a valid HTTP-date Retry-After header", async () => {
    const fakeResponse = {
      status: 429,
      ok: false,
      headers: new Headers({ "Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT" }),
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.retryAfter).toBe("Wed, 21 Oct 2026 07:28:00 GMT");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("omits an invalid Retry-After header (negative number) rather than forwarding it", async () => {
    const fakeResponse = {
      status: 429,
      ok: false,
      headers: new Headers({ "Retry-After": "-30" }),
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.code).toBe("UPSTREAM_RATE_LIMITED");
      expect(err.retryAfter).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("omits an invalid Retry-After header (garbage text) rather than forwarding it", async () => {
    const fakeResponse = {
      status: 429,
      ok: false,
      headers: new Headers({ "Retry-After": "not-a-valid-value" }),
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.retryAfter).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("omits Retry-After entirely when the header is absent", async () => {
    const fakeResponse = {
      status: 429,
      ok: false,
      headers: new Headers(),
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.retryAfter).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("maps a non-2xx, non-429 status to a sanitized UPSTREAM_ERROR (502) without leaking the response body", async () => {
    const fakeResponse = {
      status: 500,
      ok: false,
      headers: new Headers(),
      json: async () => ({ internalDebugInfo: "super secret stack trace" }),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.code).toBe("UPSTREAM_ERROR");
      expect(err.httpStatus).toBe(502);
      expect(err.message).not.toContain("super secret stack trace");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("maps a response body that is not valid JSON to UPSTREAM_ERROR (502)", async () => {
    const fakeResponse = {
      status: 200,
      ok: true,
      headers: new Headers(),
      json: async () => {
        throw new SyntaxError("Unexpected token in JSON");
      },
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.code).toBe("UPSTREAM_ERROR");
      expect(err.httpStatus).toBe(502);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("maps schema-invalid (but valid JSON) data to UPSTREAM_ERROR (502) without leaking the raw body", async () => {
    const fakeResponse = {
      status: 200,
      ok: true,
      headers: new Headers(),
      json: async () => ({ ok: "not-a-boolean", secretUpstreamDetail: "leak me not" }),
    } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fakeResponse));
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, { upstreamName: "Test Upstream" }),
      );
      expect(err.code).toBe("UPSTREAM_ERROR");
      expect(err.httpStatus).toBe(502);
      expect(err.message).not.toContain("leak me not");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("aborts a stalled response body read once the timeout elapses (headers arrive, but the body then stalls), reporting UPSTREAM_TIMEOUT — not a generic parse error and not a hang", async () => {
    let capturedSignal: AbortSignal | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        capturedSignal = init.signal as AbortSignal;
        const fakeResponse = {
          status: 200,
          ok: true,
          headers: new Headers(),
          // Headers "arrive" immediately (this resolves right away), but reading the
          // body stalls forever UNLESS the same signal that governed the original
          // fetch() call aborts. If the timeout only covered waiting for headers (the
          // bug being regression-tested here), this would hang indefinitely instead of
          // ever settling.
          json: () =>
            new Promise((_resolve, reject) => {
              capturedSignal!.addEventListener("abort", () => {
                reject(new DOMException("The operation was aborted", "AbortError"));
              });
            }),
        } as unknown as Response;
        return Promise.resolve(fakeResponse);
      }),
    );
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, {
          upstreamName: "Test Upstream",
          timeoutMs: 15,
        }),
      );
      expect(err.code).toBe("UPSTREAM_TIMEOUT");
      expect(err.httpStatus).toBe(504);
      expect(err.message).toContain("response body");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("still maps ordinary malformed JSON (not a timeout) to UPSTREAM_ERROR even with the extended timeout window", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        status: 200,
        ok: true,
        headers: new Headers(),
        json: async () => {
          throw new SyntaxError("Unexpected end of JSON input");
        },
      } as unknown as Response),
    );
    try {
      const err = await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, {
          upstreamName: "Test Upstream",
          timeoutMs: 5000,
        }),
      );
      expect(err.code).toBe("UPSTREAM_ERROR");
      expect(err.httpStatus).toBe(502);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("honors a configurable timeout rather than a hardcoded value", async () => {
    let observedSignal: AbortSignal | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        observedSignal = init.signal as AbortSignal;
        return new Promise((_resolve, reject) => {
          observedSignal!.addEventListener("abort", () => {
            reject(new DOMException("aborted", "AbortError"));
          });
        });
      }),
    );
    try {
      const start = Date.now();
      await expectAppError(
        fetchUpstreamJson("https://example.invalid/x", TestSchema, {
          upstreamName: "Test Upstream",
          timeoutMs: 25,
        }),
      );
      const elapsed = Date.now() - start;
      // Should have aborted close to the configured 25ms, not the 10s default.
      expect(elapsed).toBeLessThan(2000);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
