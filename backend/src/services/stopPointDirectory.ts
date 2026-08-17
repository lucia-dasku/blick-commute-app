import { AppError } from "../lib/errors.js";
import type { Cache } from "../lib/cache.js";
import type { DistributedLock } from "../lib/distributedLock.js";
import type { InFlightDeduper } from "../lib/cache.js";
import type { SlTransportClient } from "./slTransportClient.js";
import type { RawStopPoint } from "./upstreamTypes.js";

/**
 * A Journey Planner `stopSequence` platform node's own `id` (`type: "platform"`,
 * `isGlobalId: true`) — confirmed live to be the EXACT SAME value as SL Transport
 * `/v1/stop-points`' own `pattern_point_gid` (see this module's own doc below for the full
 * evidence). Always the exact source digit string, never a JS `number` — see
 * `lib/losslessJson.ts`'s own doc for why a value in this identifier space cannot survive an
 * ordinary `Number`/`JSON.parse` round-trip.
 */
export type PatternPointGid = string;

/**
 * A DIFFERENT identity namespace from {@link PatternPointGid}, even though both are ultimately
 * resolved through the exact same `/v1/stop-points` snapshot: `RawStopPoint.gid` (SL Transport's
 * own StopPoint-level "Global ID", e.g. `"9022001000101001"` — the `9022` class-id prefix denotes
 * a Stop Point, per SL's own pubtrans/NOPTIS-derived numbering, confirmed against Trafiklab
 * support documentation — see {@link resolveStopPointGids}'s own doc for the full evidence and
 * its honest limits) rather than `RawStopPoint.pattern_point_gid` (a Journey-Planner-facing
 * platform identifier, class-id `9025`). Kept as an explicit, distinctly-named type — never
 * aliased to or interchanged with `PatternPointGid` — specifically so a caller (and a future
 * reader) can never accidentally pass a Journey Planner platform id where a GTFS `stop_id` is
 * expected, or vice versa; they happen to share a similar digit-string SHAPE but are not
 * type-compatible identities. Always the exact source digit string, never a JS `number` — same
 * reasoning as {@link PatternPointGid}'s own doc.
 */
export type StopPointGid = string;

/**
 * One {@link StopPointGid}'s resolution against the SAME SL Transport stop-point directory
 * {@link StopPointResolution} resolves `PatternPointGid`s against — see
 * {@link resolveStopPointGids}'s own doc for the full identity bridge this rests on.
 * Deliberately a NARROWER shape than {@link StopPointResolution} (no `stopPointId`, no
 * `stopAreaType`): a GTFS `stop_id`'s only use in this codebase is building line topology at
 * STOPAREA precision (see `services/lineTopologyDirectory.ts`), never StopPoint-level identity.
 *
 * `"AMBIGUOUS"` and `"UNRESOLVED"` are handled identically to {@link StopPointResolution}'s own
 * two-tier ambiguity policy (see {@link buildGidIndex}'s own doc) — never first-record-wins.
 */
export type StopAreaIdentityResolution =
  | { status: "RESOLVED"; gid: StopPointGid; stopAreaId: number }
  | { status: "AMBIGUOUS"; gid: StopPointGid; stopAreaIds: readonly number[] }
  | { status: "UNRESOLVED"; gid: StopPointGid };

/**
 * One `PatternPointGid`'s resolution against the SL Transport stop-point directory — never
 * collapsed into a plain nullable value, so a caller can tell "genuinely not found" (UNRESOLVED)
 * apart from "found, but the mapping is not safe to trust as a single answer" (AMBIGUOUS) apart
 * from "the StopArea is proven but no single StopPoint is" (STOP_AREA_ONLY) apart from a fully
 * clean, usable answer (RESOLVED). See `resolveDeviationRelevance`'s own doc (via
 * `domain/journeyDisruptionScope.ts`) for how a caller is expected to treat all four: RESOLVED
 * contributes both a StopArea and a StopPoint id; STOP_AREA_ONLY contributes a StopArea id ONLY
 * (never a StopPoint id — see `buildIndex`'s own doc for exactly when this is produced); AMBIGUOUS
 * and UNRESOLVED contribute nothing at all, degrading the consuming scope's own completeness to
 * `"PARTIAL"` rather than being treated as a disproof of anything.
 *
 * `STOP_AREA_ONLY` matters specifically because `resolveDeviationRelevance` compares a journey's
 * own resolved scope against BOTH a deviation's `scope.stop_areas` AND its `scope.stop_points`
 * (higher-precision evidence) — an ambiguous StopPoint id must never be allowed to produce a
 * false `scope.stop_points` intersection merely because it happened to be the first record seen.
 */
export type StopPointResolution =
  | { status: "RESOLVED"; patternPointGid: PatternPointGid; stopPointId: number; stopAreaId: number; stopAreaType: string | null }
  | { status: "STOP_AREA_ONLY"; patternPointGid: PatternPointGid; stopAreaId: number; stopAreaType: string | null }
  | { status: "AMBIGUOUS"; patternPointGid: PatternPointGid; stopAreaIds: readonly number[] }
  | { status: "UNRESOLVED"; patternPointGid: PatternPointGid };

/**
 * Identity-only resolution from a Journey Planner platform id to the SL-Transport/Deviations
 * namespace stop identity a disruption's own `scope.stop_points`/`scope.stop_areas` can actually
 * be compared against. Deliberately carries NO disruption policy (which `DisruptionEffect`
 * selects which scope), NO journey-role logic (PRIMARY/NEXT/ALTERNATIVE), and is never imported
 * by Android — see `domain/journeyDisruptionScope.ts` for the layer that actually builds a
 * journey's ACCESS_POINTS/TRAVELLED_PATH scopes on top of this service's own `resolveMany`.
 *
 * ## The identity bridge, and the live evidence it rests on
 *
 * SL Journey Planner's own `legs[].stopSequence[]` (see `slJourneyPlannerClient.ts`) exposes
 * each stop the vehicle calls at as a `{ type, isGlobalId, id }` node. When `type === "platform"`
 * and `isGlobalId === true`, that `id` (e.g. `"9025001000003272"`) is the SAME value as one
 * `/v1/stop-points` record's own `pattern_point_gid` — confirmed by fetching real, live trips
 * across every mode Journey Planner exposes (metro, commuter train, tram, bus, ferry), plus a
 * multi-leg interchange transferring at T-Centralen and one at Slussen, and cross-referencing
 * EVERY platform-typed `stopSequence` entry returned (101 of 101) against a live full
 * `/v1/stop-points` snapshot (14,187 records): 101/101 resolved, 0 unresolved, 0 ambiguous, and
 * every resolved name/stop-area/mode combination was structurally sane (e.g. Akalla -> Husby ->
 * Kista -> ... -> T-Centralen on Metro 11 resolved to `METROSTN`-typed stop areas named exactly
 * that; a live Slussen transfer from Metro 19 to Bus 471 resolved through a genuine WALK between
 * two DIFFERENT Slussen-named stop areas, `1011` (METROSTN) and `44000` (BUSTERM) — exactly the
 * multi-access-point transfer case `journeyDisruptionScope.ts` has to represent). See the
 * architecture-review record for the full raw evidence this doc summarizes.
 *
 * This is deliberately NOT derived by any arithmetic/substring relationship between a platform id
 * and a stop-area id — the live data itself disproves any such shortcut: Fridhemsplan's own
 * platform id `9025001000003152` resolves to stop-area `1151`, and T-Centralen's platform id
 * `9025001000003051` resolves to stop-area `1051` — an assumed "subtract 1" (or any other
 * fixed-offset/prefix) relationship that happens to hold for most stations on this exact live
 * data would silently break for both. The ONLY reliable join is the exact `pattern_point_gid`
 * value itself, which is why this directory exists as its own service rather than a formula.
 *
 * A DIFFERENT namespace — SL Journey Planner's own `type: "stop"` place ids (e.g.
 * `"9091001000009300"`, used for a leg's `origin`/`destination` when Journey Planner has not
 * pinned a specific boarding/alighting platform, and for `searchStops`' own results) — is NEVER
 * looked up here. Those ids are outside this bridge entirely (a different HAFAS id-type prefix,
 * `91` vs `pattern_point_gid`'s own `25`); `journeyDisruptionScope.ts` treats a `stopSequence`
 * node that isn't `type: "platform"` as simply unresolvable evidence (the same as an UNRESOLVED
 * result here), never as a reason to guess at a different join.
 *
 * ## Cardinality
 *
 * Confirmed live: EVERY one of the 14,187 `pattern_point_gid` values in the current SL Transport
 * stop-point dataset is unique — zero collisions. `AMBIGUOUS`/`STOP_AREA_ONLY` are still real,
 * handled outcomes (not dead code) because nothing about the upstream schema *guarantees* this
 * holds forever — a future collision is handled in two tiers rather than by ever picking an
 * arbitrary first record: sharing records that disagree on their own `stop_area.id` produce
 * `AMBIGUOUS` (nothing usable, not even the station); sharing records that agree on
 * `stop_area.id` but disagree on their own StopPoint `id` produce `STOP_AREA_ONLY` (the station
 * is still safely usable evidence; no single StopPoint id is ever claimed proven). See
 * `buildIndex`'s own doc for exactly how this is computed.
 *
 * ## Caching
 *
 * `/v1/stop-points` is nationwide reference/topology data (an 8MB raw payload, ~594KB once
 * reduced to this directory's own compact `patternPointGid -> {stopPointId, stopAreaId,
 * stopAreaType}` index — measured against the real live payload, comfortably within a single
 * Redis value). SL Transport does not separately document this endpoint's own change cadence;
 * treated the same as `/v1/sites`, which SL DOES document as changing "at most once per day" —
 * both describe network topology, not live operational state, so the same daily freshness window
 * is a reasonable, conservative choice (see `SNAPSHOT_TTL_SECONDS`).
 *
 * Unlike `deviationsSnapshotService.ts`, there is no documented strict "N requests per minute"
 * fair-use ceiling for this specific endpoint (that constraint is SL Deviations' own, see that
 * service's own doc) — so this service does not need Deviations' own separate, never-released
 * rate-limit key. It still shares the SAME Redis-backed `Cache`/`DistributedLock` infrastructure
 * (see `app.ts`) so that (a) the ~594KB index is fetched/rebuilt once and shared across every
 * Vercel instance rather than once per cold instance, and (b) concurrent cold callers — both
 * within one instance (via `InFlightDeduper`) and across instances (via the refresh lock) —
 * collapse into a single upstream fetch rather than each independently downloading 8MB.
 *
 * A refresh failure (network error, schema mismatch, or upstream simply unreachable) falls back
 * to the last known-good index if one exists in the shared cache (`STALE_FALLBACK_TTL_SECONDS`,
 * deliberately much longer than the freshness window — stop-point topology changing between a
 * failed refresh and the next successful one is far less likely than a JSON parse hiccup or a
 * transient SL outage), and only throws when there has truly never been a successful snapshot.
 * `journeyDisruptionScope.ts`'s own caller (`routes/journeyDisruptions.ts`) treats even THAT as
 * non-fatal — see this service's own `resolveMany` doc — degrading to the existing PARTIAL
 * relevance model rather than failing the whole `/api/v1/journeys/disruptions` request.
 *
 * ## One upstream snapshot, three derived indexes
 *
 * {@link findStopAreaIdsByName} (used by `services/lineTopologyDirectory.ts` to turn
 * `journeySegmentParser.ts`'s own conservatively-normalized candidate station names into real
 * StopArea ids — see {@link StopAreaNameIndex}'s own doc for that resolution contract) and
 * {@link resolveStopPointGids} (used by that SAME module's `GtfsStopIdResolver` to bridge GTFS
 * Regional `stop_id`s to StopArea ids — see that method's own doc) are both built from the exact
 * SAME `/v1/stop-points` fetch as {@link resolveMany}, not a second upstream client or a second
 * cache/lock/dedup cycle. An earlier version of this feature gave the name index its own fully
 * independent cached snapshot (`createStopAreaNameIndex`, its own cache key, its own refresh
 * lock) — reasoned at the time as "a separate cached snapshot of the same upstream data, not a
 * duplicate upstream client", which was true but still meant two Redis round trips, two refresh
 * locks, and two independent refresh cycles for data that is always fetched and refreshed
 * together in practice. This is now genuinely ONE snapshot ({@link StopPointSnapshot}): one
 * cache key, one refresh lock, one in-flight dedupe, one freshness window — {@link buildSnapshot}
 * derives the `pattern_point_gid`-keyed index, the `gid`-keyed index, AND the name-keyed index
 * from the same raw `stopPoints` array in a single refresh, and `resolveMany`,
 * `findStopAreaIdsByName`, and `resolveStopPointGids` all read from the same `loadSnapshot()`
 * call. A caller that already triggered a fresh snapshot via one method (e.g.
 * `journeyDisruptionScope.ts` calling `resolveMany`) causes zero additional `/v1/stop-points`
 * requests when a DIFFERENT method is then called against the same warm snapshot (e.g. the
 * segment parser calling `findStopAreaIdsByName`, or the GTFS topology builder calling
 * `resolveStopPointGids`) — see `stopPointDirectory.test.ts`'s own "one shared snapshot serves
 * all three capabilities" test for the proof.
 */
export interface StopPointDirectory {
  /**
   * Resolves every one of [patternPointGids] against the current index in one shared snapshot
   * read (never one cache/upstream round trip per id). Returns a `Map` keyed by the exact input
   * strings, with every input present exactly once (as RESOLVED, STOP_AREA_ONLY, AMBIGUOUS, or
   * UNRESOLVED — see {@link StopPointResolution}'s own doc for all four) — never a partial map
   * that silently omits an id the caller has to remember to treat as "missing".
   *
   * Never throws on a mere data gap (an unresolvable id is UNRESOLVED, not an exception) — only
   * propagates when the directory itself could not be loaded AT ALL (no fresh snapshot, no stale
   * fallback, and the live refresh also failed; the same `AppError("UPSTREAM_ERROR", ...)`
   * shape every other upstream-backed service in this codebase already throws in that situation).
   * The one production caller (`routes/journeyDisruptions.ts`) catches exactly this and falls
   * back to the pre-existing origin-only PARTIAL relevance model — see that route's own doc —
   * rather than failing the whole request; `/api/v1/journeys` itself never calls this at all
   * (see `journeyDisruptionScope.ts`'s own doc on keeping the directory lookup out of the
   * primary journey critical path).
   */
  resolveMany(patternPointGids: readonly PatternPointGid[]): Promise<Map<PatternPointGid, StopPointResolution>>;

  /**
   * Resolves [normalizedName] to every distinct StopArea id that carries that exact name in the
   * SAME cached snapshot {@link resolveMany} reads from (see this module's own "One upstream
   * snapshot, two derived indexes" doc) — never a second upstream fetch. [normalizedName] must
   * already be conservatively normalized (NFC, trimmed, whitespace-collapsed, lowercased — see
   * `journeySegmentParser.ts`'s own `normalizeCandidateName`) the exact same way this index's own
   * keys are built; this method performs no normalization of its own and no fuzzy comparison
   * whatsoever — see {@link StopAreaNameIndex}'s own doc for the full contract. A name legitimately
   * shared by more than one real StopArea (confirmed live, e.g. two distinct "Bålsta" sites) is
   * returned as every matching id, never arbitrarily narrowed to one here. An unknown name
   * resolves to an empty array, never throws (mirrors {@link resolveMany}'s own never-throws
   * contract for a mere data gap).
   */
  findStopAreaIdsByName(normalizedName: string): Promise<readonly number[]>;

  /**
   * Resolves every one of [gids] — GTFS Regional `stop_times.stop_id` values, NOT
   * `PatternPointGid`s (see {@link StopPointGid}'s own doc for why these are kept as distinct
   * types) — against `RawStopPoint.gid` in the SAME cached snapshot {@link resolveMany}/
   * {@link findStopAreaIdsByName} read from. One shared snapshot read, deduplicated input,
   * never one Redis/upstream round trip per gid — see `services/lineTopologyDirectory.ts`'s own
   * `buildLineTopologyIndex` for the one production caller, which collects every unique GTFS
   * `stop_id` its included routes/trips actually use FIRST, then calls this exactly once per
   * daily topology refresh (item 4 of the production-readiness review: never resolve one stop id
   * at a time during that build).
   *
   * ## The identity bridge, and its honest limits
   *
   * SL Transport's own `RawStopPoint.gid` (e.g. `"9022001000101001"`) and GTFS Regional's own
   * `stop_times.stop_id` for a StopPoint-level stop are HYPOTHESIZED to be the exact same value,
   * on this evidence (checked live, 2026-08-17): Trafiklab support staff (Kenneth, in the
   * "Stoppställenummer i GTFS Regional och GTFS Sverige 2" thread on support.trafiklab.se)
   * confirms GTFS Regional's own 16-digit SL ids use a class-id prefix scheme — `9021` = Stop
   * Area, `9022` = Stop point — and that "these ids are SL's own designations and come from
   * their internal system pubtrans, which builds on NOPTIS" (translated from Swedish). SL
   * Transport's own `/v1/stop-points` `gid` field uses the EXACT SAME `9022`-prefixed shape
   * (confirmed directly against this backend's own live-fetched fixtures). Both being described
   * as originating from the SAME SL-internal pubtrans/NOPTIS source system, using the SAME
   * class-id numbering convention, is meaningful, genuine evidence — but it falls short of an
   * explicit Trafiklab statement that the two are guaranteed byte-for-byte interchangeable, and
   * general Trafiklab documentation elsewhere is deliberately non-specific ("GTFS Regional does
   * not share stop ids with any other dataset" — most plausibly a warning about CROSS-OPERATOR
   * id reuse, not this same-operator, same-source-system case, but not explicitly disambiguated
   * either way).
   *
   * This bridge is therefore documented as a genuine, evidence-backed HYPOTHESIS, not a proven
   * fact — `services/lineTopologyDirectory.ts`'s own `createGtfsStopIdResolver` (the real
   * implementation built on this method) is real, tested production code, but whether `app.ts`
   * actually wires it into a live deployment depends on a SEPARATE, manual verification step
   * (`scripts/verifyGtfsStopIdentityBridge.ts` — run once real GTFS Regional data can be
   * downloaded) that this deployment could not perform: `TRAFIKLAB_API_KEY` remains unconfigured.
   * See that resolver's own doc for the full chain of reasoning and current status.
   */
  resolveStopPointGids(gids: readonly StopPointGid[]): Promise<Map<StopPointGid, StopAreaIdentityResolution>>;
}

type StopPointIndexValue =
  | { kind: "resolved"; stopPointId: number; stopAreaId: number; stopAreaType: string | null }
  | { kind: "stopAreaOnly"; stopAreaId: number; stopAreaType: string | null }
  | { kind: "ambiguousStopArea"; stopAreaIds: number[] };

/** ONE cached snapshot backing {@link StopPointDirectory.resolveMany},
 * {@link StopPointDirectory.findStopAreaIdsByName}, AND
 * {@link StopPointDirectory.resolveStopPointGids} — see this module's own "One upstream
 * snapshot, two derived indexes" doc (now three). `patternPointGidIndex`, `stopPointGidIndex`,
 * and `nameIndex` are three different *shapes* of the exact same underlying `/v1/stop-points`
 * fetch, built together in one pass by {@link buildSnapshot} and refreshed together — never
 * independently. */
interface StopPointSnapshot {
  fetchedAt: string;
  patternPointGidIndex: Record<string, StopPointIndexValue>;
  /** Same tiered-ambiguity shape as `patternPointGidIndex`, keyed by `RawStopPoint.gid` instead
   * of `pattern_point_gid` — see {@link StopPointDirectory.resolveStopPointGids}'s own doc for
   * the identity bridge this serves. */
  stopPointGidIndex: Record<string, StopPointIndexValue>;
  /** Conservatively-normalized StopArea name -> every distinct StopArea id seen with that exact
   * name (see {@link StopAreaNameIndex}'s own doc). */
  nameIndex: Record<string, number[]>;
}

const SNAPSHOT_CACHE_KEY = "sl-transport:stop-point-snapshot:v1";
const REFRESH_LOCK_KEY = "sl-transport:stop-point-snapshot:refresh-lock:v1";

/** See this module's own top-level "Caching" doc for why a daily window, matching
 * `siteDirectory.ts`'s own `SITE_SNAPSHOT_TTL_SECONDS`, is used here. */
const SNAPSHOT_TTL_SECONDS = 60 * 60 * 24;

/** Kept as a stale fallback well past the freshness window — see this module's own "Caching"
 * doc for why a longer outage here is lower-risk to serve stale than SL Deviations is. */
const STALE_FALLBACK_TTL_SECONDS = 7 * 24 * 60 * 60;

/** Generous relative to the upstream fetch's own timeout budget, covering the larger (~8MB)
 * payload's own download + lossless-parse + index-build time, mirroring
 * `deviationsSnapshotService.ts`'s own `REFRESH_LOCK_TTL_MS` reasoning. */
const REFRESH_LOCK_TTL_MS_BUFFER = 15_000;

const WAIT_RETRY_COUNT = 5;
const WAIT_RETRY_DELAY_MS = 200;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isFresh(fetchedAt: string, nowMs: number): boolean {
  return nowMs - new Date(fetchedAt).getTime() < SNAPSHOT_TTL_SECONDS * 1000;
}

/**
 * Groups [stopPoints] by whatever gid [keyOf] selects (`pattern_point_gid` for
 * `patternPointGidIndex`, `gid` for `stopPointGidIndex` — see {@link StopPointSnapshot}'s own
 * doc) into the compact index this service caches and resolves against. A gid shared by more
 * than one record (never observed live for `pattern_point_gid` — see this module's own
 * "Cardinality" doc — but not something the upstream schema guarantees can never happen for
 * EITHER gid field) is handled in two tiers, never by picking an arbitrary first record:
 *
 * 1. If the sharing records DISAGREE on `stop_area.id`, the whole mapping is ambiguous outright
 *    (`"ambiguousStopArea"`) — nothing here can be trusted, not even the station.
 * 2. If they all agree on `stop_area.id` (all roads lead to the same station) but disagree on
 *    their own `id` (StopPoint id) — a genuinely different platform-level distinction — the
 *    StopArea is still safely usable (`"stopAreaOnly"`), but no single StopPoint id may be
 *    claimed proven; picking the first record's own `id` here would let a hypothetical future
 *    duplicate mapping fabricate a false `scope.stop_points` intersection in
 *    `resolveDeviationRelevance` that was never actually established. Only when every sharing
 *    record agrees on BOTH `stop_area.id` AND its own `id` is the mapping fully `"resolved"`.
 */
function buildIndex(stopPoints: readonly RawStopPoint[], keyOf: (sp: RawStopPoint) => string): Record<string, StopPointIndexValue> {
  const byGid = new Map<string, RawStopPoint[]>();
  for (const sp of stopPoints) {
    const key = keyOf(sp);
    const existing = byGid.get(key);
    if (existing) existing.push(sp);
    else byGid.set(key, [sp]);
  }

  const index: Record<string, StopPointIndexValue> = {};
  for (const [gid, records] of byGid) {
    const distinctStopAreaIds = [...new Set(records.map((r) => r.stop_area.id))];
    if (distinctStopAreaIds.length > 1) {
      index[gid] = { kind: "ambiguousStopArea", stopAreaIds: distinctStopAreaIds };
      continue;
    }
    const stopAreaId = distinctStopAreaIds[0]!;
    const stopAreaType = records[0]!.stop_area.type ?? null;
    const distinctStopPointIds = [...new Set(records.map((r) => r.id))];
    index[gid] =
      distinctStopPointIds.length === 1
        ? { kind: "resolved", stopPointId: distinctStopPointIds[0]!, stopAreaId, stopAreaType }
        : { kind: "stopAreaOnly", stopAreaId, stopAreaType };
  }
  return index;
}

function resolveOne(patternPointGid: PatternPointGid, index: Record<string, StopPointIndexValue>): StopPointResolution {
  const entry = index[patternPointGid];
  if (entry == null) return { status: "UNRESOLVED", patternPointGid };
  switch (entry.kind) {
    case "ambiguousStopArea":
      return { status: "AMBIGUOUS", patternPointGid, stopAreaIds: entry.stopAreaIds };
    case "stopAreaOnly":
      return { status: "STOP_AREA_ONLY", patternPointGid, stopAreaId: entry.stopAreaId, stopAreaType: entry.stopAreaType };
    case "resolved":
      return {
        status: "RESOLVED",
        patternPointGid,
        stopPointId: entry.stopPointId,
        stopAreaId: entry.stopAreaId,
        stopAreaType: entry.stopAreaType,
      };
  }
}

function resolveStopAreaIdentity(gid: StopPointGid, index: Record<string, StopPointIndexValue>): StopAreaIdentityResolution {
  const entry = index[gid];
  if (entry == null) return { status: "UNRESOLVED", gid };
  if (entry.kind === "ambiguousStopArea") return { status: "AMBIGUOUS", gid, stopAreaIds: entry.stopAreaIds };
  return { status: "RESOLVED", gid, stopAreaId: entry.stopAreaId };
}

function normalizeStopAreaName(name: string): string {
  return name.normalize("NFC").trim().replace(/\s+/g, " ").toLowerCase();
}

function buildNameIndex(stopPoints: readonly RawStopPoint[]): Record<string, number[]> {
  const index: Record<string, Set<number>> = {};
  for (const sp of stopPoints) {
    const key = normalizeStopAreaName(sp.stop_area.name);
    (index[key] ??= new Set()).add(sp.stop_area.id);
  }
  const result: Record<string, number[]> = {};
  for (const [key, ids] of Object.entries(index)) result[key] = [...ids];
  return result;
}

function buildSnapshot(stopPoints: readonly RawStopPoint[]): StopPointSnapshot {
  return {
    fetchedAt: new Date().toISOString(),
    patternPointGidIndex: buildIndex(stopPoints, (sp) => sp.pattern_point_gid),
    stopPointGidIndex: buildIndex(stopPoints, (sp) => sp.gid),
    nameIndex: buildNameIndex(stopPoints),
  };
}

export function createStopPointDirectory(
  client: SlTransportClient,
  cache: Cache,
  lock: DistributedLock,
  deduper: InFlightDeduper,
): StopPointDirectory {
  async function loadSnapshot(): Promise<StopPointSnapshot> {
    const cached = await cache.get<StopPointSnapshot>(SNAPSHOT_CACHE_KEY);
    if (cached && isFresh(cached.fetchedAt, Date.now())) return cached;

    // Collapses concurrent cold callers WITHIN this one process into a single attempt, on top
    // of the cross-instance refresh lock below — see this module's own "Caching" doc.
    return deduper.run(SNAPSHOT_CACHE_KEY, () => refreshSnapshot(cached));
  }

  async function refreshSnapshot(existing: StopPointSnapshot | undefined, retriesLeft = WAIT_RETRY_COUNT): Promise<StopPointSnapshot> {
    const refreshLockTtlMs = REFRESH_LOCK_TTL_MS_BUFFER + 10_000;
    const token = await lock.acquire(REFRESH_LOCK_KEY, refreshLockTtlMs);
    if (token == null) {
      // Another instance is already refreshing right now.
      if (retriesLeft > 0) {
        await delay(WAIT_RETRY_DELAY_MS);
        const refreshed = await cache.get<StopPointSnapshot>(SNAPSHOT_CACHE_KEY);
        if (refreshed && isFresh(refreshed.fetchedAt, Date.now())) return refreshed;
        return refreshSnapshot(refreshed ?? existing, retriesLeft - 1);
      }
      if (existing) return existing;
      throw new AppError("UPSTREAM_ERROR", "SL Transport stop-point directory is not yet available; please retry shortly");
    }

    try {
      const refreshedByOther = await cache.get<StopPointSnapshot>(SNAPSHOT_CACHE_KEY);
      if (refreshedByOther && isFresh(refreshedByOther.fetchedAt, Date.now())) return refreshedByOther;

      try {
        const stopPoints = await client.fetchStopPoints();
        const snapshot = buildSnapshot(stopPoints);
        await cache.set(SNAPSHOT_CACHE_KEY, snapshot, STALE_FALLBACK_TTL_SECONDS);
        return snapshot;
      } catch (err) {
        const fallback = refreshedByOther ?? existing;
        if (fallback) return fallback;
        throw err;
      }
    } finally {
      try {
        await lock.release(REFRESH_LOCK_KEY, token);
      } catch (err) {
        console.warn("Failed to release SL Transport stop-point directory refresh lock (will expire via its own TTL):", err);
      }
    }
  }

  return {
    async resolveMany(patternPointGids) {
      const snapshot = await loadSnapshot();
      const result = new Map<PatternPointGid, StopPointResolution>();
      for (const gid of patternPointGids) {
        if (!result.has(gid)) result.set(gid, resolveOne(gid, snapshot.patternPointGidIndex));
      }
      return result;
    },

    async findStopAreaIdsByName(normalizedName) {
      const snapshot = await loadSnapshot();
      return snapshot.nameIndex[normalizedName] ?? [];
    },

    async resolveStopPointGids(gids) {
      const snapshot = await loadSnapshot();
      const result = new Map<StopPointGid, StopAreaIdentityResolution>();
      for (const gid of gids) {
        if (!result.has(gid)) result.set(gid, resolveStopAreaIdentity(gid, snapshot.stopPointGidIndex));
      }
      return result;
    },
  };
}

/**
 * A narrow structural view of {@link StopPointDirectory} — exactly its
 * {@link StopPointDirectory.findStopAreaIdsByName} capability, nothing else. Exists so a consumer
 * that only ever needs name-based StopArea lookup (currently only
 * `services/lineTopologyDirectory.ts`, to turn `journeySegmentParser.ts`'s own conservatively-
 * normalized candidate station names into real StopArea ids — see that service's own doc for the
 * full resolution contract) can depend on the smaller interface rather than the whole
 * {@link StopPointDirectory}. Every real `StopPointDirectory` (from {@link createStopPointDirectory})
 * satisfies this structurally, with no adapter needed — `app.ts` passes the SAME
 * `stopPointDirectory` instance wherever a `StopAreaNameIndex` is expected. See this module's own
 * "One upstream snapshot, two derived indexes" doc for why there is no separate implementation of
 * this interface anymore.
 */
export interface StopAreaNameIndex {
  /** [normalizedName] must already be conservatively normalized (NFC, trimmed, whitespace-
   * collapsed, lowercased — see `journeySegmentParser.ts`'s own `normalizeCandidateName`) the
   * exact same way this index's own keys are built; this function performs no normalization of
   * its own and no fuzzy comparison whatsoever. */
  findStopAreaIdsByName(normalizedName: string): Promise<readonly number[]>;
}
