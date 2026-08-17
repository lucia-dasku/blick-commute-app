import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parseGtfsCsv, parseGtfsRoutes, parseGtfsStopTimes, parseGtfsTrips } from "../src/services/gtfsCsvParser.js";

const fixtureDir = fileURLToPath(new URL("../fixtures/gtfsLine11Sample/", import.meta.url));
const routesCsv = readFileSync(fixtureDir + "routes.txt", "utf8");
const tripsCsv = readFileSync(fixtureDir + "trips.txt", "utf8");
const stopTimesCsv = readFileSync(fixtureDir + "stop_times.txt", "utf8");

describe("parseGtfsCsv: generic RFC 4180 parsing", () => {
  it("parses a simple header + rows into header-keyed records", () => {
    const records = parseGtfsCsv("a,b,c\n1,2,3\n4,5,6\n");
    expect(records).toEqual([
      { a: "1", b: "2", c: "3" },
      { a: "4", b: "5", c: "6" },
    ]);
  });

  it("handles quoted fields containing a comma", () => {
    const records = parseGtfsCsv('route_id,route_long_name\n1,"Blå linjen, riktning Akalla"\n');
    expect(records).toEqual([{ route_id: "1", route_long_name: "Blå linjen, riktning Akalla" }]);
  });

  it('handles an escaped "" quote inside a quoted field', () => {
    const records = parseGtfsCsv('a,b\n1,"say ""hi"""\n');
    expect(records).toEqual([{ a: "1", b: 'say "hi"' }]);
  });

  it("pads a short row with empty strings for missing trailing columns", () => {
    const records = parseGtfsCsv("a,b,c\n1,2\n");
    expect(records).toEqual([{ a: "1", b: "2", c: "" }]);
  });

  it("returns an empty array for an empty/header-only file", () => {
    expect(parseGtfsCsv("")).toEqual([]);
    expect(parseGtfsCsv("a,b,c\n")).toEqual([]);
  });

  it("handles CRLF line endings", () => {
    expect(parseGtfsCsv("a,b\r\n1,2\r\n")).toEqual([{ a: "1", b: "2" }]);
  });
});

describe("parseGtfsRoutes / parseGtfsTrips / parseGtfsStopTimes: typed extraction", () => {
  it("parses routes.txt with route_type coerced to a number, stop/route/trip ids kept as strings", () => {
    const routes = parseGtfsRoutes(routesCsv);
    expect(routes).toEqual([
      { routeId: "9011001000011000", shortName: "11", routeType: 401 },
      { routeId: "9011001000401000", shortName: "401", routeType: 700 },
    ]);
    expect(typeof routes[0]!.routeId).toBe("string");
  });

  it("parses trips.txt, mapping each trip to its own route", () => {
    const trips = parseGtfsTrips(tripsCsv);
    expect(trips).toEqual([
      { tripId: "11-full-outbound", routeId: "9011001000011000" },
      { tripId: "11-full-inbound", routeId: "9011001000011000" },
      { tripId: "11-shortturn-outbound", routeId: "9011001000011000" },
    ]);
  });

  it("parses stop_times.txt, stop_id kept as an exact string (never coerced to number), stop_sequence coerced to a number", () => {
    const stopTimes = parseGtfsStopTimes(stopTimesCsv);
    expect(stopTimes).toHaveLength(14);
    const first = stopTimes[0]!;
    expect(first).toEqual({ tripId: "11-full-outbound", stopId: "9091001000009300", stopSequence: 1 });
    expect(typeof first.stopId).toBe("string");
    expect(typeof first.stopSequence).toBe("number");
  });

  it("the full-outbound trip's own stop_times are in strictly increasing stop_sequence order as written", () => {
    const stopTimes = parseGtfsStopTimes(stopTimesCsv).filter((s) => s.tripId === "11-full-outbound");
    const sequences = stopTimes.map((s) => s.stopSequence);
    expect(sequences).toEqual([1, 2, 3, 4, 5]);
  });

  it("a row with a non-numeric stop_sequence is dropped rather than propagating NaN", () => {
    const withBadRow = "trip_id,stop_id,stop_sequence\nt1,s1,1\nt1,s2,notanumber\n";
    expect(parseGtfsStopTimes(withBadRow)).toEqual([{ tripId: "t1", stopId: "s1", stopSequence: 1 }]);
  });

  it("stop_id values that look like they could exceed Number.MAX_SAFE_INTEGER are still exact strings, never numbers", () => {
    // Real SL-namespace gids routinely exceed Number.MAX_SAFE_INTEGER -- confirmed live
    // elsewhere in this codebase (see upstreamTypes.ts's own BigIntIdentifierStringSchema doc).
    const huge = "trip_id,stop_id,stop_sequence\nt1,99999999999999999999,1\n";
    const result = parseGtfsStopTimes(huge);
    expect(result[0]!.stopId).toBe("99999999999999999999");
  });
});
