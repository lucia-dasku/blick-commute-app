import { describe, expect, it } from "vitest";
import type { SuccessEnvelope } from "./testHelpers.js";

/**
 * Imports the actual Vercel entry point (`api/index.ts`) — not a re-built copy of
 * `src/app.ts` — and exercises it exactly as Vercel's Node.js runtime would: as a
 * default-exported Hono app whose `.fetch`/`.request` handles an incoming request. This
 * guards against the entry point silently drifting from what `src/app.ts` actually
 * exports (see docs/api-contract.md, "Vercel readiness").
 */
describe("Vercel entry point (api/index.ts)", () => {
  it("exports a Hono app (default export) that responds to GET /api/v1/health", async () => {
    const mod = await import("../api/index.js");
    const app = mod.default;
    expect(app).toBeDefined();
    expect(typeof app.request).toBe("function");

    const res = await app.request("/api/v1/health");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    const body = (await res.json()) as SuccessEnvelope<{ status: string }>;
    expect(body.schemaVersion).toBe(1);
    expect(body.data.status).toBe("ok");
  });

  it("routes GET /api/v1/stops/search through the same app instance", async () => {
    const mod = await import("../api/index.js");
    const app = mod.default;
    // No query -> validation error, but the important thing is the route exists and is
    // reachable through this exact entry point (a 404 here would mean routing is broken).
    const res = await app.request("/api/v1/stops/search");
    expect(res.status).toBe(400);
  });

  it("routes GET /api/v1/departures through the same app instance", async () => {
    const mod = await import("../api/index.js");
    const app = mod.default;
    const res = await app.request("/api/v1/departures");
    expect(res.status).toBe(400); // missing siteId, but reachable (not a 404)
  });

  it("routes GET /api/v1/disruptions through the same app instance", async () => {
    const mod = await import("../api/index.js");
    const app = mod.default;
    const res = await app.request("/api/v1/disruptions");
    expect(res.status).toBe(400); // missing siteId, but reachable (not a 404)
  });

  it("returns a NOT_FOUND envelope for an unmatched path", async () => {
    const mod = await import("../api/index.js");
    const app = mod.default;
    const res = await app.request("/api/v1/does-not-exist");
    expect(res.status).toBe(404);
  });
});
