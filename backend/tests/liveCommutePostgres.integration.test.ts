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
import { runLiveCommuteMigration } from "../scripts/migrateLiveCommute.js";
import {
  createLiveCommuteInstallationService,
  LiveCommuteSessionServiceError,
  type LiveCommuteConcreteSessionInput,
} from "../src/liveCommute/installationService.js";
import {
  PostgresLiveCommuteSessionStore,
  type LiveCommutePostgresSql,
} from "../src/liveCommute/postgresLiveCommuteSessionStore.js";
import { liveCommuteSessionVersionRef } from "../src/liveCommute/sessionStore.js";
import {
  checkedLocalLiveCommuteTestDatabaseUrl,
  LIVE_COMMUTE_TEST_SCHEMA_PREFIX,
  liveCommuteTestSchemaConnectionUrl,
} from "./liveCommutePostgresTestSupport.js";

const RAW_TEST_DATABASE_URL =
  process.env.LIVE_COMMUTE_TEST_DATABASE_URL?.trim() || undefined;
const describeWithPostgres = RAW_TEST_DATABASE_URL == null ? describe.skip : describe;
describe("PostgreSQL live commute test safeguards", () => {
  it.each([
    [
      "a non-PostgreSQL protocol",
      "https://blick_test:blick_test_ci_only@127.0.0.1:5432/blick_test",
    ],
    [
      "a database without a test marker",
      "postgresql://blick_test:blick_test_ci_only@127.0.0.1:5432/blick",
    ],
    [
      "a production-marked database",
      "postgresql://blick_test:blick_test_ci_only@127.0.0.1:5432/blick_prod_test",
    ],
    [
      "a non-loopback host",
      "postgresql://blick_test:blick_test_ci_only@database.example.invalid:5432/blick_test",
    ],
  ])("rejects %s", (_description, connectionString) => {
    expect(() => checkedLocalLiveCommuteTestDatabaseUrl(connectionString)).toThrow();
  });

  it("accepts a dedicated loopback test database", () => {
    const checked = checkedLocalLiveCommuteTestDatabaseUrl(
      "postgresql://blick_test:blick_test_ci_only@127.0.0.1:5432/blick_test",
    );

    expect(new URL(checked)).toMatchObject({
      hostname: "127.0.0.1",
      pathname: "/blick_test",
      protocol: "postgresql:",
    });
  });
});

function sessionInput(
  sessionId: string,
  startsAt: string,
  endsAt: string,
  siteId = 9192,
): LiveCommuteConcreteSessionInput {
  return {
    sessionId,
    routineId: "routine-1",
    startsAt: new Date(startsAt),
    endsAt: new Date(endsAt),
    query: {
      kind: "LINE_DIRECTION",
      siteId,
      transportMode: "BUS",
      lineId: 57,
      directionCode: 2,
    },
  };
}

describeWithPostgres("PostgreSQL live commute session store", () => {
  let adminSql: LiveCommutePostgresSql | undefined;
  let firstSql: LiveCommutePostgresSql | undefined;
  let secondSql: LiveCommutePostgresSql | undefined;
  let firstStore: PostgresLiveCommuteSessionStore;
  let secondStore: PostgresLiveCommuteSessionStore;
  const schema = `${LIVE_COMMUTE_TEST_SCHEMA_PREFIX}${randomUUID().replaceAll("-", "")}`;

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
      throw new Error("refusing to run outside the randomized live commute test schema");
    }
    if (firstScope[0]?.backend_pid === secondScope[0]?.backend_pid) {
      throw new Error("PostgreSQL integration stores must use independent connections");
    }
  }

  beforeAll(async () => {
    const connectionString = checkedLocalLiveCommuteTestDatabaseUrl(
      RAW_TEST_DATABASE_URL!,
    );
    adminSql = postgres(connectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_commute_test_admin" },
    });
    await adminSql`CREATE SCHEMA ${adminSql(schema)}`;

    const scopedConnectionString = liveCommuteTestSchemaConnectionUrl(
      connectionString,
      schema,
    );
    firstSql = postgres(scopedConnectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_commute_test_first" },
    });
    secondSql = postgres(scopedConnectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_commute_test_second" },
    });
    await assertIndependentScopedConnections();
    await runLiveCommuteMigration(scopedConnectionString);
    await runLiveCommuteMigration(scopedConnectionString);
    firstStore = new PostgresLiveCommuteSessionStore(firstSql);
    secondStore = new PostgresLiveCommuteSessionStore(secondSql);
  });

  beforeEach(async () => {
    await assertIndependentScopedConnections();
    await firstSql!`
      TRUNCATE live_commute_sessions, live_commute_installations
    `;
  });

  afterAll(async () => {
    await firstSql?.end({ timeout: 5 });
    await secondSql?.end({ timeout: 5 });
    if (adminSql != null) {
      if (!schema.startsWith(LIVE_COMMUTE_TEST_SCHEMA_PREFIX)) {
        throw new Error("refusing to remove an unexpected test schema");
      }
      await adminSql`DROP SCHEMA IF EXISTS ${adminSql(schema)} CASCADE`;
      await adminSql.end({ timeout: 5 });
    }
  });

  it("persists ownership and lifecycle across independent store instances", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };
    const registered = await firstService.registerSession(
      authentication,
      sessionInput(
        "occurrence-1",
        "2026-09-12T06:00:00.000Z",
        "2026-09-12T07:00:00.000Z",
      ),
    );
    const repeated = await firstService.registerSession(
      authentication,
      sessionInput(
        "occurrence-1",
        "2026-09-12T06:00:00.000Z",
        "2026-09-12T07:00:00.000Z",
      ),
    );

    const reloadedService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const reloaded = await reloadedService.listSessions(authentication);
    const eligible = await secondStore.listEligibleSessions(now);
    const revalidated = await secondStore.revalidateSessionVersions([
      liveCommuteSessionVersionRef(registered.session),
    ]);
    const installationRows = await secondSql!<
      { credential_digest: string; row_text: string }[]
    >`
      SELECT credential_digest, row_to_json(i)::text AS row_text
      FROM live_commute_installations AS i
      WHERE installation_id = ${issued.installationId}
    `;

    expect(reloaded).toHaveLength(1);
    expect(repeated.status).toBe("UNCHANGED");
    expect(reloaded[0]?.session.sessionId).toBe("occurrence-1");
    expect(eligible).toHaveLength(1);
    expect(revalidated).toHaveLength(1);
    expect(installationRows[0]?.credential_digest).toMatch(/^[0-9a-f]{64}$/);
    expect(installationRows[0]?.row_text).not.toContain(issued.bearerCredential);
    expect(JSON.stringify(reloaded)).not.toContain(issued.bearerCredential);
  });

  it("preserves cancelled tombstones and revocation across reconstructed services", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const input = sessionInput(
      "durable-tombstone",
      "2026-09-12T06:00:00.000Z",
      "2026-09-12T07:00:00.000Z",
    );
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };
    await firstService.registerSession(authentication, input);
    await firstService.cancelSession(authentication, {
      sessionId: input.sessionId,
      expectedRevision: 1,
    });

    const reloadedService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const replay = await reloadedService.registerSession(authentication, input);
    expect(replay).toMatchObject({
      status: "ALREADY_CANCELLED",
      session: { lifecycle: "CANCELLED", revision: 1 },
    });
    const repeatedCancellation = await reloadedService.cancelSession(authentication, {
      sessionId: input.sessionId,
      expectedRevision: 1,
    });
    expect(repeatedCancellation).toMatchObject({
      status: "UNCHANGED",
      session: { lifecycle: "CANCELLED", revision: 1 },
    });
    await expect(
      reloadedService.replaceSession(authentication, {
        ...input,
        expectedRevision: 1,
        query: {
          kind: "LINE_DIRECTION",
          siteId: 9192,
          transportMode: "BUS",
          lineId: 3,
          directionCode: 2,
        },
      }),
    ).rejects.toMatchObject({ code: "SESSION_CANCELLED" });
    expect(await reloadedService.listSessions(authentication)).toMatchObject([
      { lifecycle: "CANCELLED", revision: 1 },
    ]);
    expect((await reloadedService.revokeInstallation(authentication)).status).toBe(
      "REVOKED",
    );

    const restartedService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    await expect(
      restartedService.authenticateInstallation(authentication),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
    await expect(
      restartedService.registerSession(authentication, input),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
  });

  it("allows touching half-open windows on separate connections", async () => {
    const now = new Date("2026-09-12T06:00:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };

    const results = await Promise.all([
      firstService.registerSession(
        authentication,
        sessionInput(
          "touch-a",
          "2026-09-12T06:00:00.000Z",
          "2026-09-12T07:00:00.000Z",
        ),
      ),
      secondService.registerSession(
        authentication,
        sessionInput(
          "touch-b",
          "2026-09-12T07:00:00.000Z",
          "2026-09-12T08:00:00.000Z",
        ),
      ),
    ]);

    expect(results.map(({ status }) => status).sort()).toEqual([
      "REGISTERED",
      "REGISTERED",
    ]);
  });

  it("allows cross-installation overlap while isolating ownership", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const [firstInstallation, secondInstallation] = await Promise.all([
      firstService.registerInstallation(),
      secondService.registerInstallation(),
    ]);
    const firstAuthentication = {
      installationId: firstInstallation.installationId,
      bearerCredential: firstInstallation.bearerCredential,
    };
    const secondAuthentication = {
      installationId: secondInstallation.installationId,
      bearerCredential: secondInstallation.bearerCredential,
    };

    const registrations = await Promise.all([
      firstService.registerSession(
        firstAuthentication,
        sessionInput(
          "first-installation-occurrence",
          "2026-09-12T06:00:00.000Z",
          "2026-09-12T07:00:00.000Z",
          9192,
        ),
      ),
      secondService.registerSession(
        secondAuthentication,
        sessionInput(
          "second-installation-occurrence",
          "2026-09-12T06:00:00.000Z",
          "2026-09-12T07:00:00.000Z",
          9700,
        ),
      ),
    ]);
    expect(registrations.map(({ status }) => status)).toEqual([
      "REGISTERED",
      "REGISTERED",
    ]);
    expect(await firstStore.listEligibleSessions(now)).toHaveLength(2);

    const crossInstallationProof = {
      installationId: secondInstallation.installationId,
      bearerCredential: firstInstallation.bearerCredential,
    };
    await expect(
      firstService.listSessions(crossInstallationProof),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
    await expect(
      firstService.cancelSession(crossInstallationProof, {
        sessionId: "second-installation-occurrence",
        expectedRevision: 1,
      }),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });
    await expect(
      firstStore.withInstallationTransaction(
        firstInstallation.installationId,
        async (transaction) =>
          await transaction.getSession("second-installation-occurrence"),
      ),
    ).resolves.toBeUndefined();
    expect(await secondService.listSessions(secondAuthentication)).toMatchObject([
      {
        lifecycle: "REGISTERED",
        revision: 1,
        session: { sessionId: "second-installation-occurrence" },
      },
    ]);
  });

  it("serializes overlapping registration attempts on the installation row", async () => {
    const now = new Date("2026-09-12T06:00:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };

    const attempts = await Promise.allSettled([
      firstService.registerSession(
        authentication,
        sessionInput(
          "overlap-a",
          "2026-09-12T06:00:00.000Z",
          "2026-09-12T07:00:00.000Z",
        ),
      ),
      secondService.registerSession(
        authentication,
        sessionInput(
          "overlap-b",
          "2026-09-12T06:30:00.000Z",
          "2026-09-12T07:30:00.000Z",
        ),
      ),
    ]);

    expect(attempts.filter(({ status }) => status === "fulfilled")).toHaveLength(1);
    const failure = attempts.find(({ status }) => status === "rejected");
    expect(failure).toMatchObject({
      status: "rejected",
      reason: expect.objectContaining({
        code: "SESSION_OVERLAP",
      }),
    });
    const rows = await firstSql!<{ count: number }[]>`
      SELECT count(*)::integer AS count FROM live_commute_sessions
    `;
    expect(rows[0]?.count).toBe(1);
  });

  it("allows only one concurrent replacement for the same expected revision", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };
    const initial = sessionInput(
      "replacement-cas",
      "2026-09-12T06:00:00.000Z",
      "2026-09-12T07:00:00.000Z",
    );
    await firstService.registerSession(authentication, initial);

    const firstReplacement = {
      ...initial,
      expectedRevision: 1,
      startsAt: new Date("2026-09-12T05:45:00.000Z"),
      endsAt: new Date("2026-09-12T06:45:00.000Z"),
      query: {
        kind: "LINE_DIRECTION" as const,
        siteId: 9192,
        transportMode: "BUS" as const,
        lineId: 3,
        directionCode: 2,
      },
    };
    const secondReplacement = {
      ...initial,
      expectedRevision: 1,
      startsAt: new Date("2026-09-12T06:15:00.000Z"),
      endsAt: new Date("2026-09-12T07:15:00.000Z"),
      query: {
        kind: "LINE_DIRECTION" as const,
        siteId: 9192,
        transportMode: "BUS" as const,
        lineId: 4,
        directionCode: 2,
      },
    };

    const attempts = await Promise.allSettled([
      firstService.replaceSession(authentication, firstReplacement),
      secondService.replaceSession(authentication, secondReplacement),
    ]);

    expect(attempts.filter(({ status }) => status === "fulfilled")).toHaveLength(1);
    expect(attempts.find(({ status }) => status === "rejected")).toMatchObject({
      status: "rejected",
      reason: expect.objectContaining({ code: "SESSION_REVISION_CONFLICT" }),
    });
    const successfulAttempt = attempts.find(({ status }) => status === "fulfilled");
    if (successfulAttempt?.status !== "fulfilled") {
      throw new Error("expected one successful replacement");
    }
    const stored = await firstService.listSessions(authentication);
    expect(stored).toHaveLength(1);
    expect(stored[0]?.revision).toBe(2);
    expect(stored[0]?.session).toEqual(successfulAttempt.value.session.session);
  });

  it("leaves no authorized session after concurrent registration and revocation", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };

    const results = await Promise.allSettled([
      firstService.registerSession(
        authentication,
        sessionInput(
          "racing-session",
          "2026-09-12T06:00:00.000Z",
          "2026-09-12T07:00:00.000Z",
        ),
      ),
      secondService.revokeInstallation(authentication),
    ]);

    expect(results.some(({ status }) => status === "fulfilled")).toBe(true);
    expect(await firstStore.listEligibleSessions(now)).toEqual([]);
    await expect(firstService.authenticateInstallation(authentication)).rejects.toMatchObject({
      code: "INSTALLATION_AUTHENTICATION_FAILED",
    });
    const rows = await firstSql!<
      { revoked_at: Date | null; lifecycle: string | null }[]
    >`
      SELECT i.revoked_at, s.lifecycle
      FROM live_commute_installations AS i
      LEFT JOIN live_commute_sessions AS s
        ON s.installation_id = i.installation_id
      WHERE i.installation_id = ${issued.installationId}
    `;
    expect(rows.every(({ revoked_at }) => revoked_at != null)).toBe(true);
    expect(rows.every(({ lifecycle }) => lifecycle == null || lifecycle === "CANCELLED")).toBe(
      true,
    );
  });

  it("leaves no authorized session after concurrent replacement and revocation", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };
    const initial = sessionInput(
      "replace-race",
      "2026-09-12T06:00:00.000Z",
      "2026-09-12T07:00:00.000Z",
    );
    await firstService.registerSession(authentication, initial);

    await Promise.allSettled([
      firstService.replaceSession(authentication, {
        ...initial,
        expectedRevision: 1,
        query: {
          kind: "LINE_DIRECTION",
          siteId: 9192,
          transportMode: "BUS",
          lineId: 3,
          directionCode: 2,
        },
      }),
      secondService.revokeInstallation(authentication),
    ]);

    expect(await firstStore.listEligibleSessions(now)).toEqual([]);
    const rows = await firstSql!<
      { revoked_at: Date | null; lifecycle: string; revision: number }[]
    >`
      SELECT i.revoked_at, s.lifecycle, s.revision
      FROM live_commute_installations AS i
      INNER JOIN live_commute_sessions AS s
        ON s.installation_id = i.installation_id
      WHERE i.installation_id = ${issued.installationId}
        AND s.session_id = 'replace-race'
    `;
    expect(rows[0]?.revoked_at).not.toBeNull();
    expect(rows[0]?.lifecycle).toBe("CANCELLED");
    expect(rows[0]?.revision).toBeGreaterThanOrEqual(1);
  });

  it("installs the expected objects and enforces ownership state constraints", async () => {
    const tables = await firstSql!<{ table_name: string }[]>`
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = current_schema()
        AND table_name LIKE 'live_commute_%'
      ORDER BY table_name
    `;
    expect(tables.map(({ table_name }) => table_name)).toEqual([
      "live_commute_installations",
      "live_commute_sessions",
    ]);

    const indexes = await firstSql!<{ indexname: string; indexdef: string }[]>`
      SELECT indexname, indexdef
      FROM pg_indexes
      WHERE schemaname = current_schema()
        AND tablename IN ('live_commute_installations', 'live_commute_sessions')
    `;
    const indexDefinitions = new Map(
      indexes.map(({ indexname, indexdef }) => [indexname, indexdef]),
    );
    expect([...indexDefinitions.keys()]).toEqual(
      expect.arrayContaining([
        "live_commute_installations_pkey",
        "live_commute_installations_credential_digest_key",
        "live_commute_sessions_pkey",
        "live_commute_sessions_installation_window_idx",
        "live_commute_sessions_eligible_window_idx",
      ]),
    );
    expect(indexDefinitions.get("live_commute_sessions_installation_window_idx")).toMatch(
      /WHERE .*lifecycle.*REGISTERED/i,
    );
    expect(indexDefinitions.get("live_commute_sessions_eligible_window_idx")).toMatch(
      /WHERE .*lifecycle.*REGISTERED/i,
    );

    const constraints = await firstSql!<{ conname: string; contype: string }[]>`
      SELECT constraint_row.conname, constraint_row.contype
      FROM pg_constraint AS constraint_row
      INNER JOIN pg_class AS relation
        ON relation.oid = constraint_row.conrelid
      WHERE relation.relnamespace = current_schema()::regnamespace
        AND relation.relname IN ('live_commute_installations', 'live_commute_sessions')
    `;
    expect(constraints).toEqual(
      expect.arrayContaining([
        { conname: "live_commute_installations_pkey", contype: "p" },
        {
          conname: "live_commute_installations_credential_digest_key",
          contype: "u",
        },
        { conname: "live_commute_sessions_pkey", contype: "p" },
        { conname: "live_commute_sessions_installation_fk", contype: "f" },
      ]),
    );

    const createdAt = new Date("2026-09-12T06:00:00.000Z");
    const firstInstallationId = randomUUID();
    const secondInstallationId = randomUUID();
    const missingInstallationId = randomUUID();
    const insertInstallation = async (
      installationId: string,
      credentialDigest: string,
    ): Promise<void> => {
      await firstSql!`
        INSERT INTO live_commute_installations (
          installation_id, credential_digest, revoked_at, created_at, updated_at
        ) VALUES (
          ${installationId}, ${credentialDigest}, NULL, ${createdAt}, ${createdAt}
        )
      `;
    };
    const insertSession = async (input: {
      installationId: string;
      sessionId: string;
      lifecycle?: string;
      revision?: number;
      cancelledAt?: Date | null;
    }): Promise<void> => {
      await firstSql!`
        INSERT INTO live_commute_sessions (
          installation_id, session_id, routine_id, starts_at, ends_at, query,
          lifecycle, revision, created_at, updated_at, cancelled_at
        ) VALUES (
          ${input.installationId}, ${input.sessionId}, 'routine-constraint-test',
          ${createdAt}, ${new Date("2026-09-12T07:00:00.000Z")},
          ${firstSql!.json({ kind: "LINE_DIRECTION" })},
          ${input.lifecycle ?? "REGISTERED"}, ${input.revision ?? 1},
          ${createdAt}, ${createdAt}, ${input.cancelledAt ?? null}
        )
      `;
    };

    await Promise.all([
      insertInstallation(firstInstallationId, "1".repeat(64)),
      insertInstallation(secondInstallationId, "2".repeat(64)),
    ]);
    await expect(
      insertSession({
        installationId: missingInstallationId,
        sessionId: "missing-owner",
      }),
    ).rejects.toMatchObject({ code: "23503" });
    await expect(
      insertSession({
        installationId: firstInstallationId,
        sessionId: "invalid-revision",
        revision: 0,
      }),
    ).rejects.toMatchObject({ code: "23514" });
    await expect(
      insertSession({
        installationId: firstInstallationId,
        sessionId: "invalid-lifecycle",
        lifecycle: "PAUSED",
      }),
    ).rejects.toMatchObject({ code: "23514" });
    await expect(
      insertSession({
        installationId: firstInstallationId,
        sessionId: "missing-cancelled-at",
        lifecycle: "CANCELLED",
      }),
    ).rejects.toMatchObject({ code: "23514" });

    await insertSession({
      installationId: firstInstallationId,
      sessionId: "shared-occurrence",
    });
    await insertSession({
      installationId: secondInstallationId,
      sessionId: "shared-occurrence",
    });
    await expect(
      insertSession({
        installationId: firstInstallationId,
        sessionId: "shared-occurrence",
      }),
    ).rejects.toMatchObject({ code: "23505" });
  });

  it("enforces time constraints and rejects malformed stored queries on read", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const service = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const issued = await service.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };
    const registered = await service.registerSession(
      authentication,
      sessionInput(
        "corruptible",
        "2026-09-12T06:00:00.000Z",
        "2026-09-12T07:00:00.000Z",
      ),
    );

    await expect(firstSql!`
      UPDATE live_commute_sessions
      SET ends_at = starts_at
      WHERE installation_id = ${issued.installationId}
    `).rejects.toMatchObject({ code: "23514" });

    await firstSql!`
      UPDATE live_commute_sessions
      SET query = ${firstSql!.json({ kind: "LINE_DIRECTION" })}
      WHERE installation_id = ${issued.installationId}
    `;
    await expect(firstStore.listEligibleSessions(now)).rejects.toThrow(
      "persisted LINE_DIRECTION query shape is invalid",
    );

    const revoked = await service.revokeInstallation(authentication);
    expect(revoked.status).toBe("REVOKED");
    expect(await firstStore.listEligibleSessions(now)).toEqual([]);
    expect(
      await firstStore.revalidateSessionVersions([
        liveCommuteSessionVersionRef(registered.session),
      ]),
    ).toEqual([]);
    const lifecycleRows = await firstSql!<
      { lifecycle: string; revoked_at: Date | null }[]
    >`
      SELECT s.lifecycle, i.revoked_at
      FROM live_commute_sessions AS s
      INNER JOIN live_commute_installations AS i
        ON i.installation_id = s.installation_id
      WHERE s.installation_id = ${issued.installationId}
    `;
    expect(lifecycleRows).toMatchObject([
      { lifecycle: "CANCELLED", revoked_at: expect.any(Date) },
    ]);
  });

  it("rolls back a failed transaction and releases its connection", async () => {
    const now = new Date("2026-09-12T06:30:00.000Z");
    const firstService = createLiveCommuteInstallationService(firstStore, {
      now: () => now,
    });
    const secondService = createLiveCommuteInstallationService(secondStore, {
      now: () => now,
    });
    const issued = await firstService.registerInstallation();
    const authentication = {
      installationId: issued.installationId,
      bearerCredential: issued.bearerCredential,
    };

    await expect(
      firstStore.withInstallationTransaction(
        issued.installationId,
        async (transaction) => {
          await transaction.revokeInstallation(now);
          throw new Error("forced transaction rollback");
        },
      ),
    ).rejects.toThrow("forced transaction rollback");

    await expect(
      secondService.authenticateInstallation(authentication),
    ).resolves.toMatchObject({
      installationId: issued.installationId,
      state: "ACTIVE",
    });
    await expect(
      firstService.registerSession(
        authentication,
        sessionInput(
          "after-rollback",
          "2026-09-12T06:00:00.000Z",
          "2026-09-12T07:00:00.000Z",
        ),
      ),
    ).resolves.toMatchObject({ status: "REGISTERED" });
  });

  it("returns a sanitized authentication error for a malformed installation id", async () => {
    const service = createLiveCommuteInstallationService(firstStore);
    await expect(
      service.authenticateInstallation({
        installationId: "not-a-database-uuid",
        bearerCredential: Buffer.alloc(32, 7).toString("base64url"),
      }),
    ).rejects.toEqual(
      new LiveCommuteSessionServiceError("INSTALLATION_AUTHENTICATION_FAILED"),
    );
  });
});
