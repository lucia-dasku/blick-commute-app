/**
 * Converts SL Transport's naive local timestamps ("2026-07-04T17:33:00", no offset,
 * always Europe/Stockholm wall-clock) into real instants and explicit-offset ISO 8601
 * strings, without relying on a date library's undocumented default DST disambiguation.
 *
 * Policy (see docs/api-contract.md, "Stockholm timestamp handling"):
 *  - The naive string is first strictly validated as a real calendar date/time
 *    (correct field ranges, real day-of-month for the given month/year, real leap
 *    days). An invalid string throws `InvalidStockholmTimestampError` rather than being
 *    silently "normalized" by JavaScript's native overflow behavior (e.g. `Date.UTC`
 *    would otherwise turn 30 February into 2 March without complaint).
 *  - Normal (unambiguous) local times resolve directly via an iterative fixed-point
 *    guess (a single "treat naive as UTC, look up the offset there" pass is not reliable
 *    close to a transition, since the offset that applies at the guess instant can
 *    differ from the one that applies at the real target instant).
 *  - A local time that falls in the spring-forward gap (does not exist, e.g. 02:30 on
 *    the last Sunday of March, when clocks jump from 02:00 CET straight to 03:00 CEST)
 *    is REJECTED — it throws `InvalidStockholmTimestampError` rather than manufacturing
 *    an instant using a guessed offset. SL should never actually schedule a departure
 *    inside a wall-clock hour that doesn't exist; treating this as a controlled
 *    upstream-data error (rather than inventing a plausible-looking but fictitious
 *    instant) is the honest behavior.
 *  - A local time that falls in the autumn duplicated hour (occurs twice, e.g. 02:30 on
 *    the last Sunday of October, once as CEST and once as CET) IS resolved — using
 *    `referenceInstant` (the `fetchedAt` time of the surrounding response): departures
 *    are always near-term relative to when they were fetched, so we pick whichever of
 *    the two candidate instants is not earlier than `referenceInstant` minus a small
 *    grace buffer, preferring the earlier of the two if both qualify. This is flagged
 *    via `anomaly: "ambiguous"` — unlike the spring gap, both candidate instants are
 *    real, so resolving (rather than rejecting) is appropriate here.
 */

export const STOCKHOLM_TIME_ZONE = "Europe/Stockholm";

const AMBIGUOUS_SELECTION_BUFFER_MS = 5 * 60 * 1000;

export type TimeAnomaly = "ambiguous";

export interface ResolvedStockholmTime {
  instant: Date;
  offsetMinutes: number;
  anomaly?: TimeAnomaly;
}

/**
 * Thrown when a naive local timestamp string is malformed, has an impossible calendar
 * value (e.g. 30 February, a non-leap 29 February, hour 24), or names a wall-clock time
 * that never occurred in Europe/Stockholm (the spring-forward DST gap). Callers at the
 * API boundary (see src/routes/departures.ts) translate this into a controlled
 * `AppError("UPSTREAM_ERROR", ...)` rather than letting it surface as a generic crash.
 */
export class InvalidStockholmTimestampError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "InvalidStockholmTimestampError";
  }
}

export function isInvalidStockholmTimestampError(error: unknown): error is InvalidStockholmTimestampError {
  return error instanceof InvalidStockholmTimestampError;
}

interface WallClockParts {
  year: number;
  month: number; // 1-12
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function daysInMonth(year: number, month: number): number {
  const days = [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return days[month - 1]!;
}

function parseNaiveLocal(naiveLocal: string): WallClockParts {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(naiveLocal);
  if (!match) {
    throw new InvalidStockholmTimestampError(
      `Not a naive local timestamp (expected YYYY-MM-DDTHH:mm[:ss]): ${naiveLocal}`,
    );
  }
  const [, y, mo, d, h, mi, s] = match;
  const year = Number(y);
  const month = Number(mo);
  const day = Number(d);
  const hour = Number(h);
  const minute = Number(mi);
  const second = s ? Number(s) : 0;

  if (month < 1 || month > 12) {
    throw new InvalidStockholmTimestampError(`Invalid month in naive local timestamp: ${naiveLocal}`);
  }
  if (day < 1 || day > daysInMonth(year, month)) {
    throw new InvalidStockholmTimestampError(`Invalid day-of-month in naive local timestamp: ${naiveLocal}`);
  }
  if (hour > 23) {
    throw new InvalidStockholmTimestampError(`Invalid hour in naive local timestamp: ${naiveLocal}`);
  }
  if (minute > 59) {
    throw new InvalidStockholmTimestampError(`Invalid minute in naive local timestamp: ${naiveLocal}`);
  }
  if (second > 59) {
    throw new InvalidStockholmTimestampError(`Invalid second in naive local timestamp: ${naiveLocal}`);
  }

  return { year, month, day, hour, minute, second };
}

function partsAsUtcMillis(p: WallClockParts): number {
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
}

const wallClockFormatter = new Intl.DateTimeFormat("en-US", {
  timeZone: STOCKHOLM_TIME_ZONE,
  hour12: false,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

function formatStockholmWallClock(utcMillis: number): string {
  const parts = wallClockFormatter.formatToParts(new Date(utcMillis));
  const map: Record<string, string> = {};
  for (const part of parts) map[part.type] = part.value;
  // "24" is reported as "00" by some ICU versions for hour12:false at midnight; normalize.
  const hour = map.hour === "24" ? "00" : map.hour;
  return `${map.year}-${map.month}-${map.day}T${hour}:${map.minute}:${map.second}`;
}

/** Returns the UTC offset (in minutes) in effect in Europe/Stockholm at the given instant. */
export function getStockholmOffsetMinutesAt(utcMillis: number): number {
  const wall = formatStockholmWallClock(utcMillis);
  const wallAsUtcMillis = partsAsUtcMillis(parseNaiveLocal(wall));
  return Math.round((wallAsUtcMillis - utcMillis) / 60000);
}

/**
 * Resolves a naive Europe/Stockholm local timestamp to a real instant, explicitly
 * handling the autumn ambiguity and rejecting the spring gap rather than trusting a
 * library default. Throws `InvalidStockholmTimestampError` for a malformed/impossible
 * calendar value or a spring-gap wall-clock time that never occurred.
 */
export function resolveStockholmLocalTime(
  naiveLocal: string,
  referenceInstant: Date,
): ResolvedStockholmTime {
  const parts = parseNaiveLocal(naiveLocal);
  const naiveAsUtcMillis = partsAsUtcMillis(parts);

  // A single "treat naive as UTC, look up the offset there" guess is not reliable near a
  // DST transition: the offset that applies AT naiveAsUtcMillis can differ from the
  // offset that applies at the actual candidate instant once shifted. Iterate to a fixed
  // point (this converges in at most 2-3 steps; the offset only takes one of two values).
  let offsetGuess = getStockholmOffsetMinutesAt(naiveAsUtcMillis);
  let candidateMillis = naiveAsUtcMillis - offsetGuess * 60000;
  for (let i = 0; i < 4; i++) {
    const refinedOffset = getStockholmOffsetMinutesAt(candidateMillis);
    if (refinedOffset === offsetGuess) break;
    offsetGuess = refinedOffset;
    candidateMillis = naiveAsUtcMillis - offsetGuess * 60000;
  }
  const guessInstantMillis = candidateMillis;

  // Candidate offsets from one hour either side of the converged guess, to detect
  // whether we're near a DST transition at all (offsets differ) or safely inside a
  // stable period.
  const offsetOneHourBefore = getStockholmOffsetMinutesAt(guessInstantMillis - 3600_000);
  const offsetOneHourAfter = getStockholmOffsetMinutesAt(guessInstantMillis + 3600_000);

  if (offsetOneHourBefore === offsetOneHourAfter) {
    // No transition nearby: a single, unambiguous offset applies.
    return { instant: new Date(guessInstantMillis), offsetMinutes: offsetGuess };
  }

  const candidateBefore = naiveAsUtcMillis - offsetOneHourBefore * 60000;
  const candidateAfter = naiveAsUtcMillis - offsetOneHourAfter * 60000;

  const wallAtCandidateBefore = formatStockholmWallClock(candidateBefore);
  const wallAtCandidateAfter = formatStockholmWallClock(candidateAfter);

  const matchesBefore = wallAtCandidateBefore === naiveLocal;
  const matchesAfter = wallAtCandidateAfter === naiveLocal;

  if (matchesBefore && matchesAfter && candidateBefore !== candidateAfter) {
    // Autumn duplicated hour: both offsets round-trip to the requested wall-clock time.
    const candidates = [candidateBefore, candidateAfter].sort((a, b) => a - b);
    const referenceMillis = referenceInstant.getTime();
    const chosen =
      candidates.find((c) => c >= referenceMillis - AMBIGUOUS_SELECTION_BUFFER_MS) ??
      candidates[candidates.length - 1]!;
    return {
      instant: new Date(chosen),
      offsetMinutes: getStockholmOffsetMinutesAt(chosen),
      anomaly: "ambiguous",
    };
  }

  if (!matchesBefore && !matchesAfter) {
    // Spring gap: this local wall-clock time never occurred. Reject rather than
    // manufacture an instant using a guessed offset (see file-level policy doc above).
    throw new InvalidStockholmTimestampError(
      `Naive local time ${naiveLocal} does not exist in Europe/Stockholm (falls within ` +
        `the spring-forward DST gap)`,
    );
  }

  // Exactly one candidate round-trips correctly: a normal time that merely happens to
  // sit within an hour of a transition elsewhere on the clock.
  if (matchesBefore) {
    return { instant: new Date(candidateBefore), offsetMinutes: offsetOneHourBefore };
  }
  return { instant: new Date(candidateAfter), offsetMinutes: offsetOneHourAfter };
}

function formatOffsetSuffix(offsetMinutes: number): string {
  const sign = offsetMinutes >= 0 ? "+" : "-";
  const abs = Math.abs(offsetMinutes);
  const hh = String(Math.floor(abs / 60)).padStart(2, "0");
  const mm = String(abs % 60).padStart(2, "0");
  return `${sign}${hh}:${mm}`;
}

/**
 * Converts a naive Europe/Stockholm local timestamp (as returned by SL Transport) into
 * an ISO 8601 string with an explicit UTC offset, e.g. "2026-07-04T17:34:21+02:00". The
 * emitted wall-clock value, offset, and represented Instant are guaranteed to agree,
 * since all three are derived from the same resolved `Date`. Throws
 * `InvalidStockholmTimestampError` for invalid or nonexistent input (see
 * `resolveStockholmLocalTime`).
 */
export function naiveStockholmLocalToIso(
  naiveLocal: string,
  referenceInstant: Date,
): { iso: string; anomaly?: TimeAnomaly } {
  const resolved = resolveStockholmLocalTime(naiveLocal, referenceInstant);
  const wall = formatStockholmWallClock(resolved.instant.getTime());
  return { iso: `${wall}${formatOffsetSuffix(resolved.offsetMinutes)}`, anomaly: resolved.anomaly };
}
