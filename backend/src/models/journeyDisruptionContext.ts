import { z } from "zod";

/**
 * Additive structural metadata `normalize/normalizeJourney.ts` attaches to every journey
 * `/api/v1/journeys` returns, alongside the existing `legs`/`disruptionNotices` fields — never a
 * replacement for either. Android does not interpret this: it retains the PRIMARY journey's own
 * `disruptionContext` unchanged and sends it back verbatim as part of `POST
 * /api/v1/journeys/disruptions` (see `routes/journeyDisruptions.ts`), which is the only place
 * this context is ever actually resolved against SL Deviations (via
 * `domain/journeyDisruptionScope.ts` and `services/stopPointDirectory.ts`) — see this module's
 * own "why additive, why here" doc below.
 *
 * `version` exists so a FUTURE incompatible shape change can be introduced without breaking an
 * in-flight OLDER Android build that still sends back a `version: 1` context it fetched before
 * upgrading: `routes/journeyDisruptions.ts` only ever attempts the rich per-leg resolution for a
 * context whose `version` it actually recognizes, falling back to the pre-existing legacy
 * `legs`/`originSiteId`-only PARTIAL resolution otherwise (see that route's own doc) — the SAME
 * backward-compatible fallback an ABSENT `disruptionContext` already takes.
 *
 * `journeyStart`/`journeyEnd` are the journey's own already-computed `originName`/
 * `destinationName` — descriptive only (operator-facing logs/diagnostics if a resolution ever
 * looks suspicious), never consulted by any matching/relevance logic: accessibility/travelled-
 * path relevance must always follow PRIMARY's own actual structural route, never a routine's
 * requested-destination TEXT (see `domain/journeyDisruptionScope.ts`'s own doc on why a rerouted
 * journey's real transit legs are the only thing that decides relevance).
 *
 * ## Why additive, why produced here
 *
 * `/api/v1/journeys` must stay fast and independent of the disruption-resolution path (see
 * `routes/journeyDisruptions.ts`'s own doc on why that is a genuinely separate HTTP call) — this
 * context is cheap to produce because it is nothing more than the SAME Journey Planner
 * `stopSequence`/`origin`/`destination` data `normalizeJourney.ts` already parses, filtered down
 * to the `type: "platform" && isGlobalId: true` nodes that are actually resolvable identifiers
 * (see `services/stopPointDirectory.ts`'s own doc for that bridge). No `StopPointDirectory`
 * lookup, no SL Deviations snapshot read, and no other upstream call happens while building this
 * — it is pure, synchronous, structural extraction from a response `/api/v1/journeys` was going
 * to normalize anyway.
 */
export const JOURNEY_DISRUPTION_CONTEXT_VERSION = 1;

const JourneyDisruptionContextLegSchema = z
  .object({
    transportMode: z.string(),
    lineDesignation: z.string().nullable(),
    // Present only when Journey Planner itself pinned a specific boarding/alighting platform
    // for this leg (see normalizeJourney.ts's own platformPatternPointGid) -- absent, never a
    // fabricated value, the moment it returned a coarser `type: "stop"` node instead (confirmed
    // live: this genuinely happens for a leg's own origin even when the SAME physical stop
    // appears as a resolvable platform elsewhere in that trip's stopSequence).
    boardingPatternPointGid: z.string().optional(),
    alightingPatternPointGid: z.string().optional(),
    // Every stopSequence entry that WAS resolvable as a platform id, in original order --
    // shorter than the raw stopSequence itself whenever one or more entries were not (see
    // stopSequenceComplete below for how that gap is represented rather than silently dropped).
    stopPatternPointGids: z.array(z.string()),
    // true only when Journey Planner supplied a non-empty stopSequence for this leg AND every
    // single entry in it resolved to a usable platform id -- see
    // domain/journeyDisruptionScope.ts's own doc for exactly how this feeds TRAVELLED_PATH's own
    // PARTIAL/COMPLETE completeness. Deliberately independent of whether boarding/alighting
    // themselves resolved: a leg can have complete-looking ACCESS_POINTS while its own
    // TRAVELLED_PATH stays PARTIAL, or vice versa.
    stopSequenceComplete: z.boolean(),
  })
  .strict();
export type JourneyDisruptionContextLeg = z.infer<typeof JourneyDisruptionContextLegSchema>;

export const JourneyDisruptionContextSchema = z
  .object({
    // Deliberately z.number(), NOT z.literal(JOURNEY_DISRUPTION_CONTEXT_VERSION): a future
    // Android build newer than this backend could send a differently-versioned context.
    // Rejecting it at the SCHEMA layer merely for carrying an unrecognized version number would
    // 400 the whole request even when its shape happens to still be perfectly parseable here —
    // `routes/journeyDisruptions.ts` is what actually decides whether to trust a given version
    // (comparing it to JOURNEY_DISRUPTION_CONTEXT_VERSION at runtime), falling back to the
    // legacy PARTIAL resolution otherwise, exactly as it already does for a MISSING
    // disruptionContext. A genuinely incompatible future shape (renamed/removed fields) still
    // fails this schema on its OWN structural grounds, independent of this field.
    version: z.number().int().positive(),
    journeyStart: z.string(),
    journeyEnd: z.string(),
    legs: z.array(JourneyDisruptionContextLegSchema),
  })
  .strict();
export type JourneyDisruptionContext = z.infer<typeof JourneyDisruptionContextSchema>;
