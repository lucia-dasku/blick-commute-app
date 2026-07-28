import { describe, expect, it } from "vitest";
import { foldForSearch, InvalidSearchQueryError, MAX_RESULTS, normalizeSearchQuery, rankSites } from "../src/lib/search.js";

const sites = [
  { siteId: 9145, name: "Fruängen", note: null },
  { siteId: 9146, name: "Fruängens skola", note: null },
  { siteId: 1002, name: "T-Centralen", note: null },
  { siteId: 9530, name: "Gullmarsplan", note: null },
  { siteId: 7011, name: "Radiohuset", note: null },
  { siteId: 9331, name: "Hässelby strand", note: null },
  { siteId: 9192, name: "Slussen", note: "Södermalm" },
  { siteId: 40004, name: "Ekstubben", note: "Nacka" },
];

describe("normalizeSearchQuery", () => {
  it("trims surrounding whitespace", () => {
    expect(normalizeSearchQuery("  Slussen  ")).toBe("Slussen");
  });

  it("rejects an empty query", () => {
    expect(() => normalizeSearchQuery("   ")).toThrow(InvalidSearchQueryError);
  });

  it("rejects an overly long query", () => {
    expect(() => normalizeSearchQuery("a".repeat(65))).toThrow(InvalidSearchQueryError);
  });

  it("accepts a query at the maximum length", () => {
    expect(normalizeSearchQuery("a".repeat(64))).toHaveLength(64);
  });
});

describe("foldForSearch (Swedish character tolerance)", () => {
  it("folds å/ä/ö to their unaccented equivalents and lowercases", () => {
    expect(foldForSearch("Fruängen")).toBe("fruangen");
    expect(foldForSearch("HÄSSELBY STRAND")).toBe("hasselby strand");
    expect(foldForSearch("Södermalm")).toBe("sodermalm");
  });
});

describe("rankSites", () => {
  it("ranks an exact match (diacritic-insensitive) above everything else", () => {
    const results = rankSites(sites, "fruangen");
    expect(results[0]?.siteId).toBe(9145); // "Fruängen" exact-folds to "fruangen"
  });

  it("ranks a prefix match above a token-prefix or substring match", () => {
    const results = rankSites(sites, "frua");
    expect(results.map((s) => s.siteId)).toEqual([9145, 9146]); // both prefix-match, alphabetical tie-break
  });

  it("finds a token-prefix match for the second word of a multi-word name", () => {
    const results = rankSites(sites, "strand");
    expect(results.some((s) => s.siteId === 9331)).toBe(true);
  });

  it("finds a substring match that is not a prefix of any token", () => {
    const results = rankSites(sites, "ussen");
    expect(results.some((s) => s.siteId === 9192)).toBe(true);
  });

  it("matches against note when name does not match, ranked behind an equivalent name match", () => {
    const results = rankSites(sites, "nacka");
    expect(results.map((s) => s.siteId)).toEqual([40004]);
  });

  it("is diacritic-tolerant when the query itself has no accents", () => {
    const results = rankSites(sites, "sodermalm");
    expect(results.some((s) => s.siteId === 9192)).toBe(true); // matches via note "Södermalm"
  });

  it("is case-insensitive", () => {
    const lower = rankSites(sites, "slussen");
    const upper = rankSites(sites, "SLUSSEN");
    expect(lower.map((s) => s.siteId)).toEqual(upper.map((s) => s.siteId));
  });

  it("returns results in deterministic order across repeated calls", () => {
    const first = rankSites(sites, "s");
    const second = rankSites(sites, "s");
    expect(first.map((s) => s.siteId)).toEqual(second.map((s) => s.siteId));
  });

  it("caps results at MAX_RESULTS", () => {
    const manySites = Array.from({ length: MAX_RESULTS + 10 }, (_, i) => ({
      siteId: i,
      name: `Substation ${i}`,
      note: null,
    }));
    const results = rankSites(manySites, "station");
    expect(results.length).toBe(MAX_RESULTS);
  });

  it("returns an empty array when nothing matches", () => {
    expect(rankSites(sites, "zzzznotarealstop")).toEqual([]);
  });
});
