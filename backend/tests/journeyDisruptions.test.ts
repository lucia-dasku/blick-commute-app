import { describe, expect, it, vi } from "vitest";
import { Hono } from "hono";
import { onError, notFoundHandler } from "../src/middleware/errorHandler.js";
import { createJourneyDisruptionsRoute } from "../src/routes/journeyDisruptions.js";
import { createDeviationsSnapshotService } from "../src/services/deviationsSnapshotService.js";
import { InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import { AppError } from "../src/lib/errors.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import type { SiteDirectory } from "../src/services/siteDirectory.js";
import type { PatternPointGid, StopPointDirectory, StopPointResolution } from "../src/services/stopPointDirectory.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";
import type { Site } from "../src/models/site.js";
import type { ErrorEnvelope, SuccessEnvelope } from "./testHelpers.js";
import type { ResolvedJourneyDisruption } from "../src/domain/disruptionRelevance.js";
import { JOURNEY_DISRUPTION_CONTEXT_VERSION, type JourneyDisruptionContext } from "../src/models/journeyDisruptionContext.js";

function deviation(overrides: {
  id?: number;
  header?: string;
  details?: string;
  lines?: Array<{ designation: string; transportMode: string | null }>;
  stopAreaIds?: number[];
  stopPointIds?: number[];
  publishFrom?: string | null;
  publishUpto?: string | null;
}): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: overrides.id ?? 1,
    publish: { from: overrides.publishFrom ?? null, upto: overrides.publishUpto ?? null },
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: overrides.header ?? "h", details: overrides.details ?? "d", language: "sv" }],
    scope: {
      stop_areas: overrides.stopAreaIds?.map((id) => ({ id, name: "Test", type: null })),
      stop_points: overrides.stopPointIds?.map((id) => ({ id, name: "Test" })),
      lines: overrides.lines?.map((l, i) => ({ id: i + 1, designation: l.designation, transport_mode: l.transportMode, name: null })),
    },
  };
}

/** A StopPointDirectory that never resolves anything — sufficient for every test that never
 * sends `disruptionContext` at all (the legacy path never calls it). */
function unusedStopPointDirectory(): StopPointDirectory {
  return {
    async resolveMany(gids) {
      const result = new Map<PatternPointGid, StopPointResolution>();
      for (const gid of gids) result.set(gid, { status: "UNRESOLVED", patternPointGid: gid });
      return result;
    },
  };
}

/** A StopPointDirectory fully controlled by the test, or one that always throws (to exercise the
 * StopPointDirectory-failure legacy fallback). */
function fakeStopPointDirectory(table: Record<string, { stopPointId: number; stopAreaId: number }> | "THROW"): StopPointDirectory {
  return {
    async resolveMany(gids) {
      if (table === "THROW") throw new AppError("UPSTREAM_ERROR", "SL Transport stop-point directory unavailable for test");
      const result = new Map<PatternPointGid, StopPointResolution>();
      for (const gid of gids) {
        const entry = table[gid];
        result.set(gid, entry ? { status: "RESOLVED", patternPointGid: gid, stopPointId: entry.stopPointId, stopAreaId: entry.stopAreaId, stopAreaType: null } : { status: "UNRESOLVED", patternPointGid: gid });
      }
      return result;
    },
  };
}

function buildTestApp(deviations: RawDeviation[], sites: Site[] = [], stopPointDirectory: StopPointDirectory = unusedStopPointDirectory()) {
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
  app.route("/journeys/disruptions", createJourneyDisruptionsRoute(snapshotService, fakeSiteDirectory, stopPointDirectory));
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

describe("POST /api/v1/journeys/disruptions — the confirmed Akalla case (legacy request shape)", () => {
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

describe("POST /api/v1/journeys/disruptions — Akalla -> T-Centralen does not falsely become CONFIRMED/NO_SERVICE (legacy)", () => {
  it("a stop-scoped closure elsewhere on the same line stays LINE_RELEVANT, not silently dropped, with only the origin verified", async () => {
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

describe("POST /api/v1/journeys/disruptions — Slussen -> Liljeholmen stays disruption-free (legacy)", () => {
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

describe("POST /api/v1/journeys/disruptions — stop-scoped CONFIRMED matching (legacy)", () => {
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

  it("rejects a disruptionContext that fails schema validation", async () => {
    const { app } = buildTestApp([]);
    const res = await post(app, { legs: [], journeyPlannerNotices: [], disruptionContext: { version: 1, legs: "not an array" } });
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

function disruptionContext(legs: JourneyDisruptionContext["legs"]): JourneyDisruptionContext {
  return { version: JOURNEY_DISRUPTION_CONTEXT_VERSION, journeyStart: "Akalla", journeyEnd: "T-Centralen", legs };
}

describe("POST /api/v1/journeys/disruptions — rich disruptionContext resolution", () => {
  it("resolves the exact acceptance scenario: Kungsträdgården lift is UNRELATED, T-Centralen lift is CONFIRMED", async () => {
    const directory = fakeStopPointDirectory({
      akalla: { stopPointId: 3272, stopAreaId: 3271 },
      tcentralen: { stopPointId: 3051, stopAreaId: 1051 },
    });
    const kungstradgardenLift = deviation({ id: 12203432, header: "Avstängd hiss vid Kungsträdgården", lines: [{ designation: "10", transportMode: "METRO" }, { designation: "11", transportMode: "METRO" }], stopAreaIds: [3031] });
    const tcentralenLift = deviation({ id: 12285237, header: "Avstängd hiss vid T-Centralen", lines: [{ designation: "10", transportMode: "METRO" }, { designation: "11", transportMode: "METRO" }], stopAreaIds: [1051] });
    const { app } = buildTestApp([kungstradgardenLift, tcentralenLift], [], directory);

    const ctx = disruptionContext([
      { transportMode: "METRO", lineDesignation: "11", boardingPatternPointGid: "akalla", alightingPatternPointGid: "tcentralen", stopPatternPointGids: ["akalla", "tcentralen"], stopSequenceComplete: true },
    ]);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      journeyPlannerNotices: [],
      disruptionContext: ctx,
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
    expect(body.data.disruptions[0]!.id).toBe("12285237");
    expect(body.data.disruptions[0]!.relevance).toBe("CONFIRMED");
  });

  it("Slussen → Liljeholmen: a multi-mode origin site never lets a bus-terminal-only disruption match the metro journey", async () => {
    // Regression for the multi-mode origin fallback bug: Slussen's own site (9192) has BOTH a
    // metro StopArea (1011) and a bus terminal StopArea (44000). PRIMARY's real boarding platform
    // resolves EXACTLY to the metro StopArea -- the routine's own originSiteId is also supplied,
    // but must never additionally broaden ACCESS_POINTS to include the bus terminal.
    const directory = fakeStopPointDirectory({
      "slussen-metro": { stopPointId: 1012, stopAreaId: 1011 },
      liljeholmen: { stopPointId: 1201, stopAreaId: 1294 },
    });
    const slussenSite: Site = { siteId: 9192, name: "Slussen", note: null, lat: null, lon: null, stopAreaIds: [1011, 44000] };
    const busTerminalIssue = deviation({ id: 1, header: "Avstängd rulltrappa vid Slussen bussterminal", lines: [{ designation: "13", transportMode: "METRO" }], stopAreaIds: [44000] });
    const metroStationIssue = deviation({ id: 2, header: "Avstängd hiss vid Slussen", lines: [{ designation: "13", transportMode: "METRO" }], stopAreaIds: [1011] });
    const { app } = buildTestApp([busTerminalIssue, metroStationIssue], [slussenSite], directory);

    const ctx = disruptionContext([
      { transportMode: "METRO", lineDesignation: "13", boardingPatternPointGid: "slussen-metro", alightingPatternPointGid: "liljeholmen", stopPatternPointGids: ["slussen-metro", "liljeholmen"], stopSequenceComplete: true },
    ]);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "13" }],
      originSiteId: 9192,
      journeyPlannerNotices: [],
      disruptionContext: ctx,
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;

    // Only the genuine metro-station issue matches -- the bus-terminal one, scoped to StopArea
    // 44000, is UNRELATED even though 44000 also belongs to the same parent Slussen site.
    expect(body.data.disruptions).toHaveLength(1);
    expect(body.data.disruptions[0]!.id).toBe("2");
    expect(body.data.disruptions[0]!.relevance).toBe("CONFIRMED");
  });

  it("falls back to the legacy origin-only PARTIAL logic when disruptionContext.version is unrecognized", async () => {
    const directory = fakeStopPointDirectory({ akalla: { stopPointId: 3272, stopAreaId: 3271 } });
    const kungstradgardenLift = deviation({ id: 1, header: "Avstängd hiss vid Kungsträdgården", lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [3031] });
    const { app } = buildTestApp([kungstradgardenLift], [], directory);

    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      journeyPlannerNotices: [],
      disruptionContext: { ...disruptionContext([]), version: 2 }, // deliberately unrecognized, to exercise the fallback
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    // Legacy PARTIAL behavior: no verified scope at all (no originSiteId supplied either) ->
    // fails safe to LINE_RELEVANT, never silently dropped and never falsely CONFIRMED/UNRELATED.
    expect(body.data.disruptions[0]!.relevance).toBe("LINE_RELEVANT");
  });

  it("falls back to the legacy PARTIAL logic (never fails the request) when StopPointDirectory itself is unavailable", async () => {
    const throwingDirectory = fakeStopPointDirectory("THROW");
    const stopScoped = deviation({ id: 1, lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [9192] });
    const sites: Site[] = [{ siteId: 9192, name: "Akalla", note: null, lat: null, lon: null, stopAreaIds: [] }];
    const { app } = buildTestApp([stopScoped], sites, throwingDirectory);

    const ctx = disruptionContext([
      { transportMode: "METRO", lineDesignation: "11", boardingPatternPointGid: "akalla", alightingPatternPointGid: "tcentralen", stopPatternPointGids: [], stopSequenceComplete: false },
    ]);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      originSiteId: 9192,
      journeyPlannerNotices: [],
      disruptionContext: ctx,
    });
    expect(res.status).toBe(200); // never a 5xx just because the directory failed
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions[0]!.relevance).toBe("CONFIRMED"); // origin-only PARTIAL scope still resolves this
  });

  it("an accessibility issue at a TRANSFER station (not the journey's origin or final destination) resolves CONFIRMED", async () => {
    // Mirrors the real live Slussen transfer captured during architecture review: Metro 19
    // alights at Slussen's own metro stop area (1011), then a WALK leg crosses to Slussen's
    // separate bus terminal stop area (44000), then Bus 471 continues to Nacka Forum.
    const directory = fakeStopPointDirectory({
      akalla: { stopPointId: 3272, stopAreaId: 3271 },
      "slussen-metro": { stopPointId: 1012, stopAreaId: 1011 },
      "slussen-bus": { stopPointId: 44002, stopAreaId: 44000 },
      "nacka-forum": { stopPointId: 40171, stopAreaId: 40116 },
    });
    const slussenMetroLift = deviation({ id: 1, header: "Avstängd hiss vid Slussen", lines: [{ designation: "19", transportMode: "METRO" }], stopAreaIds: [1011] });
    const { app } = buildTestApp([slussenMetroLift], [], directory);

    const ctx = disruptionContext([
      { transportMode: "METRO", lineDesignation: "19", boardingPatternPointGid: "akalla", alightingPatternPointGid: "slussen-metro", stopPatternPointGids: ["akalla", "slussen-metro"], stopSequenceComplete: true },
      { transportMode: "WALK", lineDesignation: null, boardingPatternPointGid: "slussen-metro", alightingPatternPointGid: "slussen-bus", stopPatternPointGids: [], stopSequenceComplete: false },
      { transportMode: "BUS", lineDesignation: "471", boardingPatternPointGid: "slussen-bus", alightingPatternPointGid: "nacka-forum", stopPatternPointGids: ["slussen-bus", "nacka-forum"], stopSequenceComplete: true },
    ]);
    const res = await post(app, {
      legs: [
        { transportMode: "METRO", lineDesignation: "19" },
        { transportMode: "BUS", lineDesignation: "471" },
      ],
      journeyPlannerNotices: [],
      disruptionContext: ctx,
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
    expect(body.data.disruptions[0]!.relevance).toBe("CONFIRMED");
    expect(body.data.disruptions[0]!.matchedLineDesignations).toEqual(["19"]);

    // The bus leg's own scope must never have absorbed the metro-side Slussen stop area -- an
    // unrelated Bus 471 accessibility issue at the BUS stop area must not falsely confirm too.
    const busSideIssue = deviation({ id: 2, lines: [{ designation: "19", transportMode: "METRO" }], stopAreaIds: [44000] });
    const { app: app2 } = buildTestApp([busSideIssue], [], directory);
    const res2 = await post(app2, {
      legs: [{ transportMode: "METRO", lineDesignation: "19" }],
      journeyPlannerNotices: [],
      disruptionContext: ctx,
    });
    const body2 = (await res2.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body2.data.disruptions).toEqual([]);
  });

  it("an unresolved platform id degrades that leg's own completeness to PARTIAL rather than confirming or disproving anything", async () => {
    const directory = fakeStopPointDirectory({}); // nothing resolves
    const kungstradgardenLift = deviation({ id: 1, header: "Avstängd hiss vid Kungsträdgården", lines: [{ designation: "11", transportMode: "METRO" }], stopAreaIds: [3031] });
    const { app } = buildTestApp([kungstradgardenLift], [], directory);

    const ctx = disruptionContext([
      { transportMode: "METRO", lineDesignation: "11", boardingPatternPointGid: "akalla", alightingPatternPointGid: "tcentralen", stopPatternPointGids: [], stopSequenceComplete: false },
    ]);
    const res = await post(app, { legs: [{ transportMode: "METRO", lineDesignation: "11" }], journeyPlannerNotices: [], disruptionContext: ctx });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions[0]!.relevance).toBe("LINE_RELEVANT");
  });
});

describe("POST /api/v1/journeys/disruptions — temporal relevance", () => {
  it("omits an expired deviation when departureTime/arrivalTime are supplied", async () => {
    const expired = deviation({
      id: 1,
      lines: [{ designation: "11", transportMode: "METRO" }],
      publishUpto: "2020-01-01T00:00:00Z",
    });
    const { app } = buildTestApp([expired]);
    const res = await post(app, {
      legs: [{ transportMode: "METRO", lineDesignation: "11" }],
      journeyPlannerNotices: [],
      departureTime: "2026-08-16T10:00:00Z",
      arrivalTime: "2026-08-16T10:20:00Z",
    });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toEqual([]);
  });

  it("still includes the same deviation when departureTime/arrivalTime are omitted (older client)", async () => {
    const expired = deviation({
      id: 1,
      lines: [{ designation: "11", transportMode: "METRO" }],
      publishUpto: "2020-01-01T00:00:00Z",
    });
    const { app } = buildTestApp([expired]);
    const res = await post(app, { legs: [{ transportMode: "METRO", lineDesignation: "11" }], journeyPlannerNotices: [] });
    const body = (await res.json()) as SuccessEnvelope<{ disruptions: ResolvedJourneyDisruption[] }>;
    expect(body.data.disruptions).toHaveLength(1);
  });
});
