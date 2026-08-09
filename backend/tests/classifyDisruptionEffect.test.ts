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
