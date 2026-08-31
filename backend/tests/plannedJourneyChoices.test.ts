import { describe, expect, it } from "vitest";
import { selectPlannedJourneyChoices } from "../src/domain/plannedJourneyChoices.js";

interface Candidate {
  journeyId: string;
  departureTime: string;
  arrivalTime: string;
  transferCount: number;
  walkingDurationSeconds: number | null;
  route: string;
}

function candidate(
  journeyId: string,
  departure: string,
  arrival: string,
  options: Partial<Pick<Candidate, "transferCount" | "walkingDurationSeconds" | "route">> = {},
): Candidate {
  return {
    journeyId,
    departureTime: `2026-08-10T${departure}:00Z`,
    arrivalTime: `2026-08-10T${arrival}:00Z`,
    transferCount: options.transferCount ?? 0,
    walkingDurationSeconds: options.walkingDurationSeconds ?? 0,
    route: options.route ?? "metro",
  };
}

const leaveAt = new Date("2026-08-10T18:00:00Z");
const arriveBy = new Date("2026-08-10T18:30:00Z");

describe("planned journey choices", () => {
  it("rejects ARRIVE_BY candidates after the deadline", () => {
    const selection = selectPlannedJourneyChoices(
      [candidate("safe", "18:10", "18:29"), candidate("late", "18:15", "18:31")],
      "ARRIVE_BY",
      arriveBy,
    );

    expect(selection.eligiblePool.map((journey) => journey.journeyId)).toEqual(["safe"]);
    expect(selection.choices.map((choice) => [choice.journey.journeyId, choice.role])).toEqual([
      ["safe", "RECOMMENDED"],
    ]);
  });

  it("rejects LEAVE_AT candidates before the requested time", () => {
    const selection = selectPlannedJourneyChoices(
      [candidate("too-early", "17:59", "18:15"), candidate("eligible", "18:00", "18:20")],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.eligiblePool.map((journey) => journey.journeyId)).toEqual(["eligible"]);
  });

  it("does not choose ARRIVE_BY's absolute latest departure when it is materially worse", () => {
    const selection = selectPlannedJourneyChoices(
      [
        candidate("simple", "18:05", "18:25", { transferCount: 0, walkingDurationSeconds: 60 }),
        candidate("latest-but-worse", "18:12", "18:29", { transferCount: 2, walkingDurationSeconds: 600 }),
        candidate("earlier", "17:55", "18:20", { walkingDurationSeconds: 60 }),
      ],
      "ARRIVE_BY",
      arriveBy,
    );

    expect(selection.recommended?.journeyId).toBe("simple");
  });

  it("chooses the earlier-arriving LEAVE_AT journey instead of the first departure", () => {
    const selection = selectPlannedJourneyChoices(
      [candidate("first", "18:02", "18:29"), candidate("recommended", "18:07", "18:25")],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.recommended?.journeyId).toBe("recommended");
    expect(selection.choices.map((choice) => choice.role)).toEqual(["EARLIER", "RECOMMENDED"]);
  });

  it("uses the closest distinct departure on each side of RECOMMENDED", () => {
    const selection = selectPlannedJourneyChoices(
      [
        candidate("far-earlier", "18:01", "18:24"),
        candidate("near-earlier", "18:06", "18:26"),
        candidate("recommended", "18:10", "18:20"),
        candidate("near-later", "18:13", "18:27"),
        candidate("far-later", "18:20", "18:28"),
      ],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.earlier?.journeyId).toBe("near-earlier");
    expect(selection.later?.journeyId).toBe("near-later");
  });

  it("allows earlier and later choices from different route families", () => {
    const selection = selectPlannedJourneyChoices(
      [
        candidate("bus-earlier", "18:04", "18:26", { route: "bus" }),
        candidate("metro-recommended", "18:08", "18:20", { route: "metro" }),
        candidate("train-later", "18:12", "18:25", { route: "train" }),
      ],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.choices.map((choice) => choice.journey.route)).toEqual(["bus", "metro", "train"]);
  });

  it("returns choices in chronological departure order", () => {
    const selection = selectPlannedJourneyChoices(
      [
        candidate("later", "18:12", "18:28"),
        candidate("recommended", "18:08", "18:20"),
        candidate("earlier", "18:04", "18:26"),
      ],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.choices.map((choice) => [choice.journey.journeyId, choice.role])).toEqual([
      ["earlier", "EARLIER"],
      ["recommended", "RECOMMENDED"],
      ["later", "LATER"],
    ]);
  });

  it("deduplicates repeated ids and exact departure/arrival opportunities", () => {
    const selection = selectPlannedJourneyChoices(
      [
        candidate("same-id", "18:03", "18:28", { transferCount: 2 }),
        candidate("same-id", "18:07", "18:25"),
        candidate("duplicate-times-worse", "18:07", "18:25", { transferCount: 1 }),
        candidate("later", "18:12", "18:29"),
      ],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.eligiblePool.map((journey) => journey.journeyId)).toEqual(["same-id", "later"]);
    expect(new Set(selection.choices.map((choice) => choice.journey.journeyId)).size).toBe(selection.choices.length);
  });

  it("keeps the same line at genuinely different times as distinct choices", () => {
    const selection = selectPlannedJourneyChoices(
      [
        candidate("metro-1802", "18:02", "18:29", { route: "metro-19" }),
        candidate("metro-1807", "18:07", "18:25", { route: "metro-19" }),
        candidate("metro-1812", "18:12", "18:28", { route: "metro-19" }),
      ],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.choices.map((choice) => choice.journey.journeyId)).toEqual([
      "metro-1802",
      "metro-1807",
      "metro-1812",
    ]);
  });

  it("returns one or two choices when no useful neighbor exists", () => {
    const one = selectPlannedJourneyChoices([candidate("only", "18:05", "18:20")], "LEAVE_AT", leaveAt);
    const two = selectPlannedJourneyChoices(
      [candidate("recommended", "18:05", "18:20"), candidate("later", "18:10", "18:22")],
      "LEAVE_AT",
      leaveAt,
    );

    expect(one.choices.map((choice) => choice.role)).toEqual(["RECOMMENDED"]);
    expect(two.choices.map((choice) => choice.role)).toEqual(["RECOMMENDED", "LATER"]);
  });

  it("uses journey id as the final deterministic tie-break", () => {
    const selection = selectPlannedJourneyChoices(
      [candidate("z-choice", "18:05", "18:20"), candidate("a-choice", "18:05", "18:20")],
      "LEAVE_AT",
      leaveAt,
    );

    expect(selection.recommended?.journeyId).toBe("a-choice");
    expect(selection.choices).toHaveLength(1);
  });
});
