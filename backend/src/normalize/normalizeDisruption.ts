import type { RawDeviation, RawMessageVariant } from "../services/upstreamTypes.js";
import type { Disruption, DisruptionMessage } from "../models/disruption.js";
import { asTransportMode } from "./transportMode.js";
import { classifyDisruptionEffectWithDiagnostics } from "./classifyDisruptionEffect.js";

/** Avoid a success log on every 30-second client poll: one diagnostic per disruption version per
 * process is enough to identify the deterministic rule/version that produced its category. */
const loggedClassifications = new Set<string>();
const MAX_LOGGED_CLASSIFICATIONS = 2_000;

function logClassificationOnce(raw: RawDeviation, diagnostic: ReturnType<typeof classifyDisruptionEffectWithDiagnostics>): void {
  const key = `${raw.deviation_case_id}:${raw.version}`;
  if (loggedClassifications.has(key)) return;
  if (loggedClassifications.size >= MAX_LOGGED_CLASSIFICATIONS) {
    const oldest = loggedClassifications.values().next().value as string | undefined;
    if (oldest != null) loggedClassifications.delete(oldest);
  }
  loggedClassifications.add(key);
  console.info(
    `event=disruption_classified disruptionId=${raw.deviation_case_id} disruptionVersion=${raw.version} classifierVersion=${diagnostic.classifierVersion} matchedRule=${diagnostic.matchedRule} matchedTextSource=${diagnostic.matchedTextSource} effect=${diagnostic.effect} importance=${raw.priority.importance_level} influence=${raw.priority.influence_level} urgency=${raw.priority.urgency_level}`,
  );
}

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
  const classification = classifyDisruptionEffectWithDiagnostics(message);
  logClassificationOnce(raw, classification);
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
    effect: classification.effect,
    message,
    affectedStopAreas: (raw.scope.stop_areas ?? []).map((a) => ({ id: a.id, name: a.name, type: a.type ?? null })),
    affectedLines,
    affectedModes: Array.from(new Set(affectedLines.map((l) => l.transportMode))),
  };
}
