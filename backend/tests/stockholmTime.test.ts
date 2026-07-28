import { describe, expect, it } from "vitest";
import {
  getStockholmOffsetMinutesAt,
  InvalidStockholmTimestampError,
  isInvalidStockholmTimestampError,
  naiveStockholmLocalToIso,
  resolveStockholmLocalTime,
} from "../src/lib/stockholmTime.js";

describe("stockholmTime", () => {
  it("resolves a normal winter (CET, UTC+1) timestamp", () => {
    const { iso, anomaly } = naiveStockholmLocalToIso("2026-01-15T08:00:00", new Date("2026-01-15T07:00:00Z"));
    expect(iso).toBe("2026-01-15T08:00:00+01:00");
    expect(anomaly).toBeUndefined();
  });

  it("resolves a normal summer (CEST, UTC+2) timestamp", () => {
    const { iso, anomaly } = naiveStockholmLocalToIso("2026-07-04T17:33:00", new Date("2026-07-04T15:00:00Z"));
    expect(iso).toBe("2026-07-04T17:33:00+02:00");
    expect(anomaly).toBeUndefined();
  });

  it("resolves the instant just before the 2026 spring transition as CET", () => {
    // EU spring transition 2026: last Sunday of March = 2026-03-29, clocks jump
    // 02:00 CET -> 03:00 CEST.
    const { iso, anomaly } = naiveStockholmLocalToIso("2026-03-29T01:59:00", new Date("2026-03-29T00:00:00Z"));
    expect(iso).toBe("2026-03-29T01:59:00+01:00");
    expect(anomaly).toBeUndefined();
  });

  it("resolves the instant just after the 2026 spring transition as CEST", () => {
    const { iso, anomaly } = naiveStockholmLocalToIso("2026-03-29T03:00:00", new Date("2026-03-29T00:00:00Z"));
    expect(iso).toBe("2026-03-29T03:00:00+02:00");
    expect(anomaly).toBeUndefined();
  });

  it("rejects a nonexistent spring-gap local time instead of manufacturing an instant", () => {
    // 2026-03-29T02:30:00 never occurs in Europe/Stockholm.
    expect(() => resolveStockholmLocalTime("2026-03-29T02:30:00", new Date("2026-03-29T00:00:00Z"))).toThrow(
      InvalidStockholmTimestampError,
    );
    try {
      resolveStockholmLocalTime("2026-03-29T02:30:00", new Date("2026-03-29T00:00:00Z"));
      expect.unreachable();
    } catch (err) {
      expect(isInvalidStockholmTimestampError(err)).toBe(true);
    }
  });

  it("resolves a local time clearly before the 2026 autumn ambiguous hour as CEST", () => {
    // EU autumn transition 2026: last Sunday of October = 2026-10-25, local 02:00-02:59
    // occurs twice (first as CEST, then as CET). 01:59 is before that window and unambiguous.
    const { iso, anomaly } = naiveStockholmLocalToIso("2026-10-25T01:59:00", new Date("2026-10-25T00:00:00Z"));
    expect(iso).toBe("2026-10-25T01:59:00+02:00");
    expect(anomaly).toBeUndefined();
  });

  it("resolves a local time clearly after the 2026 autumn ambiguous hour as CET", () => {
    // 03:01 is after the second (CET) pass through 02:00-02:59 has already completed.
    const { iso, anomaly } = naiveStockholmLocalToIso("2026-10-25T03:01:00", new Date("2026-10-25T02:00:00Z"));
    expect(iso).toBe("2026-10-25T03:01:00+01:00");
    expect(anomaly).toBeUndefined();
  });

  it("flags a local time inside the duplicated autumn hour as ambiguous even near its edge", () => {
    // 02:59 is the last minute of the duplicated hour and genuinely occurs twice.
    const result = resolveStockholmLocalTime("2026-10-25T02:59:00", new Date("2026-10-25T00:30:00Z"));
    expect(result.anomaly).toBe("ambiguous");
  });

  it("disambiguates the duplicated autumn hour using fetchedAt: picks the CEST (earlier) occurrence when fetchedAt is close to the CEST pass", () => {
    // 2026-10-25T02:30:00 occurs twice: once at 00:30 UTC (CEST, +02:00) and once at
    // 01:30 UTC (CET, +01:00). A response fetched right around the first pass should
    // resolve to the earlier (CEST) occurrence.
    const fetchedAt = new Date("2026-10-25T00:31:00Z");
    const result = resolveStockholmLocalTime("2026-10-25T02:30:00", fetchedAt);
    expect(result.anomaly).toBe("ambiguous");
    expect(result.offsetMinutes).toBe(120);
    expect(result.instant.toISOString()).toBe("2026-10-25T00:30:00.000Z");
  });

  it("disambiguates the duplicated autumn hour using fetchedAt: picks the CET (later) occurrence when fetchedAt is close to the CET pass", () => {
    const fetchedAt = new Date("2026-10-25T01:29:00Z");
    const result = resolveStockholmLocalTime("2026-10-25T02:30:00", fetchedAt);
    expect(result.anomaly).toBe("ambiguous");
    expect(result.offsetMinutes).toBe(60);
    expect(result.instant.toISOString()).toBe("2026-10-25T01:30:00.000Z");
  });

  it("offset helper agrees with the resolver for a known CET instant", () => {
    const offset = getStockholmOffsetMinutesAt(new Date("2026-01-15T07:00:00Z").getTime());
    expect(offset).toBe(60);
  });

  it("offset helper agrees with the resolver for a known CEST instant", () => {
    const offset = getStockholmOffsetMinutesAt(new Date("2026-07-04T15:00:00Z").getTime());
    expect(offset).toBe(120);
  });

  describe("calendar validation", () => {
    const reference = new Date("2026-07-04T15:00:00Z");

    it("rejects 30 February", () => {
      expect(() => resolveStockholmLocalTime("2026-02-30T10:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
    });

    it("rejects 29 February in a non-leap year", () => {
      // 2026 is not a leap year (not divisible by 4).
      expect(() => resolveStockholmLocalTime("2026-02-29T10:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
    });

    it("accepts 29 February in a valid leap year", () => {
      // 2028 is a leap year (divisible by 4, not by 100).
      const { iso } = naiveStockholmLocalToIso("2028-02-29T10:00:00", new Date("2028-02-29T09:00:00Z"));
      expect(iso).toBe("2028-02-29T10:00:00+01:00");
    });

    it("rejects a century year that is not a leap year (divisible by 100, not 400)", () => {
      // 2100 is divisible by 100 but not 400, so it is NOT a leap year.
      expect(() => resolveStockholmLocalTime("2100-02-29T10:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
    });

    it("accepts a year divisible by 400 as a leap year", () => {
      const { iso } = naiveStockholmLocalToIso("2400-02-29T10:00:00", new Date("2400-02-29T09:00:00Z"));
      expect(iso).toBe("2400-02-29T10:00:00+01:00");
    });

    it("rejects an out-of-range month", () => {
      expect(() => resolveStockholmLocalTime("2026-13-01T10:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
      expect(() => resolveStockholmLocalTime("2026-00-01T10:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
    });

    it("rejects an out-of-range day for a 30-day month", () => {
      // April has 30 days.
      expect(() => resolveStockholmLocalTime("2026-04-31T10:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
    });

    it("rejects an out-of-range hour, minute, or second", () => {
      expect(() => resolveStockholmLocalTime("2026-07-04T24:00:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
      expect(() => resolveStockholmLocalTime("2026-07-04T10:60:00", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
      expect(() => resolveStockholmLocalTime("2026-07-04T10:00:60", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
    });

    it("rejects a malformed string entirely", () => {
      expect(() => resolveStockholmLocalTime("not-a-timestamp", reference)).toThrow(
        InvalidStockholmTimestampError,
      );
      expect(() => resolveStockholmLocalTime("2026-07-04", reference)).toThrow(InvalidStockholmTimestampError);
    });
  });

  describe("ISO round-trip consistency", () => {
    it("agrees on wall-clock value, offset, and represented Instant for a normal CET timestamp", () => {
      const naiveLocal = "2026-01-15T08:00:00";
      const reference = new Date("2026-01-15T07:00:00Z");
      const { iso } = naiveStockholmLocalToIso(naiveLocal, reference);

      const offsetMatch = /([+-]\d{2}):(\d{2})$/.exec(iso);
      expect(offsetMatch).not.toBeNull();
      const [, offH, offM] = offsetMatch!;
      const offsetMinutes = (offH!.startsWith("-") ? -1 : 1) * (Math.abs(Number(offH)) * 60 + Number(offM));

      const parsedInstant = new Date(iso);
      // The offset embedded in the string must be the real Stockholm offset at the
      // represented instant.
      expect(getStockholmOffsetMinutesAt(parsedInstant.getTime())).toBe(offsetMinutes);
      // Re-applying the offset to the wall-clock portion must reproduce the same
      // instant `new Date(iso)` parses to (wall-clock value, offset, and Instant agree).
      expect(iso.slice(0, 19)).toBe(naiveLocal);
      // 2026-01-15T08:00:00+01:00 is exactly 2026-01-15T07:00:00Z, i.e. the reference instant.
      expect(parsedInstant.toISOString()).toBe(reference.toISOString());
    });

    it("agrees on wall-clock value, offset, and represented Instant across the autumn ambiguous hour", () => {
      const naiveLocal = "2026-10-25T02:30:00";
      const reference = new Date("2026-10-25T00:31:00Z");
      const { iso } = naiveStockholmLocalToIso(naiveLocal, reference);

      const offsetMatch = /([+-]\d{2}):(\d{2})$/.exec(iso);
      const [, offH, offM] = offsetMatch!;
      const offsetMinutes = (offH!.startsWith("-") ? -1 : 1) * (Math.abs(Number(offH)) * 60 + Number(offM));

      const parsedInstant = new Date(iso);
      expect(getStockholmOffsetMinutesAt(parsedInstant.getTime())).toBe(offsetMinutes);
      expect(iso.slice(0, 19)).toBe(naiveLocal);
    });
  });
});
