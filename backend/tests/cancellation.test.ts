import { describe, expect, it } from "vitest";
import { normalizeDeparture, deriveIsCancelled } from "../src/normalize/normalizeDeparture.js";
import type { RawDeparture } from "../src/services/upstreamTypes.js";

function baseRawDeparture(overrides: Partial<RawDeparture> = {}): RawDeparture {
  return {
    direction: "Sofia",
    direction_code: 2,
    via: null,
    destination: "Hjorthagen",
    state: "EXPECTED",
    scheduled: "2026-07-04T17:33:00",
    expected: "2026-07-04T17:34:21",
    journey: { id: 2026070408514, state: "NORMALPROGRESS", prediction_state: "NORMAL" },
    stop_area: { id: 11002, name: "Slussen", type: "BUSTERM" },
    stop_point: { id: 11013, name: "Slussen", designation: "C" },
    line: { id: 57, designation: "57", transport_mode: "BUS" },
    deviations: [],
    ...overrides,
  };
}

const reference = new Date("2026-07-04T15:00:00Z");

describe("cancellation derivation", () => {
  it("is false for an ordinary departure", () => {
    const departure = normalizeDeparture(baseRawDeparture(), reference);
    expect(departure.isCancelled).toBe(false);
    expect(departure.state).toBe("EXPECTED");
  });

  it("is true when departure.state is literally CANCELLED", () => {
    const departure = normalizeDeparture(baseRawDeparture({ state: "CANCELLED" }), reference);
    expect(departure.isCancelled).toBe(true);
    expect(departure.state).toBe("CANCELLED");
  });

  it("is true when a trip deviation has consequence CANCELLED, even if state itself does not say so", () => {
    const departure = normalizeDeparture(
      baseRawDeparture({
        state: "EXPECTED",
        deviations: [{ importance_level: 5, consequence: "CANCELLED", message: "Turen är inställd" }],
      }),
      reference,
    );
    expect(departure.isCancelled).toBe(true);
    expect(departure.tripDeviations).toHaveLength(1);
    expect(departure.tripDeviations[0]?.consequence).toBe("CANCELLED");
  });

  it("treats an unknown/future state string as forward-compatible, not as a deserialization failure, and does not infer cancellation from it", () => {
    const departure = normalizeDeparture(baseRawDeparture({ state: "SOME_FUTURE_STATE_WE_HAVE_NEVER_SEEN" }), reference);
    expect(departure.state).toBe("SOME_FUTURE_STATE_WE_HAVE_NEVER_SEEN");
    expect(departure.isCancelled).toBe(false);
    expect(departure.journey.state).toBe("NORMALPROGRESS");
  });

  it("deriveIsCancelled is pure and matches the same rules directly", () => {
    expect(deriveIsCancelled("CANCELLED", [])).toBe(true);
    expect(deriveIsCancelled("EXPECTED", [{ importanceLevel: 1, consequence: "CANCELLED", message: "x" }])).toBe(true);
    expect(deriveIsCancelled("EXPECTED", [{ importanceLevel: 1, consequence: "INFORMATION", message: "x" }])).toBe(false);
    expect(deriveIsCancelled("UNKNOWN_STATE", [])).toBe(false);
  });

  it("builds a departureId from journey id, stop point id and the resolved scheduled time", () => {
    const departure = normalizeDeparture(baseRawDeparture(), reference);
    expect(departure.departureId).toBe(`2026070408514:11013:${departure.scheduledTime}`);
  });
});
