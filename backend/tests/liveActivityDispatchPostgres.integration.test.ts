import { randomUUID } from "node:crypto";
import postgres from "postgres";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { runLiveCommuteMigration } from "../scripts/migrateLiveCommute.js";
import { runLiveActivityDeliveryMigration } from "../scripts/migrateLiveActivityDelivery.js";
import { runLiveActivityDispatchMigration } from "../scripts/migrateLiveActivityDispatch.js";
import { createLiveCommuteInstallationService } from "../src/liveCommute/installationService.js";
import { PostgresLiveCommuteSessionStore } from "../src/liveCommute/postgresLiveCommuteSessionStore.js";
import { createLiveActivityDeliveryService } from "../src/liveCommute/apple/deliveryService.js";
import { createLiveActivityDeliveryResolver } from "../src/liveCommute/apple/deliveryResolver.js";
import { createLiveActivityDirectDispatcher } from "../src/liveCommute/apple/directDispatcher.js";
import { PostgresLiveActivityDeliveryStore } from "../src/liveCommute/apple/postgresLiveActivityDeliveryStore.js";
import { PostgresLiveActivityDispatchStore } from "../src/liveCommute/apple/postgresLiveActivityDispatchStore.js";
import { createAes256GcmActivityKitTokenProtector } from "../src/liveCommute/apple/tokenProtection.js";
import type { LiveActivityDirectDispatchOperation } from "../src/liveCommute/apple/dispatchModel.js";
import type { IssuedLiveCommuteInstallation } from "../src/liveCommute/installationService.js";
import {
  checkedLocalLiveCommuteTestDatabaseUrl,
  LIVE_COMMUTE_TEST_SCHEMA_PREFIX,
  liveCommuteTestSchemaConnectionUrl,
} from "./liveCommutePostgresTestSupport.js";

const RAW_TEST_DATABASE_URL =
  process.env.LIVE_COMMUTE_TEST_DATABASE_URL?.trim() || undefined;
const describeWithPostgres = RAW_TEST_DATABASE_URL == null ? describe.skip : describe;
const ACTIVE_AT = new Date("2026-09-12T07:30:00.000Z");
const EVENT = Math.floor(ACTIVE_AT.getTime() / 1_000);
const FINGERPRINT = "d".repeat(64);

describeWithPostgres("PostgreSQL Live Activity direct dispatch store", () => {
  const schema = `${LIVE_COMMUTE_TEST_SCHEMA_PREFIX}dispatch_${randomUUID().replaceAll("-", "").slice(0, 24)}`;
  let adminSql: ReturnType<typeof postgres> | undefined;
  let firstSql: ReturnType<typeof postgres> | undefined;
  let secondSql: ReturnType<typeof postgres> | undefined;
  let firstCore: PostgresLiveCommuteSessionStore;
  let secondCore: PostgresLiveCommuteSessionStore;
  let firstDelivery: PostgresLiveActivityDeliveryStore;
  let secondDelivery: PostgresLiveActivityDeliveryStore;
  let firstDispatch: PostgresLiveActivityDispatchStore;
  let secondDispatch: PostgresLiveActivityDispatchStore;
  const protector = createAes256GcmActivityKitTokenProtector(Buffer.alloc(32, 0x44));

  function installationService(store = firstCore) {
    return createLiveCommuteInstallationService(store, {
      now: () => new Date(ACTIVE_AT),
    });
  }

  function deliveryService(store = firstDelivery) {
    return createLiveActivityDeliveryService(store, protector, {
      now: () => new Date(ACTIVE_AT),
    });
  }

  async function assertIndependentScopedConnections(): Promise<void> {
    const [firstScope, secondScope] = await Promise.all([
      firstSql!<{ current_schema: string | null; backend_pid: number }[]>`
        SELECT current_schema() AS current_schema,
               pg_backend_pid()::integer AS backend_pid
      `,
      secondSql!<{ current_schema: string | null; backend_pid: number }[]>`
        SELECT current_schema() AS current_schema,
               pg_backend_pid()::integer AS backend_pid
      `,
    ]);
    if (
      firstScope[0]?.current_schema !== schema ||
      secondScope[0]?.current_schema !== schema
    ) {
      throw new Error("refusing to run outside the randomized dispatch test schema");
    }
    if (firstScope[0]?.backend_pid === secondScope[0]?.backend_pid) {
      throw new Error("dispatch integration stores must use independent connections");
    }
  }

  async function setupDirect(withUpdateToken: boolean) {
    const authentication = await installationService().registerInstallation();
    await installationService().registerSession(authentication, {
      sessionId: "dispatch-occurrence",
      routineId: "dispatch-routine",
      startsAt: new Date("2026-09-12T07:00:00.000Z"),
      endsAt: new Date("2026-09-12T09:00:00.000Z"),
      query: {
        kind: "LINE_DIRECTION",
        siteId: 9192,
        transportMode: "BUS",
        lineId: 57,
        directionCode: 1,
      },
    });
    await deliveryService().registerPushToStartToken(authentication, {
      token: Buffer.from("postgres-synthetic-start-token"),
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const binding = await deliveryService().createDeliveryBinding(authentication, {
      sessionId: "dispatch-occurrence",
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
    });
    if (withUpdateToken) {
      await deliveryService().registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: Buffer.from("postgres-synthetic-update-token-one"),
        clientGeneration: 1,
        environment: "SANDBOX",
      });
    }
    return { authentication, bindingId: binding.binding.bindingId };
  }

  function reserveInput(
    authentication: IssuedLiveCommuteInstallation,
    bindingId: string,
    operation: LiveActivityDirectDispatchOperation,
    eventTimestamp: number,
    reservedAt = ACTIVE_AT,
  ) {
    return {
      installationId: authentication.installationId,
      bindingId,
      sessionRevision: 1,
      operation,
      eventTimestamp,
      dispatchId: randomUUID(),
      apnsRequestId: randomUUID(),
      reservedAt,
    } as const;
  }

  async function claim(
    store: PostgresLiveActivityDispatchStore,
    authentication: IssuedLiveCommuteInstallation,
    dispatchId: string,
  ) {
    return await store.claimDirectDispatch({
      installationId: authentication.installationId,
      dispatchId,
      payloadFingerprint: FINGERPRINT,
      claimedAt: ACTIVE_AT,
    });
  }

  async function complete(
    store: PostgresLiveActivityDispatchStore,
    authentication: IssuedLiveCommuteInstallation,
    dispatchId: string,
    values: Partial<{
      state:
        | "ACCEPTED"
        | "REJECTED"
        | "RETRYABLE"
        | "OUTCOME_UNKNOWN"
        | "ABORTED";
      apnsStatus: number | null;
      apnsReason: string | null;
      retryAdvice:
        | "NO_RETRY"
        | "RETRY_AFTER_APPLE_BACKOFF"
        | "RETRY_THROTTLED"
        | "REFRESH_PROVIDER_TOKEN"
        | "PERMANENT_DESTINATION_FAILURE"
        | "PERMANENT_PAYLOAD_FAILURE"
        | "OUTCOME_UNKNOWN"
        | "OPERATOR_CONFIGURATION_REQUIRED";
      retryNotBefore: Date | null;
      invalidateExactTokenGeneration: boolean;
    }> = {},
  ) {
    return await store.completeDirectDispatch({
      installationId: authentication.installationId,
      dispatchId,
      completedAt: ACTIVE_AT,
      state: values.state ?? "ACCEPTED",
      apnsStatus: values.apnsStatus === undefined ? 200 : values.apnsStatus,
      apnsReason: values.apnsReason ?? null,
      retryAdvice: values.retryAdvice ?? "NO_RETRY",
      retryNotBefore: values.retryNotBefore ?? null,
      invalidateExactTokenGeneration:
        values.invalidateExactTokenGeneration ?? false,
    });
  }

  beforeAll(async () => {
    const connectionString = checkedLocalLiveCommuteTestDatabaseUrl(
      RAW_TEST_DATABASE_URL!,
    );
    adminSql = postgres(connectionString, { max: 1, prepare: false });
    await adminSql`CREATE SCHEMA ${adminSql(schema)}`;
    const scoped = liveCommuteTestSchemaConnectionUrl(connectionString, schema);
    firstSql = postgres(scoped, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_dispatch_test_first" },
    });
    secondSql = postgres(scoped, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_dispatch_test_second" },
    });
    await assertIndependentScopedConnections();
    await runLiveCommuteMigration(scoped);
    await runLiveActivityDeliveryMigration(scoped);
    await runLiveActivityDispatchMigration(scoped);
    await runLiveCommuteMigration(scoped);
    await runLiveActivityDeliveryMigration(scoped);
    await runLiveActivityDispatchMigration(scoped);
    firstCore = new PostgresLiveCommuteSessionStore(firstSql);
    secondCore = new PostgresLiveCommuteSessionStore(secondSql);
    firstDelivery = new PostgresLiveActivityDeliveryStore(firstSql);
    secondDelivery = new PostgresLiveActivityDeliveryStore(secondSql);
    firstDispatch = new PostgresLiveActivityDispatchStore(firstSql);
    secondDispatch = new PostgresLiveActivityDispatchStore(secondSql);
  });

  beforeEach(async () => {
    await assertIndependentScopedConnections();
    await firstSql!`
      TRUNCATE live_activity_direct_dispatch_attempts,
               live_activity_direct_dispatch_state,
               live_activity_update_tokens,
               live_activity_delivery_bindings,
               live_activity_push_to_start_tokens,
               live_commute_sessions,
               live_commute_installations
    `;
  });

  afterAll(async () => {
    await firstSql?.end({ timeout: 5 });
    await secondSql?.end({ timeout: 5 });
    if (adminSql != null) {
      if (!schema.startsWith(LIVE_COMMUTE_TEST_SCHEMA_PREFIX)) {
        throw new Error("refusing to remove an unexpected dispatch test schema");
      }
      await adminSql`DROP SCHEMA IF EXISTS ${adminSql(schema)} CASCADE`;
      await adminSql.end({ timeout: 5 });
    }
  });

  it("serializes simultaneous update reservations and leaves the newest event authoritative", async () => {
    const setup = await setupDirect(true);
    const older = reserveInput(
      setup.authentication,
      setup.bindingId,
      "DIRECT_UPDATE",
      EVENT,
    );
    const newer = reserveInput(
      setup.authentication,
      setup.bindingId,
      "DIRECT_UPDATE",
      EVENT + 1,
    );
    const results = await Promise.all([
      firstDispatch.reserveDirectDispatch(older),
      secondDispatch.reserveDirectDispatch(newer),
    ]);
    expect(results.some(({ status }) => status === "RESERVED")).toBe(true);
    const rows = await firstSql!<
      { event_timestamp: string; state: string; active_count: number }[]
    >`
      SELECT event_timestamp::text, state,
             count(*) FILTER (WHERE state IN ('RESERVED', 'IN_FLIGHT'))
               OVER ()::integer AS active_count
      FROM live_activity_direct_dispatch_attempts
      ORDER BY event_timestamp DESC
    `;
    expect(rows[0]).toMatchObject({
      event_timestamp: String(EVENT + 1),
      state: "RESERVED",
      active_count: 1,
    });
  });

  it("rejects an older tick after a newer reservation", async () => {
    const setup = await setupDirect(true);
    await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT + 2),
    );
    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT + 1),
      ),
    ).resolves.toEqual({
      status: "STALE_EVENT",
      lastReservedEventTimestamp: EVENT + 2,
    });
  });

  it("returns an explicit same-second collision", async () => {
    const setup = await setupDirect(true);
    await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
      ),
    ).resolves.toEqual({
      status: "SAME_SECOND",
      lastReservedEventTimestamp: EVENT,
    });
  });

  it("permits only one in-flight claim per binding", async () => {
    const setup = await setupDirect(true);
    const first = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (first.status !== "RESERVED") throw new Error("expected reservation");
    await expect(
      claim(firstDispatch, setup.authentication, first.attempt.dispatchId),
    ).resolves.toMatchObject({ status: "CLAIMED" });
    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(
          setup.authentication,
          setup.bindingId,
          "DIRECT_UPDATE",
          EVENT + 1,
        ),
      ),
    ).resolves.toMatchObject({
      status: "BUSY",
      blockingDispatchId: first.attempt.dispatchId,
    });
  });

  it("durably aborts a proven post-claim no-send and releases the START blocker", async () => {
    const setup = await setupDirect(false);
    const first = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "START", EVENT),
    );
    if (first.status !== "RESERVED") throw new Error("expected reservation");
    await claim(firstDispatch, setup.authentication, first.attempt.dispatchId);

    await expect(
      complete(firstDispatch, setup.authentication, first.attempt.dispatchId, {
        state: "ABORTED",
        apnsStatus: null,
        retryAdvice: "OPERATOR_CONFIGURATION_REQUIRED",
      }),
    ).resolves.toMatchObject({
      status: "COMPLETED",
      attempt: {
        state: "ABORTED",
        payloadFingerprint: FINGERPRINT,
        apnsStatus: null,
        apnsReason: null,
      },
    });

    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(setup.authentication, setup.bindingId, "START", EVENT + 1),
      ),
    ).resolves.toMatchObject({ status: "RESERVED" });
  });

  it("allows separate bindings to reserve independently", async () => {
    const first = await setupDirect(true);
    const second = await setupDirect(true);
    const results = await Promise.all([
      firstDispatch.reserveDirectDispatch(
        reserveInput(first.authentication, first.bindingId, "DIRECT_UPDATE", EVENT),
      ),
      secondDispatch.reserveDirectDispatch(
        reserveInput(second.authentication, second.bindingId, "DIRECT_UPDATE", EVENT),
      ),
    ]);
    expect(results.map(({ status }) => status)).toEqual(["RESERVED", "RESERVED"]);
  });

  it("prevents another START after APNs acceptance", async () => {
    const setup = await setupDirect(false);
    const first = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "START", EVENT),
    );
    if (first.status !== "RESERVED") throw new Error("expected reservation");
    await claim(firstDispatch, setup.authentication, first.attempt.dispatchId);
    await complete(firstDispatch, setup.authentication, first.attempt.dispatchId);
    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(setup.authentication, setup.bindingId, "START", EVENT + 1),
      ),
    ).resolves.toMatchObject({ status: "START_BLOCKED" });
  });

  it("prevents blind duplicate START after an unknown outcome", async () => {
    const setup = await setupDirect(false);
    const first = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "START", EVENT),
    );
    if (first.status !== "RESERVED") throw new Error("expected reservation");
    await claim(firstDispatch, setup.authentication, first.attempt.dispatchId);
    await complete(firstDispatch, setup.authentication, first.attempt.dispatchId, {
      state: "OUTCOME_UNKNOWN",
      apnsStatus: null,
      retryAdvice: "OUTCOME_UNKNOWN",
    });
    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(setup.authentication, setup.bindingId, "START", EVENT + 1),
      ),
    ).resolves.toMatchObject({ status: "START_BLOCKED" });
  });

  it("allows explicit corrected START retry and enforces a durable Apple backoff bound", async () => {
    const setup = await setupDirect(false);
    const first = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "START", EVENT),
    );
    if (first.status !== "RESERVED") throw new Error("expected reservation");
    await claim(firstDispatch, setup.authentication, first.attempt.dispatchId);
    await complete(firstDispatch, setup.authentication, first.attempt.dispatchId, {
      state: "REJECTED",
      apnsStatus: 400,
      apnsReason: "BadPayload",
      retryAdvice: "PERMANENT_PAYLOAD_FAILURE",
    });
    const second = await secondDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "START", EVENT + 1),
    );
    if (second.status !== "RESERVED") throw new Error("expected corrected retry");
    await claim(secondDispatch, setup.authentication, second.attempt.dispatchId);
    const retryAt = new Date("2026-09-12T07:45:00.000Z");
    await complete(secondDispatch, setup.authentication, second.attempt.dispatchId, {
      state: "RETRYABLE",
      apnsStatus: 503,
      apnsReason: "ServiceUnavailable",
      retryAdvice: "RETRY_AFTER_APPLE_BACKOFF",
      retryNotBefore: retryAt,
    });
    await expect(
      firstDispatch.reserveDirectDispatch(
        reserveInput(
          setup.authentication,
          setup.bindingId,
          "START",
          EVENT + 2,
          new Date("2026-09-12T07:40:00.000Z"),
        ),
      ),
    ).resolves.toMatchObject({
      status: "BUSY",
      blockingDispatchId: second.attempt.dispatchId,
    });
    await expect(
      firstDispatch.reserveDirectDispatch(
        reserveInput(
          setup.authentication,
          setup.bindingId,
          "START",
          EVENT + 3,
          new Date("2026-09-12T07:46:00.000Z"),
        ),
      ),
    ).resolves.toMatchObject({ status: "RESERVED" });
  });

  it("supersedes an older reserved UPDATE with newer authoritative work", async () => {
    const setup = await setupDirect(true);
    const old = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (old.status !== "RESERVED") throw new Error("expected reservation");
    const current = await secondDispatch.reserveDirectDispatch(
      reserveInput(
        setup.authentication,
        setup.bindingId,
        "DIRECT_UPDATE",
        EVENT + 1,
      ),
    );
    expect(current).toMatchObject({
      status: "RESERVED",
      supersededDispatchId: old.attempt.dispatchId,
    });
    await expect(
      claim(firstDispatch, setup.authentication, old.attempt.dispatchId),
    ).resolves.toMatchObject({ status: "SUPERSEDED" });
  });

  it("makes END terminal intent block every later ordinary UPDATE", async () => {
    const setup = await setupDirect(true);
    await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_END", EVENT),
    );
    await expect(
      secondDispatch.reserveDirectDispatch(
        reserveInput(
          setup.authentication,
          setup.bindingId,
          "DIRECT_UPDATE",
          EVENT + 100,
        ),
      ),
    ).resolves.toEqual({ status: "TERMINAL_INTENT", blockingDispatchId: null });
  });

  it("retains exact token-generation correlation while a token rotates in flight", async () => {
    const setup = await setupDirect(true);
    const reserved = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (reserved.status !== "RESERVED") throw new Error("expected reservation");
    await claim(firstDispatch, setup.authentication, reserved.attempt.dispatchId);
    await deliveryService(secondDelivery).registerUpdateToken(setup.authentication, {
      bindingId: setup.bindingId,
      token: Buffer.from("postgres-synthetic-update-token-two"),
      clientGeneration: 2,
      environment: "SANDBOX",
    });
    await expect(
      firstDispatch.getDirectDispatchAttempt(
        setup.authentication.installationId,
        reserved.attempt.dispatchId,
      ),
    ).resolves.toMatchObject({
      state: "IN_FLIGHT",
      tokenGeneration: { clientGeneration: 1, serverRevision: 1 },
    });
  });

  it("does not let a delayed terminal response invalidate a newer token generation", async () => {
    const setup = await setupDirect(true);
    const reserved = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (reserved.status !== "RESERVED") throw new Error("expected reservation");
    await claim(firstDispatch, setup.authentication, reserved.attempt.dispatchId);
    await deliveryService(secondDelivery).registerUpdateToken(setup.authentication, {
      bindingId: setup.bindingId,
      token: Buffer.from("postgres-synthetic-update-token-two"),
      clientGeneration: 2,
      environment: "SANDBOX",
    });
    await expect(
      complete(firstDispatch, setup.authentication, reserved.attempt.dispatchId, {
        state: "REJECTED",
        apnsStatus: 410,
        apnsReason: "Unregistered",
        retryAdvice: "PERMANENT_DESTINATION_FAILURE",
        invalidateExactTokenGeneration: true,
      }),
    ).resolves.toMatchObject({
      status: "COMPLETED",
      attempt: { tokenInvalidationOutcome: "GENERATION_NO_LONGER_CURRENT" },
    });
    const current = await firstDelivery.withInstallationTransaction(
      setup.authentication.installationId,
      async (transaction) =>
        await transaction.getLatestUpdateToken(setup.bindingId),
    );
    expect(current).toMatchObject({ clientGeneration: 2, lifecycle: "CURRENT" });
  });

  it("aborts before send authority after cancellation", async () => {
    const setup = await setupDirect(true);
    const reserved = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (reserved.status !== "RESERVED") throw new Error("expected reservation");
    await installationService(secondCore).cancelSession(setup.authentication, {
      sessionId: "dispatch-occurrence",
      expectedRevision: 1,
    });
    await expect(
      claim(secondDispatch, setup.authentication, reserved.attempt.dispatchId),
    ).resolves.toMatchObject({ status: "ABORTED", attempt: { state: "ABORTED" } });
  });

  it("aborts before send authority after exact-session replacement", async () => {
    const setup = await setupDirect(true);
    const reserved = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (reserved.status !== "RESERVED") throw new Error("expected reservation");
    await installationService(secondCore).replaceSession(setup.authentication, {
      sessionId: "dispatch-occurrence",
      routineId: "dispatch-routine",
      startsAt: new Date("2026-09-12T07:00:00.000Z"),
      endsAt: new Date("2026-09-12T09:00:00.000Z"),
      query: {
        kind: "LINE_DIRECTION",
        siteId: 9192,
        transportMode: "BUS",
        lineId: 3,
        directionCode: 2,
      },
      expectedRevision: 1,
    });
    await expect(
      claim(secondDispatch, setup.authentication, reserved.attempt.dispatchId),
    ).resolves.toMatchObject({ status: "ABORTED", attempt: { state: "ABORTED" } });
  });

  it("performs zero transport sends when cancellation or replacement wins before target resolution", async () => {
    const runCase = async (
      mutate: (
        setup: Awaited<ReturnType<typeof setupDirect>>,
      ) => Promise<unknown>,
    ) => {
      const setup = await setupDirect(true);
      const baseResolver = createLiveActivityDeliveryResolver(
        firstDelivery,
        protector,
        { now: () => new Date(ACTIVE_AT) },
      );
      let cacheReads = 0;
      let transportSends = 0;
      const dispatcher = createLiveActivityDirectDispatcher({
        store: firstDispatch,
        resolver: {
          resolveStartTarget: baseResolver.resolveStartTarget.bind(baseResolver),
          resolveUpdateTarget: async (input) => {
            await mutate(setup);
            return await baseResolver.resolveUpdateTarget(input);
          },
        },
        providerTokenCache: {
          getToken: () => {
            cacheReads += 1;
            throw new Error("provider cache must remain untouched");
          },
          invalidateIfCurrent: () => false,
        },
        transport: {
          send: async () => {
            transportSends += 1;
            throw new Error("transport must remain untouched");
          },
          close: async () => undefined,
        },
        bundleId: "se.blick.commute",
        now: () => new Date(ACTIVE_AT),
      });

      const result = await dispatcher.dispatch({
        installationId: setup.authentication.installationId,
        bindingId: setup.bindingId,
        sessionRevision: 1,
        operation: "DIRECT_UPDATE",
        publication: {} as never,
        generatedAt: ACTIVE_AT,
        priority: 5,
      });
      expect(result).toMatchObject({
        outcome: "NOT_SENT",
        reason: "TARGET_NO_LONGER_AUTHORIZED",
        abortRecorded: true,
        attempt: { state: "ABORTED" },
      });
      expect(cacheReads).toBe(0);
      expect(transportSends).toBe(0);
    };

    await runCase(async (setup) => {
      await installationService(secondCore).cancelSession(setup.authentication, {
        sessionId: "dispatch-occurrence",
        expectedRevision: 1,
      });
    });
    await runCase(async (setup) => {
      await installationService(secondCore).replaceSession(setup.authentication, {
        sessionId: "dispatch-occurrence",
        routineId: "dispatch-routine",
        startsAt: new Date("2026-09-12T07:00:00.000Z"),
        endsAt: new Date("2026-09-12T09:00:00.000Z"),
        query: {
          kind: "LINE_DIRECTION",
          siteId: 9192,
          transportMode: "BUS",
          lineId: 3,
          directionCode: 2,
        },
        expectedRevision: 1,
      });
    });
  });

  it("creates constrained dispatch catalog objects without sensitive columns", async () => {
    const setup = await setupDirect(true);
    const seed = await firstDispatch.reserveDirectDispatch(
      reserveInput(setup.authentication, setup.bindingId, "DIRECT_UPDATE", EVENT),
    );
    if (seed.status !== "RESERVED") throw new Error("expected reservation");
    await firstDispatch.abortDirectDispatch({
      installationId: setup.authentication.installationId,
      dispatchId: seed.attempt.dispatchId,
      completedAt: ACTIVE_AT,
      retryAdvice: "NO_RETRY",
    });

    const tables = await firstSql!<{ table_name: string }[]>`
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = current_schema()
        AND table_name LIKE 'live_activity_direct_dispatch_%'
      ORDER BY table_name
    `;
    expect(tables.map(({ table_name }) => table_name)).toEqual([
      "live_activity_direct_dispatch_attempts",
      "live_activity_direct_dispatch_state",
    ]);
    const columns = await firstSql!<{ column_name: string }[]>`
      SELECT column_name
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name IN (
          'live_activity_direct_dispatch_attempts',
          'live_activity_direct_dispatch_state'
        )
    `;
    expect(columns.map(({ column_name }) => column_name).join(" ")).not.toMatch(
      /ciphertext|nonce|auth_tag|provider_jwt|private_key|request_body|raw_header/,
    );
    const indexes = await firstSql!<{ indexname: string }[]>`
      SELECT indexname
      FROM pg_indexes
      WHERE schemaname = current_schema()
        AND indexname IN (
          'live_activity_direct_dispatch_active_idx',
          'live_activity_direct_dispatch_start_blocker_idx',
          'live_activity_direct_dispatch_history_idx',
          'live_activity_direct_dispatch_in_flight_idx'
        )
      ORDER BY indexname
    `;
    expect(indexes).toHaveLength(4);

    for (const operation of ["START", "DIRECT_UPDATE"] as const) {
      await expect(firstSql!`
        INSERT INTO live_activity_direct_dispatch_attempts (
          dispatch_id, binding_id, installation_id, session_revision,
          operation_kind, event_timestamp, apns_environment, apns_request_id,
          state, created_at
        ) VALUES (
          ${randomUUID()}, ${setup.bindingId},
          ${setup.authentication.installationId}, 1, ${operation},
          ${EVENT + (operation === "START" ? 1 : 2)}, 'SANDBOX',
          ${randomUUID()}, 'RESERVED', ${ACTIVE_AT}
        )
      `).rejects.toMatchObject({ code: "23514" });
    }

    for (const result of [
      { state: "ACCEPTED", retryAdvice: "NO_RETRY", authority: "MATCHED" },
      {
        state: "REJECTED",
        retryAdvice: "PERMANENT_PAYLOAD_FAILURE",
        authority: "NOT_CHECKED",
      },
      {
        state: "RETRYABLE",
        retryAdvice: "RETRY_AFTER_APPLE_BACKOFF",
        authority: "NOT_CHECKED",
      },
    ] as const) {
      await expect(firstSql!`
        INSERT INTO live_activity_direct_dispatch_attempts (
          dispatch_id, binding_id, installation_id, session_revision,
          operation_kind, event_timestamp, update_token_server_revision,
          update_token_client_generation, apns_environment, apns_request_id,
          payload_fingerprint, state, apns_status, retry_advice,
          post_send_authority, created_at, in_flight_at, completed_at
        ) VALUES (
          ${randomUUID()}, ${setup.bindingId},
          ${setup.authentication.installationId}, 1, 'DIRECT_UPDATE',
          ${EVENT + 3 + ["ACCEPTED", "REJECTED", "RETRYABLE"].indexOf(result.state)},
          1, 1, 'SANDBOX', ${randomUUID()}, ${FINGERPRINT}, ${result.state},
          NULL, ${result.retryAdvice}, ${result.authority},
          ${ACTIVE_AT}, ${ACTIVE_AT}, ${ACTIVE_AT}
        )
      `).rejects.toMatchObject({ code: "23514" });
    }
  });
});
