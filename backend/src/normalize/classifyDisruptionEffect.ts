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
 * True if the exact [phrase] occurs anywhere in [text] (both already normalized). Safe as a
 * plain substring check for multi-word phrases — the phrase's own internal space already rules
 * out an accidental mid-word match the way a single bare word would risk.
 */
function hasPhrase(text: string, phrase: string): boolean {
  return text.includes(phrase);
}

/** Lowercases and collapses all whitespace (including newlines) to single spaces, so a phrase
 * split across a line break or padded with repeated spaces still matches. Used for the simple,
 * unscoped rules below — compound rules use [segmentIntoUnits] instead (see its own doc). */
function normalize(text: string): string {
  return text.toLowerCase().replace(/\s+/g, " ").trim();
}

// ---- Scope-aware matching for the three compound rules ----
//
// STOP_CHANGE, ACCESSIBILITY_ISSUE, and STATION_ACCESS each require two independent halves —
// e.g. "hiss"/"rulltrappa" AND a problem word — to hold at once. Checking those halves against
// the *whole* flattened message text lets two unrelated sentences silently combine: a real SL
// disruption's details once read "...entrén ... är öppen." in one sentence and, three paragraphs
// later and about a completely different line, "...Gröna linjen är avstängd..." — the old
// whole-text search paired "entré" from the first sentence with "avstängd" from the second and
// produced STATION_ACCESS for a message that was not about station access at all. The functions
// below segment text into small units (paragraph → line group → sentence → narrow clause) first,
// and each compound rule requires both of its halves to land in the *same* unit.

/** A paragraph break (one or more blank lines) is the one unambiguous hard boundary in SL's own
 * formatting — used to segment before any punctuation-based splitting runs. */
function splitIntoParagraphs(rawText: string): string[] {
  return rawText
    .replace(/\r\n?/g, "\n")
    .split(/\n[ \t]*\n+/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
}

/**
 * Named abbreviations whose period must never be read as a sentence end, chosen for actual
 * relevance to SL's own disruption wording. Split into two lists, not one, because "must never
 * be read as a sentence end" is only true unconditionally for some of them:
 *
 * [ALWAYS_PROTECTED_ABBREVIATIONS] are grammatical connectors — a time, a date, a reason, or a
 * following noun phrase is required within the *same* sentence ("kl. 06.00", "p.g.a. vägarbete",
 * "det s.k. Slussenprojektet") — so real SL text essentially never ends a sentence on one of
 * these alone.
 *
 * [CONDITIONALLY_PROTECTED_ABBREVIATIONS] are list/aside markers ("...biljetter, kort osv.",
 * "...skyltar m.fl.", "...cyklar m.m.") that commonly *do* end a sentence on their own, with a
 * genuinely new, unrelated sentence following. Blanket-protecting these would reintroduce the
 * exact cross-sentence merge this file's scope-aware matching exists to prevent — see
 * "abbreviations that can legitimately end a sentence" in this file's test suite for the real
 * case pattern ("...osv. Trafiken är avstängd.") this distinction exists for. See
 * [continuesSameSentence] for exactly what "conditionally" means here.
 */
const ALWAYS_PROTECTED_ABBREVIATIONS = ["fr.o.m.", "t.o.m.", "p.g.a.", "kl.", "ca.", "s.k."];
const CONDITIONALLY_PROTECTED_ABBREVIATIONS = ["bl.a.", "t.ex.", "m.fl.", "m.m.", "osv.", "dvs."];

/** Domain-like tokens ("sl.se") protected the same way as [ALWAYS_PROTECTED_ABBREVIATIONS],
 * generically by pattern rather than by listing every possible domain: real SL text has no space
 * between the name and its TLD, which is exactly what distinguishes it from a genuine ". Next
 * sentence". */
const DOMAIN_LIKE_PATTERN = /\b[a-zåäö0-9-]+\.(se|com|nu|org|info|net)\b/gi;

/** A marker that cannot occur in real SL text stands in for a protected period during splitting,
 * then is restored afterward — see [protectSpecialPeriods]/[restoreSpecialPeriods]. Printable and
 * `escapeRegExp`-free by construction (plain letters only), so there is no ambiguity about what
 * is actually stored or matched, unlike a control character would risk. */
const PERIOD_SENTINEL = "PERIODSENTINEL";

/**
 * True if [following] — the text immediately after one of [CONDITIONALLY_PROTECTED_ABBREVIATIONS]
 * — clearly continues the same sentence: whitespace, then a *lowercase* Swedish letter. Anything
 * else (a digit, an opening quote or parenthesis, a bullet, an uppercase letter, or nothing at
 * all) is treated as the abbreviation genuinely ending its sentence — splitting there and letting
 * the classifier's own conservative DISRUPTION fallback take over is safer than guessing that an
 * ambiguous continuation is safe to protect.
 *
 * Deliberately checked with its own case-SENSITIVE regex, entirely separate from the
 * case-insensitive regex used to find the abbreviation's spelling itself ("Osv."/"OSV." must
 * still match the abbreviation the same as "osv."). An earlier version combined both checks into
 * one regex sharing a single `i` flag — which does not only make the abbreviation spelling
 * case-insensitive, it *also* makes the `[A-ZÅÄÖ]` half of the lookahead match lowercase letters
 * too, silently turning "not followed by an uppercase letter" into "not followed by any letter at
 * all" (true only when nothing at all follows). That bug meant every conditional abbreviation was
 * effectively always treated as sentence-ending, including genuine same-sentence, lowercase
 * continuations — see "a lowercase same-sentence continuation stays joined" in this file's test
 * suite for the exact case this silently broke.
 */
function continuesSameSentence(following: string): boolean {
  return /^\s+[a-zåäö]/.test(following);
}

function protectSpecialPeriods(text: string): string {
  let result = text;
  for (const abbreviation of ALWAYS_PROTECTED_ABBREVIATIONS) {
    result = result.replace(new RegExp(escapeRegExp(abbreviation), "gi"), (match) => match.split(".").join(PERIOD_SENTINEL));
  }
  for (const abbreviation of CONDITIONALLY_PROTECTED_ABBREVIATIONS) {
    const pattern = new RegExp(escapeRegExp(abbreviation), "gi");
    result = result.replace(pattern, (match, offset: number, whole: string) => {
      const following = whole.slice(offset + match.length);
      return continuesSameSentence(following) ? match.split(".").join(PERIOD_SENTINEL) : match;
    });
  }
  return result.replace(DOMAIN_LIKE_PATTERN, (match) => match.split(".").join(PERIOD_SENTINEL));
}

function restoreSpecialPeriods(text: string): string {
  return text.split(PERIOD_SENTINEL).join(".");
}

/** A line that itself opens with a bullet/list marker (•, *, an en/em dash, or a hyphen, each
 * followed by real content) is always its own hard boundary — SL formats an enumerated list of
 * genuinely separate items this way, and merging them the way an ordinary manually-wrapped
 * sentence is merged would recreate the same cross-item false-positive risk as unrelated
 * paragraphs. Requiring a following space (not just the marker) keeps this from ever matching an
 * ordinary word that happens to start with "-". */
const BULLET_LINE = /^[•*–—-]\s+\S/;

function isBulletLine(line: string): boolean {
  return BULLET_LINE.test(line.trim());
}

/**
 * Groups a paragraph's lines so that ordinary manual line-wrapping (SL routinely wraps one
 * sentence across lines, e.g. "...flyttas hållplats Råsta i\nriktning mot...") still folds back
 * into a single flowing line, a bullet line starts a new group of its own, and — the detail that
 * matters here — a *wrapped continuation of that bullet item* (an ordinary line immediately
 * following it, with no bullet marker of its own) joins the bullet line it continues rather than
 * becoming its own disconnected unit. Getting this wrong either way breaks real content: folding
 * every line together would merge separate bullet items exactly like the paragraph-level bug this
 * file already fixes once; cutting a wrapped continuation loose from its own bullet item would
 * split "- Hissen vid Slussen\n  är avstängd" (one logical item, hiss + avstängd together) into
 * two unrelated-looking pieces and silently lose the match.
 */
function splitParagraphIntoLineGroups(paragraph: string): string[] {
  const groups: string[] = [];
  let current: string[] = [];
  for (const line of paragraph.split("\n")) {
    if (isBulletLine(line)) {
      if (current.length > 0) groups.push(current.join(" "));
      current = [line.trim()];
    } else {
      current.push(line);
    }
  }
  if (current.length > 0) groups.push(current.join(" "));
  return groups.filter((g) => g.length > 0);
}

/**
 * Splits one line group into sentences on `.`/`!`/`?` followed by whitespace — deliberately with
 * no requirement on what follows next, so a genuine new sentence starting with a digit ("3
 * augusti..."), an opening quote, or an opening parenthesis is still correctly split. Only
 * [protectSpecialPeriods]'s fixed abbreviation/domain list is exempted; everything else with a
 * trailing period followed by whitespace is treated as a real sentence end.
 */
function splitIntoSentences(lineGroup: string): string[] {
  return protectSpecialPeriods(lineGroup)
    .split(/(?<=[.!?])\s+/)
    .map((s) => restoreSpecialPeriods(s).trim())
    .filter((s) => s.length > 0);
}

/**
 * Splits one sentence on a deliberately narrow set of clause boundaries — a semicolon, or a
 * comma directly followed by "men"/"medan" ("but"/"while"), the two ordinary ways Swedish joins
 * two contrasting clauses in a single sentence ("Entrén är öppen, men Blå linjen är avstängd.").
 * Never splits on a bare comma or "och" ("and") — real SL text routinely uses appositive commas,
 * stop lists, and "mellan X och Y" constructions inside one legitimate, single-subject clause
 * (e.g. "hissen mellan biljetthallen och gatuplan är avstängd"), which a blind comma split would
 * fragment into meaningless, falsely-negative pieces.
 */
function splitIntoClauses(sentence: string): string[] {
  return sentence
    .split(/;|,\s+(?:men|medan)\s+/i)
    .map((c) => c.trim())
    .filter((c) => c.length > 0);
}

/** Produces the normalized units the three compound rules test against — paragraph, then line
 * group (bullet-aware), then sentence, then narrow clause, each independently
 * lowercased/whitespace-collapsed. Exported so `domain/journeySegmentParser.ts` can reuse the
 * exact same sentence/clause-local scoping for its own "mellan A och B" segment parsing, rather
 * than duplicating a second copy of this paragraph/line-group/sentence/clause splitter. */
export function segmentIntoUnits(rawText: string): string[] {
  const units: string[] = [];
  for (const paragraph of splitIntoParagraphs(rawText)) {
    for (const lineGroup of splitParagraphIntoLineGroups(paragraph)) {
      for (const sentence of splitIntoSentences(lineGroup)) {
        for (const clause of splitIntoClauses(sentence)) {
          units.push(normalize(clause));
        }
      }
    }
  }
  return units;
}

// ---- Local negation guard ----
//
// Scoping to a unit is not enough on its own: "Entrén är inte stängd." is one short clause, and
// without this guard it would still satisfy entré + isClosedWording. Applied only to the closure/
// movement words below — never to "fungerar inte"/"stannar inte vid"/"angör inte", where "inte" is
// itself part of the disruption signal, not a negation of it.

const NEGATION_WORDS = ["inte", "ej", "icke"];
/** ~3-4 Swedish words each side — enough to cover both "är inte stängd" (negation before) and
 * "stängs inte" (negation after) without reaching into an unrelated neighboring clause. */
const NEGATION_WINDOW_CHARS = 24;

function isLocallyNegated(text: string, matchIndex: number, matchLength: number): boolean {
  const before = text.slice(Math.max(0, matchIndex - NEGATION_WINDOW_CHARS), matchIndex);
  const after = text.slice(matchIndex + matchLength, matchIndex + matchLength + NEGATION_WINDOW_CHARS);
  const window = `${before} ${after}`;
  return NEGATION_WORDS.some((word) => new RegExp(`(?<![${SV_LETTER}])${word}(?![${SV_LETTER}])`).test(window));
}

/** True if [pattern] (a global-flagged regex) matches [text] at least once at a position that is
 * not locally negated — a unit containing both a negated and an affirmed occurrence (rare, but
 * possible: "Den första hissen är inte avstängd, men den andra är avstängd.") still counts. */
function hasAffirmedMatch(text: string, pattern: RegExp): boolean {
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    if (!isLocallyNegated(text, match.index, match[0].length)) return true;
  }
  return false;
}

function wordPattern(stem: string): RegExp {
  return new RegExp(`(?<![${SV_LETTER}])${escapeRegExp(stem)}`, "g");
}

function suffixPattern(suffix: string): RegExp {
  return new RegExp(`[${SV_LETTER}]*${escapeRegExp(suffix)}(?![${SV_LETTER}])`, "g");
}

/**
 * Every supported inflected/conjugated form of "stänga" ("to close") that affirmatively signals a
 * closure: adjective/participle agreement (`stängd`/`stängt`/`stängda` — common/neuter/plural
 * gender, e.g. "hissen är stängd" vs "hissarna är stängda") and present passive (`stängs`). An
 * explicit, closed set of grammatical forms — never a bare "stäng" stem wildcard, which would also
 * match unrelated words that merely share the stem (see this file's own test suite, "does not
 * match an unrelated stäng- word").
 *
 * Deliberately does NOT include past/perfect passive (`stängdes`/`stängts`): unlike the four forms
 * above, no live SL wording has ever demonstrated a need for them, and omitting them keeps this
 * set to exactly the forms actual evidence supports — see this file's own top-level doc on
 * precision over keyword recall.
 *
 * Each form is matched as a word-final suffix (see [suffixPattern]), so `"avstängd"`,
 * `"avstängt"`, `"avstängda"`, and `"avstängs"` all match for free without a separate
 * "av"-prefixed entry — the same mechanism the original two-entry `"stängd"`/`"stängs"` pair
 * already relied on, just applied to the full adjective-agreement family instead of only the
 * common-gender singular. That incomplete pair missed plural/neuter adjective agreement entirely:
 * a live SL disruption's real header, "Avstängda hissar vid Mariatorget" (deviation 12285394,
 * observed 2026-08-16), fell through to the generic DISRUPTION fallback instead of
 * ACCESSIBILITY_ISSUE, because "avstängda" is not "avstängd" at a word boundary. See this file's
 * own test suite for the full morphology matrix this was verified against.
 */
const CLOSED_WORD_FORMS = ["stängd", "stängt", "stängda", "stängs"];

/** True if any [CLOSED_WORD_FORMS] member occurs as an affirmed (non-negated), word-final match —
 * reused by both ACCESSIBILITY_ISSUE and STATION_ACCESS, which differ only in *what* the closure
 * applies to. */
function isAffirmedClosedWording(text: string): boolean {
  return CLOSED_WORD_FORMS.some((form) => hasAffirmedMatch(text, suffixPattern(form)));
}

/** The STOP_CHANGE moved/withdrawn wordlist, negation-guarded the same way. */
function hasAffirmedMovedWording(text: string): boolean {
  return (
    hasAffirmedMatch(text, wordPattern("flyttad")) ||
    hasAffirmedMatch(text, wordPattern("flyttat")) ||
    hasAffirmedMatch(text, wordPattern("indragen")) ||
    hasAffirmedMatch(text, wordPattern("indragna"))
  );
}

/** True if some unit satisfies [test] on its own — the shape every compound rule below uses. */
function compoundInAnyUnit(units: readonly string[], test: (unit: string) => boolean): boolean {
  return units.some(test);
}

type MatchContext = {
  /** The whole message text, normalized as one string — what the simple, non-compound rules
   * match against, unchanged from before this file's scope-aware rewrite. */
  whole: string;
  /** Paragraph/line-group/sentence/clause-scoped units — what the three compound rules match
   * against. */
  units: readonly string[];
};

/**
 * Precedence-ordered rules — deliberately a list, not nested if/else, so the order itself is the
 * one documented precedence (see docs/api-contract.md and this file's own test suite, "precedence"
 * describe block). Earlier entries win when a text matches more than one; see each rule's inline
 * comment for the source wording it implements (docs/api-contract.md §3, "Disruption effect
 * classification").
 */
const RULES: ReadonlyArray<{ effect: Exclude<DisruptionEffect, "DISRUPTION">; test: (ctx: MatchContext) => boolean }> = [
  {
    // Whole-service suspension. "ingen trafik" and the exact phrase "inställd trafik" only —
    // never bare "inställd" — "En avgång är inställd" (ONE cancelled departure) must never
    // become NO_SERVICE.
    effect: "NO_SERVICE",
    test: ({ whole }) => hasPhrase(whole, "ingen trafik") || hasPhrase(whole, "inställd trafik"),
  },
  {
    effect: "REPLACEMENT_SERVICE",
    test: ({ whole }) => startsWord(whole, "ersättningsbuss") || startsWord(whole, "ersättningstrafik"),
  },
  {
    // "En avgång är inställd" intentionally does not reach this rule either (no wording here
    // means "only some/a few" the way "glesare"/"reducerad"/"färre" unambiguously do) — it falls
    // all the way through to the generic DISRUPTION fallback, never confidently guessed at.
    effect: "REDUCED_SERVICE",
    test: ({ whole }) => hasPhrase(whole, "glesare trafik") || hasPhrase(whole, "reducerad trafik") || hasPhrase(whole, "färre avgångar"),
  },
  {
    effect: "ROUTE_CHANGE",
    test: ({ whole }) =>
      startsWord(whole, "omled") || startsWord(whole, "omlag") || hasPhrase(whole, "annan körväg") || hasPhrase(whole, "ändrad körväg"),
  },
  {
    // "hållplats" + an affirmed moved/withdrawn word in the SAME unit is the strong, specific
    // combined signal; the two standalone phrases are strong on their own regardless of scope.
    effect: "STOP_CHANGE",
    test: ({ whole, units }) =>
      hasPhrase(whole, "stannar inte vid") ||
      hasPhrase(whole, "angör inte") ||
      compoundInAnyUnit(units, (unit) => startsWord(unit, "hållplats") && hasAffirmedMovedWording(unit)),
  },
  {
    // Accessibility requires an actual, affirmed problem in the same unit as "hiss"/"rulltrappa"
    // — not merely the word existing somewhere in the message. "tillgänglighetsproblem" is the
    // one standalone exception, since it already names the problem outright.
    effect: "ACCESSIBILITY_ISSUE",
    test: ({ whole, units }) =>
      startsWord(whole, "tillgänglighetsproblem") ||
      compoundInAnyUnit(
        units,
        (unit) =>
          (startsWord(unit, "hiss") || startsWord(unit, "rulltrapp")) &&
          (hasPhrase(unit, "ur funktion") || hasPhrase(unit, "fungerar inte") || isAffirmedClosedWording(unit)),
      ),
  },
  {
    effect: "STATION_ACCESS",
    test: ({ units }) =>
      compoundInAnyUnit(
        units,
        (unit) => (startsWord(unit, "entré") || startsWord(unit, "ingång") || startsWord(unit, "utgång")) && isAffirmedClosedWording(unit),
      ),
  },
  {
    effect: "DELAYS",
    test: ({ whole }) => startsWord(whole, "försen"),
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
  const ctx: MatchContext = { whole: normalize(rawText), units: segmentIntoUnits(rawText) };
  for (const rule of RULES) {
    if (rule.test(ctx)) return rule.effect;
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
