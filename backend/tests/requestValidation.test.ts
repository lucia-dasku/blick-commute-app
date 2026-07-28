import { describe, expect, it } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler } from "../src/middleware/errorHandler.js";
import { createDisruptionsRoute } from "../src/routes/disruptions.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import type { ErrorEnvelope } from "./testHelpers.js";

function buildApp(fakeClient: SlDeviationsClient) {
  const app = new Hono().basePath("/api/v1");
  app.route("/disruptions", createDisruptionsRoute(fakeClient, new InMemoryCache(), new InFlightDeduper()));
  app.notFound(notFoundHandler);
  app.onError(onError);
  return app;
}

describe("GET /api/v1/disruptions — 'future' request validation", () => {
  const noopClient: SlDeviationsClient = { async fetchDeviations() { return []; } };

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
});

describe("GET /api/v1/disruptions — 'transportMode' request validation", () => {
  it("accepts each documented transport mode", async () => {
    for (const mode of ["BUS", "METRO", "TRAIN", "TRAM", "SHIP", "FERRY", "TAXI"]) {
      let receivedMode: string | undefined;
      const client: SlDeviationsClient = {
        async fetchDeviations(query) {
          receivedMode = query.transportMode;
          return [];
        },
      };
      const app = buildApp(client);
      const res = await app.request(`/api/v1/disruptions?siteId=9192&transportMode=${mode}`);
      expect(res.status).toBe(200);
      expect(receivedMode).toBe(mode);
    }
  });

  it("is optional", async () => {
    const client: SlDeviationsClient = { async fetchDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192");
    expect(res.status).toBe(200);
  });

  it("rejects an unsupported mode string", async () => {
    const client: SlDeviationsClient = { async fetchDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=SUBMARINE");
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("rejects an empty transportMode value", async () => {
    const client: SlDeviationsClient = { async fetchDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=");
    expect(res.status).toBe(400);
  });

  it("rejects a lowercase mode string rather than case-normalizing it", async () => {
    const client: SlDeviationsClient = { async fetchDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=bus");
    expect(res.status).toBe(400);
  });

  it("rejects malformed values containing unexpected characters", async () => {
    const client: SlDeviationsClient = { async fetchDeviations() { return []; } };
    const app = buildApp(client);
    const res = await app.request("/api/v1/disruptions?siteId=9192&transportMode=BUS%3BDROP");
    expect(res.status).toBe(400);
  });
});

describe("GET /api/v1/disruptions — siteId/lineId validation (retained)", () => {
  const noopClient: SlDeviationsClient = { async fetchDeviations() { return []; } };

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
});
