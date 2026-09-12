import { describe, expect, it } from "vitest";
import departuresFixture from "../fixtures/slTransportDeparturesSlussen.sample.json" with { type: "json" };
import {
  LiveCommuteAuthoritativeVerificationError,
  runStoredLiveCommuteTick,
} from "../src/liveCommute/coordinator.js";
import {
  createLiveCommuteSession,
  type LineDirectionLiveQueryInput,
  type LiveCommuteSession,
} from "../src/liveCommute/model.js";
import {
  createStoredLiveCommuteSession,
  liveCommuteSessionIdentityKey,
  type LiveCommuteInstallationTransaction,
  type LiveCommuteSessionStore,
  type LiveCommuteSessionVersionRef,
  type StoredLiveCommuteInstallation,
  type StoredLiveCommuteSession,
} from "../src/liveCommute/sessionStore.js";
import type { LiveCommutePreviousSnapshotSource } from "../src/liveCommute/ports.js";
import type { SlJourneyPlannerClient } from "../src/services/slJourneyPlannerClient.js";
import type { SlTransportClient } from "../src/services/slTransportClient.js";
import type { RawDeparturesResponse } from "../src/services/upstreamTypes.js";

const NOW = new Date("2026-07-04T15:32:00.000Z");
const STARTS_AT = new Date("2026-07-04T15:00:00.000Z");
const ENDS_AT = new Date("2026-07-04T16:30:00.000Z");

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
  readonly reject: (reason: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

function lineQuery(
  overrides: Partial<LineDirectionLiveQueryInput> = {},
): LineDirectionLiveQueryInput {
  return {
    kind: "LINE_DIRECTION",
    siteId: 9192,
    transportMode: "BUS",
    lineId: 57,
    directionCode: 2,
    ...overrides,
  };
}

function liveSession(options: {
  installationId?: string;
  sessionId?: string;
  endsAt?: Date;
  query?: LineDirectionLiveQueryInput;
} = {}): LiveCommuteSession {
  return createLiveCommuteSession({
    installationId: options.installationId ?? "installation-1",
    sessionId: options.sessionId ?? "session-1",
    routineId: "routine-1",
    startsAt: STARTS_AT,
    endsAt: options.endsAt ?? ENDS_AT,
    query: options.query ?? lineQuery(),
  });
}

function storedSession(
  session: LiveCommuteSession,
  revision = 1,
): StoredLiveCommuteSession {
  return createStoredLiveCommuteSession({
    session,
    lifecycle: "REGISTERED",
    revision,
    createdAt: STARTS_AT,
    updatedAt: STARTS_AT,
    cancelledAt: null,
  });
}

class ControlledSessionStore implements LiveCommuteSessionStore {
  private readonly records = new Map<string, StoredLiveCommuteSession>();
  private readonly activeInstallations = new Set<string>();
  revalidationGate: Deferred<void> | undefined;
  readonly revalidationStarted = deferred<void>();
  revalidationCalls = 0;
  revalidatedReferences: readonly LiveCommuteSessionVersionRef[] = [];
  failList = false;
  failRevalidation = false;

  constructor(records: readonly StoredLiveCommuteSession[]) {
    for (const record of records) {
      this.records.set(liveCommuteSessionIdentityKey(record.session), record);
      this.activeInstallations.add(record.session.installationId);
    }
  }

  async createInstallation(_installation: StoredLiveCommuteInstallation): Promise<boolean> {
    throw new Error("not used by coordinator tests");
  }

  async withInstallationTransaction<T>(
    _installationId: string,
    _operation: (transaction: LiveCommuteInstallationTransaction) => Promise<T>,
  ): Promise<T> {
    throw new Error("not used by coordinator tests");
  }

  async listEligibleSessions(at: Date): Promise<readonly StoredLiveCommuteSession[]> {
    if (this.failList) throw new Error("database connection includes secret details");
    const atMillis = at.getTime();
    return [...this.records.values()].filter(
      (record) =>
        this.activeInstallations.has(record.session.installationId) &&
        record.lifecycle === "REGISTERED" &&
        record.session.startsAt.getTime() <= atMillis &&
        record.session.endsAt.getTime() > atMillis,
    );
  }

  async revalidateSessionVersions(
    references: readonly LiveCommuteSessionVersionRef[],
  ): Promise<readonly StoredLiveCommuteSession[]> {
    this.revalidationCalls++;
    this.revalidatedReferences = references;
    this.revalidationStarted.resolve();
    await this.revalidationGate?.promise;
    if (this.failRevalidation) throw new Error("database connection includes secret details");
    return references.flatMap((reference) => {
      const record = this.records.get(liveCommuteSessionIdentityKey(reference));
      return record != null &&
        this.activeInstallations.has(record.session.installationId) &&
        record.lifecycle === "REGISTERED" &&
        record.revision === reference.revision
        ? [record]
        : [];
    });
  }

  cancel(session: LiveCommuteSession): void {
    const key = liveCommuteSessionIdentityKey(session);
    const record = this.records.get(key);
    if (record == null) throw new Error("missing session fixture");
    this.records.set(
      key,
      createStoredLiveCommuteSession({
        ...record,
        lifecycle: "CANCELLED",
        updatedAt: NOW,
        cancelledAt: NOW,
      }),
    );
  }

  replace(session: LiveCommuteSession, replacement: LiveCommuteSession): void {
    const key = liveCommuteSessionIdentityKey(session);
    const record = this.records.get(key);
    if (record == null) throw new Error("missing session fixture");
    this.records.set(
      key,
      createStoredLiveCommuteSession({
        ...record,
        session: replacement,
        revision: record.revision + 1,
        updatedAt: NOW,
      }),
    );
  }

  revoke(installationId: string): void {
    this.activeInstallations.delete(installationId);
  }

  delete(session: LiveCommuteSession): void {
    this.records.delete(liveCommuteSessionIdentityKey(session));
  }
}

function unusedJourneyClient(): SlJourneyPlannerClient {
  return {
    async searchStops() {
      return [];
    },
    async trips() {
      throw new Error("journey acquisition was not expected");
    },
  };
}

function immediateTransport(calls: number[]): SlTransportClient {
  return {
    async fetchAllSites() {
      return [];
    },
    async fetchStopPoints() {
      return [];
    },
    async fetchDepartures(siteId) {
      calls.push(siteId);
      return departuresFixture as unknown as RawDeparturesResponse;
    },
  };
}

function gatedTransport(): {
  readonly client: SlTransportClient;
  readonly requested: Promise<number>;
  readonly calls: number[];
  resolve(): void;
} {
  const response = deferred<RawDeparturesResponse>();
  const requested = deferred<number>();
  const calls: number[] = [];
  return {
    calls,
    requested: requested.promise,
    client: {
      async fetchAllSites() {
        return [];
      },
      async fetchStopPoints() {
        return [];
      },
      fetchDepartures(siteId) {
        calls.push(siteId);
        requested.resolve(siteId);
        return response.promise;
      },
    },
    resolve() {
      response.resolve(departuresFixture as unknown as RawDeparturesResponse);
    },
  };
}

describe("stored live commute tick coordination", () => {
  it.each([
    ["cancelled", (store: ControlledSessionStore, session: LiveCommuteSession) => store.cancel(session)],
    [
      "replaced",
      (store: ControlledSessionStore, session: LiveCommuteSession) =>
        store.replace(
          session,
          liveSession({
            query: lineQuery({ siteId: 9300, lineId: 3, directionCode: 1 }),
          }),
        ),
    ],
    [
      "revoked",
      (store: ControlledSessionStore, session: LiveCommuteSession) =>
        store.revoke(session.installationId),
    ],
    ["deleted", (store: ControlledSessionStore, session: LiveCommuteSession) => store.delete(session)],
  ])("excludes a session %s during delayed acquisition", async (_state, mutate) => {
    const session = liveSession();
    const store = new ControlledSessionStore([storedSession(session)]);
    const transport = gatedTransport();
    const pending = runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    expect(await transport.requested).toBe(9192);
    mutate(store, session);
    transport.resolve();
    const result = await pending;

    expect(transport.calls).toEqual([9192]);
    expect(result.acquisitions).toHaveLength(1);
    expect(result.publications).toEqual([]);
  });

  it("reads the final clock after delayed authoritative validation and removes expired state", async () => {
    const session = liveSession();
    const store = new ControlledSessionStore([storedSession(session)]);
    store.revalidationGate = deferred<void>();
    const calls: number[] = [];
    let currentTime = NOW;
    const pending = runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(currentTime),
      transportClient: immediateTransport(calls),
      journeyClient: unusedJourneyClient(),
    });

    await store.revalidationStarted.promise;
    currentTime = new Date("2026-07-04T15:35:00.000Z");
    store.revalidationGate.resolve();
    const result = await pending;
    const publication = result.publications[0];

    expect(calls).toEqual([9192]);
    expect(publication?.status).toBe("READY");
    if (
      publication == null ||
      publication.status !== "READY" ||
      publication.snapshot.kind !== "LINE_DIRECTION"
    ) {
      throw new Error("expected ready line publication");
    }
    expect(publication.authorityCheckCompletedAt).toBe(currentTime.toISOString());
    expect(publication.group.validatedAt).toBe(currentTime.toISOString());
    expect(publication.snapshot.generatedAt).toBe(currentTime.toISOString());
    expect(publication.snapshot.sourceFetchedAt).toBe(NOW.toISOString());
    expect(publication.snapshot.departures).toEqual([]);
  });

  it("omits a session that expires while authoritative validation is delayed", async () => {
    const session = liveSession({ endsAt: new Date("2026-07-04T15:34:00.000Z") });
    const store = new ControlledSessionStore([storedSession(session)]);
    store.revalidationGate = deferred<void>();
    let currentTime = NOW;
    const pending = runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(currentTime),
      transportClient: immediateTransport([]),
      journeyClient: unusedJourneyClient(),
    });

    await store.revalidationStarted.promise;
    currentTime = new Date("2026-07-04T15:35:00.000Z");
    store.revalidationGate.resolve();
    const result = await pending;

    expect(result.acquisitions).toHaveLength(1);
    expect(result.publications).toEqual([]);
  });

  it("fails closed with a sanitized error when the final authority check fails", async () => {
    const session = liveSession();
    const store = new ControlledSessionStore([storedSession(session)]);
    store.failRevalidation = true;
    const calls: number[] = [];

    const result = runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(NOW),
      transportClient: immediateTransport(calls),
      journeyClient: unusedJourneyClient(),
    });

    await expect(result).rejects.toEqual(expect.any(LiveCommuteAuthoritativeVerificationError));
    await expect(result).rejects.toMatchObject({
      code: "LIVE_COMMUTE_AUTHORITATIVE_VERIFICATION_FAILED",
      message: "Live commute session authority could not be verified",
    });
    await expect(result).rejects.not.toHaveProperty("cause");
    expect(calls).toEqual([9192]);
  });

  it("preserves fresh acquired state when optional snapshot history fails", async () => {
    const session = liveSession();
    const store = new ControlledSessionStore([storedSession(session, 7)]);
    const previousSnapshots: LiveCommutePreviousSnapshotSource = {
      getPreviousSnapshot() {
        throw new Error("optional history unavailable");
      },
    };

    const result = await runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(NOW),
      transportClient: immediateTransport([]),
      journeyClient: unusedJourneyClient(),
      previousSnapshots,
    });
    const publication = result.publications[0];

    expect(publication?.status).toBe("READY");
    if (publication?.status !== "READY") throw new Error("expected ready publication");
    expect(publication.contentChanged).toBe(true);
    expect(publication.sessionVersions).toEqual([{
      installationId: session.installationId,
      sessionId: session.sessionId,
      revision: 7,
    }]);
    expect(Object.isFrozen(publication)).toBe(true);
    expect(Object.isFrozen(publication.sessionVersions)).toBe(true);
    expect(JSON.stringify(result)).not.toMatch(/credential|digest|optional history unavailable/i);
  });

  it("keeps one shared acquisition and publication when one recipient is cancelled", async () => {
    const cancelled = liveSession({ installationId: "installation-1", sessionId: "session-1" });
    const retained = liveSession({ installationId: "installation-2", sessionId: "session-2" });
    const store = new ControlledSessionStore([
      storedSession(cancelled, 3),
      storedSession(retained, 11),
    ]);
    const transport = gatedTransport();
    const pending = runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(NOW),
      transportClient: transport.client,
      journeyClient: unusedJourneyClient(),
    });

    await transport.requested;
    store.cancel(cancelled);
    transport.resolve();
    const result = await pending;

    expect(transport.calls).toEqual([9192]);
    expect(store.revalidationCalls).toBe(1);
    expect(store.revalidatedReferences).toHaveLength(2);
    expect(result.acquisitions).toHaveLength(1);
    expect(result.publications).toHaveLength(1);
    expect(result.publications[0]?.group.sessions.map(({ sessionId }) => sessionId)).toEqual([
      retained.sessionId,
    ]);
    expect(result.publications[0]?.sessionVersions).toEqual([{
      installationId: retained.installationId,
      sessionId: retained.sessionId,
      revision: 11,
    }]);
  });

  it("does no transit work when the initial authoritative session read fails", async () => {
    const store = new ControlledSessionStore([storedSession(liveSession())]);
    store.failList = true;
    const calls: number[] = [];
    const result = runStoredLiveCommuteTick({
      sessionStore: store,
      now: () => new Date(NOW),
      transportClient: immediateTransport(calls),
      journeyClient: unusedJourneyClient(),
    });

    await expect(result).rejects.toMatchObject({
      code: "LIVE_COMMUTE_AUTHORITATIVE_VERIFICATION_FAILED",
      message: "Live commute session authority could not be verified",
    });
    expect(calls).toEqual([]);
  });

  it("fails safely before transit work when a store returns a malformed query", async () => {
    const valid = storedSession(liveSession());
    const malformed = {
      ...valid,
      session: {
        ...valid.session,
        query: {
          kind: "LINE_DIRECTION",
          siteId: 0,
          transportMode: "BUS",
          lineId: 57,
          directionCode: 2,
        },
      },
    } as unknown as StoredLiveCommuteSession;
    const store = new ControlledSessionStore([malformed]);
    const calls: number[] = [];

    await expect(
      runStoredLiveCommuteTick({
        sessionStore: store,
        now: () => new Date(NOW),
        transportClient: immediateTransport(calls),
        journeyClient: unusedJourneyClient(),
      }),
    ).rejects.toMatchObject({
      code: "LIVE_COMMUTE_AUTHORITATIVE_VERIFICATION_FAILED",
      message: "Live commute session authority could not be verified",
    });
    expect(calls).toEqual([]);
  });
});
