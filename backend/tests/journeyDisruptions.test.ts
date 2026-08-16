import { describe, expect, it, vi } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler } from "../src/middleware/errorHandler.js";
import { createJourneyDisruptionsRoute } from "../src/routes/journeyDisruptions.js";
import { createDeviationsSnapshotService } from "../src/services/deviationsSnapshotService.js";
import { InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import type { SiteDirectory } from "../src/services/siteDirectory.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";
import type { Site } from "../src/models/site.js";
import type { ErrorEnvelope, SuccessEnvelope } from "./testHelpers.js";
import type { ResolvedJourneyDisruption } from "../src/domain/disruptionRelevance.js";

function deviation(overrides: {
  id?: number;
  header?: string;
  details?: string;
  lines?: Array<{ designation: string; transportMode: string | null }>;
  stopAreaIds?: number[];
}): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: overrides.id ?? 1,
    publish: { from: null, upto: null },
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: overrides.header ?? "h", details: overrides.details ?? "d", language: "sv" }],
    scope: {
      stop_areas: overrides.stopAreaIds?.map((id) => ({ id, name: "Test", type: null })),
      lines: overrides.lines?.map((l, i) => ({ id: i + 1, designation: l.designation, transport_mode: l.transportMode, name: null })),
    },
  };
}

function buildTestApp(deviations: RawDeviation[], sites: Site[] = []) {
  const fetchAllDeviations = vi.fn(async () => deviations);
  const fakeDeviationsClient: SlDeviationsClient = { fetchAllDeviations };
  const fakeSiteDirectory: SiteDirectory = {
    async search() {
      return [];
    },
    async getAllSites() {
      return sites;
    },
  };
  const snapshotService = createDeviationsSnapshotService(fakeDeviationsClient, new InMemoryCache(), new InMemoryLock());

  const app = new Hono().basePath("/api/v1");
  app.route("/journeys/disruptions", createJourneyDisruptionsRoute(snapshotService, fakeSiteDirectory));
  app.notFound(notFoundHandler);
  app.onError(onError);

  return { app, fetchAllDeviations };
}

function post(app: Hono, body: unknown) {
  return app.request("/api/v1/journeys/disruptions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

const AKALLA_NO_SERVICE = deviation({
  id: 9001,
  header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
  details: "På grund av ett tekniskt fel är trafiken på Blå linjen inställd mellan T-Centralen och Kungsträdgården.",
  lines: [
    { designation: "10", transportMode: "METRO" },
    { designation: "11", transportMode: "METRO" },
  ],
  stopAreaIds: [],
});

describe("POST /api/v1/journeys/disruptions — the confirmed Akalla case", () => {
  it("returns the NO_SERVICE deviation as LINE_RELEVANT for a Metro 11 leg with no stop scope evidence", async () => {
    const { app } = buildTestApp([AKALLA_NO_SERVICE]);
    const res = await post(app, { legs: [{ transportMode: "METRO", lineDesignation: "11" }], journeyPlannerNotices: [] });
    expect(res.status).toBe(200);
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
    const d = body.data.disruptions[0]!;
    expect(d.relevance).toBe("LINE_RELEVANT");
    expect(d.effect).toBe("NO_SERVICE");
    expect(d.matchedLineDesignations).toEqual(["11"]);
    expect(d.headline).toBe("Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården");
    expect(d.details).toBe("På grund av ett tekniskt fel är trafiken på Blå linjen inställd mellan T-Centralen och Kungsträdgården.");
  });

  it("still surfaces the disruption even though no structural proof of segment intersection exists -- never silently dropped", async () => {
    const { app } = buildTestApp([AKALLA_NO_SERVICE]);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      originSiteId: 9192,
      journeyPlannerNotices: [],
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
    expect(body.data.disruptions[0]!.relevance).toBe("LINE_RELEVANT");
  });
});

describe("POST /api/v1/journeys/disruptions — Akalla -> T-Centralen does not falsely become CONFIRMED/NO_SERVICE", () => {
  it("a stop-scoped closure elsewhere on the same line stays LINE_RELEVANT, not silently dropped, with only the origin verified", async () => {
    // Blick only has a verified stop-area mapping for the routine's own ORIGIN (Akalla) --
    // never the destination or any intermediate stop. That the origin doesn't intersect this
    // deviation's own stop scope [9001] does NOT prove the deviation is unrelated to the whole
    // journey (T-Centralen/Kungsträdgården, further down the route, might still be affected) --
    // so this must resolve LINE_RELEVANT, a conservative warning, never CONFIRMED/NO_SERVICE and
    // never silently dropped to nothing.
    const stopScopedClosure = deviation({
      id: 9002,
      header: "Inställd trafik mellan T-Centralen och Kungsträdgården",
      lines: [{ designation: "11", transportMode: "METRO" }],
      stopAreaIds: [9001], // T-Centralen, not Akalla
    });
    const sites: Site[] = [{ siteId: 9192, name: "Akalla", note: null, lat: null, lon: null, stopAreaIds: [] }];
    const { app } = buildTestApp([stopScopedClosure], sites);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      originSiteId: 9192, // Akalla -- does not intersect [9001], but that is not a disproof
      journeyPlannerNotices: [],
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
    const d = body.data.disruptions[0]!;
    expect(d.relevance).toBe("LINE_RELEVANT");
    expect(d.matchedLineDesignations).toEqual(["11"]);
    expect(d.headline).toBe("Inställd trafik mellan T-Centralen och Kungsträdgården");
  });
});

describe("POST /api/v1/journeys/disruptions — Slussen -> Liljeholmen stays disruption-free", () => {
  it("returns no disruptions for a Metro 13/14 journey when the only current disruption is an unrelated Bus 401 delay", async () => {
    const bus401 = deviation({ id: 9003, lines: [{ designation: "401", transportMode: "BUS" }], stopAreaIds: [9192] });
    const { app } = buildTestApp([bus401]);
    const res = await post(app, {
      legs: [
        { transportMode: "METRO", lineDesignation: "13" },
        { transportMode: "METRO", lineDesignation: "14" },
      ],
      originSiteId: 9192,
      journeyPlannerNotices: [],
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toEqual([]);
  });
});

describe("POST /api/v1/journeys/disruptions — stop-scoped CONFIRMED matching", () => {
  it("resolves CONFIRMED when the supplied originSiteId resolves into the deviation's own stop scope", async () => {
    const stopScoped = deviation({ id: 9004, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [9192] });
    const sites: Site[] = [{ siteId: 9192, name: "Akalla", note: null, lat: null, lon: null, stopAreaIds: [] }];
    const { app } = buildTestApp([stopScoped], sites);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      originSiteId: 9192,
      journeyPlannerNotices: [],
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions[0]!.relevance).toBe("CONFIRMED");
  });
});

describe("POST /api/v1/journeys/disruptions — Journey Planner + Deviations combination", () => {
  it("merges a matching Journey Planner notice with the richer Deviations copy, upgraded to CONFIRMED", async () => {
    const { app } = buildTestApp([AKALLA_NO_SERVICE]);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      journeyPlannerNotices: [
        { text: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården", effect: "NO_SERVICE" },
      ],
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
    const d = body.data.disruptions[0]!;
    expect(d.relevance).toBe("CONFIRMED");
    expect(d.source).toBe("SL_DEVIATIONS");
    expect(d.id).toBe("9001");
    expect(d.details).not.toBeNull();
  });

  it("preserves a Journey Planner notice with no matching Deviation, unchanged", async () => {
    const { app } = buildTestApp([]);
    const res = await post(app, {
      legs: [],
      journeyPlannerNotices: [{ text: "Replacement bus in effect", effect: "REPLACEMENT_SERVICE" }],
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toEqual([
      { headline: "Replacement bus in effect", effect: "REPLACEMENT_SERVICE", relevance: "CONFIRMED", source: "JOURNEY_PLANNER", matchedLineDesignations: [] },
    ]);
  });
});

describe("POST /api/v1/journeys/disruptions — validation", () => {
  it("rejects a request with no legs field at all", async () => {
    const { app } = buildTestApp([]);
    const res = await post(app, { journeyPlannerNotices: [] });
    expect(res.status).toBe(400);
    const body = (await res.json()) as ErrorEnvelope;
    expect(body.error.code).toBe("VALIDATION_ERROR");
  });

  it("accepts an empty legs array (nothing to match against)", async () => {
    const { app } = buildTestApp([]);
    const res = await post(app, { legs: [], journeyPlannerNotices: [] });
    expect(res.status).toBe(200);
  });

  it("rejects a non-positive-integer originSiteId", async () => {
    const { app } = buildTestApp([]);
    const res = await post(app, { legs: [], originSiteId: -1, journeyPlannerNotices: [] });
    expect(res.status).toBe(400);
  });

  it("rejects an invalid JSON body", async () => {
    const { app } = buildTestApp([]);
    const res = await app.request("/api/v1/journeys/disruptions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{not json",
    });
    expect(res.status).toBe(400);
  });
});

describe("POST /api/v1/journeys/disruptions — cache reuse (no extra upstream SL Deviations traffic)", () => {
  it("serves multiple requests, for different journeys, from the same cached snapshot without a second upstream fetch", async () => {
    const { app, fetchAllDeviations } = buildTestApp([AKALLA_NO_SERVICE]);
    await post(app, { legs: [{ transportMode: "METRO", lineDesignation: "11" }], journeyPlannerNotices: [] });
    await post(app, {
      legs: [
        { transportMode: "METRO", lineDesignation: "13" },
        { transportMode: "METRO", lineDesignation: "14" },
      ],
      journeyPlannerNotices: [],
    });
    await post(app, { legs: [{ transportMode: "BUS", lineDesignation: "401" }], journeyPlannerNotices: [] });
    expect(fetchAllDeviations).toHaveBeenCalledTimes(1);
  });
});
