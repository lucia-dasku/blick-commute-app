import type { RawJourneyPlannerJourney, RawJourneyPlannerPlace } from "../services/slJourneyPlannerClient.js";

function modeFor(productClass?: number, name?: string): string {
  if (productClass === 0 || productClass === 1) return "TRAIN";
  if (productClass === 2) return "METRO";
  if (productClass === 3 || productClass === 4) return "TRAM";
  if (productClass === 5 || productClass === 6 || productClass === 7 || productClass === 10 || productClass === 19) return "BUS";
  if (productClass === 9) return "FERRY";
  if (productClass === 99) return "WALK";
  const value = name?.toUpperCase() ?? "UNKNOWN";
  if (value.includes("FOOTPATH") || value.includes("WALK")) return "WALK";
  if (value.includes("BUS")) return "BUS";
  if (value.includes("TUNNEL") || value.includes("METRO")) return "METRO";
  if (value.includes("PENDEL") || value.includes("TÅG") || value.includes("TRAIN")) return "TRAIN";
  if (value.includes("SPÅR") || value.includes("TRAM")) return "TRAM";
  if (value.includes("BÅT") || value.includes("FERRY") || value.includes("SHIP")) return "FERRY";
  return "UNKNOWN";
}

function preferredTime(estimated?: string, planned?: string): string | undefined {
  return estimated ?? planned;
}

function disruptionText(infos: unknown[] | undefined): string[] {
  return (infos ?? []).flatMap((info) => {
  if (typeof info === "string") return [info];
  if (info && typeof info === "object") {
    const record = info as Record<string, unknown>;
    return [record.content, record.text, record.subtitle, record.name].filter((v): v is string => typeof v === "string");
  }
  return [];
  });
}

/** SL's own real, confirmed place-type value for a stop-area itself (as opposed to
 * `"platform"`, a single boarding point within one) — see slJourneyPlannerClient.ts's own
 * RawPlace doc. The only string this module treats as "this node IS the canonical
 * identity" — everything else (`"platform"`, missing, or any other/unrecognized value) is
 * treated as "keep looking at the parent chain", never asserted to be some OTHER specific
 * kind of place this schema hasn't confirmed. */
const STOP_AREA_TYPE = "stop";

/** Canonicalizes a raw SL stop object to its nearest actual transit stop/stop-area
 * identity: walks `place`, then `place.parent`, then `place.parent.parent`, ... and
 * returns the `id` of the FIRST node whose own `type` is `STOP_AREA_TYPE` — never merely
 * the first node that HAS a parent, which is too broad (a `"stop"`-typed place can itself
 * carry a further, e.g. locality, parent — see backend/src/domain/routePattern.ts's own
 * doc for why Slussen/T-Centralen/Odenplan must never collapse into a shared city-level
 * parent). A platform's own immediate parent is normally that stop, so the common case
 * still resolves in one step; a deeper chain (platform -> stop -> locality) still
 * correctly stops AT the stop, never continuing on to the locality beyond it. Falls back
 * to [place]'s own `id` when no node in the chain is ever typed `STOP_AREA_TYPE` at all
 * (e.g. a schema variant this hasn't seen) — never a name, and never `null` unless
 * [place] itself has no `id` either. */
function canonicalStopId(place: RawJourneyPlannerPlace): string | null {
  let current: RawJourneyPlannerPlace | undefined = place;
  while (current != null) {
    if (current.type === STOP_AREA_TYPE && current.id != null) return current.id;
    current = current.parent;
  }
  return place.id ?? null;
}

/** Every stop a transit leg calls at, boarding through alighting, canonicalized to
 * stop-area identity — see `canonicalStopId`. Falls back to `[origin, destination]`
 * alone when SL didn't supply a `stopSequence` for this leg, rather than leaving the
 * leg's own structural identity empty. An entry with no resolvable id at all is dropped
 * rather than poisoning the sequence with a `null`. */
function buildStopIds(leg: { origin: RawJourneyPlannerPlace; destination: RawJourneyPlannerPlace; stopSequence?: RawJourneyPlannerPlace[] }): string[] {
  const source = leg.stopSequence != null && leg.stopSequence.length > 0 ? leg.stopSequence : [leg.origin, leg.destination];
  return source.map(canonicalStopId).filter((id): id is string => id != null);
}

/** Sums a journey's total walking duration from every WALK leg's own `duration`
 * (seconds, per planned timetable — see slJourneyPlannerClient.ts's own schema doc).
 * Returns `null` — genuinely unknown, never a silent zero — the moment any relevant WALK
 * leg's own duration is itself undefined, since a partial sum would misrepresent how
 * much a journey actually walks (see backend/src/domain/dominance.ts's own doc on why an
 * unknown walking duration must never be compared as if it were zero). A journey with no
 * WALK legs at all genuinely walks zero seconds — that IS a known value, not an unknown
 * one. */
function totalWalkingDurationSeconds(legs: RawJourneyPlannerJourney["legs"]): number | null {
  let total = 0;
  for (const leg of legs) {
    const mode = modeFor(leg.transportation?.product?.class, leg.transportation?.product?.name);
    if (mode !== "WALK") continue;
    if (leg.duration == null) return null;
    total += leg.duration;
  }
  return total;
}

export function normalizeJourney(raw: RawJourneyPlannerJourney) {
  // Walking/transfer legs can still carry a transportation object. Select the first
  // recognized public-transport leg, not merely the first object-shaped leg.
  const transitLegs = raw.legs.filter((leg) => {
    const mode = modeFor(leg.transportation?.product?.class, leg.transportation?.product?.name);
    return mode !== "UNKNOWN" && mode !== "WALK";
  });
  const first = transitLegs[0] ?? raw.legs[0]!;
  const last = raw.legs[raw.legs.length - 1]!;
  const departureTime = preferredTime(first.origin.departureTimeEstimated, first.origin.departureTimePlanned);
  const arrivalTime = preferredTime(last.destination.arrivalTimeEstimated, last.destination.arrivalTimePlanned);
  const legTripIds = raw.legs.flatMap((leg) => leg.properties?.tripId == null ? [] : [leg.properties.tripId]);
  const journeyId = raw.tripId ?? [...new Set(legTripIds)].join(":");
  if (!journeyId || !departureTime || !arrivalTime) return undefined;
  return {
    journeyId,
    originName: first.origin.name,
    destinationName: last.destination.name,
    departureTime,
    arrivalTime,
    // Trusts SL's own `interchanges` when supplied; falls back to deriving it from the
    // already-computed public-transport leg count when it's absent, rather than assuming
    // zero -- a multi-leg journey missing `interchanges` must never be undercounted down
    // to a direct trip (see backend/src/services/candidateCollector.ts's own MAX_CHANGES
    // enforcement, which this feeds into: an undercounted journey could otherwise slip
    // through a limit it should have been rejected by). WALK legs are never counted:
    // `transitLegs` already excludes them (see this function's own doc above).
    transferCount: raw.interchanges ?? Math.max(0, transitLegs.length - 1),
    // Internal-only ranking input — backend/src/routes/journeys.ts's own toPublicJourney
    // strips this before building the response; backend/src/domain/dominance.ts is the
    // only consumer. See totalWalkingDurationSeconds's own doc for why this is nullable.
    walkingDurationSeconds: totalWalkingDurationSeconds(raw.legs),
    firstLeg: {
      transportMode: modeFor(first.transportation?.product?.class, first.transportation?.product?.name),
      lineDesignation: first.transportation?.disassembledName ?? first.transportation?.number ?? null,
      direction: first.transportation?.destination?.name ?? null,
      originName: first.origin.name,
      destinationName: first.destination.name,
      departureTime,
      arrivalTime: preferredTime(first.destination.arrivalTimeEstimated, first.destination.arrivalTimePlanned) ?? arrivalTime,
      isRealtime: first.origin.departureTimeEstimated != null,
    },
    legs: raw.legs.map((leg) => {
      const transportMode = leg.transportation == null ? "WALK" : modeFor(leg.transportation.product?.class, leg.transportation.product?.name);
      return {
        transportMode,
        lineDesignation: leg.transportation?.disassembledName ?? leg.transportation?.number ?? null,
        direction: leg.transportation?.destination?.name ?? null,
        originName: leg.origin.name,
        destinationName: leg.destination.name,
        departureTime: preferredTime(leg.origin.departureTimeEstimated, leg.origin.departureTimePlanned) ?? null,
        arrivalTime: preferredTime(leg.destination.arrivalTimeEstimated, leg.destination.arrivalTimePlanned) ?? null,
        isRealtime: leg.origin.departureTimeEstimated != null || leg.destination.arrivalTimeEstimated != null,
        disruptions: disruptionText(leg.infos),
        // Internal-only structural identity — never sent to Android as-is (see
        // walkingDurationSeconds's own doc above). Empty for a WALK leg: RoutePattern
        // only ever includes transit legs (see backend/src/domain/routePattern.ts).
        stopIds: transportMode === "WALK" ? [] : buildStopIds(leg),
      };
    }),
    disruptions: raw.legs.flatMap((leg) => disruptionText(leg.infos)),
  };
}
