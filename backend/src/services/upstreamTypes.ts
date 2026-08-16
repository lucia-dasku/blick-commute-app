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

export const RawStopPointRefSchema = z
  .object({
    id: SafeIntSchema,
    name: z.string(),
    designation: z.string().nullable().optional(),
  })
  .passthrough();

export const RawLineRefSchema = z
  .object({
    id: SafeIntSchema,
    designation: z.string(),
    transport_mode: z.string(),
  })
  .passthrough();

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
        // Confirmed live (2026-08-16 architecture review for exact-destination disruption
        // scoping) that the real /v1/messages feed's own `scope` never actually populates this
        // for any of a live 159-deviation snapshot -- `stop_areas`/`lines` are the only scope
        // evidence SL currently sends on this endpoint. Modeled here anyway, reusing the exact
        // same shape the embedded RawSiteDeviationSchema (`/v1/sites/{id}/departures`) already
        // uses for it: additive/optional, so it costs nothing today and is used automatically
        // the moment SL ever does start populating it here, with no further schema change
        // required. See backend/src/domain/journeyDisruptionScope.ts's own doc for why a
        // platform-specific StopPoint match is kept as separate, more precise evidence from a
        // station-wide StopArea match rather than being collapsed into it up front.
        stop_points: z.array(RawScopeStopPointSchema).optional(),
        lines: z.array(RawDeviationScopeLineSchema).optional(),
      })
      .passthrough(),
  })
  .passthrough();
export type RawDeviation = z.infer<typeof RawDeviationSchema>;
export const RawDeviationListSchema = z.array(RawDeviationSchema);

/**
 * Runtime schema for SL Transport's `/v1/stop-points` — a nationwide reference-data snapshot
 * (14,187 entries confirmed live for the SL region) used ONLY by `StopPointDirectory`
 * (`services/stopPointDirectory.ts`) to resolve a Journey Planner `stopSequence` platform's own
 * `id` (`type: "platform"`, `isGlobalId: true`) to the SL-Transport/Deviations-namespace
 * StopArea/StopPoint identity a disruption's own `scope` can actually be compared against — see
 * that service's own doc for the full identity-resolution contract and the live evidence that
 * `platform.id` and this schema's own `pattern_point_gid` are the same value.
 *
 * Deliberately NOT parsed via the ordinary `fetchUpstreamJson`/`response.json()` path every
 * other upstream client in this codebase uses: `gid`/`pattern_point_gid` are routinely larger
 * than `Number.MAX_SAFE_INTEGER` (confirmed live — EVERY one of the 14,187 real entries exceeds
 * it), and `response.json()` would silently round them. `slTransportClient.ts`'s
 * `fetchStopPoints()` instead reads the response body as text and parses it with
 * `lib/losslessJson.ts`'s `parseLosslessJson`, which returns every JSON number (of any
 * magnitude) as its exact source digit string — this is why EVERY numeric field below is typed
 * as a string on the wire and only coerced to a real `number` where the value is confirmed to
 * always stay within the safe range (`id`, `stop_area.id`): `gid` and `pattern_point_gid`
 * themselves are never coerced, staying exact strings end to end (see `PatternPointGid` in
 * `services/stopPointDirectory.ts`).
 */
const LosslessNumericStringSchema = z
  .string()
  .regex(/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/, "Expected a JSON number literal preserved as a string by parseLosslessJson");

/** A numeric-string field this backend wants as an ordinary JS `number` — used only for fields
 * confirmed to stay within `Number.MAX_SAFE_INTEGER` (small SL Transport ids), never for
 * `gid`/`pattern_point_gid` themselves (see `RawStopPointSchema`'s own doc). */
const SafeIntFromLosslessStringSchema = LosslessNumericStringSchema.transform((v) => Number(v)).pipe(z.number().int().safe());

/** An identifier field kept as an exact digit string specifically because it may exceed
 * `Number.MAX_SAFE_INTEGER` (`gid`, `pattern_point_gid`) — validated to be a well-formed
 * non-negative integer lexeme, but deliberately NEVER transformed to a JS `number`. */
const BigIntIdentifierStringSchema = z.string().regex(/^\d+$/, "Expected a non-negative integer lexeme preserved as a string");

export const RawStopPointSchema = z
  .object({
    id: SafeIntFromLosslessStringSchema,
    gid: BigIntIdentifierStringSchema,
    pattern_point_gid: BigIntIdentifierStringSchema,
    name: z.string(),
    // SL's own sub-type of stop point -- confirmed live values include "PLATFORM" (metro/train/
    // tram), "BUSSTOP", and "PIER" (ferry); kept as a permissive string, never a closed enum, for
    // the same forward-compatibility reason every other upstream-native `type`/`transport_mode`
    // field in this codebase is (see docs/api-contract.md §3.5).
    type: z.string(),
    stop_area: z
      .object({
        id: SafeIntFromLosslessStringSchema,
        name: z.string(),
        type: z.string().nullable().optional(),
      })
      .passthrough(),
  })
  .passthrough();
export type RawStopPoint = z.infer<typeof RawStopPointSchema>;
export const RawStopPointListSchema = z.array(RawStopPointSchema);
