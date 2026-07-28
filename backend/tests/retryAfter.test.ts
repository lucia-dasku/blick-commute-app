import { describe, expect, it } from "vitest";
import { isValidRetryAfterValue } from "../src/lib/retryAfter.js";

describe("isValidRetryAfterValue", () => {
  it("accepts a plain non-negative integer (delay-seconds)", () => {
    expect(isValidRetryAfterValue("0")).toBe(true);
    expect(isValidRetryAfterValue("30")).toBe(true);
    expect(isValidRetryAfterValue("120")).toBe(true);
    expect(isValidRetryAfterValue("007")).toBe(true); // leading zeros are still 1*DIGIT
  });

  it("accepts a valid IMF-fixdate HTTP-date", () => {
    expect(isValidRetryAfterValue("Wed, 21 Oct 2026 07:28:00 GMT")).toBe(true);
    expect(isValidRetryAfterValue("Sun, 06 Nov 2026 08:49:37 GMT")).toBe(true);
  });

  it("rejects a negative number", () => {
    expect(isValidRetryAfterValue("-30")).toBe(false);
  });

  it("rejects a decimal number", () => {
    expect(isValidRetryAfterValue("30.5")).toBe(false);
  });

  it("rejects a number with a leading plus sign", () => {
    expect(isValidRetryAfterValue("+30")).toBe(false);
  });

  it("rejects garbage text", () => {
    expect(isValidRetryAfterValue("banana")).toBe(false);
    expect(isValidRetryAfterValue("")).toBe(false);
    expect(isValidRetryAfterValue("   ")).toBe(false);
  });

  it("rejects an ISO 8601 date string (not a valid HTTP-date format)", () => {
    expect(isValidRetryAfterValue("2026-10-21T07:28:00Z")).toBe(false);
    expect(isValidRetryAfterValue("2026-10-21")).toBe(false);
  });

  it("rejects a date string missing the required GMT suffix", () => {
    expect(isValidRetryAfterValue("Wed, 21 Oct 2026 07:28:00")).toBe(false);
  });

  it("rejects a malformed weekday/month name", () => {
    expect(isValidRetryAfterValue("Wedx, 21 Oct 2026 07:28:00 GMT")).toBe(false);
    expect(isValidRetryAfterValue("Wed, 21 Octt 2026 07:28:00 GMT")).toBe(false);
  });

  it("rejects trailing or leading whitespace/garbage around an otherwise-valid value", () => {
    expect(isValidRetryAfterValue(" 30")).toBe(false);
    expect(isValidRetryAfterValue("30 ")).toBe(false);
    expect(isValidRetryAfterValue("30;")).toBe(false);
  });
});
