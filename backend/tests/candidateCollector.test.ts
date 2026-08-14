import { describe, expect, it, vi } from "vitest";
import { CandidateCollector, isEligibleJourney, MAX_ACQUISITION_BATCHES, MAX_CHANGES } from "../src/services/candidateCollector.js";
import type { RawJourneyPlannerJourney, SlJourneyPlannerClient, TripsRequest } from "../src/services/slJourneyPlannerClient.js";

function rawJourney(id: string, departure: string, arrival: string, interchanges = 0, mode: "metro" | "bus" = "metro"): RawJourneyPlannerJourney {
  return {
    tripId: id,
    interchanges,
    legs: [
      {
        origin: { name: "T-Centralen", departureTimeEstimated: departure },
        destination: { name: "Mariatorget", arrivalTimeEstimated: arrival },
        transportation: {
          disassembledName: mode === "metro" ? "14" : "135",
          product: { class: mode === "metro" ? 2 : 5, name: mode === "metro" ? "Tunnelbana" : "Buss" },
          destination: { name: "Mariatorget" },
        },
        infos: [],
      },
    ],
  } as unknown as RawJourneyPlannerJourney;
}

/** Serves a scripted sequence of raw-journey batches, one per call — [batches] is consumed
 * in order; a call past the end of the script returns an empty array (SL genuinely has
 * nothing more to offer). Records every request it received for assertions. */
function scriptedClient(batches: RawJourneyPlannerJourney[][]): { client: SlJourneyPlannerClient; requests: TripsRequest[] } {
  const requests: TripsRequest[] = [];
  let callIndex = 0;
  const client: SlJourneyPlannerClient = {
    async searchStops() {
      return [];
    },
    async trips(request) {
      requests.push(request);
      const batch = batches[callIndex] ?? [];
      callIndex++;
      return batch;
    },
  };
  return { client, requests };
}

const ORIGIN = "origin-id";
const DESTINATION = "destination-id";
const REQUESTED_AT_MILLIS = Date.parse("2026-08-10T07:00:00Z");
const BASE_OPTIONS = { transportModes: ["METRO", "BUS"] as const, maxChanges: 2 };

describe("isEligibleJourney", () => {
  it("rejects a journey that has already departed", () => {
    expect(isEligibleJourney({ departureTime: "2026-08-10T06:59:59Z", transferCount: 0 }, REQUESTED_AT_MILLIS)).toBe(false);
  });

  it("accepts a journey departing exactly at requestedAt", () => {
    expect(isEligibleJourney({ departureTime: "2026-08-10T07:00:00Z", transferCount: 0 }, REQUESTED_AT_MILLIS)).toBe(true);
  });

  it(`rejects a journey with more than ${MAX_CHANGES} changes`, () => {
    expect(isEligibleJourney({ departureTime: "2026-08-10T08:00:00Z", transferCount: MAX_CHANGES + 1 }, REQUESTED_AT_MILLIS)).toBe(false);
  });

  it(`accepts a journey with exactly ${MAX_CHANGES} changes`, () => {
    expect(isEligibleJourney({ departureTime: "2026-08-10T08:00:00Z", transferCount: MAX_CHANGES }, REQUESTED_AT_MILLIS)).toBe(true);
  });
});

describe("CandidateCollector.fetchBatch", () => {
  it("normalizes, mode-filters, and eligibility-filters a single batch", async () => {
    const { client } = scriptedClient([
      [
        rawJourney("metro-ok", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z"),
        rawJourney("bus-wrong-mode", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", 0, "bus"),
        rawJourney("expired", "2026-08-10T06:00:00Z", "2026-08-10T06:20:00Z"),
        rawJourney("too-many-changes", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", MAX_CHANGES + 1),
      ],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, transportModes: ["METRO"], departureAt: new Date("2026-08-10T07:00:00Z") });

    expect(collector.pool.map((j) => j.journeyId)).toEqual(["metro-ok"]);
  });

  it("reports the EARLIEST departure strictly after this batch's own bucket, before eligibility filtering, even when a later one exists", async () => {
    const { client } = scriptedClient([
      [
        rawJourney("survives", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z"),
        // Filtered out for too many changes, but SL still told us about it -- the EARLIEST
        // one strictly after the bucket is what advancement must be based on, never the
        // latest (see acquireUntil's own doc for why: jumping straight past the latest
        // returned departure could skip a genuinely relevant journey between the two).
        rawJourney("filtered-but-later", "2026-08-10T08:30:00Z", "2026-08-10T08:50:00Z", MAX_CHANGES + 1),
      ],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const batch = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });

    expect(collector.pool.map((j) => j.journeyId)).toEqual(["survives"]);
    expect(batch.earliestDepartureAfterCursor?.toISOString()).toBe("2026-08-10T08:00:00.000Z");
  });

  it("excludes a departure WITHIN this batch's own request-minute bucket from earliestDepartureAfterCursor -- it carries no forward information", async () => {
    const { client } = scriptedClient([
      [
        // Same minute as the 07:00:00 request below -- already fully answered by this very
        // response, so it must not count as "new territory" to advance toward.
        rawJourney("same-minute", "2026-08-10T07:00:40Z", "2026-08-10T07:20:00Z"),
      ],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const batch = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });

    expect(collector.pool.map((j) => j.journeyId)).toEqual(["same-minute"]);
    expect(batch.earliestDepartureAfterCursor).toBeNull();
  });

  it("reports earliestDepartureAfterCursor as null and rawJourneyIds as empty when SL returns nothing", async () => {
    const { client } = scriptedClient([[]]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const batch = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });

    expect(collector.pool).toEqual([]);
    expect(batch.earliestDepartureAfterCursor).toBeNull();
    expect(batch.rawJourneyIds).toEqual([]);
  });

  it("rawJourneyIds lists every journey SL returned, before eligibility filtering or upsert", async () => {
    const { client } = scriptedClient([
      [rawJourney("dup", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z")],
      [rawJourney("dup", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z"), rawJourney("new", "2026-08-10T08:10:00Z", "2026-08-10T08:30:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });
    const second = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:05:00Z") });

    // "dup" (already in the pool) and "new" both appear here -- rawJourneyIds is the
    // batch's own full response, never filtered down by what was already known.
    expect(second.rawJourneyIds).toEqual(["dup", "new"]);
  });

  it("upserts a journey already known from an earlier batch, rather than ignoring the repeat", async () => {
    const { client } = scriptedClient([
      [rawJourney("dup", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z")],
      [rawJourney("dup", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z"), rawJourney("new", "2026-08-10T08:10:00Z", "2026-08-10T08:30:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });
    expect(collector.pool.map((j) => j.journeyId)).toEqual(["dup"]);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:05:00Z") });
    // "dup" appears exactly once, alongside the genuinely new "new" -- never duplicated.
    expect(collector.pool.map((j) => j.journeyId).sort()).toEqual(["dup", "new"]);
  });

  it("a later batch's newer departure estimate replaces the earlier one for the same journey id", async () => {
    const { client } = scriptedClient([
      [rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T19:00:00Z")],
      [rawJourney("x", "2026-08-10T18:42:00Z", "2026-08-10T19:00:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });
    expect(collector.pool.find((j) => j.journeyId === "x")?.departureTime).toBe("2026-08-10T18:40:00Z");

    // A genuinely LATER, distinct query (a different request-minute bucket) -- never the
    // literal same query repeated, which `fetchBatch` now recognizes as a duplicate and
    // skips (see its own doc) rather than re-asking SL something already answered.
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:01:00Z") });

    // Exactly one "x" in the pool, and it carries the NEWER departure -- the stale first
    // observation must not survive alongside or instead of it.
    const matches = collector.pool.filter((j) => j.journeyId === "x");
    expect(matches).toHaveLength(1);
    expect(matches[0]!.departureTime).toBe("2026-08-10T18:42:00Z");
  });

  it("a later batch's newer arrival estimate replaces the earlier one for the same journey id", async () => {
    const { client } = scriptedClient([
      [rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T19:00:00Z")],
      [rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T19:05:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });
    // A distinct later query, not the identical one repeated -- see the previous test's
    // own comment.
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:01:00Z") });

    const matches = collector.pool.filter((j) => j.journeyId === "x");
    expect(matches).toHaveLength(1);
    expect(matches[0]!.arrivalTime).toBe("2026-08-10T19:05:00Z");
  });

  it("a journey id stays unique in the pool no matter how many batches it reappears in", async () => {
    const { client } = scriptedClient([
      [rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T19:00:00Z")],
      [rawJourney("x", "2026-08-10T18:41:00Z", "2026-08-10T19:00:00Z")],
      [rawJourney("x", "2026-08-10T18:42:00Z", "2026-08-10T19:00:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    // Three distinct queries (different request-minute buckets), each genuinely re-sent --
    // never the same query repeated three times, which would be recognized as a duplicate
    // after the first and skipped.
    for (let i = 0; i < 3; i++) {
      await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date(Date.parse("2026-08-10T07:00:00Z") + i * 60_000) });
    }

    expect(collector.pool).toHaveLength(1);
    expect(collector.pool[0]!.departureTime).toBe("2026-08-10T18:42:00Z");
  });

  it("a byte-equivalent repeat of an already-known journey does not create a duplicate pool entry", async () => {
    const identical = rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T19:00:00Z");
    const { client } = scriptedClient([[identical], [identical]]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });

    expect(collector.pool).toHaveLength(1);
  });

  it("a journey already in the pool is removed once its latest reported representation is no longer eligible", async () => {
    const { client } = scriptedClient([
      [rawJourney("x", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", 0)],
      // Same id, but a realtime update now reports more changes than MAX_CHANGES allows --
      // the LATEST information says this journey no longer qualifies, so it must not
      // linger in the pool under its former, now-stale eligible state.
      [rawJourney("x", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", MAX_CHANGES + 1)],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:00:00Z") });
    expect(collector.pool.map((j) => j.journeyId)).toEqual(["x"]);

    // A distinct later query, not the identical one repeated -- see the upsert tests'
    // own comment above.
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T07:01:00Z") });
    expect(collector.pool).toEqual([]);
  });
});

// ---- Query-scoped probe tracking: the same request-minute bucket queried with different
// SL options (transport modes, maxChanges, routeType, viaStopId) is a genuinely different
// request, never "already answered" merely because the minute coincides -- see
// CandidateCollector's own `probeKey` doc. ----

describe("CandidateCollector query-scoped probe tracking", () => {
  it("recognizes an identical (minute, transport modes, maxChanges, routeType, viaStopId) query and skips sending it again", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const options = { ...BASE_OPTIONS, departureAt: new Date("2026-08-10T18:35:00Z") };

    const first = await collector.fetchBatch(options);
    const second = await collector.fetchBatch(options);

    expect(callCount).toBe(1);
    expect(first.skipped).toBe(false);
    expect(second).toEqual({ earliestDepartureAfterCursor: null, rawJourneyIds: [], skipped: true });
  });

  it("issues a genuinely different query at the SAME minute when transport modes and maxChanges differ", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    // Narrow: mirrors a targeted NEXT search (metro-only, no changes).
    const narrow = await collector.fetchBatch({ transportModes: ["METRO"], maxChanges: 0, departureAt: anchor });
    // Broad: mirrors a later ALTERNATIVE search at the exact SAME minute.
    const broad = await collector.fetchBatch({ transportModes: ["METRO", "BUS"], maxChanges: 2, departureAt: anchor });

    expect(callCount).toBe(2);
    expect(narrow.skipped).toBe(false);
    expect(broad.skipped).toBe(false);
  });

  it("treats the same transport modes supplied in a different order as the identical probe", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    const first = await collector.fetchBatch({ transportModes: ["METRO", "BUS"], maxChanges: 1, departureAt: anchor });
    const second = await collector.fetchBatch({ transportModes: ["BUS", "METRO"], maxChanges: 1, departureAt: anchor });

    expect(callCount).toBe(1);
    expect(first.skipped).toBe(false);
    expect(second.skipped).toBe(true);
  });

  it("treats a different maxChanges at the same minute as a different probe", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    const first = await collector.fetchBatch({ transportModes: ["METRO"], maxChanges: 0, departureAt: anchor });
    const second = await collector.fetchBatch({ transportModes: ["METRO"], maxChanges: 1, departureAt: anchor });

    expect(callCount).toBe(2);
    expect(first.skipped).toBe(false);
    expect(second.skipped).toBe(false);
  });

  it("treats a different routeType at the same minute as a different probe", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    const first = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor, routeType: "leasttime" });
    const second = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor, routeType: "leastinterchange" });

    expect(callCount).toBe(2);
    expect(first.skipped).toBe(false);
    expect(second.skipped).toBe(false);
  });

  it("an explicit routeType of \"leasttime\" is recognized as identical to an omitted routeType, SL's own documented default", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    const first = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor });
    const second = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor, routeType: "leasttime" });

    expect(callCount).toBe(1);
    expect(first.skipped).toBe(false);
    expect(second.skipped).toBe(true);
  });

  it("treats a different viaStopId (including entirely absent) at the same minute as a different probe", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    const viaA = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor, viaStopId: "via-a" });
    const viaB = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor, viaStopId: "via-b" });
    const noVia = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor });

    expect(callCount).toBe(3);
    expect(viaA.skipped).toBe(false);
    expect(viaB.skipped).toBe(false);
    expect(noVia.skipped).toBe(false);
  });

  it("a broader later query at the same minute discovers a bus candidate a narrower earlier query there could never have returned", async () => {
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips(request) {
        const allowsBus = (request.transportModes ?? []).includes("BUS");
        const results = [rawJourney("metro-candidate", "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z", 0, "metro")];
        if (allowsBus) results.push(rawJourney("bus-candidate", "2026-08-10T18:40:00Z", "2026-08-10T18:45:00Z", 0, "bus"));
        return results;
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:40:00Z");

    // Simulates a targeted NEXT search, narrowed to metro only.
    await collector.fetchBatch({ transportModes: ["METRO"], maxChanges: 0, departureAt: anchor });
    expect(collector.pool.map((j) => j.journeyId)).toEqual(["metro-candidate"]);

    // Simulates a later ALTERNATIVE search at the SAME minute, with the full allowed
    // mode set -- must not be treated as "already probed" merely because the minute
    // coincides with the earlier, narrower search.
    await collector.fetchBatch({ transportModes: ["METRO", "BUS"], maxChanges: 2, departureAt: anchor });

    expect(collector.pool.map((j) => j.journeyId).sort()).toEqual(["bus-candidate", "metro-candidate"]);
  });

  it("PRIMARY retargeting: the OLD primary's own targeted query at a minute never blocks the NEW primary's differently-targeted query at that same minute", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    // The OLD primary's own NEXT_DISCOVERY, narrowed to metro.
    const oldPrimarySearch = await collector.fetchBatch({ transportModes: ["METRO"], maxChanges: 0, departureAt: anchor });
    // PRIMARY retargets to a bus; the NEW NEXT_DISCOVERY, anchored at the exact same
    // minute, is narrowed to the new primary's own (different) transport modes/transfer
    // count -- a genuinely different query, never blocked by the abandoned one.
    const newPrimarySearch = await collector.fetchBatch({ transportModes: ["BUS"], maxChanges: 1, departureAt: anchor });

    expect(callCount).toBe(2);
    expect(oldPrimarySearch.skipped).toBe(false);
    expect(newPrimarySearch.skipped).toBe(false);
  });

  it("an identical query is never sent twice, no matter how many times it is requested", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney("x", "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const options = { ...BASE_OPTIONS, departureAt: new Date("2026-08-10T18:35:00Z") };

    const results = [];
    for (let i = 0; i < 5; i++) results.push(await collector.fetchBatch(options));

    expect(callCount).toBe(1);
    expect(results.map((r) => r.skipped)).toEqual([false, true, true, true, true]);
  });

  it("the shared request budget counts only real upstream calls, never skipped duplicates", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);
    const anchor = new Date("2026-08-10T18:35:00Z");

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor }); // real
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor }); // duplicate, skipped
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: anchor }); // duplicate, skipped
    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T18:36:00Z") }); // real (different minute)

    expect(collector.batchesUsedSoFar).toBe(2);
    expect(callCount).toBe(2);
  });
});

describe("CandidateCollector.acquireUntil", () => {
  it("returns immediately without fetching anything when the initial pool already satisfies the caller", async () => {
    const { client, requests } = scriptedClient([[rawJourney("unused", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z")]]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(BASE_OPTIONS, new Date("2026-08-10T07:00:00Z"), new Date("2026-08-10T12:00:00Z"), () => true);

    expect(pool).toEqual([]);
    expect(requests).toHaveLength(0);
  });

  it("a targeted forward search finds a same-family NEXT missing from the first batch (a second, time-anchored batch)", async () => {
    // First batch (the initial acquisition, simulated as already in the pool): only PRIMARY.
    // acquireUntil's own forward search should need exactly one more batch to find NEXT.
    const { client, requests } = scriptedClient([
      [rawJourney("next-metro", "2026-08-10T18:39:00Z", "2026-08-10T18:42:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      { transportModes: ["METRO"], maxChanges: 0 },
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-10T20:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "next-metro"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["next-metro"]);
    expect(requests).toHaveLength(1);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T18:36:00.000Z");
  });

  it("a batch whose only departure falls within its own bucket falls back to advancing the cursor itself by one minute", async () => {
    const { client, requests } = scriptedClient([
      // Departs in the SAME minute as the 18:36 request below, so it is excluded from
      // earliestDepartureAfterCursor entirely (see fetchBatch's own "within its own
      // bucket" tests) -- the collector must still make progress, via the cursor-based
      // fallback, not get stuck.
      [rawJourney("too-early", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z", 3)], // also ineligible (too many changes)
      [rawJourney("the-one", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-10T20:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "the-one"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["the-one"]);
    expect(requests).toHaveLength(2);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T18:36:00.000Z");
    // One minute past the cursor itself (18:36), since the batch had nothing to offer
    // strictly beyond its own bucket -- not an arbitrary step.
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-08-10T18:37:00.000Z");
  });

  it("a hidden journey between two returned departures is discovered via an intermediate request, never skipped by jumping to just past the latest one", async () => {
    const { client, requests } = scriptedClient([
      // SL's own best-match proposals happen to be 18:40 and 19:00, leaving out the
      // genuinely relevant 18:50 journey entirely -- a realistic "best match, not
      // exhaustive pagination" response.
      [
        rawJourney("shown-early", "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z"),
        rawJourney("shown-late", "2026-08-10T19:00:00Z", "2026-08-10T19:10:00Z"),
      ],
      // Anchored AT the bucket containing the EARLIEST of the two above (18:40) --
      // probing that minute directly, never jumping a full minute past it, and never
      // anywhere near the latest (which would have been 19:00 or 19:01, skipping this
      // entirely).
      [rawJourney("hidden", "2026-08-10T18:50:30Z", "2026-08-10T19:00:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:35:00Z"),
      new Date("2026-08-10T20:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "hidden"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["shown-early", "shown-late", "hidden"]);
    expect(requests).toHaveLength(2);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T18:35:00.000Z");
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-08-10T18:40:00.000Z");
  });

  it("two consecutive batches returning the exact same journey-id set do not halt acquisition, and a later request still exposes a hidden journey", async () => {
    const sharedPair = () => [
      rawJourney("j1", "2026-08-10T10:05:00Z", "2026-08-10T10:20:00Z"),
      rawJourney("j2", "2026-08-10T10:10:00Z", "2026-08-10T10:25:00Z"),
    ];
    const { client, requests } = scriptedClient([
      sharedPair(),
      // SL repeats its own best-match answer verbatim even though the cursor genuinely
      // moved -- a realistic degenerate case (see acquireUntil's own doc: this must NOT be
      // treated as proof there is nothing left to find).
      sharedPair(),
      [rawJourney("j3", "2026-08-10T10:15:00Z", "2026-08-10T10:30:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T10:00:00Z"),
      new Date("2026-08-10T12:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "j3"),
    );

    expect(pool.map((j) => j.journeyId).sort()).toEqual(["j1", "j2", "j3"]);
    expect(requests).toHaveLength(3);
    // 10:00 probes the initial bucket, finding j1(10:05)/j2(10:10) as "after cursor" --
    // earliest is j1, so 10:05 (its own bucket) is probed next, directly, never jumped
    // past. That request re-returns the identical j1/j2 pair (now j1 sits INSIDE its own
    // just-queried 10:05 bucket, so only j2's 10:10 counts as "after cursor") -- 10:10 is
    // probed next, where j3 finally appears.
    expect(requests.map((r) => r.departureAt.toISOString())).toEqual([
      "2026-08-10T10:00:00.000Z",
      "2026-08-10T10:05:00.000Z",
      "2026-08-10T10:10:00.000Z",
    ]);
  });

  it("a same-minute departure is found by the very first batch, never excluded merely because it falls within that batch's own bucket", async () => {
    // Standing in for a targeted NEXT search anchored at PRIMARY's own floored minute
    // (18:35:05 floors to 18:35:00) -- the departure it's looking for shares that same
    // minute (18:35:40), which earliestDepartureAfterCursor deliberately excludes, but
    // `fresh`/`isSatisfied` must still see it -- that exclusion only concerns cursor
    // ADVANCEMENT, never which candidates are actually returned.
    const { client, requests } = scriptedClient([[rawJourney("same-minute-next", "2026-08-10T18:35:40Z", "2026-08-10T18:40:00Z")]]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      { transportModes: ["METRO"], maxChanges: 0 },
      new Date("2026-08-10T18:35:00Z"),
      new Date("2026-08-10T20:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "same-minute-next"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["same-minute-next"]);
    expect(requests).toHaveLength(1);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T18:35:00.000Z");
  });

  it("rolls the cursor over the local Stockholm day at 23:59 -> 00:00", async () => {
    const atLastMinute = () => [rawJourney("at-2359", "2026-08-10T21:59:00Z", "2026-08-10T22:10:00Z")]; // local 23:59 CEST
    const { client, requests } = scriptedClient([
      atLastMinute(),
      // Probing 23:59 itself directly (per the probe-before-advance rule) finds nothing
      // NEW strictly beyond that bucket -- this is what forces the cursor-advancement
      // FALLBACK path, which is the one that actually crosses the day boundary.
      atLastMinute(),
      [rawJourney("after-midnight", "2026-08-10T22:05:00Z", "2026-08-10T22:20:00Z")], // local 00:05 CEST, the following day
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T21:55:00Z"), // local 23:55
      new Date("2026-08-11T20:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "after-midnight"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["at-2359", "after-midnight"]);
    expect(requests).toHaveLength(3);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T21:55:00.000Z");
    // Probes 23:59 itself first, before ever advancing past it.
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-08-10T21:59:00.000Z");
    // Only now does the cursor cross the day boundary -- local 00:00 the FOLLOWING day,
    // not 23:60 or any other malformed rollover.
    expect(requests[2]!.departureAt.toISOString()).toBe("2026-08-10T22:00:00.000Z");
  });

  it("skips the non-existent hour across the 2026 spring-forward Stockholm DST transition", async () => {
    const atLastMinuteBeforeGap = () => [rawJourney("before-gap", "2026-03-29T00:59:00Z", "2026-03-29T01:10:00Z")]; // local 01:59 CET
    const { client, requests } = scriptedClient([
      atLastMinuteBeforeGap(),
      // Probing 01:59 itself directly finds nothing NEW strictly beyond that bucket --
      // forces the fallback path, which is what actually crosses the DST gap.
      atLastMinuteBeforeGap(),
      [rawJourney("after-gap", "2026-03-29T01:05:00Z", "2026-03-29T01:20:00Z")], // local 03:05 CEST
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, Date.parse("2026-03-29T00:00:00Z"));

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-03-29T00:55:00Z"), // local 01:55 CET
      new Date("2026-03-29T06:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "after-gap"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["before-gap", "after-gap"]);
    expect(requests).toHaveLength(3);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-03-29T00:55:00.000Z");
    // Probes 01:59 itself first, before ever advancing past it.
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-03-29T00:59:00.000Z");
    // Only now does the cursor cross the DST gap -- local 03:00 CEST, since local
    // 02:00-02:59 never happened and there is no representable request minute inside it
    // to anchor at.
    expect(requests[2]!.departureAt.toISOString()).toBe("2026-03-29T01:00:00.000Z");
  });

  it("advances correctly through the Stockholm autumn DST fold (the duplicated 02:00-02:59 hour), without duplicate or infinite bucket probing", async () => {
    const { client, requests } = scriptedClient([
      [rawJourney("at-0259-first", "2026-10-25T00:59:00Z", "2026-10-25T01:10:00Z")], // local 02:59 CEST, first occurrence
      [rawJourney("at-0259-first", "2026-10-25T00:59:00Z", "2026-10-25T01:10:00Z")],
      [rawJourney("at-0200-second", "2026-10-25T01:00:30Z", "2026-10-25T01:15:00Z")], // local 02:00:30 CET, second occurrence
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, Date.parse("2026-10-25T00:00:00Z"));

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-10-25T00:55:00Z"), // local 02:55 CEST, first occurrence
      new Date("2026-10-25T06:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "at-0200-second"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["at-0259-first", "at-0200-second"]);
    expect(requests).toHaveLength(3);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-10-25T00:55:00.000Z");
    // Probes local 02:59 (first occurrence, CEST) itself first.
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-10-25T00:59:00.000Z");
    // Stockholm's own wall clock genuinely falls back from 02:59:59 CEST to 02:00:00 CET
    // at this real instant -- the cursor correctly follows it to the SECOND occurrence of
    // 02:00, a real, later, and previously-unprobed UTC instant (never a re-visit of the
    // first occurrence's own already-probed buckets, and never stuck oscillating).
    expect(requests[2]!.departureAt.toISOString()).toBe("2026-10-25T01:00:00.000Z");
  });

  it("probes the bucket containing the earliest new departure before advancing past it", async () => {
    const { client, requests } = scriptedClient([
      [
        rawJourney("a", "2026-08-10T18:40:05Z", "2026-08-10T18:45:00Z"),
        rawJourney("b", "2026-08-10T19:00:00Z", "2026-08-10T19:10:00Z"),
      ],
      [],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.acquireUntil(BASE_OPTIONS, new Date("2026-08-10T18:35:00Z"), new Date("2026-08-10T20:00:00Z"), () => false);

    expect(requests).toHaveLength(2);
    expect(requests[0]!.departureAt.toISOString()).toBe("2026-08-10T18:35:00.000Z");
    // Probes the bucket containing 18:40:05 (18:40 itself) directly -- never 18:41 (one
    // full minute past it) and never 19:00/19:01 (past the latest returned departure).
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-08-10T18:40:00.000Z");
  });

  it("a hidden journey sharing the SAME minute as an already-shown departure is discovered once that minute is probed", async () => {
    const { client, requests } = scriptedClient([
      [rawJourney("shown", "2026-08-10T18:40:05Z", "2026-08-10T18:45:00Z")],
      [rawJourney("hidden-same-minute", "2026-08-10T18:40:40Z", "2026-08-10T18:46:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:35:00Z"),
      new Date("2026-08-10T20:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "hidden-same-minute"),
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["shown", "hidden-same-minute"]);
    expect(requests).toHaveLength(2);
    expect(requests[1]!.departureAt.toISOString()).toBe("2026-08-10T18:40:00.000Z");
  });

  it("does not re-query a request-minute bucket already probed by an earlier acquireUntil call on the same collector", async () => {
    // Simulates what PRIMARY retargeting can cause (see backend/src/routes/journeys.ts's
    // own doc): a second, unrelated search starting from an EARLIER anchor than the first
    // and climbing back up through territory the first one already covered.
    const { client, requests } = scriptedClient([
      // Call 1 probes 18:40, then 18:45.
      [rawJourney("first-probe-marker", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z")],
      [rawJourney("second-probe-marker", "2026-08-10T18:45:30Z", "2026-08-10T18:50:30Z")],
      // Call 2 starts at 18:20 and, on its very first batch, finds its earliest-after-
      // cursor departure sitting in the SAME 18:45 bucket call 1 already probed.
      [rawJourney("climb-marker", "2026-08-10T18:45:00Z", "2026-08-10T18:50:00Z")],
      [rawJourney("final-marker", "2026-08-10T18:46:30Z", "2026-08-10T18:51:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:40:00Z"),
      new Date("2026-08-10T19:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "second-probe-marker"),
    );

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:20:00Z"),
      new Date("2026-08-10T19:00:00Z"),
      (candidates) => candidates.some((j) => j.journeyId === "final-marker"),
    );

    expect(pool.map((j) => j.journeyId)).toContain("final-marker");
    expect(requests).toHaveLength(4);
    expect(requests.map((r) => r.departureAt.toISOString())).toEqual([
      "2026-08-10T18:40:00.000Z",
      "2026-08-10T18:45:00.000Z",
      "2026-08-10T18:20:00.000Z",
      // NOT 18:45 again -- already probed by the first call, so call 2 skips straight
      // past it to the next representable minute instead of re-querying it.
      "2026-08-10T18:46:00.000Z",
    ]);
  });

  it("terminates when the search cursor would exceed searchUntil, without over-fetching", async () => {
    const { client, requests } = scriptedClient([[rawJourney("irrelevant", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")]]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T19:00:00Z"), // already past `until`
      new Date("2026-08-10T18:59:00Z"),
      () => false,
    );

    expect(pool).toEqual([]);
    expect(requests).toHaveLength(0);
  });

  it("terminates safely when SL returns an empty continuation", async () => {
    const { client, requests } = scriptedClient([
      [rawJourney("only-one", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")],
      [],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-10T20:00:00Z"),
      () => false, // never satisfied -- must terminate on its own once SL goes empty
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["only-one"]);
    expect(requests).toHaveLength(2);
  });

  it("a pathological SL response that keeps returning the exact same journey regardless of the request anchor is bounded by the shared request budget, never by a same-response check", async () => {
    const identicalJourney = () => [rawJourney("same-every-time", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")];
    // The fake client would happily keep returning this forever if asked -- prove the
    // collector's own SHARED BUDGET is what eventually stops it, not a "the last two
    // responses looked the same" heuristic (that check was deliberately removed: a
    // repeated best-match response never proves a further request couldn't still expose
    // something new -- see acquireUntil's own doc).
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return identicalJourney();
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-11T18:36:00Z"), // a generous 24h horizon -- must not be what stops this
      () => false, // never satisfied -- only the shared budget can stop this
    );

    expect(pool.map((j) => j.journeyId)).toEqual(["same-every-time"]);
    // The identical journey keeps falling within its own ever-advancing bucket (its
    // departure never changes, so the fallback path re-advances the cursor by one minute
    // every single time) -- genuine, if minimal, forward progress on every request, so
    // only the hard budget cap (never a same-response check) ends this.
    expect(callCount).toBe(MAX_ACQUISITION_BATCHES);
  });

  it("respects a hard batch-count safety cap against a pathological always-advancing-by-the-minimum response", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips(request) {
        callCount++;
        // Always return exactly one new journey departing one minute after whatever was
        // requested -- genuine forward progress every single time, so only the safety cap
        // (not "no forward progress") can ever stop this.
        const departure = new Date(request.departureAt.getTime() + 60_000);
        return [rawJourney(`journey-${callCount}`, departure.toISOString(), new Date(departure.getTime() + 600_000).toISOString())];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-12T18:36:00Z"), // far beyond any reasonable batch count
      () => false, // never satisfied
    );

    // Genuine forward progress every time (a new journey, two minutes further out, on
    // every single call) -- only the hard safety cap stops this, not searchUntil or
    // no-forward-progress.
    expect(callCount).toBe(MAX_ACQUISITION_BATCHES);
  });

  it("terminates immediately if the cursor ever fails to make forward progress -- a state real Stockholm request-minute math can never actually produce", async () => {
    // Both `floorToStockholmRequestMinute` and `nextStockholmRequestMinute` always
    // strictly advance past their input (see stockholmTime.ts's own doc), so this
    // defensive branch is unreachable through any genuine sequence of requests -- proven
    // separately by that function's own tests. To still prove the BACKSTOP ITSELF works if
    // it were ever reached, this one test mocks the module (unlike every other test in
    // this file, which relies on the real implementation) and dynamically re-imports
    // CandidateCollector so the mock applies only here, never to the statically-imported
    // collector every other test uses. Both functions are frozen to the SAME constant,
    // never just `nextStockholmRequestMinute` alone: `nextCursorAfter` probes a fresh
    // candidate bucket via `floorToStockholmRequestMinute` directly whenever one is
    // available, without ever calling `nextStockholmRequestMinute` at all -- freezing only
    // the latter would not reach the code path this test needs to exercise.
    vi.resetModules();
    vi.doMock("../src/lib/stockholmTime.js", async (importOriginal) => {
      const actual = await importOriginal<typeof import("../src/lib/stockholmTime.js")>();
      const frozenBucket = new Date("2026-08-10T18:36:00Z");
      return {
        ...actual,
        floorToStockholmRequestMinute: () => frozenBucket,
        nextStockholmRequestMinute: () => frozenBucket,
      };
    });

    const { CandidateCollector: PatchedCandidateCollector } = await import("../src/services/candidateCollector.js");
    const { client, requests } = scriptedClient([
      [rawJourney("a", "2026-08-10T18:40:00Z", "2026-08-10T18:50:00Z")],
      [rawJourney("b", "2026-08-10T18:50:00Z", "2026-08-10T19:00:00Z")],
    ]);
    const collector = new PatchedCandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    const pool = await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-11T18:36:00Z"),
      () => false,
    );

    expect(pool.map((j: { journeyId: string }) => j.journeyId)).toEqual(["a"]);
    expect(requests).toHaveLength(1);

    vi.doUnmock("../src/lib/stockholmTime.js");
    vi.resetModules();
  });

  it("accepts a function for `until`, re-read before every batch, so a caller can shrink the remaining search window mid-acquisition", async () => {
    const { client, requests } = scriptedClient([
      [rawJourney("first", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")],
      [rawJourney("second", "2026-08-10T18:41:00Z", "2026-08-10T18:45:00Z")],
    ]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    // Shrinks to 18:36:30 (still within "first"'s own request minute) the moment "first" is
    // seen -- a caller re-deriving its own domain state (e.g. a newly-discovered NEXT) can
    // narrow the remaining window this way without needing its own separate acquisition loop.
    let bound = new Date("2026-08-10T20:00:00Z");
    const pool = await collector.acquireUntil(BASE_OPTIONS, new Date("2026-08-10T18:36:00Z"), () => bound, (candidates) => {
      if (candidates.some((j) => j.journeyId === "first")) bound = new Date("2026-08-10T18:36:30Z");
      return false;
    });

    expect(pool.map((j) => j.journeyId)).toEqual(["first"]);
    // The second batch is never fetched -- the cursor after "first" (18:37:00, the next
    // representable request minute) already exceeds the shrunk 18:36:30 bound.
    expect(requests).toHaveLength(1);
  });
});

describe("CandidateCollector shared request budget", () => {
  it("batchesUsedSoFar and budgetExhausted reflect real requests sent, not batches merely attempted", async () => {
    const { client } = scriptedClient([[rawJourney("a", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")], []]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    expect(collector.batchesUsedSoFar).toBe(0);
    expect(collector.budgetExhausted).toBe(false);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T18:36:00Z") });
    expect(collector.batchesUsedSoFar).toBe(1);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T18:37:00Z") });
    expect(collector.batchesUsedSoFar).toBe(2);
    expect(collector.budgetExhausted).toBe(false);
  });

  it("fetchBatch sends no request and returns an empty result once the budget is already exhausted", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips() {
        callCount++;
        return [rawJourney(`journey-${callCount}`, "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    // Distinct queries (a different request-minute bucket each time) so every single one
    // genuinely spends shared budget -- the identical query repeated would be recognized
    // as a duplicate and skipped after the first, never actually exhausting anything.
    for (let i = 0; i < MAX_ACQUISITION_BATCHES; i++) {
      await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date(Date.parse("2026-08-10T18:36:00Z") + i * 60_000) });
    }
    expect(collector.budgetExhausted).toBe(true);
    expect(callCount).toBe(MAX_ACQUISITION_BATCHES);

    const batch = await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T20:00:00Z") });

    expect(batch).toEqual({ earliestDepartureAfterCursor: null, rawJourneyIds: [], skipped: false });
    // No new request was sent to SL -- the budget guard short-circuits before `client.trips`,
    // for a query that was never even probed before (proving this is genuinely the budget
    // guard, not the separate duplicate-query skip).
    expect(callCount).toBe(MAX_ACQUISITION_BATCHES);
  });

  it("is shared across two separate acquireUntil calls on the same instance -- NEXT and ALTERNATIVE acquisition draw from ONE budget, not one each", async () => {
    let callCount = 0;
    const client: SlJourneyPlannerClient = {
      async searchStops() {
        return [];
      },
      async trips(request) {
        callCount++;
        // Always genuine forward progress, so only the shared budget can stop this.
        const departure = new Date(request.departureAt.getTime() + 60_000);
        return [rawJourney(`journey-${callCount}`, departure.toISOString(), new Date(departure.getTime() + 600_000).toISOString())];
      },
    };
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    // First "phase" (standing in for NEXT acquisition) spends most of the budget.
    await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-12T18:36:00Z"),
      () => collector.batchesUsedSoFar >= MAX_ACQUISITION_BATCHES - 2,
    );
    expect(collector.batchesUsedSoFar).toBe(MAX_ACQUISITION_BATCHES - 2);

    // A second, independent "phase" (standing in for ALTERNATIVE acquisition) on the SAME
    // collector must only get the two requests left in the shared budget, never a fresh 30.
    await collector.acquireUntil(
      BASE_OPTIONS,
      new Date("2026-08-10T18:36:00Z"),
      new Date("2026-08-12T18:36:00Z"),
      () => false,
    );

    expect(collector.batchesUsedSoFar).toBe(MAX_ACQUISITION_BATCHES);
    expect(collector.budgetExhausted).toBe(true);
    expect(callCount).toBe(MAX_ACQUISITION_BATCHES);
  });

  it("a normal one-request case leaves the budget almost entirely untouched", async () => {
    const { client } = scriptedClient([[rawJourney("only", "2026-08-10T18:36:00Z", "2026-08-10T18:40:00Z")]]);
    const collector = new CandidateCollector(client, ORIGIN, DESTINATION, REQUESTED_AT_MILLIS);

    await collector.fetchBatch({ ...BASE_OPTIONS, departureAt: new Date("2026-08-10T18:36:00Z") });

    expect(collector.batchesUsedSoFar).toBe(1);
    expect(collector.budgetExhausted).toBe(false);
  });
});
