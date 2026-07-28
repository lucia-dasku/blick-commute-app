import { z } from "zod";
import { TransportModeSchema } from "./common.js";

/**
 * `state` (departure) and `journey.state` / `journey.predictionState` are intentionally
 * typed as plain strings, not closed enums: SL Transport's OpenAPI spec could not be
 * fully verified during architecture review (see docs/api-contract.md), and the product
 * doc requires unfamiliar future values to be treated as forward-compatible rather than
 * fail deserialization.
 */
export const JourneySchema = z.object({
  id: z.number(),
  state: z.string(),
  predictionState: z.string().nullable(),
});
export type Journey = z.infer<typeof JourneySchema>;

export const TripDeviationSchema = z.object({
  importanceLevel: z.number(),
  consequence: z.string(),
  message: z.string(),
});
export type TripDeviation = z.infer<typeof TripDeviationSchema>;

export const StopAreaRefSchema = z.object({
  id: z.number().int(),
  name: z.string(),
  type: z.string().nullable(),
});
export type StopAreaRef = z.infer<typeof StopAreaRefSchema>;

export const StopPointRefSchema = z.object({
  id: z.number().int(),
  name: z.string(),
  designation: z.string().nullable(),
});
export type StopPointRef = z.infer<typeof StopPointRefSchema>;

export const LineRefSchema = z.object({
  id: z.number().int(),
  designation: z.string(),
  transportMode: TransportModeSchema,
});
export type LineRef = z.infer<typeof LineRefSchema>;

export const DepartureSchema = z.object({
  departureId: z.string(),
  line: LineRefSchema,
  direction: z.string().nullable(),
  directionCode: z.number().int().nullable(),
  destination: z.string().nullable(),
  via: z.string().nullable(),
  stopArea: StopAreaRefSchema,
  stopPoint: StopPointRefSchema,
  scheduledTime: z.string(),
  expectedTime: z.string().nullable(),
  state: z.string(),
  isCancelled: z.boolean(),
  journey: JourneySchema,
  tripDeviations: z.array(TripDeviationSchema),
});
export type Departure = z.infer<typeof DepartureSchema>;

export const SiteDeviationLineRefSchema = z.object({
  id: z.number().int(),
  designation: z.string(),
  transportMode: TransportModeSchema.nullable(),
});

export const SiteDeviationSchema = z.object({
  id: z.number().int(),
  importanceLevel: z.number(),
  message: z.string(),
  affectedStopAreas: z.array(z.object({ id: z.number().int(), name: z.string(), type: z.string().nullable() })),
  affectedStopPoints: z.array(z.object({ id: z.number().int(), name: z.string() })),
  affectedLines: z.array(SiteDeviationLineRefSchema),
});
export type SiteDeviation = z.infer<typeof SiteDeviationSchema>;

export const DeparturesResponseSchema = z.object({
  fetchedAt: z.string(),
  timeZone: z.literal("Europe/Stockholm"),
  siteId: z.number().int(),
  departures: z.array(DepartureSchema),
  siteDeviations: z.array(SiteDeviationSchema),
});
export type DeparturesResponse = z.infer<typeof DeparturesResponseSchema>;
