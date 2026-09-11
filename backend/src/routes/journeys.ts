import { Hono } from "hono";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import {
  selectPlannedJourneyChoices,
  type PlannedJourneyRole,
  type PlannedJourneySelection,
} from "../domain/plannedJourneyChoices.js";
import {
  CandidateCollector,
  requestMaxChanges,
  type JourneyChangesPreference,
  type NormalizedJourney,
} from "../services/candidateCollector.js";
import {
  acquireAuthoritativeLiveJourneys,
  toRankable,
  type LiveJourneyRole,
  type RankableNormalizedJourney,
} from "../services/liveJourneyAcquisition.js";
import { floorToStockholmRequestMinute, nextStockholmRequestMinute } from "../lib/stockholmTime.js";
import {
  journeyTransportModes,
  type JourneyTransportMode,
  type SlJourneyPlannerClient,
} from "../services/slJourneyPlannerClient.js";

function required(value: string | undefined, name: string, max = 128): string {
  const normalized = value?.trim();
  if (!normalized || normalized.length > max) throw new AppError("VALIDATION_ERROR", `Query parameter '${name}' is invalid`);
  return normalized;
}

function requestedTransportModes(value: string | undefined): JourneyTransportMode[] {
  if (value == null) return [...journeyTransportModes];
  const requested = [...new Set(value.split(",").map((mode) => mode.trim().toUpperCase()).filter(Boolean))];
  if (requested.length === 0 || requested.some((mode) => !journeyTransportModes.includes(mode as JourneyTransportMode))) {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'transportModes' is invalid");
  }
  return journeyTransportModes.filter((mode) => requested.includes(mode));
}

/**
 * `searchUntil` bounds forward candidate acquisition to a genuine routine-occurrence
 * boundary the caller already knows — see this route's own doc, and the Android call
 * sites: `RoutineActiveWindowWorker`'s own `windowEnd`, or `RoutineDetailsViewModel`'s own
 * `NextOccurrenceCalculator` result. A malformed value that WAS supplied is a validation
 * error; an ABSENT one is not — it means "answer from the initial acquisition alone, fail
 * closed rather than invent a search horizon and search unboundedly".
 */
function parseSearchUntil(value: string | undefined): Date | null {
  if (value == null) return null;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) throw new AppError("VALIDATION_ERROR", "Query parameter 'searchUntil' is invalid");
  return parsed;
}

/** The routine's persisted Direct/Both/With-changes preference (see
 * `CandidateCollector`'s own `JourneyChangesPreference` doc) — an ABSENT value defaults to
 * `"BOTH"`, the pre-existing unfiltered behavior, for backward compatibility with any caller
 * that predates this parameter; a value that WAS supplied but isn't one of the three known
 * ones is a validation error, exactly like `requestedTransportModes`'s own handling. */
function parseChangesPreference(value: string | undefined): JourneyChangesPreference {
  if (value == null) return "BOTH";
  const normalized = value.trim().toUpperCase();
  if (normalized !== "DIRECT_ONLY" && normalized !== "BOTH" && normalized !== "WITH_CHANGES_ONLY") {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'changesPreference' is invalid");
  }
  return normalized;
}

export const MAX_LATER_JOURNEYS = 3;

function parseLaterJourneyCount(value: string | undefined): number {
  if (value == null) return 0;
  if (!/^\d+$/.test(value)) throw new AppError("VALIDATION_ERROR", "Query parameter 'laterJourneyCount' is invalid");
  const count = Number(value);
  if (!Number.isSafeInteger(count) || count < 0 || count > MAX_LATER_JOURNEYS) {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'laterJourneyCount' is invalid");
  }
  return count;
}

export type JourneySearchMode = "NOW" | "LEAVE_AT" | "ARRIVE_BY";
export type JourneyContext = "LIVE" | "PLANNED";

interface JourneySearchRequest {
  searchMode: JourneySearchMode;
  journeyContext: JourneyContext;
  fetchedAt: Date;
  requestedDateTime: Date | null;
}

const EXPLICIT_OFFSET_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?(?:Z|[+-]\d{2}:\d{2})$/;

function hasValidCalendarFields(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(value);
  if (match == null) return false;
  const [, yearText, monthText, dayText, hourText, minuteText, secondText] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText ?? "0");
  const calendarCheck = new Date(Date.UTC(year, month - 1, day, hour, minute, second));
  return (
    calendarCheck.getUTCFullYear() === year &&
    calendarCheck.getUTCMonth() === month - 1 &&
    calendarCheck.getUTCDate() === day &&
    calendarCheck.getUTCHours() === hour &&
    calendarCheck.getUTCMinutes() === minute &&
    calendarCheck.getUTCSeconds() === second
  );
}

/** Parses the explicit live/planned contract. Planned values must name a real instant and
 * include an offset; the server never guesses a timezone. SL accepts whole-minute anchors,
 * so sub-minute precision is rejected rather than silently changing the requested intent or
 * creating needless cache identities that map to the same upstream query. */
function parseJourneySearch(
  searchModeValue: string | undefined,
  requestedDateTimeValue: string | undefined,
  searchUntil: Date | null,
  fetchedAt: Date,
): JourneySearchRequest {
  const searchMode = (searchModeValue ?? "NOW").trim().toUpperCase();
  if (searchMode !== "NOW" && searchMode !== "LEAVE_AT" && searchMode !== "ARRIVE_BY") {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'searchMode' is invalid");
  }

  if (searchMode === "NOW") {
    if (requestedDateTimeValue != null) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'requestedDateTime' is not valid for NOW searches");
    }
    return { searchMode, journeyContext: "LIVE", fetchedAt, requestedDateTime: null };
  }

  if (searchUntil != null) {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'searchUntil' is only valid for NOW searches");
  }
  const raw = requestedDateTimeValue?.trim();
  if (raw == null || !EXPLICIT_OFFSET_TIMESTAMP.test(raw) || !hasValidCalendarFields(raw)) {
    throw new AppError(
      "VALIDATION_ERROR",
      "Query parameter 'requestedDateTime' must be an ISO-8601 timestamp with an explicit offset",
    );
  }
  const requestedDateTime = new Date(raw);
  if (Number.isNaN(requestedDateTime.getTime())) {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'requestedDateTime' is invalid");
  }
  if (requestedDateTime.getUTCSeconds() !== 0 || requestedDateTime.getUTCMilliseconds() !== 0) {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'requestedDateTime' must use whole-minute precision");
  }
  if (requestedDateTime.getTime() <= fetchedAt.getTime()) {
    throw new AppError("VALIDATION_ERROR", "Query parameter 'requestedDateTime' must be in the future");
  }
  return { searchMode, journeyContext: "PLANNED", fetchedAt, requestedDateTime };
}

export type { LiveJourneyRole } from "../services/liveJourneyAcquisition.js";
export type JourneyRole = LiveJourneyRole | PlannedJourneyRole;

/**
 * Resolves the separate Event chooser without entering the live PRIMARY/NEXT acquisition
 * state machine. The initial SL best-match batch may contain only one side of the eventual
 * recommendation, especially for ARRIVE_BY. Two complementary, bounded profiles can fill
 * those blind spots:
 *
 * 1. A `leastinterchange` request at the original planned instant can reveal a useful
 *    earlier/simple candidate hidden behind SL's default `leasttime` top three.
 * 2. A departure request from RECOMMENDED's minute can reveal the closest later
 *    opportunity. If that is the exact initial LEAVE_AT probe, the next request minute is
 *    used so CandidateCollector can still deduplicate identical upstream queries.
 *
 * Each profile is requested only while its side is missing, every result is re-evaluated
 * under the original deadline/lower bound, and at most two real follow-up calls are spent.
 * Modes and change preferences remain those of the Event request; neighbors may therefore
 * use different routes, unlike live NEXT.
 */
async function resolvePlannedSelection(
  collector: CandidateCollector,
  transportModes: readonly JourneyTransportMode[],
  changesPreference: JourneyChangesPreference,
  searchMode: Exclude<JourneySearchMode, "NOW">,
  requestedDateTime: Date,
): Promise<{ selection: PlannedJourneySelection<RankableNormalizedJourney>; plannedCalls: number }> {
  const derive = () => selectPlannedJourneyChoices(toRankable(collector.pool), searchMode, requestedDateTime);
  let selection = derive();
  let plannedCalls = 0;

  if (selection.earlier == null && !collector.budgetExhausted) {
    const callsBefore = collector.batchesUsedSoFar;
    await collector.fetchBatch({
      transportModes,
      maxChanges: requestMaxChanges(changesPreference),
      departureAt: requestedDateTime,
      dateTimeMode: searchMode === "ARRIVE_BY" ? "ARRIVAL" : "DEPARTURE",
      routeType: "leastinterchange",
    });
    plannedCalls += collector.batchesUsedSoFar - callsBefore;
    selection = derive();
  }

  if (selection.recommended != null && selection.later == null && plannedCalls < 2 && !collector.budgetExhausted) {
    const callsBefore = collector.batchesUsedSoFar;
    const recommendedMinute = floorToStockholmRequestMinute(new Date(selection.recommended.departureTime));
    const result = await collector.fetchBatch({
      transportModes,
      maxChanges: requestMaxChanges(changesPreference),
      departureAt: recommendedMinute,
      dateTimeMode: "DEPARTURE",
    });
    if (result.skipped && !collector.budgetExhausted) {
      await collector.fetchBatch({
        transportModes,
        maxChanges: requestMaxChanges(changesPreference),
        departureAt: nextStockholmRequestMinute(recommendedMinute),
        dateTimeMode: "DEPARTURE",
      });
    }
    plannedCalls += collector.batchesUsedSoFar - callsBefore;
    selection = derive();
  }

  return { selection, plannedCalls };
}

/** The structured event this route emits once per request, purely for measuring real-world
 * SL request volume before release — see this route's own doc. Deliberately carries only
 * counts and booleans: no station names, stop ids, journey payloads, or anything else that
 * could identify a specific user's route. Never included in the public API response. */
export interface JourneyAcquisitionMetrics {
  event: "journey_acquisition_metrics";
  /** Total real SL requests this one `/journeys` request spent, across the initial batch
   * and every acquisition phase/retarget — always `initialCalls + nextCalls +
   * alternativeCalls + primaryDiscoveryCalls + plannedCalls + laterCalls`, kept as its own field so a
   * consumer never has to re-derive it. */
  slCalls: number;
  initialCalls: number;
  nextCalls: number;
  alternativeCalls: number;
  /** Real follow-up requests spent on the separate planned Event chooser. Always zero for
   * LIVE requests and bounded to at most two for PLANNED requests. */
  plannedCalls: number;
  /** Foreground-only real requests spent looking for role-free later journeys. */
  laterCalls: number;
  laterRequested: number;
  laterReturned: number;
  primaryFound: boolean;
  nextFound: boolean;
  alternativeFound: boolean;
  authoritativeJourneyCount: number;
  /** See `liveJourneyAcquisition.ts`'s PRIMARY_DISCOVERY state — real SL requests spent on the
   * `WITH_CHANGES_ONLY`-only bounded forward search for an initial PRIMARY. Zero for every
   * other case, including a `WITH_CHANGES_ONLY` request whose initial batch already
   * contained an eligible candidate. */
  primaryDiscoveryCalls: number;
  /** Equivalent to `primaryRetargets > 0` — kept as its own boolean since "did this happen
   * at all" and "how many times" are usually aggregated differently downstream. */
  primaryChanged: boolean;
  primaryRetargets: number;
  budgetExhausted: boolean;
}

export type EmitJourneyAcquisitionMetrics = (metrics: JourneyAcquisitionMetrics) => void;

/** Default sink: a single structured JSON line to stdout, identifiable by its own `event`
 * field, small and replaceable by a real metrics backend later without touching any caller
 * of `emitMetrics` — see this route's own doc. Matches this backend's existing
 * `console.*`-based observability convention (see errorHandler.ts) rather than introducing
 * a new logging framework for this one event. */
function logJourneyAcquisitionMetrics(metrics: JourneyAcquisitionMetrics): void {
  console.log(JSON.stringify(metrics));
}

/** Builds the public response shape by explicitly picking each field, rather than
 * spreading the journey and excluding the internal-only ones (`pattern`,
 * `walkingDurationSeconds`, each leg's own `stopIds`) — see the product spec's own "keep
 * route metadata internal unless UI needs it" requirement. Picking explicitly means a
 * FUTURE internal-only field added to the normalized shape can never leak into the
 * response merely because this function wasn't also updated to exclude it. `role` belongs
 * to the request context: live responses use PRIMARY/NEXT/ALTERNATIVE, while planned Event
 * responses use EARLIER/RECOMMENDED/LATER. */
function toPublicJourneyData(journey: NormalizedJourney) {
  return {
    journeyId: journey.journeyId,
    originName: journey.originName,
    destinationName: journey.destinationName,
    departureTime: journey.departureTime,
    arrivalTime: journey.arrivalTime,
    transferCount: journey.transferCount,
    firstLeg: journey.firstLeg,
    legs: journey.legs.map((leg) => ({
      transportMode: leg.transportMode,
      lineDesignation: leg.lineDesignation,
      direction: leg.direction,
      originName: leg.originName,
      destinationName: leg.destinationName,
      departureTime: leg.departureTime,
      arrivalTime: leg.arrivalTime,
      isRealtime: leg.isRealtime,
      disruptions: leg.disruptions,
    })),
    disruptions: journey.disruptions,
    // Additive: the classified, deduplicated counterpart to `disruptions` above (see
    // normalizeJourney.ts's own JourneyDisruptionNotice doc) -- lets Android decide PRIMARY's
    // own live disruption relevance for the notification/widget/Routine Details without
    // re-implementing classification client-side.
    disruptionNotices: journey.disruptionNotices,
    // Additive: structural metadata for the separate POST /api/v1/journeys/disruptions lookup
    // -- see models/journeyDisruptionContext.ts's own doc. Android retains this unchanged with
    // whichever journey currently holds PRIMARY and sends it back verbatim; it never interprets
    // it itself.
    disruptionContext: journey.disruptionContext,
  };
}

function toPublicJourney(journey: NormalizedJourney, role: JourneyRole) {
  return { ...toPublicJourneyData(journey), role };
}

/**
 * `now` is captured once per request (never re-read mid-request) and is an injectable
 * `() => Date` — defaulted to the real wall clock in production, overridable in tests —
 * so acquisition and eligibility can be asserted deterministically rather than racing the
 * real clock (see journeys.test.ts).
 *
 * ## The live PRIMARY / NEXT / ALTERNATIVE model
 *
 * Replaces an earlier threshold-based design (a fixed "large gap" minute count, a fixed
 * minimum arrival-advantage minute count, and "transferCount === 0 means regular") with a
 * structural one: two journeys are route-compatible when their RoutePattern relation holds
 * (see backend/src/domain/routePattern.ts's own `isRouteCompatible` — a pairwise
 * compatibility check, NOT a globally transitive "route family" equivalence class) — same
 * public-transport leg count, same mode per leg, same boarding/alighting stop per leg, and
 * either an exact or a local/express-compatible stop sequence — regardless of line
 * designation.
 *
 * Pareto dominance (see backend/src/domain/dominance.ts) answers "is this candidate worth
 * keeping" — but ONLY for ALTERNATIVE, never globally. PRIMARY and NEXT are always selected
 * directly from the full eligible pool (see `liveJourneyAcquisition.ts`'s `deriveSelection`
 * documentation for exactly why a
 * global dominance pass is actively unsafe for NEXT, not merely unnecessary).
 *
 * - **PRIMARY**: the current regular route's own next departure — deterministic
 *   lexicographic selection (earliest arrival, then fewer transfers, then less known
 *   walking, then a later departure, then journeyId) over every eligible candidate (see
 *   backend/src/domain/journeyRoles.ts).
 * - **NEXT**: the earliest still-current departure route-compatible with PRIMARY that
 *   departs after it — never a route-incompatible journey, however that journey happened
 *   to be ordered in SL's own response, and never suppressed by a "better" same-family
 *   journey that simply departs later.
 * - **ALTERNATIVE**: a genuinely useful, route-INCOMPATIBLE journey that departs after
 *   PRIMARY, before NEXT, and arrives strictly before NEXT's own arrival —
 *   no minimum-minute advantage, no gap-size threshold. Only ever searched for once PRIMARY
 *   AND NEXT are both known: there is no "alternative" concept without a NEXT baseline. The
 *   only place dominance is applied globally-ish is here, scoped to just the candidates
 *   that already qualify as ALTERNATIVE — see `selectAlternative`'s own doc.
 *
 * ## Acquisition
 *
 * SL Journey Planner only ever returns up to 3 trips per request (`calc_number_of_trips`)
 * — it is a best-match proposal service, not exhaustive pagination, and its own request
 * precision is whole minutes (`itd_time` is HHMM — see stockholmTime.ts's own
 * `toItdDateTime`/`floorToStockholmRequestMinute` doc). The initial request asks across
 * every allowed mode, with `maxChanges` narrowed by `requestMaxChanges`, and is anchored at
 * this request's own single `requestedAt` instant — SL is never left to independently
 * resolve its own notion of "now" (see
 * slJourneyPlannerClient.ts's own `itd_date`/`itd_time` doc).
 *
 * For LIVE requests, if that alone doesn't establish NEXT, a second, TARGETED request
 * follows — anchored at PRIMARY's own departure
 * MINUTE (never a step derived from its exact second — a departure sharing PRIMARY's own
 * request minute is not skipped, and PRIMARY itself is correctly excluded from becoming its
 * own NEXT by `selectNext`'s own identity check, not by the anchor), narrowed to PRIMARY's
 * own route family's transport modes and transfer count (see
 * `liveJourneyAcquisition.ts`'s `transportModesUsedBy`) to reduce irrelevant results. This
 * can repeat in further batches (see
 * CandidateCollector.acquireUntil), each one UPSERTED into the collector's own shared pool
 * — a journey already known from an earlier batch has its entry REPLACED with whatever this
 * batch just returned for it, never left frozen at its first-seen values (see that class's
 * own doc) — with the WHOLE selection re-derived from scratch every time (see
 * `liveJourneyAcquisition.ts`'s `deriveSelection`). A newly-discovered journey can therefore
 * promote itself to PRIMARY (a
 * better lexicographic candidate) or NEXT (an earlier route-compatible departure) even if it
 * arrived in a later batch — and so can a journey ALREADY in the pool whose realtime data
 * simply changed: a delayed PRIMARY can lose its own role to a candidate that didn't move,
 * exactly as if that candidate had just been discovered for the first time. Acquisition for
 * NEXT stops the moment it's found, when the search cursor passes `searchUntil` (absent
 * entirely, this route answers from the initial batch alone rather than searching
 * unboundedly — see `parseSearchUntil`'s own doc), when the shared request budget is spent
 * (see CandidateCollector's own `MAX_ACQUISITION_BATCHES` doc), or when SL can no longer
 * make forward progress. It does NOT stop merely because a batch repeats an already-seen
 * set of journey ids — SL's own best-match results are not exhaustive, so an identical
 * response does not prove a further request couldn't still expose something new between
 * two previously-reported departures (see CandidateCollector.acquireUntil's own doc); only
 * the conditions just listed are trusted to mean "there is nothing more to find here". If
 * NEXT still cannot be established, the response contains PRIMARY alone — never an
 * unrelated journey mislabelled NEXT merely to fill a second slot.
 *
 * A PLANNED request never enters that live model or loop. It uses the separate
 * EARLIER/RECOMMENDED/LATER selector in `plannedJourneyChoices.ts`: ARRIVE_BY admits only
 * journeys arriving by the deadline, LEAVE_AT admits only journeys departing at or after
 * the requested time, RECOMMENDED uses deterministic planned-quality ordering, and the
 * closest distinct departures on either side become EARLIER and LATER regardless of route
 * family. Results are returned in chronological departure order. At most two follow-up SL
 * requests complement the initial best-match batch (see `resolvePlannedSelection`), with no
 * horizon or cursor loop.
 *
 * Only once PRIMARY and NEXT both exist does an ALTERNATIVE search run, using the full
 * allowed mode set (an alternative is, by definition, route-incompatible with PRIMARY, so it
 * is never narrowed to PRIMARY's own modes). NEXT's own departure supplies this search's own
 * upper bound directly — no `searchUntil` is needed once NEXT exists. Unlike the NEXT
 * search, this one does NOT stop the moment a qualifying candidate is found: a later batch
 * can still discover a route-compatible journey that reclassifies NEXT to an earlier departure,
 * which can invalidate a candidate that qualified against the OLD NEXT (it may now depart
 * after, or arrive later than, the NEW NEXT) — or can simply UPDATE NEXT's own realtime
 * arrival in place, which can equally invalidate a candidate that no longer arrives before
 * it — so every batch here only upserts and re-derives PRIMARY/NEXT, and the search's own
 * upper bound is re-read from the CURRENT NEXT before every request, shrinking immediately
 * if NEXT does.
 *
 * Both targeted searches are also retargeted whenever PRIMARY itself changes mid-search —
 * see `liveJourneyAcquisition.ts`'s `resolveSelection` documentation for the full state
 * machine. `selectAlternative` is only
 * ever evaluated once, after that state machine has settled on a PRIMARY/NEXT pair that is
 * mutually current and consistent — never against a NEXT (or a PRIMARY) that has since been
 * superseded.
 *
 * A single structured `journey_acquisition_metrics` line (see `JourneyAcquisitionMetrics`)
 * is emitted once per request via the injectable `emitMetrics` — real SL call volume before
 * release is otherwise invisible, since none of this acquisition behaviour is observable
 * from the public response shape.
 *
 * ## Changes preference
 *
 * `changesPreference` (see `parseChangesPreference`/`CandidateCollector`'s own
 * `JourneyChangesPreference` doc) narrows the ENTIRE eligible candidate pool — applied inside
 * `CandidateCollector.fetchBatch`, the single choke point every batch from every acquisition
 * phase upserts through — to only zero-change journeys (`DIRECT_ONLY`), only journeys requiring
 * at least one change (`WITH_CHANGES_ONLY`), or every eligible journey regardless of transfer
 * count (`BOTH`, the default). Because the filter applies before PRIMARY/NEXT/ALTERNATIVE are
 * ever selected, not after, a `DIRECT_ONLY` request's PRIMARY/NEXT/ALTERNATIVE are always
 * genuinely direct — never a mixed-preference selection with disallowed rows merely hidden from
 * the response afterward.
 *
 * That pool-level filter alone is not sufficient by itself, though: SL only ever returns up to
 * 3 best-match trips per request, with no notion of `changesPreference` at all, so an unfiltered
 * request can let 3 disallowed candidates fill every slot and silently crowd a genuinely
 * eligible one out of the response entirely before Blick's own filter ever sees it. Two
 * complementary fixes close that gap, one per direction:
 * - `DIRECT_ONLY` narrows the REQUEST itself — see `requestMaxChanges` — asking SL for
 *   `maxChanges: 0` everywhere (the initial batch, NEXT_DISCOVERY, and ALTERNATIVE_INTERVAL_DISCOVERY
 *   alike), so SL's own top-3 are already confined to the space Blick wants and can never be
 *   crowded out by a transfer journey that was never eligible to begin with.
 * - `WITH_CHANGES_ONLY` cannot be narrowed the same way — SL has no "minimum changes" request
 *   parameter — so instead, when the initial batch's own eligible pool comes back empty,
 *   `liveJourneyAcquisition.ts`'s PRIMARY_DISCOVERY phase probes
 *   forward, reusing the exact same bounded cursor/dedup/budget machinery NEXT_DISCOVERY
 *   already relies on, until either an eligible candidate is found or the search genuinely
 *   runs out of room (`searchUntil`, or the shared request budget).
 */
export function createJourneyRoutes(
  client: SlJourneyPlannerClient,
  now: () => Date = () => new Date(),
  emitMetrics: EmitJourneyAcquisitionMetrics = logJourneyAcquisitionMetrics,
) {
  const route = new Hono();
  route.get("/locations/search", async (c) => {
    const query = required(c.req.query("query"), "query", 100);
    const locations = (await client.searchStops(query)).map((location) => ({
      id: location.id,
      name: location.disassembledName ?? location.name,
    }));
    c.header("Cache-Control", "public, s-maxage=3600, stale-while-revalidate=86400");
    return c.json(successEnvelope({ query, locations }));
  });
  route.get("/", async (c) => {
    const originId = required(c.req.query("originId"), "originId");
    const destinationId = required(c.req.query("destinationId"), "destinationId");
    const transportModes = requestedTransportModes(c.req.query("transportModes"));
    const searchUntil = parseSearchUntil(c.req.query("searchUntil"));
    const changesPreference = parseChangesPreference(c.req.query("changesPreference"));
    const laterJourneyCount = parseLaterJourneyCount(c.req.query("laterJourneyCount"));
    if (originId === destinationId) throw new AppError("VALIDATION_ERROR", "Origin and destination must differ");

    // One timestamp for the whole request: every eligibility check and acquisition anchor
    // below is measured against this same instant, never a freshly re-read wall clock.
    const fetchedAt = now();
    const search = parseJourneySearch(
      c.req.query("searchMode"),
      c.req.query("requestedDateTime"),
      searchUntil,
      fetchedAt,
    );
    if (search.journeyContext === "PLANNED" && laterJourneyCount !== 0) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'laterJourneyCount' is only valid for NOW searches");
    }
    const selectedJourneys: Array<{ journey: NormalizedJourney; role: JourneyRole }> = [];
    let laterJourneys: NormalizedJourney[] = [];
    let slCalls: number;
    let initialCalls: number;
    let nextCalls = 0;
    let alternativeCalls = 0;
    let plannedCalls = 0;
    let primaryDiscoveryCalls = 0;
    let primaryRetargets = 0;
    let laterCalls = 0;
    let budgetExhausted: boolean;

    if (search.journeyContext === "LIVE") {
      const result = await acquireAuthoritativeLiveJourneys(client, {
        originId,
        destinationId,
        transportModes,
        changesPreference,
        searchUntil,
        fetchedAt,
        laterJourneyCount,
      });
      selectedJourneys.push(...result.journeys);
      laterJourneys = [...result.laterJourneys];
      slCalls = result.stats.slCalls;
      initialCalls = result.stats.initialCalls;
      nextCalls = result.stats.nextCalls;
      alternativeCalls = result.stats.alternativeCalls;
      primaryDiscoveryCalls = result.stats.primaryDiscoveryCalls;
      primaryRetargets = result.stats.primaryRetargets;
      laterCalls = result.stats.laterCalls;
      budgetExhausted = result.stats.budgetExhausted;
    } else {
      if (search.searchMode === "NOW" || search.requestedDateTime == null) {
        throw new Error("Planned journey search is missing its planned-time contract");
      }
      const eligibilityStart = search.searchMode === "LEAVE_AT" ? search.requestedDateTime : fetchedAt;
      const collector = new CandidateCollector(
        client,
        originId,
        destinationId,
        eligibilityStart.getTime(),
        changesPreference,
      );
      await collector.fetchBatch({
        transportModes,
        maxChanges: requestMaxChanges(changesPreference),
        departureAt: search.requestedDateTime,
        dateTimeMode: search.searchMode === "ARRIVE_BY" ? "ARRIVAL" : "DEPARTURE",
      });
      initialCalls = collector.batchesUsedSoFar;
      const plannedResolution = await resolvePlannedSelection(
        collector,
        transportModes,
        changesPreference,
        search.searchMode,
        search.requestedDateTime,
      );
      selectedJourneys.push(...plannedResolution.selection.choices);
      plannedCalls = plannedResolution.plannedCalls;
      slCalls = collector.batchesUsedSoFar;
      budgetExhausted = collector.budgetExhausted;
    }

    const journeys = selectedJourneys.map(({ journey, role }) => toPublicJourney(journey, role));
    const primaryFound = selectedJourneys.some(({ role }) => role === "PRIMARY");
    const nextFound = selectedJourneys.some(({ role }) => role === "NEXT");
    const alternativeFound = selectedJourneys.some(({ role }) => role === "ALTERNATIVE");

    emitMetrics({
      event: "journey_acquisition_metrics",
      slCalls,
      initialCalls,
      nextCalls,
      alternativeCalls,
      plannedCalls,
      laterCalls,
      laterRequested: search.journeyContext === "LIVE" ? laterJourneyCount : 0,
      laterReturned: laterJourneys.length,
      primaryFound,
      nextFound,
      alternativeFound,
      authoritativeJourneyCount: journeys.length,
      primaryDiscoveryCalls,
      primaryChanged: primaryRetargets > 0,
      primaryRetargets,
      budgetExhausted,
    });

    c.header("Cache-Control", "public, s-maxage=30, stale-while-revalidate=30");
    return c.json(
      successEnvelope({
        fetchedAt: search.fetchedAt.toISOString(),
        journeyContext: search.journeyContext,
        searchMode: search.searchMode,
        requestedDateTime: search.requestedDateTime?.toISOString() ?? null,
        journeys,
        laterJourneys: laterJourneys.map(toPublicJourneyData),
      }),
    );
  });
  return route;
}
