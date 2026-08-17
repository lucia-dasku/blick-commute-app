/**
 * A standalone, human-run operational audit — NOT part of the request-serving path, NOT run by
 * the test suite, NOT run automatically by anything. Run this exactly once real GTFS Regional
 * access is available, review its results, and only THEN consider setting `TRAFIKLAB_API_KEY` in
 * a real deployment — see `docs/api-contract.md`, "Segment-parsing relevance enhancement", and
 * `services/lineTopologyDirectory.ts`'s own `createGtfsStopIdResolver` doc for the full context
 * this script exists to close.
 *
 * ## What this proves (or disproves)
 *
 * `createGtfsStopIdResolver` treats GTFS Regional's own `stop_times.stop_id` as identical to SL
 * Transport's own `/v1/stop-points` `gid` field — a genuine, evidence-backed HYPOTHESIS (matching
 * `9022`-prefixed "Stop point" class ids in both, per Trafiklab support documentation, both
 * sourced from SL's own pubtrans/NOPTIS system), never verified against a real downloaded feed.
 * This script performs that verification: download the real feed, collect every unique `stop_id`
 * any supported SL route's own trips actually use, and check each one against a live
 * `StopPointDirectory` snapshot.
 *
 * ## What to do with the result
 *
 * - A near-100% resolution rate across every mode, including T-Centralen/Slussen and ordinary
 *   stops, is what "the bridge holds" looks like — safe to proceed with `TRAFIKLAB_API_KEY`
 *   configured in a real deployment.
 * - A significant unresolved/ambiguous rate, or a rate that varies suspiciously by mode, means the
 *   hypothesis does NOT hold as assumed — do NOT set `TRAFIKLAB_API_KEY` in a real deployment
 *   until the actual relationship is understood; report the finding instead of proceeding anyway.
 *
 * ## Usage
 *
 *   TRAFIKLAB_API_KEY=your-key npx tsx scripts/verifyGtfsStopIdentityBridge.ts
 *
 * (or `npm run verify:gtfs-bridge` — see package.json)
 */
import { config } from "../src/config/env.js";
import { createSlTransportClient } from "../src/services/slTransportClient.js";
import { createStopPointDirectory } from "../src/services/stopPointDirectory.js";
import { createGtfsFeedSource, transportModeForGtfsRouteType } from "../src/services/lineTopologyDirectory.js";
import { parseGtfsRoutes, parseGtfsStopTimes, parseGtfsTrips } from "../src/services/gtfsCsvParser.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";

// Real, human-recognizable stations to call out individually in the report -- matched by
// substring against each raw stop_times.txt row's OWN stop_id-adjacent context is not available
// (stop_times.txt carries no name), so these are instead cross-referenced by NAME against the
// live StopPointDirectory snapshot after the fact (see "named station spot-checks" below).
const NAMED_SPOT_CHECKS = ["t-centralen", "slussen"];

interface ModeBucket {
  mode: string;
  total: number;
  resolved: number;
  unresolved: number;
  ambiguous: number;
}

async function main(): Promise<void> {
  const apiKey = config.trafiklabApiKey;
  if (!apiKey) {
    console.error("TRAFIKLAB_API_KEY is not set. This script cannot run without it -- see this file's own doc.");
    process.exitCode = 1;
    return;
  }

  console.log("Step 1/5: downloading and extracting the real GTFS Regional SL feed...");
  const downloadStart = Date.now();
  const feedSource = createGtfsFeedSource(apiKey);
  const result = await feedSource.fetchFeedFiles();
  const downloadMs = Date.now() - downloadStart;
  if (result.status !== "OK") {
    console.error(`Unexpected NOT_MODIFIED on a cold, validator-less request -- treating as a hard failure.`);
    process.exitCode = 1;
    return;
  }
  const rawSizes = {
    routes: Buffer.byteLength(result.files.routesCsv, "utf8"),
    trips: Buffer.byteLength(result.files.tripsCsv, "utf8"),
    stopTimes: Buffer.byteLength(result.files.stopTimesCsv, "utf8"),
  };
  console.log(
    `  downloaded + extracted in ${downloadMs}ms -- routes.txt ${(rawSizes.routes / 1024).toFixed(0)}KB, ` +
      `trips.txt ${(rawSizes.trips / 1024).toFixed(0)}KB, stop_times.txt ${(rawSizes.stopTimes / 1024 / 1024).toFixed(1)}MB`,
  );

  console.log("Step 2/5: parsing CSV + observing real route_type values...");
  const parseStart = Date.now();
  const routes = parseGtfsRoutes(result.files.routesCsv);
  const trips = parseGtfsTrips(result.files.tripsCsv);
  const stopTimes = parseGtfsStopTimes(result.files.stopTimesCsv);
  const parseMs = Date.now() - parseStart;
  console.log(`  parsed in ${parseMs}ms -- ${routes.length} routes, ${trips.length} trips, ${stopTimes.length} stop_times rows`);

  const routeTypeCounts = new Map<number, number>();
  const routeTypeExamples = new Map<number, string>();
  const unmappedRouteTypes = new Set<number>();
  for (const route of routes) {
    routeTypeCounts.set(route.routeType, (routeTypeCounts.get(route.routeType) ?? 0) + 1);
    if (!routeTypeExamples.has(route.routeType)) routeTypeExamples.set(route.routeType, route.shortName);
    if (transportModeForGtfsRouteType(route.routeType) == null) unmappedRouteTypes.add(route.routeType);
  }
  console.log("  real route_type values observed (value: count, example route_short_name, mapped mode):");
  for (const [routeType, count] of [...routeTypeCounts.entries()].sort((a, b) => a[0] - b[0])) {
    const mapped = transportModeForGtfsRouteType(routeType) ?? "UNMAPPED";
    console.log(`    ${routeType}: ${count} routes (e.g. "${routeTypeExamples.get(routeType)}") -> ${mapped}`);
  }
  if (unmappedRouteTypes.size > 0) {
    console.warn(
      `  WARNING: ${unmappedRouteTypes.size} route_type value(s) have no mapping in transportModeForGtfsRouteType ` +
        `(${[...unmappedRouteTypes].join(", ")}) -- every route using one of these will simply have no topology, ` +
        "degrading safely to LINE_RELEVANT, but review whether this mapping needs extending.",
    );
  }

  console.log("Step 3/5: collecting every unique stop_id used by a route this backend maps to a known mode...");
  const supportedRouteIds = new Set(routes.filter((r) => transportModeForGtfsRouteType(r.routeType) != null).map((r) => r.routeId));
  const supportedTripIds = new Set(trips.filter((t) => supportedRouteIds.has(t.routeId)).map((t) => t.tripId));
  const uniqueStopIds = new Set<string>();
  for (const st of stopTimes) {
    if (supportedTripIds.has(st.tripId)) uniqueStopIds.add(st.stopId);
  }
  console.log(`  ${uniqueStopIds.size} unique stop_id values in use across ${supportedTripIds.size} supported trips`);

  console.log("Step 4/5: loading the live StopPointDirectory snapshot and resolving every stop_id...");
  const slTransportClient = createSlTransportClient();
  const stopPointDirectory = createStopPointDirectory(slTransportClient, new InMemoryCache(), new InMemoryLock(), new InFlightDeduper());
  const resolveStart = Date.now();
  const resolutions = await stopPointDirectory.resolveStopPointGids([...uniqueStopIds]);
  const resolveMs = Date.now() - resolveStart;

  let resolvedCount = 0;
  let unresolvedCount = 0;
  let ambiguousCount = 0;
  const unresolvedExamples: string[] = [];
  const ambiguousExamples: string[] = [];
  for (const [gid, resolution] of resolutions) {
    if (resolution.status === "RESOLVED") resolvedCount++;
    else if (resolution.status === "AMBIGUOUS") {
      ambiguousCount++;
      if (ambiguousExamples.length < 10) ambiguousExamples.push(gid);
    } else {
      unresolvedCount++;
      if (unresolvedExamples.length < 10) unresolvedExamples.push(gid);
    }
  }

  console.log(`  resolved in ${resolveMs}ms`);
  console.log(`\n=== RESULT ===`);
  console.log(`Total unique stop_ids checked: ${uniqueStopIds.size}`);
  console.log(`Resolved:    ${resolvedCount} (${((resolvedCount / uniqueStopIds.size) * 100).toFixed(1)}%)`);
  console.log(`Unresolved:  ${unresolvedCount} (${((unresolvedCount / uniqueStopIds.size) * 100).toFixed(1)}%)`);
  console.log(`Ambiguous:   ${ambiguousCount} (${((ambiguousCount / uniqueStopIds.size) * 100).toFixed(1)}%)`);
  if (unresolvedExamples.length > 0) console.log(`  unresolved examples: ${unresolvedExamples.join(", ")}`);
  if (ambiguousExamples.length > 0) console.log(`  ambiguous examples: ${ambiguousExamples.join(", ")}`);

  console.log("\nBy mode:");
  const byMode = new Map<string, ModeBucket>();
  const modeByRouteId = new Map<string, string>();
  for (const route of routes) {
    const mode = transportModeForGtfsRouteType(route.routeType);
    if (mode != null) modeByRouteId.set(route.routeId, mode);
  }
  const modeByTripId = new Map<string, string>();
  for (const trip of trips) {
    const mode = modeByRouteId.get(trip.routeId);
    if (mode != null) modeByTripId.set(trip.tripId, mode);
  }
  const stopIdsByMode = new Map<string, Set<string>>();
  for (const st of stopTimes) {
    const mode = modeByTripId.get(st.tripId);
    if (mode == null) continue;
    let set = stopIdsByMode.get(mode);
    if (!set) {
      set = new Set();
      stopIdsByMode.set(mode, set);
    }
    set.add(st.stopId);
  }
  for (const [mode, stopIds] of stopIdsByMode) {
    const bucket: ModeBucket = { mode, total: 0, resolved: 0, unresolved: 0, ambiguous: 0 };
    for (const stopId of stopIds) {
      const resolution = resolutions.get(stopId);
      bucket.total++;
      if (resolution?.status === "RESOLVED") bucket.resolved++;
      else if (resolution?.status === "AMBIGUOUS") bucket.ambiguous++;
      else bucket.unresolved++;
    }
    byMode.set(mode, bucket);
  }
  for (const bucket of byMode.values()) {
    const pct = ((bucket.resolved / bucket.total) * 100).toFixed(1);
    console.log(`  ${bucket.mode}: ${bucket.resolved}/${bucket.total} resolved (${pct}%), ${bucket.unresolved} unresolved, ${bucket.ambiguous} ambiguous`);
  }

  console.log("\nStep 5/5: named station spot-checks (T-Centralen, Slussen)...");
  for (const name of NAMED_SPOT_CHECKS) {
    const stopAreaIds = await stopPointDirectory.findStopAreaIdsByName(name);
    console.log(`  "${name}" -> StopArea id(s) ${stopAreaIds.join(", ") || "(none found)"}`);
  }
  console.log(
    "  (cross-reference these StopArea ids manually against which GTFS stop_ids resolved to them, " +
      "if a deeper manual check is warranted beyond the aggregate percentages above.)",
  );

  console.log("\n=== CONCLUSION ===");
  if (resolvedCount === uniqueStopIds.size) {
    console.log("100% resolution. The GTFS stop_id <-> SL Transport gid bridge holds for every stop_id checked.");
  } else if (resolvedCount / uniqueStopIds.size >= 0.95) {
    console.log(
      "Resolution is high (>=95%) but not complete -- review the unresolved/ambiguous examples above before " +
        "trusting this bridge in production. A small residual gap may be acceptable (affected lines simply stay " +
        "PARTIAL and fall back to LINE_RELEVANT) but should be understood, not ignored.",
    );
  } else {
    console.log(
      "Resolution is NOT high enough to trust this bridge as-is. Do NOT set TRAFIKLAB_API_KEY in a real " +
        "deployment based on this result -- the hypothesis in createGtfsStopIdResolver's own doc does not hold " +
        "as assumed. Investigate the actual relationship (or report this as a blocker) before proceeding.",
    );
  }
}

main().catch((err) => {
  console.error("verifyGtfsStopIdentityBridge failed:", err);
  process.exitCode = 1;
});
