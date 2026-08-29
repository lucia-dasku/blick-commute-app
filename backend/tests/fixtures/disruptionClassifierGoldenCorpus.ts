import type { DisruptionEffect } from "../../src/models/disruption.js";
import type { DisruptionClassifierRule } from "../../src/normalize/classifyDisruptionEffect.js";

export interface DisruptionClassifierGoldenCase {
  name: string;
  header: string;
  details: string;
  expectedEffect: DisruptionEffect;
  expectedRule: DisruptionClassifierRule;
  expectedSource: "HEADER" | "DETAILS" | "NONE";
  explanation?: string;
}

/** Reviewed Swedish messages already observed or represented by Blick's existing regression
 * fixtures. Add a real failing phrase here before changing classifier behavior. */
export const disruptionClassifierGoldenCorpus: readonly DisruptionClassifierGoldenCase[] = [
  {
    name: "live delayed departure header",
    header: "L401 försenat avgång med 5 minuter",
    details: "L401 avgång är försenad.",
    expectedEffect: "DELAYS",
    expectedRule: "DELAYS",
    expectedSource: "HEADER",
  },
  {
    name: "Blue line service suspension",
    header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
    details: "Resenärer hänvisas till alternativa resvägar.",
    expectedEffect: "NO_SERVICE",
    expectedRule: "NO_SERVICE",
    expectedSource: "HEADER",
  },
  {
    name: "reduced service",
    header: "Glesare trafik på linje 14",
    details: "",
    expectedEffect: "REDUCED_SERVICE",
    expectedRule: "REDUCED_SERVICE",
    expectedSource: "HEADER",
  },
  {
    name: "rerouted bus",
    header: "Bussarna kör annan körväg via Slussen",
    details: "",
    expectedEffect: "ROUTE_CHANGE",
    expectedRule: "ROUTE_CHANGE",
    expectedSource: "HEADER",
  },
  {
    name: "moved stop",
    header: "Hållplatsen är tillfälligt flyttad",
    details: "",
    expectedEffect: "STOP_CHANGE",
    expectedRule: "STOP_CHANGE",
    expectedSource: "HEADER",
  },
  {
    name: "replacement buses",
    header: "Ersättningsbussar kör mellan Gullmarsplan och Skarpnäck",
    details: "",
    expectedEffect: "REPLACEMENT_SERVICE",
    expectedRule: "REPLACEMENT_SERVICE",
    expectedSource: "HEADER",
  },
  {
    name: "closed station entrance",
    header: "Entrén vid Södermalmstorg är avstängd",
    details: "",
    expectedEffect: "STATION_ACCESS",
    expectedRule: "STATION_ACCESS",
    expectedSource: "HEADER",
  },
  {
    name: "live Mariatorget lift wording",
    header: "Avstängda hissar vid Mariatorget",
    details: "Båda hissarna mellan biljetthallen och plattformen är avstängda.",
    expectedEffect: "ACCESSIBILITY_ISSUE",
    expectedRule: "ACCESSIBILITY_ISSUE",
    expectedSource: "HEADER",
  },
  {
    name: "specific effect found only in details",
    header: "Trafikinformation för linje 17",
    details: "Glesare trafik på grund av personalbrist.",
    expectedEffect: "REDUCED_SERVICE",
    expectedRule: "REDUCED_SERVICE",
    expectedSource: "DETAILS",
  },
  {
    name: "ambiguous generic notice",
    header: "Trafikinformation",
    details: "Se vår webbplats för mer information.",
    expectedEffect: "DISRUPTION",
    expectedRule: "GENERIC_FALLBACK",
    expectedSource: "NONE",
    explanation: "No passenger effect is stated confidently.",
  },
  {
    name: "one cancelled departure is not whole-service suspension",
    header: "En avgång är inställd",
    details: "Nästa avgång går enligt tidtabell.",
    expectedEffect: "DISRUPTION",
    expectedRule: "GENERIC_FALLBACK",
    expectedSource: "NONE",
    explanation: "A single cancelled departure must not become NO_SERVICE or REDUCED_SERVICE.",
  },
];
