/**
 * A JSON parser that never converts a number literal to a JS `number` — every number in the
 * source (however large, however many digits) comes out as the exact source substring
 * (`string`), byte-for-byte. Exists specifically for `slTransportClient.ts`'s `/v1/stop-points`
 * ingestion: that endpoint's `pattern_point_gid`/`gid` fields are routinely larger than
 * `Number.MAX_SAFE_INTEGER` (confirmed against the real live upstream — every single record
 * in a production snapshot exceeded it, e.g. `9025001000000101`), and the ordinary
 * `response.json()` path every other upstream client in this codebase uses
 * (`lib/upstreamFetch.ts`) would silently round such a value to the nearest representable
 * double (`9025001000000101` -> `9025001000000100`) — corrupting the exact identifier
 * `StopPointDirectory` joins Journey Planner's own platform ids against. See that service's own
 * doc for why this join must be exact-string equality, never a rounded numeric comparison.
 *
 * Deliberately NOT a regex-based rewrite of the raw text (e.g. quoting long digit runs before
 * handing off to the real `JSON.parse`) — a real tokenizer is the only way to reliably tell a
 * number literal apart from the same digits appearing inside a string value, and to handle
 * whitespace/escaping correctly. Deliberately NOT a general-purpose npm dependency either
 * (`json-bigint`, `lossless-json`, ...): this parser's own contract is narrower and simpler
 * than either (every number becomes a string, full stop — no `LosslessNumber` wrapper type to
 * thread through calling code) and is exercised by this module's own exhaustive tests, so a
 * third-party parsing library's own bug surface is never a dependency of Blick's stop-identity
 * join at all.
 *
 * Only ever used for the ONE upstream response this precision issue actually applies to —
 * every other SL Transport/Deviations/Journey Planner client keeps using the ordinary
 * `fetchUpstreamJson`/`response.json()` path unchanged (see this function's own call site in
 * `slTransportClient.ts`).
 *
 * Uses `String.prototype.charAt`, never bracket indexing (`text[i]`), throughout: under this
 * project's `noUncheckedIndexedAccess` compiler option bracket indexing would type every
 * character as `string | undefined`, forcing an undefined check on every single comparison;
 * `charAt` returns `""` (never `undefined`) past the end of the string, which is exactly the
 * "not any expected character" sentinel every comparison below already wants, and every loop's
 * own `i < n` bound (never `charAt`'s return value) is what actually decides termination.
 */
export function parseLosslessJson(text: string): unknown {
  let i = 0;
  const n = text.length;

  function fail(message: string): never {
    const line = text.slice(0, i).split("\n").length;
    throw new SyntaxError(`${message} at position ${i} (line ${line})`);
  }

  function skipWhitespace(): void {
    while (i < n) {
      const c = text.charCodeAt(i);
      if (c === 0x20 || c === 0x09 || c === 0x0a || c === 0x0d) i++;
      else break;
    }
  }

  function parseValue(): unknown {
    skipWhitespace();
    if (i >= n) fail("Unexpected end of input");
    const c = text.charAt(i);
    if (c === "{") return parseObject();
    if (c === "[") return parseArray();
    if (c === '"') return parseString();
    if (c === "-" || (c >= "0" && c <= "9")) return parseNumber();
    if (text.startsWith("true", i)) {
      i += 4;
      return true;
    }
    if (text.startsWith("false", i)) {
      i += 5;
      return false;
    }
    if (text.startsWith("null", i)) {
      i += 4;
      return null;
    }
    fail(`Unexpected token '${c}'`);
  }

  function parseObject(): Record<string, unknown> {
    const obj: Record<string, unknown> = {};
    i++; // {
    skipWhitespace();
    if (text.charAt(i) === "}") {
      i++;
      return obj;
    }
    for (;;) {
      skipWhitespace();
      if (text.charAt(i) !== '"') fail("Expected string key");
      const key = parseString();
      skipWhitespace();
      if (text.charAt(i) !== ":") fail("Expected ':'");
      i++;
      obj[key] = parseValue();
      skipWhitespace();
      const after = text.charAt(i);
      if (after === ",") {
        i++;
        continue;
      }
      if (after === "}") {
        i++;
        break;
      }
      fail("Expected ',' or '}'");
    }
    return obj;
  }

  function parseArray(): unknown[] {
    const arr: unknown[] = [];
    i++; // [
    skipWhitespace();
    if (text.charAt(i) === "]") {
      i++;
      return arr;
    }
    for (;;) {
      arr.push(parseValue());
      skipWhitespace();
      const after = text.charAt(i);
      if (after === ",") {
        i++;
        continue;
      }
      if (after === "]") {
        i++;
        break;
      }
      fail("Expected ',' or ']'");
    }
    return arr;
  }

  function parseString(): string {
    i++; // opening quote
    let result = "";
    let start = i;
    while (i < n) {
      const c = text.charAt(i);
      if (c === '"') {
        result += text.slice(start, i);
        i++;
        return result;
      }
      if (c === "\\") {
        result += text.slice(start, i);
        i++;
        const esc = text.charAt(i);
        if (esc === '"') result += '"';
        else if (esc === "\\") result += "\\";
        else if (esc === "/") result += "/";
        else if (esc === "b") result += "\b";
        else if (esc === "f") result += "\f";
        else if (esc === "n") result += "\n";
        else if (esc === "r") result += "\r";
        else if (esc === "t") result += "\t";
        else if (esc === "u") {
          const hex = text.slice(i + 1, i + 5);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail("Invalid unicode escape");
          result += String.fromCharCode(parseInt(hex, 16));
          i += 4;
        } else fail(`Invalid escape '\\${esc}'`);
        i++;
        start = i;
        continue;
      }
      i++;
    }
    fail("Unterminated string");
  }

  /**
   * Returns the exact source lexeme of one JSON number, as a string — never routed through
   * `Number(...)`/`parseFloat`, so no precision is ever lost regardless of magnitude. Full JSON
   * number grammar (RFC 8259 §6): `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`.
   */
  function parseNumber(): string {
    const start = i;
    if (text.charAt(i) === "-") i++;
    if (text.charAt(i) === "0") {
      i++;
    } else if (text.charAt(i) >= "1" && text.charAt(i) <= "9") {
      while (i < n && text.charAt(i) >= "0" && text.charAt(i) <= "9") i++;
    } else {
      fail("Invalid number");
    }
    if (text.charAt(i) === ".") {
      i++;
      if (!(text.charAt(i) >= "0" && text.charAt(i) <= "9")) fail("Invalid number (missing fraction digits)");
      while (i < n && text.charAt(i) >= "0" && text.charAt(i) <= "9") i++;
    }
    if (text.charAt(i) === "e" || text.charAt(i) === "E") {
      i++;
      if (text.charAt(i) === "+" || text.charAt(i) === "-") i++;
      if (!(text.charAt(i) >= "0" && text.charAt(i) <= "9")) fail("Invalid number (missing exponent digits)");
      while (i < n && text.charAt(i) >= "0" && text.charAt(i) <= "9") i++;
    }
    return text.slice(start, i);
  }

  const value = parseValue();
  skipWhitespace();
  if (i !== n) fail("Unexpected trailing content");
  return value;
}
