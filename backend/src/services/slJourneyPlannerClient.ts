import { z } from "zod";
import { config } from "../config/env.js";
import { fetchUpstreamJson } from "../lib/upstreamFetch.js";

const LocationSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  disassembledName: z.string().optional(),
  type: z.string(),
  matchQuality: z.number().optional(),
}).passthrough();

const StopFinderSchema = z.object({ locations: z.array(LocationSchema).optional() }).passthrough();

const PlaceSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1),
  disassembledName: z.string().optional(),
  departureTimePlanned: z.string().datetime({ offset: true }).optional(),
  departureTimeEstimated: z.string().datetime({ offset: true }).optional(),
  arrivalTimePlanned: z.string().datetime({ offset: true }).optional(),
  arrivalTimeEstimated: z.string().datetime({ offset: true }).optional(),
}).passthrough();

const TransportationSchema = z.object({
  disassembledName: z.string().optional(),
  number: z.string().optional(),
  product: z.object({ class: z.number().int().optional(), name: z.string().optional() }).passthrough().optional(),
  destination: z.object({ name: z.string().optional() }).passthrough().optional(),
}).passthrough();

const LegPropertiesSchema = z.object({
  // The live SL /trips response identifies each public-transport trip here. It does
  // not expose a journey-level `tripId` on the journey object.
  tripId: z.string().min(1).optional(),
}).passthrough();

const LegSchema = z.object({
  origin: PlaceSchema,
  destination: PlaceSchema,
  transportation: TransportationSchema.optional(),
  infos: z.array(z.unknown()).optional(),
  properties: LegPropertiesSchema.optional(),
}).passthrough();

const JourneySchema = z.object({
  // Kept optional for compatibility with variants that do provide it, while the
  // normal live response is identified from legs[*].properties.tripId.
  tripId: z.string().min(1).optional(),
  interchanges: z.number().int().nonnegative().optional(),
  legs: z.array(LegSchema).min(1),
}).passthrough();

const TripsSchema = z.object({ journeys: z.array(JourneySchema).optional() }).passthrough();

export type RawJourneyPlannerLocation = z.infer<typeof LocationSchema>;
export type RawJourneyPlannerJourney = z.infer<typeof JourneySchema>;
export const journeyTransportModes = ["METRO", "TRAIN", "BUS", "TRAM", "FERRY"] as const;
export type JourneyTransportMode = (typeof journeyTransportModes)[number];

export interface SlJourneyPlannerClient {
  searchStops(query: string): Promise<RawJourneyPlannerLocation[]>;
  trips(
    originId: string,
    destinationId: string,
    transportModes?: readonly JourneyTransportMode[],
  ): Promise<RawJourneyPlannerJourney[]>;
}

export function createSlJourneyPlannerClient(baseUrl = config.slJourneyPlannerBaseUrl): SlJourneyPlannerClient {
  return {
    async searchStops(query) {
      const params = new URLSearchParams({ name_sf: query, any_obj_filter_sf: "2", type_sf: "any" });
      const response = await fetchUpstreamJson(`${baseUrl}/stop-finder?${params}`, StopFinderSchema, {
        upstreamName: "SL Journey Planner",
      });
      return response.locations ?? [];
    },
    async trips(originId, destinationId, transportModes = journeyTransportModes) {
      const enabled = new Set(transportModes);
      const params = new URLSearchParams({
        type_origin: "any",
        type_destination: "any",
        name_origin: originId,
        name_destination: destinationId,
        calc_number_of_trips: "3",
        route_type: "leasttime",
        // Without this, SL may include a trip departing before name_origin/name_destination's
        // requested time in the result set (its "closest to the requested time" behavior is not
        // one-directional by default) — one root cause of a past departure ever being ranked as
        // "fastest" (see journeys.ts's own defensive departure-time filter for the other layer of
        // protection against this same class of bug).
        calc_one_direction: "true",
        // SL's own default (nine) is far looser than a commute journey should ever need; this
        // app never wants to present a nine-change trip as a viable "fastest"/"alternative"
        // option. journeys.ts independently filters journey.transferCount defensively — this
        // upstream parameter's job is only to avoid asking SL to consider (and cache) such trips
        // at all.
        max_changes: "2",
        incl_mot_0: String(enabled.has("TRAIN")),
        incl_mot_2: String(enabled.has("METRO")),
        incl_mot_4: String(enabled.has("TRAM")),
        incl_mot_5: String(enabled.has("BUS")),
        incl_mot_9: String(enabled.has("FERRY")),
        incl_mot_10: String(enabled.has("BUS")),
        incl_mot_14: String(enabled.has("TRAIN")),
        incl_mot_19: String(enabled.has("BUS")),
      });
      const response = await fetchUpstreamJson(`${baseUrl}/trips?${params}`, TripsSchema, {
        upstreamName: "SL Journey Planner",
      });
      return response.journeys ?? [];
    },
  };
}
