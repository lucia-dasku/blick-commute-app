import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import postgres from "postgres";
import {
  afterAll,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
} from "vitest";
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

const RAW_TEST_DATABASE_URL =
  process.env.LIVE_COMMUTE_TEST_DATABASE_URL?.trim() || undefined;
const describeWithPostgres = RAW_TEST_DATABASE_URL == null ? describe.skip : describe;
const TEST_SCHEMA_PREFIX = "blick_live_commute_test_";

function checkedLocalTestDatabaseUrl(raw: string): string {
  const url = new URL(raw);
  if (url.protocol !== "postgres:" && url.protocol !== "postgresql:") {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must use PostgreSQL");
  }
  const database = decodeURIComponent(url.pathname.replace(/^\/+/, ""));
  if (!/(^|[-_])test([-_]|$)/i.test(database)) {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must name a dedicated test database");
  }
  if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must point to a local database");
  }
  return url.toString();
}

function schemaConnectionUrl(connectionString: string, schema: string): string {
  const url = new URL(connectionString);
  url.searchParams.set("search_path", schema);
  return url.toString();
}

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
  const schema = `${TEST_SCHEMA_PREFIX}${randomUUID().replaceAll("-", "")}`;

  beforeAll(async () => {
    const connectionString = checkedLocalTestDatabaseUrl(RAW_TEST_DATABASE_URL!);
    adminSql = postgres(connectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_live_commute_test_admin" },
    });
    await adminSql`CREATE SCHEMA ${adminSql(schema)}`;

    const scopedConnectionString = schemaConnectionUrl(connectionString, schema);
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
    const [firstScope, secondScope] = await Promise.all([
      firstSql<{ current_schema: string | null }[]>`
        SELECT current_schema() AS current_schema
      `,
      secondSql<{ current_schema: string | null }[]>`
        SELECT current_schema() AS current_schema
      `,
    ]);
    if (
      firstScope[0]?.current_schema !== schema ||
      secondScope[0]?.current_schema !== schema
    ) {
      throw new Error("refusing to run outside the randomized live commute test schema");
    }
    const migration = await readFile(
      new URL("../migrations/002_live_commute_sessions.sql", import.meta.url),
      "utf8",
    );
    await firstSql.begin(async (transaction) => {
      await transaction.unsafe(migration);
    });
    firstStore = new PostgresLiveCommuteSessionStore(firstSql);
    secondStore = new PostgresLiveCommuteSessionStore(secondSql);
  });

  beforeEach(async () => {
    await firstSql!`
      TRUNCATE live_commute_sessions, live_commute_installations
    `;
  });

  afterAll(async () => {
    await firstSql?.end({ timeout: 5 });
    await secondSql?.end({ timeout: 5 });
    if (adminSql != null) {
      if (!schema.startsWith(TEST_SCHEMA_PREFIX)) {
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

    const attempts = await Promise.allSettled([
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
      secondService.replaceSession(authentication, {
        ...initial,
        expectedRevision: 1,
        query: {
          kind: "LINE_DIRECTION",
          siteId: 9192,
          transportMode: "BUS",
          lineId: 4,
          directionCode: 2,
        },
      }),
    ]);

    expect(attempts.filter(({ status }) => status === "fulfilled")).toHaveLength(1);
    expect(attempts.find(({ status }) => status === "rejected")).toMatchObject({
      status: "rejected",
      reason: expect.objectContaining({ code: "SESSION_REVISION_CONFLICT" }),
    });
    const stored = await firstService.listSessions(authentication);
    expect(stored).toHaveLength(1);
    expect(stored[0]?.revision).toBe(2);
    expect(stored[0]?.session.query.kind).toBe("LINE_DIRECTION");
    if (stored[0]?.session.query.kind !== "LINE_DIRECTION") {
      throw new Error("expected a stored line query");
    }
    expect([3, 4]).toContain(stored[0].session.query.lineId);
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
