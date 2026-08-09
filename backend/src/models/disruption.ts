import { z } from "zod";
import { TransportModeSchema } from "./common.js";

export const DisruptionMessageSchema = z.object({
  header: z.string(),
  details: z.string(),
  scopeAlias: z.string().nullable(),
  webLink: z.string().nullable(),
  language: z.string(),
});
export type DisruptionMessage = z.infer<typeof DisruptionMessageSchema>;

export const DisruptionPrioritySchema = z.object({
  importance: z.number(),
  influence: z.number(),
  urgency: z.number(),
});

/**
 * Blick's own closed set of passenger-facing disruption effects — a deterministic local
 * classification of the upstream SL Deviations message (`src/normalize/classifyDisruptionEffect.ts`),
 * never an SL-provided field. `DISRUPTION` is the conservative fallback used both for text the
 * classifier doesn't confidently recognize and for any non-Swedish message (see that file's own
 * doc) — a generic label is preferable to a confidently wrong one. Closed and validated (`z.enum`)
 * deliberately unlike `TransportModeSchema`: every possible value is produced by this backend's
 * own classifier, never passed through from an unpredictable upstream field, so there is no
 * forward-compatibility reason to accept an unrecognized string here the way there is for SL's
 * own `transport_mode` (see docs/api-contract.md, "Request validation vs. response compatibility").
 */
export const DISRUPTION_EFFECTS = [
  "DELAYS",
  "NO_SERVICE",
  "REDUCED_SERVICE",
  "ROUTE_CHANGE",
  "STOP_CHANGE",
  "REPLACEMENT_SERVICE",
  "STATION_ACCESS",
  "ACCESSIBILITY_ISSUE",
  "DISRUPTION",
] as const;
export const DisruptionEffectSchema = z.enum(DISRUPTION_EFFECTS);
export type DisruptionEffect = z.infer<typeof DisruptionEffectSchema>;

export const DisruptionSchema = z.object({
  disruptionId: z.string(),
  version: z.number(),
  createdAt: z.string(),
  modifiedAt: z.string().nullable(),
  validFrom: z.string().nullable(),
  validUntil: z.string().nullable(),
  priority: DisruptionPrioritySchema,
  effect: DisruptionEffectSchema,
  message: DisruptionMessageSchema,
  affectedStopAreas: z.array(z.object({ id: z.number().int(), name: z.string(), type: z.string().nullable() })),
  affectedLines: z.array(
    z.object({
      id: z.number().int(),
      designation: z.string(),
      transportMode: TransportModeSchema,
      name: z.string().nullable(),
    }),
  ),
  affectedModes: z.array(TransportModeSchema),
});
export type Disruption = z.infer<typeof DisruptionSchema>;

export const DisruptionsResponseSchema = z.object({
  fetchedAt: z.string(),
  disruptions: z.array(DisruptionSchema),
});
export type DisruptionsResponse = z.infer<typeof DisruptionsResponseSchema>;
