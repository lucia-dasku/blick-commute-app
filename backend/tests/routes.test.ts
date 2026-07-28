import { describe, expect, it } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler } from "../src/middleware/errorHandler.js";
import { healthRoute } from "../src/routes/health.js";
import { createStopsRoute } from "../src/routes/stops.js";
import { createDeparturesRoute } from "../src/routes/departures.js";
import { createDisruptionsRoute } from "../src/routes/disruptions.js";
import { createSiteDirectory } from "../src/services/siteDirectory.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import type { SlTransportClient } from "../src/services/slTransportClient.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import departuresFixture from "../fixtures/slTransportDeparturesSlussen.sample.json" with { type: "json" };
import sitesFixture from "../fixtures/slSites.sample.json" with { type: "json" };
import type { RawDeparturesResponse, RawSlSite } from "../src/services/upstreamTypes.js";
import { AppError } from "../src/lib/errors.js";
import type { ErrorEnvelope, SuccessEnvelope } from "./testHelpers.js";
import { createSlDeviationsClient } from "../src/services/slDeviationsClient.js";
import { vi } from "vitest";

function buildTestApp(options?: { departuresShouldFail?: boolean }) {
  const cache = new InMemoryCache();
  const deduper = new InFlightDeduper();

  const fakeTransportClient: SlTransportClient = {
    async fetchAllSites() {
      return sitesFixture as unknown as RawSlSite[];
    },
    async fetchDepartures() {
      if (options?.departuresShouldFail) {
        throw new AppError("UPSTREAM_ERROR", "SL Transport returned HTTP 500 for test");
      }
      return departuresFixture as unknown as RawDeparturesResponse;
    },
  };

  const fakeDeviationsClient: SlDeviationsClient = {
    async fetchDeviations() {
      return [];
    },
  };

  const siteDirectory = createSiteDirectory(fakeTransportClient, cache, deduper);

  const app = new Hono().basePath("/api/v1");
  app.route("/health", healthRoute);
  app.route("/stops", createStopsRoute(siteDirectory));
  app.route("/departures", createDeparturesRoute(fakeTransportClient));
  app.route("/disruptions", createDisruptionsRoute(fakeDeviationsClient, cache, deduper));
  app.notFound(notFoundHandler);
  app.onError(onError);

  return app;
}

describe("GET /api/v1/health", () => {
  it("returns schemaVersion, ok status, and no-store cache header", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/health");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    const body = (await res.json()) as SuccessEnvelope<{ status: string }>;
    expect(body).toMatchObject({ schemaVersion: 1, data: { status: "ok" } });
  });
});

describe("GET /api/v1/stops/search", () => {
  it("returns a validation error envelope when query is missing", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/stops/search");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body).toEqual({ schemaVersion: 1, error: { code: "VALIDATION_ERROR", message: expect.any(String) } });
  });

  it("returns a validation error envelope for an empty query", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/stops/search?query=%20%20");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("returns ranked sites with the long-cache Cache-Control header", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/stops/search?query=Slussen");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("public, s-maxage=3600, stale-while-revalidate=86400");
    const body = (await res.json()) as SuccessEnvelope<{ sites: Array<{ siteId: number }> }>;
    expect(body.schemaVersion).toBe(1);
    expect(body.data.sites.some((s) => s.siteId === 9192)).toBe(true);
  });
});

describe("GET /api/v1/departures", () => {
  it("returns a validation error envelope when siteId is missing", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/departures");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("returns a validation error envelope when siteId is not a positive integer", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/departures?siteId=not-a-number");
    expect(res.status).toBe(400);
  });

  it("returns normalized departures with the 30s Cache-Control header on success", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/departures?siteId=9192");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("public, s-maxage=30, stale-while-revalidate=30");
    const body = (await res.json()) as SuccessEnvelope<{ timeZone: string; departures: unknown[] }>;
    expect(body.data.timeZone).toBe("Europe/Stockholm");
    expect(body.data.departures.length).toBe(3);
  });

  it("translates an upstream failure into an UPSTREAM_ERROR envelope with the correct HTTP status", async () => {
    const app = buildTestApp({ departuresShouldFail: true });
    const res = await app.request("/api/v1/departures?siteId=9192");
    expect(res.status).toBe(502);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body).toEqual({ schemaVersion: 1, error: { code: "UPSTREAM_ERROR", message: expect.any(String) } });
  });
});

describe("GET /api/v1/disruptions", () => {
  it("returns the 60s+ Cache-Control header required by SL's fair-use guidance", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/disruptions?siteId=9192");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("public, s-maxage=60, stale-while-revalidate=60");
  });

  it("rejects a non-integer lineId", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/disruptions?siteId=9192&lineId=abc");
    expect(res.status).toBe(400);
  });

  it("requires siteId (not optional)", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/disruptions");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("requires siteId even when other filters are present", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/disruptions?lineId=17&transportMode=METRO");
    expect(res.status).toBe(400);
  });
});

describe("unmatched routes", () => {
  it("returns a NOT_FOUND error envelope", async () => {
    const app = buildTestApp();
    const res = await app.request("/api/v1/does-not-exist");
    expect(res.status).toBe(404);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("NOT_FOUND");
  });
});

describe("GET /api/v1/disruptions — malformed upstream data", () => {
  it("returns a sanitized UPSTREAM_ERROR/502, never an unexpected 500, when SL Deviations " +
    "returns data that fails schema validation (e.g. an empty message_variants array)", async () => {
    const malformedPayload = [
      {
        version: 1,
        created: "2026-07-27T20:12:47.15+02:00",
        modified: null,
        deviation_case_id: 1,
        priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
        message_variants: [], // invalid: must have at least one variant
        scope: {},
      },
    ];

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        status: 200,
        ok: true,
        headers: new Headers(),
        json: async () => malformedPayload,
      } as unknown as Response),
    );

    try {
      const realDeviationsClient = createSlDeviationsClient("https://example.invalid/v1");
      const cache = new InMemoryCache();
      const deduper = new InFlightDeduper();
      const app = new Hono().basePath("/api/v1");
      app.route("/disruptions", createDisruptionsRoute(realDeviationsClient, cache, deduper));
      app.notFound(notFoundHandler);
      app.onError(onError);

      const res = await app.request("/api/v1/disruptions?siteId=9192");
      expect(res.status).toBe(502);
      const body = (await res.json()) as ErrorEnvelope;
      expect(body.error.code).toBe("UPSTREAM_ERROR");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("also returns UPSTREAM_ERROR/502 for a naive (offset-less) created timestamp", async () => {
    const malformedPayload = [
      {
        version: 1,
        created: "2026-07-27T20:12:47", // invalid: no explicit UTC offset
        modified: null,
        deviation_case_id: 1,
        priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
        message_variants: [{ header: "h", details: "d", language: "sv" }],
        scope: {},
      },
    ];

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        status: 200,
        ok: true,
        headers: new Headers(),
        json: async () => malformedPayload,
      } as unknown as Response),
    );

    try {
      const realDeviationsClient = createSlDeviationsClient("https://example.invalid/v1");
      const cache = new InMemoryCache();
      const deduper = new InFlightDeduper();
      const app = new Hono().basePath("/api/v1");
      app.route("/disruptions", createDisruptionsRoute(realDeviationsClient, cache, deduper));
      app.notFound(notFoundHandler);
      app.onError(onError);

      const res = await app.request("/api/v1/disruptions?siteId=9192");
      expect(res.status).toBe(502);
      const body = (await res.json()) as ErrorEnvelope;
      expect(body.error.code).toBe("UPSTREAM_ERROR");
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
