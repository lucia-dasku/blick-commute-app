import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createLiveCommuteInstallationService,
  type IssuedLiveCommuteInstallation,
  type LiveCommuteConcreteSessionInput,
} from "../src/liveCommute/installationService.js";
import { InMemoryLiveCommuteSessionStore } from "../src/liveCommute/inMemoryLiveCommuteSessionStore.js";
import {
  createLiveActivityDeliveryResolver,
} from "../src/liveCommute/apple/deliveryResolver.js";
import {
  createLiveActivityDeliveryService,
} from "../src/liveCommute/apple/deliveryService.js";
import { InMemoryLiveActivityDeliveryStore } from "../src/liveCommute/apple/inMemoryLiveActivityDeliveryStore.js";
import {
  createStoredPushToStartToken,
  liveActivityUpdateProtectionContext,
} from "../src/liveCommute/apple/deliveryModel.js";
import type { LiveActivityDeliveryStore } from "../src/liveCommute/apple/deliveryStore.js";
import {
  createAes256GcmActivityKitTokenProtector,
  type ActivityKitTokenProtector,
  type ProtectedActivityKitToken,
} from "../src/liveCommute/apple/tokenProtection.js";

const INITIAL_NOW = new Date("2026-09-12T06:00:00.000Z");
const STARTS_AT = new Date("2026-09-12T07:00:00.000Z");
const ACTIVE_AT = new Date("2026-09-12T07:30:00.000Z");
const ENDS_AT = new Date("2026-09-12T08:00:00.000Z");

const INSTALLATION_UUIDS = [
  "00000000-0000-4000-8000-000000000001",
  "00000000-0000-4000-8000-000000000002",
  "00000000-0000-4000-8000-000000000003",
] as const;
const BINDING_UUIDS = [
  "10000000-0000-4000-8000-000000000001",
  "10000000-0000-4000-8000-000000000002",
  "10000000-0000-4000-8000-000000000003",
  "10000000-0000-4000-8000-000000000004",
] as const;
const UNKNOWN_BINDING_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff";

const PROTECTION_KEY = Uint8Array.from(
  { length: 32 },
  (_, index) => (index + 1) & 0xff,
);
const PUSH_TOKEN_A = Buffer.from("synthetic-push-token-alpha-0001", "utf8");
const PUSH_TOKEN_B = Buffer.from("synthetic-push-token-bravo-0002", "utf8");
const PUSH_TOKEN_C = Buffer.from("synthetic-push-token-charlie-003", "utf8");
const UPDATE_TOKEN_A = Buffer.from("synthetic-update-token-alpha-001", "utf8");
const UPDATE_TOKEN_B = Buffer.from("synthetic-update-token-bravo-002", "utf8");
const UPDATE_TOKEN_C = Buffer.from("synthetic-update-token-charlie-03", "utf8");

function nextValue(values: readonly string[], index: number, subject: string): string {
  const value = values[index];
  if (value == null) throw new Error(`${subject} fixture exhausted`);
  return value;
}

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
  const coreStore = new InMemoryLiveCommuteSessionStore();
  const deliveryStore = new InMemoryLiveActivityDeliveryStore(coreStore);
  let now = new Date(INITIAL_NOW);
  let installationUuidIndex = 0;
  let bindingUuidIndex = 0;
  let credentialByte = 0x41;
  let nonceSeed = 1;

  const installationService = createLiveCommuteInstallationService(coreStore, {
    now: () => new Date(now),
    randomUuid: () =>
      nextValue(
        INSTALLATION_UUIDS,
        installationUuidIndex++,
        "installation UUID",
      ),
    randomBytes: (size) => Buffer.alloc(size, credentialByte++),
  });
  const baseProtector = createAes256GcmActivityKitTokenProtector(PROTECTION_KEY, {
    randomBytes: (size) => {
      const seed = nonceSeed++;
      return Uint8Array.from({ length: size }, (_, index) => (seed + index) & 0xff);
    },
  });
  const unprotectCalls: Array<{ readonly context: string; readonly digest: string }> = [];
  const protector: ActivityKitTokenProtector = Object.freeze({
    protect: baseProtector.protect,
    digest: baseProtector.digest,
    unprotect: (protectedToken: ProtectedActivityKitToken, context: string) => {
      unprotectCalls.push({ context, digest: protectedToken.digest });
      return baseProtector.unprotect(protectedToken, context);
    },
  });
  const deliveryService = createLiveActivityDeliveryService(deliveryStore, protector, {
    now: () => new Date(now),
    randomUuid: () =>
      nextValue(BINDING_UUIDS, bindingUuidIndex++, "binding UUID"),
  });
  const resolver = createLiveActivityDeliveryResolver(deliveryStore, protector, {
    now: () => new Date(now),
  });

  return {
    coreStore,
    deliveryStore,
    installationService,
    deliveryService,
    resolver,
    protector,
    unprotectCalls,
    now: () => new Date(now),
    setNow(value: Date) {
      now = new Date(value);
    },
  };
}

async function issueWithSession(
  value: ReturnType<typeof harness>,
  overrides: Partial<LiveCommuteConcreteSessionInput> = {},
) {
  const authentication = await value.installationService.registerInstallation();
  const registration = await value.installationService.registerSession(
    authentication,
    lineSession(overrides),
  );
  return { authentication, registration };
}

function directBindingInput(sessionId = "occurrence-1", sessionRevision = 1) {
  return {
    sessionId,
    sessionRevision,
    strategy: "DIRECT_TOKEN" as const,
  };
}

function broadcastBindingInput(sessionId = "occurrence-1", sessionRevision = 1) {
  return {
    sessionId,
    sessionRevision,
    strategy: "BROADCAST_CHANNEL" as const,
  };
}

async function latestPushToken(
  value: ReturnType<typeof harness>,
  authentication: IssuedLiveCommuteInstallation,
) {
  return await value.deliveryStore.withInstallationTransaction(
    authentication.installationId,
    async (transaction) => await transaction.getLatestPushToStartToken(),
  );
}

async function pushTokenGeneration(
  value: ReturnType<typeof harness>,
  authentication: IssuedLiveCommuteInstallation,
  generation: number,
) {
  return await value.deliveryStore.withInstallationTransaction(
    authentication.installationId,
    async (transaction) => await transaction.getPushToStartToken(generation),
  );
}

async function bindingRecord(
  value: ReturnType<typeof harness>,
  authentication: IssuedLiveCommuteInstallation,
  bindingId: string,
) {
  return await value.deliveryStore.withInstallationTransaction(
    authentication.installationId,
    async (transaction) => await transaction.getDeliveryBinding(bindingId),
  );
}

async function latestUpdateToken(
  value: ReturnType<typeof harness>,
  authentication: IssuedLiveCommuteInstallation,
  bindingId: string,
) {
  return await value.deliveryStore.withInstallationTransaction(
    authentication.installationId,
    async (transaction) => await transaction.getLatestUpdateToken(bindingId),
  );
}

async function updateTokenGeneration(
  value: ReturnType<typeof harness>,
  authentication: IssuedLiveCommuteInstallation,
  bindingId: string,
  generation: number,
) {
  return await value.deliveryStore.withInstallationTransaction(
    authentication.installationId,
    async (transaction) => await transaction.getUpdateToken(bindingId, generation),
  );
}

function serializedEnvelope(value: unknown): string {
  if (value == null || typeof value !== "object") return JSON.stringify(value);
  return `${JSON.stringify(value)}\n${JSON.stringify(
    Object.getOwnPropertyDescriptors(value),
  )}`;
}

function expectNoSecretMaterial(
  value: unknown,
  bytes: readonly Uint8Array[],
  text: readonly string[],
): void {
  const serialized = serializedEnvelope(value);
  for (const forbiddenKey of [
    "protectedToken",
    "ciphertext",
    "authenticationTag",
    "nonce",
    "digest",
    "bearerCredential",
  ]) {
    expect(serialized).not.toContain(`"${forbiddenKey}"`);
  }
  for (const secret of bytes) {
    const copy = Buffer.from(secret);
    expect(serialized).not.toContain(copy.toString("hex"));
    expect(serialized).not.toContain(copy.toString("base64"));
    expect(serialized).not.toContain(`[${[...copy].join(",")}]`);
  }
  for (const secret of text) expect(serialized).not.toContain(secret);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("push-to-start token authority", () => {
  it("registers, deduplicates, rotates, rejects stale/conflicting generations, and invalidates idempotently", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);

    const initial = await value.deliveryService.registerPushToStartToken(
      authentication,
      { token: PUSH_TOKEN_A, clientGeneration: 1, environment: "SANDBOX" },
    );
    const repeated = await value.deliveryService.registerPushToStartToken(
      authentication,
      { token: PUSH_TOKEN_A, clientGeneration: 1, environment: "SANDBOX" },
    );

    expect(initial).toMatchObject({
      status: "REGISTERED",
      token: { clientGeneration: 1, serverRevision: 1, lifecycle: "CURRENT" },
    });
    expect(repeated).toEqual({ ...initial, status: "UNCHANGED" });
    await expect(
      value.deliveryService.registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_B,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });
    await expect(
      value.deliveryService.registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_A,
        clientGeneration: 1,
        environment: "PRODUCTION",
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });

    value.setNow(new Date("2026-09-12T06:01:00.000Z"));
    const rotated = await value.deliveryService.registerPushToStartToken(
      authentication,
      { token: PUSH_TOKEN_B, clientGeneration: 2, environment: "PRODUCTION" },
    );
    expect(rotated).toMatchObject({
      status: "ROTATED",
      token: { clientGeneration: 2, serverRevision: 2, lifecycle: "CURRENT" },
    });
    expect(await pushTokenGeneration(value, authentication, 1)).toMatchObject({
      lifecycle: "REPLACED",
      serverRevision: 1,
      replacedAt: new Date("2026-09-12T06:01:00.000Z"),
    });
    expect(await latestPushToken(value, authentication)).toMatchObject({
      clientGeneration: 2,
      lifecycle: "CURRENT",
    });
    await expect(
      value.deliveryService.registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_A,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });
    await expect(
      value.deliveryService.invalidatePushToStartToken(authentication, {
        expectedClientGeneration: 1,
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });

    value.setNow(new Date("2026-09-12T06:02:00.000Z"));
    const invalidated = await value.deliveryService.invalidatePushToStartToken(
      authentication,
      { expectedClientGeneration: 2 },
    );
    const repeatedInvalidation =
      await value.deliveryService.invalidatePushToStartToken(authentication, {
        expectedClientGeneration: 2,
      });
    expect(invalidated).toMatchObject({
      status: "INVALIDATED",
      token: { clientGeneration: 2, serverRevision: 2, lifecycle: "INVALIDATED" },
    });
    expect(repeatedInvalidation).toEqual({ ...invalidated, status: "UNCHANGED" });
    expect(await latestPushToken(value, authentication)).toMatchObject({
      lifecycle: "INVALIDATED",
      invalidatedAt: new Date("2026-09-12T06:02:00.000Z"),
    });

    const secondOwner = await value.installationService.registerInstallation();
    await expect(
      value.deliveryService.invalidatePushToStartToken(secondOwner, {
        expectedClientGeneration: 1,
      }),
    ).rejects.toMatchObject({ code: "TOKEN_NOT_FOUND" });
  });

  it("never resolves an invalidated push-to-start token", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    value.setNow(ACTIVE_AT);

    expect(
      await value.resolver.resolveStartTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).toMatchObject({
      kind: "START",
      pushToStartToken: PUSH_TOKEN_A,
      tokenGeneration: { clientGeneration: 1, serverRevision: 1 },
    });
    expect(value.unprotectCalls).toHaveLength(1);

    await value.deliveryService.invalidatePushToStartToken(authentication, {
      expectedClientGeneration: 1,
    });
    expect(
      await value.resolver.resolveStartTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(1);
  });
});

describe("delivery binding authority", () => {
  it("binds exactly one specification to an exact session revision", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);

    await expect(
      value.deliveryService.createDeliveryBinding(
        authentication,
        directBindingInput("occurrence-1", 2),
      ),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });

    const created = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    const repeated = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    expect(created).toMatchObject({
      status: "CREATED",
      binding: {
        bindingId: BINDING_UUIDS[0],
        installationId: authentication.installationId,
        sessionId: "occurrence-1",
        sessionRevision: 1,
        strategy: "DIRECT_TOKEN",
        lifecycle: "PENDING_START",
      },
    });
    expect(repeated).toEqual({ ...created, status: "UNCHANGED" });
    await expect(
      value.deliveryService.createDeliveryBinding(authentication, {
        ...broadcastBindingInput(),
        appleActivityId: "a-different-activity",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_CONFLICT" });
    expect(
      await bindingRecord(value, authentication, created.binding.bindingId),
    ).toEqual(created.binding);
  });

  it("attaches one reported Apple activity identifier and prevents duplicate starts or ownership", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    await value.installationService.registerSession(
      authentication,
      lineSession({
        sessionId: "occurrence-2",
        startsAt: ENDS_AT,
        endsAt: new Date("2026-09-12T09:00:00.000Z"),
      }),
    );
    const first = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    const second = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput("occurrence-2"),
    );
    await value.deliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    value.setNow(ACTIVE_AT);
    await expect(
      value.resolver.resolveStartTarget({
        installationId: authentication.installationId,
        bindingId: first.binding.bindingId,
      }),
    ).resolves.toMatchObject({ kind: "START" });
    const attached = await value.deliveryService.attachAppleActivityIdentifier(
      authentication,
      {
        bindingId: first.binding.bindingId,
        appleActivityId: "reported-activity-1",
      },
    );
    expect(attached).toMatchObject({
      status: "ATTACHED",
      binding: { appleActivityId: "reported-activity-1" },
    });
    await expect(
      value.deliveryService.attachAppleActivityIdentifier(authentication, {
        bindingId: first.binding.bindingId,
        appleActivityId: "reported-activity-1",
      }),
    ).resolves.toEqual({ ...attached, status: "UNCHANGED" });
    await expect(
      value.deliveryService.createDeliveryBinding(
        authentication,
        directBindingInput(),
      ),
    ).resolves.toEqual({ status: "UNCHANGED", binding: attached.binding });
    await expect(
      value.deliveryService.attachAppleActivityIdentifier(authentication, {
        bindingId: first.binding.bindingId,
        appleActivityId: "different-activity",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_CONFLICT" });
    await expect(
      value.resolver.resolveStartTarget({
        installationId: authentication.installationId,
        bindingId: first.binding.bindingId,
      }),
    ).resolves.toBeUndefined();

    value.setNow(ENDS_AT);
    await expect(
      value.deliveryService.attachAppleActivityIdentifier(authentication, {
        bindingId: second.binding.bindingId,
        appleActivityId: "reported-activity-1",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_CONFLICT" });
    await value.deliveryService.endDeliveryBinding(authentication, {
      bindingId: first.binding.bindingId,
    });
    await expect(
      value.deliveryService.attachAppleActivityIdentifier(authentication, {
        bindingId: first.binding.bindingId,
        appleActivityId: "reported-activity-1",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_INACTIVE" });
  });

  it("makes a binding ineligible when its exact session revision is replaced", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const created = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: created.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    value.setNow(ACTIVE_AT);
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: created.binding.bindingId,
      }),
    ).toMatchObject({ kind: "DIRECT_UPDATE", updateToken: UPDATE_TOKEN_A });
    expect(value.unprotectCalls).toHaveLength(1);

    value.setNow(new Date("2026-09-12T06:10:00.000Z"));
    const replacement = await value.installationService.replaceSession(authentication, {
      ...lineSession({ endsAt: new Date("2026-09-12T08:30:00.000Z") }),
      expectedRevision: 1,
    });
    expect(replacement).toMatchObject({ status: "REPLACED", session: { revision: 2 } });
    value.setNow(ACTIVE_AT);
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: created.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(1);
    await expect(
      value.deliveryService.registerUpdateToken(authentication, {
        bindingId: created.binding.bindingId,
        token: UPDATE_TOKEN_B,
        clientGeneration: 2,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });
    await expect(
      value.deliveryService.createDeliveryBinding(authentication, directBindingInput()),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });
    await expect(
      value.deliveryService.createDeliveryBinding(
        authentication,
        directBindingInput("occurrence-1", 2),
      ),
    ).resolves.toMatchObject({ status: "CREATED", binding: { sessionRevision: 2 } });
  });

  it("makes bindings ineligible after session cancellation or installation revocation", async () => {
    const cancelledValue = harness();
    const cancelledOwner = await issueWithSession(cancelledValue);
    const cancelledBinding = await cancelledValue.deliveryService.createDeliveryBinding(
      cancelledOwner.authentication,
      directBindingInput(),
    );
    await cancelledValue.deliveryService.registerUpdateToken(
      cancelledOwner.authentication,
      {
        bindingId: cancelledBinding.binding.bindingId,
        token: UPDATE_TOKEN_A,
        clientGeneration: 1,
        environment: "SANDBOX",
      },
    );
    await cancelledValue.installationService.cancelSession(
      cancelledOwner.authentication,
      { sessionId: "occurrence-1", expectedRevision: 1 },
    );
    cancelledValue.setNow(ACTIVE_AT);
    expect(
      await cancelledValue.resolver.resolveUpdateTarget({
        installationId: cancelledOwner.authentication.installationId,
        bindingId: cancelledBinding.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(cancelledValue.unprotectCalls).toHaveLength(0);
    await expect(
      cancelledValue.deliveryService.registerUpdateToken(
        cancelledOwner.authentication,
        {
          bindingId: cancelledBinding.binding.bindingId,
          token: UPDATE_TOKEN_B,
          clientGeneration: 2,
          environment: "SANDBOX",
        },
      ),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });

    const revokedValue = harness();
    const revokedOwner = await issueWithSession(revokedValue);
    const revokedBinding = await revokedValue.deliveryService.createDeliveryBinding(
      revokedOwner.authentication,
      directBindingInput(),
    );
    await revokedValue.deliveryService.registerUpdateToken(revokedOwner.authentication, {
      bindingId: revokedBinding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    await revokedValue.installationService.revokeInstallation(
      revokedOwner.authentication,
    );
    revokedValue.setNow(ACTIVE_AT);
    expect(
      await revokedValue.resolver.resolveUpdateTarget({
        installationId: revokedOwner.authentication.installationId,
        bindingId: revokedBinding.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(revokedValue.unprotectCalls).toHaveLength(0);
    await expect(
      revokedValue.deliveryService.registerPushToStartToken(
        revokedOwner.authentication,
        { token: PUSH_TOKEN_A, clientGeneration: 1, environment: "SANDBOX" },
      ),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
  });

  it("uses half-open session windows and never decrypts an expired target", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    value.setNow(new Date(STARTS_AT.getTime() - 1));
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).toBeUndefined();
    value.setNow(ENDS_AT);
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(0);

    value.setNow(ENDS_AT);
    await expect(
      value.deliveryService.registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: UPDATE_TOKEN_B,
        clientGeneration: 2,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });
  });

  it("reads delivery eligibility time only after acquiring installation authority", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    value.setNow(ACTIVE_AT);

    let releaseLock = () => {};
    let markLocked = () => {};
    const locked = new Promise<void>((resolve) => {
      markLocked = resolve;
    });
    const release = new Promise<void>((resolve) => {
      releaseLock = resolve;
    });
    const holdingTransaction = value.coreStore.withInstallationTransaction(
      authentication.installationId,
      async () => {
        markLocked();
        await release;
      },
    );
    await locked;
    const resolution = value.resolver.resolveUpdateTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });
    value.setNow(ENDS_AT);
    releaseLock();
    await holdingTransaction;

    await expect(resolution).resolves.toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(0);
  });
});

describe("update targets and terminal state", () => {
  it("rotates direct tokens, rejects stale/conflicting input, and decrypts only the authoritative generation", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    await value.deliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    const targetInput = {
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    };

    value.setNow(ACTIVE_AT);
    expect(await value.resolver.resolveUpdateTarget(targetInput)).toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(0);
    const initial = await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 4,
      environment: "SANDBOX",
    });
    const repeated = await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 4,
      environment: "SANDBOX",
    });
    expect(initial).toMatchObject({
      status: "REGISTERED",
      token: { clientGeneration: 4, serverRevision: 1, lifecycle: "CURRENT" },
    });
    expect(repeated).toEqual({ ...initial, status: "UNCHANGED" });
    await expect(
      value.resolver.resolveStartTarget(targetInput),
    ).resolves.toBeUndefined();

    value.setNow(new Date("2026-09-12T06:01:00.000Z"));
    const rotated = await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_B,
      clientGeneration: 8,
      environment: "PRODUCTION",
    });
    expect(rotated).toMatchObject({
      status: "ROTATED",
      token: { clientGeneration: 8, serverRevision: 2, lifecycle: "CURRENT" },
    });
    expect(
      await updateTokenGeneration(
        value,
        authentication,
        binding.binding.bindingId,
        4,
      ),
    ).toMatchObject({ lifecycle: "REPLACED", serverRevision: 1 });
    await expect(
      value.deliveryService.registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: UPDATE_TOKEN_A,
        clientGeneration: 4,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });
    await expect(
      value.deliveryService.registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: UPDATE_TOKEN_C,
        clientGeneration: 8,
        environment: "PRODUCTION",
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });

    value.setNow(ACTIVE_AT);
    const resolved = await value.resolver.resolveUpdateTarget(targetInput);
    expect(resolved).toMatchObject({
      kind: "DIRECT_UPDATE",
      strategy: "DIRECT_TOKEN",
      environment: "PRODUCTION",
      updateToken: UPDATE_TOKEN_B,
      tokenGeneration: { clientGeneration: 8, serverRevision: 2 },
      sessionVersion: {
        installationId: authentication.installationId,
        sessionId: "occurrence-1",
        revision: 1,
      },
    });
    expect(value.unprotectCalls).toEqual([
      {
        context: liveActivityUpdateProtectionContext(
          authentication.installationId,
          binding.binding.bindingId,
          8,
          "PRODUCTION",
        ),
        digest: value.protector.digest(UPDATE_TOKEN_B),
      },
    ]);
  });

  it("invalidates direct update tokens independently with generation compare-and-swap", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 2,
      environment: "SANDBOX",
    });
    await expect(
      value.deliveryService.invalidateUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        expectedClientGeneration: 1,
      }),
    ).rejects.toMatchObject({ code: "TOKEN_GENERATION_CONFLICT" });
    const invalidated = await value.deliveryService.invalidateUpdateToken(
      authentication,
      {
        bindingId: binding.binding.bindingId,
        expectedClientGeneration: 2,
      },
    );
    expect(invalidated).toMatchObject({
      status: "INVALIDATED",
      token: { clientGeneration: 2, lifecycle: "INVALIDATED" },
    });
    await expect(
      value.deliveryService.invalidateUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        expectedClientGeneration: 2,
      }),
    ).resolves.toEqual({ ...invalidated, status: "UNCHANGED" });
    value.setNow(ACTIVE_AT);
    await expect(
      value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).resolves.toBeUndefined();
    await expect(
      value.resolver.resolveStartTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).resolves.toBeUndefined();

    const cancelledValue = harness();
    const cancelledOwner = await issueWithSession(cancelledValue);
    const cancelledBinding =
      await cancelledValue.deliveryService.createDeliveryBinding(
        cancelledOwner.authentication,
        directBindingInput(),
      );
    await cancelledValue.deliveryService.registerUpdateToken(
      cancelledOwner.authentication,
      {
        bindingId: cancelledBinding.binding.bindingId,
        token: UPDATE_TOKEN_A,
        clientGeneration: 1,
        environment: "SANDBOX",
      },
    );
    await cancelledValue.installationService.cancelSession(
      cancelledOwner.authentication,
      { sessionId: "occurrence-1", expectedRevision: 1 },
    );
    await expect(
      cancelledValue.deliveryService.invalidateUpdateToken(
        cancelledOwner.authentication,
        {
          bindingId: cancelledBinding.binding.bindingId,
          expectedClientGeneration: 1,
        },
      ),
    ).resolves.toMatchObject({ status: "INVALIDATED" });
  });

  it("keeps broadcast strategy tokenless and reports only the channel requirement for updates", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      broadcastBindingInput(),
    );
    await value.deliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    await expect(
      value.deliveryService.registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: UPDATE_TOKEN_A,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_STRATEGY_MISMATCH" });
    await expect(
      value.deliveryService.invalidateUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        expectedClientGeneration: 1,
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_STRATEGY_MISMATCH" });
    value.setNow(ACTIVE_AT);
    const update = await value.resolver.resolveUpdateTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });
    expect(update).toMatchObject({
      kind: "BROADCAST_CHANNEL_REQUIRED",
      strategy: "BROADCAST_CHANNEL",
    });
    expect(Object.keys(update ?? {}).sort()).toEqual([
      "bindingId",
      "kind",
      "publicationKey",
      "resolvedAt",
      "sessionVersion",
      "strategy",
    ]);
    expect(value.unprotectCalls).toHaveLength(0);

    const start = await value.resolver.resolveStartTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });
    expect(start).toMatchObject({
      kind: "START",
      strategy: "BROADCAST_CHANNEL",
      broadcastChannelRequirement: "APNS_CHANNEL_REQUIRED",
      pushToStartToken: PUSH_TOKEN_A,
      tokenGeneration: { clientGeneration: 1, serverRevision: 1 },
    });
    expect(value.unprotectCalls).toHaveLength(1);
    expect(
      await latestUpdateToken(value, authentication, binding.binding.bindingId),
    ).toBeUndefined();
  });

  it("terminalizes atomically across clock rollback and makes same-terminal replays idempotent", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    value.setNow(new Date("2026-09-12T06:05:00.000Z"));
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_B,
      clientGeneration: 2,
      environment: "SANDBOX",
    });
    value.setNow(new Date("2026-09-12T06:01:00.000Z"));

    const ended = await value.deliveryService.endDeliveryBinding(authentication, {
      bindingId: binding.binding.bindingId,
    });
    const replay = await value.deliveryService.endDeliveryBinding(authentication, {
      bindingId: binding.binding.bindingId,
    });
    expect(ended).toMatchObject({
      status: "ENDED",
      binding: {
        lifecycle: "ENDED",
        endedAt: new Date("2026-09-12T06:05:00.000Z"),
      },
    });
    expect(replay).toEqual({ ...ended, status: "UNCHANGED" });
    expect(
      await latestUpdateToken(value, authentication, binding.binding.bindingId),
    ).toMatchObject({
      lifecycle: "INVALIDATED",
      invalidatedAt: new Date("2026-09-12T06:05:00.000Z"),
      clientGeneration: 2,
    });
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(0);
    await expect(
      value.deliveryService.createDeliveryBinding(authentication, directBindingInput()),
    ).resolves.toMatchObject({ status: "ALREADY_TERMINAL" });
    await expect(
      value.deliveryService.invalidateDeliveryBinding(authentication, {
        bindingId: binding.binding.bindingId,
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_INACTIVE" });

    const invalidatedValue = harness();
    const invalidatedOwner = await issueWithSession(invalidatedValue);
    const invalidatedBinding =
      await invalidatedValue.deliveryService.createDeliveryBinding(
        invalidatedOwner.authentication,
        broadcastBindingInput(),
      );
    const invalidated =
      await invalidatedValue.deliveryService.invalidateDeliveryBinding(
        invalidatedOwner.authentication,
        { bindingId: invalidatedBinding.binding.bindingId },
      );
    const invalidationReplay =
      await invalidatedValue.deliveryService.invalidateDeliveryBinding(
        invalidatedOwner.authentication,
        { bindingId: invalidatedBinding.binding.bindingId },
      );
    expect(invalidated.status).toBe("INVALIDATED");
    expect(invalidationReplay).toEqual({ ...invalidated, status: "UNCHANGED" });
    await expect(
      invalidatedValue.deliveryService.endDeliveryBinding(
        invalidatedOwner.authentication,
        { bindingId: invalidatedBinding.binding.bindingId },
      ),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_INACTIVE" });
  });
});

describe("ownership, secrecy, and transaction boundaries", () => {
  it("enforces authenticated installation scope for binding access", async () => {
    const value = harness();
    const ownerA = await issueWithSession(value);
    const ownerB = await issueWithSession(value);
    const bindingA = await value.deliveryService.createDeliveryBinding(
      ownerA.authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(ownerA.authentication, {
      bindingId: bindingA.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    await expect(
      value.deliveryService.registerUpdateToken(ownerB.authentication, {
        bindingId: bindingA.binding.bindingId,
        token: UPDATE_TOKEN_B,
        clientGeneration: 2,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_NOT_FOUND" });
    await expect(
      value.deliveryService.endDeliveryBinding(ownerB.authentication, {
        bindingId: bindingA.binding.bindingId,
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_NOT_FOUND" });
    await expect(
      value.deliveryService.endDeliveryBinding(
        {
          installationId: ownerA.authentication.installationId,
          bearerCredential: ownerB.authentication.bearerCredential,
        },
        { bindingId: bindingA.binding.bindingId },
      ),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
    value.setNow(ACTIVE_AT);
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: ownerB.authentication.installationId,
        bindingId: bindingA.binding.bindingId,
      }),
    ).toBeUndefined();
    expect(value.unprotectCalls).toHaveLength(0);
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: ownerA.authentication.installationId,
        bindingId: UNKNOWN_BINDING_ID,
      }),
    ).toBeUndefined();
  });

  it("keeps token plaintext, ciphertext, digests, and credentials out of safe DTOs and errors", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    const registered = await value.deliveryService.registerPushToStartToken(
      authentication,
      { token: PUSH_TOKEN_A, clientGeneration: 1, environment: "SANDBOX" },
    );
    const update = await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const persistedPush = await latestPushToken(value, authentication);
    const persistedUpdate = await latestUpdateToken(
      value,
      authentication,
      binding.binding.bindingId,
    );
    expect(persistedPush).toBeDefined();
    expect(persistedUpdate).toBeDefined();

    const error = await value.deliveryService
      .registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_C,
        clientGeneration: 1,
        environment: "SANDBOX",
      })
      .catch((failure: unknown) => failure);
    expect(error).toMatchObject({
      code: "TOKEN_GENERATION_CONFLICT",
      message: "ActivityKit token generation conflict",
    });

    const protectedBytes = [
      persistedPush!.protectedToken.nonce,
      persistedPush!.protectedToken.authenticationTag,
      persistedPush!.protectedToken.ciphertext,
      persistedUpdate!.protectedToken.nonce,
      persistedUpdate!.protectedToken.authenticationTag,
      persistedUpdate!.protectedToken.ciphertext,
    ];
    const protectedText = [
      authentication.bearerCredential,
      persistedPush!.protectedToken.digest,
      persistedUpdate!.protectedToken.digest,
    ];
    for (const safeValue of [registered, update, binding, error]) {
      expectNoSecretMaterial(
        safeValue,
        [PUSH_TOKEN_A, PUSH_TOKEN_C, UPDATE_TOKEN_A, ...protectedBytes],
        protectedText,
      );
    }
  });

  it("performs delivery registration and resolution without any fetch or SL call", async () => {
    const fetchSpy = vi.fn(async () => {
      throw new Error("unexpected network access");
    });
    vi.stubGlobal("fetch", fetchSpy);
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    value.setNow(ACTIVE_AT);
    await value.resolver.resolveStartTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });
    await value.resolver.resolveUpdateTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("rolls back every staged delivery write when a later transaction step fails", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    const binding = await value.deliveryService.createDeliveryBinding(
      authentication,
      directBindingInput(),
    );
    await value.deliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const failingStore: LiveActivityDeliveryStore = {
      listDeliveryBindingsForSessionVersions: async (references) =>
        await value.deliveryStore.listDeliveryBindingsForSessionVersions(references),
      withInstallationTransaction: async (installationId, operation) =>
        await value.deliveryStore.withInstallationTransaction(
          installationId,
          async (transaction) =>
            await operation({
              ...transaction,
              saveUpdateToken: async (token) => {
                if (token.lifecycle === "INVALIDATED") {
                  throw new Error("synthetic persistence failure");
                }
                await transaction.saveUpdateToken(token);
              },
            }),
        ),
    };
    const failingService = createLiveActivityDeliveryService(
      failingStore,
      value.protector,
      { now: value.now },
    );

    await expect(
      failingService.endDeliveryBinding(authentication, {
        bindingId: binding.binding.bindingId,
      }),
    ).rejects.toThrow("synthetic persistence failure");
    expect(
      await bindingRecord(value, authentication, binding.binding.bindingId),
    ).toMatchObject({ lifecycle: "PENDING_START", endedAt: null });
    expect(
      await latestUpdateToken(value, authentication, binding.binding.bindingId),
    ).toMatchObject({ lifecycle: "CURRENT", invalidatedAt: null });
    value.setNow(ACTIVE_AT);
    expect(
      await value.resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).toMatchObject({ kind: "DIRECT_UPDATE", updateToken: UPDATE_TOKEN_A });
  });

  it("rolls back direct adapter mutations when a transaction callback throws", async () => {
    const value = harness();
    const { authentication } = await issueWithSession(value);
    await value.deliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_A,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    await expect(
      value.deliveryStore.withInstallationTransaction(
        authentication.installationId,
        async (transaction) => {
          const current = await transaction.getLatestPushToStartToken();
          expect(current).toBeDefined();
          const invalidatedAt = new Date("2026-09-12T06:03:00.000Z");
          await transaction.savePushToStartToken(
            createStoredPushToStartToken({
              ...current!,
              lifecycle: "INVALIDATED",
              updatedAt: invalidatedAt,
              replacedAt: null,
              invalidatedAt,
            }),
          );
          throw new Error("synthetic callback failure");
        },
      ),
    ).rejects.toThrow("synthetic callback failure");
    expect(await latestPushToken(value, authentication)).toMatchObject({
      lifecycle: "CURRENT",
      invalidatedAt: null,
    });
  });
});
