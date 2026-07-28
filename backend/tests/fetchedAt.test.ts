import { describe, expect, it, vi } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler } from "../src/middleware/errorHandler.js";
import { createDeparturesRoute } from "../src/routes/departures.js";
import { createDisruptionsRoute } from "../src/routes/disruptions.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import type { SlTransportClient } from "../src/services/slTransportClient.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import departuresFixture from "../fixtures/slTransportDeparturesSlussen.sample.json" with { type: "json" };
import type { RawDeparturesResponse } from "../src/services/upstreamTypes.js";
import type { SuccessEnvelope } from "./testHelpers.js";

interface DeparturesData {
  fetchedAt: string;
}
interface DisruptionsData {
  fetchedAt: string;
  disruptions: unknown[];
}

/**
 * These tests exist specifically to regression-test the "fetchedAt semantics" fix:
 * fetchedAt must be captured after the upstream response is received (never before the
 * request starts), a cached response must retain its ORIGINAL fetch time (never a
 * freshly generated one), and concurrent/deduplicated requests must share one fetchedAt.
 */

describe("GET /api/v1/departures — fetchedAt semantics", () => {
  it("captures fetchedAt only after the upstream response has been received, not when the request started", async () => {
    vi.useFakeTimers();
    try {
      const start = new Date("2026-07-04T15:00:00.000Z");
      vi.setSystemTime(start);

      const fakeClient: SlTransportClient = {
        async fetchAllSites() {
          return [];
        },
        async fetchDepartures() {
          // Simulate the upstream taking 5 seconds to respond.
          await new Promise<void>((resolve) => setTimeout(resolve, 5000));
          return departuresFixture as unknown as RawDeparturesResponse;
        },
      };

      const app = new Hono().basePath("/api/v1");
      app.route("/departures", createDeparturesRoute(fakeClient));
      app.notFound(notFoundHandler);
      app.onError(onError);

      const requestPromise = app.request("/api/v1/departures?siteId=9192");
      await vi.advanceTimersByTimeAsync(5000);
      const res = await requestPromise;

      expect(res.status).toBe(200);
      const body = (await res.json()) as SuccessEnvelope<DeparturesData>;
      const expectedFetchedAt = new Date(start.getTime() + 5000).toISOString();
      expect(body.data.fetchedAt).toBe(expectedFetchedAt);
      // Specifically NOT the time the request started.
      expect(body.data.fetchedAt).not.toBe(start.toISOString());
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("GET /api/v1/disruptions — fetchedAt semantics", () => {
  function buildApp(fakeClient: SlDeviationsClient, cache = new InMemoryCache(), deduper = new InFlightDeduper()) {
    const app = new Hono().basePath("/api/v1");
    app.route("/disruptions", createDisruptionsRoute(fakeClient, cache, deduper));
    app.notFound(notFoundHandler);
    app.onError(onError);
    return app;
  }

  it("uses a fresh fetchedAt on a cache miss", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-04T15:00:00.000Z"));
      let callCount = 0;
      const fakeClient: SlDeviationsClient = {
        async fetchDeviations() {
          callCount += 1;
          return [];
        },
      };
      const app = buildApp(fakeClient);
      const res = await app.request("/api/v1/disruptions?siteId=9192");
      const body = (await res.json()) as SuccessEnvelope<DisruptionsData>;

      expect(callCount).toBe(1);
      expect(body.data.fetchedAt).toBe("2026-07-04T15:00:00.000Z");
    } finally {
      vi.useRealTimers();
    }
  });

  it("does NOT generate a new fetchedAt when serving a cached response", async () => {
    vi.useFakeTimers();
    try {
      const cache = new InMemoryCache();
      const deduper = new InFlightDeduper();
      let callCount = 0;
      const fakeClient: SlDeviationsClient = {
        async fetchDeviations() {
          callCount += 1;
          return [];
        },
      };

      vi.setSystemTime(new Date("2026-07-04T15:00:00.000Z"));
      const app1 = buildApp(fakeClient, cache, deduper);
      const first = await app1.request("/api/v1/disruptions?siteId=9192");
      const firstBody = (await first.json()) as SuccessEnvelope<DisruptionsData>;

      // Advance real clock time forward (but stay within the 60s cache TTL) and issue a
      // second, separate request against the same cache.
      vi.setSystemTime(new Date("2026-07-04T15:00:30.000Z"));
      const app2 = buildApp(fakeClient, cache, deduper);
      const second = await app2.request("/api/v1/disruptions?siteId=9192");
      const secondBody = (await second.json()) as SuccessEnvelope<DisruptionsData>;

      expect(callCount).toBe(1); // upstream was not called again
      expect(secondBody.data.fetchedAt).toBe(firstBody.data.fetchedAt); // original time preserved
      expect(secondBody.data.fetchedAt).toBe("2026-07-04T15:00:00.000Z");
    } finally {
      vi.useRealTimers();
    }
  });

  it("fetches again and produces a new fetchedAt once the cache TTL has elapsed", async () => {
    vi.useFakeTimers();
    try {
      const cache = new InMemoryCache();
      const deduper = new InFlightDeduper();
      let callCount = 0;
      const fakeClient: SlDeviationsClient = {
        async fetchDeviations() {
          callCount += 1;
          return [];
        },
      };

      vi.setSystemTime(new Date("2026-07-04T15:00:00.000Z"));
      const app1 = buildApp(fakeClient, cache, deduper);
      const first = await app1.request("/api/v1/disruptions?siteId=9192");
      const firstBody = (await first.json()) as SuccessEnvelope<DisruptionsData>;

      // 61 seconds later: past the 60s TTL floor.
      vi.setSystemTime(new Date("2026-07-04T15:01:01.000Z"));
      const app2 = buildApp(fakeClient, cache, deduper);
      const second = await app2.request("/api/v1/disruptions?siteId=9192");
      const secondBody = (await second.json()) as SuccessEnvelope<DisruptionsData>;

      expect(callCount).toBe(2);
      expect(secondBody.data.fetchedAt).not.toBe(firstBody.data.fetchedAt);
      expect(secondBody.data.fetchedAt).toBe("2026-07-04T15:01:01.000Z");
    } finally {
      vi.useRealTimers();
    }
  });

  it("makes exactly one upstream call for simultaneous identical requests, and both responses share the same fetchedAt", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-04T15:00:00.000Z"));
      let callCount = 0;
      const fakeClient: SlDeviationsClient = {
        async fetchDeviations() {
          callCount += 1;
          // Simulate network latency so the two concurrent requests genuinely overlap.
          await new Promise<void>((resolve) => setTimeout(resolve, 200));
          return [];
        },
      };
      const cache = new InMemoryCache();
      const deduper = new InFlightDeduper();
      const app = buildApp(fakeClient, cache, deduper);

      const requestA = app.request("/api/v1/disruptions?siteId=9192");
      const requestB = app.request("/api/v1/disruptions?siteId=9192");
      await vi.advanceTimersByTimeAsync(200);
      const [resA, resB] = await Promise.all([requestA, requestB]);

      const bodyA = (await resA.json()) as SuccessEnvelope<DisruptionsData>;
      const bodyB = (await resB.json()) as SuccessEnvelope<DisruptionsData>;

      expect(callCount).toBe(1);
      expect(bodyA.data.fetchedAt).toBe(bodyB.data.fetchedAt);
      expect(bodyA.data.fetchedAt).toBe("2026-07-04T15:00:00.200Z");
    } finally {
      vi.useRealTimers();
    }
  });
});
