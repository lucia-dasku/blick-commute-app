import { randomUUID } from "node:crypto";
import postgres from "postgres";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { runLiveActivityPublicationCyclesMigration } from "../scripts/migrateLiveActivityPublicationCycles.js";
import { runLiveActivityPublicationPolicyMigration } from "../scripts/migrateLiveActivityPublicationPolicy.js";
import { runLiveActivityDispatchMigration } from "../scripts/migrateLiveActivityDispatch.js";
import { runLiveActivityDeliveryMigration } from "../scripts/migrateLiveActivityDelivery.js";
import { runLiveCommuteMigration } from "../scripts/migrateLiveCommute.js";
import {
  emptyLiveActivityPublicationCycleSafeSummary,
  liveActivityPublicationCycleSlotAt,
  type LiveActivityPublicationCycleSafeSummary,
} from "../src/liveCommute/apple/publicationCycleModel.js";
import { PostgresLiveActivityPublicationCycleStore } from "../src/liveCommute/apple/postgresLiveActivityPublicationCycleStore.js";
import {
  LIVE_COMMUTE_TEST_SCHEMA_PREFIX,
  checkedLocalLiveCommuteTestDatabaseUrl,
  liveCommuteTestSchemaConnectionUrl,
} from "./liveCommutePostgresTestSupport.js";

const RAW_TEST_DATABASE_URL =
  process.env.LIVE_COMMUTE_TEST_DATABASE_URL?.trim() || undefined;
const describeWithPostgres = RAW_TEST_DATABASE_URL == null ? describe.skip : describe;
const CADENCE_MILLISECONDS = 30_000;
const BASE_AT = new Date("2026-09-12T07:30:01.000Z");
const LEASE_MILLISECONDS = 20_000;

function atOffset(milliseconds: number): Date {
  return new Date(BASE_AT.getTime() + milliseconds);
}

function populatedSummary(): LiveActivityPublicationCycleSafeSummary {
  return Object.freeze({
    acquisitionCount: 2,
    publicationOutcomeCount: 3,
    readyPublicationGroupCount: 2,
    bindingCount: 3,
    sendDecisionCount: 1,
    noPushDecisionCount: 1,
    deferralDecisionCount: 1,
    dispatchRequestedCount: 1,
    networkAttemptedCount: 1,
    dispatchRecordedCount: 1,
    dispatchNotReservedCount: 0,
    dispatchNotSentCount: 0,
    dispatchResultNotRecordedCount: 0,
    dispatchCallFailedCount: 0,
  });
}

describeWithPostgres("PostgreSQL Live Activity publication cycle store", () => {
  const schema = `${LIVE_COMMUTE_TEST_SCHEMA_PREFIX}cycle_${randomUUID().replaceAll("-", "").slice(0, 24)}`;
  let adminSql: ReturnType<typeof postgres> | undefined;
  let firstSql: ReturnType<typeof postgres> | undefined;
  let secondSql: ReturnType<typeof postgres> | undefined;
  let firstStore: PostgresLiveActivityPublicationCycleStore;
  let secondStore: PostgresLiveActivityPublicationCycleStore;

  async function setDatabaseNow(at: Date): Promise<void> {
    await firstSql!`
      UPDATE live_activity_publication_cycle_test_clock
      SET database_now = ${at}
      WHERE singleton = TRUE
    `;
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
      throw new Error("refusing to run outside the randomized cycle test schema");
    }
    if (firstScope[0]?.backend_pid === secondScope[0]?.backend_pid) {
      throw new Error("cycle integration stores must use independent connections");
    }
  }

  beforeAll(async () => {
    const connectionString = checkedLocalLiveCommuteTestDatabaseUrl(
      RAW_TEST_DATABASE_URL!,
    );
    adminSql = postgres(connectionString, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_cycle_test_admin" },
    });
    await adminSql`CREATE SCHEMA ${adminSql(schema)}`;
    const scoped = liveCommuteTestSchemaConnectionUrl(connectionString, schema);
    firstSql = postgres(scoped, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_cycle_test_first" },
    });
    secondSql = postgres(scoped, {
      max: 1,
      prepare: false,
      connection: { application_name: "blick_cycle_test_second" },
    });
    await assertIndependentScopedConnections();
    for (let application = 0; application < 2; application += 1) {
      await runLiveCommuteMigration(scoped);
      await runLiveActivityDeliveryMigration(scoped);
      await runLiveActivityDispatchMigration(scoped);
      await runLiveActivityPublicationPolicyMigration(scoped);
      await runLiveActivityPublicationCyclesMigration(scoped);
    }
    await firstSql.unsafe(`
      CREATE TABLE "${schema}".live_activity_publication_cycle_test_clock (
        singleton BOOLEAN PRIMARY KEY CHECK (singleton = TRUE),
        database_now TIMESTAMPTZ NOT NULL
      );
      INSERT INTO "${schema}".live_activity_publication_cycle_test_clock (
        singleton,
        database_now
      ) VALUES (TRUE, '2026-09-12T07:30:01.000Z');
      CREATE FUNCTION "${schema}".clock_timestamp() RETURNS TIMESTAMPTZ
      LANGUAGE SQL
      VOLATILE
      AS $$
        SELECT database_now
        FROM "${schema}".live_activity_publication_cycle_test_clock
        WHERE singleton = TRUE
      $$;
    `);
    await Promise.all([
      firstSql.unsafe(`SET search_path TO "${schema}", pg_catalog`),
      secondSql.unsafe(`SET search_path TO "${schema}", pg_catalog`),
    ]);
    firstStore = new PostgresLiveActivityPublicationCycleStore(firstSql);
    secondStore = new PostgresLiveActivityPublicationCycleStore(secondSql);
  });

  beforeEach(async () => {
    await assertIndependentScopedConnections();
    await setDatabaseNow(BASE_AT);
    await firstSql!`TRUNCATE live_activity_publication_cycles`;
    await firstSql!`
      UPDATE live_activity_publication_cycle_control
      SET last_fence_generation = 0,
          current_slot_start_epoch_seconds = NULL,
          current_slot_cadence_seconds = NULL,
          updated_at = created_at
      WHERE scope = 'GLOBAL'
    `;
  });

  afterAll(async () => {
    await firstSql?.end({ timeout: 5 });
    await secondSql?.end({ timeout: 5 });
    if (adminSql != null) {
      if (!schema.startsWith(LIVE_COMMUTE_TEST_SCHEMA_PREFIX)) {
        throw new Error("refusing to remove an unexpected cycle test schema");
      }
      await adminSql`DROP SCHEMA IF EXISTS ${adminSql(schema)} CASCADE`;
      await adminSql.end({ timeout: 5 });
    }
  });

  it("serializes sixteen same-slot claims across distinct PostgreSQL processes", async () => {
    const slot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const claims = await Promise.all(
      Array.from({ length: 16 }, (_, index) =>
        (index % 2 === 0 ? firstStore : secondStore).claimPublicationCycle({
          slot,
          claimedAt: BASE_AT,
          leaseDurationMilliseconds: LEASE_MILLISECONDS,
        }),
      ),
    );

    expect(claims.filter(({ status }) => status === "CLAIMED")).toHaveLength(1);
    expect(
      claims.filter(({ status }) => status === "ALREADY_RUNNING"),
    ).toHaveLength(15);
    const rows = await firstSql!<
      { state: string; fence_generation: string | number }[]
    >`
      SELECT state, fence_generation
      FROM live_activity_publication_cycles
    `;
    expect(rows).toHaveLength(1);
    expect(rows[0]?.state).toBe("CLAIMED");
    expect(Number(rows[0]?.fence_generation)).toBe(1);
  });

  it("uses database time despite caller skew, then finalizes and deduplicates the slot", async () => {
    const slot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const claimed = await firstStore.claimPublicationCycle({
      slot,
      claimedAt: BASE_AT,
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    if (claimed.status !== "CLAIMED") throw new Error("expected cycle claim");

    await expect(
      secondStore.claimPublicationCycle({
        slot,
        claimedAt: atOffset(28_000),
        leaseDurationMilliseconds: LEASE_MILLISECONDS,
      }),
    ).resolves.toMatchObject({
      status: "ALREADY_RUNNING",
      active: { cycleId: claimed.owner.cycleId, fenceGeneration: 1 },
    });

    const renewed = await secondStore.renewPublicationCycle({
      owner: claimed.owner,
      checkedAt: atOffset(28_000),
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    expect(renewed).toMatchObject({
      cycleId: claimed.owner.cycleId,
      fenceGeneration: 1,
      startedAt: BASE_AT,
      leaseExpiresAt: atOffset(20_000),
    });
    if (renewed == null) throw new Error("expected cycle renewal");

    const finalized = await firstStore.finalizePublicationCycle({
      owner: renewed,
      finalizedAt: new Date(BASE_AT.getTime() - 500),
      state: "COMPLETED",
      summary: populatedSummary(),
      failureCode: null,
    });
    expect(finalized).toMatchObject({
      state: "COMPLETED",
      fenceGeneration: 1,
      summary: populatedSummary(),
      failureCode: null,
    });

    await expect(
      secondStore.claimPublicationCycle({
        slot,
        claimedAt: atOffset(12_000),
        leaseDurationMilliseconds: LEASE_MILLISECONDS,
      }),
    ).resolves.toMatchObject({
      status: "ALREADY_FINALIZED",
      cycle: { cycleId: claimed.owner.cycleId, state: "COMPLETED" },
    });
  });

  it("blocks an adjacent slot while the previous global cycle has a valid lease", async () => {
    const firstSlot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const firstClaim = await firstStore.claimPublicationCycle({
      slot: firstSlot,
      claimedAt: atOffset(20_000),
      leaseDurationMilliseconds: 90_000,
    });
    if (firstClaim.status !== "CLAIMED") throw new Error("expected first claim");
    const nextAt = atOffset(30_000);
    const nextSlot = liveActivityPublicationCycleSlotAt(
      nextAt,
      CADENCE_MILLISECONDS,
    );
    await setDatabaseNow(nextAt);

    await expect(
      secondStore.claimPublicationCycle({
        slot: nextSlot,
        claimedAt: nextAt,
        leaseDurationMilliseconds: 90_000,
      }),
    ).resolves.toMatchObject({
      status: "ALREADY_RUNNING",
      active: { cycleId: firstClaim.owner.cycleId, slot: firstSlot },
    });
    const [control] = await firstSql!<
      { current_slot_start_epoch_seconds: string | number }[]
    >`
      SELECT current_slot_start_epoch_seconds
      FROM live_activity_publication_cycle_control
      WHERE scope = 'GLOBAL'
    `;
    expect(Number(control?.current_slot_start_epoch_seconds)).toBe(
      firstSlot.startEpochSeconds,
    );
  });

  it("reclaims an expired lease with a higher fence and preserves abandoned history", async () => {
    const slot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const firstClaim = await firstStore.claimPublicationCycle({
      slot,
      claimedAt: BASE_AT,
      leaseDurationMilliseconds: 1_000,
    });
    if (firstClaim.status !== "CLAIMED") throw new Error("expected first claim");
    const reclaimedAt = atOffset(1_000);
    await setDatabaseNow(reclaimedAt);
    const secondClaim = await secondStore.claimPublicationCycle({
      slot,
      claimedAt: reclaimedAt,
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    if (secondClaim.status !== "CLAIMED") throw new Error("expected reclaim");

    expect(secondClaim.owner.cycleId).not.toBe(firstClaim.owner.cycleId);
    expect(secondClaim.owner.fenceGeneration).toBeGreaterThan(
      firstClaim.owner.fenceGeneration,
    );
    await expect(
      firstStore.renewPublicationCycle({
        owner: firstClaim.owner,
        checkedAt: atOffset(2_000),
        leaseDurationMilliseconds: LEASE_MILLISECONDS,
      }),
    ).resolves.toBeUndefined();
    await expect(
      firstStore.finalizePublicationCycle({
        owner: firstClaim.owner,
        finalizedAt: atOffset(2_000),
        state: "COMPLETED",
        summary: emptyLiveActivityPublicationCycleSafeSummary(),
        failureCode: null,
      }),
    ).resolves.toBeUndefined();

    const rows = await firstSql!<
      {
        cycle_id: string;
        fence_generation: string | number;
        state: string;
        failure_code: string | null;
      }[]
    >`
      SELECT cycle_id, fence_generation, state, failure_code
      FROM live_activity_publication_cycles
      ORDER BY fence_generation
    `;
    expect(
      rows.map((row) => ({
        ...row,
        fence_generation: Number(row.fence_generation),
      })),
    ).toEqual([
      {
        cycle_id: firstClaim.owner.cycleId,
        fence_generation: 1,
        state: "ABANDONED",
        failure_code: "LEASE_EXPIRED",
      },
      {
        cycle_id: secondClaim.owner.cycleId,
        fence_generation: 2,
        state: "CLAIMED",
        failure_code: null,
      },
    ]);
  });

  it("claims only the current slot after a five-minute gap and rejects older slots", async () => {
    const initialSlot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const initial = await firstStore.claimPublicationCycle({
      slot: initialSlot,
      claimedAt: BASE_AT,
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    if (initial.status !== "CLAIMED") throw new Error("expected initial claim");
    await firstStore.finalizePublicationCycle({
      owner: initial.owner,
      finalizedAt: atOffset(5_000),
      state: "COMPLETED",
      summary: emptyLiveActivityPublicationCycleSafeSummary(),
      failureCode: null,
    });

    const currentAt = atOffset(300_000);
    await setDatabaseNow(currentAt);
    const currentSlot = liveActivityPublicationCycleSlotAt(
      currentAt,
      CADENCE_MILLISECONDS,
    );
    const current = await secondStore.claimPublicationCycle({
      slot: currentSlot,
      claimedAt: currentAt,
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    expect(current).toMatchObject({ status: "CLAIMED" });
    await expect(
      firstStore.claimPublicationCycle({
        slot: initialSlot,
        claimedAt: currentAt,
        leaseDurationMilliseconds: LEASE_MILLISECONDS,
      }),
    ).resolves.toEqual({ status: "STALE_SLOT" });

    const countRows = await firstSql!<{ count: string | number }[]>`
      SELECT COUNT(*) AS count
      FROM live_activity_publication_cycles
    `;
    expect(Number(countRows[0]?.count)).toBe(2);
  });

  it("rolls back a failed claim and reuses both connections", async () => {
    const slot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    await firstSql!.unsafe(`
      CREATE FUNCTION fail_publication_cycle_insert() RETURNS trigger
      LANGUAGE plpgsql AS $$
      BEGIN
        RAISE EXCEPTION 'synthetic publication cycle insert failure';
      END
      $$
    `);
    await firstSql!`
      CREATE TRIGGER fail_publication_cycle_insert
      BEFORE INSERT ON live_activity_publication_cycles
      FOR EACH ROW EXECUTE FUNCTION fail_publication_cycle_insert()
    `;
    try {
      await expect(
        secondStore.claimPublicationCycle({
          slot,
          claimedAt: BASE_AT,
          leaseDurationMilliseconds: LEASE_MILLISECONDS,
        }),
      ).rejects.toMatchObject({
        code: "LIVE_ACTIVITY_PUBLICATION_CYCLE_PERSISTENCE_FAILED",
        message: "Live Activity publication cycle persistence operation failed",
      });
    } finally {
      await firstSql!`
        DROP TRIGGER IF EXISTS fail_publication_cycle_insert
        ON live_activity_publication_cycles
      `;
      await firstSql!.unsafe(
        "DROP FUNCTION IF EXISTS fail_publication_cycle_insert()",
      );
    }

    const [control] = await firstSql!<
      {
        last_fence_generation: string | number;
        current_slot_start_epoch_seconds: string | number | null;
      }[]
    >`
      SELECT last_fence_generation, current_slot_start_epoch_seconds
      FROM live_activity_publication_cycle_control
      WHERE scope = 'GLOBAL'
    `;
    expect(Number(control?.last_fence_generation)).toBe(0);
    expect(control?.current_slot_start_epoch_seconds).toBeNull();
    await expect(
      firstStore.claimPublicationCycle({
        slot,
        claimedAt: BASE_AT,
        leaseDurationMilliseconds: LEASE_MILLISECONDS,
      }),
    ).resolves.toMatchObject({
      status: "CLAIMED",
      owner: { fenceGeneration: 1 },
    });
    await assertIndependentScopedConnections();
  });

  it("records an honest no-work terminal row with only zero safe counters", async () => {
    const slot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const claim = await firstStore.claimPublicationCycle({
      slot,
      claimedAt: BASE_AT,
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    if (claim.status !== "CLAIMED") throw new Error("expected no-work claim");
    const renewed = await firstStore.renewPublicationCycle({
      owner: claim.owner,
      checkedAt: atOffset(1_000),
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    if (renewed == null) throw new Error("expected no-work renewal");

    await expect(
      secondStore.finalizePublicationCycle({
        owner: renewed,
        finalizedAt: atOffset(2_000),
        state: "NO_WORK",
        summary: emptyLiveActivityPublicationCycleSafeSummary(),
        failureCode: null,
      }),
    ).resolves.toMatchObject({
      state: "NO_WORK",
      summary: emptyLiveActivityPublicationCycleSafeSummary(),
      failureCode: null,
    });
  });

  it("enforces global active uniqueness and safe summary constraints", async () => {
    const slot = liveActivityPublicationCycleSlotAt(
      BASE_AT,
      CADENCE_MILLISECONDS,
    );
    const claim = await firstStore.claimPublicationCycle({
      slot,
      claimedAt: BASE_AT,
      leaseDurationMilliseconds: LEASE_MILLISECONDS,
    });
    if (claim.status !== "CLAIMED") throw new Error("expected constraint seed");

    await expect(firstSql!`
      INSERT INTO live_activity_publication_cycles (
        cycle_id, scope, slot_start_epoch_seconds, slot_cadence_seconds,
        fence_generation, state, claimed_at, lease_expires_at, updated_at
      ) VALUES (
        ${randomUUID()}, 'GLOBAL', ${slot.startEpochSeconds + slot.cadenceSeconds},
        ${slot.cadenceSeconds}, 2, 'CLAIMED', ${atOffset(1_000)},
        ${atOffset(10_000)}, ${atOffset(1_000)}
      )
    `).rejects.toMatchObject({ code: "23505" });

    await expect(firstSql!`
      UPDATE live_activity_publication_cycles
      SET state = 'NO_WORK',
          finalized_at = ${atOffset(2_000)},
          acquisition_count = 1,
          publication_outcome_count = 0,
          ready_publication_group_count = 0,
          binding_count = 0,
          send_decision_count = 0,
          no_push_decision_count = 0,
          deferral_decision_count = 0,
          dispatch_requested_count = 0,
          network_attempted_count = 0,
          dispatch_recorded_count = 0,
          dispatch_not_reserved_count = 0,
          dispatch_not_sent_count = 0,
          dispatch_result_not_recorded_count = 0,
          dispatch_call_failed_count = 0
      WHERE cycle_id = ${claim.owner.cycleId}
    `).rejects.toMatchObject({ code: "23514" });

    const columns = await firstSql!<{ column_name: string }[]>`
      SELECT column_name
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name IN (
          'live_activity_publication_cycle_control',
          'live_activity_publication_cycles'
        )
    `;
    expect(columns.map(({ column_name }) => column_name).join(" ")).not.toMatch(
      /token|jwt|private_key|payload|snapshot|raw_response|destination|route/i,
    );
  });
});
