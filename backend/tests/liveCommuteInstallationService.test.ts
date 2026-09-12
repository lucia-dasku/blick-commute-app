import { describe, expect, it, vi } from "vitest";
import {
  createLiveCommuteInstallationService,
  type IssuedLiveCommuteInstallation,
  type LiveCommuteConcreteSessionInput,
} from "../src/liveCommute/installationService.js";
import { InMemoryLiveCommuteSessionStore } from "../src/liveCommute/inMemoryLiveCommuteSessionStore.js";
import {
  createStoredLiveCommuteSession,
  deserializePersistedLiveCommuteQuery,
  liveCommuteSessionVersionRef,
  serializePersistedLiveCommuteQuery,
} from "../src/liveCommute/sessionStore.js";

const INITIAL_NOW = new Date("2026-09-12T06:00:00.000Z");
const STARTS_AT = new Date("2026-09-12T07:00:00.000Z");
const ENDS_AT = new Date("2026-09-12T08:00:00.000Z");
const UUIDS = [
  "00000000-0000-4000-8000-000000000001",
  "00000000-0000-4000-8000-000000000002",
  "00000000-0000-4000-8000-000000000003",
] as const;

function lineSession(
  overrides: Partial<LiveCommuteConcreteSessionInput> = {},
): LiveCommuteConcreteSessionInput {
  return {
    sessionId: "occurrence-1",
    routineId: "routine-1",
    startsAt: STARTS_AT,
    endsAt: ENDS_AT,
    query: {
      kind: "LINE_DIRECTION",
      siteId: 9192,
      transportMode: "BUS",
      lineId: 57,
      directionCode: 1,
    },
    ...overrides,
  };
}

function harness() {
  const store = new InMemoryLiveCommuteSessionStore();
  let now = new Date(INITIAL_NOW);
  let uuidIndex = 0;
  let secretByte = 1;
  const service = createLiveCommuteInstallationService(store, {
    now: () => new Date(now),
    randomUuid: () => UUIDS[uuidIndex++]!,
    randomBytes: (size) => Buffer.alloc(size, secretByte++),
  });
  return {
    store,
    service,
    setNow(value: Date) {
      now = new Date(value);
    },
  };
}

async function issue(
  value: ReturnType<typeof harness>,
): Promise<IssuedLiveCommuteInstallation> {
  return await value.service.registerInstallation();
}

describe("live commute installation ownership", () => {
  it("issues separate random identifiers and 32-byte bearer credentials", async () => {
    const value = harness();
    const first = await issue(value);
    const second = await issue(value);

    expect(first.installationId).not.toBe(second.installationId);
    expect(first.bearerCredential).not.toBe(second.bearerCredential);
    expect(Buffer.from(first.bearerCredential, "base64url")).toHaveLength(32);
    expect(Object.keys(first).sort()).toEqual(["bearerCredential", "installationId"]);
  });

  it("stores only the digest and omits authentication material from safe values", async () => {
    const value = harness();
    const authentication = await issue(value);
    const installation = await value.service.authenticateInstallation(authentication);
    const registered = await value.service.registerSession(
      authentication,
      lineSession(),
    );
    let persistedCredential = "";
    await value.store.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => {
        persistedCredential = (await transaction.getInstallation())!.credentialDigest;
      },
    );

    expect(persistedCredential).toMatch(/^[0-9a-f]{64}$/);
    expect(persistedCredential).not.toBe(authentication.bearerCredential);
    expect(JSON.stringify(installation)).not.toContain(authentication.bearerCredential);
    expect(JSON.stringify(installation)).not.toContain(persistedCredential);
    expect(JSON.stringify(registered.session)).not.toContain(authentication.bearerCredential);
    expect(JSON.stringify(registered.session)).not.toContain(persistedCredential);
  });

  it("sanitizes persistence failures without retaining credential diagnostics", async () => {
    const value = harness();
    const hiddenDigest = "a".repeat(64);
    const rawCredential = Buffer.alloc(32, 1).toString("base64url");
    const driverError = new Error("database host and query details");
    Object.defineProperty(driverError, "parameters", {
      value: [hiddenDigest],
      enumerable: false,
    });
    vi.spyOn(value.store, "createInstallation").mockRejectedValue(driverError);

    const error = await value.service.registerInstallation().catch((failure: unknown) => failure);

    expect(error).toMatchObject({
      code: "INSTALLATION_REGISTRATION_FAILED",
      message: "Installation registration failed",
    });
    expect(Reflect.ownKeys(error as object)).not.toContain("parameters");
    expect(JSON.stringify(Object.getOwnPropertyDescriptors(error as object))).not.toContain(
      hiddenDigest,
    );
    expect(String(error)).not.toContain(rawCredential);
  });

  it("rejects installation-ID-only access and wrong credentials with one sanitized error", async () => {
    const value = harness();
    const authentication = await issue(value);
    const wrongCredential = Buffer.alloc(32, 99).toString("base64url");

    await expect(
      value.service.authenticateInstallation({
        installationId: authentication.installationId,
      } as IssuedLiveCommuteInstallation),
    ).rejects.toMatchObject({
      code: "INSTALLATION_AUTHENTICATION_FAILED",
      message: "Installation authentication failed",
    });
    const wrongAuthentication = {
      installationId: authentication.installationId,
      bearerCredential: wrongCredential,
    };
    await expect(
      value.service.authenticateInstallation({
        ...wrongAuthentication,
      }),
    ).rejects.toMatchObject({
      code: "INSTALLATION_AUTHENTICATION_FAILED",
      message: "Installation authentication failed",
    });
    await expect(
      value.service.authenticateInstallation({
        installationId: 42,
        bearerCredential: wrongCredential,
      } as never),
    ).rejects.toMatchObject({
      code: "INSTALLATION_AUTHENTICATION_FAILED",
      message: "Installation authentication failed",
    });
    const error = await value.service
      .registerSession(
        wrongAuthentication,
        lineSession({
          query: {
            kind: "LINE_DIRECTION",
            siteId: 0,
            transportMode: "BUS",
            lineId: 57,
            directionCode: 1,
          },
        }),
      )
      .catch((failure: unknown) => failure);
    expect(error).toMatchObject({
      code: "INSTALLATION_AUTHENTICATION_FAILED",
      message: "Installation authentication failed",
    });
    expect(String(error)).not.toContain(authentication.installationId);
    expect(String(error)).not.toContain(wrongCredential);
  });

  it("scopes reads and mutations to the authenticated installation", async () => {
    const value = harness();
    const ownerA = await issue(value);
    const ownerB = await issue(value);
    await value.service.registerSession(ownerA, lineSession({ sessionId: "only-a" }));
    await value.service.registerSession(ownerB, lineSession({ sessionId: "only-b" }));

    expect(
      (await value.service.listSessions(ownerA)).map(({ session }) => session.sessionId),
    ).toEqual(["only-a"]);
    await expect(
      value.service.cancelSession(ownerA, {
        sessionId: "only-b",
        expectedRevision: 1,
      }),
    ).rejects.toMatchObject({ code: "SESSION_NOT_FOUND" });
    await expect(
      value.service.listSessions({
        installationId: ownerB.installationId,
        bearerCredential: ownerA.bearerCredential,
      }),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
  });

  it("persists revocation, invalidates registered sessions, and rejects later operations", async () => {
    const value = harness();
    const authentication = await issue(value);
    await value.service.registerSession(authentication, lineSession());
    value.setNow(new Date("2026-09-12T06:05:00.000Z"));

    const revoked = await value.service.revokeInstallation(authentication);
    const repeated = await value.service.revokeInstallation(authentication);

    expect(revoked.status).toBe("REVOKED");
    expect(revoked.installation.state).toBe("REVOKED");
    expect(repeated.status).toBe("UNCHANGED");
    await expect(value.service.listSessions(authentication)).rejects.toMatchObject({
      code: "INSTALLATION_AUTHENTICATION_FAILED",
    });
    await expect(
      value.service.registerSession(authentication, lineSession({ sessionId: "late" })),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });

    await value.store.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => {
        expect((await transaction.getInstallation())?.state).toBe("REVOKED");
        expect(await transaction.listSessions()).toMatchObject([
          { lifecycle: "CANCELLED", revision: 1 },
        ]);
      },
    );
  });

  it("serializes an in-flight registration with revocation", async () => {
    const value = harness();
    const authentication = await issue(value);

    const registration = value.service.registerSession(authentication, lineSession());
    const revocation = value.service.revokeInstallation(authentication);
    const [registered, revoked] = await Promise.all([registration, revocation]);

    expect(registered.status).toBe("REGISTERED");
    expect(revoked.status).toBe("REVOKED");
    expect(await value.store.listEligibleSessions(STARTS_AT)).toEqual([]);
  });

  it("serializes an in-flight replacement with revocation", async () => {
    const value = harness();
    const authentication = await issue(value);
    await value.service.registerSession(authentication, lineSession());

    const replacement = value.service.replaceSession(authentication, {
      ...lineSession({ endsAt: new Date("2026-09-12T08:30:00.000Z") }),
      expectedRevision: 1,
    });
    const revocation = value.service.revokeInstallation(authentication);
    await Promise.allSettled([replacement, revocation]);

    expect(await value.store.listEligibleSessions(STARTS_AT)).toEqual([]);
    await value.store.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => {
        expect((await transaction.getInstallation())?.state).toBe("REVOKED");
        expect(await transaction.listSessions()).toMatchObject([
          { lifecycle: "CANCELLED" },
        ]);
      },
    );
  });
});

describe("live commute concrete-session mutations", () => {
  it("deduplicates concurrent identical registration", async () => {
    const value = harness();
    const authentication = await issue(value);

    const results = await Promise.all([
      value.service.registerSession(authentication, lineSession()),
      value.service.registerSession(authentication, lineSession()),
    ]);

    expect(results.map(({ status }) => status).sort()).toEqual([
      "REGISTERED",
      "UNCHANGED",
    ]);
    expect(await value.service.listSessions(authentication)).toHaveLength(1);
  });

  it("rejects a conflicting registration replay without changing stored state", async () => {
    const value = harness();
    const authentication = await issue(value);
    await value.service.registerSession(authentication, lineSession());

    await expect(
      value.service.registerSession(
        authentication,
        lineSession({
          endsAt: new Date("2026-09-12T08:30:00.000Z"),
        }),
      ),
    ).rejects.toMatchObject({ code: "SESSION_REGISTRATION_CONFLICT" });
    expect((await value.service.listSessions(authentication))[0]).toMatchObject({
      revision: 1,
      session: { endsAt: ENDS_AT },
    });
  });

  it("replaces with compare-and-swap and recognizes an identical retry", async () => {
    const value = harness();
    const authentication = await issue(value);
    await value.service.registerSession(authentication, lineSession());
    value.setNow(new Date("2026-09-12T06:10:00.000Z"));
    const replacement = lineSession({
      endsAt: new Date("2026-09-12T08:30:00.000Z"),
    });

    const replaced = await value.service.replaceSession(authentication, {
      ...replacement,
      expectedRevision: 1,
    });
    const retry = await value.service.replaceSession(authentication, {
      ...replacement,
      expectedRevision: 1,
    });

    expect(replaced).toMatchObject({ status: "REPLACED", session: { revision: 2 } });
    expect(retry).toMatchObject({ status: "UNCHANGED", session: { revision: 2 } });
    await expect(
      value.service.replaceSession(authentication, {
        ...lineSession({ endsAt: new Date("2026-09-12T09:00:00.000Z") }),
        expectedRevision: 1,
      }),
    ).rejects.toMatchObject({ code: "SESSION_REVISION_CONFLICT" });
  });

  it("makes cancellation idempotent without allowing delayed registration resurrection", async () => {
    const value = harness();
    const authentication = await issue(value);
    await value.service.registerSession(authentication, lineSession());
    value.setNow(new Date("2026-09-12T06:15:00.000Z"));

    const cancelled = await value.service.cancelSession(authentication, {
      sessionId: "occurrence-1",
      expectedRevision: 1,
    });
    const repeated = await value.service.cancelSession(authentication, {
      sessionId: "occurrence-1",
      expectedRevision: 1,
    });
    const delayedRegistration = await value.service.registerSession(
      authentication,
      lineSession(),
    );

    expect(cancelled).toMatchObject({
      status: "CANCELLED",
      session: { lifecycle: "CANCELLED", revision: 1 },
    });
    expect(repeated.status).toBe("UNCHANGED");
    expect(delayedRegistration.status).toBe("ALREADY_CANCELLED");
    await expect(
      value.service.replaceSession(authentication, {
        ...lineSession(),
        expectedRevision: 1,
      }),
    ).rejects.toMatchObject({ code: "SESSION_CANCELLED" });
  });

  it("prevents an old cancellation from overwriting a newer edit", async () => {
    const value = harness();
    const authentication = await issue(value);
    await value.service.registerSession(authentication, lineSession());
    await value.service.replaceSession(authentication, {
      ...lineSession({ endsAt: new Date("2026-09-12T08:30:00.000Z") }),
      expectedRevision: 1,
    });

    await expect(
      value.service.cancelSession(authentication, {
        sessionId: "occurrence-1",
        expectedRevision: 1,
      }),
    ).rejects.toMatchObject({ code: "SESSION_REVISION_CONFLICT" });
    expect((await value.service.listSessions(authentication))[0]).toMatchObject({
      lifecycle: "REGISTERED",
      revision: 2,
    });
  });

  it("atomically rejects one of two concurrent overlapping registrations", async () => {
    const value = harness();
    const authentication = await issue(value);

    const results = await Promise.allSettled([
      value.service.registerSession(authentication, lineSession({ sessionId: "first" })),
      value.service.registerSession(
        authentication,
        lineSession({
          sessionId: "second",
          startsAt: new Date("2026-09-12T07:30:00.000Z"),
          endsAt: new Date("2026-09-12T08:30:00.000Z"),
        }),
      ),
    ]);

    expect(results.filter(({ status }) => status === "fulfilled")).toHaveLength(1);
    const rejected = results.find(({ status }) => status === "rejected");
    expect(rejected).toMatchObject({
      status: "rejected",
      reason: { code: "SESSION_OVERLAP" },
    });
    expect(await value.service.listSessions(authentication)).toHaveLength(1);
  });

  it("allows touching half-open windows", async () => {
    const value = harness();
    const authentication = await issue(value);

    await value.service.registerSession(authentication, lineSession({ sessionId: "first" }));
    await value.service.registerSession(
      authentication,
      lineSession({
        sessionId: "second",
        startsAt: ENDS_AT,
        endsAt: new Date("2026-09-12T09:00:00.000Z"),
      }),
    );

    expect(await value.service.listSessions(authentication)).toHaveLength(2);
  });
});

describe("live commute store reads and persisted query validation", () => {
  it("rejects an invalid persisted session window before it can be stored", () => {
    expect(() =>
      createStoredLiveCommuteSession({
        session: {
          installationId: UUIDS[0],
          ...lineSession({ endsAt: STARTS_AT }),
        } as never,
        lifecycle: "REGISTERED",
        revision: 1,
        createdAt: INITIAL_NOW,
        updatedAt: INITIAL_NOW,
        cancelledAt: null,
      }),
    ).toThrow("endsAt must be later than startsAt");
  });

  it("derives eligibility from the clock and revalidates exact versions", async () => {
    const value = harness();
    const authentication = await issue(value);
    const registered = await value.service.registerSession(authentication, lineSession());
    const reference = liveCommuteSessionVersionRef(registered.session);

    expect(await value.store.listEligibleSessions(new Date(STARTS_AT.getTime() - 1))).toEqual([]);
    expect(await value.store.listEligibleSessions(STARTS_AT)).toHaveLength(1);
    expect(await value.store.listEligibleSessions(ENDS_AT)).toEqual([]);
    expect(await value.store.revalidateSessionVersions([reference, reference])).toHaveLength(1);
    expect(
      await value.store.revalidateSessionVersions([{ ...reference, revision: 2 }]),
    ).toEqual([]);

    await value.service.cancelSession(authentication, {
      sessionId: reference.sessionId,
      expectedRevision: reference.revision,
    });
    expect(await value.store.revalidateSessionVersions([reference])).toEqual([]);
  });

  it("round-trips canonical exact queries and rejects malformed or noncanonical storage", () => {
    const canonical = {
      kind: "EXACT_DESTINATION" as const,
      originId: "A=1@O=Odenplan",
      destinationId: "A=1@O=Slussen",
      transportModes: ["METRO", "BUS"] as const,
      changesPreference: "BOTH" as const,
      searchUntil: new Date("2026-09-12T08:00:00.000Z"),
      searchMode: "NOW" as const,
      laterJourneyCount: 0 as const,
    };
    const persisted = serializePersistedLiveCommuteQuery(canonical);

    expect(deserializePersistedLiveCommuteQuery(persisted)).toEqual(canonical);
    expect(() =>
      deserializePersistedLiveCommuteQuery({ ...persisted, changesPreference: "INVALID" }),
    ).toThrow();
    expect(() =>
      deserializePersistedLiveCommuteQuery({ ...persisted, searchMode: "LEAVE_AT" }),
    ).toThrow("not canonical");
    expect(() =>
      deserializePersistedLiveCommuteQuery({ ...persisted, unexpected: true }),
    ).toThrow("shape is invalid");
    expect(() =>
      deserializePersistedLiveCommuteQuery({
        ...persisted,
        transportModes: ["BUS", "METRO"],
      }),
    ).toThrow("not canonical");
  });

  it("rolls back all in-memory transaction writes when a callback fails", async () => {
    const value = harness();
    const authentication = await issue(value);
    const registered = await value.service.registerSession(authentication, lineSession());

    await expect(
      value.store.withInstallationTransaction(
        authentication.installationId,
        async (transaction) => {
          await transaction.saveSession({
            ...registered.session,
            revision: 2,
          });
          throw new Error("synthetic failure");
        },
      ),
    ).rejects.toThrow("synthetic failure");
    expect((await value.service.listSessions(authentication))[0]?.revision).toBe(1);
  });
});
