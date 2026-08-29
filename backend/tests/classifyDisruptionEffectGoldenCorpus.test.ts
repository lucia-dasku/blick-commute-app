import { describe, expect, it } from "vitest";
import type { DisruptionMessage } from "../src/models/disruption.js";
import {
  DISRUPTION_CLASSIFIER_VERSION,
  classifyDisruptionEffectWithDiagnostics,
} from "../src/normalize/classifyDisruptionEffect.js";
import { disruptionClassifierGoldenCorpus } from "./fixtures/disruptionClassifierGoldenCorpus.js";

describe("disruption classifier golden corpus", () => {
  for (const golden of disruptionClassifierGoldenCorpus) {
    it(golden.name, () => {
      const message: DisruptionMessage = {
        header: golden.header,
        details: golden.details,
        scopeAlias: null,
        webLink: null,
        language: "sv",
      };

      const result = classifyDisruptionEffectWithDiagnostics(message);

      expect(result.effect).toBe(golden.expectedEffect);
      expect(result.matchedRule).toBe(golden.expectedRule);
      expect(result.matchedTextSource).toBe(golden.expectedSource);
      expect(result.classifierVersion).toBe(DISRUPTION_CLASSIFIER_VERSION);
      expect(result.classifierVersion).toBe(1);
    });
  }
});
