import { selectAlternative, selectLaterJourneys, selectNext, selectPrimary } from "../domain/journeyRoles.js";
import { buildRoutePattern, type RoutePattern } from "../domain/routePattern.js";
import { floorToStockholmRequestMinute } from "../lib/stockholmTime.js";
import {
  journeyTransportModes,
  type JourneyTransportMode,
} from "../models/common.js";
import {
  CandidateCollector,
  MAX_CHANGES,
  requestMaxChanges,
  type JourneyChangesPreference,
  type NormalizedJourney,
} from "./candidateCollector.js";
import type { SlJourneyPlannerClient } from "./slJourneyPlannerClient.js";

export type LiveJourneyRole = "PRIMARY" | "NEXT" | "ALTERNATIVE";

/** A normalized journey plus the structural route pattern used only while assigning roles. */
export type RankableNormalizedJourney = NormalizedJourney & { pattern: RoutePattern };

export function toRankable(journeys: NormalizedJourney[]): RankableNormalizedJourney[] {
  return journeys.map((journey) => ({ ...journey, pattern: buildRoutePattern(journey) }));
}

interface Selection {
  rankablePool: RankableNormalizedJourney[];
  primary: RankableNormalizedJourney | undefined;
  next: RankableNormalizedJourney | undefined;
}

/**
 * Recomputes PRIMARY and NEXT from the collector's complete current pool after every batch.
 * Selection deliberately precedes any dominance filtering: global dominance is safe for
 * PRIMARY but can suppress the earliest route-compatible NEXT. Dominance therefore remains
 * scoped to ALTERNATIVE selection after PRIMARY and NEXT have settled.
 */
function deriveSelection(pool: NormalizedJourney[]): Selection {
  const rankablePool = toRankable(pool);
  const primary = selectPrimary(rankablePool);
  const next = primary == null ? undefined : selectNext(rankablePool, primary);
  return { rankablePool, primary, next };
}

function transportModesUsedBy(
  pattern: RoutePattern,
  fallback: readonly JourneyTransportMode[],
): JourneyTransportMode[] {
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
  nextCalls: number;
  alternativeCalls: number;
  primaryDiscoveryCalls: number;
  primaryRetargets: number;
}

interface LaterAcquisitionResult {
  selection: Selection;
  laterJourneys: RankableNormalizedJourney[];
  laterCalls: number;
  primaryRetargets: number;
}

interface PrimaryTarget {
  journeyId: string;
  transportModes: readonly JourneyTransportMode[];
  transferCount: number;
}

function primaryTargetOf(
  primary: RankableNormalizedJourney,
  fallbackModes: readonly JourneyTransportMode[],
): PrimaryTarget {
  return {
    journeyId: primary.journeyId,
    transportModes: transportModesUsedBy(primary.pattern, fallbackModes),
    transferCount: primary.transferCount,
  };
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
 * Runs the bounded live-role state machine over one shared collector. It discovers a missing
 * PRIMARY for WITH_CHANGES_ONLY, then searches for NEXT and the PRIMARY/NEXT interval used by
 * ALTERNATIVE. Every batch is upserted before roles are re-derived. If PRIMARY changes during
 * a targeted search, the search is abandoned and retargeted to the new PRIMARY; the shared
 * collector budget bounds all phases and retargets together.
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

  if (
    changesPreference === "WITH_CHANGES_ONLY" &&
    selection.primary == null &&
    searchUntil != null &&
    !collector.budgetExhausted
  ) {
    const callsBefore = collector.batchesUsedSoFar;
    await collector.acquireUntil(
      { transportModes, maxChanges: MAX_CHANGES },
      requestedAt,
      searchUntil,
      (pool) => pool.length > 0,
    );
    primaryDiscoveryCalls = collector.batchesUsedSoFar - callsBefore;
    selection = deriveSelection(collector.pool);
  }

  while (selection.primary != null) {
    const primary = selection.primary;
    const target = primaryTargetOf(primary, transportModes);

    if (selection.next == null) {
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
      break;
    }

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
    if (selection.next == null) continue;
    break;
  }

  return { selection, nextCalls, alternativeCalls, primaryDiscoveryCalls, primaryRetargets };
}

/**
 * Extends a settled live selection with the optional foreground reserve while reusing the
 * same collector, deduplication rules, cursor behavior, and request budget. Supplemental
 * rows never receive authoritative roles, and no reserve is returned without a final NEXT.
 */
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
    return selectLaterJourneys(
      selection.rankablePool,
      selection.primary,
      selection.next,
      requestedCount,
      alternative,
    );
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

export interface AcquireLiveJourneysInput {
  readonly originId: string;
  readonly destinationId: string;
  readonly transportModes: readonly JourneyTransportMode[];
  readonly changesPreference: JourneyChangesPreference;
  readonly searchUntil: Date | null;
  /** One instant shared by the initial anchor and every candidate eligibility check. */
  readonly fetchedAt: Date;
  readonly laterJourneyCount: number;
}

export interface RoleAssignedLiveJourney {
  readonly role: LiveJourneyRole;
  readonly journey: NormalizedJourney;
}

export interface LiveJourneyAcquisitionStats {
  readonly slCalls: number;
  readonly initialCalls: number;
  readonly nextCalls: number;
  readonly alternativeCalls: number;
  readonly laterCalls: number;
  readonly primaryDiscoveryCalls: number;
  readonly primaryRetargets: number;
  readonly budgetExhausted: boolean;
}

export interface AuthoritativeLiveJourneys {
  readonly fetchedAt: Date;
  readonly journeys: readonly RoleAssignedLiveJourney[];
  readonly laterJourneys: readonly NormalizedJourney[];
  readonly stats: LiveJourneyAcquisitionStats;
}

function withoutPattern(journey: RankableNormalizedJourney): NormalizedJourney {
  const { pattern, ...normalized } = journey;
  void pattern;
  return normalized;
}

/**
 * Acquires and assigns the backend-authoritative live journey roles once. The caller supplies
 * validated request values and decides how to serialize, publish, or handle a rejected upstream
 * call; this service deliberately has no HTTP or background-execution policy.
 */
export async function acquireAuthoritativeLiveJourneys(
  client: SlJourneyPlannerClient,
  input: AcquireLiveJourneysInput,
): Promise<AuthoritativeLiveJourneys> {
  const collector = new CandidateCollector(
    client,
    input.originId,
    input.destinationId,
    input.fetchedAt.getTime(),
    input.changesPreference,
  );

  await collector.fetchBatch({
    transportModes: input.transportModes,
    maxChanges: requestMaxChanges(input.changesPreference),
    departureAt: input.fetchedAt,
    dateTimeMode: "DEPARTURE",
  });
  const initialCalls = collector.batchesUsedSoFar;

  const liveResolution = await resolveSelection(
    collector,
    input.transportModes,
    input.searchUntil,
    input.changesPreference,
    input.fetchedAt,
  );
  const laterResolution = await resolveLaterJourneys(
    collector,
    liveResolution.selection,
    input.transportModes,
    input.searchUntil,
    input.laterJourneyCount,
  );
  const selection = laterResolution.selection;
  const alternative =
    selection.primary != null && selection.next != null
      ? selectAlternative(selection.rankablePool, selection.primary, selection.next)
      : undefined;

  const journeys: RoleAssignedLiveJourney[] = [];
  if (selection.primary != null) {
    journeys.push({ role: "PRIMARY", journey: withoutPattern(selection.primary) });
    if (alternative != null) {
      journeys.push({ role: "ALTERNATIVE", journey: withoutPattern(alternative) });
    }
    if (selection.next != null) {
      journeys.push({ role: "NEXT", journey: withoutPattern(selection.next) });
    }
  }

  return {
    fetchedAt: input.fetchedAt,
    journeys,
    laterJourneys: laterResolution.laterJourneys.map(withoutPattern),
    stats: {
      slCalls: collector.batchesUsedSoFar,
      initialCalls,
      nextCalls: liveResolution.nextCalls,
      alternativeCalls: liveResolution.alternativeCalls,
      laterCalls: laterResolution.laterCalls,
      primaryDiscoveryCalls: liveResolution.primaryDiscoveryCalls,
      primaryRetargets: liveResolution.primaryRetargets + laterResolution.primaryRetargets,
      budgetExhausted: collector.budgetExhausted,
    },
  };
}
