import { randomUUID } from "node:crypto";
import postgres from "postgres";
import {
  afterAll,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
} from "vitest";
import { runLiveActivityDeliveryMigration } from "../scripts/migrateLiveActivityDelivery.js";
import { runLiveCommuteMigration } from "../scripts/migrateLiveCommute.js";
import {
  createLiveActivityDeliveryResolver,
} from "../src/liveCommute/apple/deliveryResolver.js";
import {
  activityKitPushToStartProtectionContext,
  createStoredPushToStartToken,
} from "../src/liveCommute/apple/deliveryModel.js";
import {
  createLiveActivityDeliveryService,
  LiveActivityDeliveryServiceError,
} from "../src/liveCommute/apple/deliveryService.js";
import {
  PostgresLiveActivityDeliveryStore,
} from "../src/liveCommute/apple/postgresLiveActivityDeliveryStore.js";
import {
  createAes256GcmActivityKitTokenProtector,
  type ActivityKitTokenProtector,
} from "../src/liveCommute/apple/tokenProtection.js";
import {
  createLiveCommuteInstallationService,
  type LiveCommuteConcreteSessionInput,
  type LiveCommuteInstallationAuthentication,
} from "../src/liveCommute/installationService.js";
import {
  PostgresLiveCommuteSessionStore,
  type LiveCommutePostgresSql,
} from "../src/liveCommute/postgresLiveCommuteSessionStore.js";
import {
  checkedLocalLiveCommuteTestDatabaseUrl,
  LIVE_COMMUTE_TEST_SCHEMA_PREFIX,
  liveCommuteTestSchemaConnectionUrl,
} from "./liveCommutePostgresTestSupport.js";

const RAW_TEST_DATABASE_URL =
  process.env.LIVE_COMMUTE_TEST_DATABASE_URL?.trim() || undefined;
const describeWithPostgres = RAW_TEST_DATABASE_URL == null ? describe.skip : describe;
const NOW = new Date("2026-09-12T06:30:00.000Z");
const SESSION_START = "2026-09-12T06:00:00.000Z";
const SESSION_END = "2026-09-12T08:00:00.000Z";
const TEST_PROTECTION_KEY = Buffer.alloc(32, 0x41);
const PUSH_TOKEN_ONE = Buffer.from([0x10, 0x11, 0x12, 0x13, 0x14]);
const PUSH_TOKEN_TWO = Buffer.from([0x20, 0x21, 0x22, 0x23, 0x24]);
const PUSH_TOKEN_THREE = Buffer.from([0x30, 0x31, 0x32, 0x33, 0x34]);
const UPDATE_TOKEN_ONE = Buffer.from([0x50, 0x51, 0x52, 0x53]);
const UPDATE_TOKEN_TWO = Buffer.from([0x60, 0x61, 0x62, 0x63]);

function sessionInput(
  sessionId: string,
  startsAt = SESSION_START,
  endsAt = SESSION_END,
  lineId = 57,
): LiveCommuteConcreteSessionInput {
  return {
    sessionId,
    routineId: "routine-live-activity",
    startsAt: new Date(startsAt),
    endsAt: new Date(endsAt),
    query: {
      kind: "LINE_DIRECTION",
      siteId: 9192,
      transportMode: "BUS",
      lineId,
      directionCode: 2,
    },
  };
}

describeWithPostgres("PostgreSQL Live Activity delivery store", () => {
  let adminSql: LiveCommutePostgresSql | undefined;
  let firstSql: LiveCommutePostgresSql | undefined;
  let secondSql: LiveCommutePostgresSql | undefined;
  let firstCoreStore: PostgresLiveCommuteSessionStore;
  let secondCoreStore: PostgresLiveCommuteSessionStore;
  let firstDeliveryStore: PostgresLiveActivityDeliveryStore;
  let secondDeliveryStore: PostgresLiveActivityDeliveryStore;
  let tokenProtector: ActivityKitTokenProtector;
  const schema = `${LIVE_COMMUTE_TEST_SCHEMA_PREFIX}la_${randomUUID().replaceAll("-", "")}`;

  function coreService(store: PostgresLiveCommuteSessionStore) {
    return createLiveCommuteInstallationService(store, { now: () => new Date(NOW) });
  }

  function deliveryService(store: PostgresLiveActivityDeliveryStore) {
    return createLiveActivityDeliveryService(store, tokenProtector, {
      now: () => new Date(NOW),
    });
  }

  async function issueInstallation(
    store = firstCoreStore,
  ): Promise<LiveCommuteInstallationAuthentication> {
    return await coreService(store).registerInstallation();
  }

  async function issueInstallationWithSession(
    sessionId: string,
    input = sessionInput(sessionId),
  ): Promise<LiveCommuteInstallationAuthentication> {
    const service = coreService(firstCoreStore);
    const authentication = await service.registerInstallation();
    await service.registerSession(authentication, input);
    return authentication;
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
      throw new Error("refusing to run outside the randomized live activity test schema");
    }
    if (firstScope[0]?.backend_pid === secondScope[0]?.backend_pid) {
      throw new Error("Live Activity integration stores must use independent connections");
    }
  }

  beforeAll(async () => {
    const connectionString = checkedLocalLiveCommuteTestDatabaseUrl(
      RAW_TEST_DATABASE_URL!,
    );
    adminSql = postgres(connectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_activity_test_admin" },
    });
    await adminSql`CREATE SCHEMA ${adminSql(schema)}`;

    const scopedConnectionString = liveCommuteTestSchemaConnectionUrl(
      connectionString,
      schema,
    );
    firstSql = postgres(scopedConnectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_activity_test_first" },
    });
    secondSql = postgres(scopedConnectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_activity_test_second" },
    });
    await assertIndependentScopedConnections();

    await runLiveCommuteMigration(scopedConnectionString);
    await runLiveActivityDeliveryMigration(scopedConnectionString);
    await runLiveCommuteMigration(scopedConnectionString);
    await runLiveActivityDeliveryMigration(scopedConnectionString);

    firstCoreStore = new PostgresLiveCommuteSessionStore(firstSql);
    secondCoreStore = new PostgresLiveCommuteSessionStore(secondSql);
    firstDeliveryStore = new PostgresLiveActivityDeliveryStore(firstSql);
    secondDeliveryStore = new PostgresLiveActivityDeliveryStore(secondSql);
  });

  beforeEach(async () => {
    await assertIndependentScopedConnections();
    await firstSql!`
      TRUNCATE live_activity_update_tokens,
               live_activity_delivery_bindings,
               live_activity_push_to_start_tokens,
               live_commute_sessions,
               live_commute_installations
    `;
    tokenProtector = createAes256GcmActivityKitTokenProtector(TEST_PROTECTION_KEY);
  });

  afterAll(async () => {
    await firstSql?.end({ timeout: 5 });
    await secondSql?.end({ timeout: 5 });
    if (adminSql != null) {
      if (!schema.startsWith(LIVE_COMMUTE_TEST_SCHEMA_PREFIX)) {
        throw new Error("refusing to remove an unexpected live activity test schema");
      }
      await adminSql`DROP SCHEMA IF EXISTS ${adminSql(schema)} CASCADE`;
      await adminSql.end({ timeout: 5 });
    }
  });

  it("persists protected start, binding, and update state across adapters", async () => {
    const authentication = await issueInstallationWithSession("persisted-delivery");
    const firstService = deliveryService(firstDeliveryStore);
    const registered = await firstService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const binding = await firstService.createDeliveryBinding(authentication, {
      sessionId: "persisted-delivery",
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
    });

    const resolver = createLiveActivityDeliveryResolver(
      secondDeliveryStore,
      tokenProtector,
      { now: () => new Date(NOW) },
    );
    const start = await resolver.resolveStartTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });
    await deliveryService(secondDeliveryStore).attachAppleActivityIdentifier(
      authentication,
      {
        bindingId: binding.binding.bindingId,
        appleActivityId: "synthetic-activity-1",
      },
    );
    await firstService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const update = await resolver.resolveUpdateTarget({
      installationId: authentication.installationId,
      bindingId: binding.binding.bindingId,
    });
    const protectedRows = await secondSql!<
      { token_ciphertext: Buffer; token_digest: string }[]
    >`
      SELECT token_ciphertext, token_digest
      FROM live_activity_push_to_start_tokens
      WHERE installation_id = ${authentication.installationId}
    `;

    expect(registered).toMatchObject({
      status: "REGISTERED",
      token: { clientGeneration: 1, serverRevision: 1, lifecycle: "CURRENT" },
    });
    expect(start?.pushToStartToken).toEqual(PUSH_TOKEN_ONE);
    expect(update?.kind).toBe("DIRECT_UPDATE");
    if (update?.kind !== "DIRECT_UPDATE") {
      throw new Error("expected a direct update target");
    }
    expect(update.updateToken).toEqual(UPDATE_TOKEN_ONE);
    expect(protectedRows[0]?.token_ciphertext.equals(PUSH_TOKEN_ONE)).toBe(false);
    expect(protectedRows[0]?.token_digest).toMatch(/^[0-9a-f]{64}$/);
    expect(JSON.stringify(registered)).not.toContain(PUSH_TOKEN_ONE.toString("hex"));
    await expect(
      deliveryService(secondDeliveryStore).invalidateUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        expectedClientGeneration: 1,
      }),
    ).resolves.toMatchObject({ status: "INVALIDATED" });
    await expect(
      resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).resolves.toBeUndefined();
  });

  it("serializes identical and conflicting concurrent initial token registration", async () => {
    const firstAuthentication = await issueInstallation();
    const firstService = deliveryService(firstDeliveryStore);
    const secondService = deliveryService(secondDeliveryStore);
    const identical = await Promise.all([
      firstService.registerPushToStartToken(firstAuthentication, {
        token: PUSH_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
      secondService.registerPushToStartToken(firstAuthentication, {
        token: PUSH_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ]);
    expect(identical.map(({ status }) => status).sort()).toEqual([
      "REGISTERED",
      "UNCHANGED",
    ]);

    const secondAuthentication = await issueInstallation(secondCoreStore);
    const conflicting = await Promise.allSettled([
      firstService.registerPushToStartToken(secondAuthentication, {
        token: PUSH_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
      secondService.registerPushToStartToken(secondAuthentication, {
        token: PUSH_TOKEN_TWO,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ]);
    expect(conflicting.filter(({ status }) => status === "fulfilled")).toHaveLength(1);
    expect(conflicting.find(({ status }) => status === "rejected")).toMatchObject({
      status: "rejected",
      reason: expect.objectContaining({ code: "TOKEN_GENERATION_CONFLICT" }),
    });

    const currentRows = await firstSql!<
      { installation_id: string; total: number; current_count: number }[]
    >`
      SELECT installation_id,
             count(*)::integer AS total,
             count(*) FILTER (WHERE lifecycle = 'CURRENT')::integer AS current_count
      FROM live_activity_push_to_start_tokens
      GROUP BY installation_id
      ORDER BY installation_id
    `;
    expect(currentRows).toHaveLength(2);
    expect(currentRows.every(({ total, current_count }) => total === 1 && current_count === 1)).toBe(
      true,
    );
  });

  it("keeps generation three authoritative across both concurrent call orders", async () => {
    const runRace = async (higherFirst: boolean) => {
      const authentication = await issueInstallation();
      const firstService = deliveryService(firstDeliveryStore);
      const secondService = deliveryService(secondDeliveryStore);
      await firstService.registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      });
      const generationTwo = () =>
        firstService.registerPushToStartToken(authentication, {
          token: PUSH_TOKEN_TWO,
          clientGeneration: 2,
          environment: "SANDBOX",
        });
      const generationThree = () =>
        secondService.registerPushToStartToken(authentication, {
          token: PUSH_TOKEN_THREE,
          clientGeneration: 3,
          environment: "PRODUCTION",
        });
      const attempts = await Promise.allSettled(
        higherFirst
          ? [generationThree(), generationTwo()]
          : [generationTwo(), generationThree()],
      );
      expect(attempts.some((attempt) =>
        attempt.status === "fulfilled" &&
        attempt.value.token.clientGeneration === 3,
      )).toBe(true);
      const latest = await secondDeliveryStore.withInstallationTransaction(
        authentication.installationId,
        async (transaction) => await transaction.getLatestPushToStartToken(),
      );
      expect(latest).toMatchObject({
        clientGeneration: 3,
        lifecycle: "CURRENT",
        environment: "PRODUCTION",
      });
      expect(
        tokenProtector.unprotect(
          latest!.protectedToken,
          activityKitPushToStartProtectionContext(
            authentication.installationId,
            3,
            "PRODUCTION",
          ),
        ),
      ).toEqual(PUSH_TOKEN_THREE);
      await expect(generationTwo()).rejects.toMatchObject({
        code: "TOKEN_GENERATION_CONFLICT",
      });
      return authentication.installationId;
    };

    const installationIds = [await runRace(false), await runRace(true)];
    const rows = await firstSql!<{ installation_id: string; current_count: number }[]>`
      SELECT installation_id,
             count(*) FILTER (WHERE lifecycle = 'CURRENT')::integer AS current_count
      FROM live_activity_push_to_start_tokens
      WHERE installation_id IN ${firstSql!(installationIds)}
      GROUP BY installation_id
    `;
    expect(rows).toHaveLength(2);
    expect(rows.every(({ current_count }) => current_count === 1)).toBe(true);
  });

  it("makes revocation authoritative when it races token rotation", async () => {
    const authentication = await issueInstallationWithSession("revocation-race");
    const firstDeliveryService = deliveryService(firstDeliveryStore);
    await firstDeliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const binding = await firstDeliveryService.createDeliveryBinding(authentication, {
      sessionId: "revocation-race",
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
    });

    const attempts = await Promise.allSettled([
      firstDeliveryService.registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_TWO,
        clientGeneration: 2,
        environment: "SANDBOX",
      }),
      coreService(secondCoreStore).revokeInstallation(authentication),
    ]);
    expect(attempts[1]).toMatchObject({ status: "fulfilled" });

    const resolver = createLiveActivityDeliveryResolver(
      secondDeliveryStore,
      tokenProtector,
      { now: () => new Date(NOW) },
    );
    await expect(
      resolver.resolveStartTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).resolves.toBeUndefined();
    await expect(
      deliveryService(secondDeliveryStore).registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_THREE,
        clientGeneration: 3,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
    const installationRows = await firstSql!<{ revoked: boolean; registered: number }[]>`
      SELECT i.revoked_at IS NOT NULL AS revoked,
             count(*) FILTER (WHERE s.lifecycle = 'REGISTERED')::integer AS registered
      FROM live_commute_installations AS i
      LEFT JOIN live_commute_sessions AS s
        ON s.installation_id = i.installation_id
      WHERE i.installation_id = ${authentication.installationId}
      GROUP BY i.revoked_at
    `;
    expect(installationRows).toEqual([{ revoked: true, registered: 0 }]);
  });

  it("invalidates old delivery authority when replacement races update rotation", async () => {
    const input = sessionInput("replacement-race");
    const authentication = await issueInstallationWithSession(input.sessionId, input);
    const firstDeliveryService = deliveryService(firstDeliveryStore);
    await firstDeliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const binding = await firstDeliveryService.createDeliveryBinding(authentication, {
      sessionId: input.sessionId,
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
    });
    await firstDeliveryService.registerUpdateToken(authentication, {
      bindingId: binding.binding.bindingId,
      token: UPDATE_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    const attempts = await Promise.allSettled([
      firstDeliveryService.registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: UPDATE_TOKEN_TWO,
        clientGeneration: 2,
        environment: "SANDBOX",
      }),
      coreService(secondCoreStore).replaceSession(authentication, {
        ...input,
        expectedRevision: 1,
        endsAt: new Date("2026-09-12T08:30:00.000Z"),
        query: {
          kind: "LINE_DIRECTION",
          siteId: 9192,
          transportMode: "BUS",
          lineId: 3,
          directionCode: 1,
        },
      }),
    ]);
    expect(attempts[1]).toMatchObject({ status: "fulfilled" });

    const resolver = createLiveActivityDeliveryResolver(
      secondDeliveryStore,
      tokenProtector,
      { now: () => new Date(NOW) },
    );
    await expect(
      resolver.resolveUpdateTarget({
        installationId: authentication.installationId,
        bindingId: binding.binding.bindingId,
      }),
    ).resolves.toBeUndefined();
    await expect(
      deliveryService(secondDeliveryStore).registerUpdateToken(authentication, {
        bindingId: binding.binding.bindingId,
        token: Buffer.from([0x70, 0x71]),
        clientGeneration: 3,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });
    const authorityRows = await firstSql!<
      { binding_revision: number; session_revision: number }[]
    >`
      SELECT b.session_revision AS binding_revision,
             s.revision AS session_revision
      FROM live_activity_delivery_bindings AS b
      INNER JOIN live_commute_sessions AS s
        ON s.installation_id = b.installation_id
       AND s.session_id = b.session_id
      WHERE b.binding_id = ${binding.binding.bindingId}
    `;
    expect(authorityRows).toEqual([{ binding_revision: 1, session_revision: 2 }]);
  });

  it("cannot leave a newly created binding eligible when cancellation races it", async () => {
    const authentication = await issueInstallationWithSession("cancellation-race");
    const firstDeliveryService = deliveryService(firstDeliveryStore);
    await firstDeliveryService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });

    const attempts = await Promise.allSettled([
      firstDeliveryService.createDeliveryBinding(authentication, {
        sessionId: "cancellation-race",
        sessionRevision: 1,
        strategy: "BROADCAST_CHANNEL",
      }),
      coreService(secondCoreStore).cancelSession(authentication, {
        sessionId: "cancellation-race",
        expectedRevision: 1,
      }),
    ]);
    expect(attempts[1]).toMatchObject({ status: "fulfilled" });

    const rows = await firstSql!<{ binding_id: string }[]>`
      SELECT binding_id::text AS binding_id
      FROM live_activity_delivery_bindings
      WHERE installation_id = ${authentication.installationId}
    `;
    if (rows[0] != null) {
      const resolver = createLiveActivityDeliveryResolver(
        secondDeliveryStore,
        tokenProtector,
        { now: () => new Date(NOW) },
      );
      await expect(
        resolver.resolveStartTarget({
          installationId: authentication.installationId,
          bindingId: rows[0].binding_id,
        }),
      ).resolves.toBeUndefined();
    }
    await expect(
      deliveryService(secondDeliveryStore).createDeliveryBinding(authentication, {
        sessionId: "cancellation-race",
        sessionRevision: 1,
        strategy: "BROADCAST_CHANNEL",
      }),
    ).rejects.toMatchObject({ code: "SESSION_NOT_AUTHORIZED" });
  });

  it("keeps an explicitly invalidated binding terminal under replay", async () => {
    const authentication = await issueInstallationWithSession("invalidated-binding");
    const firstService = deliveryService(firstDeliveryStore);
    await firstService.registerPushToStartToken(authentication, {
      token: PUSH_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const created = await firstService.createDeliveryBinding(authentication, {
      sessionId: "invalidated-binding",
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
      appleActivityId: "synthetic-terminal-activity",
    });
    await firstService.registerUpdateToken(authentication, {
      bindingId: created.binding.bindingId,
      token: UPDATE_TOKEN_ONE,
      clientGeneration: 1,
      environment: "SANDBOX",
    });
    const invalidated = await firstService.invalidateDeliveryBinding(authentication, {
      bindingId: created.binding.bindingId,
    });

    const replay = await deliveryService(secondDeliveryStore).createDeliveryBinding(
      authentication,
      {
        sessionId: "invalidated-binding",
        sessionRevision: 1,
        strategy: "DIRECT_TOKEN",
        appleActivityId: "synthetic-terminal-activity",
      },
    );
    expect(invalidated.status).toBe("INVALIDATED");
    expect(replay).toMatchObject({
      status: "ALREADY_TERMINAL",
      binding: { bindingId: created.binding.bindingId, lifecycle: "INVALIDATED" },
    });
    await expect(
      firstService.registerUpdateToken(authentication, {
        bindingId: created.binding.bindingId,
        token: UPDATE_TOKEN_TWO,
        clientGeneration: 2,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_INACTIVE" });
    const rows = await firstSql!<
      { binding_count: number; current_tokens: number; invalidated_tokens: number }[]
    >`
      SELECT count(DISTINCT b.binding_id)::integer AS binding_count,
             count(u.*) FILTER (WHERE u.lifecycle = 'CURRENT')::integer AS current_tokens,
             count(u.*) FILTER (WHERE u.lifecycle = 'INVALIDATED')::integer AS invalidated_tokens
      FROM live_activity_delivery_bindings AS b
      LEFT JOIN live_activity_update_tokens AS u ON u.binding_id = b.binding_id
      WHERE b.binding_id = ${created.binding.bindingId}
    `;
    expect(rows).toEqual([
      { binding_count: 1, current_tokens: 0, invalidated_tokens: 1 },
    ]);
  });

  it("enforces direct versus broadcast update-token strategy in PostgreSQL", async () => {
    const service = coreService(firstCoreStore);
    const authentication = await service.registerInstallation();
    await service.registerSession(
      authentication,
      sessionInput(
        "broadcast-session",
        "2026-09-12T06:00:00.000Z",
        "2026-09-12T07:00:00.000Z",
      ),
    );
    await service.registerSession(
      authentication,
      sessionInput(
        "direct-session",
        "2026-09-12T07:00:00.000Z",
        "2026-09-12T08:00:00.000Z",
      ),
    );
    const appleService = deliveryService(firstDeliveryStore);
    const broadcast = await appleService.createDeliveryBinding(authentication, {
      sessionId: "broadcast-session",
      sessionRevision: 1,
      strategy: "BROADCAST_CHANNEL",
    });
    await expect(
      appleService.registerUpdateToken(authentication, {
        bindingId: broadcast.binding.bindingId,
        token: UPDATE_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_STRATEGY_MISMATCH" });

    await expect(firstSql!`
      INSERT INTO live_activity_update_tokens (
        binding_id, installation_id, binding_strategy, server_revision,
        client_generation, apns_environment, lifecycle, token_digest,
        token_ciphertext, token_nonce, token_auth_tag, created_at, updated_at,
        replaced_at, invalidated_at
      ) VALUES (
        ${broadcast.binding.bindingId}, ${authentication.installationId}, 'DIRECT_TOKEN',
        1, 1, 'SANDBOX', 'CURRENT', ${"a".repeat(64)}, ${Buffer.alloc(4, 1)},
        ${Buffer.alloc(12, 2)}, ${Buffer.alloc(16, 3)}, ${NOW}, ${NOW}, NULL, NULL
      )
    `).rejects.toMatchObject({ code: "23503" });

    const direct = await appleService.createDeliveryBinding(authentication, {
      sessionId: "direct-session",
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
    });
    await expect(
      appleService.registerUpdateToken(authentication, {
        bindingId: direct.binding.bindingId,
        token: UPDATE_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ).resolves.toMatchObject({ status: "REGISTERED" });
  });

  it("creates the expected catalog objects and enforces database constraints", async () => {
    const tables = await firstSql!<{ table_name: string }[]>`
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = current_schema()
        AND table_name LIKE 'live_activity_%'
      ORDER BY table_name
    `;
    expect(tables.map(({ table_name }) => table_name)).toEqual([
      "live_activity_delivery_bindings",
      "live_activity_push_to_start_tokens",
      "live_activity_update_tokens",
    ]);
    const indexes = await firstSql!<{ indexname: string }[]>`
      SELECT indexname
      FROM pg_indexes
      WHERE schemaname = current_schema()
        AND indexname IN (
          'live_activity_delivery_bindings_activity_idx',
          'live_activity_push_to_start_tokens_current_idx',
          'live_activity_update_tokens_current_idx'
        )
      ORDER BY indexname
    `;
    expect(indexes.map(({ indexname }) => indexname)).toEqual([
      "live_activity_delivery_bindings_activity_idx",
      "live_activity_push_to_start_tokens_current_idx",
      "live_activity_update_tokens_current_idx",
    ]);
    const sessionForeignKey = await firstSql!<{ definition: string }[]>`
      SELECT pg_get_constraintdef(oid) AS definition
      FROM pg_constraint
      WHERE connamespace = current_schema()::regnamespace
        AND conname = 'live_activity_delivery_bindings_session_fk'
    `;
    expect(sessionForeignKey[0]?.definition).toContain(
      "FOREIGN KEY (installation_id, session_id)",
    );
    expect(sessionForeignKey[0]?.definition).not.toContain("session_revision");

    const authentication = await issueInstallationWithSession("constraint-session");
    await expect(firstSql!`
      INSERT INTO live_activity_push_to_start_tokens (
        installation_id, server_revision, client_generation, apns_environment,
        lifecycle, token_digest, token_ciphertext, token_nonce, token_auth_tag,
        created_at, updated_at, replaced_at, invalidated_at
      ) VALUES (
        ${authentication.installationId}, 1, 1, 'SANDBOX', 'CURRENT',
        ${"b".repeat(64)}, ${Buffer.alloc(4, 4)}, ${Buffer.alloc(11, 5)},
        ${Buffer.alloc(16, 6)}, ${NOW}, ${NOW}, NULL, NULL
      )
    `).rejects.toMatchObject({ code: "23514" });

    const binding = await deliveryService(firstDeliveryStore).createDeliveryBinding(
      authentication,
      {
        sessionId: "constraint-session",
        sessionRevision: 1,
        strategy: "DIRECT_TOKEN",
      },
    );
    const appleService = deliveryService(firstDeliveryStore);
    await appleService.attachAppleActivityIdentifier(authentication, {
      bindingId: binding.binding.bindingId,
      appleActivityId: "unique-activity-id",
    });
    await coreService(firstCoreStore).registerSession(
      authentication,
      sessionInput(
        "constraint-session-2",
        SESSION_END,
        "2026-09-12T09:00:00.000Z",
      ),
    );
    const secondBinding = await appleService.createDeliveryBinding(authentication, {
      sessionId: "constraint-session-2",
      sessionRevision: 1,
      strategy: "DIRECT_TOKEN",
    });
    await expect(
      appleService.attachAppleActivityIdentifier(authentication, {
        bindingId: secondBinding.binding.bindingId,
        appleActivityId: "unique-activity-id",
      }),
    ).rejects.toMatchObject({ code: "DELIVERY_BINDING_CONFLICT" });
    await expect(firstSql!`
      INSERT INTO live_activity_delivery_bindings (
        binding_id, installation_id, session_id, session_revision, delivery_strategy,
        lifecycle, apple_activity_id, created_at, updated_at, ended_at, invalidated_at
      ) VALUES (
        ${randomUUID()}, ${authentication.installationId}, 'constraint-session', 1,
        'DIRECT_TOKEN', 'PENDING_START', NULL, ${NOW}, ${NOW}, NULL, NULL
      )
    `).rejects.toMatchObject({ code: "23505" });
    await expect(firstSql!`
      UPDATE live_activity_delivery_bindings
      SET lifecycle = 'ENDED'
      WHERE binding_id = ${binding.binding.bindingId}
    `).rejects.toMatchObject({ code: "23514" });
    await expect(firstSql!`
      INSERT INTO live_activity_delivery_bindings (
        binding_id, installation_id, session_id, session_revision, delivery_strategy,
        lifecycle, apple_activity_id, created_at, updated_at, ended_at, invalidated_at
      ) VALUES (
        ${randomUUID()}, ${authentication.installationId}, 'missing-session', 1,
        'DIRECT_TOKEN', 'PENDING_START', NULL, ${NOW}, ${NOW}, NULL, NULL
      )
    `).rejects.toMatchObject({ code: "23503" });
  });

  it("rolls back callback failures and releases the connection for reuse", async () => {
    const authentication = await issueInstallation();
    const protectedToken = tokenProtector.protect(
      PUSH_TOKEN_ONE,
      activityKitPushToStartProtectionContext(
        authentication.installationId,
        1,
        "SANDBOX",
      ),
    );
    const callbackFailure = new LiveActivityDeliveryServiceError("TOKEN_NOT_FOUND");

    await expect(
      firstDeliveryStore.withInstallationTransaction(
        authentication.installationId,
        async (transaction) => {
          await transaction.savePushToStartToken(
            createStoredPushToStartToken({
              installationId: authentication.installationId,
              serverRevision: 1,
              clientGeneration: 1,
              environment: "SANDBOX",
              lifecycle: "CURRENT",
              protectedToken,
              createdAt: NOW,
              updatedAt: NOW,
              replacedAt: null,
              invalidatedAt: null,
            }),
          );
          throw callbackFailure;
        },
      ),
    ).rejects.toBe(callbackFailure);

    const rolledBack = await secondSql!<{ count: number }[]>`
      SELECT count(*)::integer AS count
      FROM live_activity_push_to_start_tokens
      WHERE installation_id = ${authentication.installationId}
    `;
    expect(rolledBack[0]?.count).toBe(0);
    await expect(
      deliveryService(firstDeliveryStore).registerPushToStartToken(authentication, {
        token: PUSH_TOKEN_ONE,
        clientGeneration: 1,
        environment: "SANDBOX",
      }),
    ).resolves.toMatchObject({ status: "REGISTERED" });
    const current = await firstDeliveryStore.withInstallationTransaction(
      authentication.installationId,
      async (transaction) => await transaction.getLatestPushToStartToken(),
    );
    await expect(
      firstDeliveryStore.withInstallationTransaction(
        authentication.installationId,
        async (transaction) => {
          await transaction.savePushToStartToken(
            createStoredPushToStartToken({
              ...current!,
              lifecycle: "REPLACED",
              replacedAt: NOW,
              invalidatedAt: null,
            }),
          );
        },
      ),
    ).rejects.toMatchObject({
      code: "LIVE_ACTIVITY_DELIVERY_PERSISTENCE_FAILED",
    });
    await expect(
      secondDeliveryStore.withInstallationTransaction(
        authentication.installationId,
        async (transaction) => await transaction.getLatestPushToStartToken(),
      ),
    ).resolves.toMatchObject({ lifecycle: "CURRENT" });
    await expect(firstSql!`SELECT 1 AS reusable`).resolves.toMatchObject([
      { reusable: 1 },
    ]);
  });
});
