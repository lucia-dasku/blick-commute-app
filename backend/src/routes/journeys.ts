import { Hono } from "hono";
import { AppError } from "../lib/errors.js";
import { successEnvelope } from "../models/common.js";
import { normalizeJourney } from "../normalize/normalizeJourney.js";
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

function transportSignature(journey: { legs: Array<{ transportMode: string }> }): string {
  return [...new Set(journey.legs
    .map((leg) => leg.transportMode)
    .filter((mode) => mode !== "WALK"))]
    .sort()
    .join(",");
}

/** Mirrors the `max_changes` request parameter sent to SL (see slJourneyPlannerClient.ts) —
 * enforced again here, defensively, since SL is not guaranteed to always honor it and a cached
 * upstream response could predate this parameter existing at all. */
const MAX_CHANGES = 2;

/** True only for a journey whose first public-transport departure has not yet passed
 * `requestedAtMillis`, and that requires no more than MAX_CHANGES changes. `journey.departureTime`
 * is already normalizeJourney's own "effective first public-transport departure" (derived from
 * the first non-walking leg, not necessarily legs[0] — see that function's own doc), so no
 * further leg inspection is needed here. Applied identically to the primary ranking and the
 * different-mode fallback search (both funnel through rankedFor below) so neither path can ever
 * surface an expired or over-the-limit journey as "fastest" or "alternative". */
function isEligibleJourney(journey: { departureTime: string; transferCount: number }, requestedAtMillis: number): boolean {
  return Date.parse(journey.departureTime) >= requestedAtMillis && journey.transferCount <= MAX_CHANGES;
}

/**
 * `now` is captured once per request (never re-read mid-request) and is an injectable
 * `() => Date` — defaulted to the real wall clock in production, overridable in tests — so
 * "is this journey already expired" can be asserted deterministically rather than racing the
 * real clock (see journeys.test.ts).
 */
export function createJourneyRoutes(client: SlJourneyPlannerClient, now: () => Date = () => new Date()) {
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
    if (originId === destinationId) throw new AppError("VALIDATION_ERROR", "Origin and destination must differ");

    // One timestamp for the whole request: every eligibility check below (the primary search
    // and the different-mode fallback alike) is measured against this same instant, never a
    // freshly re-read wall clock partway through.
    const requestedAt = now();
    const requestedAtMillis = requestedAt.getTime();

    const rankedFor = async (modes: JourneyTransportMode[]) => {
      const allowedModes = new Set<string>(modes);
      return (await client.trips(originId, destinationId, modes))
        .map(normalizeJourney)
        .filter((journey): journey is NonNullable<typeof journey> => journey != null)
        .filter((journey) => journey.legs
          .filter((leg) => leg.transportMode !== "WALK")
          .every((leg) => allowedModes.has(leg.transportMode)))
        // Defends against SL including an already-departed trip despite calc_one_direction
        // (see slJourneyPlannerClient.ts) and against a >MAX_CHANGES trip despite max_changes —
        // applied BEFORE sorting/fastest/alternative selection so neither can ever pick one.
        .filter((journey) => isEligibleJourney(journey, requestedAtMillis))
        .sort((a, b) => Date.parse(a.arrivalTime) - Date.parse(b.arrivalTime));
    };

    const ranked = await rankedFor(transportModes);
    const fastest = ranked[0];
    let alternative = fastest == null
      ? undefined
      : ranked.slice(1).find((journey) => transportSignature(journey) !== transportSignature(fastest));
    if (fastest != null && alternative == null) {
      const fastestModes = new Set(transportSignature(fastest).split(","));
      const remainingModes = transportModes.filter((mode) => !fastestModes.has(mode));
      if (remainingModes.length > 0) {
        alternative = (await rankedFor(remainingModes)).find((journey) => journey.journeyId !== fastest.journeyId);
      }
    }
    const journeys = fastest == null ? [] : [fastest, ...(alternative == null ? [] : [alternative])];
    c.header("Cache-Control", "public, s-maxage=30, stale-while-revalidate=30");
    return c.json(successEnvelope({ fetchedAt: requestedAt.toISOString(), journeys }));
  });
  return route;
}
