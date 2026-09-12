import { describe, expect, it, vi } from "vitest";
import { createLiveCommuteSession, liveCommutePublicationKey } from "../src/liveCommute/model.js";
import type { StoredLiveCommuteTickResult } from "../src/liveCommute/coordinator.js";
import type { LiveCommuteSnapshot } from "../src/liveCommute/snapshot.js";
import type { LiveCommuteSessionVersionRef } from "../src/liveCommute/sessionStore.js";
import type { LiveActivityDeliveryBinding } from "../src/liveCommute/apple/deliveryModel.js";
import type { AuthoritativeReadyLiveCommutePublication } from "../src/liveCommute/apple/deliveryPlan.js";
import type { LiveActivityDirectDispatchInput } from "../src/liveCommute/apple/directDispatcher.js";
import type {
  LiveActivityDispatchBindingReference,
  LiveActivityDirectDispatchHistory,
  LiveActivityPublicationMetadata,
} from "../src/liveCommute/apple/dispatchModel.js";
import { mapLiveCommuteSnapshotToContentState } from "../src/liveCommute/apple/liveActivityWireContract.js";
import {
  createLiveActivityPublicationPolicyConfig,
  liveActivityVisibleFingerprint,
} from "../src/liveCommute/apple/publicationPolicy.js";
import {
  runLiveActivityPublicationCycle,
  type RunLiveActivityPublicationCycleInput,
} from "../src/liveCommute/apple/publicationWorker.js";

const GENERATED_AT = new Date("2026-09-12T10:00:00.000Z");
const SOURCE_AT = new Date("2026-09-12T09:59:30.000Z");
const ENDS_AT = new Date("2026-09-12T11:00:00.000Z");
const QUERY = Object.freeze({
  kind: "LINE_DIRECTION" as const,
  siteId: 9001,
  transportMode: "METRO",
  lineId: 13,
  directionCode: 1,
});
const PUBLICATION_KEY = liveCommutePublicationKey(QUERY);
const POLICY_CONFIG = createLiveActivityPublicationPolicyConfig({
  freshSourceLifetimeMilliseconds: 5 * 60_000,
  freshnessHeartbeatLeadMilliseconds: 60_000,
  minimumFreshnessPublicationIntervalMilliseconds: 30_000,
  minimumStaleDateLeadMilliseconds: 15_000,
  unknownUpdateReconciliation: "ENABLED",
});

const REFERENCES: readonly LiveCommuteSessionVersionRef[] = Object.freeze([
  Object.freeze({ installationId: "installation-1", sessionId: "session-1", revision: 1 }),
  Object.freeze({ installationId: "installation-2", sessionId: "session-2", revision: 2 }),
]);

const BINDING_IDS = Object.freeze([
  "31e40315-30a9-42f7-88c8-634374de2a66",
  "a6cc7ad1-a2f9-4fc1-9a80-24a5d70c9ce8",
  "78792115-1210-4c75-b15f-09f757abb6fb",
]);

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function lineSnapshot(
  overrides: Partial<Extract<LiveCommuteSnapshot, { readonly kind: "LINE_DIRECTION" }>> = {},
): Extract<LiveCommuteSnapshot, { readonly kind: "LINE_DIRECTION" }> {
  return {
    kind: "LINE_DIRECTION",
    freshness: "FRESH",
    sourceFetchedAt: SOURCE_AT.toISOString(),
    generatedAt: SOURCE_AT.toISOString(),
    departures: [
      {
        departureId: "metro-13-1",
        lineDesignation: "13",
        direction: "Northbound",
        destination: "Ropsten",
        scheduledTime: "2026-09-12T10:03:00.000Z",
        expectedTime: null,
        effectiveTime: "2026-09-12T10:03:00.000Z",
        isCancelled: false,
        state: "EXPECTED",
        journeyState: "NORMALPROGRESS",
        predictionState: null,
      },
      {
        departureId: "metro-13-2",
        lineDesignation: "13",
        direction: "Northbound",
        destination: "Ropsten",
        scheduledTime: "2026-09-12T10:08:00.000Z",
        expectedTime: null,
        effectiveTime: "2026-09-12T10:08:00.000Z",
        isCancelled: false,
        state: "EXPECTED",
        journeyState: "NORMALPROGRESS",
        predictionState: null,
      },
    ],
    ...overrides,
  };
}

function publication(
  references: readonly LiveCommuteSessionVersionRef[] = REFERENCES,
  snapshot: LiveCommuteSnapshot = lineSnapshot(),
): AuthoritativeReadyLiveCommutePublication {
  const status = snapshot.freshness === "FRESH" ? "READY" : "READY_STALE";
  return {
    status,
    group: {
      key: PUBLICATION_KEY,
      acquisitionKey: "synthetic-acquisition" as never,
      query: QUERY,
      sessions: references.map((reference) =>
        createLiveCommuteSession({
          installationId: reference.installationId,
          sessionId: reference.sessionId,
          routineId: `routine-${reference.sessionId}`,
          startsAt: new Date("2026-09-12T09:00:00.000Z"),
          endsAt: ENDS_AT,
          query: QUERY,
        }),
      ),
      validatedAt: GENERATED_AT.toISOString(),
    },
    snapshot,
    contentChanged: true,
    sessionVersions: references,
    authorityCheckCompletedAt: GENERATED_AT.toISOString(),
  } as AuthoritativeReadyLiveCommutePublication;
}

function tick(
  ready: readonly AuthoritativeReadyLiveCommutePublication[],
): StoredLiveCommuteTickResult {
  return Object.freeze({
    plannedAt: "2026-09-12T09:59:29.000Z",
    acquisitions: Object.freeze([]),
    publications: Object.freeze(ready),
  });
}

function binding(
  reference: LiveCommuteSessionVersionRef,
  index: number,
  strategy: LiveActivityDeliveryBinding["strategy"] = "DIRECT_TOKEN",
): LiveActivityDeliveryBinding {
  return Object.freeze({
    bindingId: BINDING_IDS[index] as string,
    installationId: reference.installationId,
    sessionId: reference.sessionId,
    sessionRevision: reference.revision,
    strategy,
    lifecycle: "PENDING_START",
    appleActivityId: null,
    createdAt: new Date("2026-09-12T09:00:00.000Z"),
    updatedAt: new Date("2026-09-12T09:00:00.000Z"),
    endedAt: null,
    invalidatedAt: null,
  });
}

function history(
  target: LiveActivityDeliveryBinding,
  metadata: LiveActivityPublicationMetadata | null = null,
): LiveActivityDirectDispatchHistory {
  const accepted =
    metadata == null
      ? null
      : ({
          bindingId: target.bindingId,
          installationId: target.installationId,
          sessionRevision: target.sessionRevision,
          operation: "DIRECT_UPDATE",
          state: "ACCEPTED",
          eventTimestamp: Math.floor((GENERATED_AT.getTime() - 60_000) / 1_000),
          publicationMetadata: metadata,
          completedAt: new Date(GENERATED_AT.getTime() - 60_000),
          retryNotBefore: null,
        } as NonNullable<LiveActivityDirectDispatchHistory["latestAcceptedAttempt"]>);
  return {
    bindingId: target.bindingId,
    installationId: target.installationId,
    sessionRevision: target.sessionRevision,
    latestAcceptedAttempt: accepted,
    latestAttempt: accepted,
  };
}

function acceptedMetadata(
  snapshot: LiveCommuteSnapshot,
  fingerprintOverride?: string,
): LiveActivityPublicationMetadata {
  const state = mapLiveCommuteSnapshotToContentState(snapshot, GENERATED_AT);
  return Object.freeze({
    visibleContentFingerprint:
      fingerprintOverride ?? liveActivityVisibleFingerprint(state),
    sourceFetchedAt: new Date(state.sourceFetchedAt * 1_000),
    staleAt: new Date(state.sourceFetchedAt * 1_000 + 5 * 60_000),
  });
}

function recordedAccepted() {
  return {
    outcome: "RECORDED" as const,
    attempt: { state: "ACCEPTED" } as never,
    networkAttempted: true as const,
  };
}

function baseInput(options: {
  readonly publications: readonly AuthoritativeReadyLiveCommutePublication[];
  readonly bindings: readonly LiveActivityDeliveryBinding[];
  readonly histories?: readonly LiveActivityDirectDispatchHistory[];
  readonly dispatch?: RunLiveActivityPublicationCycleInput["dispatcher"]["dispatch"];
  readonly concurrency?: number;
  readonly failHistory?: boolean;
  readonly updateTokenHistoryBindingIds?: readonly string[];
}): RunLiveActivityPublicationCycleInput {
  return {
    sessionStore: {} as never,
    now: () => new Date(GENERATED_AT),
    transportClient: {} as never,
    journeyClient: {} as never,
    runStoredTick: vi.fn(async () => tick(options.publications)),
    deliveryStore: {
      listDeliveryBindingsForSessionVersions: vi.fn(async () =>
        options.bindings.map((binding) => ({
          binding,
          hasUpdateTokenHistory:
            options.updateTokenHistoryBindingIds?.includes(binding.bindingId) ?? false,
        })),
      ),
    },
    dispatchStore: {
      listDirectDispatchHistoryForBindings: vi.fn(async (
        references: readonly LiveActivityDispatchBindingReference[],
      ) => {
        if (options.failHistory) throw new Error("postgresql://secret-history");
        return (
          options.histories ??
          references.map((reference) => ({
            ...reference,
            latestAcceptedAttempt: null,
            latestAttempt: null,
          }))
        );
      }),
    },
    dispatcher: {
      dispatch: options.dispatch ?? vi.fn(async () => recordedAccepted()),
    },
    clientStateProvider: {
      getClientPublicationState: vi.fn(async (target: LiveActivityDeliveryBinding) => ({
        capability:
          target.installationId === "installation-2"
            ? "DIRECT_IOS18"
            : "DIRECT_LEGACY",
        frequentPushes: "ENABLED",
      } as const)),
    },
    startAlertProvider: {
      createStartAlert: vi.fn(async () => ({
        title: "Synthetic localized title",
        body: "Synthetic localized body",
      })),
    },
    priorityPolicy: {
      priorityFor: vi.fn(async () => 5 as const),
    },
    policyConfig: POLICY_CONFIG,
    dispatchConcurrency: options.concurrency ?? 2,
  };
}

describe("one-shot Live Activity publication cycle", () => {
  it("runs the stored tick once and reuses one mapped/fingerprinted group state across direct starts", async () => {
    const current = publication();
    const targets = REFERENCES.map((reference, index) => binding(reference, index));
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => recordedAccepted());
    const input = baseInput({ publications: [current], bindings: targets, dispatch });
    const originalSnapshot = structuredClone(current.snapshot);

    const summary = await runLiveActivityPublicationCycle(input);

    expect(input.runStoredTick).toHaveBeenCalledTimes(1);
    expect(input.deliveryStore.listDeliveryBindingsForSessionVersions).toHaveBeenCalledTimes(1);
    expect(input.dispatchStore.listDirectDispatchHistoryForBindings).toHaveBeenCalledTimes(1);
    expect(dispatch).toHaveBeenCalledTimes(2);
    const first = dispatch.mock.calls[0]?.[0];
    const second = dispatch.mock.calls[1]?.[0];
    expect(first?.preparedPublication).toBe(second?.preparedPublication);
    expect(first?.preparedPublication?.intent.visibleContentFingerprint).toBe(
      second?.preparedPublication?.intent.visibleContentFingerprint,
    );
    expect(first).toMatchObject({ operation: "START", mode: { kind: "DIRECT_LEGACY" } });
    expect(second).toMatchObject({ operation: "START", mode: { kind: "DIRECT_IOS_18" } });
    expect(first?.generatedAt.toISOString()).toBe(GENERATED_AT.toISOString());
    expect(first?.preparedPublication?.intent.sourceFetchedAt.toISOString()).toBe(
      SOURCE_AT.toISOString(),
    );
    expect(first?.generatedAt.getTime()).not.toBe(
      first?.preparedPublication?.intent.sourceFetchedAt.getTime(),
    );
    expect(current.snapshot).toEqual(originalSnapshot);
    expect(summary).toMatchObject({
      readyPublicationGroupCount: 1,
      bindingCount: 2,
      historyLookupFailed: false,
    });
  });

  it("does not start stale state and explicitly defers broadcast without dispatch", async () => {
    const stale = publication(
      REFERENCES,
      lineSnapshot({ freshness: "STALE" }),
    );
    const targets = [
      binding(REFERENCES[0] as LiveCommuteSessionVersionRef, 0),
      binding(REFERENCES[1] as LiveCommuteSessionVersionRef, 1, "BROADCAST_CHANNEL"),
    ];
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => recordedAccepted());

    const summary = await runLiveActivityPublicationCycle(
      baseInput({ publications: [stale], bindings: targets, dispatch }),
    );

    expect(dispatch).not.toHaveBeenCalled();
    expect(summary.bindings.map(({ decision }) => decision)).toEqual([
      "NO_PUSH_STALE_SOURCE",
      "DEFER_BROADCAST",
    ]);
  });

  it("uses a first UPDATE when nonsecret token history proves the activity exists", async () => {
    const current = publication([REFERENCES[0] as LiveCommuteSessionVersionRef]);
    const target = binding(REFERENCES[0] as LiveCommuteSessionVersionRef, 0);
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => recordedAccepted());
    const input = baseInput({
      publications: [current],
      bindings: [target],
      updateTokenHistoryBindingIds: [target.bindingId],
      dispatch,
    });

    const summary = await runLiveActivityPublicationCycle(input);

    expect(dispatch).toHaveBeenCalledWith(
      expect.objectContaining({ operation: "DIRECT_UPDATE" }),
    );
    expect(input.startAlertProvider.createStartAlert).not.toHaveBeenCalled();
    expect(summary.bindings[0]).toMatchObject({ decision: "UPDATE_CONTENT" });
  });

  it("defers an invalid localized START alert before reservation", async () => {
    const current = publication([REFERENCES[0] as LiveCommuteSessionVersionRef]);
    const target = binding(REFERENCES[0] as LiveCommuteSessionVersionRef, 0);
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => recordedAccepted());
    const input: RunLiveActivityPublicationCycleInput = {
      ...baseInput({ publications: [current], bindings: [target], dispatch }),
      startAlertProvider: {
        createStartAlert: vi.fn(async () => ({ title: "", body: "" })),
      },
    };

    const summary = await runLiveActivityPublicationCycle(input);

    expect(dispatch).not.toHaveBeenCalled();
    expect(summary.bindings[0]).toEqual({
      bindingId: target.bindingId,
      sessionRevision: target.sessionRevision,
      decision: "DEFER_START_ALERT_UNAVAILABLE",
      dispatch: null,
    });
  });

  it("uses durable visible history so a newer source and later countdown wall time alone do not push", async () => {
    const newerSourceSnapshot = lineSnapshot({
      sourceFetchedAt: "2026-09-12T09:59:45.000Z",
      generatedAt: "2026-09-12T09:59:45.000Z",
    });
    const current = publication([REFERENCES[0] as LiveCommuteSessionVersionRef], newerSourceSnapshot);
    const target = binding(REFERENCES[0] as LiveCommuteSessionVersionRef, 0);
    const oldSnapshot = lineSnapshot();
    const accepted = Object.freeze({
      ...acceptedMetadata(oldSnapshot),
      staleAt: new Date("2026-09-12T10:04:30.000Z"),
    });
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => recordedAccepted());

    const summary = await runLiveActivityPublicationCycle(
      baseInput({
        publications: [current],
        bindings: [target],
        histories: [history(target, accepted)],
        dispatch,
      }),
    );

    expect(dispatch).not.toHaveBeenCalled();
    expect(summary.bindings[0]).toMatchObject({
      decision: "NO_PUSH_UNCHANGED",
      dispatch: null,
    });
  });

  it("isolates recipient dispatch failures and returns no sensitive material", async () => {
    const changed = lineSnapshot({
      departures: lineSnapshot().departures.map((departure, index) =>
        index === 0
          ? {
              ...departure,
              expectedTime: "2026-09-12T10:05:00.000Z",
              effectiveTime: "2026-09-12T10:05:00.000Z",
            }
          : departure,
      ),
    });
    const current = publication(REFERENCES, changed);
    const targets = REFERENCES.map((reference, index) => binding(reference, index));
    const oldMetadata = acceptedMetadata(lineSnapshot());
    const dispatch = vi.fn(async (request: LiveActivityDirectDispatchInput) => {
      if (request.installationId === "installation-1") {
        throw new Error("synthetic.header.jwt-secret device-token-secret");
      }
      return recordedAccepted();
    });

    const summary = await runLiveActivityPublicationCycle(
      baseInput({
        publications: [current],
        bindings: targets,
        histories: targets.map((target) => history(target, oldMetadata)),
        dispatch,
      }),
    );

    expect(dispatch).toHaveBeenCalledTimes(2);
    expect(summary.bindings.map(({ dispatch: result }) => result?.outcome)).toEqual([
      "CALL_FAILED",
      "RECORDED",
    ]);
    expect(summary.bindings.every(({ decision }) => decision === "UPDATE_CONTENT")).toBe(true);
    const serialized = JSON.stringify(summary);
    expect(serialized).not.toContain("jwt-secret");
    expect(serialized).not.toContain("device-token-secret");
    expect(serialized).not.toContain("content-state");
  });

  it("fails every affected binding closed when batch history cannot be loaded", async () => {
    const current = publication();
    const targets = REFERENCES.map((reference, index) => binding(reference, index));
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => recordedAccepted());

    const summary = await runLiveActivityPublicationCycle(
      baseInput({
        publications: [current],
        bindings: targets,
        dispatch,
        failHistory: true,
      }),
    );

    expect(summary.historyLookupFailed).toBe(true);
    expect(summary.bindings.map(({ decision }) => decision)).toEqual([
      "DEFER_PUBLICATION_HISTORY",
      "DEFER_PUBLICATION_HISTORY",
    ]);
    expect(dispatch).not.toHaveBeenCalled();
    expect(JSON.stringify(summary)).not.toContain("secret-history");
  });

  it("enforces the injected dispatch concurrency bound without a timer or global mutex", async () => {
    const references = Object.freeze([
      ...REFERENCES,
      Object.freeze({ installationId: "installation-3", sessionId: "session-3", revision: 3 }),
    ]);
    const current = publication(references);
    const targets = references.map((reference, index) => binding(reference, index));
    const twoStarted = deferred<void>();
    const thirdStarted = deferred<void>();
    const releases: Array<() => void> = [];
    let active = 0;
    let maximumActive = 0;
    let started = 0;
    const dispatch = vi.fn(async (_request: LiveActivityDirectDispatchInput) => {
      active += 1;
      started += 1;
      maximumActive = Math.max(maximumActive, active);
      const gate = deferred<void>();
      releases.push(() => gate.resolve());
      if (started === 2) twoStarted.resolve();
      if (started === 3) thirdStarted.resolve();
      await gate.promise;
      active -= 1;
      return recordedAccepted();
    });

    const cycle = runLiveActivityPublicationCycle(
      baseInput({
        publications: [current],
        bindings: targets,
        dispatch,
        concurrency: 2,
      }),
    );
    await twoStarted.promise;
    expect(dispatch).toHaveBeenCalledTimes(2);
    expect(maximumActive).toBe(2);
    releases[0]?.();
    releases[1]?.();
    await thirdStarted.promise;
    expect(maximumActive).toBe(2);
    releases[2]?.();

    const summary = await cycle;
    expect(summary.bindingCount).toBe(3);
    expect(dispatch).toHaveBeenCalledTimes(3);
  });
});
