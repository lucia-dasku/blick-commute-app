import { describe, expect, it } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler } from "../src/middleware/errorHandler.js";
import { createDisruptionsRoute } from "../src/routes/disruptions.js";
import { createDeviationsSnapshotService } from "../src/services/deviationsSnapshotService.js";
import { InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import type { SiteDirectory } from "../src/services/siteDirectory.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";
import type { ErrorEnvelope, SuccessEnvelope } from "./testHelpers.js";

interface DisruptionsData {
  fetchedAt: string;
  disruptions: { disruptionId: string }[];
}

/** No site directory entries are needed for these tests — every fixture deviation below
 * is scoped directly to `scope.stop_areas: [{ id: 9192 }]`, matching
 * `resolveSiteStopAreaIds`'s own fallback (siteId alone) when the directory doesn't
 * recognize it (see src/services/deviationsFilter.ts). */
const emptySiteDirectory: SiteDirectory = {
  async search() {
    return [];
  },
  async getAllSites() {
    return [];
  },
};

function buildApp(fakeClient: SlDeviationsClient, siteDirectory: SiteDirectory = emptySiteDirectory) {
  const snapshotService = createDeviationsSnapshotService(fakeClient, new InMemoryCache(), new InMemoryLock());
  const app = new Hono().basePath("/api/v1");
  app.route("/disruptions", createDisruptionsRoute(snapshotService, siteDirectory));
  app.notFound(notFoundHandler);
  app.onError(onError);
  return app;
}

function deviation(overrides: {
  caseId: number;
  siteId?: number;
  lineId?: number;
  transportMode?: string;
  from?: string;
  upto?: string;
}): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: overrides.caseId,
    publish: { from: overrides.from ?? null, upto: overrides.upto ?? null },
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: "h", details: "d", language: "sv" }],
    scope: {
      stop_areas: [{ id: overrides.siteId ?? 9192, name: "Test", type: null }],
      lines:
        overrides.lineId != null || overrides.transportMode != null
          ? [{ id: overrides.lineId ?? 1, designation: "1", transport_mode: overrides.transportMode ?? null, name: null }]
          : [],
    },
  };
}

describe("GET /api/v1/disruptions — 'future' request validation", () => {
  const noopClient: SlDeviationsClient = { async fetchAllDeviations() { return []; } };

  it("defaults to false when 'future' is absent", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192");
    expect(res.status).toBe(200);
  });

  it("accepts 'true'", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&future=true");
    expect(res.status).toBe(200);
  });

  it("accepts 'false'", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&future=false");
    expect(res.status).toBe(200);
  });

  it("rejects an arbitrary string such as 'banana' rather than silently treating it as false", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&future=banana");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("rejects an empty 'future' value", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&future=");
    expect(res.status).toBe(400);
  });

  it("rejects 'True' (wrong case) rather than coercing it", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&future=True");
    expect(res.status).toBe(400);
  });

  it("excludes a not-yet-started deviation when absent, includes it when 'future=true' (local filtering)", async () => {
    const notYetStarted = deviation({ caseId: 1, from: "2999-01-01T00:00:00+01:00" });
    const client: SlDeviationsClient = { async fetchAllDeviations() { return [notYetStarted]; } };

    const app = buildApp(client);
    const withoutFuture = await app.request("/api/v1/disruptions?siteId=9192");
    const withoutFutureBody = (await withoutFuture.json()) as SuccessEnvelope<DisruptionsData>;
    expect(withoutFutureBody.data.disruptions).toHaveLength(0);

    const withFuture = await app.request("/api/v1/disruptions?siteId=9192&future=true");
    const withFutureBody = (await withFuture.json()) as SuccessEnvelope<DisruptionsData>;
    expect(withFutureBody.data.disruptions).toHaveLength(1);
  });
});

describe("GET /api/v1/disruptions — 'transportMode' request validation", () => {
  it("accepts each documented transport mode, and local filtering narrows the response to that mode only", async () => {
    for (const mode of ["BUS", "METRO", "TRAIN", "TRAM", "SHIP", "FERRY", "TAXI"]) {
      const matching = deviation({ caseId: 1, transportMode: mode });
      const other = deviation({ caseId: 2, transportMode: "BUS" === mode ? "METRO" : "BUS" });
      const client: SlDeviationsClient = { async fetchAllDeviations() { return [matching, other]; } };
      const app = buildApp(client);

      const res = await app.request(`/api/v1/disruptions?siteId=9192&transportMode=${mode}`);
      expect(res.status).toBe(200);
      const body = (await res.json()) as SuccessEnvelope<DisruptionsData>;
      expect(body.data.disruptions.map((d) => d.disruptionId)).toEqual(["1"]);
    }
  });

  it("is optional", async () => {
    const client: SlDeviationsClient = { async fetchAllDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192");
    expect(res.status).toBe(200);
  });

  it("rejects an unsupported mode string", async () => {
    const client: SlDeviationsClient = { async fetchAllDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=SUBMARINE");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("rejects an empty transportMode value", async () => {
    const client: SlDeviationsClient = { async fetchAllDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=");
    expect(res.status).toBe(400);
  });

  it("rejects a lowercase mode string rather than case-normalizing it", async () => {
    const client: SlDeviationsClient = { async fetchAllDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=bus");
    expect(res.status).toBe(400);
  });

  it("rejects malformed values containing unexpected characters", async () => {
    const client: SlDeviationsClient = { async fetchAllDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=BUS%3BDROP");
    expect(res.status).toBe(400);
  });
});

describe("GET /api/v1/disruptions — siteId/lineId validation (retained)", () => {
  const noopClient: SlDeviationsClient = { async fetchAllDeviations() { return []; } };

  it("still requires siteId", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions");
    expect(res.status).toBe(400);
  });

  it("still rejects a non-positive siteId", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=0");
    expect(res.status).toBe(400);
  });

  it("still rejects a non-integer lineId", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&lineId=1.5");
    expect(res.status).toBe(400);
  });

  it("still accepts a valid positive-integer lineId", async () => {
    const app = buildApp(noopClient);
    const res = await app.request("/api/v1/disruptions?siteId=9192&lineId=17");
    expect(res.status).toBe(200);
  });

  it("local filtering narrows the response to the requested lineId only", async () => {
    const matching = deviation({ caseId: 1, lineId: 17 });
    const other = deviation({ caseId: 2, lineId: 18 });
    const client: SlDeviationsClient = { async fetchAllDeviations() { return [matching, other]; } };
    const app = buildApp(client);

    const res = await app.request("/api/v1/disruptions?siteId=9192&lineId=17");
    const body = (await res.json()) as SuccessEnvelope<DisruptionsData>;
    expect(body.data.disruptions.map((d) => d.disruptionId)).toEqual(["1"]);
  });
});
