import { describe, expect, it } from "vitest";
import { normalizeDisruption, selectMessageVariant } from "../src/normalize/normalizeDisruption.js";
import type { RawDeviation, RawMessageVariant } from "../src/services/upstreamTypes.js";
import slDeviationsFixture from "../fixtures/slDeviationsSlussen.sample.json" with { type: "json" };

const swedish: RawMessageVariant = { header: "SV header", details: "SV details", language: "sv" };
const english: RawMessageVariant = { header: "EN header", details: "EN details", language: "en" };
const finnish: RawMessageVariant = { header: "FI header", details: "FI details", language: "fi" };

describe("selectMessageVariant", () => {
  it("picks the Swedish variant when present, regardless of its position in the array", () => {
    expect(selectMessageVariant([english, swedish, finnish])).toBe(swedish);
    expect(selectMessageVariant([swedish, english])).toBe(swedish);
  });

  it("falls back to the first available variant only when no Swedish variant exists", () => {
    expect(selectMessageVariant([english, finnish])).toBe(english);
  });

  it("throws rather than silently returning undefined when there are no variants at all", () => {
    expect(() => selectMessageVariant([])).toThrow();
  });
});

describe("normalizeDisruption", () => {
  const raw = (slDeviationsFixture as unknown as RawDeviation[])[0]!;

  it("maps every field to the documented normalized shape", () => {
    const disruption = normalizeDisruption(raw);
    expect(disruption.disruptionId).toBe(String(raw.deviation_case_id));
    expect(disruption.message.header).toBe(raw.message_variants[0]!.header);
    expect(disruption.message.language).toBe("sv");
    expect(disruption.priority).toEqual({ importance: 2, influence: 3, urgency: 1 });
    expect(disruption.affectedLines.length).toBe(raw.scope.lines?.length ?? 0);
    expect(disruption.affectedModes).toContain("METRO");
  });

  it("derives affectedModes as the unique set of affected line transport modes", () => {
    const disruption = normalizeDisruption(raw);
    const uniqueModes = new Set(disruption.affectedModes);
    expect(disruption.affectedModes.length).toBe(uniqueModes.size);
  });

  it("does not assume message_variants[0] is Swedish when it is not", () => {
    const rawWithEnglishFirst: RawDeviation = {
      ...raw,
      message_variants: [english, swedish],
    };
    const disruption = normalizeDisruption(rawWithEnglishFirst);
    expect(disruption.message.header).toBe("SV header");
    expect(disruption.message.language).toBe("sv");
  });
});
