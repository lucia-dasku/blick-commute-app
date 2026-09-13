import { randomUUID } from "node:crypto";
import postgres from "postgres";
import {
  LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE,
  cloneLiveActivityPublicationCycleOwner,
  createLiveActivityPublicationCycleSafeSummary,
  createLiveActivityPublicationCycleSlot,
  isLiveActivityPublicationCycleSlotCurrent,
  liveActivityPublicationCycleLeaseDurationMilliseconds,
  normalizedLiveActivityPublicationCycleUuid,
  positiveLiveActivityPublicationCycleFence,
  type LiveActivityPublicationCycleFailureCode,
  type LiveActivityPublicationCycleOwner,
  type LiveActivityPublicationCycleRecord,
  type LiveActivityPublicationCycleSafeSummary,
  type LiveActivityPublicationCycleState,
} from "./publicationCycleModel.js";
import type {
  ClaimLiveActivityPublicationCycleInput,
  ClaimLiveActivityPublicationCycleResult,
  FinalizeLiveActivityPublicationCycleInput,
  LiveActivityPublicationCycleStore,
  RenewLiveActivityPublicationCycleInput,
} from "./publicationCycleStore.js";

export type LiveActivityPublicationCyclePostgresSql = ReturnType<typeof postgres>;

const MAX_SAFE_DATABASE_INTEGER = 9_007_199_254_740_991;
const PERSISTENCE_ERROR_MESSAGE =
  "Live Activity publication cycle persistence operation failed";

export class LiveActivityPublicationCyclePersistenceError extends Error {
  readonly code = "LIVE_ACTIVITY_PUBLICATION_CYCLE_PERSISTENCE_FAILED" as const;

  constructor() {
    super(PERSISTENCE_ERROR_MESSAGE);
    this.name = "LiveActivityPublicationCyclePersistenceError";
  }
}

interface CycleControlRow {
  last_fence_generation: string | number;
  current_slot_start_epoch_seconds: string | number | null;
  current_slot_cadence_seconds: number | null;
}

interface DatabaseClockRow {
  database_now: Date;
}

interface PublicationCycleRow {
  cycle_id: string;
  scope: string;
  slot_start_epoch_seconds: string | number;
  slot_cadence_seconds: number;
  fence_generation: string | number;
  state: string;
  claimed_at: Date;
  started_at: Date | null;
  lease_expires_at: Date;
  finalized_at: Date | null;
  updated_at: Date;
  failure_code: string | null;
  acquisition_count: string | number | null;
  publication_outcome_count: string | number | null;
  ready_publication_group_count: string | number | null;
  binding_count: string | number | null;
  send_decision_count: string | number | null;
  no_push_decision_count: string | number | null;
  deferral_decision_count: string | number | null;
  dispatch_requested_count: string | number | null;
  network_attempted_count: string | number | null;
  dispatch_recorded_count: string | number | null;
  dispatch_not_reserved_count: string | number | null;
  dispatch_not_sent_count: string | number | null;
  dispatch_result_not_recorded_count: string | number | null;
  dispatch_call_failed_count: string | number | null;
}

const CYCLE_STATES: readonly LiveActivityPublicationCycleState[] = Object.freeze([
  "CLAIMED",
  "RUNNING",
  "COMPLETED",
  "NO_WORK",
  "FAILED",
  "ABANDONED",
]);
const FAILURE_CODES: readonly LiveActivityPublicationCycleFailureCode[] = Object.freeze([
  "WORKER_FAILED",
  "PUBLICATION_HISTORY_UNAVAILABLE",
  "LEASE_EXPIRED",
]);

function validInstant(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function instantAfter(value: Date, durationMilliseconds: number, field: string): Date {
  return validInstant(new Date(value.getTime() + durationMilliseconds), field);
}

function safeDatabaseInteger(
  value: string | number,
  field: string,
  minimum = 0,
): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (
    !Number.isSafeInteger(parsed) ||
    parsed < minimum ||
    parsed > MAX_SAFE_DATABASE_INTEGER
  ) {
    throw new Error(`${field} is outside the supported integer range`);
  }
  return parsed;
}

function cycleState(value: string): LiveActivityPublicationCycleState {
  if (!CYCLE_STATES.some((candidate) => candidate === value)) {
    throw new Error("publication cycle state is invalid");
  }
  return value as LiveActivityPublicationCycleState;
}

function failureCode(
  value: string | null,
): LiveActivityPublicationCycleFailureCode | null {
  if (value == null) return null;
  if (!FAILURE_CODES.some((candidate) => candidate === value)) {
    throw new Error("publication cycle failure code is invalid");
  }
  return value as LiveActivityPublicationCycleFailureCode;
}

function summaryFromRow(
  row: PublicationCycleRow,
): LiveActivityPublicationCycleSafeSummary | null {
  const values = [
    row.acquisition_count,
    row.publication_outcome_count,
    row.ready_publication_group_count,
    row.binding_count,
    row.send_decision_count,
    row.no_push_decision_count,
    row.deferral_decision_count,
    row.dispatch_requested_count,
    row.network_attempted_count,
    row.dispatch_recorded_count,
    row.dispatch_not_reserved_count,
    row.dispatch_not_sent_count,
    row.dispatch_result_not_recorded_count,
    row.dispatch_call_failed_count,
  ];
  if (values.every((value) => value == null)) return null;
  if (values.some((value) => value == null)) {
    throw new Error("publication cycle summary is incomplete");
  }
  return createLiveActivityPublicationCycleSafeSummary({
    acquisitionCount: safeDatabaseInteger(
      row.acquisition_count!,
      "acquisition_count",
    ),
    publicationOutcomeCount: safeDatabaseInteger(
      row.publication_outcome_count!,
      "publication_outcome_count",
    ),
    readyPublicationGroupCount: safeDatabaseInteger(
      row.ready_publication_group_count!,
      "ready_publication_group_count",
    ),
    bindingCount: safeDatabaseInteger(row.binding_count!, "binding_count"),
    sendDecisionCount: safeDatabaseInteger(
      row.send_decision_count!,
      "send_decision_count",
    ),
    noPushDecisionCount: safeDatabaseInteger(
      row.no_push_decision_count!,
      "no_push_decision_count",
    ),
    deferralDecisionCount: safeDatabaseInteger(
      row.deferral_decision_count!,
      "deferral_decision_count",
    ),
    dispatchRequestedCount: safeDatabaseInteger(
      row.dispatch_requested_count!,
      "dispatch_requested_count",
    ),
    networkAttemptedCount: safeDatabaseInteger(
      row.network_attempted_count!,
      "network_attempted_count",
    ),
    dispatchRecordedCount: safeDatabaseInteger(
      row.dispatch_recorded_count!,
      "dispatch_recorded_count",
    ),
    dispatchNotReservedCount: safeDatabaseInteger(
      row.dispatch_not_reserved_count!,
      "dispatch_not_reserved_count",
    ),
    dispatchNotSentCount: safeDatabaseInteger(
      row.dispatch_not_sent_count!,
      "dispatch_not_sent_count",
    ),
    dispatchResultNotRecordedCount: safeDatabaseInteger(
      row.dispatch_result_not_recorded_count!,
      "dispatch_result_not_recorded_count",
    ),
    dispatchCallFailedCount: safeDatabaseInteger(
      row.dispatch_call_failed_count!,
      "dispatch_call_failed_count",
    ),
  });
}

function cycleRecordFromRow(
  row: PublicationCycleRow,
): LiveActivityPublicationCycleRecord {
  if (row.scope !== LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE) {
    throw new Error("publication cycle scope is invalid");
  }
  const state = cycleState(row.state);
  return Object.freeze({
    cycleId: normalizedLiveActivityPublicationCycleUuid(row.cycle_id),
    slot: createLiveActivityPublicationCycleSlot({
      startEpochSeconds: safeDatabaseInteger(
        row.slot_start_epoch_seconds,
        "slot_start_epoch_seconds",
      ),
      cadenceSeconds: safeDatabaseInteger(
        row.slot_cadence_seconds,
        "slot_cadence_seconds",
        1,
      ),
    }),
    fenceGeneration: positiveLiveActivityPublicationCycleFence(
      safeDatabaseInteger(row.fence_generation, "fence_generation", 1),
    ),
    state,
    claimedAt: validInstant(row.claimed_at, "claimed_at"),
    startedAt:
      row.started_at == null ? null : validInstant(row.started_at, "started_at"),
    leaseExpiresAt: validInstant(row.lease_expires_at, "lease_expires_at"),
    finalizedAt:
      row.finalized_at == null
        ? null
        : validInstant(row.finalized_at, "finalized_at"),
    updatedAt: validInstant(row.updated_at, "updated_at"),
    summary: summaryFromRow(row),
    failureCode: failureCode(row.failure_code),
  });
}

function ownerFromRecord(
  record: LiveActivityPublicationCycleRecord,
): LiveActivityPublicationCycleOwner {
  return cloneLiveActivityPublicationCycleOwner(record);
}

function hasAnyCount(summary: LiveActivityPublicationCycleSafeSummary): boolean {
  return Object.values(summary).some((count) => count !== 0);
}

async function persistenceOperation<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch {
    throw new LiveActivityPublicationCyclePersistenceError();
  }
}

/**
 * PostgreSQL-backed global cycle authority. The caller owns and closes the supplied pool;
 * construction performs no database, scheduler, transit, or network work.
 */
export class PostgresLiveActivityPublicationCycleStore
  implements LiveActivityPublicationCycleStore
{
  constructor(
    private readonly sql: LiveActivityPublicationCyclePostgresSql,
    private readonly createUuid: () => string = randomUUID,
  ) {}

  async claimPublicationCycle(
    input: ClaimLiveActivityPublicationCycleInput,
  ): Promise<ClaimLiveActivityPublicationCycleResult> {
    const slot = createLiveActivityPublicationCycleSlot(input.slot);
    const claimedAt = validInstant(input.claimedAt, "claimedAt");
    const leaseDurationMilliseconds =
      liveActivityPublicationCycleLeaseDurationMilliseconds(
        input.leaseDurationMilliseconds,
      );
    if (!isLiveActivityPublicationCycleSlotCurrent(slot, claimedAt)) {
      return Object.freeze({ status: "STALE_SLOT" });
    }
    return await persistenceOperation(async () =>
      (await this.sql.begin(async (transactionSql) => {
        const controls = await transactionSql<CycleControlRow[]>`
          SELECT last_fence_generation, current_slot_start_epoch_seconds,
                 current_slot_cadence_seconds
          FROM live_activity_publication_cycle_control
          WHERE scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
          FOR UPDATE
        `;
        const control = controls[0];
        if (control == null) throw new Error("publication cycle control is missing");
        const clockRows = await transactionSql<DatabaseClockRow[]>`
          SELECT clock_timestamp() AS database_now
        `;
        const databaseNow = validInstant(
          clockRows[0]?.database_now as Date,
          "database_now",
        );
        if (!isLiveActivityPublicationCycleSlotCurrent(slot, databaseNow)) {
          return Object.freeze({ status: "STALE_SLOT" } as const);
        }
        const leaseExpiresAt = instantAfter(
          databaseNow,
          leaseDurationMilliseconds,
          "leaseExpiresAt",
        );
        const lastFenceGeneration = safeDatabaseInteger(
          control.last_fence_generation,
          "last_fence_generation",
        );
        const currentSlotStart =
          control.current_slot_start_epoch_seconds == null
            ? null
            : safeDatabaseInteger(
                control.current_slot_start_epoch_seconds,
                "current_slot_start_epoch_seconds",
              );
        const currentSlotCadence = control.current_slot_cadence_seconds;
        if ((currentSlotStart == null) !== (currentSlotCadence == null)) {
          throw new Error("publication cycle control slot is incomplete");
        }
        if (currentSlotStart != null && slot.startEpochSeconds < currentSlotStart) {
          return Object.freeze({ status: "STALE_SLOT" } as const);
        }
        if (
          currentSlotStart === slot.startEpochSeconds &&
          currentSlotCadence !== slot.cadenceSeconds
        ) {
          return Object.freeze({ status: "CADENCE_CONFLICT" } as const);
        }

        const activeRows = await transactionSql<PublicationCycleRow[]>`
          SELECT *
          FROM live_activity_publication_cycles
          WHERE scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
            AND state IN ('CLAIMED', 'RUNNING')
          ORDER BY fence_generation DESC
          LIMIT 1
        `;
        const activeRow = activeRows[0];
        if (activeRow != null) {
          const active = cycleRecordFromRow(activeRow);
          if (active.leaseExpiresAt.getTime() > databaseNow.getTime()) {
            return Object.freeze({
              status: "ALREADY_RUNNING" as const,
              active: ownerFromRecord(active),
            });
          }
          const abandonedRows = await transactionSql<PublicationCycleRow[]>`
            UPDATE live_activity_publication_cycles
            SET state = 'ABANDONED',
                finalized_at = ${databaseNow},
                updated_at = GREATEST(updated_at, ${databaseNow}),
                failure_code = 'LEASE_EXPIRED'
            WHERE cycle_id = ${active.cycleId}
              AND scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
              AND fence_generation = ${active.fenceGeneration}
              AND state IN ('CLAIMED', 'RUNNING')
              AND lease_expires_at <= ${databaseNow}
            RETURNING *
          `;
          if (abandonedRows.length !== 1) {
            throw new Error("expired publication cycle changed concurrently");
          }
        }

        const finalizedRows = await transactionSql<PublicationCycleRow[]>`
          SELECT *
          FROM live_activity_publication_cycles
          WHERE scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
            AND slot_start_epoch_seconds = ${slot.startEpochSeconds}
            AND slot_cadence_seconds = ${slot.cadenceSeconds}
            AND state IN ('COMPLETED', 'NO_WORK', 'FAILED')
          ORDER BY fence_generation DESC
          LIMIT 1
        `;
        const finalizedRow = finalizedRows[0];
        if (finalizedRow != null) {
          return Object.freeze({
            status: "ALREADY_FINALIZED" as const,
            cycle: cycleRecordFromRow(finalizedRow),
          });
        }

        if (lastFenceGeneration >= MAX_SAFE_DATABASE_INTEGER) {
          throw new Error("publication cycle fence range is exhausted");
        }
        const nextFenceGeneration = lastFenceGeneration + 1;
        const updatedControls = await transactionSql<CycleControlRow[]>`
          UPDATE live_activity_publication_cycle_control
          SET last_fence_generation = ${nextFenceGeneration},
              current_slot_start_epoch_seconds = ${slot.startEpochSeconds},
              current_slot_cadence_seconds = ${slot.cadenceSeconds},
              updated_at = GREATEST(updated_at, ${databaseNow})
          WHERE scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
            AND last_fence_generation = ${lastFenceGeneration}
          RETURNING last_fence_generation, current_slot_start_epoch_seconds,
                    current_slot_cadence_seconds
        `;
        if (updatedControls.length !== 1) {
          throw new Error("publication cycle control changed concurrently");
        }

        const cycleId = normalizedLiveActivityPublicationCycleUuid(this.createUuid());
        const inserted = await transactionSql<PublicationCycleRow[]>`
          INSERT INTO live_activity_publication_cycles (
            cycle_id, scope, slot_start_epoch_seconds, slot_cadence_seconds,
            fence_generation, state, claimed_at, started_at, lease_expires_at,
            finalized_at, updated_at, failure_code
          ) VALUES (
            ${cycleId}, ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE},
            ${slot.startEpochSeconds}, ${slot.cadenceSeconds},
            ${nextFenceGeneration}, 'CLAIMED', ${databaseNow}, NULL,
            ${leaseExpiresAt}, NULL, ${databaseNow}, NULL
          )
          RETURNING *
        `;
        const record = inserted[0];
        if (record == null) throw new Error("publication cycle was not inserted");
        return Object.freeze({
          status: "CLAIMED" as const,
          owner: ownerFromRecord(cycleRecordFromRow(record)),
        });
      })) as ClaimLiveActivityPublicationCycleResult,
    );
  }

  async renewPublicationCycle(
    input: RenewLiveActivityPublicationCycleInput,
  ): Promise<LiveActivityPublicationCycleOwner | undefined> {
    const owner = cloneLiveActivityPublicationCycleOwner(input.owner);
    validInstant(input.checkedAt, "checkedAt");
    const leaseDurationMilliseconds =
      liveActivityPublicationCycleLeaseDurationMilliseconds(
        input.leaseDurationMilliseconds,
      );

    return await persistenceOperation(async () =>
      (await this.sql.begin(async (transactionSql) => {
        const controls = await transactionSql<CycleControlRow[]>`
          SELECT last_fence_generation, current_slot_start_epoch_seconds,
                 current_slot_cadence_seconds
          FROM live_activity_publication_cycle_control
          WHERE scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
          FOR UPDATE
        `;
        const control = controls[0];
        if (control == null) throw new Error("publication cycle control is missing");
        const clockRows = await transactionSql<DatabaseClockRow[]>`
          SELECT clock_timestamp() AS database_now
        `;
        const databaseNow = validInstant(
          clockRows[0]?.database_now as Date,
          "database_now",
        );
        const requestedLeaseExpiresAt = instantAfter(
          databaseNow,
          leaseDurationMilliseconds,
          "leaseExpiresAt",
        );
        if (
          safeDatabaseInteger(
            control.last_fence_generation,
            "last_fence_generation",
          ) !== owner.fenceGeneration
        ) {
          return undefined;
        }

        const renewed = await transactionSql<PublicationCycleRow[]>`
          UPDATE live_activity_publication_cycles
          SET state = 'RUNNING',
              started_at = COALESCE(started_at, ${databaseNow}),
              lease_expires_at = GREATEST(
                lease_expires_at,
                ${requestedLeaseExpiresAt}
              ),
              updated_at = GREATEST(updated_at, ${databaseNow})
          WHERE cycle_id = ${owner.cycleId}
            AND scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
            AND slot_start_epoch_seconds = ${owner.slot.startEpochSeconds}
            AND slot_cadence_seconds = ${owner.slot.cadenceSeconds}
            AND fence_generation = ${owner.fenceGeneration}
            AND state IN ('CLAIMED', 'RUNNING')
            AND lease_expires_at > ${databaseNow}
          RETURNING *
        `;
        const row = renewed[0];
        return row == null ? undefined : ownerFromRecord(cycleRecordFromRow(row));
      })) as LiveActivityPublicationCycleOwner | undefined,
    );
  }

  async finalizePublicationCycle(
    input: FinalizeLiveActivityPublicationCycleInput,
  ): Promise<LiveActivityPublicationCycleRecord | undefined> {
    const owner = cloneLiveActivityPublicationCycleOwner(input.owner);
    validInstant(input.finalizedAt, "finalizedAt");
    const summary =
      input.summary == null
        ? null
        : createLiveActivityPublicationCycleSafeSummary(input.summary);
    if ((input.state === "FAILED") !== (input.failureCode != null)) {
      throw new RangeError("cycle final state and failure code are inconsistent");
    }
    if (
      input.state === "NO_WORK" &&
      (summary == null || hasAnyCount(summary))
    ) {
      throw new RangeError("a no-work cycle must have zero result counters");
    }
    if (input.state !== "FAILED" && summary == null) {
      throw new RangeError("a successful cycle must have result counters");
    }

    return await persistenceOperation(async () =>
      (await this.sql.begin(async (transactionSql) => {
        const controls = await transactionSql<CycleControlRow[]>`
          SELECT last_fence_generation, current_slot_start_epoch_seconds,
                 current_slot_cadence_seconds
          FROM live_activity_publication_cycle_control
          WHERE scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
          FOR UPDATE
        `;
        const control = controls[0];
        if (control == null) throw new Error("publication cycle control is missing");
        const clockRows = await transactionSql<DatabaseClockRow[]>`
          SELECT clock_timestamp() AS database_now
        `;
        const databaseNow = validInstant(
          clockRows[0]?.database_now as Date,
          "database_now",
        );
        if (
          safeDatabaseInteger(
            control.last_fence_generation,
            "last_fence_generation",
          ) !== owner.fenceGeneration
        ) {
          return undefined;
        }

        const finalized = await transactionSql<PublicationCycleRow[]>`
          UPDATE live_activity_publication_cycles
          SET state = ${input.state},
              finalized_at = ${databaseNow},
              updated_at = GREATEST(updated_at, ${databaseNow}),
              failure_code = ${input.failureCode},
              acquisition_count = ${summary?.acquisitionCount ?? null},
              publication_outcome_count =
                ${summary?.publicationOutcomeCount ?? null},
              ready_publication_group_count =
                ${summary?.readyPublicationGroupCount ?? null},
              binding_count = ${summary?.bindingCount ?? null},
              send_decision_count = ${summary?.sendDecisionCount ?? null},
              no_push_decision_count = ${summary?.noPushDecisionCount ?? null},
              deferral_decision_count =
                ${summary?.deferralDecisionCount ?? null},
              dispatch_requested_count =
                ${summary?.dispatchRequestedCount ?? null},
              network_attempted_count =
                ${summary?.networkAttemptedCount ?? null},
              dispatch_recorded_count =
                ${summary?.dispatchRecordedCount ?? null},
              dispatch_not_reserved_count =
                ${summary?.dispatchNotReservedCount ?? null},
              dispatch_not_sent_count =
                ${summary?.dispatchNotSentCount ?? null},
              dispatch_result_not_recorded_count =
                ${summary?.dispatchResultNotRecordedCount ?? null},
              dispatch_call_failed_count =
                ${summary?.dispatchCallFailedCount ?? null}
          WHERE cycle_id = ${owner.cycleId}
            AND scope = ${LIVE_ACTIVITY_PUBLICATION_CYCLE_SCOPE}
            AND slot_start_epoch_seconds = ${owner.slot.startEpochSeconds}
            AND slot_cadence_seconds = ${owner.slot.cadenceSeconds}
            AND fence_generation = ${owner.fenceGeneration}
            AND state IN ('CLAIMED', 'RUNNING')
            AND lease_expires_at > ${databaseNow}
          RETURNING *
        `;
        const row = finalized[0];
        return row == null ? undefined : cycleRecordFromRow(row);
      })) as LiveActivityPublicationCycleRecord | undefined,
    );
  }
}
