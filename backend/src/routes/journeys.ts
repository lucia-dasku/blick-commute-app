import { Hono } from "hono";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import { buildRoutePattern, type RoutePattern } from "../domain/routePattern.js";
import { selectAlternative, selectLaterJourneys, selectNext, selectPrimary } from "../domain/journeyRoles.js";
import {
  selectPlannedJourneyChoices,
  type PlannedJourneyRole,
  type PlannedJourneySelection,
} from "../domain/plannedJourneyChoices.js";
import {
  CandidateCollector,
  MAX_CHANGES,
  requestMaxChanges,
  type JourneyChangesPreference,
  type NormalizedJourney,
} from "../services/candidateCollector.js";
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

export type LiveJourneyRole = "PRIMARY" | "NEXT" | "ALTERNATIVE";
export type JourneyRole = LiveJourneyRole | PlannedJourneyRole;

/** A full normalized journey plus its RoutePattern — structurally satisfies
 * [RankableJourney] (see backend/src/domain/journeyRoles.ts), so it can be passed
 * directly to selectPrimary/selectNext/selectAlternative, but also keeps every field
 * (`legs`, `firstLeg`, `disruptions`, ...) the final response needs to build from. */
type RankableNormalizedJourney = NormalizedJourney & { pattern: RoutePattern };

function toRankable(journeys: NormalizedJourney[]): RankableNormalizedJourney[] {
  return journeys.map((journey) => ({ ...journey, pattern: buildRoutePattern(journey) }));
}

interface Selection {
  rankablePool: RankableNormalizedJourney[];
  primary: RankableNormalizedJourney | undefined;
  next: RankableNormalizedJourney | undefined;
}

/**
 * Re-derives the full PRIMARY/NEXT selection from the collector's own current candidate
 * pool: selects live PRIMARY, then (if PRIMARY exists) live NEXT (see
 * backend/src/domain/journeyRoles.ts),
 * directly from the full eligible pool — deliberately
 * WITHOUT a global Pareto-dominance pass first. Called after every acquisition batch, never
 * only once, so a newly-discovered OR newly-UPDATED candidate can always reclassify an
 * earlier choice — PRIMARY/NEXT are never frozen merely because they were selected from an
 * early batch, and a later batch's fresher realtime data for an already-known journey (see
 * backend/src/services/candidateCollector.ts's own upsert doc) is exactly as able to change
 * the outcome as a brand-new candidate would be (see this route's own doc).
 *
 * Global dominance filtering before PRIMARY/NEXT selection was removed deliberately: it is
 * safe for PRIMARY (whose own lexicographic order already ranks a dominated candidate no
 * better than its dominator — see `selectPrimary`'s own doc) but NOT safe for NEXT, whose
 * own criterion is "the soonest route-compatible departure", a genuinely different question
 * from "which candidate is objectively best overall" — see `selectNext`'s own doc for the
 * two concrete failure modes this avoids (a later-but-faster same-family departure
 * wrongly suppressing the true next departure; an unrelated, route-incompatible journey
 * doing the same). Dominance is still used, but scoped only to ALTERNATIVE candidates
 * inside `selectAlternative` itself, once PRIMARY and NEXT are already fixed — see that
 * function's own doc.
 */
function deriveSelection(pool: NormalizedJourney[]): Selection {
  const rankablePool = toRankable(pool);
  const primary = selectPrimary(rankablePool);
  const next = primary == null ? undefined : selectNext(rankablePool, primary);
  return { rankablePool, primary, next };
}

/**
 * The transport modes PRIMARY's own route family actually uses — for narrowing a targeted
 * NEXT search to "what SL can actually filter on" (see this route's own doc). Never used
 * as proof of route compatibility by itself: every candidate SL returns still goes
 * through the real `isRouteCompatible` check regardless of this narrowing (see
 * backend/src/services/candidateCollector.ts's own doc). Falls back to [fallback] (the
 * request's own full allowed set) on the defensive edge case where PRIMARY's pattern
 * resolves to no recognized SL inclusion-flag mode at all, so the search is never
 * narrowed to an empty, unsatisfiable set.
 */
function transportModesUsedBy(pattern: RoutePattern, fallback: readonly JourneyTransportMode[]): JourneyTransportMode[] {
  const recognized = new Set<JourneyTransportMode>();
  for (const leg of pattern.legs) {
    if ((journeyTransportModes as readonly string[]).includes(leg.transportMode)) {
      recognized.add(leg.transportMode as JourneyTransportMode);
    }
  }
  return recognized.size > 0 ? [...recognized] : [...fallback];
}

interface AcquisitionResult {
  selection: Selection;
  /** Real SL requests spent inside NEXT_DISCOVERY, summed across every attempt — including
   * every abandoned attempt a PRIMARY retarget cut short (see `resolveSelection`'s own
   * doc). Never reset back to zero on a retarget: it is a running total for the whole
   * `/journeys` request, exactly like the shared budget it draws from. */
  nextCalls: number;
  /** Same accounting as [nextCalls], for ALTERNATIVE_INTERVAL_DISCOVERY. */
  alternativeCalls: number;
  /** Real SL requests spent on the `WITH_CHANGES_ONLY`-only bounded forward search for an
   * initial PRIMARY, when the initial batch's own eligible pool came back empty — see
   * `resolveSelection`'s own doc. Always zero for `BOTH`/`DIRECT_ONLY` requests, and zero for
   * `WITH_CHANGES_ONLY` too whenever the initial batch already contained an eligible
   * candidate. */
  primaryDiscoveryCalls: number;
  /** How many times the acquisition loop discovered that its CURRENT PRIMARY was no
   * longer the current PRIMARY (a different journeyId) partway through a targeted search,
   * and had to abandon that search and retarget — see `resolveSelection`'s own doc. Zero
   * in the common case where PRIMARY never changes after the initial batch. */
  primaryRetargets: number;
}

interface LaterAcquisitionResult {
  selection: Selection;
  laterJourneys: RankableNormalizedJourney[];
  laterCalls: number;
  primaryRetargets: number;
}

/**
 * A PRIMARY's own identity for acquisition-targeting purposes — see `resolveSelection`'s
 * own doc. `journeyId` alone decides whether PRIMARY "changed" (a realtime update to an
 * unchanged journey's own times must never trigger a retarget); `transportModes` and
 * `transferCount` are compared too, defensively, in case SL ever reassigns the same trip id
 * to a structurally different journey — see backend/src/services/candidateCollector.ts's
 * own doc on why upsert can only ever replace by id, never verify the replacement is
 * "the same journey" in any deeper sense.
 */
interface PrimaryTarget {
  journeyId: string;
  transportModes: readonly JourneyTransportMode[];
  transferCount: number;
}

function primaryTargetOf(primary: RankableNormalizedJourney, fallbackModes: readonly JourneyTransportMode[]): PrimaryTarget {
  return { journeyId: primary.journeyId, transportModes: transportModesUsedBy(primary.pattern, fallbackModes), transferCount: primary.transferCount };
}

function sameTarget(a: PrimaryTarget, b: PrimaryTarget): boolean {
  return (
    a.journeyId === b.journeyId &&
    a.transferCount === b.transferCount &&
    a.transportModes.length === b.transportModes.length &&
    a.transportModes.every((mode, i) => mode === b.transportModes[i])
  );
}

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

/**
 * Drives NEXT/ALTERNATIVE acquisition to a state that is internally consistent with the
 * CURRENT PRIMARY — never a NEXT or ALTERNATIVE evaluated against a PRIMARY that has since
 * been superseded. `deriveSelection` (see its own doc) already re-derives PRIMARY and NEXT
 * from the whole pool after every batch, so realtime data — not just newly-discovered
 * candidates — can promote a different journey to PRIMARY mid-acquisition. The two targeted
 * searches (`transportModesUsedBy`, `primary.transferCount`, the request anchor) are built
 * from whichever journey was PRIMARY when a search STARTED; if that journey stops being
 * PRIMARY before the search finishes, continuing to spend the search on ITS OWN
 * characteristics would be searching for the wrong thing entirely — a metro-targeted search
 * for a PRIMARY that has since become a bus serves no one.
 *
 * Modelled as an explicit loop over two phases, re-evaluated from scratch every time either
 * phase's own targeted search ends for ANY reason:
 *
 * - **NEXT_DISCOVERY** (current PRIMARY has no NEXT yet): fails closed immediately (PRIMARY
 *   alone) when there is no `searchUntil` boundary to search within, or the shared budget is
 *   already spent. Otherwise searches, narrowed to the current PRIMARY's own transport
 *   modes/transfer count, anchored at its own floored departure minute. The search's own
 *   `isSatisfied` callback re-derives selection after every batch and reports "satisfied"
 *   (stopping the search) the moment EITHER a NEXT is found for this SAME PRIMARY, OR
 *   PRIMARY itself changes (a different journeyId) — the latter is not success, merely the
 *   earliest safe point to abandon a now-pointless search. After the search ends: if PRIMARY
 *   changed, retarget (loop back and re-evaluate from the top, now snapshotting the NEW
 *   PRIMARY); if a NEXT now exists for the SAME PRIMARY, loop back too (the top of the loop
 *   will move to ALTERNATIVE_INTERVAL_DISCOVERY); otherwise this PRIMARY's own NEXT search
 *   has genuinely run its course (boundary reached, budget exhausted, empty response, or no
 *   forward progress) without success — fail closed, PRIMARY alone.
 * - **ALTERNATIVE_INTERVAL_DISCOVERY** (current PRIMARY already has a NEXT): searches the
 *   full allowed mode set across the (PRIMARY, current NEXT) interval — see this route's own
 *   top-level doc for why ALTERNATIVE acquisition never stops merely because a qualifying
 *   candidate already exists. Its own `isSatisfied` callback reports "satisfied" the moment
 *   PRIMARY changes, OR the current PRIMARY's own NEXT disappears (a same-family journey
 *   that used to qualify no longer does, e.g. it was upserted into ineligibility). After the
 *   search ends: if PRIMARY changed, retarget (loop back — the new PRIMARY may already have
 *   its own NEXT in the pool, moving straight back to ALTERNATIVE_INTERVAL_DISCOVERY without
 *   any wasted NEXT search, or may need one); if the SAME PRIMARY simply lost its NEXT, loop
 *   back too (the top of the loop will re-enter NEXT_DISCOVERY for that same PRIMARY);
 *   otherwise PRIMARY and NEXT are both stable and the search ran its full natural course —
 *   the loop ends here, and `selectAlternative` is safe to evaluate against this exact pair.
 *
 * The loop itself needs no separate iteration cap: every transition out of a phase requires
 * that phase's own targeted search to have actually run (consuming shared budget — see
 * backend/src/services/candidateCollector.ts's own `MAX_ACQUISITION_BATCHES` doc), so the
 * number of times this can retarget is itself bounded by the same shared budget every real
 * request already draws from. Once that budget is exhausted, every further search becomes a
 * no-op and the loop settles within at most one more pass.
 *
 * ## PRIMARY_DISCOVERY (`WITH_CHANGES_ONLY` only)
 *
 * Runs BEFORE the loop above, and only when [collector]'s pool is still empty after the
 * initial batch — i.e. `deriveSelection` found no PRIMARY at all yet. SL has no "minimum
 * changes" request parameter (see `requestMaxChanges`'s own doc), so under
 * `WITH_CHANGES_ONLY` an initial batch containing only direct journeys is NOT proof that no
 * with-changes journey exists — it may simply be that SL's own top-3 best-match picks
 * happened to all be direct, with a perfectly good 1-2-change journey one request further
 * out. This reuses the exact same bounded, deduplicated, budget-shared `acquireUntil`
 * machinery NEXT_DISCOVERY below already relies on — anchored at the request's own
 * `requestedAt` instant, searching the full allowed mode set (there is no PRIMARY yet to
 * narrow to), satisfied the moment the shared pool contains anything at all (every entry in
 * it is already both current and `changesPreference`-eligible, per
 * `CandidateCollector.fetchBatch`'s own upsert gate) — and fails closed exactly like
 * NEXT_DISCOVERY when there is no `searchUntil` boundary to search within, rather than
 * inventing one. The very first `acquireUntil` fetch this issues targets the SAME
 * (bucket, options) the initial batch already queried, so it is recognized as a genuine
 * duplicate and skipped at no budget cost — see `CandidateCollector.fetchBatch`'s own
 * `probeKey` doc — before cursor advancement moves on to genuinely new territory. Once this
 * either finds something or gives up, `selection` is re-derived once more and the loop below
 * proceeds completely normally from whatever it settled on — no special-casing needed beyond
 * this one preliminary phase.
 */
async function resolveSelection(
  collector: CandidateCollector,
  transportModes: readonly JourneyTransportMode[],
  searchUntil: Date | null,
  changesPreference: JourneyChangesPreference,
  requestedAt: Date,
): Promise<AcquisitionResult> {
  let selection = deriveSelection(collector.pool);
  let nextCalls = 0;
  let alternativeCalls = 0;
  let primaryDiscoveryCalls = 0;
  let primaryRetargets = 0;

  if (changesPreference === "WITH_CHANGES_ONLY" && selection.primary == null && searchUntil != null && !collector.budgetExhausted) {
    const callsBefore = collector.batchesUsedSoFar;
    await collector.acquireUntil({ transportModes, maxChanges: MAX_CHANGES }, requestedAt, searchUntil, (pool) => pool.length > 0);
    primaryDiscoveryCalls = collector.batchesUsedSoFar - callsBefore;
    selection = deriveSelection(collector.pool);
  }

  while (selection.primary != null) {
    const primary = selection.primary;
    const target = primaryTargetOf(primary, transportModes);

    if (selection.next == null) {
      // NEXT_DISCOVERY, targeted to the CURRENT primary alone.
      if (searchUntil == null || collector.budgetExhausted) break;

      const callsBefore = collector.batchesUsedSoFar;
      await collector.acquireUntil(
        { transportModes: target.transportModes, maxChanges: target.transferCount },
        floorToStockholmRequestMinute(new Date(primary.departureTime)),
        searchUntil,
        (pool) => {
          selection = deriveSelection(pool);
          if (selection.primary == null) return true;
          if (!sameTarget(primaryTargetOf(selection.primary, transportModes), target)) return true;
          return selection.next != null;
        },
      );
      nextCalls += collector.batchesUsedSoFar - callsBefore;

      if (selection.primary == null) break;
      if (!sameTarget(primaryTargetOf(selection.primary, transportModes), target)) {
        primaryRetargets++;
        continue;
      }
      if (selection.next != null) continue;
      break; // this PRIMARY's own NEXT search ran its course without success -- fail closed
    }

    // ALTERNATIVE_INTERVAL_DISCOVERY: PRIMARY already has a NEXT.
    const next = selection.next;
    const initialNext = next;
    if (!collector.budgetExhausted) {
      const callsBefore = collector.batchesUsedSoFar;
      await collector.acquireUntil(
        { transportModes, maxChanges: requestMaxChanges(changesPreference) },
        floorToStockholmRequestMinute(new Date(primary.departureTime)),
        () => new Date((selection.next ?? initialNext).departureTime),
        (pool) => {
          selection = deriveSelection(pool);
          if (selection.primary == null) return true;
          if (!sameTarget(primaryTargetOf(selection.primary, transportModes), target)) return true;
          if (selection.next == null) return true;
          return false;
        },
      );
      alternativeCalls += collector.batchesUsedSoFar - callsBefore;
    }

    if (selection.primary == null) break;
    if (!sameTarget(primaryTargetOf(selection.primary, transportModes), target)) {
      primaryRetargets++;
      continue;
    }
    if (selection.next == null) continue; // lost its NEXT -- back to NEXT_DISCOVERY, same primary
    break; // PRIMARY and NEXT both stable -- the ALTERNATIVE search ran its full course
  }

  return { selection, nextCalls, alternativeCalls, primaryDiscoveryCalls, primaryRetargets };
}

/** Extends the already-settled live candidate pool with a small, foreground-only reserve.
 * It reuses CandidateCollector's query dedupe, cursor rules, upserts and shared budget. The
 * optional missing-NEXT probe is deliberately handled by deriveSelection: Android never
 * promotes a supplemental row, and no later rows are returned unless a final authoritative
 * NEXT exists. */
async function resolveLaterJourneys(
  collector: CandidateCollector,
  initialSelection: Selection,
  transportModes: readonly JourneyTransportMode[],
  searchUntil: Date | null,
  requestedCount: number,
): Promise<LaterAcquisitionResult> {
  let selection = initialSelection;
  let laterCalls = 0;
  let primaryRetargets = 0;
  const callCap = requestedCount === 1 ? 1 : 2;

  const selectedLater = () => {
    if (selection.primary == null || selection.next == null) return [];
    const alternative = selectAlternative(selection.rankablePool, selection.primary, selection.next);
    return selectLaterJourneys(selection.rankablePool, selection.primary, selection.next, requestedCount, alternative);
  };

  if (requestedCount === 0 || selection.primary == null || selectedLater().length >= requestedCount) {
    return { selection, laterJourneys: selectedLater(), laterCalls, primaryRetargets };
  }

  while (selection.primary != null && laterCalls < callCap && !collector.budgetExhausted) {
    const primary = selection.primary;
    const target = primaryTargetOf(primary, transportModes);
    const anchor = floorToStockholmRequestMinute(new Date((selection.next ?? primary).departureTime));
    const callsBefore = collector.batchesUsedSoFar;
    await collector.acquireUntil(
      { transportModes: target.transportModes, maxChanges: target.transferCount },
      anchor,
      searchUntil,
      (pool) => {
        selection = deriveSelection(pool);
        if (selection.primary == null) return true;
        if (!sameTarget(primaryTargetOf(selection.primary, transportModes), target)) return true;
        return selection.next != null && selectedLater().length >= requestedCount;
      },
      callCap - laterCalls,
    );
    const callsMade = collector.batchesUsedSoFar - callsBefore;
    laterCalls += callsMade;
    selection = deriveSelection(collector.pool);

    if (selection.primary == null) break;
    if (!sameTarget(primaryTargetOf(selection.primary, transportModes), target)) {
      primaryRetargets++;
      continue;
    }
    if (callsMade === 0 || selectedLater().length >= requestedCount) break;
  }

  return { selection, laterJourneys: selectedLater(), laterCalls, primaryRetargets };
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
  /** See `resolveSelection`'s own PRIMARY_DISCOVERY doc — real SL requests spent on the
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
function toPublicJourneyData(journey: RankableNormalizedJourney) {
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

function toPublicJourney(journey: RankableNormalizedJourney, role: JourneyRole) {
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
 * directly from the full eligible pool (see `deriveSelection`'s own doc for exactly why a
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
 * `toItdDateTime`/`floorToStockholmRequestMinute` doc). The initial request asks broadly
 * (every allowed mode, `MAX_CHANGES`) anchored at this request's own single `requestedAt`
 * instant — SL is never left to independently resolve its own notion of "now" (see
 * slJourneyPlannerClient.ts's own `itd_date`/`itd_time` doc).
 *
 * For LIVE requests, if that alone doesn't establish NEXT, a second, TARGETED request
 * follows — anchored at PRIMARY's own departure
 * MINUTE (never a step derived from its exact second — a departure sharing PRIMARY's own
 * request minute is not skipped, and PRIMARY itself is correctly excluded from becoming its
 * own NEXT by `selectNext`'s own identity check, not by the anchor), narrowed to PRIMARY's
 * own route family's transport modes and transfer count (see `transportModesUsedBy`) to
 * reduce irrelevant results. This can repeat in further batches (see
 * CandidateCollector.acquireUntil), each one UPSERTED into the collector's own shared pool
 * — a journey already known from an earlier batch has its entry REPLACED with whatever this
 * batch just returned for it, never left frozen at its first-seen values (see that class's
 * own doc) — with the WHOLE selection re-derived from scratch every time (see
 * `deriveSelection`). A newly-discovered journey can therefore promote itself to PRIMARY (a
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
 * see `resolveSelection`'s own doc for the full state machine. `selectAlternative` is only
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
 *   `resolveSelection`'s own PRIMARY_DISCOVERY phase (see that function's own doc) probes
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
    const eligibilityStart = search.searchMode === "LEAVE_AT" ? search.requestedDateTime! : fetchedAt;
    const collector = new CandidateCollector(client, originId, destinationId, eligibilityStart.getTime(), changesPreference);

    // requestMaxChanges(changesPreference), not the bare MAX_CHANGES ceiling -- see that
    // function's own doc for why DIRECT_ONLY must narrow this very first request to
    // maxChanges: 0 rather than asking broadly and filtering the response afterward.
    const requestAnchor = search.requestedDateTime ?? fetchedAt;
    await collector.fetchBatch({
      transportModes,
      maxChanges: requestMaxChanges(changesPreference),
      departureAt: requestAnchor,
      dateTimeMode: search.searchMode === "ARRIVE_BY" ? "ARRIVAL" : "DEPARTURE",
    });
    const initialCalls = collector.batchesUsedSoFar;

    let selection: Selection | undefined;
    let plannedSelection: PlannedJourneySelection<RankableNormalizedJourney> | undefined;
    let nextCalls = 0;
    let alternativeCalls = 0;
    let plannedCalls = 0;
    let primaryDiscoveryCalls = 0;
    let primaryRetargets = 0;
    let laterCalls = 0;
    let laterJourneys: RankableNormalizedJourney[] = [];

    if (search.journeyContext === "LIVE") {
      const liveResolution = await resolveSelection(
        collector,
        transportModes,
        searchUntil,
        changesPreference,
        fetchedAt,
      );
      selection = liveResolution.selection;
      nextCalls = liveResolution.nextCalls;
      alternativeCalls = liveResolution.alternativeCalls;
      primaryDiscoveryCalls = liveResolution.primaryDiscoveryCalls;
      primaryRetargets = liveResolution.primaryRetargets;
      const laterResolution = await resolveLaterJourneys(
        collector,
        selection,
        transportModes,
        searchUntil,
        laterJourneyCount,
      );
      selection = laterResolution.selection;
      laterJourneys = laterResolution.laterJourneys;
      laterCalls = laterResolution.laterCalls;
      primaryRetargets += laterResolution.primaryRetargets;
    } else {
      if (search.searchMode === "NOW" || search.requestedDateTime == null) {
        throw new Error("Planned journey search is missing its planned-time contract");
      }
      const plannedResolution = await resolvePlannedSelection(
        collector,
        transportModes,
        changesPreference,
        search.searchMode,
        search.requestedDateTime,
      );
      plannedSelection = plannedResolution.selection;
      plannedCalls = plannedResolution.plannedCalls;
    }

    const alternative =
      selection?.primary != null && selection.next != null
        ? selectAlternative(selection.rankablePool, selection.primary, selection.next)
        : undefined;

    const journeys: ReturnType<typeof toPublicJourney>[] = [];
    if (selection?.primary != null) {
      journeys.push(toPublicJourney(selection.primary, "PRIMARY"));
      if (alternative != null) journeys.push(toPublicJourney(alternative, "ALTERNATIVE"));
      if (selection.next != null) journeys.push(toPublicJourney(selection.next, "NEXT"));
    } else if (plannedSelection != null) {
      for (const choice of plannedSelection.choices) {
        journeys.push(toPublicJourney(choice.journey, choice.role));
      }
    }

    emitMetrics({
      event: "journey_acquisition_metrics",
      slCalls: collector.batchesUsedSoFar,
      initialCalls,
      nextCalls,
      alternativeCalls,
      plannedCalls,
      laterCalls,
      laterRequested: search.journeyContext === "LIVE" ? laterJourneyCount : 0,
      laterReturned: laterJourneys.length,
      primaryFound: selection?.primary != null,
      nextFound: selection?.next != null,
      alternativeFound: alternative != null,
      authoritativeJourneyCount: journeys.length,
      primaryDiscoveryCalls,
      primaryChanged: primaryRetargets > 0,
      primaryRetargets,
      budgetExhausted: collector.budgetExhausted,
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
