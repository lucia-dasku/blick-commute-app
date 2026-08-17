import { z } from "zod";
import { config } from "../config/env.js";
import { fetchUpstreamJson } from "../lib/upstreamFetch.js";
import { toItdDateTime } from "../lib/stockholmTime.js";

const LocationSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  disassembledName: z.string().optional(),
  type: z.string(),
  matchQuality: z.number().optional(),
  /** `properties.stopId` — confirmed LIVE (2026-08-16, cross-referenced against 11 real
   * stations) to equal SL Transport's own `site.id` (see `models/site.ts`) plus a fixed
   * `18000000` national-numbering offset (Trafiklab's own docs call this SL's "rikshållplats"/
   * national stop id) — e.g. Akalla: `stopId "18009300"` -> `site.id 9300`, Slussen: `"18009192"`
   * -> `9192`. Reliable across every metro/train station tested, including two same-named but
   * distinct real sites ("Bålsta") correctly disambiguated; one honest live exception (a
   * ferry-only destination, Vaxholm) resolved to no real site at all, treated as an ordinary
   * "unresolved" rather than assumed to fail the same way everywhere else. This bridge is
   * verified against the SAME `id` this schema already exposes (`type_sf: "any"` also accepts a
   * bare `id` as `name_sf`, returning that exact place's own record — confirmed live), which is
   * what lets `services/journeyEndpointSiteResolver.ts` resolve a bare, already-received
   * `originId`/`destinationId` back to this same detail without needing Android to persist or
   * forward any new field of its own. See that service's own doc for the full resolution
   * contract and why this is deliberately never called from `/api/v1/journeys` itself. */
  properties: z.object({ stopId: z.string().optional() }).passthrough().optional(),
}).passthrough();

const StopFinderSchema = z.object({ locations: z.array(LocationSchema).optional() }).passthrough();

/** Recursive shape: a platform's own `parent` is the stop-area it belongs to, itself
 * shaped exactly like this object (SL Journey Planner v2's `legsorigindestinationObject`
 * — see the OpenAPI spec at trafiklab.se/openapi/sl-journey-planner.json). An explicit
 * interface + `z.ZodType` annotation is required for a self-referential Zod schema.
 *
 * `type` distinguishes what kind of place this specific node is -- confirmed real values
 * (from live SL fixtures, see backend/fixtures/*.sample.json) are `"platform"` (a single
 * boarding point, whose own `parent` is usually the stop-area it belongs to) and `"stop"`
 * (a stop-area itself, which can itself carry a FURTHER parent, e.g. a locality). Preserved
 * defensively/verbatim (never validated against a fixed enum) since real SL data may use
 * other values this schema hasn't seen yet -- see normalizeJourney.ts's own
 * canonicalStopId, which is the actual consumer of this field.
 *
 * `isGlobalId` distinguishes whether `id` is safe to treat as a standalone global identifier at
 * all -- confirmed live that a `type: "platform"` node normally carries `isGlobalId: true`
 * alongside a `pattern_point_gid`-namespace `id` (e.g. `"9025001000003272"`), which is exactly
 * the identifier `StopPointDirectory` (`services/stopPointDirectory.ts`) resolves against SL
 * Transport's own `/v1/stop-points` -- see that service's own doc for the full bridge evidence.
 * Also confirmed live that a leg's own `origin`/`destination` can carry `type: "stop"` instead
 * (SL has not pinned a specific boarding/alighting platform yet) even when the SAME physical
 * stop appears as `type: "platform"` elsewhere in that same trip's own `stopSequence` -- see
 * `normalizeJourney.ts`'s own `buildDisruptionContextLeg`, which is why every consumer of this
 * field checks `type === "platform" && isGlobalId === true` before ever treating `id` as a
 * `PatternPointGid`, rather than assuming `id` is always in that namespace. */
interface RawPlace {
  id?: string;
  name: string;
  type?: string;
  isGlobalId?: boolean;
  disassembledName?: string;
  departureTimePlanned?: string;
  departureTimeEstimated?: string;
  arrivalTimePlanned?: string;
  arrivalTimeEstimated?: string;
  parent?: RawPlace;
}

const PlaceSchema: z.ZodType<RawPlace> = z.lazy(() => z.object({
  id: z.string().optional(),
  name: z.string().min(1),
  type: z.string().optional(),
  isGlobalId: z.boolean().optional(),
  disassembledName: z.string().optional(),
  departureTimePlanned: z.string().datetime({ offset: true }).optional(),
  departureTimeEstimated: z.string().datetime({ offset: true }).optional(),
  arrivalTimePlanned: z.string().datetime({ offset: true }).optional(),
  arrivalTimeEstimated: z.string().datetime({ offset: true }).optional(),
  // The place this node belongs to -- see normalizeJourney.ts's own canonicalStopId,
  // which walks this chain by `type` (never blindly by presence alone) to find the
  // nearest actual stop-area identity, so a platform change alone can never create a new
  // route family (backend/src/domain/routePattern.ts), while a stop-area's OWN further
  // (e.g. locality) parent is never mistaken for the canonical identity either.
  parent: PlaceSchema.optional(),
}).passthrough());

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
  // Every stop the vehicle calls at for this leg, boarding through alighting -- the
  // source of RoutePattern's structural identity (see normalizeJourney.ts's own
  // buildStopIds and backend/src/domain/routePattern.ts). Optional: absent on a leg SL
  // didn't return it for (e.g. a footpath), in which case normalizeJourney falls back to
  // [origin, destination] alone.
  stopSequence: z.array(PlaceSchema).optional(),
  // Duration of the leg in seconds, per planned timetable -- used for a WALK leg's own
  // contribution to a journey's total walking duration (see normalizeJourney.ts).
  duration: z.number().optional(),
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
export type RawJourneyPlannerPlace = RawPlace;
export const journeyTransportModes = ["METRO", "TRAIN", "BUS", "TRAM", "FERRY"] as const;
export type JourneyTransportMode = (typeof journeyTransportModes)[number];

export type JourneyRouteType = "leasttime" | "leastinterchange" | "leastwalking";

export interface TripsRequest {
  originId: string;
  destinationId: string;
  /** Defaults to every regular mode (see `journeyTransportModes`) when omitted. */
  transportModes?: readonly JourneyTransportMode[];
  /** Forwarded verbatim as SL's own `max_changes` parameter. No default: every call site
   * (an initial broad search, a targeted NEXT search narrowed to PRIMARY's own route
   * family, or a targeted ALTERNATIVE search) must decide deliberately how many changes
   * it's willing to accept, rather than silently inheriting SL's own default (nine) or a
   * value meant for a different search — see backend/src/routes/journeys.ts's own doc. */
  maxChanges: number;
  /** Anchors the search at this real instant — resolved to SL's own `itd_date`/`itd_time`
   * parameters against Europe/Stockholm wall-clock time (see stockholmTime.ts's own
   * `toItdDateTime`, DST-safe and independent of the backend process's own local
   * timezone). Always supplied, even for a request's very first search — SL is never left
   * to independently choose its own notion of "now" (see journeys.ts's own doc). */
  departureAt: Date;
  /** Defaults to `"leasttime"` (the existing behavior) when omitted. */
  routeType?: JourneyRouteType;
  /** A single stop the trip should route through — SL's own `name_via` (`type_via` is
   * always `"any"` per the official OpenAPI spec, so there is no separate type option to
   * set). Only used for a deliberately targeted search — see
   * backend/src/services/candidateCollector.ts's own doc on when/why. */
  viaStopId?: string;
}

export interface SlJourneyPlannerClient {
  searchStops(query: string): Promise<RawJourneyPlannerLocation[]>;
  trips(request: TripsRequest): Promise<RawJourneyPlannerJourney[]>;
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
    async trips(request) {
      const enabled = new Set(request.transportModes ?? journeyTransportModes);
      const { itdDate, itdTime } = toItdDateTime(request.departureAt);
      const params = new URLSearchParams({
        type_origin: "any",
        type_destination: "any",
        name_origin: request.originId,
        name_destination: request.destinationId,
        calc_number_of_trips: "3",
        route_type: request.routeType ?? "leasttime",
        // Without this, SL may include a trip departing before the requested itd_date/
        // itd_time in the result set (its "closest to the requested time" behavior is not
        // one-directional by default) — one root cause of a past departure ever being
        // ranked as PRIMARY (see journeys.ts's own defensive departure-time filter for
        // the other layer of protection against this same class of bug).
        calc_one_direction: "true",
        // The explicit anchor for this search — see TripsRequest.departureAt's own doc.
        itd_date: itdDate,
        itd_time: itdTime,
        itd_trip_date_time_dep_arr: "dep",
        // SL's own default (nine) is far looser than a commute journey should ever need; this
        // app never wants to present a nine-change trip as a viable regular/alternative option.
        // journeys.ts independently filters journey.transferCount defensively — this upstream
        // parameter's job is only to avoid asking SL to consider (and cache) such trips at all.
        max_changes: String(request.maxChanges),
        incl_mot_0: String(enabled.has("TRAIN")),
        incl_mot_2: String(enabled.has("METRO")),
        incl_mot_4: String(enabled.has("TRAM")),
        incl_mot_5: String(enabled.has("BUS")),
        incl_mot_9: String(enabled.has("FERRY")),
        incl_mot_10: String(enabled.has("BUS")),
        incl_mot_14: String(enabled.has("TRAIN")),
        incl_mot_19: String(enabled.has("BUS")),
      });
      if (request.viaStopId != null) {
        params.set("type_via", "any");
        params.set("name_via", request.viaStopId);
      }
      const response = await fetchUpstreamJson(`${baseUrl}/trips?${params}`, TripsSchema, {
        upstreamName: "SL Journey Planner",
      });
      return response.journeys ?? [];
    },
  };
}
