import { describe, expect, it } from "vitest";
import { createStopPointDirectory } from "../src/services/stopPointDirectory.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import type { DistributedLock } from "../src/lib/distributedLock.js";
import { AppError, isAppError } from "../src/lib/errors.js";
import type { SlTransportClient } from "../src/services/slTransportClient.js";
import type { RawStopPoint } from "../src/services/upstreamTypes.js";

function stopPoint(overrides: Partial<RawStopPoint> & Pick<RawStopPoint, "id" | "pattern_point_gid">): RawStopPoint {
  return {
    gid: `9022001000${overrides.id}001`,
    name: `Stop ${overrides.id}`,
    type: "PLATFORM",
    stop_area: { id: overrides.id, name: `Area ${overrides.id}`, type: "METROSTN" },
    ...overrides,
  };
}

const AKALLA = stopPoint({ id: 3272, pattern_point_gid: "9025001000003272", name: "Akalla, Stockholm", stop_area: { id: 3271, name: "Akalla", type: "METROSTN" } });
const T_CENTRALEN = stopPoint({ id: 3051, pattern_point_gid: "9025001000003051", name: "T-Centralen, Stockholm", stop_area: { id: 1051, name: "T-Centralen", type: "METROSTN" } });

function scriptedClient(script: Array<(() => Promise<RawStopPoint[]>) | Error>) {
  let callCount = 0;
  const client: SlTransportClient = {
    async fetchAllSites() {
      throw new Error("not used in this test");
    },
    async fetchDepartures() {
      throw new Error("not used in this test");
    },
    async fetchStopPoints() {
      const index = callCount;
      callCount += 1;
      const entry = script[index];
      if (entry == null) throw new Error(`scriptedClient: no script entry for call #${index}`);
      if (entry instanceof Error) throw entry;
      return entry();
    },
  };
  return { client, callCount: () => callCount };
}

function freshDirectory(stopPoints: RawStopPoint[]) {
  const { client } = scriptedClient([async () => stopPoints]);
  return createStopPointDirectory(client, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
}

describe("createStopPointDirectory — resolution outcomes", () => {
  it("resolves a known pattern_point_gid to its stop point / stop area identity", async () => {
    const directory = freshDirectory([AKALLA, T_CENTRALEN]);
    const results = await directory.resolveMany(["9025001000003272"]);

    expect(results.get("9025001000003272")).toEqual({
      status: "RESOLVED",
      patternPointGid: "9025001000003272",
      stopPointId: 3272,
      stopAreaId: 3271,
      stopAreaType: "METROSTN",
    });
  });

  it("returns UNRESOLVED for a pattern_point_gid absent from the directory", async () => {
    const directory = freshDirectory([AKALLA]);
    const results = await directory.resolveMany(["9025001000009999"]);

    expect(results.get("9025001000009999")).toEqual({ status: "UNRESOLVED", patternPointGid: "9025001000009999" });
  });

  it("resolves every requested id exactly once, deduplicating repeated inputs", async () => {
    const directory = freshDirectory([AKALLA, T_CENTRALEN]);
    const results = await directory.resolveMany(["9025001000003272", "9025001000003051", "9025001000003272"]);

    expect(results.size).toBe(2);
    expect(results.get("9025001000003272")?.status).toBe("RESOLVED");
    expect(results.get("9025001000003051")?.status).toBe("RESOLVED");
  });

  it("marks a pattern_point_gid AMBIGUOUS when it maps to more than one DIFFERENT stop area", async () => {
    const a = stopPoint({ id: 100, pattern_point_gid: "9025000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const b = stopPoint({ id: 200, pattern_point_gid: "9025000000000100", stop_area: { id: 20, name: "Area B", type: "METROSTN" } });
    const directory = freshDirectory([a, b]);

    const results = await directory.resolveMany(["9025000000000100"]);
    expect(results.get("9025000000000100")).toEqual({
      status: "AMBIGUOUS",
      patternPointGid: "9025000000000100",
      stopAreaIds: [10, 20],
    });
  });

  it("resolves fully (RESOLVED) when two records share a pattern_point_gid AND agree on both stop area and StopPoint id", async () => {
    const a = stopPoint({ id: 100, pattern_point_gid: "9025000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const b = stopPoint({ id: 100, pattern_point_gid: "9025000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const directory = freshDirectory([a, b]);

    const results = await directory.resolveMany(["9025000000000100"]);
    expect(results.get("9025000000000100")).toEqual({
      status: "RESOLVED",
      patternPointGid: "9025000000000100",
      stopPointId: 100,
      stopAreaId: 10,
      stopAreaType: "METROSTN",
    });
  });

  it("resolves the StopArea only (STOP_AREA_ONLY) when records agree on stop area but disagree on their own StopPoint id -- never arbitrarily picks the first StopPoint id", async () => {
    const a = stopPoint({ id: 100, pattern_point_gid: "9025000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const b = stopPoint({ id: 101, pattern_point_gid: "9025000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const directory = freshDirectory([a, b]);

    const results = await directory.resolveMany(["9025000000000100"]);
    expect(results.get("9025000000000100")).toEqual({
      status: "STOP_AREA_ONLY",
      patternPointGid: "9025000000000100",
      stopAreaId: 10,
      stopAreaType: "METROSTN",
    });
  });

  it("preserves a real >MAX_SAFE_INTEGER-scale id exactly through resolution", async () => {
    const huge = stopPoint({ id: 3272, pattern_point_gid: "9025001000003272", gid: "9022001000101001" });
    const directory = freshDirectory([huge]);
    const results = await directory.resolveMany(["9025001000003272"]);
    expect(results.get("9025001000003272")?.status).toBe("RESOLVED");
  });
});

describe("createStopPointDirectory — resolveStopPointGids (GTFS stop_id -> StopArea identity bridge)", () => {
  it("resolves a known RawStopPoint.gid to its StopArea id", async () => {
    const directory = freshDirectory([AKALLA, T_CENTRALEN]);
    const results = await directory.resolveStopPointGids([AKALLA.gid]);
    expect(results.get(AKALLA.gid)).toEqual({ status: "RESOLVED", gid: AKALLA.gid, stopAreaId: 3271 });
  });

  it("an unknown gid resolves to UNRESOLVED, never throws", async () => {
    const directory = freshDirectory([AKALLA]);
    const results = await directory.resolveStopPointGids(["9022999999999999999"]);
    expect(results.get("9022999999999999999")).toEqual({ status: "UNRESOLVED", gid: "9022999999999999999" });
  });

  it("duplicate gid records that agree on StopArea resolve safely (not ambiguous)", async () => {
    const a = stopPoint({ id: 100, pattern_point_gid: "pp-100a", gid: "9022000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const b = stopPoint({ id: 101, pattern_point_gid: "pp-100b", gid: "9022000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const directory = freshDirectory([a, b]);
    const results = await directory.resolveStopPointGids(["9022000000000100"]);
    expect(results.get("9022000000000100")).toEqual({ status: "RESOLVED", gid: "9022000000000100", stopAreaId: 10 });
  });

  it("duplicate gid records that DISAGREE on StopArea are AMBIGUOUS, never first-record-wins", async () => {
    const a = stopPoint({ id: 100, pattern_point_gid: "pp-100a", gid: "9022000000000100", stop_area: { id: 10, name: "Area A", type: "METROSTN" } });
    const b = stopPoint({ id: 200, pattern_point_gid: "pp-200b", gid: "9022000000000100", stop_area: { id: 20, name: "Area B", type: "METROSTN" } });
    const directory = freshDirectory([a, b]);
    const results = await directory.resolveStopPointGids(["9022000000000100"]);
    expect(results.get("9022000000000100")).toEqual({ status: "AMBIGUOUS", gid: "9022000000000100", stopAreaIds: [10, 20] });
  });

  it("a real >MAX_SAFE_INTEGER-scale gid stays an exact string throughout", async () => {
    const huge = stopPoint({ id: 3272, pattern_point_gid: "9025001000003272", gid: "90220010009999999999999" });
    const directory = freshDirectory([huge]);
    const results = await directory.resolveStopPointGids(["90220010009999999999999"]);
    expect(results.get("90220010009999999999999")).toMatchObject({ status: "RESOLVED", stopAreaId: 3272 });
  });

  it("batch resolution deduplicates repeated input gids", async () => {
    const directory = freshDirectory([AKALLA, T_CENTRALEN]);
    const results = await directory.resolveStopPointGids([AKALLA.gid, T_CENTRALEN.gid, AKALLA.gid]);
    expect(results.size).toBe(2);
  });

  it("one resolveStopPointGids call loads exactly one StopPoint snapshot (no per-gid upstream fetch)", async () => {
    const { client, callCount } = scriptedClient([async () => [AKALLA, T_CENTRALEN]]);
    const directory = createStopPointDirectory(client, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
    await directory.resolveStopPointGids([AKALLA.gid, T_CENTRALEN.gid, "unknown-gid"]);
    expect(callCount()).toBe(1);
  });

  it("all three capabilities (resolveMany, findStopAreaIdsByName, resolveStopPointGids) share the SAME cached snapshot -- zero additional fetches across all three", async () => {
    const { client, callCount } = scriptedClient([async () => [AKALLA, T_CENTRALEN]]);
    const directory = createStopPointDirectory(client, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
    await directory.resolveMany([AKALLA.pattern_point_gid]);
    await directory.findStopAreaIdsByName("akalla"); // stop_area.name, not the platform's own longer name
    await directory.resolveStopPointGids([AKALLA.gid]);
    expect(callCount()).toBe(1);
  });
});

describe("createStopPointDirectory — caching", () => {
  it("fetches once and serves subsequent resolveMany calls from cache within the freshness window", async () => {
    const { client, callCount } = scriptedClient([async () => [AKALLA]]);
    const directory = createStopPointDirectory(client, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());

    await directory.resolveMany(["9025001000003272"]);
    await directory.resolveMany(["9025001000003272"]);

    expect(callCount()).toBe(1);
  });
});

describe("createStopPointDirectory — stale fallback", () => {
  it("falls back to the last known-good index when a refresh fails, rather than throwing", async () => {
    const { client, callCount } = scriptedClient([
      async () => [AKALLA],
      new AppError("UPSTREAM_ERROR", "SL Transport returned HTTP 500 for test"),
    ]);
    const cache = new InMemoryCache();
    const directoryA = createStopPointDirectory(client, cache, new InMemoryLock(), new InFlightDeduper());
    await directoryA.resolveMany(["9025001000003272"]);

    // A brand new directory instance (simulating a fresh cold start well past the freshness
    // window) sharing the SAME cache -- but this time the upstream fetch fails.
    const cacheEntry = await cache.get("sl-transport:stop-point-snapshot:v1");
    expect(cacheEntry).toBeTruthy();
    await cache.set("sl-transport:stop-point-snapshot:v1", { ...(cacheEntry as object), fetchedAt: "2020-01-01T00:00:00.000Z" }, 60 * 60 * 24 * 7);

    const directoryB = createStopPointDirectory(client, cache, new InMemoryLock(), new InFlightDeduper());
    const results = await directoryB.resolveMany(["9025001000003272"]);

    expect(callCount()).toBe(2); // the failed refresh WAS attempted
    expect(results.get("9025001000003272")?.status).toBe("RESOLVED"); // still served from stale data
  });

  it("throws a controlled UPSTREAM_ERROR when there is no snapshot at all and the fetch fails", async () => {
    const originalError = new AppError("UPSTREAM_TIMEOUT", "SL Transport did not complete within 10000ms");
    const { client } = scriptedClient([originalError]);
    const directory = createStopPointDirectory(client, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());

    try {
      await directory.resolveMany(["9025001000003272"]);
      expect.fail("expected resolveMany() to throw");
    } catch (err) {
      expect(err).toBe(originalError);
      expect(isAppError(err) && err.code).toBe("UPSTREAM_TIMEOUT");
    }
  });
});

describe("createStopPointDirectory — concurrency across separate simulated instances", () => {
  it("concurrent resolveMany() calls from separate directory instances sharing one cache/lock make exactly one upstream call", async () => {
    const { client, callCount } = scriptedClient([
      async () => {
        await new Promise<void>((resolve) => setTimeout(resolve, 20));
        return [AKALLA, T_CENTRALEN];
      },
    ]);

    // ONE shared cache and lock (simulating one shared Redis); a SEPARATE InFlightDeduper per
    // instance (simulating separate Vercel instances, each with only its own process memory).
    const sharedCache = new InMemoryCache();
    const sharedLock = new InMemoryLock();
    const instanceA = createStopPointDirectory(client, sharedCache, sharedLock, new InFlightDeduper());
    const instanceB = createStopPointDirectory(client, sharedCache, sharedLock, new InFlightDeduper());
    const instanceC = createStopPointDirectory(client, sharedCache, sharedLock, new InFlightDeduper());

    const [resultsA, resultsB, resultsC] = await Promise.all([
      instanceA.resolveMany(["9025001000003272"]),
      instanceB.resolveMany(["9025001000003272"]),
      instanceC.resolveMany(["9025001000003272"]),
    ]);

    expect(callCount()).toBe(1);
    expect(resultsA.get("9025001000003272")).toEqual(resultsB.get("9025001000003272"));
    expect(resultsB.get("9025001000003272")).toEqual(resultsC.get("9025001000003272"));
  });
});

describe("createStopPointDirectory — identity resolution uses ONLY pattern_point_gid, never a fallback", () => {
  it("never falls back to matching by name -- two stop points with identical names but different gids resolve independently", async () => {
    const a = stopPoint({ id: 100, pattern_point_gid: "9025000000000100", name: "Centralen" });
    const b = stopPoint({ id: 200, pattern_point_gid: "9025000000000200", name: "Centralen" }); // same name, different physical stop
    const directory = freshDirectory([a, b]);

    const results = await directory.resolveMany(["9025000000000100", "9025000000000200", "9025000000000999"]);
    expect(results.get("9025000000000100")?.status).toBe("RESOLVED");
    expect(results.get("9025000000000200")?.status).toBe("RESOLVED");
    // A gid never present in the directory is UNRESOLVED even though its name ("unknown") is
    // never even consulted -- there is no name-based search path at all.
    expect(results.get("9025000000000999")).toEqual({ status: "UNRESOLVED", patternPointGid: "9025000000000999" });
  });

  it("never derives an id via substring/arithmetic manipulation -- a real live case where that would silently break", async () => {
    // Confirmed live: Fridhemsplan's own platform gid is "...3152" but its stop-area id is
    // "1151" (not "3151"), and T-Centralen's platform gid is "...3051" but its stop-area id is
    // "1051" (not "3051") -- an assumed fixed-offset/substring relationship between the two
    // would silently corrupt both. The only correct join is the exact pattern_point_gid value.
    const fridhemsplan = stopPoint({ id: 3152, pattern_point_gid: "9025001000003152", stop_area: { id: 1151, name: "Fridhemsplan", type: "METROSTN" } });
    const tcentralen = stopPoint({ id: 3051, pattern_point_gid: "9025001000003051", stop_area: { id: 1051, name: "T-Centralen", type: "METROSTN" } });
    const directory = freshDirectory([fridhemsplan, tcentralen]);

    const results = await directory.resolveMany(["9025001000003152", "9025001000003051"]);
    expect(results.get("9025001000003152")).toMatchObject({ status: "RESOLVED", stopAreaId: 1151 });
    expect(results.get("9025001000003051")).toMatchObject({ status: "RESOLVED", stopAreaId: 1051 });
  });

  it("StopPointResolution never carries a coordinate field at all -- there is no lat/lon fallback path to accidentally use", async () => {
    const directory = freshDirectory([AKALLA]);
    const result = (await directory.resolveMany(["9025001000003272"])).get("9025001000003272");
    expect(result).not.toHaveProperty("lat");
    expect(result).not.toHaveProperty("lon");
  });
});

describe("createStopPointDirectory — findStopAreaIdsByName (merged from the former standalone StopAreaNameIndex)", () => {
  it("resolves a known name (case/whitespace-normalized) to its StopArea id", async () => {
    const slussen = stopPoint({ id: 1, pattern_point_gid: "pp-1", stop_area: { id: 1011, name: "Slussen", type: "METROSTN" } });
    const tcentralen = stopPoint({ id: 2, pattern_point_gid: "pp-2", stop_area: { id: 1051, name: "T-Centralen", type: "METROSTN" } });
    const directory = freshDirectory([slussen, tcentralen]);
    expect(await directory.findStopAreaIdsByName("slussen")).toEqual([1011]);
    expect(await directory.findStopAreaIdsByName("t-centralen")).toEqual([1051]);
  });

  it("multiple stop-points sharing one StopArea (many platforms) still produce one name entry", async () => {
    const platforms = [1, 2, 3].map((id) => stopPoint({ id, pattern_point_gid: `pp-${id}`, stop_area: { id: 1011, name: "Slussen", type: "METROSTN" } }));
    const directory = freshDirectory(platforms);
    expect(await directory.findStopAreaIdsByName("slussen")).toEqual([1011]);
  });

  it("a name genuinely shared by two distinct StopAreas returns both, never arbitrarily narrowed", async () => {
    // Mirrors the real live case (two distinct "Bålsta" sites, verified live 2026-08-16).
    const a = stopPoint({ id: 1, pattern_point_gid: "pp-1", stop_area: { id: 5299, name: "Bålsta", type: "RAILWSTN" } });
    const b = stopPoint({ id: 2, pattern_point_gid: "pp-2", stop_area: { id: 9710, name: "Bålsta", type: "RAILWSTN" } });
    const directory = freshDirectory([a, b]);
    const result = await directory.findStopAreaIdsByName("bålsta");
    expect([...result].sort()).toEqual([5299, 9710]);
  });

  it("an unknown name resolves to an empty array, never throws", async () => {
    const slussen = stopPoint({ id: 1, pattern_point_gid: "pp-1", stop_area: { id: 1011, name: "Slussen", type: "METROSTN" } });
    const directory = freshDirectory([slussen]);
    expect(await directory.findStopAreaIdsByName("nagonstans")).toEqual([]);
  });

  it("exact match only -- a near-miss name does not resolve", async () => {
    const slussen = stopPoint({ id: 1, pattern_point_gid: "pp-1", stop_area: { id: 1011, name: "Slussen", type: "METROSTN" } });
    const directory = freshDirectory([slussen]);
    expect(await directory.findStopAreaIdsByName("sluss")).toEqual([]);
    expect(await directory.findStopAreaIdsByName("slussen station")).toEqual([]);
  });

  it("caches across calls -- repeated findStopAreaIdsByName lookups do not re-fetch stop-points", async () => {
    const slussen = stopPoint({ id: 1, pattern_point_gid: "pp-1", stop_area: { id: 1011, name: "Slussen", type: "METROSTN" } });
    const { client, callCount } = scriptedClient([async () => [slussen]]);
    const directory = createStopPointDirectory(client, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
    await directory.findStopAreaIdsByName("slussen");
    await directory.findStopAreaIdsByName("slussen");
    await directory.findStopAreaIdsByName("t-centralen");
    expect(callCount()).toBe(1);
  });

  it("one shared snapshot serves both capabilities -- resolveMany warming the snapshot means findStopAreaIdsByName causes zero additional /stop-points calls, and vice versa", async () => {
    const slussen = stopPoint({ id: 1, pattern_point_gid: "pp-1", stop_area: { id: 1011, name: "Slussen", type: "METROSTN" } });
    const { client: clientA, callCount: callCountA } = scriptedClient([async () => [slussen]]);
    const directoryA = createStopPointDirectory(clientA, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
    await directoryA.resolveMany(["pp-1"]); // warms the snapshot via the pattern_point_gid path
    await directoryA.findStopAreaIdsByName("slussen"); // must reuse the SAME warm snapshot
    expect(callCountA()).toBe(1);

    const { client: clientB, callCount: callCountB } = scriptedClient([async () => [slussen]]);
    const directoryB = createStopPointDirectory(clientB, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
    await directoryB.findStopAreaIdsByName("slussen"); // warms the snapshot via the name path this time
    await directoryB.resolveMany(["pp-1"]); // must reuse the SAME warm snapshot
    expect(callCountB()).toBe(1);
  });
});

describe("createStopPointDirectory — lock release is best-effort", () => {
  it("a throwing lock.release() does not turn an already-successful, already-cached fetch into a failure", async () => {
    const { client, callCount } = scriptedClient([async () => [AKALLA]]);
    const innerLock = new InMemoryLock();
    const throwingReleaseLock: DistributedLock = {
      acquire: (key, ttlMs) => innerLock.acquire(key, ttlMs),
      release: async () => {
        throw new Error("Redis connection dropped during release");
      },
    };
    const directory = createStopPointDirectory(client, new InMemoryCache(), throwingReleaseLock, new InFlightDeduper());

    const results = await directory.resolveMany(["9025001000003272"]);

    expect(callCount()).toBe(1);
    expect(results.get("9025001000003272")?.status).toBe("RESOLVED");
  });
});
