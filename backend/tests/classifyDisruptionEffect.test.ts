import { describe, expect, it } from "vitest";
import { classifyDisruptionEffect, classifyEffectFromText } from "../src/normalize/classifyDisruptionEffect.js";
import type { DisruptionMessage } from "../src/models/disruption.js";
import slDeviationsFixture from "../fixtures/slDeviationsSlussen.sample.json" with { type: "json" };

function sv(header: string, details = ""): DisruptionMessage {
  return { header, details, scopeAlias: null, webLink: null, language: "sv" };
}

describe("classifyEffectFromText: one effect per rule", () => {
  it('"Förseningar på linje 14" -> DELAYS', () => {
    expect(classifyEffectFromText("Förseningar på linje 14")).toBe("DELAYS");
  });

  it('"L401 försenat avgång med 5 minuter" -> DELAYS', () => {
    expect(classifyEffectFromText("L401 försenat avgång med 5 minuter")).toBe("DELAYS");
  });

  it('"Ingen trafik mellan X och Y" -> NO_SERVICE', () => {
    expect(classifyEffectFromText("Ingen trafik mellan X och Y")).toBe("NO_SERVICE");
  });

  it('"Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården" -> NO_SERVICE (exact phrase, real live SL header)', () => {
    expect(classifyEffectFromText("Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården")).toBe("NO_SERVICE");
  });

  it('"Ersättningsbussar kör mellan X och Y" -> REPLACEMENT_SERVICE', () => {
    expect(classifyEffectFromText("Ersättningsbussar kör mellan X och Y")).toBe("REPLACEMENT_SERVICE");
  });

  it('"Ersättningstrafik gäller tills vidare" -> REPLACEMENT_SERVICE', () => {
    expect(classifyEffectFromText("Ersättningstrafik gäller tills vidare")).toBe("REPLACEMENT_SERVICE");
  });

  it('"Glesare trafik" -> REDUCED_SERVICE', () => {
    expect(classifyEffectFromText("Glesare trafik")).toBe("REDUCED_SERVICE");
  });

  it('"Reducerad trafik på grund av personalbrist" -> REDUCED_SERVICE', () => {
    expect(classifyEffectFromText("Reducerad trafik på grund av personalbrist")).toBe("REDUCED_SERVICE");
  });

  it('"Färre avgångar" -> REDUCED_SERVICE', () => {
    expect(classifyEffectFromText("Färre avgångar")).toBe("REDUCED_SERVICE");
  });

  it('"Bussarna kör annan körväg" -> ROUTE_CHANGE', () => {
    expect(classifyEffectFromText("Bussarna kör annan körväg")).toBe("ROUTE_CHANGE");
  });

  it('"Bussar 401 är omledda" -> ROUTE_CHANGE', () => {
    expect(classifyEffectFromText("Bussar 401 är omledda")).toBe("ROUTE_CHANGE");
  });

  it('"Trafiken är omlagd" -> ROUTE_CHANGE', () => {
    expect(classifyEffectFromText("Trafiken är omlagd")).toBe("ROUTE_CHANGE");
  });

  it('"Linjen har fått ändrad körväg" -> ROUTE_CHANGE', () => {
    expect(classifyEffectFromText("Linjen har fått ändrad körväg")).toBe("ROUTE_CHANGE");
  });

  it('"Hållplatsen är tillfälligt flyttad" -> STOP_CHANGE', () => {
    expect(classifyEffectFromText("Hållplatsen är tillfälligt flyttad")).toBe("STOP_CHANGE");
  });

  it('"Bussen stannar inte vid Slussen" -> STOP_CHANGE', () => {
    expect(classifyEffectFromText("Bussen stannar inte vid Slussen")).toBe("STOP_CHANGE");
  });

  it('"Tåget angör inte Södertälje" -> STOP_CHANGE', () => {
    expect(classifyEffectFromText("Tåget angör inte Södertälje")).toBe("STOP_CHANGE");
  });

  it('"Hållplatsen är indragen tills vidare" -> STOP_CHANGE', () => {
    expect(classifyEffectFromText("Hållplatsen är indragen tills vidare")).toBe("STOP_CHANGE");
  });

  it('"Hissen är ur funktion" -> ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissen är ur funktion")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"Rulltrappan är avstängd" -> ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Rulltrappan är avstängd")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"Hissen fungerar inte" -> ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissen fungerar inte")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"Tillgänglighetsproblem vid Fruängen" -> ACCESSIBILITY_ISSUE (standalone, no hiss/rulltrappa needed)', () => {
    expect(classifyEffectFromText("Tillgänglighetsproblem vid Fruängen")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"3 augusti stängs en utgång vid Slussen" -> STATION_ACCESS', () => {
    expect(classifyEffectFromText("3 augusti stängs en utgång vid Slussen")).toBe("STATION_ACCESS");
  });

  it('"Entrén vid Södermalmstorg är avstängd" -> STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entrén vid Södermalmstorg är avstängd")).toBe("STATION_ACCESS");
  });

  it('"Ingången är stängd" -> STATION_ACCESS', () => {
    expect(classifyEffectFromText("Ingången är stängd")).toBe("STATION_ACCESS");
  });
});

describe("classifyEffectFromText: must NOT confidently misclassify", () => {
  it('"En avgång är inställd" does not become NO_SERVICE', () => {
    expect(classifyEffectFromText("En avgång är inställd")).not.toBe("NO_SERVICE");
  });

  it('"En avgång är inställd" falls back to null (no rule matches) rather than a guessed effect', () => {
    // "inställd" alone is not in any rule's wordlist -- see classifyDisruptionEffect.ts's own
    // doc on why this is deliberately absent from both NO_SERVICE and REDUCED_SERVICE.
    expect(classifyEffectFromText("En avgång är inställd")).toBeNull();
  });

  it("bare \"hiss\" with no problem wording does not become ACCESSIBILITY_ISSUE", () => {
    expect(classifyEffectFromText("Hissen vid uppgången är ny")).toBeNull();
  });

  it("bare \"rulltrappa\" with no problem wording does not become ACCESSIBILITY_ISSUE", () => {
    expect(classifyEffectFromText("Rulltrappan har målats om")).toBeNull();
  });

  it('"flyttad" without any mention of "hållplats" does not become STOP_CHANGE', () => {
    expect(classifyEffectFromText("Kontoret har flyttad öppettider")).toBeNull();
  });

  it('"utgång" with no closure wording does not become STATION_ACCESS', () => {
    expect(classifyEffectFromText("Ny utgång öppnad vid Slussen")).toBeNull();
  });

  it("generic/ambiguous text falls back to DISRUPTION at the classifyDisruptionEffect level", () => {
    expect(classifyDisruptionEffect(sv("Trafikinformation", "Se länk för mer information."))).toBe("DISRUPTION");
  });
});

describe("classifyDisruptionEffect: language gate", () => {
  it("a non-Swedish message is never classified, even if the English text would otherwise match a rule", () => {
    const englishDelay: DisruptionMessage = {
      header: "Försenat", // deliberately Swedish wording, but tagged as English
      details: "",
      scopeAlias: null,
      webLink: null,
      language: "en",
    };
    expect(classifyDisruptionEffect(englishDelay)).toBe("DISRUPTION");
  });

  it("a Finnish message is never classified", () => {
    const finnish: DisruptionMessage = { header: "Myöhässä", details: "", scopeAlias: null, webLink: null, language: "fi" };
    expect(classifyDisruptionEffect(finnish)).toBe("DISRUPTION");
  });

  it("a Swedish message is classified normally", () => {
    expect(classifyDisruptionEffect(sv("Förseningar på linje 14"))).toBe("DELAYS");
  });
});

describe("classifyDisruptionEffect: header before details", () => {
  it("a specific header classification wins over a different classification found only in the details", () => {
    // The real fixture case this rule exists for: the header alone says DELAYS; the details
    // separately mention a bridge opening (an unrelated cause), which must never steal the
    // classification away from the header's own, more specific wording.
    const message = sv("L401 försenat avgång med 5 minuter", "L401 avgång från Ekstubben är 5 minuter försenad p g a Bro öppning.");
    expect(classifyDisruptionEffect(message)).toBe("DELAYS");
  });

  it("header REPLACEMENT_SERVICE wins even though the details alone would say NO_SERVICE", () => {
    const message = sv("Ersättningsbussar sätts in", "Ingen trafik med tunnelbana under tiden.");
    expect(classifyDisruptionEffect(message)).toBe("REPLACEMENT_SERVICE");
  });

  it("falls through to the details when the header alone matches no specific rule", () => {
    const message = sv("Trafikinformation för linje 17", "Glesare trafik på grund av personalbrist.");
    expect(classifyDisruptionEffect(message)).toBe("REDUCED_SERVICE");
  });

  it("falls all the way to DISRUPTION when neither header nor details match anything specific", () => {
    const message = sv("Trafikinformation", "Se vår webbplats för mer information.");
    expect(classifyDisruptionEffect(message)).toBe("DISRUPTION");
  });

  it("does not concatenate header and details into one search -- a match that only exists across the boundary between them is not found", () => {
    // "ingen" ends the header; "trafik" starts the details. Concatenating them (with or without
    // a separator that happens to look like whitespace) must not accidentally assemble "ingen
    // trafik" out of two unrelated fragments.
    const message = sv("Se ingen", "trafik just nu, allt som vanligt.");
    expect(classifyDisruptionEffect(message)).toBe("DISRUPTION");
  });
});

describe("classifyEffectFromText: precedence when multiple effects could match", () => {
  it('"Ingen trafik mellan X och Y. Ersättningsbussar kör." -> NO_SERVICE (primary impact: normal service is not running)', () => {
    expect(classifyEffectFromText("Ingen trafik mellan X och Y. Ersättningsbussar kör.")).toBe("NO_SERVICE");
  });

  it("NO_SERVICE wins over DELAYS when both are present", () => {
    expect(classifyEffectFromText("Ingen trafik just nu, tidigare förseningar orsakade detta.")).toBe("NO_SERVICE");
  });

  it("REPLACEMENT_SERVICE wins over ROUTE_CHANGE when both are present", () => {
    expect(classifyEffectFromText("Ersättningsbussar kör omledd väg förbi arbetsområdet.")).toBe("REPLACEMENT_SERVICE");
  });

  it("ACCESSIBILITY_ISSUE wins over STATION_ACCESS when both subjects and a shared closure word appear together", () => {
    // "avstängd" alone would satisfy BOTH rules here: hiss + isClosedWording (ACCESSIBILITY_ISSUE)
    // and utgång + isClosedWording (STATION_ACCESS). Precedence order (docs/api-contract.md)
    // puts ACCESSIBILITY_ISSUE first.
    expect(classifyEffectFromText("Hissen vid utgången är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it("DELAYS is checked last among the specific effects, after ROUTE_CHANGE", () => {
    expect(classifyEffectFromText("Bussarna kör annan körväg vilket orsakar förseningar.")).toBe("ROUTE_CHANGE");
  });
});

describe("classifyEffectFromText: compound rules do not combine unrelated sentences", () => {
  // The real bug this section exists for: a live SL disruption's details read (in one sentence)
  // "...entrén ... är öppen." and, three paragraphs later about a completely different line,
  // "...Gröna linjen är avstängd...". Searching the whole flattened text let "entré" from the
  // first sentence pair with "avstängd" from the second and produce STATION_ACCESS for a message
  // that was not about station access. See "real SL fixture" below for that exact real text.

  it('"Entrén är öppen. Blå linjen är avstängd." does not become STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entrén är öppen. Blå linjen är avstängd.")).toBeNull();
  });

  it('"Hissen fungerar. Trafiken är avstängd." does not become ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissen fungerar. Trafiken är avstängd.")).toBeNull();
  });

  it('"Hållplatsen trafikeras som vanligt. Informationsskylten är flyttad." does not become STOP_CHANGE', () => {
    expect(classifyEffectFromText("Hållplatsen trafikeras som vanligt. Informationsskylten är flyttad.")).toBeNull();
  });

  it('a comma-joined contrast clause ("...,  men...") does not combine either', () => {
    expect(classifyEffectFromText("Entrén är öppen, men Blå linjen är avstängd.")).toBeNull();
  });

  it('a semicolon-joined clause does not combine either', () => {
    expect(classifyEffectFromText("Hissen är i drift; trafiken är avstängd.")).toBeNull();
  });

  it('a comma-joined "medan" clause does not combine either', () => {
    expect(classifyEffectFromText("Hållplatsen trafikeras som vanligt, medan informationsskylten är flyttad.")).toBeNull();
  });

  it("a blind comma split would have broken this legitimate single-clause appositive -- it must still classify correctly", () => {
    expect(classifyEffectFromText("Hissen mellan biljetthallen och gatuplan är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });
});

describe("classifyEffectFromText: a sentence boundary is found even when the next sentence does not start with an uppercase letter", () => {
  // An earlier version of the sentence splitter required an uppercase letter right after the
  // punctuation+whitespace to count as a boundary, specifically to avoid false splits at
  // abbreviations like "kl. 06.00". That heuristic has the same bug in the opposite direction: a
  // genuine new sentence starting with a digit, an opening quote, an opening parenthesis, or a
  // bullet marker then silently fails to split at all -- recreating the exact cross-sentence
  // merge this file exists to prevent, just triggered a different way. Reproduced for real with
  // "...entrén ... öppen. 3 augusti stängs..." -- covered here for all three affected effects.

  it('a digit-started second sentence ("3 augusti...") does not merge into STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entrén är öppen. 3 augusti stängs biljetthallen.")).toBeNull();
  });

  it('a digit-started second sentence ("3 augusti...") does not merge into ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissen fungerar bra. 3 augusti stängs biljetthallen.")).toBeNull();
  });

  it('a digit-started second sentence ("3 augusti...") does not merge into STOP_CHANGE', () => {
    expect(classifyEffectFromText("Hållplatsen trafikeras normalt. 3 augusti är biljettautomaten flyttad.")).toBeNull();
  });

  it("a quote-started second sentence does not merge into STATION_ACCESS", () => {
    expect(classifyEffectFromText('Entrén är öppen. "Trafiken är avstängd" enligt SL.')).toBeNull();
  });

  it("a parenthesis-started second sentence does not merge into STATION_ACCESS", () => {
    expect(classifyEffectFromText("Entrén är öppen. (Trafiken är avstängd på grund av arbete.)")).toBeNull();
  });

  it("a bullet-started second line does not merge into STATION_ACCESS, even with no terminating punctuation", () => {
    expect(classifyEffectFromText("- Entrén är öppen\n- Trafiken är avstängd")).toBeNull();
  });

  it("a bullet marker does not interfere with matching within its own line", () => {
    expect(classifyEffectFromText("- Hissen vid entrén är avstängd")).toBe("ACCESSIBILITY_ISSUE");
  });

  it("an ordinary (non-bulleted) manually-wrapped line still folds back together, unaffected by bullet detection", () => {
    expect(classifyEffectFromText("Hållplatsen är tillfälligt\nflyttad")).toBe("STOP_CHANGE");
  });
});

describe("classifyEffectFromText: abbreviations that can legitimately end a sentence", () => {
  // Unlike "kl."/"t.o.m." (grammatical connectors that always have a same-sentence complement
  // following them), "osv."/"dvs."/"m.fl." commonly end a list or an aside on their own, with a
  // genuinely new, unrelated sentence following. Blanket-protecting their period the way the
  // connectors are protected would silently recreate the exact cross-sentence merge this file's
  // scope-aware matching exists to prevent -- these must still split.

  it('"osv." followed by a new sentence still splits (does not merge into STATION_ACCESS)', () => {
    expect(classifyEffectFromText("Entrén är öppen, biljetter osv. Trafiken är avstängd.")).toBeNull();
  });

  it('"dvs." followed by a new sentence still splits (does not merge into STATION_ACCESS)', () => {
    expect(classifyEffectFromText("Entrén är öppen, dvs. Trafiken är avstängd.")).toBeNull();
  });

  it('"m.fl." followed by a new sentence still splits (does not merge into STOP_CHANGE)', () => {
    expect(classifyEffectFromText("Hållplatsen trafikeras normalt, skyltar m.fl. Informationsskylten är flyttad.")).toBeNull();
  });

  it('"osv." followed by a digit still splits, not merges (the ambiguous case defaults to splitting, not to protecting)', () => {
    expect(classifyEffectFromText("Entrén är öppen, biljetter osv. 3 augusti stängs biljetthallen.")).toBeNull();
  });

  it('"osv." followed by an opening quote still splits', () => {
    expect(classifyEffectFromText('Entrén är öppen, biljetter osv. "Trafiken är avstängd" enligt SL.')).toBeNull();
  });

  it('"osv." followed by an opening parenthesis still splits', () => {
    expect(classifyEffectFromText("Entrén är öppen, biljetter osv. (Trafiken är avstängd på grund av arbete.)")).toBeNull();
  });

  it('sentence-final "m.m." followed by a new, uppercase-started sentence still splits (does not merge into ACCESSIBILITY_ISSUE)', () => {
    expect(classifyEffectFromText("Entrén är öppen, skyltar m.m. Trafiken är avstängd.")).toBeNull();
  });

  it('sentence-final "m.m." followed by a date still splits', () => {
    expect(classifyEffectFromText("Entrén är öppen, skyltar m.m. 16 augusti stängs biljetthallen.")).toBeNull();
  });

  it("a lowercase same-sentence continuation after \"osv.\" stays joined -- the exact case an earlier, case-insensitive version of this check silently got wrong", () => {
    // Under the earlier bug, the "not followed by an uppercase letter" check was accidentally
    // matched case-insensitively too (both halves shared one regex's "i" flag), so it actually
    // meant "not followed by any letter at all" -- true only when nothing follows. Every
    // conditional abbreviation, including this genuine same-sentence use, was therefore always
    // treated as sentence-ending, which would have wrongly returned null here instead.
    expect(classifyEffectFromText("Hissen, biljetter osv. också skadade, är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('a lowercase same-sentence continuation after "m.m." also stays joined', () => {
    expect(classifyEffectFromText("Hissen, skyltar m.m. också trasiga, är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });
});

describe("classifyEffectFromText: compound rules still match within one unit", () => {
  it("a soft-wrapped single newline inside one sentence still matches (STOP_CHANGE)", () => {
    expect(classifyEffectFromText("Hållplatsen är tillfälligt\nflyttad")).toBe("STOP_CHANGE");
  });

  it('"kl." between the two compound halves is not mistaken for a sentence boundary -- a false split here would separate "hiss" from "avstängd" into different units and lose the match', () => {
    expect(classifyEffectFromText("Hissen är, från kl. 06.00, avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"t.o.m." between the two compound halves is not mistaken for a sentence boundary, for the same reason', () => {
    expect(classifyEffectFromText("Hissen, gäller t.o.m. 16 augusti, är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('a "sl.se" style URL between the two compound halves is not mistaken for a sentence boundary, for the same reason', () => {
    expect(classifyEffectFromText("Hissen, se sl.se för detaljer, är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"p.g.a." between the two compound halves is not mistaken for a sentence boundary', () => {
    expect(classifyEffectFromText("Hissen är, p.g.a. tekniskt fel, avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"m.m." between the two compound halves is not mistaken for a sentence boundary', () => {
    expect(classifyEffectFromText("Hissen, se biljetter m.m. här, är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it("a wrapped continuation line inside one bullet item stays joined to it, while the next bullet item stays isolated", () => {
    // Getting this wrong either way loses real content: folding every line together would merge
    // separate bullet items back into the paragraph-level false-positive this file already fixes
    // once; cutting the continuation loose from its own bullet item would split "hiss" from
    // "avstängd" into two disconnected units and silently lose the match instead.
    expect(classifyEffectFromText("- Hissen vid Slussen\n  är avstängd\n- Rulltrappan fungerar normalt")).toBe("ACCESSIBILITY_ISSUE");
  });

  it("the real Östermalmstorg sentence (appositive commas naming which entrance) still classifies correctly", () => {
    expect(classifyEffectFromText("En av hissarna vid Östermalmstorg, entrén mot Stureplan, är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });
});

describe("classifyEffectFromText: local negation guard", () => {
  it('"Entrén är inte stängd." does not become STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entrén är inte stängd.")).toBeNull();
  });

  it('"Entrén stängs inte." (negation after the verb) does not become STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entrén stängs inte.")).toBeNull();
  });

  it('"Hissen är inte avstängd." does not become ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissen är inte avstängd.")).toBeNull();
  });

  it('"Hållplatsen är inte flyttad." does not become STOP_CHANGE', () => {
    expect(classifyEffectFromText("Hållplatsen är inte flyttad.")).toBeNull();
  });

  it("a negated occurrence does not hide a genuinely affirmed one in the same unit", () => {
    expect(classifyEffectFromText("Den första hissen är inte avstängd, men den andra hissen är avstängd.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"fungerar inte" is unaffected by the negation guard -- "inte" is the disruption signal there, not a negation of it', () => {
    expect(classifyEffectFromText("Hissen fungerar inte")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"stannar inte vid" is unaffected by the negation guard', () => {
    expect(classifyEffectFromText("Bussen stannar inte vid Slussen")).toBe("STOP_CHANGE");
  });
});

describe("classifyEffectFromText: normalization", () => {
  it("matches regardless of uppercase/lowercase", () => {
    expect(classifyEffectFromText("FÖRSENINGAR PÅ LINJE 14")).toBe("DELAYS");
    expect(classifyEffectFromText("iNGEN tRAFIK mellan X och Y")).toBe("NO_SERVICE");
  });

  it("matches a phrase split across a newline", () => {
    expect(classifyEffectFromText("Ingen\ntrafik mellan X och Y")).toBe("NO_SERVICE");
  });

  it("matches through repeated/extra whitespace", () => {
    expect(classifyEffectFromText("Ingen     trafik   mellan X och Y")).toBe("NO_SERVICE");
  });

  it("matches with leading/trailing whitespace", () => {
    expect(classifyEffectFromText("   Förseningar på linje 14   \n")).toBe("DELAYS");
  });

  it("preserves and correctly matches Swedish å/ä/ö, including a word that starts with ä", () => {
    expect(classifyEffectFromText("Linjen har fått ändrad körväg")).toBe("ROUTE_CHANGE");
    expect(classifyEffectFromText("Hållplatsen är tillfälligt flyttad")).toBe("STOP_CHANGE");
  });
});

describe("classifyDisruptionEffect: real SL fixture", () => {
  const fixture = slDeviationsFixture as unknown as Array<{
    message_variants: Array<{ header: string; details: string; language: string; scope_alias?: string | null; weblink?: string | null }>;
  }>;

  it('the Slussen exit-closure disruption ("3 augusti stängs en utgång vid Slussen") classifies as STATION_ACCESS', () => {
    const raw = fixture[0]!.message_variants[0]!;
    const message = sv(raw.header, raw.details);
    expect(message.header).toBe("3 augusti stängs en utgång vid Slussen");
    expect(classifyDisruptionEffect(message)).toBe("STATION_ACCESS");
  });

  it('the L401 delay disruption ("L401 försenat avgång med 5 minuter") classifies as DELAYS despite its details mentioning a bridge opening', () => {
    const raw = fixture[1]!.message_variants[0]!;
    const message = sv(raw.header, raw.details);
    expect(message.header).toBe("L401 försenat avgång med 5 minuter");
    expect(message.details).toContain("Bro öppning");
    expect(classifyDisruptionEffect(message)).toBe("DELAYS");
  });

  // Real live disruption (id 11592474, fetched from SL Deviations directly) that exposed the
  // cross-sentence bug this file's scope-aware rewrite fixes. Testing only
  // classifyDisruptionEffect(message) is not enough on its own: once the header matches
  // NO_SERVICE via the new "inställd trafik" phrase, header-first short-circuit means the
  // details -- where the actual cross-sentence false positive lived -- would never be evaluated
  // for this fixture, so that single assertion could keep passing even if the details-scoping
  // fix regressed. Header and details are therefore asserted independently below.
  describe("the Blue-line closure disruption (id 11592474) -- the real cross-sentence false positive", () => {
    const header = "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården";
    const details =
      "Från och med måndag 22 juni till och med söndag 16 augusti är tågtrafiken på Blå linjen helt inställd mellan " +
      "T-Centralen och Kungsträdgården på grund av arbeten med tunnelbanans utbyggnad.\n\n" +
      "Tåg från Akalla och Hjulsta har T-Centralen som slutstation. Mellan T-Centralen och Kungsträdgården hänvisar vi " +
      "till alternativa resvägar såsom Spårväg City 7, busslinje 65 eller 69, eller en promenad på cirka 800 meter.\n\n" +
      "Vid T-Centralen avgår tågen från följande spår:\nLinje 10 mot Hjulsta avgår från spår 5.\n" +
      "Linje 11 mot Akalla avgår från spår 6.\n\n" +
      "Vid station Kungsträdgården är endast entrén mot Gallerian/Regeringsgatan öppen under arbetets gång.\n\n" +
      "Var uppmärksam på att Gröna linjen är avstängd mellan Odenplan och S:t Eriksplan och har förändrad trafik under " +
      "perioden måndag 6 juli till och med söndag 26 juli.\n\n" +
      "Sök din resa på sl.se eller i SL-appen för att hitta det bästa alternativet för din resa.";

    it("the header alone classifies as NO_SERVICE (the inställd trafik addition)", () => {
      expect(classifyEffectFromText(header)).toBe("NO_SERVICE");
    });

    it("the details alone -- entrance open in one sentence, an unrelated line closed three paragraphs later -- classify as null, not STATION_ACCESS (the scope-aware fix)", () => {
      expect(classifyEffectFromText(details)).toBeNull();
    });

    it("the full message classifies as NO_SERVICE end to end, from the header", () => {
      expect(classifyDisruptionEffect(sv(header, details))).toBe("NO_SERVICE");
    });

    it("with a deliberately generic header, the same details fall through to the safe DISRUPTION fallback -- proving the fix is the scoping, not just the new header phrase", () => {
      expect(classifyDisruptionEffect(sv("Trafikinformation", details))).toBe("DISRUPTION");
    });
  });
});

describe("classifyEffectFromText: the Mariatorget accessibility-classification regression (real live SL wording, observed 2026-08-16)", () => {
  // The real bug: the pre-fix closure matcher (isAffirmedClosedWording) only recognized "stängd"/
  // "stängs" as EXACT word-final suffixes, so ordinary adjective agreement ("stängda"/"stängt")
  // and other tense forms were invisible to it. This real live SL header/details pair (deviation
  // 12285394, fetched directly from SL Deviations on 2026-08-16, reproduced verbatim below -- not
  // invented) fell through to the generic DISRUPTION fallback instead of ACCESSIBILITY_ISSUE
  // purely because "avstängda" is not "avstängd" at a word boundary. See disruptionRelevance.test.ts's
  // "the Mariatorget accessibility-classification regression" describe block for the complementary
  // end-to-end proof that this classification change correctly propagates through
  // scopePolicyForEffect and resolveDeviationRelevance for a real Slussen -> Mälarhöjden journey.
  const mariatorgetHeader = "Avstängda hissar vid Mariatorget";
  const mariatorgetDetails =
    "Båda hissarna vid Mariatorget, entrén mot Mariatorget, är avstängda på grund av tekniskt fel. " +
    "Resenärer i behov av hiss hänvisas till den andra entrén, mot Polishuset.\n \n" +
    "Vi saknar prognos för när hissarna åter kan vara i drift.\n\n" +
    "Hissarna omfattas av tillgänglighetsgarantin. För mer information om tillgänglighet, kontakta SL på telefonnummer 020-120 20 22.";

  it('header only: "Avstängda hissar vid Mariatorget" -> ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText(mariatorgetHeader)).toBe("ACCESSIBILITY_ISSUE");
  });

  it("the full message (header + real details, via classifyDisruptionEffect) -> ACCESSIBILITY_ISSUE", () => {
    expect(classifyDisruptionEffect(sv(mariatorgetHeader, mariatorgetDetails))).toBe("ACCESSIBILITY_ISSUE");
  });

  it('the details\' own "Båda hissarna ... är avstängda" sentence, in isolation -> ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Båda hissarna vid Mariatorget, entrén mot Mariatorget, är avstängda på grund av tekniskt fel.")).toBe(
      "ACCESSIBILITY_ISSUE",
    );
  });

  it('the details\' own recovery sentence ("åter kan vara i drift") does NOT itself trigger a false STATION_ACCESS/ACCESSIBILITY_ISSUE match from "entré" elsewhere in a different sentence', () => {
    // "i drift" ("in service" -- the opposite of a problem) must never be misread as "ur drift";
    // this also guards the cross-sentence scoping around the two separate "entré" mentions.
    expect(classifyEffectFromText("Vi saknar prognos för när hissarna åter kan vara i drift.")).toBeNull();
  });
});

describe("classifyEffectFromText: Swedish closure-word morphology matrix", () => {
  // Compact table-driven coverage of every CLOSED_WORD_FORMS entry (see
  // classifyDisruptionEffect.ts's own doc) against both subject rules that share it.

  const accessibilityPositive: ReadonlyArray<[string, string]> = [
    ["singular adjective: avstängd", "Hissen är avstängd"],
    ["plural adjective: avstängda (the Mariatorget form, predicate position)", "Hissarna är avstängda"],
    ["plural adjective: avstängda, attributive before the subject (the real SL header word order)", "Avstängda hissar vid Mariatorget"],
    ["singular adjective, no av- prefix: stängd", "Rulltrappan är stängd"],
    ["plural adjective, no av- prefix: stängda", "Rulltrapporna är stängda"],
    ["neuter adjective: avstängt", "Hissområdet är avstängt på grund av ombyggnad."],
  ];
  for (const [label, text] of accessibilityPositive) {
    it(`${label} -> ACCESSIBILITY_ISSUE ("${text}")`, () => {
      expect(classifyEffectFromText(text)).toBe("ACCESSIBILITY_ISSUE");
    });
  }

  const stationAccessPositive: ReadonlyArray<[string, string]> = [
    ["singular adjective: stängd", "Entrén är stängd"],
    ["plural adjective: stängda", "Entréerna är stängda"],
    ["plural adjective: avstängda, attributive before the subject", "Avstängda utgångar vid stationen"],
    ["singular adjective: avstängd (ingång)", "Ingången är avstängd"],
    ["present passive: stängs (pre-existing, unchanged)", "3 augusti stängs en utgång vid Slussen"],
  ];
  for (const [label, text] of stationAccessPositive) {
    it(`${label} -> STATION_ACCESS ("${text}")`, () => {
      expect(classifyEffectFromText(text)).toBe("STATION_ACCESS");
    });
  }

  it('an unrelated word sharing only the "stäng-" stem (not any of the four supported grammatical forms) does not trigger a false positive', () => {
    // "stängningstider" (closing/operating HOURS -- a schedule notice, not a closure/malfunction)
    // is not "stängd"/"stängt"/"stängda"/"stängs" at a word boundary -- confirms the fix is an
    // explicit, closed grammatical set, never an uncontrolled stem prefix.
    expect(classifyEffectFromText("Information om hissarnas stängningstider inför helgen")).toBeNull();
  });

  it('past passive "stängdes" is deliberately NOT a closure trigger -- no live wording has demonstrated a need for it', () => {
    expect(classifyEffectFromText("Hissen stängdes på grund av tekniskt fel")).toBeNull();
  });

  it('past passive with av- prefix "avstängdes" is deliberately NOT a closure trigger, for the same reason', () => {
    expect(classifyEffectFromText("Hissen avstängdes på grund av tekniskt fel")).toBeNull();
  });

  it('perfect passive "stängts" is deliberately NOT a closure trigger, for the same reason', () => {
    expect(classifyEffectFromText("Entrén har stängts på grund av tekniskt fel")).toBeNull();
  });
});

describe("classifyEffectFromText: negative regressions for the new plural/neuter morphology", () => {
  it('"Hissarna är inte avstängda" -> not ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissarna är inte avstängda")).toBeNull();
  });

  it('"Rulltrapporna är inte avstängda" -> not ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Rulltrapporna är inte avstängda")).toBeNull();
  });

  it('"Entréerna är inte stängda" -> not STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entréerna är inte stängda")).toBeNull();
  });

  it("a negated plural occurrence does not hide a genuinely affirmed one in the same unit (plural counterpart of the existing singular regression)", () => {
    expect(classifyEffectFromText("De första hissarna är inte avstängda, men de andra hissarna är avstängda.")).toBe("ACCESSIBILITY_ISSUE");
  });

  it('"Hissarna fungerar normalt. Trafiken är avstängd." does not become ACCESSIBILITY_ISSUE (plural subject, cross-sentence)', () => {
    expect(classifyEffectFromText("Hissarna fungerar normalt. Trafiken är avstängd.")).toBeNull();
  });

  it('"Entréerna är öppna. Trafiken är avstängd." does not become STATION_ACCESS (plural subject, cross-sentence)', () => {
    expect(classifyEffectFromText("Entréerna är öppna. Trafiken är avstängd.")).toBeNull();
  });

  it('bare plural "Det finns hissar vid stationen" does not become ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Det finns hissar vid stationen")).toBeNull();
  });

  it('bare plural "Hissarna är nya" does not become ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Hissarna är nya")).toBeNull();
  });

  it('bare plural "Rulltrapporna fungerar normalt" does not become ACCESSIBILITY_ISSUE', () => {
    expect(classifyEffectFromText("Rulltrapporna fungerar normalt")).toBeNull();
  });

  it('bare plural "Entréerna är öppna" does not become STATION_ACCESS', () => {
    expect(classifyEffectFromText("Entréerna är öppna")).toBeNull();
  });

  it('"tekniskt fel" alone, with no accessibility/access subject at all, does not classify', () => {
    expect(classifyEffectFromText("Ett tekniskt fel har uppstått i biljettsystemet")).toBeNull();
  });

  it('"tekniskt fel" merely co-occurring with an accessibility subject, with no closure/problem wording actually said about it, does not classify', () => {
    expect(classifyEffectFromText("Stationen har ett tekniskt fel i biljettautomaten, hissen fungerar som vanligt.")).toBeNull();
  });
});

describe("classifyEffectFromText: every effect is reachable", () => {
  const cases: Record<string, string> = {
    DELAYS: "Förseningar på linje 14",
    NO_SERVICE: "Ingen trafik mellan X och Y",
    REDUCED_SERVICE: "Glesare trafik",
    ROUTE_CHANGE: "Bussarna kör annan körväg",
    STOP_CHANGE: "Hållplatsen är tillfälligt flyttad",
    REPLACEMENT_SERVICE: "Ersättningsbussar kör mellan X och Y",
    STATION_ACCESS: "3 augusti stängs en utgång vid Slussen",
    ACCESSIBILITY_ISSUE: "Hissen är ur funktion",
  };

  for (const [effect, text] of Object.entries(cases)) {
    it(`reaches ${effect}`, () => {
      expect(classifyEffectFromText(text)).toBe(effect);
    });
  }

  it("DISRUPTION is never returned by classifyEffectFromText itself (it returns null instead; DISRUPTION is only assembled by classifyDisruptionEffect)", () => {
    expect(classifyEffectFromText("Se vår webbplats för mer information.")).toBeNull();
  });
});
