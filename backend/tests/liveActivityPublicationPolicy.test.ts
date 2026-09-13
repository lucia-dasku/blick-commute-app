import { describe, expect, it } from "vitest";
import type { LiveCommuteSnapshot } from "../src/liveCommute/snapshot.js";
import {
  mapLiveCommuteSnapshotToContentState,
  type BlickLiveActivityExactContentStateV1,
  type BlickLiveActivityLineContentStateV1,
} from "../src/liveCommute/apple/liveActivityWireContract.js";
import {
  createLiveActivityPublicationIntent,
  createLiveActivityPublicationPolicyConfig,
  decideLiveActivityPublication,
  liveActivityVisibleFingerprint,
  type LiveActivityFrequentPushState,
  type LiveActivityPublicationHistory,
  type LiveActivityPublicationHistoryEntry,
  type LiveActivityPublicationIntent,
  type LiveActivityPublicationPolicyConfig,
  type LiveActivityPublicationPolicyInput,
} from "../src/liveCommute/apple/publicationPolicy.js";

const DECISION_AT = new Date("2026-09-12T07:40:00.000Z");
const SOURCE_AT = new Date("2026-09-12T07:39:30.000Z");

function epoch(value: string | Date): number {
  return Math.floor(new Date(value).getTime() / 1_000);
}

const CONFIG = createLiveActivityPublicationPolicyConfig({
  freshSourceLifetimeMilliseconds: 5 * 60_000,
  freshnessHeartbeatLeadMilliseconds: 90_000,
  minimumFreshnessPublicationIntervalMilliseconds: 60_000,
  minimumStaleDateLeadMilliseconds: 15_000,
  unknownUpdateReconciliation: "ENABLED",
});

function lineState(
  overrides: Partial<BlickLiveActivityLineContentStateV1> = {},
): BlickLiveActivityLineContentStateV1 {
  return {
    schemaVersion: 1,
    commuteKind: "LINE_DIRECTION",
    freshness: "FRESH",
    sourceFetchedAt: epoch(SOURCE_AT),
    departures: [
      {
        departureId: "departure-primary",
        lineDesignation: "13",
        direction: "Norsborg",
        destination: "T-Centralen",
        scheduledAt: epoch("2026-09-12T07:43:00.000Z"),
        expectedAt: epoch("2026-09-12T07:43:30.000Z"),
        effectiveAt: epoch("2026-09-12T07:43:30.000Z"),
        isCancelled: false,
        departureState: "EXPECTED",
        journeyState: "NORMAL",
        predictionState: "REALTIME",
      },
      {
        departureId: "departure-next",
        lineDesignation: "13",
        direction: "Norsborg",
        destination: "T-Centralen",
        scheduledAt: epoch("2026-09-12T07:50:00.000Z"),
        expectedAt: null,
        effectiveAt: epoch("2026-09-12T07:50:00.000Z"),
        isCancelled: false,
        departureState: "SCHEDULED",
        journeyState: "NORMAL",
        predictionState: null,
      },
    ],
    ...overrides,
  };
}

function exactState(
  overrides: Partial<BlickLiveActivityExactContentStateV1> = {},
): BlickLiveActivityExactContentStateV1 {
  return {
    schemaVersion: 1,
    commuteKind: "EXACT_DESTINATION",
    freshness: "FRESH",
    sourceFetchedAt: epoch(SOURCE_AT),
    journeys: [
      {
        journeyId: "journey-primary",
        role: "PRIMARY",
        originName: "Slussen",
        destinationName: "Solna centrum",
        departureAt: epoch("2026-09-12T07:43:00.000Z"),
        effectiveDepartureAt: epoch("2026-09-12T07:43:00.000Z"),
        arrivalAt: epoch("2026-09-12T08:05:00.000Z"),
        transferCount: 1,
        firstLeg: {
          transportMode: "METRO",
          lineDesignation: "13",
          direction: "Ropsten",
          originName: "Slussen",
          destinationName: "T-Centralen",
          departureAt: epoch("2026-09-12T07:43:00.000Z"),
          arrivalAt: epoch("2026-09-12T07:48:00.000Z"),
          isRealtime: true,
        },
      },
      {
        journeyId: "journey-next",
        role: "NEXT",
        originName: "Slussen",
        destinationName: "Solna centrum",
        departureAt: epoch("2026-09-12T07:50:00.000Z"),
        effectiveDepartureAt: epoch("2026-09-12T07:50:00.000Z"),
        arrivalAt: epoch("2026-09-12T08:12:00.000Z"),
        transferCount: 1,
        firstLeg: {
          transportMode: "METRO",
          lineDesignation: "13",
          direction: "Ropsten",
          originName: "Slussen",
          destinationName: "T-Centralen",
          departureAt: epoch("2026-09-12T07:50:00.000Z"),
          arrivalAt: epoch("2026-09-12T07:55:00.000Z"),
          isRealtime: false,
        },
      },
    ],
    ...overrides,
  };
}

function intent(
  contentState: BlickLiveActivityLineContentStateV1 | BlickLiveActivityExactContentStateV1,
  status: LiveActivityPublicationIntent["status"] =
    contentState.freshness === "FRESH" ? "READY" : "READY_STALE",
  config: LiveActivityPublicationPolicyConfig = CONFIG,
): LiveActivityPublicationIntent {
  return createLiveActivityPublicationIntent({
    status,
    contentState,
    config,
  });
}

function historyEntry(
  publicationIntent: LiveActivityPublicationIntent,
  overrides: Partial<LiveActivityPublicationHistoryEntry> = {},
): LiveActivityPublicationHistoryEntry {
  return {
    operation: "DIRECT_UPDATE",
    state: "ACCEPTED",
    eventTimestamp: epoch(DECISION_AT) - 60,
    publicationMetadata: {
      visibleContentFingerprint: publicationIntent.visibleContentFingerprint,
      sourceFetchedAt: new Date(publicationIntent.sourceFetchedAt),
      staleAt: new Date(publicationIntent.staleAt),
    },
    completedAt: new Date(DECISION_AT.getTime() - 60_000),
    retryNotBefore: null,
    ...overrides,
  };
}

function acceptedHistory(
  publicationIntent: LiveActivityPublicationIntent,
  overrides: Partial<LiveActivityPublicationHistoryEntry> = {},
): LiveActivityPublicationHistory {
  const accepted = historyEntry(publicationIntent, overrides);
  return {
    latestAcceptedAttempt: accepted,
    latestAttempt: accepted,
  };
}

function noHistory(
  latestAttempt: LiveActivityPublicationHistoryEntry | null = null,
): LiveActivityPublicationHistory {
  return { latestAcceptedAttempt: null, latestAttempt };
}

function policyInput(
  publicationIntent: LiveActivityPublicationIntent,
  history: LiveActivityPublicationHistory,
  overrides: Partial<LiveActivityPublicationPolicyInput> = {},
): LiveActivityPublicationPolicyInput {
  return {
    decisionAt: DECISION_AT,
    intent: publicationIntent,
    history,
    deliveryStrategy: "DIRECT_TOKEN",
    capability: "DIRECT_IOS18",
    frequentPushes: "ENABLED",
    startAlertAvailable: true,
    config: CONFIG,
    ...overrides,
    existingActivityKnown: overrides.existingActivityKnown ?? false,
  };
}

function expectContentUpdate(
  currentState:
    | BlickLiveActivityLineContentStateV1
    | BlickLiveActivityExactContentStateV1,
  acceptedState:
    | BlickLiveActivityLineContentStateV1
    | BlickLiveActivityExactContentStateV1,
): void {
  const current = intent(currentState);
  const accepted = intent(acceptedState);
  expect(
    decideLiveActivityPublication(policyInput(current, acceptedHistory(accepted))),
  ).toMatchObject({
    kind: "UPDATE_CONTENT",
    publicationMetadata: {
      visibleContentFingerprint: current.visibleContentFingerprint,
      sourceFetchedAt: current.sourceFetchedAt,
      staleAt:
        current.contentState.freshness === "FRESH" ? current.staleAt : null,
    },
  });
}

function lineWithFirstDeparture(
  changes: Partial<BlickLiveActivityLineContentStateV1["departures"][number]>,
): BlickLiveActivityLineContentStateV1 {
  const state = lineState();
  return {
    ...state,
    departures: [{ ...state.departures[0]!, ...changes }, state.departures[1]!],
  };
}

function exactWithFirstJourney(
  changes: Partial<BlickLiveActivityExactContentStateV1["journeys"][number]>,
): BlickLiveActivityExactContentStateV1 {
  const state = exactState();
  return {
    ...state,
    journeys: [{ ...state.journeys[0]!, ...changes }, state.journeys[1]!],
  };
}

describe("Live Activity visible-content fingerprint", () => {
  it("excludes sourceFetchedAt for both wire variants", () => {
    const line = lineState();
    const exact = exactState();
    expect(
      liveActivityVisibleFingerprint({
        ...line,
        sourceFetchedAt: line.sourceFetchedAt + 300,
      }),
    ).toBe(liveActivityVisibleFingerprint(line));
    expect(
      liveActivityVisibleFingerprint({
        ...exact,
        sourceFetchedAt: exact.sourceFetchedAt + 300,
      }),
    ).toBe(liveActivityVisibleFingerprint(exact));
  });

  it.each([
    ["schema version", () => ({ ...lineState(), schemaVersion: 2 } as never)],
    ["freshness", () => ({ ...lineState(), freshness: "STALE" as const })],
    ["departure identity", () => lineWithFirstDeparture({ departureId: "changed" })],
    ["line", () => lineWithFirstDeparture({ lineDesignation: "14" })],
    ["direction", () => lineWithFirstDeparture({ direction: "Fruängen" })],
    ["destination", () => lineWithFirstDeparture({ destination: "Liljeholmen" })],
    ["scheduled time", () => lineWithFirstDeparture({ scheduledAt: epoch(DECISION_AT) + 400 })],
    ["expected time", () => lineWithFirstDeparture({ expectedAt: epoch(DECISION_AT) + 401 })],
    ["effective time", () => lineWithFirstDeparture({ effectiveAt: epoch(DECISION_AT) + 402 })],
    ["cancellation", () => lineWithFirstDeparture({ isCancelled: true })],
    ["departure state", () => lineWithFirstDeparture({ departureState: "CANCELLED" })],
    ["journey state", () => lineWithFirstDeparture({ journeyState: "DEVIATED" })],
    ["prediction state", () => lineWithFirstDeparture({ predictionState: "SCHEDULED" })],
    [
      "visible order",
      () => {
        const state = lineState();
        return { ...state, departures: [...state.departures].reverse() };
      },
    ],
  ])("includes visible LINE %s", (_field, changed) => {
    expect(liveActivityVisibleFingerprint(changed())).not.toBe(
      liveActivityVisibleFingerprint(lineState()),
    );
  });

  it.each([
    ["schema version", () => ({ ...exactState(), schemaVersion: 2 } as never)],
    ["freshness", () => ({ ...exactState(), freshness: "STALE" as const })],
    ["journey identity", () => exactWithFirstJourney({ journeyId: "changed" })],
    ["role", () => exactWithFirstJourney({ role: "ALTERNATIVE" })],
    ["origin", () => exactWithFirstJourney({ originName: "Gamla stan" })],
    ["destination", () => exactWithFirstJourney({ destinationName: "Sundbyberg" })],
    ["departure time", () => exactWithFirstJourney({ departureAt: epoch(DECISION_AT) + 400 })],
    [
      "effective departure time",
      () => exactWithFirstJourney({ effectiveDepartureAt: epoch(DECISION_AT) + 401 }),
    ],
    ["arrival time", () => exactWithFirstJourney({ arrivalAt: epoch(DECISION_AT) + 800 })],
    ["transfer count", () => exactWithFirstJourney({ transferCount: 2 })],
    [
      "first-leg mode",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, transportMode: "BUS" },
        });
      },
    ],
    [
      "first-leg line",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, lineDesignation: "4" },
        });
      },
    ],
    [
      "first-leg direction",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, direction: "Akalla" },
        });
      },
    ],
    [
      "first-leg origin",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, originName: "Medborgarplatsen" },
        });
      },
    ],
    [
      "first-leg destination",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, destinationName: "Fridhemsplan" },
        });
      },
    ],
    [
      "first-leg departure",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, departureAt: epoch(DECISION_AT) + 410 },
        });
      },
    ],
    [
      "first-leg arrival",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, arrivalAt: epoch(DECISION_AT) + 510 },
        });
      },
    ],
    [
      "first-leg realtime state",
      () => {
        const state = exactState();
        return exactWithFirstJourney({
          firstLeg: { ...state.journeys[0]!.firstLeg, isRealtime: false },
        });
      },
    ],
    [
      "visible order",
      () => {
        const state = exactState();
        return { ...state, journeys: [...state.journeys].reverse() };
      },
    ],
  ])("includes visible EXACT %s", (_field, changed) => {
    expect(liveActivityVisibleFingerprint(changed())).not.toBe(
      liveActivityVisibleFingerprint(exactState()),
    );
  });

  it("includes commute kind", () => {
    expect(liveActivityVisibleFingerprint(lineState())).not.toBe(
      liveActivityVisibleFingerprint(exactState()),
    );
  });

  it("ignores changes in the three hidden LINE reserve rows after wire mapping", () => {
    const snapshot: Extract<LiveCommuteSnapshot, { kind: "LINE_DIRECTION" }> = {
      kind: "LINE_DIRECTION",
      freshness: "FRESH",
      sourceFetchedAt: SOURCE_AT.toISOString(),
      generatedAt: DECISION_AT.toISOString(),
      departures: Array.from({ length: 5 }, (_, index) => ({
        departureId: `departure-${index + 1}`,
        lineDesignation: "13",
        direction: "Norsborg",
        destination: "T-Centralen",
        scheduledTime: new Date(
          DECISION_AT.getTime() + (index + 3) * 60_000,
        ).toISOString(),
        expectedTime: null,
        effectiveTime: new Date(
          DECISION_AT.getTime() + (index + 3) * 60_000,
        ).toISOString(),
        isCancelled: false,
        state: "SCHEDULED",
        journeyState: "NORMAL",
        predictionState: null,
      })),
    };
    const changedReserve: typeof snapshot = {
      ...snapshot,
      departures: snapshot.departures.map((departure, index) =>
        index < 2
          ? departure
          : {
              ...departure,
              destination: `Hidden ${index}`,
              state: "EXPECTED",
              predictionState: "REALTIME",
            },
      ),
    };
    const first = mapLiveCommuteSnapshotToContentState(snapshot, DECISION_AT);
    const second = mapLiveCommuteSnapshotToContentState(
      changedReserve,
      DECISION_AT,
    );

    expect(first.commuteKind).toBe("LINE_DIRECTION");
    expect(
      first.commuteKind === "LINE_DIRECTION"
        ? first.departures.map(({ departureId }) => departureId)
        : [],
    ).toEqual(["departure-1", "departure-2"]);
    expect(liveActivityVisibleFingerprint(second)).toBe(
      liveActivityVisibleFingerprint(first),
    );
    const accepted = intent(first);
    expect(
      decideLiveActivityPublication(
        policyInput(intent(second), acceptedHistory(accepted)),
      ),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });

  it("is deterministic and does not mutate caller-owned wire state", () => {
    const state = lineState();
    const before = structuredClone(state);
    const first = liveActivityVisibleFingerprint(state);
    const second = liveActivityVisibleFingerprint(state);

    expect(first).toBe(second);
    expect(first).toMatch(/^[0-9a-f]{64}$/);
    expect(state).toEqual(before);
    expect(Object.isFrozen(state)).toBe(false);
    expect(Object.isFrozen(state.departures)).toBe(false);
  });
});

describe("Live Activity content and no-push policy", () => {
  it("does not push merely because an absolute-time countdown advances", () => {
    const current = intent(lineState());
    const history = acceptedHistory(current);
    for (const decisionAt of [
      new Date(DECISION_AT.getTime() + 30_000),
      new Date(DECISION_AT.getTime() + 60_000),
    ]) {
      expect(
        decideLiveActivityPublication(
          policyInput(current, history, { decisionAt }),
        ),
      ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
    }
  });

  it("does not classify a sourceFetchedAt-only change as a content update", () => {
    const accepted = intent(lineState());
    const current = intent(
      lineState({ sourceFetchedAt: accepted.contentState.sourceFetchedAt + 30 }),
    );
    const history = acceptedHistory(accepted, {
      publicationMetadata: {
        visibleContentFingerprint: accepted.visibleContentFingerprint,
        sourceFetchedAt: new Date(accepted.sourceFetchedAt),
        staleAt: new Date(DECISION_AT.getTime() + 10 * 60_000),
      },
    });

    expect(
      decideLiveActivityPublication(policyInput(current, history)),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });

  it("does not push identical visible EXACT content", () => {
    const current = intent(exactState());
    expect(
      decideLiveActivityPublication(
        policyInput(current, acceptedHistory(current)),
      ),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });

  it("publishes visible LINE timing, rollover, and cancellation changes", () => {
    const accepted = lineState();
    expectContentUpdate(
      lineWithFirstDeparture({
        expectedAt: accepted.departures[0]!.expectedAt! + 180,
        effectiveAt: accepted.departures[0]!.effectiveAt + 180,
      }),
      accepted,
    );
    expectContentUpdate(
      {
        ...accepted,
        departures: [
          accepted.departures[1]!,
          {
            ...accepted.departures[1]!,
            departureId: "departure-after-next",
            scheduledAt: accepted.departures[1]!.scheduledAt + 420,
            effectiveAt: accepted.departures[1]!.effectiveAt + 420,
          },
        ],
      },
      accepted,
    );
    expectContentUpdate(lineWithFirstDeparture({ isCancelled: true }), accepted);
  });

  it("publishes material journey role and timing changes", () => {
    const accepted = exactState();
    expectContentUpdate(
      {
        ...accepted,
        journeys: [
          { ...accepted.journeys[0]!, role: "NEXT" },
          { ...accepted.journeys[1]!, role: "PRIMARY" },
        ],
      },
      accepted,
    );
    expectContentUpdate(
      exactWithFirstJourney({
        departureAt: accepted.journeys[0]!.departureAt + 120,
        effectiveDepartureAt:
          accepted.journeys[0]!.effectiveDepartureAt + 120,
      }),
      accepted,
    );
  });

  it("publishes both FRESH-to-STALE and STALE-to-FRESH visible transitions", () => {
    const fresh = lineState();
    const stale = lineState({ freshness: "STALE" });
    expectContentUpdate(stale, fresh);
    expectContentUpdate(fresh, stale);
  });

  it("does not let visibly different but older source data overwrite accepted newer state", () => {
    const accepted = intent(
      lineState({ sourceFetchedAt: epoch(SOURCE_AT) + 30 }),
    );
    const current = intent(
      lineWithFirstDeparture({
        expectedAt: epoch("2026-09-12T07:46:00.000Z"),
        effectiveAt: epoch("2026-09-12T07:46:00.000Z"),
      }),
    );

    expect(
      decideLiveActivityPublication(
        policyInput(current, acceptedHistory(accepted)),
      ),
    ).toEqual({ kind: "NO_PUSH_STALE_SOURCE" });
  });

  it("does not republish already accepted unchanged stale state", () => {
    const stale = intent(lineState({ freshness: "STALE" }));
    expect(
      decideLiveActivityPublication(policyInput(stale, acceptedHistory(stale))),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });
});

describe("Live Activity START policy", () => {
  it.each([
    ["DIRECT_LEGACY", "DIRECT_LEGACY"],
    ["DIRECT_IOS18", "DIRECT_IOS_18"],
  ] as const)("starts fresh visible content for known %s capability", (capability, mode) => {
    const publicationIntent = intent(lineState());
    expect(
      decideLiveActivityPublication(
        policyInput(publicationIntent, noHistory(), { capability }),
      ),
    ).toMatchObject({
      kind: "START",
      mode: { kind: mode },
      publicationMetadata: {
        visibleContentFingerprint: publicationIntent.visibleContentFingerprint,
        sourceFetchedAt: publicationIntent.sourceFetchedAt,
        staleAt: null,
      },
    });
  });

  it("does not start from a stale fallback", () => {
    const stale = intent(lineState({ freshness: "STALE" }));
    expect(
      decideLiveActivityPublication(policyInput(stale, noHistory())),
    ).toEqual({ kind: "NO_PUSH_STALE_SOURCE" });
  });

  it("distinguishes an empty new START from an existing activity that needs END", () => {
    const empty = intent(lineState({ departures: [] }));
    expect(
      decideLiveActivityPublication(policyInput(empty, noHistory())),
    ).toEqual({ kind: "DEFER_START_CONTENT_UNAVAILABLE" });
    expect(
      decideLiveActivityPublication(
        policyInput(empty, noHistory(), { existingActivityKnown: true }),
      ),
    ).toEqual({ kind: "END_REQUIRED_BUT_UNAVAILABLE" });
  });

  it("uses UPDATE to establish history for an existing client activity", () => {
    const current = intent(lineState());
    expect(
      decideLiveActivityPublication(
        policyInput(current, noHistory(), { existingActivityKnown: true }),
      ),
    ).toMatchObject({ kind: "UPDATE_CONTENT" });

    const priorUpdate = historyEntry(current, {
      state: "REJECTED",
      publicationMetadata: null,
    });
    expect(
      decideLiveActivityPublication(
        policyInput(current, noHistory(priorUpdate)),
      ),
    ).toMatchObject({ kind: "UPDATE_CONTENT" });
  });

  it("defers unknown, broadcast-only, and alert-less start capability", () => {
    const current = intent(lineState());
    expect(
      decideLiveActivityPublication(
        policyInput(current, noHistory(), { capability: "UNKNOWN" }),
      ),
    ).toEqual({ kind: "DEFER_START_CAPABILITY_UNKNOWN" });
    expect(
      decideLiveActivityPublication(
        policyInput(current, noHistory(), { capability: "BROADCAST_CAPABLE" }),
      ),
    ).toEqual({ kind: "DEFER_BROADCAST" });
    expect(
      decideLiveActivityPublication(
        policyInput(current, noHistory(), { startAlertAvailable: false }),
      ),
    ).toEqual({ kind: "DEFER_START_ALERT_UNAVAILABLE" });
  });

  it("defers every broadcast binding before considering direct start", () => {
    const current = intent(lineState());
    expect(
      decideLiveActivityPublication(
        policyInput(current, noHistory(), {
          deliveryStrategy: "BROADCAST_CHANNEL",
        }),
      ),
    ).toEqual({ kind: "DEFER_BROADCAST" });
  });

  it("never retries an ambiguous START and reports active dispatch work separately", () => {
    const current = intent(lineState());
    const ambiguous = historyEntry(current, {
      operation: "START",
      state: "OUTCOME_UNKNOWN",
      publicationMetadata: null,
    });
    expect(
      decideLiveActivityPublication(policyInput(current, noHistory(ambiguous))),
    ).toEqual({ kind: "DEFER_OUTCOME_UNKNOWN" });

    const active = historyEntry(current, {
      operation: "START",
      state: "IN_FLIGHT",
      publicationMetadata: null,
      completedAt: null,
    });
    expect(
      decideLiveActivityPublication(policyInput(current, noHistory(active))),
    ).toEqual({ kind: "DEFER_DISPATCH_BUSY" });
  });

  it("fails closed when accepted history lacks publication metadata", () => {
    const current = intent(lineState());
    const legacyAccepted = historyEntry(current, { publicationMetadata: null });
    expect(
      decideLiveActivityPublication(
        policyInput(current, {
          latestAcceptedAttempt: legacyAccepted,
          latestAttempt: legacyAccepted,
        }),
      ),
    ).toEqual({ kind: "DEFER_PUBLICATION_HISTORY" });
  });

  it.each(["REJECTED", "ABORTED", "SUPERSEDED"] as const)(
    "does not request an UPDATE after a terminal %s END intent",
    (state) => {
      const current = intent(lineWithFirstDeparture({ destination: "Changed" }));
      const accepted = historyEntry(intent(lineState()));
      const ended = historyEntry(current, {
        operation: "DIRECT_END",
        state,
        eventTimestamp: accepted.eventTimestamp + 1,
      });
      expect(
        decideLiveActivityPublication(
          policyInput(current, {
            latestAcceptedAttempt: accepted,
            latestAttempt: ended,
          }),
        ),
      ).toEqual({ kind: "END_REQUIRED_BUT_UNAVAILABLE" });
    },
  );
});

describe("Live Activity freshness heartbeat policy", () => {
  const heartbeatDecision = (input: {
    frequentPushes?: LiveActivityFrequentPushState;
    acceptedSourceAt?: Date;
    currentSourceAt?: Date;
    acceptedStaleAt?: Date | null;
    acceptedCompletedAt?: Date | null;
    decisionAt?: Date;
    config?: LiveActivityPublicationPolicyConfig;
  } = {}) => {
    const decisionAt = input.decisionAt ?? new Date("2026-09-12T07:43:30.000Z");
    const acceptedSourceAt = input.acceptedSourceAt ?? SOURCE_AT;
    const currentSourceAt =
      input.currentSourceAt ?? new Date(acceptedSourceAt.getTime() + 60_000);
    const acceptedState = lineState({ sourceFetchedAt: epoch(acceptedSourceAt) });
    const currentState = lineState({ sourceFetchedAt: epoch(currentSourceAt) });
    const accepted = intent(acceptedState, "READY", input.config ?? CONFIG);
    const current = intent(currentState, "READY", input.config ?? CONFIG);
    const acceptedEntry = historyEntry(accepted, {
      eventTimestamp: epoch(decisionAt) - 120,
      publicationMetadata: {
        visibleContentFingerprint: accepted.visibleContentFingerprint,
        sourceFetchedAt: new Date(accepted.sourceFetchedAt),
        staleAt:
          input.acceptedStaleAt === undefined
            ? new Date(accepted.staleAt)
            : input.acceptedStaleAt,
      },
      completedAt:
        input.acceptedCompletedAt === undefined
          ? new Date(decisionAt.getTime() - 120_000)
          : input.acceptedCompletedAt,
    });
    return decideLiveActivityPublication(
      policyInput(
        current,
        { latestAcceptedAttempt: acceptedEntry, latestAttempt: acceptedEntry },
        {
          decisionAt,
          frequentPushes: input.frequentPushes ?? "ENABLED",
          config: input.config ?? CONFIG,
        },
      ),
    );
  };

  it("sends a heartbeat only for newer fresh source near expiry with enabled frequent pushes", () => {
    expect(heartbeatDecision()).toMatchObject({ kind: "UPDATE_FRESHNESS" });
  });

  it.each(["DISABLED", "UNKNOWN"] as const)(
    "does not use freshness-only budget when frequent pushes are %s",
    (frequentPushes) => {
      expect(heartbeatDecision({ frequentPushes })).toEqual({
        kind: "NO_PUSH_UNCHANGED",
      });
    },
  );

  it("does not heartbeat while the accepted stale date is safely far away", () => {
    expect(
      heartbeatDecision({
        acceptedStaleAt: new Date("2026-09-12T08:00:00.000Z"),
      }),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });

  it("rejects older source and requires a genuinely extended stale date", () => {
    expect(
      heartbeatDecision({
        acceptedSourceAt: new Date("2026-09-12T07:40:00.000Z"),
        currentSourceAt: new Date("2026-09-12T07:39:00.000Z"),
      }),
    ).toEqual({ kind: "NO_PUSH_STALE_SOURCE" });
    expect(
      heartbeatDecision({
        acceptedStaleAt: new Date("2026-09-12T07:50:00.000Z"),
      }),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });

  it("enforces the minimum accepted-publication interval", () => {
    const decisionAt = new Date("2026-09-12T07:43:30.000Z");
    expect(
      heartbeatDecision({
        decisionAt,
        acceptedCompletedAt: new Date(decisionAt.getTime() - 30_000),
      }),
    ).toEqual({ kind: "NO_PUSH_UNCHANGED" });
  });

  it("does not renew freshness without a recorded accepted stale date", () => {
    expect(heartbeatDecision({ acceptedStaleAt: null })).toEqual({
      kind: "NO_PUSH_UNCHANGED",
    });
  });

  it("refuses a fresh publication whose source-derived stale date is not safely future", () => {
    const decisionAt = new Date("2026-09-12T07:45:00.000Z");
    expect(
      heartbeatDecision({
        decisionAt,
        acceptedStaleAt: new Date(decisionAt.getTime() - 30_000),
        currentSourceAt: new Date("2026-09-12T07:40:00.000Z"),
      }),
    ).toEqual({ kind: "NO_PUSH_STALE_SOURCE" });
  });
});

describe("Live Activity unknown-update reconciliation", () => {
  function reconciliationDecision(enabled: boolean, decisionAt = DECISION_AT) {
    const current = intent(lineState());
    const accepted = historyEntry(current, {
      eventTimestamp: epoch(decisionAt) - 120,
      completedAt: new Date(decisionAt.getTime() - 120_000),
    });
    const unknown = historyEntry(current, {
      operation: "DIRECT_UPDATE",
      state: "OUTCOME_UNKNOWN",
      eventTimestamp: epoch(decisionAt) - 30,
      completedAt: new Date(decisionAt.getTime() - 30_000),
    });
    const config = createLiveActivityPublicationPolicyConfig({
      ...CONFIG,
      unknownUpdateReconciliation: enabled ? "ENABLED" : "DISABLED",
    });
    return decideLiveActivityPublication(
      policyInput(
        current,
        { latestAcceptedAttempt: accepted, latestAttempt: unknown },
        { decisionAt, config },
      ),
    );
  }

  it("requests an explicitly enabled newer reconciliation update", () => {
    expect(reconciliationDecision(true)).toMatchObject({
      kind: "UPDATE_RECONCILIATION",
    });
  });

  it("defers reconciliation when disabled or not strictly newer than the unknown event", () => {
    expect(reconciliationDecision(false)).toEqual({
      kind: "DEFER_OUTCOME_UNKNOWN",
    });
    const current = intent(lineState());
    const accepted = historyEntry(current, { eventTimestamp: epoch(DECISION_AT) - 60 });
    const unknown = historyEntry(current, {
      state: "OUTCOME_UNKNOWN",
      eventTimestamp: epoch(DECISION_AT),
    });
    expect(
      decideLiveActivityPublication(
        policyInput(current, {
          latestAcceptedAttempt: accepted,
          latestAttempt: unknown,
        }),
      ),
    ).toEqual({ kind: "DEFER_OUTCOME_UNKNOWN" });
  });

  it.each([
    ["ENABLED", "UPDATE_RECONCILIATION"],
    ["DISABLED", "DEFER_OUTCOME_UNKNOWN"],
  ] as const)(
    "handles accepted A, unknown B, current B with %s reconciliation",
    (unknownUpdateReconciliation, expectedKind) => {
      const acceptedIntent = intent(lineState());
      const unknownIntent = intent(
        lineWithFirstDeparture({ destination: "Changed in unknown update" }),
      );
      const accepted = historyEntry(acceptedIntent, {
        eventTimestamp: epoch(DECISION_AT) - 120,
      });
      const unknown = historyEntry(unknownIntent, {
        state: "OUTCOME_UNKNOWN",
        eventTimestamp: epoch(DECISION_AT) - 30,
      });
      const config = createLiveActivityPublicationPolicyConfig({
        ...CONFIG,
        unknownUpdateReconciliation,
      });
      expect(
        decideLiveActivityPublication(
          policyInput(
            unknownIntent,
            { latestAcceptedAttempt: accepted, latestAttempt: unknown },
            { config },
          ),
        ),
      ).toMatchObject({ kind: expectedKind });
    },
  );

  it("publishes genuinely newer content after an unknown UPDATE", () => {
    const acceptedIntent = intent(lineState());
    const unknownIntent = intent(
      lineWithFirstDeparture({ destination: "Unknown B" }),
    );
    const current = intent(lineWithFirstDeparture({ destination: "Authoritative C" }));
    const accepted = historyEntry(acceptedIntent, {
      eventTimestamp: epoch(DECISION_AT) - 120,
    });
    const unknown = historyEntry(unknownIntent, {
      state: "OUTCOME_UNKNOWN",
      eventTimestamp: epoch(DECISION_AT) - 30,
    });
    expect(
      decideLiveActivityPublication(
        policyInput(
          current,
          { latestAcceptedAttempt: accepted, latestAttempt: unknown },
          {
            config: createLiveActivityPublicationPolicyConfig({
              ...CONFIG,
              unknownUpdateReconciliation: "DISABLED",
            }),
          },
        ),
      ),
    ).toMatchObject({ kind: "UPDATE_CONTENT" });
  });
});

describe("Live Activity publication-policy validation", () => {
  it("accepts explicit zero values only for optional policy intervals", () => {
    expect(
      createLiveActivityPublicationPolicyConfig({
        freshSourceLifetimeMilliseconds: 1,
        freshnessHeartbeatLeadMilliseconds: 0,
        minimumFreshnessPublicationIntervalMilliseconds: 0,
        minimumStaleDateLeadMilliseconds: 0,
        unknownUpdateReconciliation: "DISABLED",
      }),
    ).toEqual({
      freshSourceLifetimeMilliseconds: 1,
      freshnessHeartbeatLeadMilliseconds: 0,
      minimumFreshnessPublicationIntervalMilliseconds: 0,
      minimumStaleDateLeadMilliseconds: 0,
      unknownUpdateReconciliation: "DISABLED",
    });
  });

  it.each([
    ["zero fresh lifetime", { freshSourceLifetimeMilliseconds: 0 }],
    ["negative heartbeat lead", { freshnessHeartbeatLeadMilliseconds: -1 }],
    [
      "fractional minimum interval",
      { minimumFreshnessPublicationIntervalMilliseconds: 0.5 },
    ],
    ["negative stale lead", { minimumStaleDateLeadMilliseconds: -1 }],
    [
      "unsafe fresh lifetime",
      { freshSourceLifetimeMilliseconds: Number.MAX_SAFE_INTEGER + 1 },
    ],
    ["invalid reconciliation", { unknownUpdateReconciliation: "SOMETIMES" }],
  ])("rejects %s", (_name, overrides) => {
    expect(() =>
      createLiveActivityPublicationPolicyConfig({
        ...CONFIG,
        ...overrides,
      } as LiveActivityPublicationPolicyConfig),
    ).toThrow();
  });

  it("rejects missing config, invalid content, and freshness/status disagreement", () => {
    expect(() =>
      createLiveActivityPublicationPolicyConfig(null as never),
    ).toThrow("config must be an object");
    expect(() =>
      createLiveActivityPublicationIntent({
        status: "READY",
        contentState: {
          ...lineState(),
          commuteKind: "INVALID",
        } as never,
        config: CONFIG,
      }),
    ).toThrow("commuteKind is invalid");
    expect(() =>
      createLiveActivityPublicationIntent({
        status: "READY_STALE",
        contentState: lineState(),
        config: CONFIG,
      }),
    ).toThrow("status and content freshness do not match");
  });

  it("derives staleAt from source freshness rather than the decision clock", () => {
    const publicationIntent = intent(lineState());
    expect(publicationIntent.staleAt).toEqual(
      new Date(SOURCE_AT.getTime() + CONFIG.freshSourceLifetimeMilliseconds),
    );
    expect(publicationIntent.staleAt.getTime()).not.toBe(
      DECISION_AT.getTime() + CONFIG.freshSourceLifetimeMilliseconds,
    );
  });
});
