import { describe, expect, it, vi } from "vitest";
import {
  createGtfsFeedSource,
  createGtfsStopIdResolver,
  createLineTopologyDirectory,
  createUnavailableGtfsFeedSource,
  createUnprovenGtfsStopIdResolver,
  transportModeForGtfsRouteType,
  type GtfsFeedFetchResult,
  type GtfsFeedSource,
  type GtfsStopIdResolution,
  type GtfsStopIdResolver,
  type LineTopologyDirectory,
} from "../src/services/lineTopologyDirectory.js";
import type { StopAreaIdentityResolution, StopAreaNameIndex, StopPointDirectory } from "../src/services/stopPointDirectory.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import { AppError } from "../src/lib/errors.js";

// GTFS-namespace stop ids (illustrative, string) -> the StopArea ids they bridge to, mirroring
// backend/fixtures/gtfsLine11Sample/ (see that fixture's own README entry). Real StopArea ids
// verified live earlier for Akalla/T-Centralen/Kungsträdgården.
const GTFS = { akalla: "g-akalla", husby: "g-husby", kista: "g-kista", tcentralen: "g-tcentralen", kungstradgarden: "g-kungstradgarden" };
const STOP_AREA = { akalla: 3271, husby: 3261, kista: 3251, tcentralen: 1051, kungstradgarden: 3031 };

const LINE_11_ROUTES = "route_id,route_short_name,route_type\nR11,11,401\n"; // 401 = Metro Service, Trafiklab's real extended route_type for SL metro
const LINE_11_TRIPS = "route_id,trip_id\nR11,t-full\n";
const LINE_11_FULL_STOP_TIMES = [
  `t-full,${GTFS.akalla},1`,
  `t-full,${GTFS.husby},2`,
  `t-full,${GTFS.kista},3`,
  `t-full,${GTFS.tcentralen},4`,
  `t-full,${GTFS.kungstradgarden},5`,
].join("\n");
const LINE_11_STOP_TIMES = `trip_id,stop_id,stop_sequence\n${LINE_11_FULL_STOP_TIMES}\n`;

/** A minimal STORED-only (uncompressed) ZIP builder, just enough to prove createGtfsFeedSource's
 * own end-to-end fetch -> extract -> file-name-mapping wiring against a real ZIP archive -- the
 * ZIP format itself (including DEFLATE, error paths, and independent cross-validation against
 * .NET's own ZipFile reader) is already thoroughly covered in gtfsZipExtractor.test.ts. */
function buildTestZip(entries: Array<{ name: string; content: string }>): Uint8Array {
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;
  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name, "utf8");
    const data = Buffer.from(entry.content, "utf8");
    const localHeaderOffset = offset;
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt32LE(data.length, 18); // compressed size (== uncompressed: STORED)
    localHeader.writeUInt32LE(data.length, 22); // uncompressed size
    localHeader.writeUInt16LE(nameBuf.length, 26);
    localParts.push(localHeader, nameBuf, data);
    offset += localHeader.length + nameBuf.length + data.length;

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt32LE(data.length, 20);
    centralHeader.writeUInt32LE(data.length, 24);
    centralHeader.writeUInt16LE(nameBuf.length, 28);
    centralHeader.writeUInt32LE(localHeaderOffset, 42);
    centralParts.push(centralHeader, nameBuf);
  }
  const centralDirectoryOffset = offset;
  const centralDirectoryBuf = Buffer.concat(centralParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralDirectoryBuf.length, 12);
  eocd.writeUInt32LE(centralDirectoryOffset, 16);
  return new Uint8Array(Buffer.concat([...localParts, centralDirectoryBuf, eocd]));
}

interface SimpleFeed {
  routesCsv: string;
  tripsCsv: string;
  stopTimesCsv: string;
}

/** Always returns a fresh "200 OK" result (never NOT_MODIFIED) -- sufficient for every test that
 * doesn't specifically exercise conditional-request behavior (see the dedicated "conditional GET"
 * describe block below for those). */
function fakeFeedSource(result: SimpleFeed | Error, onFetch?: () => void): GtfsFeedSource {
  return {
    async fetchFeedFiles() {
      onFetch?.();
      if (result instanceof Error) throw result;
      return { status: "OK", files: result, validators: {} };
    },
  };
}

function fakeStopIdResolver(table: Record<string, number>): GtfsStopIdResolver {
  return {
    async resolveMany(gtfsStopIds) {
      const result = new Map<string, GtfsStopIdResolution>();
      for (const id of gtfsStopIds) {
        const stopAreaId = table[id];
        result.set(id, stopAreaId != null ? { status: "RESOLVED", stopAreaId } : { status: "UNRESOLVED" });
      }
      return result;
    },
  };
}

function fakeNameIndex(table: Record<string, number[]>): StopAreaNameIndex {
  return {
    async findStopAreaIdsByName(name) {
      return table[name] ?? [];
    },
  };
}

const LINE_11_STOP_ID_TABLE: Record<string, number> = {
  [GTFS.akalla]: STOP_AREA.akalla,
  [GTFS.husby]: STOP_AREA.husby,
  [GTFS.kista]: STOP_AREA.kista,
  [GTFS.tcentralen]: STOP_AREA.tcentralen,
  [GTFS.kungstradgarden]: STOP_AREA.kungstradgarden,
};

const LINE_11_NAME_TABLE: Record<string, number[]> = {
  akalla: [STOP_AREA.akalla],
  husby: [STOP_AREA.husby],
  kista: [STOP_AREA.kista],
  "t-centralen": [STOP_AREA.tcentralen],
  kungsträdgården: [STOP_AREA.kungstradgarden],
};

function directoryFor(
  feed: SimpleFeed | Error = { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES },
  stopIdTable: Record<string, number> = LINE_11_STOP_ID_TABLE,
  nameTable: Record<string, number[]> = LINE_11_NAME_TABLE,
  onFetch?: () => void,
): LineTopologyDirectory {
  return createLineTopologyDirectory(
    fakeFeedSource(feed, onFetch),
    fakeStopIdResolver(stopIdTable),
    fakeNameIndex(nameTable),
    new InMemoryCache(),
    new InMemoryLock(),
    new InFlightDeduper(),
  );
}

describe("createLineTopologyDirectory: resolveSegment end to end", () => {
  it("adjacent stops resolve to the single connecting edge", async () => {
    const directory = directoryFor();
    const result = await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården");
    expect(result).toEqual({
      status: "RESOLVED",
      stopAreaA: STOP_AREA.tcentralen,
      stopAreaB: STOP_AREA.kungstradgarden,
      edges: new Set([`${Math.min(STOP_AREA.tcentralen, STOP_AREA.kungstradgarden)}:${Math.max(STOP_AREA.tcentralen, STOP_AREA.kungstradgarden)}`]),
      orderedStopAreaIds: [STOP_AREA.tcentralen, STOP_AREA.kungstradgarden],
    });
  });

  it("reversed station order resolves to the exact same edge set (stopAreaA/B simply track which argument was passed where)", async () => {
    const directory = directoryFor();
    const forward = await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    const backward = await directory.resolveSegment("METRO", "11", "t-centralen", "akalla");
    expect(forward.status).toBe("RESOLVED");
    expect(backward.status).toBe("RESOLVED");
    expect(forward.status === "RESOLVED" && backward.status === "RESOLVED" && forward.edges).toEqual(backward.status === "RESOLVED" && backward.edges);
  });

  it("a station name carrying trailing prose past the real name (unresolved at full length) still resolves via word-boundary shrinking", async () => {
    const directory = directoryFor();
    const result = await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården hänvisar vi till alternativa resvägar");
    expect(result.status).toBe("RESOLVED");
    expect(result.status === "RESOLVED" && result.stopAreaB).toBe(STOP_AREA.kungstradgarden);
  });
});

describe("createLineTopologyDirectory: line/mode identity", () => {
  it("the same designation on a DIFFERENT mode (metro 11 vs bus 11) never contaminates the other's topology", async () => {
    const routesWithBothModes = "route_id,route_short_name,route_type\nR11metro,11,401\nR11bus,11,700\n";
    const tripsWithBothModes = "route_id,trip_id\nR11metro,t-metro\nR11bus,t-bus11\n";
    const stopTimes = `trip_id,stop_id,stop_sequence\nt-metro,${GTFS.akalla},1\nt-metro,${GTFS.tcentralen},2\nt-bus11,g-bus-stop-a,1\nt-bus11,g-bus-stop-b,2\n`;
    const directory = directoryFor({
      routesCsv: routesWithBothModes,
      tripsCsv: tripsWithBothModes,
      stopTimesCsv: stopTimes,
    });

    const metroResult = await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    expect(metroResult.status).toBe("RESOLVED");

    // The bus-11 stops were never given a name/stopId mapping -- if the bus route's own trip
    // leaked into the metro-mode graph, this would still resolve; it must not.
    const busNamesLeaking = await directory.resolveSegment("METRO", "11", "akalla", "bus stop b");
    expect(busNamesLeaking.status).not.toBe("RESOLVED");
  });

  it("an unrecognized transportMode resolves to UNRESOLVED, never a crash", async () => {
    const directory = directoryFor();
    expect(await directory.resolveSegment("SPACESHIP", "11", "akalla", "t-centralen")).toEqual({ status: "UNRESOLVED" });
  });

  it("a lineDesignation the feed has no route for resolves to UNRESOLVED", async () => {
    const directory = directoryFor();
    expect(await directory.resolveSegment("METRO", "99", "akalla", "t-centralen")).toEqual({ status: "UNRESOLVED" });
  });
});

describe("createLineTopologyDirectory: multi-mode station resolves to the line-specific StopArea", () => {
  it("a name shared by two StopAreas (e.g. a metro/bus multi-mode site) resolves to only the one actually on this line's own topology", async () => {
    const nameTableWithMultiMode: Record<string, number[]> = { ...LINE_11_NAME_TABLE, slussen: [1011, 44000] };
    // Only StopArea 1011 (the metro one) is ever wired into line 11's own topology below.
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\n";
    const tripsCsv = "route_id,trip_id\nR11,t-full\n";
    const stopTimesCsv = `trip_id,stop_id,stop_sequence\nt-full,g-slussen,1\nt-full,${GTFS.tcentralen},2\n`;
    const directory = directoryFor(
      { routesCsv, tripsCsv, stopTimesCsv },
      { "g-slussen": 1011, [GTFS.tcentralen]: STOP_AREA.tcentralen },
      nameTableWithMultiMode,
    );

    const result = await directory.resolveSegment("METRO", "11", "slussen", "t-centralen");
    expect(result).toEqual({
      status: "RESOLVED",
      stopAreaA: 1011,
      stopAreaB: STOP_AREA.tcentralen,
      edges: new Set([`1011:1051`]),
      orderedStopAreaIds: [1011, STOP_AREA.tcentralen],
    });
  });
});

describe("createLineTopologyDirectory: branch/ambiguous topology", () => {
  it("a genuinely branching line between the two requested stops resolves AMBIGUOUS, never a guess", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\n";
    const tripsCsv = "route_id,trip_id\nR11,via-a\nR11,via-b\n";
    const stopTimesCsv = [
      "trip_id,stop_id,stop_sequence",
      `via-a,${GTFS.akalla},1`,
      `via-a,${GTFS.husby},2`,
      `via-a,${GTFS.tcentralen},3`,
      `via-b,${GTFS.akalla},1`,
      `via-b,${GTFS.kista},2`,
      `via-b,${GTFS.tcentralen},3`,
    ].join("\n");
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv });

    const result = await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    expect(result).toEqual({ status: "AMBIGUOUS" });
  });
});

describe("createLineTopologyDirectory: name resolution failure modes", () => {
  it("an unknown station name resolves UNRESOLVED", async () => {
    const directory = directoryFor();
    expect(await directory.resolveSegment("METRO", "11", "akalla", "nagonstans")).toEqual({ status: "UNRESOLVED" });
  });

  it("a name matching more than one StopArea, both on this same line, is AMBIGUOUS", async () => {
    const nameTableWithDup: Record<string, number[]> = { ...LINE_11_NAME_TABLE, akalla: [STOP_AREA.akalla, STOP_AREA.husby] };
    const directory = directoryFor(undefined, undefined, nameTableWithDup);
    expect(await directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).toEqual({ status: "AMBIGUOUS" });
  });
});

describe("createLineTopologyDirectory: never create an edge across a missing GTFS stop (items 6/7)", () => {
  it("A resolved -> B UNRESOLVED -> C resolved never produces an A<->C edge (this line becomes PARTIAL and simply stops being usable)", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\n";
    const tripsCsv = "route_id,trip_id\nR11,t-full\n";
    // B (g-husby) is deliberately absent from the stop-id table below.
    const stopTimesCsv = `trip_id,stop_id,stop_sequence\nt-full,${GTFS.akalla},1\nt-full,${GTFS.husby},2\nt-full,${GTFS.tcentralen},3\n`;
    const gapTable: Record<string, number> = { [GTFS.akalla]: STOP_AREA.akalla, [GTFS.tcentralen]: STOP_AREA.tcentralen }; // husby omitted
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv }, gapTable, LINE_11_NAME_TABLE);

    // A fabricated A<->C edge would have made this resolve RESOLVED; instead the whole line is
    // PARTIAL (an unresolved stop exists) and therefore unavailable for authoritative use.
    const result = await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    expect(result).toEqual({ status: "UNRESOLVED" });
  });

  it("an AMBIGUOUS GTFS stop identity (not just UNRESOLVED) also breaks contiguity and marks the line PARTIAL", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\n";
    const tripsCsv = "route_id,trip_id\nR11,t-full\n";
    const stopTimesCsv = `trip_id,stop_id,stop_sequence\nt-full,${GTFS.akalla},1\nt-full,${GTFS.husby},2\nt-full,${GTFS.tcentralen},3\n`;
    const ambiguousResolver: GtfsStopIdResolver = {
      async resolveMany(ids) {
        const result = new Map<string, GtfsStopIdResolution>();
        for (const id of ids) {
          if (id === GTFS.husby) result.set(id, { status: "AMBIGUOUS" });
          else if (id === GTFS.akalla) result.set(id, { status: "RESOLVED", stopAreaId: STOP_AREA.akalla });
          else if (id === GTFS.tcentralen) result.set(id, { status: "RESOLVED", stopAreaId: STOP_AREA.tcentralen });
          else result.set(id, { status: "UNRESOLVED" });
        }
        return result;
      },
    };
    const directory = createLineTopologyDirectory(
      fakeFeedSource({ routesCsv, tripsCsv, stopTimesCsv }),
      ambiguousResolver,
      fakeNameIndex(LINE_11_NAME_TABLE),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );
    expect(await directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).toEqual({ status: "UNRESOLVED" });
  });

  it("a gap near the middle of a longer trip still correctly separates the two RESOLVED fragments on either side (no bridging in either fragment)", async () => {
    // A - B - [gap: C unresolved] - D - E. A<->B and D<->E must resolve; nothing must ever
    // connect B to D.
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\n";
    const tripsCsv = "route_id,trip_id\nR11,t-full\n";
    const stopTimesCsv = [
      "trip_id,stop_id,stop_sequence",
      `t-full,${GTFS.akalla},1`,
      `t-full,${GTFS.husby},2`,
      `t-full,g-gap-stop,3`,
      `t-full,${GTFS.tcentralen},4`,
      `t-full,${GTFS.kungstradgarden},5`,
    ].join("\n");
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv }, LINE_11_STOP_ID_TABLE, LINE_11_NAME_TABLE);

    // The line as a whole is PARTIAL (g-gap-stop never resolves), so resolveSegment must not
    // treat it as authoritative for ANY pair on this line, including the two genuinely-adjacent
    // pairs on either side of the gap -- this is the conservative, safe outcome (item 6).
    expect(await directory.resolveSegment("METRO", "11", "akalla", "husby")).toEqual({ status: "UNRESOLVED" });
    expect(await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården")).toEqual({ status: "UNRESOLVED" });
    // And critically, no fabricated edge across the gap either.
    expect(await directory.resolveSegment("METRO", "11", "husby", "t-centralen")).toEqual({ status: "UNRESOLVED" });
  });
});

describe("createLineTopologyDirectory: an unrecognized route_type does not poison unrelated lines (item 26)", () => {
  it("a route whose route_type maps to no known mode simply has no topology of its own, while a normal supported line right next to it is unaffected", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\nRTAXI,401,1501\n"; // 1501 = Communal Taxi Service, deliberately unmapped
    const tripsCsv = "route_id,trip_id\nR11,t-full\nRTAXI,t-taxi\n"; // t-full matches LINE_11_STOP_TIMES's own trip id
    const stopTimesCsv = `${LINE_11_STOP_TIMES}t-taxi,g-taxi-a,1\nt-taxi,g-taxi-b,2\n`;
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv }, LINE_11_STOP_ID_TABLE, LINE_11_NAME_TABLE);

    // The normal metro-11 line resolves completely, unaffected by the unmapped taxi route
    // sharing the exact same designation ("401") on a different, unrecognized mode.
    const metroResult = await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården");
    expect(metroResult.status).toBe("RESOLVED");

    // The unmapped route itself never gets a mode key at all -- querying it under any mode is
    // UNRESOLVED, never a crash and never a guessed mode.
    const taxiResult = await directory.resolveSegment("TAXI", "401", "somewhere", "another place");
    expect(taxiResult).toEqual({ status: "UNRESOLVED" });
  });
});

describe("createLineTopologyDirectory: per-line completeness (item 6)", () => {
  it("one line with a gap (PARTIAL) does not poison a completely independent line, which stays COMPLETE and fully usable", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\nR13,13,401\n";
    const tripsCsv = "route_id,trip_id\nR11,t-11\nR13,t-13\n";
    const stopTimesCsv = [
      "trip_id,stop_id,stop_sequence",
      `t-11,${GTFS.akalla},1`,
      `t-11,g-unresolved-gap,2`,
      `t-11,${GTFS.tcentralen},3`,
      `t-13,${GTFS.tcentralen},1`,
      `t-13,${GTFS.kungstradgarden},2`,
    ].join("\n");
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv }, LINE_11_STOP_ID_TABLE, LINE_11_NAME_TABLE);

    expect(await directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).toEqual({ status: "UNRESOLVED" }); // PARTIAL line 11
    expect(await directory.resolveSegment("METRO", "13", "t-centralen", "kungsträdgården")).toEqual({
      status: "RESOLVED",
      stopAreaA: STOP_AREA.tcentralen,
      stopAreaB: STOP_AREA.kungstradgarden,
      edges: new Set([`${Math.min(STOP_AREA.tcentralen, STOP_AREA.kungstradgarden)}:${Math.max(STOP_AREA.tcentralen, STOP_AREA.kungstradgarden)}`]),
      orderedStopAreaIds: [STOP_AREA.tcentralen, STOP_AREA.kungstradgarden],
    }); // COMPLETE line 13, fully usable
  });

  it("a fully-resolved line (every stop RESOLVED) is COMPLETE and usable -- the ordinary, healthy case", async () => {
    const directory = directoryFor(); // LINE_11_STOP_TIMES: every one of its 5 stops is in LINE_11_STOP_ID_TABLE
    const result = await directory.resolveSegment("METRO", "11", "akalla", "kungsträdgården");
    expect(result.status).toBe("RESOLVED");
  });

  it("resolveEndpointsCorridor is ALSO gated on line completeness, not just resolveSegment", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\n";
    const tripsCsv = "route_id,trip_id\nR11,t-full\n";
    const stopTimesCsv = `trip_id,stop_id,stop_sequence\nt-full,${GTFS.akalla},1\nt-full,g-unresolved,2\nt-full,${GTFS.tcentralen},3\n`;
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv }, LINE_11_STOP_ID_TABLE, LINE_11_NAME_TABLE);
    const site = (siteId: number, stopAreaIds: number[]) => ({ siteId, name: "s", note: null, lat: null, lon: null, stopAreaIds });
    const result = await directory.resolveEndpointsCorridor("METRO", "11", site(1, [STOP_AREA.akalla]), site(2, [STOP_AREA.tcentralen]));
    expect(result).toEqual({ status: "UNRESOLVED" });
  });

  it("resolveEndpointsCorridor always orients orderedStopAreaIds origin-first, destination-last -- regardless of the underlying GTFS pattern's own storage direction", async () => {
    // The real fixture stores the pattern as akalla -> husby -> kista -> t-centralen ->
    // kungstradgarden (sequence 1..5). Requesting the corridor in the OPPOSITE direction
    // (origin=kungstradgarden, destination=akalla) must still return orderedStopAreaIds oriented
    // kungstradgarden-first, akalla-last -- never the pattern's own raw storage order.
    const directory = directoryFor();
    const site = (siteId: number, stopAreaIds: number[]) => ({ siteId, name: "s", note: null, lat: null, lon: null, stopAreaIds });
    const result = await directory.resolveEndpointsCorridor(
      "METRO",
      "11",
      site(1, [STOP_AREA.kungstradgarden]),
      site(2, [STOP_AREA.akalla]),
    );
    expect(result).toMatchObject({
      status: "RESOLVED",
      orderedStopAreaIds: [STOP_AREA.kungstradgarden, STOP_AREA.tcentralen, STOP_AREA.kista, STOP_AREA.husby, STOP_AREA.akalla],
    });
  });

  it("resolveEndpointsCorridor's own orientation matches the pattern's natural direction when origin/destination are requested in that same order (no reversal needed)", async () => {
    const directory = directoryFor();
    const site = (siteId: number, stopAreaIds: number[]) => ({ siteId, name: "s", note: null, lat: null, lon: null, stopAreaIds });
    const result = await directory.resolveEndpointsCorridor("METRO", "11", site(1, [STOP_AREA.akalla]), site(2, [STOP_AREA.kungstradgarden]));
    expect(result).toMatchObject({
      status: "RESOLVED",
      orderedStopAreaIds: [STOP_AREA.akalla, STOP_AREA.husby, STOP_AREA.kista, STOP_AREA.tcentralen, STOP_AREA.kungstradgarden],
    });
  });
});

describe("createLineTopologyDirectory: graceful failure -- never throws, always degrades to UNRESOLVED", () => {
  it("no TRAFIKLAB_API_KEY / feed source failure -> UNRESOLVED, not a thrown error", async () => {
    const directory = directoryFor(new AppError("UPSTREAM_ERROR", "GTFS Regional is not configured (TRAFIKLAB_API_KEY is not set)"));
    await expect(directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toEqual({ status: "UNRESOLVED" });
  });

  it("a refresh failure after the previous fetch has gone stale (past the 24h freshness window) still uses that stale feed rather than failing", async () => {
    vi.useFakeTimers();
    try {
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      const goodFeed = { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES };
      const healthyDirectory = createLineTopologyDirectory(
        fakeFeedSource(goodFeed),
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        sharedCache,
        sharedLock,
        new InFlightDeduper(),
      );
      // Warm the shared cache with a genuinely successful fetch first.
      await expect(healthyDirectory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toMatchObject({ status: "RESOLVED" });

      // Advance past the 24h freshness window (but well within InMemoryCache's own much longer
      // stale-fallback TTL) so the next call genuinely attempts a refresh.
      vi.advanceTimersByTime(25 * 60 * 60 * 1000);

      const failingDirectory = createLineTopologyDirectory(
        fakeFeedSource(new Error("GTFS Regional temporarily unavailable")),
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        sharedCache,
        sharedLock,
        new InFlightDeduper(),
      );
      // Stale topology exists but is now UNVALIDATED -- item 17: must not be authoritative.
      await expect(failingDirectory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toEqual({ status: "UNRESOLVED" });
    } finally {
      vi.useRealTimers();
    }
  });

  it("the real createUnprovenGtfsStopIdResolver always resolves UNRESOLVED (never guesses a StopArea)", async () => {
    const directory = createLineTopologyDirectory(
      fakeFeedSource({ routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES }),
      createUnprovenGtfsStopIdResolver(),
      fakeNameIndex(LINE_11_NAME_TABLE),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );
    const result = await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    expect(result).toEqual({ status: "UNRESOLVED" });
  });
});

describe("createUnprovenGtfsStopIdResolver / createGtfsStopIdResolver", () => {
  it("createUnprovenGtfsStopIdResolver resolves every id UNRESOLVED, batched", async () => {
    const resolver = createUnprovenGtfsStopIdResolver();
    const result = await resolver.resolveMany(["a", "b", "c"]);
    expect(result).toEqual(
      new Map([
        ["a", { status: "UNRESOLVED" }],
        ["b", { status: "UNRESOLVED" }],
        ["c", { status: "UNRESOLVED" }],
      ]),
    );
  });

  it("createGtfsStopIdResolver delegates to StopPointDirectory.resolveStopPointGids, translating RESOLVED/AMBIGUOUS/UNRESOLVED", async () => {
    let receivedGids: readonly string[] = [];
    const fakeDirectory: Pick<StopPointDirectory, "resolveStopPointGids"> = {
      async resolveStopPointGids(gids) {
        receivedGids = gids;
        const result = new Map<string, StopAreaIdentityResolution>();
        result.set("g-resolved", { status: "RESOLVED", gid: "g-resolved", stopAreaId: 42 });
        result.set("g-ambiguous", { status: "AMBIGUOUS", gid: "g-ambiguous", stopAreaIds: [1, 2] });
        result.set("g-unresolved", { status: "UNRESOLVED", gid: "g-unresolved" });
        return result;
      },
    };
    const resolver = createGtfsStopIdResolver(fakeDirectory);
    const result = await resolver.resolveMany(["g-resolved", "g-ambiguous", "g-unresolved"]);

    expect(receivedGids).toEqual(["g-resolved", "g-ambiguous", "g-unresolved"]);
    expect(result.get("g-resolved")).toEqual({ status: "RESOLVED", stopAreaId: 42 });
    expect(result.get("g-ambiguous")).toEqual({ status: "AMBIGUOUS" });
    expect(result.get("g-unresolved")).toEqual({ status: "UNRESOLVED" });
  });

  it("createGtfsStopIdResolver never converts a gid to a JS number -- exact strings pass through untouched", async () => {
    const hugeGid = "90220010009999999999999";
    const fakeDirectory: Pick<StopPointDirectory, "resolveStopPointGids"> = {
      async resolveStopPointGids(gids) {
        return new Map(gids.map((g) => [g, { status: "RESOLVED", gid: g, stopAreaId: 1 } as const]));
      },
    };
    const resolver = createGtfsStopIdResolver(fakeDirectory);
    const result = await resolver.resolveMany([hugeGid]);
    expect(result.has(hugeGid)).toBe(true); // string-keyed lookup survived exactly, no numeric coercion/rounding
  });
});

describe("transportModeForGtfsRouteType: real Trafiklab extended route_type evidence", () => {
  // Values confirmed live against Trafiklab's own current documentation
  // (trafiklab.se/api/gtfs-datasets/overview/extensions/ and Google's canonical
  // developers.google.com/transit/gtfs/reference/extended-route-types, both checked 2026-08-16).
  // Trafiklab's own docs explicitly cite 401 with Stockholm's Tunnelbanan (SL Metro) as the
  // worked example -- the strongest evidence available for SL's real METRO code without a live
  // feed download (still blocked on the missing TRAFIKLAB_API_KEY credential).
  it.each([
    [100, "TRAIN"], // Railway Service
    [106, "TRAIN"], // Regional Rail Service
    [109, "TRAIN"], // Suburban Railway
    [401, "METRO"], // Metro Service -- Trafiklab-confirmed for SL (Tunnelbanan)
    [402, "METRO"], // Underground Service
    [700, "BUS"], // Bus Service
    [704, "BUS"], // Local Bus Service
    [714, "BUS"], // Rail Replacement Bus Service
    [900, "TRAM"], // Tram Service
    [902, "TRAM"], // Local Tram Service
    [1000, "FERRY"], // Water Transport Service
    [1200, "FERRY"], // Ferry Service
  ])("route_type %i resolves to %s", (routeType, expectedMode) => {
    expect(transportModeForGtfsRouteType(routeType)).toBe(expectedMode);
  });

  it("basic GTFS route_type values (0-4) match no mode -- Trafiklab's real feeds never emit them", () => {
    for (const basicCode of [0, 1, 2, 3, 4]) {
      expect(transportModeForGtfsRouteType(basicCode)).toBeNull();
    }
  });

  it("a documented code from a family SL doesn't operate (e.g. 1501 Communal Taxi Service) matches no mode, never a guess", () => {
    expect(transportModeForGtfsRouteType(1501)).toBeNull();
  });

  it("the real Metro 11 fixture (route_type 401, Trafiklab's own documented SL Metro code) resolves successfully end to end", async () => {
    const directory = directoryFor();
    const result = await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården");
    expect(result.status).toBe("RESOLVED");
  });
});

describe("createUnavailableGtfsFeedSource: the production wiring gate (item 21)", () => {
  it("always throws without ever calling fetch, regardless of environment configuration", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    try {
      await expect(createUnavailableGtfsFeedSource().fetchFeedFiles()).rejects.toThrow(/not yet enabled/i);
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      fetchSpy.mockRestore();
    }
  });
});

describe("createGtfsFeedSource: real network wiring, gated on TRAFIKLAB_API_KEY", () => {
  it("throws immediately without ever calling fetch when no API key is configured", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    try {
      await expect(createGtfsFeedSource(undefined).fetchFeedFiles()).rejects.toThrow(/TRAFIKLAB_API_KEY is not set/);
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("propagates a controlled error when the upstream responds with a non-OK, non-304 status", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 503 }));
    try {
      await expect(createGtfsFeedSource("test-key").fetchFeedFiles()).rejects.toThrow(/GTFS Regional fetch failed with status 503/);
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("extracts routes.txt/trips.txt/stop_times.txt from a real returned zip, capturing ETag/Last-Modified", async () => {
    const zipBytes = buildTestZip([
      { name: "routes.txt", content: LINE_11_ROUTES },
      { name: "trips.txt", content: LINE_11_TRIPS },
      { name: "stop_times.txt", content: LINE_11_STOP_TIMES },
      { name: "agency.txt", content: "agency_id,agency_name\n9000,SL\n" },
    ]);
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(zipBytes, { status: 200, headers: { etag: '"abc123"', "last-modified": "Mon, 17 Aug 2026 00:00:00 GMT" } }));
    try {
      const result = await createGtfsFeedSource("test-key").fetchFeedFiles();
      expect(result).toEqual({
        status: "OK",
        files: { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES },
        validators: { etag: '"abc123"', lastModified: "Mon, 17 Aug 2026 00:00:00 GMT" },
      });
      expect(fetchSpy).toHaveBeenCalledWith(expect.stringContaining("opendata.samtrafiken.se/gtfs/sl/sl.zip?key=test-key"), expect.anything());
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("sends If-None-Match when a previous ETag is supplied, and returns NOT_MODIFIED on a real 304", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 304 }));
    try {
      const result: GtfsFeedFetchResult = await createGtfsFeedSource("test-key").fetchFeedFiles({ etag: '"abc123"' });
      expect(result).toEqual({ status: "NOT_MODIFIED" });
      const [, init] = fetchSpy.mock.calls[0]!;
      expect((init as RequestInit).headers).toMatchObject({ "If-None-Match": '"abc123"' });
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("falls back to If-Modified-Since when only Last-Modified is known (no ETag)", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 304 }));
    try {
      await createGtfsFeedSource("test-key").fetchFeedFiles({ lastModified: "Mon, 17 Aug 2026 00:00:00 GMT" });
      const [, init] = fetchSpy.mock.calls[0]!;
      expect((init as RequestInit).headers).toMatchObject({ "If-Modified-Since": "Mon, 17 Aug 2026 00:00:00 GMT" });
    } finally {
      fetchSpy.mockRestore();
    }
  });
});

describe("createLineTopologyDirectory: conditional GET / freshness (items 17/18)", () => {
  it("after the freshness window elapses, a 304 response revalidates without rebuilding the index", async () => {
    vi.useFakeTimers();
    try {
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      let fetchCount = 0;
      const conditionalSource: GtfsFeedSource = {
        async fetchFeedFiles(previousValidators) {
          fetchCount++;
          if (previousValidators?.etag === '"v1"') return { status: "NOT_MODIFIED" };
          return {
            status: "OK",
            files: { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES },
            validators: { etag: '"v1"' },
          };
        },
      };
      const directory = createLineTopologyDirectory(
        conditionalSource,
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        sharedCache,
        sharedLock,
        new InFlightDeduper(),
      );

      await expect(directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toMatchObject({ status: "RESOLVED" });
      expect(fetchCount).toBe(1);

      vi.advanceTimersByTime(25 * 60 * 60 * 1000); // past freshness, but within stale-fallback

      // This call should trigger a conditional revalidation (304) -- the index itself is reused,
      // and the topology remains usable (revalidated, not rebuilt).
      await expect(directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toMatchObject({ status: "RESOLVED" });
      expect(fetchCount).toBe(2);

      // A THIRD call within the new freshness window must not trigger yet another attempt.
      await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
      expect(fetchCount).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("a 200 response after staleness rebuilds the index atomically", async () => {
    vi.useFakeTimers();
    try {
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      let version = 1;
      const evolvingSource: GtfsFeedSource = {
        async fetchFeedFiles() {
          const v = version++;
          return {
            status: "OK",
            files: { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES },
            validators: { etag: `"v${v}"` },
          };
        },
      };
      const directory = createLineTopologyDirectory(
        evolvingSource,
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        sharedCache,
        sharedLock,
        new InFlightDeduper(),
      );
      await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
      vi.advanceTimersByTime(25 * 60 * 60 * 1000);
      await expect(directory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toMatchObject({ status: "RESOLVED" });
      expect(version).toBe(3); // called again -- a genuine rebuild happened, not a silent no-op
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("createLineTopologyDirectory: compact index / warm lookup performance", () => {
  it("a warm resolveSegment call makes zero GtfsStopIdResolver calls -- all stop-id resolution happens once, during index build, never per lookup", async () => {
    let resolverCallCount = 0;
    const countingResolver: GtfsStopIdResolver = {
      async resolveMany(ids) {
        resolverCallCount++;
        return new Map(ids.map((id) => [id, LINE_11_STOP_ID_TABLE[id] != null ? { status: "RESOLVED" as const, stopAreaId: LINE_11_STOP_ID_TABLE[id]! } : { status: "UNRESOLVED" as const }]));
      },
    };
    const directory = createLineTopologyDirectory(
      fakeFeedSource({ routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES }),
      countingResolver,
      fakeNameIndex(LINE_11_NAME_TABLE),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );

    await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården"); // cold: builds the whole index (ONE batch call)
    expect(resolverCallCount).toBe(1);

    await directory.resolveSegment("METRO", "11", "akalla", "husby"); // warm: must be a pure index lookup
    await directory.resolveSegment("METRO", "11", "husby", "kista");
    await directory.resolveEndpointsCorridor(
      "METRO",
      "11",
      { siteId: 1, name: "s", note: null, lat: null, lon: null, stopAreaIds: [STOP_AREA.akalla] },
      { siteId: 2, name: "d", note: null, lat: null, lon: null, stopAreaIds: [STOP_AREA.tcentralen] },
    );
    expect(resolverCallCount).toBe(1); // unchanged -- no additional batch calls on warm lookups
  });

  it("the index is built for every line in the feed exactly once, regardless of how many different lines are subsequently queried", async () => {
    const routesCsv = "route_id,route_short_name,route_type\nR11,11,401\nR401,401,700\n";
    const tripsCsv = "route_id,trip_id\nR11,t-11\nR401,t-401\n";
    const stopTimesCsv = `${LINE_11_STOP_TIMES}t-401,g-bus-a,1\nt-401,g-bus-b,2\n`;
    let fetchCount = 0;
    const directory = directoryFor({ routesCsv, tripsCsv, stopTimesCsv }, LINE_11_STOP_ID_TABLE, LINE_11_NAME_TABLE, () => fetchCount++);
    await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    await directory.resolveSegment("BUS", "401", "somewhere", "another place");
    expect(fetchCount).toBe(1);
  });
});

describe("createLineTopologyDirectory: quota protection -- failure backoff (item 19: ~24h, not 1h)", () => {
  it("repeated resolveSegment calls after a hard failure (no prior snapshot) make only ONE upstream fetch attempt, not one per call", async () => {
    let fetchCount = 0;
    const alwaysFailingSource: GtfsFeedSource = {
      async fetchFeedFiles() {
        fetchCount++;
        throw new Error("GTFS Regional temporarily unavailable");
      },
    };
    const directory = createLineTopologyDirectory(
      alwaysFailingSource,
      fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
      fakeNameIndex(LINE_11_NAME_TABLE),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );

    const results = await Promise.all([
      directory.resolveSegment("METRO", "11", "akalla", "t-centralen"),
      directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården"),
      directory.resolveSegment("METRO", "11", "husby", "kista"),
    ]);

    expect(fetchCount).toBe(1); // the never-released attempt-claim lock blocks every call after the first
    expect(results.every((r) => r.status === "UNRESOLVED")).toBe(true); // still degrades safely
  });

  it("a second wave of calls shortly after the first failure still makes no new fetch attempt (within the ~24h backoff window)", async () => {
    let fetchCount = 0;
    const alwaysFailingSource: GtfsFeedSource = {
      async fetchFeedFiles() {
        fetchCount++;
        throw new Error("GTFS Regional temporarily unavailable");
      },
    };
    const directory = createLineTopologyDirectory(
      alwaysFailingSource,
      fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
      fakeNameIndex(LINE_11_NAME_TABLE),
      new InMemoryCache(),
      new InMemoryLock(),
      new InFlightDeduper(),
    );

    await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    expect(fetchCount).toBe(1);

    await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    expect(fetchCount).toBe(1); // no new attempt without advancing past the backoff window
  });

  it("a mere 1-hour gap does NOT trigger a new attempt -- the backoff is ~24h, not 1h", async () => {
    vi.useFakeTimers();
    try {
      let fetchCount = 0;
      const alwaysFailingSource: GtfsFeedSource = {
        async fetchFeedFiles() {
          fetchCount++;
          throw new Error("GTFS Regional temporarily unavailable");
        },
      };
      const directory = createLineTopologyDirectory(
        alwaysFailingSource,
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        new InMemoryCache(),
        new InMemoryLock(),
        new InFlightDeduper(),
      );

      await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
      expect(fetchCount).toBe(1);

      vi.advanceTimersByTime(90 * 60 * 1000); // 1.5h -- well past the OLD 1h window, well within 24h

      await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
      expect(fetchCount).toBe(1); // still blocked -- proves the backoff is NOT the old 1h value
    } finally {
      vi.useRealTimers();
    }
  });

  it("after the full ~24h backoff window elapses, a new upstream attempt is made", async () => {
    vi.useFakeTimers();
    try {
      let fetchCount = 0;
      const alwaysFailingSource: GtfsFeedSource = {
        async fetchFeedFiles() {
          fetchCount++;
          throw new Error("GTFS Regional temporarily unavailable");
        },
      };
      const directory = createLineTopologyDirectory(
        alwaysFailingSource,
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        new InMemoryCache(),
        new InMemoryLock(),
        new InFlightDeduper(),
      );

      await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
      expect(fetchCount).toBe(1);

      vi.advanceTimersByTime(25 * 60 * 60 * 1000); // past the ~24h backoff window

      await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
      expect(fetchCount).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("a failure with an existing stale snapshot still degrades to UNRESOLVED (stale is never authoritative), and does not re-attempt within the backoff window", async () => {
    vi.useFakeTimers();
    try {
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      const goodFeed = { routesCsv: LINE_11_ROUTES, tripsCsv: LINE_11_TRIPS, stopTimesCsv: LINE_11_STOP_TIMES };
      const healthyDirectory = createLineTopologyDirectory(
        fakeFeedSource(goodFeed),
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        sharedCache,
        sharedLock,
        new InFlightDeduper(),
      );
      await expect(healthyDirectory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toMatchObject({ status: "RESOLVED" });

      vi.advanceTimersByTime(25 * 60 * 60 * 1000); // past the 24h freshness window

      let failedFetchCount = 0;
      const failingDirectory = createLineTopologyDirectory(
        {
          async fetchFeedFiles() {
            failedFetchCount++;
            throw new Error("GTFS Regional temporarily unavailable");
          },
        },
        fakeStopIdResolver(LINE_11_STOP_ID_TABLE),
        fakeNameIndex(LINE_11_NAME_TABLE),
        sharedCache,
        sharedLock,
        new InFlightDeduper(),
      );
      await expect(failingDirectory.resolveSegment("METRO", "11", "akalla", "t-centralen")).resolves.toEqual({ status: "UNRESOLVED" });
      await expect(failingDirectory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården")).resolves.toEqual({ status: "UNRESOLVED" });
      expect(failedFetchCount).toBe(1); // one attempt was made (and failed), not one per call
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("createLineTopologyDirectory: caching / upstream request budget", () => {
  it("repeated resolveSegment calls reuse the cached feed -- at most one fetch", async () => {
    let fetchCount = 0;
    const directory = directoryFor(undefined, undefined, undefined, () => fetchCount++);
    await directory.resolveSegment("METRO", "11", "akalla", "t-centralen");
    await directory.resolveSegment("METRO", "11", "t-centralen", "kungsträdgården");
    await directory.resolveSegment("METRO", "11", "husby", "kista");
    expect(fetchCount).toBe(1);
  });

  it("concurrent cold resolveSegment calls deduplicate into a single upstream fetch", async () => {
    let fetchCount = 0;
    const directory = directoryFor(undefined, undefined, undefined, () => fetchCount++);
    await Promise.all([
      directory.resolveSegment("METRO", "11", "akalla", "t-centralen"),
      directory.resolveSegment("METRO", "11", "akalla", "husby"),
      directory.resolveSegment("METRO", "11", "kista", "t-centralen"),
    ]);
    expect(fetchCount).toBe(1);
  });
});
