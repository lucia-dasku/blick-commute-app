import { describe, expect, it } from "vitest";
import { InMemoryLiveCommuteSessionStore } from "../src/liveCommute/inMemoryLiveCommuteSessionStore.js";
import {
  createLiveCommuteInstallationService,
  type IssuedLiveCommuteInstallation,
} from "../src/liveCommute/installationService.js";
import { createLiveActivityDeliveryService } from "../src/liveCommute/apple/deliveryService.js";
import { InMemoryLiveActivityDeliveryStore } from "../src/liveCommute/apple/inMemoryLiveActivityDeliveryStore.js";
import { InMemoryLiveActivityDispatchStore } from "../src/liveCommute/apple/inMemoryLiveActivityDispatchStore.js";
import { createAes256GcmActivityKitTokenProtector } from "../src/liveCommute/apple/tokenProtection.js";

const ACTIVE_AT = new Date("2026-09-12T07:30:00.000Z");
const STARTS_AT = new Date("2026-09-12T07:00:00.000Z");
const ENDS_AT = new Date("2026-09-12T08:00:00.000Z");
const EVENT = 1_789_198_200;
const FINGERPRINT = "a".repeat(64);
const VISIBLE_FINGERPRINT = "b".repeat(64);
const SOURCE_FETCHED_AT = new Date("2026-09-12T07:29:00.000Z");
const STALE_AT = new Date("2026-09-12T07:34:00.000Z");

const INSTALLATION_IDS = [
  "00000000-0000-4000-8000-000000000001",
  "00000000-0000-4000-8000-000000000002",
] as const;
const BINDING_IDS = [
  "10000000-0000-4000-8000-000000000001",
  "10000000-0000-4000-8000-000000000002",
] as const;

function dispatchUuid(value: number): string {
  return `20000000-0000-4000-8000-${value.toString().padStart(12, "0")}`;
}

function requestUuid(value: number): string {
  return `30000000-0000-4000-8000-${value.toString().padStart(12, "0")}`;
}

function harness() {
  const coreStore = new InMemoryLiveCommuteSessionStore();
  const deliveryStore = new InMemoryLiveActivityDeliveryStore(coreStore);
  let installationIndex = 0;
  let bindingIndex = 0;
  let credentialByte = 0x31;
  let nonceByte = 0x51;
  const installationService = createLiveCommuteInstallationService(coreStore, {
    now: () => new Date(ACTIVE_AT),
    randomUuid: () => INSTALLATION_IDS[installationIndex++]!,
    randomBytes: (size) => Buffer.alloc(size, credentialByte++),
  });
  const protector = createAes256GcmActivityKitTokenProtector(
    Buffer.alloc(32, 0x41),
    { randomBytes: (size) => Buffer.alloc(size, nonceByte++) },
  );
  const deliveryService = createLiveActivityDeliveryService(deliveryStore, protector, {
    now: () => new Date(ACTIVE_AT),
    randomUuid: () => BINDING_IDS[bindingIndex++]!,
  });
  const dispatchStore = new InMemoryLiveActivityDispatchStore(deliveryStore);
  return {
    deliveryStore,
    installationService,
    deliveryService,
    dispatchStore,
  };
}

async function setupDirect(
  value: ReturnType<typeof harness>,
  withUpdateToken: boolean,
) {
  const authentication = await value.installationService.registerInstallation();
  await value.installationService.registerSession(authentication, {
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
  });
  await value.deliveryService.registerPushToStartToken(authentication, {
    token: Buffer.from("synthetic-start-token"),
    clientGeneration: 1,
    environment: "SANDBOX",
  });
  const binding = await value.deliveryService.createDeliveryBinding(authentication, {
    sessionId: "occurrence-1",
    sessionRevision: 1,
    strategy: "DIRECT_TOKEN",
  });
  if (withUpdateToken) {
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: Buffer.from("synthetic-update-token-one"),
      clientGeneration: 1,
      environment: "SANDBOX",
    });
  }
  return { authentication, bindingId: binding.binding.bindingId };
}

function reserveInput(
  authentication: IssuedLiveCommuteInstallation,
  bindingId: string,
  operation: "START" | "DIRECT_UPDATE" | "DIRECT_END",
  eventTimestamp: number,
  sequence: number,
) {
  return {
    installationId: authentication.installationId,
    bindingId,
    sessionRevision: 1,
    operation,
    eventTimestamp,
    dispatchId: dispatchUuid(sequence),
    apnsRequestId: requestUuid(sequence),
    reservedAt: ACTIVE_AT,
  } as const;
}

describe("durable direct-dispatch state machine", () => {
  it("batch-loads delivery bindings for exact session-version references", async () => {
    const value = harness();
    const first = await setupDirect(value, true);
    const second = await setupDirect(value, false);

    const bindings = await value.deliveryStore.listDeliveryBindingsForSessionVersions([
      {
        installationId: second.authentication.installationId,
        sessionId: "occurrence-1",
        revision: 1,
      },
      {
        installationId: first.authentication.installationId,
        sessionId: "occurrence-1",
        revision: 2,
      },
      {
        installationId: first.authentication.installationId,
        sessionId: "occurrence-1",
        revision: 1,
      },
    ]);

    expect(bindings.map(({ binding }) => binding.bindingId)).toEqual([
      first.bindingId,
      second.bindingId,
    ]);
    expect(bindings.map(({ hasUpdateTokenHistory }) => hasUpdateTokenHistory)).toEqual([
      true,
      false,
    ]);
    expect(Object.isFrozen(bindings)).toBe(true);
  });

  it("returns loaded-empty history and preserves accepted publication metadata", async () => {
    const value = harness();
    const { authentication, bindingId } = await setupDirect(value, true);
    const references = [
      {
        bindingId,
        installationId: authentication.installationId,
        sessionRevision: 1,
      },
      {
        bindingId,
        installationId: authentication.installationId,
        sessionRevision: 2,
      },
    ] as const;

    await expect(
      value.dispatchStore.listDirectDispatchHistoryForBindings(references),
    ).resolves.toEqual([
      { ...references[0], latestAcceptedAttempt: null, latestAttempt: null },
      { ...references[1], latestAcceptedAttempt: null, latestAttempt: null },
    ]);

    const accepted = await value.dispatchStore.reserveDirectDispatch({
      ...reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT, 1),
      publicationMetadata: {
        visibleContentFingerprint: VISIBLE_FINGERPRINT,
        sourceFetchedAt: SOURCE_FETCHED_AT,
        staleAt: STALE_AT,
      },
    });
    if (accepted.status !== "RESERVED") throw new Error("expected reservation");
    await value.dispatchStore.claimDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: accepted.attempt.dispatchId,
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await value.dispatchStore.completeDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: accepted.attempt.dispatchId,
      completedAt: ACTIVE_AT,
      state: "ACCEPTED",
      apnsStatus: 200,
      apnsReason: null,
      retryAdvice: "NO_RETRY",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });

    const later = await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT + 1, 2),
    );
    if (later.status !== "RESERVED") throw new Error("expected reservation");
    await value.dispatchStore.abortDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: later.attempt.dispatchId,
      completedAt: ACTIVE_AT,
      retryAdvice: "NO_RETRY",
    });

    const [history, wrongRevision] =
      await value.dispatchStore.listDirectDispatchHistoryForBindings(references);
    expect(history).toMatchObject({
      ...references[0],
      latestAcceptedAttempt: {
        dispatchId: accepted.attempt.dispatchId,
        state: "ACCEPTED",
        publicationMetadata: {
          visibleContentFingerprint: VISIBLE_FINGERPRINT,
          sourceFetchedAt: SOURCE_FETCHED_AT,
          staleAt: STALE_AT,
        },
      },
      latestAttempt: { dispatchId: later.attempt.dispatchId, state: "ABORTED" },
    });
    expect(wrongRevision).toEqual({
      ...references[1],
      latestAcceptedAttempt: null,
      latestAttempt: null,
    });

    const legacy = harness();
    const legacySetup = await setupDirect(legacy, true);
    const legacyReserved = await legacy.dispatchStore.reserveDirectDispatch(
      reserveInput(
        legacySetup.authentication,
        legacySetup.bindingId,
        "DIRECT_UPDATE",
        EVENT,
        1,
      ),
    );
    if (legacyReserved.status !== "RESERVED") throw new Error("expected reservation");
    await legacy.dispatchStore.claimDirectDispatch({
      installationId: legacySetup.authentication.installationId,
      dispatchId: legacyReserved.attempt.dispatchId,
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await legacy.dispatchStore.completeDirectDispatch({
      installationId: legacySetup.authentication.installationId,
      dispatchId: legacyReserved.attempt.dispatchId,
      completedAt: ACTIVE_AT,
      state: "ACCEPTED",
      apnsStatus: 200,
      apnsReason: null,
      retryAdvice: "NO_RETRY",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
    const [legacyHistory] =
      await legacy.dispatchStore.listDirectDispatchHistoryForBindings([
        {
          bindingId: legacySetup.bindingId,
          installationId: legacySetup.authentication.installationId,
          sessionRevision: 1,
        },
      ]);
    expect(legacyHistory?.latestAcceptedAttempt).toMatchObject({
      state: "ACCEPTED",
      publicationMetadata: null,
    });
  });

  it("reports stale/same-second events and supersedes only an unsent update", async () => {
    const value = harness();
    const { authentication, bindingId } = await setupDirect(value, true);
    const first = await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT, 1),
    );
    expect(first.status).toBe("RESERVED");

    await expect(
      value.dispatchStore.reserveDirectDispatch(
        reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT, 2),
      ),
    ).resolves.toEqual({
      status: "SAME_SECOND",
      lastReservedEventTimestamp: EVENT,
    });
    await expect(
      value.dispatchStore.reserveDirectDispatch(
        reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT - 1, 3),
      ),
    ).resolves.toEqual({
      status: "STALE_EVENT",
      lastReservedEventTimestamp: EVENT,
    });

    const newer = await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT + 1, 4),
    );
    expect(newer).toMatchObject({
      status: "RESERVED",
      supersededDispatchId: dispatchUuid(1),
    });
    await expect(
      value.dispatchStore.claimDirectDispatch({
        installationId: authentication.installationId,
        dispatchId: dispatchUuid(1),
        payloadFingerprint: FINGERPRINT,
        claimedAt: ACTIVE_AT,
      }),
    ).resolves.toMatchObject({ status: "SUPERSEDED" });
    await expect(
      value.dispatchStore.claimDirectDispatch({
        installationId: authentication.installationId,
        dispatchId: dispatchUuid(4),
        payloadFingerprint: FINGERPRINT,
        claimedAt: ACTIVE_AT,
      }),
    ).resolves.toMatchObject({ status: "CLAIMED" });
  });

  it("keeps one in-flight slot and records accepted post-send authority", async () => {
    const value = harness();
    const { authentication, bindingId } = await setupDirect(value, true);
    const reserved = await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT, 1),
    );
    expect(reserved.status).toBe("RESERVED");
    await value.dispatchStore.claimDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: dispatchUuid(1),
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await expect(
      value.dispatchStore.reserveDirectDispatch(
        reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT + 1, 2),
      ),
    ).resolves.toMatchObject({ status: "BUSY", blockingDispatchId: dispatchUuid(1) });

    const completed = await value.dispatchStore.completeDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: dispatchUuid(1),
      completedAt: ACTIVE_AT,
      state: "ACCEPTED",
      apnsStatus: 200,
      apnsReason: null,
      retryAdvice: "NO_RETRY",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
    expect(completed).toMatchObject({
      status: "COMPLETED",
      attempt: { state: "ACCEPTED", postSendAuthority: "MATCHED" },
    });
  });

  it("uses END terminal intent to supersede a pending update and reject later updates", async () => {
    const value = harness();
    const { authentication, bindingId } = await setupDirect(value, true);
    await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT, 1),
    );
    const ending = await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_END", EVENT + 1, 2),
    );
    expect(ending).toMatchObject({
      status: "RESERVED",
      supersededDispatchId: dispatchUuid(1),
    });
    await value.dispatchStore.abortDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: dispatchUuid(2),
      completedAt: ACTIVE_AT,
      retryAdvice: "OPERATOR_CONFIGURATION_REQUIRED",
    });
    await expect(
      value.dispatchStore.reserveDirectDispatch(
        reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT + 2, 3),
      ),
    ).resolves.toEqual({ status: "TERMINAL_INTENT", blockingDispatchId: null });
  });

  it("blocks duplicate accepted or unknown START while allowing a definitive correction", async () => {
    const acceptedValue = harness();
    const acceptedSetup = await setupDirect(acceptedValue, false);
    await acceptedValue.dispatchStore.reserveDirectDispatch(
      reserveInput(
        acceptedSetup.authentication,
        acceptedSetup.bindingId,
        "START",
        EVENT,
        1,
      ),
    );
    await acceptedValue.dispatchStore.claimDirectDispatch({
      installationId: acceptedSetup.authentication.installationId,
      dispatchId: dispatchUuid(1),
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await acceptedValue.dispatchStore.completeDirectDispatch({
      installationId: acceptedSetup.authentication.installationId,
      dispatchId: dispatchUuid(1),
      completedAt: ACTIVE_AT,
      state: "OUTCOME_UNKNOWN",
      apnsStatus: null,
      apnsReason: null,
      retryAdvice: "OUTCOME_UNKNOWN",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
    await expect(
      acceptedValue.dispatchStore.reserveDirectDispatch(
        reserveInput(
          acceptedSetup.authentication,
          acceptedSetup.bindingId,
          "START",
          EVENT + 1,
          2,
        ),
      ),
    ).resolves.toMatchObject({ status: "START_BLOCKED" });
    await acceptedValue.deliveryService.registerUpdateToken(
      acceptedSetup.authentication,
      {
        bindingId: acceptedSetup.bindingId,
        token: Buffer.from("synthetic-update-token-after-start"),
        clientGeneration: 1,
        environment: "SANDBOX",
      },
    );
    await expect(
      acceptedValue.dispatchStore.reserveDirectDispatch(
        reserveInput(
          acceptedSetup.authentication,
          acceptedSetup.bindingId,
          "DIRECT_UPDATE",
          EVENT + 2,
          3,
        ),
      ),
    ).resolves.toMatchObject({ status: "RESERVED" });

    const rejectedValue = harness();
    const rejectedSetup = await setupDirect(rejectedValue, false);
    await rejectedValue.dispatchStore.reserveDirectDispatch(
      reserveInput(
        rejectedSetup.authentication,
        rejectedSetup.bindingId,
        "START",
        EVENT,
        1,
      ),
    );
    await rejectedValue.dispatchStore.claimDirectDispatch({
      installationId: rejectedSetup.authentication.installationId,
      dispatchId: dispatchUuid(1),
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await rejectedValue.dispatchStore.completeDirectDispatch({
      installationId: rejectedSetup.authentication.installationId,
      dispatchId: dispatchUuid(1),
      completedAt: ACTIVE_AT,
      state: "REJECTED",
      apnsStatus: 400,
      apnsReason: "BadPayload",
      retryAdvice: "PERMANENT_PAYLOAD_FAILURE",
      retryNotBefore: null,
      invalidateExactTokenGeneration: false,
    });
    await expect(
      rejectedValue.dispatchStore.reserveDirectDispatch(
        reserveInput(
          rejectedSetup.authentication,
          rejectedSetup.bindingId,
          "START",
          EVENT + 1,
          2,
        ),
      ),
    ).resolves.toMatchObject({ status: "RESERVED" });
  });

  it("aborts at claim after cancellation and marks accepted send authority changes", async () => {
    const cancelledValue = harness();
    const cancelledSetup = await setupDirect(cancelledValue, true);
    await cancelledValue.dispatchStore.reserveDirectDispatch(
      reserveInput(
        cancelledSetup.authentication,
        cancelledSetup.bindingId,
        "DIRECT_UPDATE",
        EVENT,
        1,
      ),
    );
    await cancelledValue.installationService.cancelSession(
      cancelledSetup.authentication,
      { sessionId: "occurrence-1", expectedRevision: 1 },
    );
    await expect(
      cancelledValue.dispatchStore.claimDirectDispatch({
        installationId: cancelledSetup.authentication.installationId,
        dispatchId: dispatchUuid(1),
        payloadFingerprint: FINGERPRINT,
        claimedAt: ACTIVE_AT,
      }),
    ).resolves.toMatchObject({ status: "ABORTED", attempt: { state: "ABORTED" } });

    const changedValue = harness();
    const changedSetup = await setupDirect(changedValue, true);
    await changedValue.dispatchStore.reserveDirectDispatch(
      reserveInput(
        changedSetup.authentication,
        changedSetup.bindingId,
        "DIRECT_UPDATE",
        EVENT,
        1,
      ),
    );
    await changedValue.dispatchStore.claimDirectDispatch({
      installationId: changedSetup.authentication.installationId,
      dispatchId: dispatchUuid(1),
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await changedValue.installationService.cancelSession(changedSetup.authentication, {
      sessionId: "occurrence-1",
      expectedRevision: 1,
    });
    await expect(
      changedValue.dispatchStore.completeDirectDispatch({
        installationId: changedSetup.authentication.installationId,
        dispatchId: dispatchUuid(1),
        completedAt: ACTIVE_AT,
        state: "ACCEPTED",
        apnsStatus: 200,
        apnsReason: null,
        retryAdvice: "NO_RETRY",
        retryNotBefore: null,
        invalidateExactTokenGeneration: false,
      }),
    ).resolves.toMatchObject({
      status: "COMPLETED",
      attempt: { postSendAuthority: "CHANGED" },
    });
  });

  it("never invalidates a newer update-token generation after a delayed terminal result", async () => {
    const value = harness();
    const { authentication, bindingId } = await setupDirect(value, true);
    await value.dispatchStore.reserveDirectDispatch(
      reserveInput(authentication, bindingId, "DIRECT_UPDATE", EVENT, 1),
    );
    await value.dispatchStore.claimDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: dispatchUuid(1),
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId,
      token: Buffer.from("synthetic-update-token-two"),
      clientGeneration: 2,
      environment: "SANDBOX",
    });
    const completed = await value.dispatchStore.completeDirectDispatch({
      installationId: authentication.installationId,
      dispatchId: dispatchUuid(1),
      completedAt: ACTIVE_AT,
      state: "REJECTED",
      apnsStatus: 410,
      apnsReason: "Unregistered",
      retryAdvice: "PERMANENT_DESTINATION_FAILURE",
      retryNotBefore: null,
      invalidateExactTokenGeneration: true,
    });
    expect(completed).toMatchObject({
      status: "COMPLETED",
      attempt: { tokenInvalidationOutcome: "GENERATION_NO_LONGER_CURRENT" },
    });
    const latest = await value.deliveryStore.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => await transaction.getLatestUpdateToken(bindingId),
    );
    expect(latest).toMatchObject({ clientGeneration: 2, lifecycle: "CURRENT" });
  });
});
