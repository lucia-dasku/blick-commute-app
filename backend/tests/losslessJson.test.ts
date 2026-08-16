import { describe, expect, it } from "vitest";
import { parseLosslessJson } from "../src/lib/losslessJson.js";

describe("parseLosslessJson", () => {
  it("parses a plain object/array/string/boolean/null mix, numbers as strings", () => {
    const text = `{"a": 1, "b": [true, false, null, "x"], "c": {"nested": "y"}}`;
    expect(parseLosslessJson(text)).toEqual({ a: "1", b: [true, false, null, "x"], c: { nested: "y" } });
  });

  it("preserves booleans and null as real JS booleans/null, not strings", () => {
    expect(parseLosslessJson("[true, false, null]")).toEqual([true, false, null]);
  });

  it("preserves ordinary small integers and floats as exact-digit strings", () => {
    expect(parseLosslessJson("42")).toBe("42");
    expect(parseLosslessJson("-42")).toBe("-42");
    expect(parseLosslessJson("3.14")).toBe("3.14");
    expect(parseLosslessJson("0")).toBe("0");
    expect(parseLosslessJson("-0")).toBe("-0");
  });

  it("preserves exponent notation exactly", () => {
    expect(parseLosslessJson("1e10")).toBe("1e10");
    expect(parseLosslessJson("1.5E-10")).toBe("1.5E-10");
    expect(parseLosslessJson("2e+5")).toBe("2e+5");
  });

  it("round-trips a real >MAX_SAFE_INTEGER pattern_point_gid without corrupting a single digit", () => {
    // Confirmed live against the real SL Transport /v1/stop-points upstream (Stavsnäs pier,
    // stop point id 101): this exact value is what the real production payload returns.
    // Number.MAX_SAFE_INTEGER is 9007199254740991 -- this is well beyond it.
    const patternPointGid = "9025001000000101";
    const text = `{"id": 101, "pattern_point_gid": ${patternPointGid}}`;

    // Demonstrates the bug this parser exists to avoid: plain JSON.parse silently rounds the
    // last digit away.
    const naive = JSON.parse(text) as { pattern_point_gid: number };
    expect(String(naive.pattern_point_gid)).not.toBe(patternPointGid);
    expect(String(naive.pattern_point_gid)).toBe("9025001000000100");

    const lossless = parseLosslessJson(text) as { pattern_point_gid: string };
    expect(lossless.pattern_point_gid).toBe(patternPointGid);
    expect(typeof lossless.pattern_point_gid).toBe("string");
  });

  it("round-trips a >MAX_SAFE_INTEGER value inside an array", () => {
    const gids = ["9025001000003272", "9025001000003051", "9022001000101001"];
    const parsed = parseLosslessJson(`[${gids.join(",")}]`);
    expect(parsed).toEqual(gids);
  });

  it("handles string escapes exactly like JSON.parse", () => {
    const text = String.raw`"line1\nline2\ttab\\backslash\"quote\/slashå"`;
    expect(parseLosslessJson(text)).toBe(JSON.parse(text));
  });

  it("handles nested objects/arrays and whitespace between tokens", () => {
    const text = `
      {
        "stops" : [
          { "id": 9025001000003272, "name": "Akalla" },
          { "id": 9025001000003051, "name": "T-Centralen" }
        ]
      }
    `;
    expect(parseLosslessJson(text)).toEqual({
      stops: [
        { id: "9025001000003272", name: "Akalla" },
        { id: "9025001000003051", name: "T-Centralen" },
      ],
    });
  });

  it("matches JSON.parse's structural result (ignoring number type) for a realistic stop-point record", () => {
    // Built as a literal string, deliberately NOT via JSON.stringify(objectLiteral): a raw
    // >MAX_SAFE_INTEGER numeric literal written directly in JS/TS source is already rounded by
    // the engine before JSON.stringify ever sees it -- the exact same class of bug this parser
    // exists to avoid, so the fixture itself must not fall into it.
    const text = `{
      "id": 101,
      "gid": 9022001000101001,
      "pattern_point_gid": 9025001000000101,
      "name": "Stavsnäs",
      "lat": 59.2864051510743,
      "lon": 18.704700202055,
      "has_entrance": false,
      "stop_area": {"id": 101, "name": "Stavsnäs", "type": "SHIPBER"}
    }`;
    const naive = JSON.parse(text);
    const lossless = parseLosslessJson(text) as Record<string, unknown>;
    expect(lossless.name).toBe(naive.name);
    expect(lossless.has_entrance).toBe(naive.has_entrance);
    expect((lossless.stop_area as Record<string, unknown>).name).toBe(naive.stop_area.name);
    expect(Number(lossless.lat)).toBe(naive.lat);
    expect(Number(lossless.lon)).toBe(naive.lon);
    expect(lossless.id).toBe("101");
    expect(lossless.gid).toBe("9022001000101001");
    expect(lossless.pattern_point_gid).toBe("9025001000000101");
  });

  it("rejects malformed JSON the same way JSON.parse would reject it", () => {
    expect(() => parseLosslessJson("{invalid")).toThrow(SyntaxError);
    expect(() => parseLosslessJson("[1, 2,]")).toThrow(SyntaxError);
    expect(() => parseLosslessJson("")).toThrow(SyntaxError);
    expect(() => parseLosslessJson("{\"a\": 1} trailing")).toThrow(SyntaxError);
    expect(() => parseLosslessJson("01")).toThrow(SyntaxError); // matches JSON.parse("01") also throwing
    expect(() => parseLosslessJson("NaN")).toThrow(SyntaxError);
  });

  it("parses a large realistic array (14k+ entries) quickly and without precision loss", () => {
    const n = 15000;
    const entries: string[] = [];
    for (let idx = 0; idx < n; idx++) {
      entries.push(`{"id":${idx},"pattern_point_gid":902500100000${String(idx).padStart(4, "0")}}`);
    }
    const text = `[${entries.join(",")}]`;
    const start = Date.now();
    const parsed = parseLosslessJson(text) as Array<{ id: string; pattern_point_gid: string }>;
    expect(Date.now() - start).toBeLessThan(2000);
    expect(parsed).toHaveLength(n);
    expect(parsed[0]!.pattern_point_gid).toBe("9025001000000000");
    expect(parsed[n - 1]!.pattern_point_gid).toBe(`902500100000${String(n - 1).padStart(4, "0")}`);
  });
});
