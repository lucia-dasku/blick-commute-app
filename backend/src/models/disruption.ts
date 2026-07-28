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

export const DisruptionSchema = z.object({
  disruptionId: z.string(),
  version: z.number(),
  createdAt: z.string(),
  modifiedAt: z.string().nullable(),
  validFrom: z.string().nullable(),
  validUntil: z.string().nullable(),
  priority: DisruptionPrioritySchema,
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
