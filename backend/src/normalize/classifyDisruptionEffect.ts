import type { DisruptionEffect, DisruptionMessage } from "../models/disruption.js";

/**
 * Swedish letters — used to build word-boundary-safe patterns that correctly treat å/ä/ö as
 * ordinary letters. JavaScript's native `\b` is ASCII-only (`\w` is `[A-Za-z0-9_]`), so a plain
 * `\bändrad\b` fails to anchor correctly on a word that itself starts or ends with å/ä/ö — the
 * hand-rolled lookaround helpers below do not have that gap.
 */
const SV_LETTER = "a-zåäö";

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * True if [stem] occurs in [text] at the start of a word — any suffix may follow (so `"hiss"`
 * matches `"hissen"`/`"hissar"`), but [stem] must not be preceded by another letter, so it never
 * fires mid-word inside an unrelated compound.
 */
function startsWord(text: string, stem: string): boolean {
  return new RegExp(`(?<![${SV_LETTER}])${escapeRegExp(stem)}`).test(text);
}

/**
 * True if [suffix] occurs in [text] at the end of a word — any prefix may precede it (so
 * `"stängd"` matches both bare "stängd" and "avstängd"), but nothing may follow [suffix] within
 * the same word.
 */
function endsWord(text: string, suffix: string): boolean {
  return new RegExp(`${escapeRegExp(suffix)}(?![${SV_LETTER}])`).test(text);
}

/**
 * True if the exact [phrase] occurs anywhere in [text] (both already normalized). Safe as a
 * plain substring check for multi-word phrases — the phrase's own internal space already rules
 * out an accidental mid-word match the way a single bare word would risk.
 */
function hasPhrase(text: string, phrase: string): boolean {
  return text.includes(phrase);
}

/** Lowercases and collapses all whitespace (including newlines) to single spaces, so a phrase
 * split across a line break or padded with repeated spaces still matches. */
function normalize(text: string): string {
  return text.toLowerCase().replace(/\s+/g, " ").trim();
}

/** `"stängd"`/`"stängs"` as a word-final suffix also matches `"avstängd"`/`"avstängs"` for free
 * (the `av-` prefix is exactly what [endsWord] deliberately ignores) — reused by both
 * ACCESSIBILITY_ISSUE and STATION_ACCESS, which differ only in *what* the closure applies to. */
function isClosedWording(text: string): boolean {
  return endsWord(text, "stängd") || endsWord(text, "stängs");
}

/**
 * Precedence-ordered rules — deliberately a list, not nested if/else, so the order itself is the
 * one documented precedence (see docs/api-contract.md and this file's own test suite, "precedence"
 * describe block). Earlier entries win when a text matches more than one; see each rule's inline
 * comment for the source wording it implements (docs/api-contract.md §3, "Disruption effect
 * classification").
 */
const RULES: ReadonlyArray<{ effect: Exclude<DisruptionEffect, "DISRUPTION">; test: (text: string) => boolean }> = [
  {
    // Whole-service suspension. Deliberately narrow (the exact phrase only) rather than also
    // matching bare "inställd" — "En avgång är inställd" (ONE cancelled departure) must never
    // become NO_SERVICE.
    effect: "NO_SERVICE",
    test: (text) => hasPhrase(text, "ingen trafik"),
  },
  {
    effect: "REPLACEMENT_SERVICE",
    test: (text) => startsWord(text, "ersättningsbuss") || startsWord(text, "ersättningstrafik"),
  },
  {
    // "En avgång är inställd" intentionally does not reach this rule either (no wording here
    // means "only some/a few" the way "glesare"/"reducerad"/"färre" unambiguously do) — it falls
    // all the way through to the generic DISRUPTION fallback, never confidently guessed at.
    effect: "REDUCED_SERVICE",
    test: (text) => hasPhrase(text, "glesare trafik") || hasPhrase(text, "reducerad trafik") || hasPhrase(text, "färre avgångar"),
  },
  {
    effect: "ROUTE_CHANGE",
    test: (text) =>
      startsWord(text, "omled") || startsWord(text, "omlag") || hasPhrase(text, "annan körväg") || hasPhrase(text, "ändrad körväg"),
  },
  {
    // "hållplats" + a moved/withdrawn word anywhere in the same text (not necessarily adjacent —
    // "Hållplatsen är tillfälligt flyttad" has "är tillfälligt" in between) is the strong,
    // specific combined signal; the two standalone phrases are strong on their own.
    effect: "STOP_CHANGE",
    test: (text) =>
      hasPhrase(text, "stannar inte vid") ||
      hasPhrase(text, "angör inte") ||
      (startsWord(text, "hållplats") &&
        (startsWord(text, "flyttad") || startsWord(text, "flyttat") || startsWord(text, "indragen") || startsWord(text, "indragna"))),
  },
  {
    // Accessibility requires an actual problem, not merely the word "hiss"/"rulltrappa" —
    // "tillgänglighetsproblem" is the one standalone exception, since it already names the
    // problem outright.
    effect: "ACCESSIBILITY_ISSUE",
    test: (text) =>
      startsWord(text, "tillgänglighetsproblem") ||
      ((startsWord(text, "hiss") || startsWord(text, "rulltrapp")) &&
        (hasPhrase(text, "ur funktion") || hasPhrase(text, "fungerar inte") || isClosedWording(text))),
  },
  {
    effect: "STATION_ACCESS",
    test: (text) => (startsWord(text, "entré") || startsWord(text, "ingång") || startsWord(text, "utgång")) && isClosedWording(text),
  },
  {
    effect: "DELAYS",
    test: (text) => startsWord(text, "försen"),
  },
];

/**
 * Classifies one piece of already-selected message text (a header OR a details body) against
 * [RULES] in precedence order. Returns `null` — never `"DISRUPTION"` — when nothing matches, so
 * [classifyDisruptionEffect] can tell "this text said nothing specific" apart from "this text
 * was itself classified as generic", and fall through to classifying [DisruptionMessage.details]
 * only in the former case.
 */
export function classifyEffectFromText(rawText: string): DisruptionEffect | null {
  const text = normalize(rawText);
  for (const rule of RULES) {
    if (rule.test(text)) return rule.effect;
  }
  return null;
}

/**
 * Classifies [message] into one of Blick's nine passenger-facing disruption effects. Header
 * first, details only as a fallback: SL's header normally states the passenger effect, while
 * details often only add causes or secondary information (a real example already in
 * `fixtures/slDeviationsSlussen.sample.json`: header "L401 försenat avgång med 5 minuter"
 * classifies as DELAYS on its own; its details separately mention a bridge opening, which must
 * never steal the classification away from the header's own, more specific wording). Only when
 * the header itself matches nothing specific are the details classified the same way.
 *
 * Swedish-only for v1: a message whose selected variant is not Swedish is never run through
 * these hand-tuned Swedish rules — `selectMessageVariant` already prefers the Swedish variant
 * when one exists (`normalizeDisruption.ts`), so this only matters for the rare disruption with
 * no Swedish variant at all. `"DISRUPTION"` is always a safe, conservative fallback; a
 * confidently wrong classification is worse than a generic one.
 */
export function classifyDisruptionEffect(message: DisruptionMessage): DisruptionEffect {
  if (message.language !== "sv") return "DISRUPTION";
  return classifyEffectFromText(message.header) ?? classifyEffectFromText(message.details) ?? "DISRUPTION";
}
