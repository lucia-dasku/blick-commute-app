import type { RawDeviation, RawMessageVariant } from "../services/upstreamTypes.js";
import type { Disruption, DisruptionMessage } from "../models/disruption.js";
import { asTransportMode } from "./transportMode.js";

/**
 * Selects the Swedish-language message variant. Falls back to the first available
 * variant only when no `language === "sv"` entry exists — never assumes index 0 is
 * Swedish (SL Deviations can and does return other languages).
 */
export function selectMessageVariant(variants: readonly RawMessageVariant[]): RawMessageVariant {
  if (variants.length === 0) {
    throw new Error("Deviation has no message_variants at all");
  }
  const swedish = variants.find((v) => v.language === "sv");
  return swedish ?? variants[0]!;
}

function normalizeMessage(raw: RawMessageVariant): DisruptionMessage {
  return {
    header: raw.header,
    details: raw.details,
    scopeAlias: raw.scope_alias ?? null,
    webLink: raw.weblink ?? null,
    language: raw.language,
  };
}

export function normalizeDisruption(raw: RawDeviation): Disruption {
  const message = normalizeMessage(selectMessageVariant(raw.message_variants));
  const affectedLines = (raw.scope.lines ?? []).map((l) => ({
    id: l.id,
    designation: l.designation,
    transportMode: asTransportMode(l.transport_mode),
    name: l.name ?? null,
  }));

  return {
    disruptionId: String(raw.deviation_case_id),
    version: raw.version,
    createdAt: raw.created,
    modifiedAt: raw.modified ?? null,
    validFrom: raw.publish?.from ?? null,
    validUntil: raw.publish?.upto ?? null,
    priority: {
      importance: raw.priority.importance_level,
      influence: raw.priority.influence_level,
      urgency: raw.priority.urgency_level,
    },
    message,
    affectedStopAreas: (raw.scope.stop_areas ?? []).map((a) => ({ id: a.id, name: a.name, type: a.type ?? null })),
    affectedLines,
    affectedModes: Array.from(new Set(affectedLines.map((l) => l.transportMode))),
  };
}
