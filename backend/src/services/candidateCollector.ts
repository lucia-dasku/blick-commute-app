import { normalizeJourney } from "../normalize/normalizeJourney.js";
import { floorToStockholmRequestMinute, nextStockholmRequestMinute } from "../lib/stockholmTime.js";
import type {
  JourneyDateTimeMode,
  JourneyRouteType,
  JourneyTransportMode,
  SlJourneyPlannerClient,
} from "./slJourneyPlannerClient.js";

export type NormalizedJourney = NonNullable<ReturnType<typeof normalizeJourney>>;

/** Mirrors the `max_changes` request parameter — the one explicit Blick product rule that
 * survives from the previous ranking approach (see backend/src/routes/journeys.ts's own
 * doc); everything else about "is this journey good enough" is now decided structurally
 * (route family) or by Pareto dominance, never by a threshold. Enforced again here,
 * defensively, since SL is not guaranteed to always honor the `max_changes` it was asked
 * for, and a cached upstream response could predate this parameter existing at all. */
export const MAX_CHANGES = 2;

/** True only for a journey whose first public-transport departure has not yet passed
 * `requestedAtMillis`, and that requires no more than `MAX_CHANGES` changes.
 * `journey.departureTime` is already normalizeJourney's own "effective first
 * public-transport departure" (derived from the first non-walking leg, not necessarily
 * legs[0] — see that function's own doc), so no further leg inspection is needed here. */
export function isEligibleJourney(journey: { departureTime: string; transferCount: number }, requestedAtMillis: number): boolean {
  return Date.parse(journey.departureTime) >= requestedAtMillis && journey.transferCount <= MAX_CHANGES;
}

/** A routine's persisted Direct/Both/With-changes preference (see
 * `android/app/src/main/java/se/blick/app/domain/model/CommuteRoutine.kt`'s own
 * `changesPreference` field) — the caller-supplied counterpart to `MAX_CHANGES`: that constant
 * is a fixed product-wide ceiling, while this is a per-request, per-routine choice of WHICH
 * transferCounts within that ceiling are eligible at all. `BOTH` is the pre-existing, unfiltered
 * behavior. */
export type JourneyChangesPreference = "DIRECT_ONLY" | "BOTH" | "WITH_CHANGES_ONLY";

/** Whether `transferCount` is eligible under `preference` — `DIRECT_ONLY` admits only a
 * zero-change journey, `WITH_CHANGES_ONLY` admits only one requiring at least one change, and
 * `BOTH` admits everything (subject to `isEligibleJourney`'s own separate `MAX_CHANGES` ceiling,
 * never re-checked here). A pure, minute predicate so [CandidateCollector] can apply it at the
 * exact same choke point `isEligibleJourney` already gates entry to the shared candidate pool at
 * — see [CandidateCollector.fetchBatch]'s own doc for why filtering there, before PRIMARY/NEXT/
 * ALTERNATIVE are ever selected from that pool, is what keeps the selected roles themselves
 * correct for the preference, rather than merely hiding disallowed rows from an otherwise
 * unfiltered selection after the fact. */
export function matchesChangesPreference(transferCount: number, preference: JourneyChangesPreference): boolean {
  switch (preference) {
    case "DIRECT_ONLY":
      return transferCount === 0;
    case "WITH_CHANGES_ONLY":
      return transferCount >= 1;
    case "BOTH":
      return true;
  }
}

/** The `max_changes` ceiling to actually REQUEST from SL for [preference] — distinct from, and
 * complementary to, `matchesChangesPreference`'s own pool-level filter above, which still
 * applies unconditionally regardless of this value (defense in depth, exactly like
 * `isEligibleJourney`'s own `MAX_CHANGES` re-check). SL Journey Planner only ever returns up to
 * 3 best-match trips per request (`calc_number_of_trips` — see journeys.ts's own doc); asking
 * broadly (up to `MAX_CHANGES`) and filtering the response afterward lets SL's own "best match"
 * picks — which have no notion of Blick's `changesPreference` at all — fill all 3 slots with
 * journeys requiring changes, silently crowding a genuinely eligible direct journey out of the
 * batch entirely before Blick's own filter ever gets a chance to see it (e.g. three transfer
 * journeys plus a fourth, later direct one: an unfiltered request only ever learns about the
 * first three). `DIRECT_ONLY` therefore narrows the REQUEST itself to `maxChanges: 0`, so SL's
 * own top-3 are already constrained to the space Blick actually wants and can never be crowded
 * out this way. `WITH_CHANGES_ONLY` and `BOTH` both still request the full `MAX_CHANGES`
 * ceiling — SL has no "minimum changes" request parameter, so a with-changes-only search cannot
 * be narrowed the same way at the request level; see journeys.ts's own bounded
 * PRIMARY-discovery doc for how that preference is instead handled, by continuing to search
 * forward rather than by asking SL differently. */
export function requestMaxChanges(preference: JourneyChangesPreference): number {
  return preference === "DIRECT_ONLY" ? 0 : MAX_CHANGES;
}

/** A purely operational safety backstop against a pathological SL response pattern (e.g.
 * repeatedly advancing by the minimum step without ever satisfying the caller) — NOT a
 * product/ranking threshold. `searchUntil`, NEXT's own departure as ALTERNATIVE's own
 * search bound, and "no forward progress" (see `acquireUntil`) are what actually bound
 * every real search; this exists only so a bug elsewhere can never turn into an unbounded
 * request storm against SL. Exported so tests can assert against the literal directly.
 *
 * Shared across a WHOLE `CandidateCollector` instance — i.e. a whole `/journeys` request —
 * never reset per `acquireUntil` call, so NEXT acquisition and ALTERNATIVE acquisition
 * spend from the SAME budget rather than each independently getting their own 30 requests.
 * The initial acquisition batch (fetched directly by the route handler, not through
 * `acquireUntil`) counts against it too: every `fetchBatch` call increments the same
 * counter regardless of which search invoked it, because what's being bounded is real
 * requests against SL, and the initial batch is as real a request as any other. See
 * `batchesUsedSoFar` / `budgetExhausted`. */
export const MAX_ACQUISITION_BATCHES = 30;

export interface CandidateBatchOptions {
  transportModes: readonly JourneyTransportMode[];
  maxChanges: number;
  departureAt: Date;
  dateTimeMode?: JourneyDateTimeMode;
  routeType?: JourneyRouteType;
  viaStopId?: string;
}

export interface CandidateBatchResult {
  /** The EARLIEST effective departure time among every journey SL returned in this batch
   * that falls STRICTLY AFTER this batch's own request-minute bucket (i.e. strictly after
   * `floorToStockholmRequestMinute(options.departureAt)`'s own minute) — computed across
   * every journey SL returned, BEFORE eligibility filtering, so the search cursor still
   * advances correctly even when every such result happened to be ineligible or already
   * known. See `acquireUntil`'s own doc for why the EARLIEST (never the latest) is what
   * cursor advancement must be based on: SL is a best-match proposal service, not
   * exhaustive pagination, so a relevant journey between the earliest and latest departure
   * this batch returned can easily be missing from it, and jumping straight past the
   * latest one would make that journey permanently undiscoverable. `null` when every
   * departure this batch returned falls within its own already-queried minute (nothing yet
   * known to advance toward), when SL returned no journeys in this batch at all, when the
   * shared request budget was already exhausted and no request was even sent (see
   * `budgetExhausted`), or when this exact query was skipped as a genuine duplicate (see
   * `skipped`). */
  earliestDepartureAfterCursor: Date | null;
  /** Every journey id SL returned in this batch, before eligibility filtering — used by
   * `acquireUntil` to detect a genuinely EMPTY SL response, one of the response-shape-based
   * termination signals this collector uses (see that method's own doc for why an
   * identical-but-non-empty response is deliberately NOT one). Empty when SL returned no
   * journeys in this batch at all, when the shared request budget was already exhausted,
   * or when this exact query was skipped as a duplicate (see `skipped`) — `acquireUntil`
   * distinguishes the latter from a genuine empty SL response via that flag, never by this
   * field alone. */
  rawJourneyIds: readonly string[];
  /** True when `fetchBatch` recognized this exact query (see `probeKey`'s own doc — the
   * same request-minute bucket AND the same transport modes/`maxChanges`/`routeType`/
   * `viaStopId`) had already been sent to SL earlier in this collector's lifetime, and
   * skipped sending a genuinely redundant duplicate rather than spending shared budget to
   * confirm an answer already known. `earliestDepartureAfterCursor` and `rawJourneyIds`
   * are always their empty/null defaults when this is true, but a skip must never be
   * confused with SL actually confirming this minute has nothing — `acquireUntil` must
   * still advance the cursor and keep searching forward, never terminate on a skip alone. */
  skipped: boolean;
}

export type AcquisitionOptions = Omit<CandidateBatchOptions, "departureAt">;

/**
 * Batches SL Journey Planner acquisition for one origin/destination pair — request,
 * normalize, mode-filter, and maintain ONE candidate pool, keyed by journeyId, across every
 * batch this ONE instance has ever fetched (never assumes list position is stable between
 * batches). One instance is shared for a whole request — the initial acquisition and any
 * later targeted searches for NEXT/ALTERNATIVE all upsert into the SAME pool AND share one
 * request budget (see `MAX_ACQUISITION_BATCHES`), so the same logical journey can never
 * appear twice in the final pool regardless of which search found it first, and one
 * request-heavy search can never silently starve another of its own separate budget. See
 * backend/src/routes/journeys.ts's own doc for how its caller uses this.
 *
 * The pool holds the MOST RECENTLY returned representation of each journeyId, never the
 * first — SL's own realtime data can change between two requests for what is structurally
 * the same journey (a delayed departure, an updated arrival estimate, a revised transfer
 * count), and Blick must reflect the latest known state, not freeze in whatever a batch
 * happened to report first. See `fetchBatch`'s own doc for exactly how an id already in the
 * pool is replaced (or removed) rather than ignored.
 */
export class CandidateCollector {
  private readonly candidatesById = new Map<string, NormalizedJourney>();
  /** Every DISTINCT SL query this instance has ever actually sent, identified by
   * `probeKey` — the Stockholm request-minute bucket it targeted COMBINED WITH every SL
   * parameter that can materially change which journeys come back (departure/arrival mode,
   * transport modes, `maxChanges`, `routeType`, `viaStopId`). Deliberately NOT keyed by minute alone: the
   * same minute queried once as a departure search and once as an arrival search, or once
   * narrowed to PRIMARY's own transport modes (a targeted NEXT
   * search) and once with the full allowed mode set (an ALTERNATIVE search) are two
   * genuinely different requests that can return different journeys — a bus a narrow
   * METRO-only NEXT search could never have returned is a real, valid discovery for a
   * later ALTERNATIVE search landing on that exact same minute, and must never be treated
   * as "already answered" merely because the minute coincides. See `probeKey`'s own doc
   * for the exact key shape, and `nextCursorAfter`'s own doc for why this matters most
   * once PRIMARY retargeting can rewind the cursor to an earlier point than territory an
   * earlier, now-abandoned search already covered. Shared across every phase and every
   * retarget on this instance, exactly like `candidatesById`/`batchesUsed`. */
  private readonly probedQueries = new Set<string>();
  private batchesUsed = 0;

  constructor(
    private readonly client: SlJourneyPlannerClient,
    private readonly originId: string,
    private readonly destinationId: string,
    private readonly requestedAtMillis: number,
    /** Defaults to `"BOTH"` (unfiltered, the pre-existing behavior) so every existing call site
     * that predates this parameter — direct construction in tests included — keeps compiling
     * and behaving exactly as before. */
    private readonly changesPreference: JourneyChangesPreference = "BOTH",
  ) {}

  /** The full accumulated candidate pool, across every batch this instance has ever
   * fetched from every acquisition phase (initial, NEXT, ALTERNATIVE) — one entry per
   * logical journeyId, always the MOST RECENTLY returned representation of it (see this
   * class's own doc). A caller re-derives its own selection (PRIMARY/NEXT/ALTERNATIVE)
   * from this after every batch, rather than threading its own separately-accumulated
   * array, so a later batch's updated data is always what selection actually sees — see
   * backend/src/routes/journeys.ts's own `deriveSelection`. Iteration order is each id's
   * first-ever insertion order (`Map`'s own guarantee); not meaningful to selection (every
   * selector here is order-independent) but stable enough for deterministic tests. */
  get pool(): NormalizedJourney[] {
    return [...this.candidatesById.values()];
  }

  /** Requests this instance has actually sent to SL so far, across every batch from every
   * search phase — see `MAX_ACQUISITION_BATCHES`'s own doc. Exposed so tests can assert
   * budget behaviour directly rather than inferring it from response shape or timing. */
  get batchesUsedSoFar(): number {
    return this.batchesUsed;
  }

  /** True once this instance has spent its whole shared SL-request budget — see
   * `MAX_ACQUISITION_BATCHES`'s own doc. Once true, `fetchBatch` sends no further requests
   * (returning an empty result instead) and `acquireUntil` returns whatever pool it has
   * already accumulated. */
  get budgetExhausted(): boolean {
    return this.batchesUsed >= MAX_ACQUISITION_BATCHES;
  }

  /** Requests one batch from SL, normalizes and mode-filters it, and UPSERTS the result
   * into this instance's own shared `pool`: a journeyId not yet in the pool is inserted; a
   * journeyId already in the pool has its entry REPLACED with this batch's own
   * representation (SL's realtime data wins over whatever was known before — see this
   * class's own doc) rather than being ignored as a duplicate. A journeyId whose LATEST
   * representation is no longer eligible (see `isEligibleJourney` — already departed
   * relative to this request, or now exceeds MAX_CHANGES — AND `matchesChangesPreference`,
   * gating on `this.changesPreference`) is instead REMOVED from the pool entirely: keeping a
   * stale, once-eligible representation around after fresher data shows it no longer
   * qualifies would be exactly the kind of staleness this upsert model exists to prevent.
   * Gating `changesPreference` at this exact choke point — never as a later filter over an
   * already-selected PRIMARY/NEXT/ALTERNATIVE — is what keeps those roles themselves correct
   * for the preference: a `DIRECT_ONLY` pool can never even contain a with-changes journey for
   * `selectPrimary`/`selectNext`/`selectAlternative` to (mis)select from in the first place. A
   * journeyId SL simply doesn't mention again is left untouched — no news is not the same as
   * bad news, and the last known representation remains the best available one.
   *
   * Before sending anything, checks whether this EXACT query (see `probeKey`'s own doc) has
   * already been made on this instance — if so, the request is skipped entirely (see
   * `CandidateBatchResult.skipped`) and NO shared-budget unit is spent; a genuinely
   * redundant round trip to SL is never worth its own budget slot. This is checked here,
   * not only inside `acquireUntil`'s own cursor-advancement logic (`nextCursorAfter`),
   * because a brand-new `acquireUntil` call's very FIRST fetch — e.g. immediately after a
   * PRIMARY retarget, anchored fresh at the new PRIMARY's own floored departure minute —
   * never goes through cursor advancement at all, and could otherwise repeat a query this
   * instance already made in an earlier, now-abandoned search. */
  async fetchBatch(options: CandidateBatchOptions): Promise<CandidateBatchResult> {
    if (this.budgetExhausted) return { earliestDepartureAfterCursor: null, rawJourneyIds: [], skipped: false };

    const key = this.probeKey(options);
    if (this.probedQueries.has(key)) return { earliestDepartureAfterCursor: null, rawJourneyIds: [], skipped: true };

    this.batchesUsed++;
    this.probedQueries.add(key);

    const allowedModes = new Set<string>(options.transportModes);
    const raw = await this.client.trips({
      originId: this.originId,
      destinationId: this.destinationId,
      transportModes: options.transportModes,
      maxChanges: options.maxChanges,
      departureAt: options.departureAt,
      dateTimeMode: options.dateTimeMode,
      routeType: options.routeType,
      viaStopId: options.viaStopId,
    });

    const normalized = raw
      .map(normalizeJourney)
      .filter((journey): journey is NormalizedJourney => journey != null)
      .filter((journey) => journey.legs.filter((leg) => leg.transportMode !== "WALK").every((leg) => allowedModes.has(leg.transportMode)));

    // "Strictly after this batch's own bucket" -- see CandidateBatchResult's own doc: a
    // departure within the SAME minute this batch was queried at carries no forward
    // information (SL already answered that whole minute in this very response), so it is
    // deliberately excluded from the earliest-after-cursor computation below -- entirely
    // separate from the upsert loop right after it, which cares about journey IDENTITY,
    // never about cursor progress.
    const bucketEndMillis = floorToStockholmRequestMinute(options.departureAt).getTime() + 60_000;
    let earliestDepartureAfterCursor: Date | null = null;
    for (const journey of normalized) {
      const departureMillis = Date.parse(journey.departureTime);
      if (departureMillis < bucketEndMillis) continue;
      if (earliestDepartureAfterCursor == null || departureMillis < earliestDepartureAfterCursor.getTime()) {
        earliestDepartureAfterCursor = new Date(departureMillis);
      }
    }

    for (const journey of normalized) {
      if (isEligibleJourney(journey, this.requestedAtMillis) && matchesChangesPreference(journey.transferCount, this.changesPreference)) {
        this.candidatesById.set(journey.journeyId, journey);
      } else {
        this.candidatesById.delete(journey.journeyId);
      }
    }

    return { earliestDepartureAfterCursor, rawJourneyIds: normalized.map((journey) => journey.journeyId), skipped: false };
  }

  /**
   * Builds a deterministic, query-scoped probe identity for [options]: the Stockholm
   * request-minute bucket it targets (see `floorToStockholmRequestMinute`), combined with
   * every SL parameter that can materially change which journeys come back. Time alone is
   * NOT enough — see this class's own `probedQueries` doc for why the same minute queried
   * once narrowed to PRIMARY's own transport modes and once with the full allowed mode set
   * are two genuinely different requests, never interchangeable.
   *
   * `originId`/`destinationId` are deliberately excluded: this collector is permanently
   * bound to one origin/destination pair for its whole lifetime (`fetchBatch` always reads
   * `this.originId`/`this.destinationId`, set once in the constructor and never taken from
   * [options]), so they can never differ between two calls on the same instance and would
   * only add noise to the key.
   *
   * Transport modes are deduplicated and sorted before joining, so `["BUS","METRO"]`,
   * `["METRO","BUS"]`, and a caller accidentally repeating an entry all produce the
   * IDENTICAL key — the set of allowed modes is what matters to SL, never the order a
   * caller happened to list them in. `routeType` is normalized through its own documented
   * SL default (`"leasttime"` — see `TripsRequest.routeType`'s own doc) before joining, so
   * an explicit `"leasttime"` and an omitted `routeType` — which resolve to the exact same
   * upstream request — are also recognized as identical. `viaStopId` has no such default
   * (its absence is itself meaningful, "no via constraint", never equivalent to any
   * specific stop id), so it is normalized to an empty-string sentinel only when absent —
   * safe since a real SL stop id is never itself an empty string (see this route's own
   * `required` validation at the API boundary).
   *
   * `dateTimeMode` is normalized to the upstream default (`"DEPARTURE"`) so omission and an
   * explicit default are identical, while an `"ARRIVAL"` search remains distinct.
   * `departureAt`'s exact second is intentionally NOT part of the key — only its own
   * floored request-minute bucket is, matching SL's own whole-minute request precision
   * (`itd_time` is HHMM) and this collector's own bucket-probing model throughout.
   */
  private probeKey(options: CandidateBatchOptions): string {
    const bucketStartMillis = floorToStockholmRequestMinute(options.departureAt).getTime();
    const modes = [...new Set(options.transportModes)].sort().join(",");
    const routeType = options.routeType ?? "leasttime";
    const viaStopId = options.viaStopId ?? "";
    const dateTimeMode = options.dateTimeMode ?? "DEPARTURE";
    return `${bucketStartMillis}|${dateTimeMode}|${modes}|${options.maxChanges}|${routeType}|${viaStopId}`;
  }

  /**
   * Repeatedly fetches batches starting at [from], advancing the departure-time cursor
   * CONSERVATIVELY — see `nextCursorAfter`'s own doc for the exact bucket-probing rule —
   * deliberately never jumping straight past the LATEST departure a batch returned. SL
   * Journey Planner is a best-match proposal service, not exhaustive pagination: a single
   * request from 18:35 might return 18:40:05 and 19:00 while a genuinely relevant journey
   * exists at 18:40:40 (the SAME minute as the first one, just not the specific proposal SL
   * chose to mention) or at 18:50 (a minute SL skipped over entirely) — either could go
   * permanently undiscovered if the cursor advanced past that territory without ever
   * actually querying it. This does not make SL exhaustive — it remains a best-match
   * service, and this collector never claims otherwise — but it stops this collector from
   * incorrectly treating a span as fully searched merely because SL happened to mention one
   * far-future proposal within it.
   *
   * Every batch's own results are upserted into this instance's SHARED `pool` (see
   * `fetchBatch`'s own doc) before [isSatisfied] is consulted — so a later batch's updated
   * representation of an already-known journey is what a caller's own re-derived selection
   * actually sees, never a stale first-seen copy.
   *
   * Terminates ONLY on one of these genuinely safe conditions:
   * - the cursor passes [until] (the requested search boundary);
   * - [isSatisfied] reports the accumulated pool already answers the caller's question;
   * - SL returns an outright empty response (nothing to advance from at all, and nothing
   *   new to have upserted) — a genuinely SKIPPED duplicate query (see
   *   `CandidateBatchResult.skipped`) is deliberately NOT treated the same way: it means
   *   this exact query was already answered earlier, not that SL was asked and said
   *   nothing, so the search still advances the cursor and keeps going rather than
   *   stopping;
   * - a batch offers no representable forward progress (defensive backstop — the computed
   *   next cursor fails to exceed the current one; unreachable through genuine
   *   `nextStockholmRequestMinute` math, since that always strictly advances, but kept as
   *   insurance against a future change to that assumption);
   * - the shared request budget is exhausted (see `MAX_ACQUISITION_BATCHES`) — a skipped
   *   duplicate query never spends any of this budget, so it alone can never exhaust it.
   *
   * Deliberately does NOT terminate merely because two consecutive batches returned the
   * same set of journey ids. SL is a best-match proposal service, not exhaustive
   * pagination — a repeated best-match result set does not prove that a later request
   * minute cannot expose another relevant journey the previous two simply didn't mention
   * (e.g. request 18:35 and request 18:41 both answer with 18:40/19:00, but a genuinely
   * relevant 18:50 journey only surfaces once a request actually probes that gap). The
   * shared request budget above is the intended protection against a genuinely
   * pathological upstream repeating itself forever — not a same-set heuristic, which would
   * risk discarding real information merely because SL happened to answer identically
   * twice in a row.
   *
   * [until] may be a fixed instant, or a function re-read before every batch so a caller
   * can shrink (or grow) the remaining search window as it learns more — e.g. ALTERNATIVE
   * acquisition in journeys.ts narrowing to a newly-discovered, earlier NEXT departure
   * without needing its own separate acquisition loop.
   *
   * [isSatisfied] is re-evaluated against the FULL shared `pool` after every single batch
   * (including before the very first fetch) — so a caller that closes over its own domain
   * recomputation (e.g. re-deriving PRIMARY/NEXT from the growing pool) always sees
   * newly-discovered AND newly-updated candidates before this decides whether to fetch
   * another batch. This method itself has no opinion on what "satisfied" means — a caller
   * is free to have [isSatisfied] always return `false` so acquisition runs its full
   * natural course (bounded only by [until], the shared budget, or a lack of forward
   * progress) rather than stopping the instant some candidate merely exists; see
   * ALTERNATIVE acquisition in journeys.ts, which must never stop just because a qualifying
   * alternative was found, since a later batch can still reclassify NEXT itself and
   * invalidate it.
   */
  async acquireUntil(
    options: AcquisitionOptions,
    from: Date,
    until: Date | (() => Date),
    isSatisfied: (pool: NormalizedJourney[]) => boolean,
  ): Promise<NormalizedJourney[]> {
    const currentUntil = typeof until === "function" ? until : () => until;
    if (isSatisfied(this.pool)) return this.pool;

    let cursor = from;
    while (cursor.getTime() <= currentUntil().getTime() && !this.budgetExhausted) {
      const batch = await this.fetchBatch({ ...options, departureAt: cursor });
      if (isSatisfied(this.pool)) return this.pool;
      // A skipped duplicate (see `CandidateBatchResult.skipped`) also reports an empty
      // `rawJourneyIds`, but must NOT be mistaken for SL confirming this minute has
      // nothing -- it means this exact query was already answered earlier, so the search
      // still advances the cursor and keeps going.
      if (!batch.skipped && batch.rawJourneyIds.length === 0) return this.pool;

      const nextCursor = this.nextCursorAfter(cursor, batch.earliestDepartureAfterCursor, options);
      if (nextCursor.getTime() <= cursor.getTime()) return this.pool;
      cursor = nextCursor;
    }
    return this.pool;
  }

  /**
   * Computes the next request-minute cursor after a batch anchored at [cursor], queried
   * with [options], reported [earliestDepartureAfterCursor] (see `CandidateBatchResult`'s
   * own doc; `null` means the batch offered nothing strictly beyond its own bucket).
   *
   * When there IS a candidate, this does NOT jump straight past it (to
   * `nextStockholmRequestMinute(earliestDepartureAfterCursor)`, one full minute beyond its
   * own bucket) the way an earlier version of this method did. That was itself a gap: a
   * departure at 18:40:05 and one at 18:40:40 can both be genuine, distinct SL best-match
   * proposals, but SL only ever returns a handful per request — a request from 18:35 that
   * happens to mention 18:40:05 says nothing about whether 18:40:40 would also have been
   * returned if THAT minute had been queried directly. So the candidate's own bucket
   * (`floorToStockholmRequestMinute(earliestDepartureAfterCursor)`, e.g. 18:40:00) is
   * queried FIRST, WITH THE SAME [options] this whole search is using — only once that
   * specific (bucket, options) query has actually been probed does advancement move past
   * it. Checked via `probeKey`, never by bucket time alone (see `probedQueries`'s own doc
   * for why: the same minute probed once under a narrower scope and once under a broader
   * one are different queries, and a later, broader search must still be free to probe a
   * minute an earlier, narrower one already touched).
   *
   * "Already probed" is checked against `probedQueries`, not re-derived from batch
   * contents — necessary once PRIMARY retargeting (see backend/src/routes/journeys.ts's own
   * doc) can restart a search from an earlier anchor than one already explored: a new,
   * earlier-departing PRIMARY's own targeted search can climb back up through (bucket,
   * options) pairs an earlier, now-abandoned search already queried, and re-querying those
   * wastes shared budget without discovering anything new. When the candidate query was
   * already probed, this advances past it via `nextStockholmRequestMinute` exactly as
   * before.
   *
   * Falls back to `nextStockholmRequestMinute(cursor)` — advancing past the CURRENT
   * bucket, not the (nonexistent) candidate one — when there is no candidate at all, e.g.
   * every departure this batch returned fell within its own just-queried bucket.
   */
  private nextCursorAfter(cursor: Date, earliestDepartureAfterCursor: Date | null, options: AcquisitionOptions): Date {
    if (earliestDepartureAfterCursor == null) return nextStockholmRequestMinute(cursor);
    const candidateBucket = floorToStockholmRequestMinute(earliestDepartureAfterCursor);
    const candidateKey = this.probeKey({ ...options, departureAt: candidateBucket });
    if (!this.probedQueries.has(candidateKey)) return candidateBucket;
    return nextStockholmRequestMinute(candidateBucket);
  }
}
