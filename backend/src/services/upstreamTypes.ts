import { z } from "zod";

/**
 * A contract integer field (site/stop/line/journey IDs, direction codes, deviation
 * versions/case IDs, priority/importance levels): always a whole number that upstream
 * documents as an identifier or ordinal, never a measurement that could plausibly carry
 * a fractional part. Rejecting non-integers and integers outside the safe range here
 * (rather than downstream) turns a corrupt upstream payload into a controlled
 * `UPSTREAM_ERROR`/502 at the validation boundary instead of an `IEEE754` rounding bug
 * or an unexpected crash somewhere later in the normalization pipeline.
 */
const SafeIntSchema = z.number().int().safe();

/**
 * An RFC 3339 / ISO 8601 timestamp with an explicit UTC offset (either `Z` or
 * `+HH:MM`/`-HH:MM`) — never a naive, timezone-less string. SL Deviations documents
 * `created`/`modified`/`publish.from`/`publish.upto` as already being in this form (see
 * docs/api-contract.md, unlike SL Transport's naive `scheduled`/`expected`, which are
 * handled separately by src/lib/stockholmTime.ts). Zod's default `precision` (unset)
 * accepts any number of fractional-second digits, matching upstream's own inconsistent
 * formatting (e.g. `.01`, `.15`, `.000`).
 */
const ExplicitOffsetDateTimeSchema = z.string().datetime({ offset: true });

/**
 * Runtime (Zod) schemas for the upstream SL Transport / SL Deviations JSON payloads,
 * typed only as far as verified during architecture review (see docs/api-contract.md).
 * Every object schema uses `.passthrough()`: fields outside this list are ignored by
 * the normalization layer, not stripped or rejected — upstream may add fields at any
 * time and this backend must not break, or silently discard data, when it does.
 *
 * These schemas are the enforcement point for "runtime-validate upstream data": each
 * service client (see slTransportClient.ts / slDeviationsClient.ts) parses the raw
 * upstream JSON through the relevant schema via `fetchUpstreamJson` before it is ever
 * cast to a TypeScript type. A payload that fails validation becomes a controlled
 * `AppError("UPSTREAM_ERROR", ...)` (HTTP 502), not an unchecked cast that could crash
 * or silently misbehave deeper in the normalization pipeline.
 */

export const RawSlSiteSchema = z
  .object({
    id: SafeIntSchema,
    name: z.string(),
    note: z.string().nullable().optional(),
    // Nullable AND optional: confirmed against the real (non-fixture) upstream response
    // that some sites are returned with `lat`/`lon` entirely absent, not merely `null`
    // (see the 2026-07-28 production incident where these being required caused the
    // ENTIRE site list — and therefore ALL stop search — to fail with UPSTREAM_ERROR,
    // since one non-conforming site invalidates the whole `z.array(...)` parse).
    lat: z.number().nullable().optional(),
    lon: z.number().nullable().optional(),
    stop_areas: z.array(SafeIntSchema),
  })
  .passthrough();
export type RawSlSite = z.infer<typeof RawSlSiteSchema>;
export const RawSlSiteListSchema = z.array(RawSlSiteSchema);

export const RawJourneySchema = z
  .object({
    id: SafeIntSchema,
    state: z.string(),
    prediction_state: z.string().nullable().optional(),
  })
  .passthrough();
export type RawJourney = z.infer<typeof RawJourneySchema>;

export const RawTripDeviationSchema = z
  .object({
    importance_level: SafeIntSchema,
    consequence: z.string(),
    message: z.string(),
  })
  .passthrough();
export type RawTripDeviation = z.infer<typeof RawTripDeviationSchema>;

export const RawStopAreaRefSchema = z
  .object({
    id: SafeIntSchema,
    name: z.string(),
    type: z.string().nullable().optional(),
  })
  .passthrough();
export type RawStopAreaRef = z.infer<typeof RawStopAreaRefSchema>;

export const RawStopPointRefSchema = z
  .object({
    id: SafeIntSchema,
    name: z.string(),
    designation: z.string().nullable().optional(),
  })
  .passthrough();
export type RawStopPointRef = z.infer<typeof RawStopPointRefSchema>;

export const RawLineRefSchema = z
  .object({
    id: SafeIntSchema,
    designation: z.string(),
    transport_mode: z.string(),
  })
  .passthrough();
export type RawLineRef = z.infer<typeof RawLineRefSchema>;

export const RawDepartureSchema = z
  .object({
    direction: z.string().nullable().optional(),
    direction_code: SafeIntSchema.nullable().optional(),
    via: z.string().nullable().optional(),
    destination: z.string().nullable().optional(),
    state: z.string(),
    scheduled: z.string(),
    expected: z.string().nullable().optional(),
    journey: RawJourneySchema,
    stop_area: RawStopAreaRefSchema,
    stop_point: RawStopPointRefSchema,
    line: RawLineRefSchema,
    deviations: z.array(RawTripDeviationSchema).optional(),
  })
  .passthrough();
export type RawDeparture = z.infer<typeof RawDepartureSchema>;

export const RawSiteDeviationLineRefSchema = z
  .object({
    id: SafeIntSchema,
    designation: z.string(),
    transport_mode: z.string().nullable().optional(),
  })
  .passthrough();
export type RawSiteDeviationLineRef = z.infer<typeof RawSiteDeviationLineRefSchema>;

const RawScopeStopAreaSchema = z
  .object({ id: SafeIntSchema, name: z.string(), type: z.string().nullable().optional() })
  .passthrough();
const RawScopeStopPointSchema = z.object({ id: SafeIntSchema, name: z.string() }).passthrough();

export const RawSiteDeviationSchema = z
  .object({
    id: SafeIntSchema,
    importance_level: SafeIntSchema,
    message: z.string(),
    scope: z
      .object({
        stop_areas: z.array(RawScopeStopAreaSchema).optional(),
        stop_points: z.array(RawScopeStopPointSchema).optional(),
        lines: z.array(RawSiteDeviationLineRefSchema).optional(),
      })
      .passthrough(),
  })
  .passthrough();
export type RawSiteDeviation = z.infer<typeof RawSiteDeviationSchema>;

export const RawDeparturesResponseSchema = z
  .object({
    departures: z.array(RawDepartureSchema),
    stop_deviations: z.array(RawSiteDeviationSchema).optional(),
  })
  .passthrough();
export type RawDeparturesResponse = z.infer<typeof RawDeparturesResponseSchema>;

export const RawMessageVariantSchema = z
  .object({
    header: z.string(),
    details: z.string(),
    scope_alias: z.string().nullable().optional(),
    weblink: z.string().nullable().optional(),
    language: z.string(),
  })
  .passthrough();
export type RawMessageVariant = z.infer<typeof RawMessageVariantSchema>;

const RawDeviationScopeLineSchema = z
  .object({
    id: SafeIntSchema,
    designation: z.string(),
    transport_mode: z.string().nullable().optional(),
    name: z.string().nullable().optional(),
  })
  .passthrough();

export const RawDeviationSchema = z
  .object({
    version: SafeIntSchema,
    created: ExplicitOffsetDateTimeSchema,
    modified: ExplicitOffsetDateTimeSchema.nullable().optional(),
    deviation_case_id: SafeIntSchema,
    publish: z
      .object({
        from: ExplicitOffsetDateTimeSchema.nullable().optional(),
        upto: ExplicitOffsetDateTimeSchema.nullable().optional(),
      })
      .passthrough()
      .optional(),
    priority: z
      .object({
        importance_level: SafeIntSchema,
        influence_level: SafeIntSchema,
        urgency_level: SafeIntSchema,
      })
      .passthrough(),
    message_variants: z.array(RawMessageVariantSchema).min(1),
    scope: z
      .object({
        stop_areas: z.array(RawScopeStopAreaSchema).optional(),
        lines: z.array(RawDeviationScopeLineSchema).optional(),
      })
      .passthrough(),
  })
  .passthrough();
export type RawDeviation = z.infer<typeof RawDeviationSchema>;
export const RawDeviationListSchema = z.array(RawDeviationSchema);
