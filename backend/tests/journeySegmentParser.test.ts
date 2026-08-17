import { describe, expect, it } from "vitest";
import { parseSegmentsFromText, parseStructuredDisruptionSegment } from "../src/domain/journeySegmentParser.js";

describe("parseSegmentsFromText: the one supported grammar -- mellan A och B", () => {
  it('"mellan T-Centralen och Kungsträdgården" parses to one candidate', () => {
    const result = parseSegmentsFromText("Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården");
    expect(result).toEqual({ status: "PARSED", candidates: [{ stopA: "t-centralen", stopB: "kungsträdgården" }] });
  });

  it("case variation (all-caps) parses safely, case-normalized", () => {
    const result = parseSegmentsFromText("INSTÄLLD TRAFIK MELLAN T-CENTRALEN OCH KUNGSTRÄDGÅRDEN");
    expect(result).toEqual({ status: "PARSED", candidates: [{ stopA: "t-centralen", stopB: "kungsträdgården" }] });
  });

  it("harmless trailing punctuation is stripped from the second candidate", () => {
    const result = parseSegmentsFromText("Trafiken är inställd mellan Hökarängen och Farsta strand.");
    expect(result).toEqual({ status: "PARSED", candidates: [{ stopA: "hökarängen", stopB: "farsta strand" }] });
  });

  it("unrelated prose containing neither word does not parse", () => {
    expect(parseSegmentsFromText("Hissen är avstängd på grund av tekniskt fel.")).toEqual({ status: "UNSUPPORTED" });
  });

  it('"T-Centralen och Kungsträdgården" without the leading "mellan" does not parse', () => {
    expect(parseSegmentsFromText("Berör resenärer T-Centralen och Kungsträdgården idag.")).toEqual({ status: "UNSUPPORTED" });
  });

  it('incomplete "mellan T-Centralen" (no "och B" at all) does not parse', () => {
    expect(parseSegmentsFromText("Arbete pågår mellan T-Centralen tills vidare.")).toEqual({ status: "UNSUPPORTED" });
  });

  it('a clause containing "mellan" twice is skipped as within-clause-ambiguous, never a guess', () => {
    // Two distinct "mellan X och Y" phrases inside the very same clause -- deliberately not
    // guessed at; see this function's own doc.
    expect(
      parseSegmentsFromText("Arbete sker mellan T-Centralen och Kungsträdgården samt mellan Odenplan och Rådmansgatan."),
    ).toEqual({ status: "UNSUPPORTED" });
  });

  it("multiple genuinely separate clauses each parse independently, not first-match-wins", () => {
    // Two different sentences, each with exactly one "mellan A och B" -- both are returned as
    // candidates; which one (if any) belongs to a given affected line is decided later, per line,
    // by LineTopologyDirectory -- never guessed here.
    const text = "Blå linjen är inställd mellan T-Centralen och Kungsträdgården. Gröna linjen är avstängd mellan Odenplan och S:t Eriksplan.";
    const result = parseSegmentsFromText(text);
    expect(result.status).toBe("PARSED");
    expect(result.status === "PARSED" && result.candidates).toEqual([
      { stopA: "t-centralen", stopB: "kungsträdgården" },
      { stopA: "odenplan", stopB: "s:t eriksplan" },
    ]);
  });

  it("parsing stays sentence/clause-local -- does not assemble a match across a genuine sentence boundary", () => {
    // "mellan T-Centralen" ends one sentence; "och Kungsträdgården" starts a wholly unrelated
    // next one. Concatenating them would wrongly manufacture a segment that was never stated.
    expect(parseSegmentsFromText("Arbete sker mellan T-Centralen. Och Kungsträdgården har normal trafik.")).toEqual({
      status: "UNSUPPORTED",
    });
  });

  it("a comma-joined contrast clause (\"...,  men...\") keeps the mellan-och phrase in its own clause, unaffected by the other clause", () => {
    expect(parseSegmentsFromText("Entrén är öppen, men trafiken är inställd mellan T-Centralen och Kungsträdgården.")).toEqual({
      status: "PARSED",
      candidates: [{ stopA: "t-centralen", stopB: "kungsträdgården" }],
    });
  });

  it("does no fuzzy resolution of its own -- returns the raw (conservatively normalized) candidate text unchanged, never a corrected/guessed spelling", () => {
    // A misspelled/unknown name is still returned verbatim as a candidate; only
    // LineTopologyDirectory's own exact-match resolution (a separate module) ever accepts or
    // rejects it -- this parser has no notion of what a valid station name even is.
    const result = parseSegmentsFromText("Trafiken är inställd mellan Nagonstans och Ettannatstalle.");
    expect(result).toEqual({ status: "PARSED", candidates: [{ stopA: "nagonstans", stopB: "ettannatstalle" }] });
  });
});

describe("parseStructuredDisruptionSegment: header and details are unioned, never short-circuited", () => {
  it("a segment named only in details is never discarded because the header also names a different one", () => {
    // An earlier version of this function returned the header's candidate ONLY, silently
    // discarding a genuinely different, relevant details-only segment -- unsafe for the relevance
    // use case (see this function's own doc): both must survive.
    const result = parseStructuredDisruptionSegment({
      header: "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
      details: "Se även arbete mellan Odenplan och Rådmansgatan för gröna linjen.",
    });
    expect(result).toEqual({
      status: "PARSED",
      candidates: [
        { stopA: "t-centralen", stopB: "kungsträdgården" },
        { stopA: "odenplan", stopB: "rådmansgatan för gröna linjen" },
      ],
    });
  });

  it("the header contributes nothing when it has no mellan-och phrase, so the union is just details' own candidate", () => {
    // stopB legitimately carries trailing prose here ("på grund av arbete") -- this sentence has
    // no clause boundary after the real station name, so nothing trims it at the parser level;
    // resolving the raw candidate down to the real station name is LineTopologyDirectory's own
    // exact-match resolution step, never guessed here (see this file's own top-level doc).
    const result = parseStructuredDisruptionSegment({
      header: "Trafikinformation",
      details: "Tågtrafiken är inställd mellan Hökarängen och Farsta strand på grund av arbete.",
    });
    expect(result).toEqual({ status: "PARSED", candidates: [{ stopA: "hökarängen", stopB: "farsta strand på grund av arbete" }] });
  });

  it("an identical segment repeated verbatim in both header and details is deduplicated to one candidate", () => {
    // SL's own details body often restates the header's first sentence -- a genuine repeat is
    // resolved once, not twice, but this is a NARROW exact-pair dedup, never a fuzzy one (see this
    // function's own doc): it only collapses an identical stopA/stopB pair, never merges two
    // candidates whose text merely overlaps.
    const result = parseStructuredDisruptionSegment({
      header: "Inställd trafik mellan T-Centralen och Kungsträdgården",
      details: "Trafiken är inställd mellan T-Centralen och Kungsträdgården.",
    });
    expect(result).toEqual({ status: "PARSED", candidates: [{ stopA: "t-centralen", stopB: "kungsträdgården" }] });
  });

  it("UNSUPPORTED when neither header nor details contain the grammar", () => {
    expect(parseStructuredDisruptionSegment({ header: "Trafikinformation", details: "Se vår webbplats." })).toEqual({
      status: "UNSUPPORTED",
    });
  });
});

describe("parseStructuredDisruptionSegment: real live SL fixture", () => {
  // The real live disruption (deviation_case_id 11592474, observed 2026-08-16) already covered
  // as a classifier fixture in classifyDisruptionEffect.test.ts's own "Blue-line closure
  // disruption" describe block -- reproduced verbatim here for the segment parser.
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

  it("the header alone parses to exactly the T-Centralen/Kungsträdgården candidate", () => {
    expect(parseSegmentsFromText(header)).toEqual({ status: "PARSED", candidates: [{ stopA: "t-centralen", stopB: "kungsträdgården" }] });
  });

  it("the full message unions header and details -- the header's own clean candidate AND every one of details' own candidates all survive", () => {
    const result = parseStructuredDisruptionSegment({ header, details });
    expect(result.status).toBe("PARSED");
    const candidates = result.status === "PARSED" ? result.candidates : [];
    // The header's own clean candidate (no trailing prose -- the header ends right after the
    // station name), distinct from details' own two T-Centralen/Kungsträdgården mentions below
    // (each of which carries different trailing prose, so exact-pair dedup does not collapse them).
    expect(candidates).toContainEqual({ stopA: "t-centralen", stopB: "kungsträdgården" });
    // The genuinely different Green-line candidate from details survives too -- nothing from
    // details is dropped just because the header also matched.
    expect(candidates).toContainEqual({
      stopA: "odenplan",
      stopB: "s:t eriksplan och har förändrad trafik under perioden måndag 6 juli till och med söndag 26 juli",
    });
    expect(candidates.filter((c) => c.stopA === "t-centralen" && c.stopB.startsWith("kungsträdgården"))).toHaveLength(3);
  });

  it("the details alone contain both the repeated Blue-line candidate and the separate, genuinely different Green-line one -- both surfaced, neither dropped", () => {
    // Every occurrence here happens to fall in a clause with trailing prose after the real
    // station name (unlike the header's own clean, short construction) -- raw, unshrunk
    // candidates are exactly what this parser is supposed to produce; see this file's own
    // top-level doc on why trimming to the real station name belongs to LineTopologyDirectory's
    // own exact-match resolution, never guessed here. Both real stations still start each
    // candidate's own stopB, which is what that later exact-match resolution step relies on.
    const result = parseSegmentsFromText(details);
    expect(result.status).toBe("PARSED");
    const candidates = result.status === "PARSED" ? result.candidates : [];
    expect(candidates.filter((c) => c.stopA === "t-centralen" && c.stopB.startsWith("kungsträdgården"))).toHaveLength(2);
    expect(candidates).toContainEqual({
      stopA: "odenplan",
      stopB: "s:t eriksplan och har förändrad trafik under perioden måndag 6 juli till och med söndag 26 juli",
    });
  });
});
