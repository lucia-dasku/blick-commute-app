import type {
  RawDeparture,
  RawDeparturesResponse,
  RawSiteDeviation,
  RawTripDeviation,
} from "../services/upstreamTypes.js";
import type { Departure, DeparturesResponse, SiteDeviation, TripDeviation } from "../models/departure.js";
import { naiveStockholmLocalToIso } from "../lib/stockholmTime.js";
import { asTransportMode } from "./transportMode.js";

function normalizeTripDeviation(raw: RawTripDeviation): TripDeviation {
  return {
    importanceLevel: raw.importance_level,
    consequence: raw.consequence,
    message: raw.message,
  };
}

/**
 * `isCancelled` derivation policy (see docs/api-contract.md, "Cancellation"):
 * a departure is considered cancelled if, and only if, its own `state` is literally
 * "CANCELLED", or at least one of its trip deviations has `consequence === "CANCELLED"`.
 * No other state string is treated as a cancellation signal — unfamiliar future state
 * values pass through in the `state` field untouched, they just don't flip this flag.
 */
export function deriveIsCancelled(state: string, tripDeviations: readonly TripDeviation[]): boolean {
  if (state === "CANCELLED") return true;
  return tripDeviations.some((deviation) => deviation.consequence === "CANCELLED");
}

/**
 * Departure identity: `${journey.id}:${stopPoint.id}:${scheduledTime}`. `journey.id`
 * alone looks date-scoped in practice, but combining it with the stop point and the
 * (already timezone-resolved) scheduled time keeps the identity stable across refreshes
 * of the same departure while defending against the theoretical case of one journey
 * visiting the same stop point twice (e.g. a loop route).
 */
function buildDepartureId(journeyId: number, stopPointId: number, scheduledTimeIso: string): string {
  return `${journeyId}:${stopPointId}:${scheduledTimeIso}`;
}

export function normalizeDeparture(raw: RawDeparture, referenceInstant: Date): Departure {
  const scheduled = naiveStockholmLocalToIso(raw.scheduled, referenceInstant);
  const expected = raw.expected != null ? naiveStockholmLocalToIso(raw.expected, referenceInstant) : null;

  const tripDeviations = (raw.deviations ?? []).map(normalizeTripDeviation);
  const isCancelled = deriveIsCancelled(raw.state, tripDeviations);

  return {
    departureId: buildDepartureId(raw.journey.id, raw.stop_point.id, scheduled.iso),
    line: {
      id: raw.line.id,
      designation: raw.line.designation,
      transportMode: asTransportMode(raw.line.transport_mode),
    },
    direction: raw.direction ?? null,
    directionCode: raw.direction_code ?? null,
    destination: raw.destination ?? null,
    via: raw.via ?? null,
    stopArea: {
      id: raw.stop_area.id,
      name: raw.stop_area.name,
      type: raw.stop_area.type ?? null,
    },
    stopPoint: {
      id: raw.stop_point.id,
      name: raw.stop_point.name,
      designation: raw.stop_point.designation ?? null,
    },
    scheduledTime: scheduled.iso,
    expectedTime: expected?.iso ?? null,
    state: raw.state,
    isCancelled,
    journey: {
      id: raw.journey.id,
      state: raw.journey.state,
      predictionState: raw.journey.prediction_state ?? null,
    },
    tripDeviations,
  };
}

function normalizeSiteDeviation(raw: RawSiteDeviation): SiteDeviation {
  return {
    id: raw.id,
    importanceLevel: raw.importance_level,
    message: raw.message,
    affectedStopAreas: (raw.scope.stop_areas ?? []).map((a) => ({ id: a.id, name: a.name, type: a.type ?? null })),
    affectedStopPoints: (raw.scope.stop_points ?? []).map((p) => ({ id: p.id, name: p.name })),
    affectedLines: (raw.scope.lines ?? []).map((l) => ({
      id: l.id,
      designation: l.designation,
      transportMode: l.transport_mode ? asTransportMode(l.transport_mode) : null,
    })),
  };
}

export function normalizeDeparturesResponse(
  siteId: number,
  raw: RawDeparturesResponse,
  fetchedAt: Date,
): DeparturesResponse {
  return {
    fetchedAt: fetchedAt.toISOString(),
    timeZone: "Europe/Stockholm",
    siteId,
    departures: raw.departures.map((d) => normalizeDeparture(d, fetchedAt)),
    siteDeviations: (raw.stop_deviations ?? []).map(normalizeSiteDeviation),
  };
}
