import { config } from "../config/env.js";
import { AppError } from "../lib/errors.js";
import type { Cache } from "../lib/cache.js";
import type { DistributedLock } from "../lib/distributedLock.js";
import type { InFlightDeduper } from "../lib/cache.js";
import type { Site } from "../models/site.js";
import type { StopAreaNameIndex, StopPointDirectory } from "./stopPointDirectory.js";
import { parseGtfsRoutes, parseGtfsStopTimes, parseGtfsTrips, type GtfsRoute, type GtfsStopTime, type GtfsTrip } from "./gtfsCsvParser.js";
import { extractNamedFilesFromZip } from "./gtfsZipExtractor.js";
import {
  buildLineTopologyGraph,
  resolveSegmentEdges,
  type LineTopologyGraph,
  type StopAreaEdgeKey,
  type TripStopSequenceEntry,
} from "../domain/lineTopologyGraph.js";
import { resolveEndpointStopAreaOnLine } from "../domain/requestedCorridor.js";

/**
 * One GTFS Regional `stop_times.stop_id`'s resolution to the SL-Transport-namespace StopArea id
 * the rest of this backend's disruption-relevance code already operates in. Deliberately a
 * NARROWER shape than `StopPointResolution`/`StopAreaIdentityResolution` (no StopPoint-level
 * detail at all) — line topology only ever needs StopArea precision.
 */
export type GtfsStopIdResolution = { status: "RESOLVED"; stopAreaId: number } | { status: "AMBIGUOUS" } | { status: "UNRESOLVED" };

/**
 * This feature's SECOND identity bridge — a GTFS Regional `stop_id` (SL's own operator-local id
 * within that specific feed) to the SL-Transport-namespace StopArea id. Deliberately BATCH-shaped
 * (never a single-id `resolveStopArea(id)` — see `createGtfsStopIdResolver`'s own doc for why an
 * earlier per-call-resolve version of this interface was replaced): a daily topology refresh
 * needs to resolve every unique `stop_id` its included routes/trips actually use, and calling a
 * per-id method thousands of times sequentially during that one refresh would be exactly the kind
 * of unbounded per-stop upstream/cache traffic this feature's own quota-protection design (see
 * `ATTEMPT_CLAIM_KEY`'s own doc) exists to prevent elsewhere.
 */
export interface GtfsStopIdResolver {
  resolveMany(gtfsStopIds: readonly string[]): Promise<Map<string, GtfsStopIdResolution>>;
}

/**
 * The only implementation SAFE to wire when the identity bridge below has not been independently,
 * empirically verified against real live GTFS Regional data — always resolves every id to
 * `"UNRESOLVED"`, never a guess. Because `LineTopologyDirectory` already treats "no StopArea
 * evidence for this line's own stops" as ordinary topology-unavailable (every line ends up
 * `completeness: "PARTIAL"` — see `buildLineTopologyIndex`'s own doc), wiring this placeholder in
 * front of the segment-parsing enhancement makes it safely, permanently inert: every
 * `resolveSegment` call falls through to `UNRESOLVED`, and `disruptionRelevance.ts` keeps
 * returning the existing `LINE_RELEVANT` result exactly as it did before this feature existed.
 */
export function createUnprovenGtfsStopIdResolver(): GtfsStopIdResolver {
  return {
    async resolveMany(gtfsStopIds) {
      return new Map(gtfsStopIds.map((id) => [id, { status: "UNRESOLVED" as const }]));
    },
  };
}

/**
 * The REAL implementation of the GTFS-stop-id-to-StopArea bridge, built on
 * `StopPointDirectory.resolveStopPointGids` (never a second, independent stop-point client or
 * cache — see that method's own doc). This is genuine, tested production code — but whether it is
 * actually SAFE to trust in a live deployment rests on an identity hypothesis that remains
 * genuinely unverified against real data (see below); `app.ts` is what decides whether this or
 * {@link createUnprovenGtfsStopIdResolver} is actually wired in.
 *
 * ## The identity bridge, and its honest limits
 *
 * SL Transport's own `RawStopPoint.gid` and GTFS Regional's own `stop_times.stop_id` (for SL)
 * are HYPOTHESIZED to be the exact same value — checked live against Trafiklab's own current
 * documentation and support forum (2026-08-17): Trafiklab support staff (Kenneth, in the
 * "Stoppställenummer i GTFS Regional och GTFS Sverige 2" thread on support.trafiklab.se) confirm
 * GTFS Regional's own 16-digit SL ids use a class-id prefix scheme (`9021` = Stop Area, `9022` =
 * Stop point) and that "these ids are SL's own designations and come from their internal system
 * pubtrans, which builds on NOPTIS" (translated). SL Transport's own `/v1/stop-points` `gid`
 * field uses the EXACT SAME `9022`-prefixed shape. Both being described as originating from the
 * SAME SL-internal pubtrans/NOPTIS source system, using the SAME class-id numbering convention,
 * is genuine, meaningful evidence — but it stops short of an explicit Trafiklab statement
 * guaranteeing byte-for-byte interchangeability, and general Trafiklab documentation elsewhere is
 * deliberately non-specific ("GTFS Regional does not share stop ids with any other dataset" —
 * most plausibly a warning about CROSS-OPERATOR id reuse, not this same-operator,
 * same-source-system case, but not explicitly disambiguated either way).
 *
 * This is therefore a genuine, evidence-backed HYPOTHESIS, not a proven fact. Proving it requires
 * downloading the REAL current SL GTFS Regional feed and cross-referencing a representative
 * sample of its own real `stop_id`s against `StopPointDirectory`'s own real `gid` index across
 * every supported mode (metro, commuter rail, tram, bus, ferry), including T-Centralen, Slussen,
 * and ordinary non-interchange stops — see `scripts/verifyGtfsStopIdentityBridge.ts`, written to
 * perform exactly that audit and report resolved/unresolved/ambiguous counts, but which this
 * deployment could not run: `TRAFIKLAB_API_KEY` remains unconfigured. Until that script has been
 * run once real access exists, and its results reviewed, this resolver must not be trusted as
 * "production-verified" — see `app.ts`'s own wiring doc for the resulting decision.
 */
export function createGtfsStopIdResolver(stopPointDirectory: Pick<StopPointDirectory, "resolveStopPointGids">): GtfsStopIdResolver {
  return {
    async resolveMany(gtfsStopIds) {
      const resolutions = await stopPointDirectory.resolveStopPointGids(gtfsStopIds);
      const result = new Map<string, GtfsStopIdResolution>();
      for (const id of gtfsStopIds) {
        const resolution = resolutions.get(id);
        if (resolution == null || resolution.status === "UNRESOLVED") result.set(id, { status: "UNRESOLVED" });
        else if (resolution.status === "AMBIGUOUS") result.set(id, { status: "AMBIGUOUS" });
        else result.set(id, { status: "RESOLVED", stopAreaId: resolution.stopAreaId });
      }
      return result;
    },
  };
}

/**
 * Trafiklab's GTFS Regional/GTFS Sverige feeds do NOT use GTFS's basic one-digit `route_type`
 * values at all — confirmed directly against Trafiklab's own current documentation
 * (trafiklab.se/api/gtfs-datasets/overview/extensions/, checked live 2026-08-16): "GTFS Sverige
 * and GTFS Regional does not use the standard one digit GTFS route types, but only uses extended
 * route types." An earlier version of this mapping used the basic scheme ({TRAM:0, METRO:1,
 * TRAIN:2, BUS:3, FERRY:4}) — that was never verified against real Trafiklab data and, per this
 * documentation, is simply wrong for this feed: it would never match a single real SL route.
 *
 * Trafiklab's own documented extended-route-type table explicitly cites `route_type` 401 ("Metro
 * Service") with Stockholm's own Tunnelbanan (SL Metro) as the worked example — the strongest
 * available evidence for SL's real METRO code short of an actual feed download, which remains
 * blocked on the missing `TRAFIKLAB_API_KEY` credential.
 *
 * Ranges below follow Google's own canonical extended-route-types reference
 * (developers.google.com/transit/gtfs/reference/extended-route-types, checked live 2026-08-16),
 * narrowed to exactly the code families Trafiklab's own documentation confirms it actually emits
 * for Swedish regional data:
 * - Railway Service (100–109) → `TRAIN`: SL Pendeltåg/Roslagsbanan land somewhere in this family
 *   (most likely 106 "Regional Rail Service" or 109 "Suburban Railway" — neither could be pinned
 *   down further without a real feed, so the whole documented range is accepted rather than
 *   guessing one exact code).
 * - Urban Railway Service (400–405) → `METRO`: 401 is Trafiklab-confirmed for SL; 402
 *   ("Underground Service") is included as the same family in case SL ever emits it instead.
 * - Bus Service (700–716) → `BUS`: every documented subtype, including rail-replacement service
 *   (714, which Trafiklab's own changelog confirms is actively used in Swedish regional feeds).
 * - Tram Service (900–906) → `TRAM`: covers SL's light rail (Tvärbanan, Lidingöbanan,
 *   Nockebybanan, Spårväg City).
 * - Water Transport Service (1000–1099, plus 1200, Google's own canonical dedicated "Ferry
 *   Service" code) → `FERRY`: covers SL Pendelbåt/Waxholmsbolaget; Trafiklab's own documentation
 *   only explicitly cites 1000, but 1200 is included defensively since it is the more specific
 *   canonical code for exactly this service.
 *
 * A code outside every one of these ranges matches no `TransportMode` at all — this is NOT a
 * bug: it safely contributes no entries to the compact topology index, exactly like a line the
 * feed genuinely has no data for, never a crash and never a wrong-mode guess. This is
 * deliberately narrower than Google's full extended table (Coach Service 200–209, Aerial Lift
 * 1300, Funicular 1400, Taxi 1500s, etc.) — SL's own network has no service in those families;
 * adding them now would be an unverified guess this feature's own spec explicitly rules out.
 */
export function transportModeForGtfsRouteType(routeType: number): string | null {
  if (routeType >= 100 && routeType <= 109) return "TRAIN";
  if (routeType >= 400 && routeType <= 405) return "METRO";
  if (routeType >= 700 && routeType <= 716) return "BUS";
  if (routeType >= 900 && routeType <= 906) return "TRAM";
  if ((routeType >= 1000 && routeType <= 1099) || routeType === 1200) return "FERRY";
  return null;
}

export interface GtfsFeedFiles {
  routesCsv: string;
  tripsCsv: string;
  stopTimesCsv: string;
}

/** Conditional-request validators from the PREVIOUS successful fetch — sent back on the next
 * attempt so an unchanged feed costs one cheap `304` rather than a full re-download/re-parse. See
 * `GtfsFeedSource.fetchFeedFiles`'s own doc. */
export interface GtfsFeedValidators {
  etag?: string;
  lastModified?: string;
}

export type GtfsFeedFetchResult = { status: "NOT_MODIFIED" } | { status: "OK"; files: GtfsFeedFiles; validators: GtfsFeedValidators };

const GTFS_ROUTES_FILE = "routes.txt";
const GTFS_TRIPS_FILE = "trips.txt";
const GTFS_STOP_TIMES_FILE = "stop_times.txt";

/**
 * Fetches and extracts the SL operator's own GTFS Regional feed — a ZIP archive (`GET
 * https://opendata.samtrafiken.se/gtfs/{operator}/{operator}.zip?key=...` — verified live against
 * Trafiklab's own current documentation, 2026-08-16) containing (among others) `routes.txt`,
 * `trips.txt`, and `stop_times.txt`. Extraction is real (`gtfsZipExtractor.ts` — a small,
 * dependency-free, in-memory ZIP Central Directory reader), entirely in memory (the response body
 * is read via `Response.arrayBuffer()`, never written to a temp file) — appropriate for Vercel's
 * own request/response lifecycle. Exercised against real, valid ZIP archives
 * (`gtfsZipExtractor.test.ts`, independently cross-checked against .NET's own
 * `System.IO.Compression.ZipFile` reader) but NOT against the real live Trafiklab feed itself,
 * since `TRAFIKLAB_API_KEY` remains unconfigured in this deployment.
 *
 * [previousValidators], when supplied, is sent as `If-None-Match`/`If-Modified-Since` (ETag
 * preferred when both are known — standard conditional-request practice); a `304 Not Modified`
 * response short-circuits to `{status: "NOT_MODIFIED"}` WITHOUT downloading or extracting
 * anything — see `createLineTopologyDirectory`'s own "Freshness and conditional requests" doc for
 * why this still counts as one real HTTP request against Trafiklab's own quota, never treated as
 * free.
 */
export interface GtfsFeedSource {
  fetchFeedFiles(previousValidators?: GtfsFeedValidators): Promise<GtfsFeedFetchResult>;
}

export function createGtfsFeedSource(apiKey: string | undefined = config.trafiklabApiKey, operator = "sl"): GtfsFeedSource {
  return {
    async fetchFeedFiles(previousValidators) {
      if (!apiKey) {
        throw new AppError("UPSTREAM_ERROR", "GTFS Regional is not configured (TRAFIKLAB_API_KEY is not set)");
      }
      const headers: Record<string, string> = {};
      if (previousValidators?.etag) headers["If-None-Match"] = previousValidators.etag;
      else if (previousValidators?.lastModified) headers["If-Modified-Since"] = previousValidators.lastModified;

      const response = await fetch(`https://opendata.samtrafiken.se/gtfs/${operator}/${operator}.zip?key=${apiKey}`, { headers });
      if (response.status === 304) return { status: "NOT_MODIFIED" };
      if (!response.ok) {
        throw new AppError("UPSTREAM_ERROR", `GTFS Regional fetch failed with status ${response.status}`);
      }

      const zipBytes = new Uint8Array(await response.arrayBuffer());
      const files = extractNamedFilesFromZip(zipBytes, [GTFS_ROUTES_FILE, GTFS_TRIPS_FILE, GTFS_STOP_TIMES_FILE]);
      const validators: GtfsFeedValidators = {};
      const etag = response.headers.get("etag");
      const lastModified = response.headers.get("last-modified");
      if (etag) validators.etag = etag;
      if (lastModified) validators.lastModified = lastModified;
      return {
        status: "OK",
        files: { routesCsv: files[GTFS_ROUTES_FILE]!, tripsCsv: files[GTFS_TRIPS_FILE]!, stopTimesCsv: files[GTFS_STOP_TIMES_FILE]! },
        validators,
      };
    },
  };
}

/**
 * A PERMANENTLY inert `GtfsFeedSource` — never attempts a network call under any circumstances,
 * including when `TRAFIKLAB_API_KEY` IS configured. `app.ts` wires this instead of the real
 * {@link createGtfsFeedSource} until the GTFS-stop-id identity bridge
 * ({@link createGtfsStopIdResolver}) has been independently verified against real live data (see
 * that function's own doc) — wiring even a WORKING feed source together with
 * {@link createUnprovenGtfsStopIdResolver} would still be CORRECTNESS-safe (an always-UNRESOLVED
 * resolver keeps every line `"PARTIAL"`, so `resolveSegment` still only ever reaches `UNRESOLVED`)
 * but NOT quota-safe: it would download and parse the whole feed for an index that can never
 * contain a single resolved entry, for no benefit. Swap this for the real `createGtfsFeedSource`
 * in `app.ts` in the SAME change that swaps in the real, verified `GtfsStopIdResolver` — never
 * independently.
 */
export function createUnavailableGtfsFeedSource(): GtfsFeedSource {
  return {
    async fetchFeedFiles() {
      throw new AppError(
        "UPSTREAM_ERROR",
        "GTFS Regional line topology is not yet enabled in this deployment (the GTFS stop-identity bridge remains unproven — see GtfsStopIdResolver's own doc)",
      );
    },
  };
}

export type LineSegmentResolution =
  | { status: "RESOLVED"; stopAreaA: number; stopAreaB: number; edges: ReadonlySet<StopAreaEdgeKey>; orderedStopAreaIds: readonly number[] }
  | { status: "AMBIGUOUS" }
  | { status: "UNRESOLVED" };

export interface LineTopologyDirectory {
  /**
   * Resolves a parsed `"mellan A och B"` candidate ([stopAName]/[stopBName], already
   * conservatively normalized by `journeySegmentParser.ts`) against [transportMode] +
   * [lineDesignation]'s own real static topology. Never throws — every failure mode (no
   * `TRAFIKLAB_API_KEY` configured, upstream fetch failure, an unrecognized `transportMode`, a
   * line the current feed has no data for, that line's own topology being `"PARTIAL"` (see
   * `buildLineTopologyIndex`'s own doc), stale/unvalidated topology (see this module's own
   * "Freshness" doc), a station name that doesn't resolve, an ambiguous multi-path segment) all
   * collapse to this same `LineSegmentResolution`'s own `"UNRESOLVED"` or `"AMBIGUOUS"` state,
   * which `disruptionRelevance.ts` already treats identically: keep the existing `LINE_RELEVANT`
   * result. A WARM, validated call is a cheap lookup: no GTFS re-parsing, no `stop_times` rescan,
   * no additional `GtfsStopIdResolver` calls.
   */
  resolveSegment(transportMode: string, lineDesignation: string, stopAName: string, stopBName: string): Promise<LineSegmentResolution>;

  /**
   * The requested-corridor counterpart to `resolveSegment` (see `domain/requestedCorridor.ts`'s
   * own `isRequestedCorridorTrusted` doc for how a caller decides whether to actually trust the
   * result): resolves [originSite]/[destinationSite] — already-verified SL Transport `Site`s —
   * onto [transportMode] + [lineDesignation]'s own SAME cached topology `resolveSegment` uses.
   * Same never-throws contract, and same warm-lookup cost, as `resolveSegment`.
   *
   * A RESOLVED result's own `orderedStopAreaIds` is ALWAYS oriented [originSite] first,
   * [destinationSite] last — the underlying GTFS trip pattern may internally be stored in either
   * direction, but the requested JOURNEY has a real direction, and `isRequestedCorridorTrusted`'s
   * own exact-prefix/exact-suffix check depends on that orientation being correct, not merely on
   * `stopAreaA`/`stopAreaB` matching origin/destination.
   */
  resolveEndpointsCorridor(transportMode: string, lineDesignation: string, originSite: Site, destinationSite: Site): Promise<LineSegmentResolution>;
}

/** {@link LineTopologyGraph}, with its two `Set`s written out as plain arrays (a `Set` does not
 * survive a `JSON.stringify`/`JSON.parse` round trip — see {@link serializeGraph}/
 * {@link deserializeGraph}), plus this line's own topology completeness — see
 * {@link buildLineTopologyIndex}'s own doc for exactly what makes a line `"PARTIAL"`. */
interface SerializedLineTopology {
  completeness: "COMPLETE" | "PARTIAL";
  nodes: number[];
  edges: StopAreaEdgeKey[];
  patterns: number[][];
}

function serializeGraph(graph: LineTopologyGraph, completeness: "COMPLETE" | "PARTIAL"): SerializedLineTopology {
  return { completeness, nodes: [...graph.nodes], edges: [...graph.edges], patterns: graph.patterns.map((pattern) => [...pattern]) };
}

function deserializeGraph(serialized: SerializedLineTopology): LineTopologyGraph {
  return { nodes: new Set(serialized.nodes), edges: new Set(serialized.edges), patterns: serialized.patterns };
}

function lineIndexKey(transportMode: string, lineDesignation: string): string {
  return `${transportMode}:${lineDesignation}`;
}

/**
 * Builds the compact per-line topology index for one GTFS feed — every line the feed has data
 * for, resolved and validated in a small, fixed number of linear passes (never once per
 * `resolveSegment` call — see `createLineTopologyDirectory`'s own "Compact topology index" doc).
 *
 * ## Never bridging a gap
 *
 * A raw GTFS trip's own `stop_times` rows are grouped by `(lineKey, tripId)` and sorted by
 * `stop_sequence`, but a row whose own `stop_id` does not resolve to `"RESOLVED"` (UNRESOLVED OR
 * AMBIGUOUS — both mean "this backend does not safely know the StopArea here") is NEVER silently
 * skipped and continued past. An earlier version of this function did exactly that: filtering out
 * an unresolved row before {@link buildLineTopologyGraph} ever saw it made stops A and C on a
 * real `A -> unresolved-B -> C` trip look, to that function, exactly like a genuine, directly
 * adjacent `A -> C` trip — fabricating an edge that was never actually travelled (confirmed
 * live-reproducible bug; see `lineTopologyDirectory.test.ts`'s own gap-fragmentation tests).
 *
 * Instead, each real trip is split into contiguous RESOLVED fragments at every such gap, each
 * fragment keyed as its own distinct pseudo-trip id (`${tripId}#${fragmentIndex}`) so
 * {@link buildLineTopologyGraph}'s own per-trip pattern grouping treats the piece before a gap and
 * the piece after it as two entirely separate, never-connected patterns — see this file's own
 * "Never create an edge across a missing GTFS stop" regression tests for the direct proof.
 *
 * ## Completeness
 *
 * Every line whose own included trips contained at least one row that did not resolve to
 * `"RESOLVED"` is marked `completeness: "PARTIAL"` — a line with ANY identity gap is not trusted
 * as authoritative for CONFIRMED/UNRELATED segment evidence (see
 * `createLineTopologyDirectory`'s own `loadGraphForLine`, which treats a `"PARTIAL"` line
 * identically to "no topology for this line at all"), even though the edges it WAS able to build
 * from its resolved fragments are still structurally correct (never fabricated) — deliberately
 * conservative: the documented identity bridge should make a real, healthy SL line resolve
 * cleanly end to end; a line that comes out `"PARTIAL"` is itself evidence of identity/data drift
 * this backend should not silently paper over by guessing.
 *
 * [stopIdResolver] is called EXACTLY ONCE, batched over every distinct `stop_id` any included
 * line's trips actually use — never once per row, never once per trip (item 4 of the
 * production-readiness review).
 */
async function buildLineTopologyIndex(
  routes: readonly GtfsRoute[],
  trips: readonly GtfsTrip[],
  stopTimes: readonly GtfsStopTime[],
  stopIdResolver: GtfsStopIdResolver,
): Promise<Record<string, SerializedLineTopology>> {
  const lineKeyByRouteId = new Map<string, string>();
  for (const route of routes) {
    const mode = transportModeForGtfsRouteType(route.routeType);
    if (mode == null) continue;
    lineKeyByRouteId.set(route.routeId, lineIndexKey(mode, route.shortName));
  }

  const lineKeyByTripId = new Map<string, string>();
  for (const trip of trips) {
    const lineKey = lineKeyByRouteId.get(trip.routeId);
    if (lineKey != null) lineKeyByTripId.set(trip.tripId, lineKey);
  }

  // Group every relevant row by (lineKey -> tripId), preserving ALL of them (including ones whose
  // identity might not resolve) so sequence gaps can be detected -- never silently dropped before
  // the topology builder ever sees them.
  const rowsByLineAndTrip = new Map<string, Map<string, GtfsStopTime[]>>();
  const allRequiredStopIds = new Set<string>();
  for (const stopTime of stopTimes) {
    const lineKey = lineKeyByTripId.get(stopTime.tripId);
    if (lineKey == null) continue;
    allRequiredStopIds.add(stopTime.stopId);
    let byTrip = rowsByLineAndTrip.get(lineKey);
    if (!byTrip) {
      byTrip = new Map();
      rowsByLineAndTrip.set(lineKey, byTrip);
    }
    let rows = byTrip.get(stopTime.tripId);
    if (!rows) {
      rows = [];
      byTrip.set(stopTime.tripId, rows);
    }
    rows.push(stopTime);
  }

  // ONE batch resolution for every distinct GTFS stop_id any included line's trips actually use.
  const identities = await stopIdResolver.resolveMany([...allRequiredStopIds]);

  const index: Record<string, SerializedLineTopology> = {};
  for (const [lineKey, byTrip] of rowsByLineAndTrip) {
    const entries: TripStopSequenceEntry[] = [];
    let completeness: "COMPLETE" | "PARTIAL" = "COMPLETE";

    for (const [tripId, rows] of byTrip) {
      const sorted = [...rows].sort((a, b) => a.stopSequence - b.stopSequence);
      let fragment = 0;
      let fragmentOpen = false;
      for (const row of sorted) {
        const identity = identities.get(row.stopId);
        if (identity == null || identity.status !== "RESOLVED") {
          completeness = "PARTIAL";
          if (fragmentOpen) fragment++; // start a new, disconnected fragment after this gap
          fragmentOpen = false;
          continue; // never bridges across this gap
        }
        entries.push({ tripId: `${tripId}#${fragment}`, stopAreaId: identity.stopAreaId, sequence: row.stopSequence });
        fragmentOpen = true;
      }
    }

    index[lineKey] = serializeGraph(buildLineTopologyGraph(entries), completeness);
  }
  return index;
}

const INDEX_CACHE_KEY = "gtfs-regional:sl-line-topology-index:v2";
const INDEX_REFRESH_LOCK_KEY = "gtfs-regional:sl-line-topology-index:refresh-lock:v2";

/** GTFS Regional's own documented update cadence ("updated on a daily basis") — matches
 * `StopPointDirectory`'s own identical reasoning for reference/topology data, and additionally
 * required here: Trafiklab's own lowest access tier allows as few as 50 calls per MONTH, far
 * tighter than SL Deviations' "at most once per minute" — a daily cache is close to mandatory to
 * stay within budget at all. Also the freshness/authority window for {@link isValidated} — see
 * this module's own "Freshness and conditional requests" doc. */
const INDEX_TTL_SECONDS = 60 * 60 * 24;
const INDEX_STALE_FALLBACK_TTL_SECONDS = 7 * 24 * 60 * 60;
const REFRESH_LOCK_TTL_MS = 30_000;
const WAIT_RETRY_COUNT = 5;
const WAIT_RETRY_DELAY_MS = 200;

/**
 * ## Quota protection
 *
 * A never-released claim, acquired immediately before the ONE real upstream
 * `feedSource.fetchFeedFiles()` attempt, so that EVERY attempt — a full `200` rebuild, a cheap
 * `304 Not Modified`, or an outright failure — blocks every OTHER attempt (across every Vercel
 * instance, via the shared Redis-backed `DistributedLock`) for the rest of
 * [ATTEMPT_BACKOFF_MS]. Mirrors `deviationsSnapshotService.ts`'s own `RATE_LIMIT_KEY` technique
 * (acquired with its own backoff window as its TTL, deliberately never released, left to expire
 * naturally).
 *
 * Deliberately ~24 hours, matching {@link INDEX_TTL_SECONDS} — NOT the shorter window an earlier
 * version of this module used. A `304` is never assumed "free" against Trafiklab's own quota
 * (nothing in their documentation guarantees that), so this backend budgets even a conditional
 * validation attempt as "one request used for today" — at most one real HTTP attempt per ~24h
 * period, matching the static feed's own documented daily update cadence, comfortably inside
 * quota even on Trafiklab's lowest tier (as low as 50 calls/month). A sustained total outage
 * therefore costs at most ~1 wasted attempt/day, not 24 — Blick's own existing `LINE_RELEVANT`
 * fallback already makes waiting a day for segment precision to recover an acceptable trade,
 * never a reason to spend quota faster chasing a faster recovery.
 */
const ATTEMPT_CLAIM_KEY = "gtfs-regional:sl-line-topology-index:attempt-claim:v2";
const ATTEMPT_BACKOFF_MS = 24 * 60 * 60 * 1000;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isValidated(validatedAt: string, nowMs: number): boolean {
  return nowMs - new Date(validatedAt).getTime() < INDEX_TTL_SECONDS * 1000;
}

/** Conservative normalization matching `journeySegmentParser.ts`'s own `normalizeCandidateName`
 * exactly — see that function's own doc. Applied identically to a parsed candidate name AND to
 * each successively-shorter word-boundary prefix tried below, so every comparison against
 * `StopAreaNameIndex` is a genuine, identically-normalized exact match, never a fuzzy one. */
function normalizeForLookup(raw: string): string {
  return raw
    .normalize("NFC")
    .trim()
    .replace(/\s+/g, " ")
    .replace(/[.,;:!?]+$/, "");
}

/**
 * Resolves [rawCandidateName] to exactly one StopArea id on [graph]'s own line, via EXACT match
 * only — no fuzzy matching, no Levenshtein distance, no substring matching, no hardcoded aliases.
 * Tries the full (normalized) candidate first; only when that yields NO candidate does it retry
 * progressively shorter WORD-boundary prefixes of the same string — never arbitrary substrings —
 * specifically to recover the real station name from a candidate that legitimately captured
 * trailing prose past it. The longest prefix that resolves to exactly one StopArea on THIS line
 * wins — "longest exact match". A name that resolves to more than one StopArea on this same line
 * at any single prefix length is `"AMBIGUOUS"` immediately, without trying shorter prefixes.
 */
async function resolveStationNameOnLine(
  rawCandidateName: string,
  graph: LineTopologyGraph,
  nameIndex: StopAreaNameIndex,
): Promise<{ status: "RESOLVED"; stopAreaId: number } | { status: "AMBIGUOUS" } | { status: "UNRESOLVED" }> {
  const words = normalizeForLookup(rawCandidateName).split(" ").filter((w) => w.length > 0);
  for (let wordCount = words.length; wordCount > 0; wordCount--) {
    const candidate = normalizeForLookup(words.slice(0, wordCount).join(" "));
    if (candidate.length === 0) continue;
    const matches = await nameIndex.findStopAreaIdsByName(candidate);
    const onThisLine = matches.filter((id) => graph.nodes.has(id));
    if (onThisLine.length > 1) return { status: "AMBIGUOUS" };
    if (onThisLine.length === 1) return { status: "RESOLVED", stopAreaId: onThisLine[0]! };
  }
  return { status: "UNRESOLVED" };
}

/** ONE cached snapshot of the compact per-line topology index. See this module's own "Freshness
 * and conditional requests" doc for the distinction between `builtAt` and `validatedAt`. */
interface LineTopologyIndexSnapshot {
  /** When `index` was last actually rebuilt from a genuine `200` response — unchanged across any
   * number of subsequent `304` revalidations. */
  builtAt: string;
  /** When freshness was last CONFIRMED — bumped to "now" by either a `200` rebuild or a `304 Not
   * Modified` revalidation. This, not `builtAt`, is what {@link isValidated} checks: a snapshot
   * whose `validatedAt` has fallen stale (a revalidation attempt failed, or none has been made in
   * over {@link INDEX_TTL_SECONDS}) is NEVER used as authoritative evidence for CONFIRMED/
   * UNRELATED — see `loadGraphForLine`'s own doc — even though it stays cached (via
   * `INDEX_STALE_FALLBACK_TTL_SECONDS`) so a LATER successful `304`/`200` can cheaply resume from
   * it rather than starting cold. */
  validatedAt: string;
  etag?: string;
  lastModified?: string;
  index: Record<string, SerializedLineTopology>;
}

export function createLineTopologyDirectory(
  feedSource: GtfsFeedSource,
  stopIdResolver: GtfsStopIdResolver,
  nameIndex: StopAreaNameIndex,
  cache: Cache,
  lock: DistributedLock,
  deduper: InFlightDeduper,
): LineTopologyDirectory {
  async function loadIndex(): Promise<LineTopologyIndexSnapshot | null> {
    const cached = await cache.get<LineTopologyIndexSnapshot>(INDEX_CACHE_KEY);
    if (cached && isValidated(cached.validatedAt, Date.now())) return cached;
    return deduper.run(INDEX_CACHE_KEY, () => refreshIndex(cached));
  }

  async function refreshIndex(
    existing: LineTopologyIndexSnapshot | undefined | null,
    retriesLeft = WAIT_RETRY_COUNT,
  ): Promise<LineTopologyIndexSnapshot | null> {
    const token = await lock.acquire(INDEX_REFRESH_LOCK_KEY, REFRESH_LOCK_TTL_MS);
    if (token == null) {
      if (retriesLeft > 0) {
        await delay(WAIT_RETRY_DELAY_MS);
        const refreshed = await cache.get<LineTopologyIndexSnapshot>(INDEX_CACHE_KEY);
        if (refreshed && isValidated(refreshed.validatedAt, Date.now())) return refreshed;
        return refreshIndex(refreshed ?? existing, retriesLeft - 1);
      }
      return existing ?? null;
    }

    try {
      const refreshedByOther = await cache.get<LineTopologyIndexSnapshot>(INDEX_CACHE_KEY);
      if (refreshedByOther && isValidated(refreshedByOther.validatedAt, Date.now())) return refreshedByOther;

      // Quota protection (see ATTEMPT_CLAIM_KEY's own doc): even holding the refresh lock, do
      // not attempt a real upstream request if some attempt -- 200, 304, or a failure -- already
      // happened within the backoff window.
      const attemptToken = await lock.acquire(ATTEMPT_CLAIM_KEY, ATTEMPT_BACKOFF_MS);
      const baseline = refreshedByOther ?? existing;
      if (attemptToken == null) {
        return baseline ?? null; // no upstream call made -- a prior attempt within the backoff window already happened
      }
      // attemptToken is deliberately never released -- see ATTEMPT_CLAIM_KEY's own doc.

      try {
        const previousValidators: GtfsFeedValidators | undefined = baseline ? { etag: baseline.etag, lastModified: baseline.lastModified } : undefined;
        const result = await feedSource.fetchFeedFiles(previousValidators);

        if (result.status === "NOT_MODIFIED") {
          // Nothing changed upstream -- keep the existing index untouched, just confirm it is
          // still current. Atomic by construction: this never touches `index`/`builtAt` at all.
          if (baseline == null) return null; // a 304 with no prior snapshot to revalidate is not usable
          const revalidated: LineTopologyIndexSnapshot = { ...baseline, validatedAt: new Date().toISOString() };
          await cache.set(INDEX_CACHE_KEY, revalidated, INDEX_STALE_FALLBACK_TTL_SECONDS);
          return revalidated;
        }

        // status === "OK" -- build the ENTIRE replacement index in local variables first; only
        // cache.set() below ever makes it visible, so a failure at any point up to here (zip
        // extraction, CSV parsing, identity resolution, graph construction) leaves the previous
        // known-good snapshot completely untouched (see this module's own "Atomic replacement"
        // doc) -- caught by the try/catch below exactly like a network failure would be.
        const routes = parseGtfsRoutes(result.files.routesCsv);
        const trips = parseGtfsTrips(result.files.tripsCsv);
        const stopTimes = parseGtfsStopTimes(result.files.stopTimesCsv);
        const index = await buildLineTopologyIndex(routes, trips, stopTimes, stopIdResolver);
        const now = new Date().toISOString();
        const snapshot: LineTopologyIndexSnapshot = {
          builtAt: now,
          validatedAt: now,
          etag: result.validators.etag,
          lastModified: result.validators.lastModified,
          index,
        };
        await cache.set(INDEX_CACHE_KEY, snapshot, INDEX_STALE_FALLBACK_TTL_SECONDS);
        return snapshot;
      } catch (err) {
        // Refresh failed -- the existing snapshot (if any) is returned UNCHANGED, with its
        // ORIGINAL validatedAt still stale: it remains available as a stale fallback (see
        // INDEX_STALE_FALLBACK_TTL_SECONDS) but loadGraphForLine will correctly refuse to treat
        // it as authoritative until a later revalidation succeeds.
        if (baseline) return baseline;
        console.warn(
          `GTFS Regional feed unavailable; the segment-parsing relevance enhancement is inactive until this is resolved (will not retry for ${ATTEMPT_BACKOFF_MS / 3_600_000}h):`,
          err,
        );
        return null;
      }
    } finally {
      try {
        await lock.release(INDEX_REFRESH_LOCK_KEY, token);
      } catch (err) {
        console.warn("Failed to release GTFS Regional line-topology-index refresh lock (will expire via its own TTL):", err);
      }
    }
  }

  /**
   * The one gate every real lookup goes through — `null` whenever the topology for this exact
   * `(transportMode, lineDesignation)` pair must NOT be treated as authoritative:
   *
   * 1. No snapshot at all (feed never successfully fetched).
   * 2. The snapshot's own `validatedAt` has gone stale (item 17 of the production-readiness
   *    review: stale topology may stay CACHED for a later cheap revalidation, but must never
   *    itself change passenger-facing relevance).
   * 3. This specific line has no entry in the index at all (the feed has no data for it).
   * 4. This specific line's own entry is `completeness: "PARTIAL"` (item 6: any identity gap on
   *    this line means its edges cannot be trusted as complete negative evidence, and are
   *    therefore not exposed at all here — see `evaluateLineSegmentEvidence`'s own doc in
   *    `disruptionRelevance.ts` for how a caller is expected to react to `UNRESOLVED`).
   */
  async function loadGraphForLine(transportMode: string, lineDesignation: string): Promise<LineTopologyGraph | null> {
    const snapshot = await loadIndex();
    if (snapshot == null) return null;
    if (!isValidated(snapshot.validatedAt, Date.now())) return null;
    const serialized = snapshot.index[lineIndexKey(transportMode, lineDesignation)];
    if (serialized == null || serialized.completeness !== "COMPLETE") return null;
    return deserializeGraph(serialized);
  }

  return {
    async resolveSegment(transportMode, lineDesignation, stopAName, stopBName) {
      try {
        const graph = await loadGraphForLine(transportMode, lineDesignation);
        if (graph == null) return { status: "UNRESOLVED" };

        const resolvedA = await resolveStationNameOnLine(stopAName, graph, nameIndex);
        if (resolvedA.status !== "RESOLVED") return { status: resolvedA.status };
        const resolvedB = await resolveStationNameOnLine(stopBName, graph, nameIndex);
        if (resolvedB.status !== "RESOLVED") return { status: resolvedB.status };

        const edges = resolveSegmentEdges(graph, resolvedA.stopAreaId, resolvedB.stopAreaId);
        if (edges.status !== "RESOLVED") return { status: edges.status };
        return {
          status: "RESOLVED",
          stopAreaA: resolvedA.stopAreaId,
          stopAreaB: resolvedB.stopAreaId,
          edges: edges.edges,
          orderedStopAreaIds: edges.orderedStopAreaIds,
        };
      } catch (err) {
        console.warn("LineTopologyDirectory.resolveSegment failed; falling back to UNRESOLVED (the existing LINE_RELEVANT result is unaffected):", err);
        return { status: "UNRESOLVED" };
      }
    },

    async resolveEndpointsCorridor(transportMode, lineDesignation, originSite, destinationSite) {
      try {
        const graph = await loadGraphForLine(transportMode, lineDesignation);
        if (graph == null) return { status: "UNRESOLVED" };

        const resolvedA = resolveEndpointStopAreaOnLine(originSite, graph);
        if (resolvedA.status !== "RESOLVED") return { status: resolvedA.status };
        const resolvedB = resolveEndpointStopAreaOnLine(destinationSite, graph);
        if (resolvedB.status !== "RESOLVED") return { status: resolvedB.status };

        const edges = resolveSegmentEdges(graph, resolvedA.stopAreaId, resolvedB.stopAreaId);
        if (edges.status !== "RESOLVED") return { status: edges.status };
        // resolveSegmentEdges orders its own result by the underlying GTFS pattern's own storage
        // direction, which has no relationship to which of originSite/destinationSite the caller
        // asked for -- the requested journey itself DOES have a real direction (origin first), so
        // that direction must be forced here, never left to depend on pattern storage order (see
        // isRequestedCorridorTrusted's own doc for why an incorrectly-oriented corridor would let a
        // reversed actual run falsely satisfy an exact-prefix/suffix check).
        const orderedStopAreaIds =
          edges.orderedStopAreaIds[0] === resolvedA.stopAreaId ? edges.orderedStopAreaIds : [...edges.orderedStopAreaIds].reverse();
        return {
          status: "RESOLVED",
          stopAreaA: resolvedA.stopAreaId,
          stopAreaB: resolvedB.stopAreaId,
          edges: edges.edges,
          orderedStopAreaIds,
        };
      } catch (err) {
        console.warn("LineTopologyDirectory.resolveEndpointsCorridor failed; falling back to UNRESOLVED (the existing LINE_RELEVANT result is unaffected):", err);
        return { status: "UNRESOLVED" };
      }
    },
  };
}
