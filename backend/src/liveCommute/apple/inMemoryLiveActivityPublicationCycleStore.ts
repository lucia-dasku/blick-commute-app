import { randomUUID } from "node:crypto";
import {
  cloneLiveActivityPublicationCycleOwner,
  createLiveActivityPublicationCycleSafeSummary,
  createLiveActivityPublicationCycleSlot,
  isLiveActivityPublicationCycleSlotCurrent,
  liveActivityPublicationCycleLeaseDurationMilliseconds,
  normalizedLiveActivityPublicationCycleUuid,
  type LiveActivityPublicationCycleRecord,
  type LiveActivityPublicationCycleSafeSummary,
  type LiveActivityPublicationCycleSlot,
} from "./publicationCycleModel.js";
import type {
  ClaimLiveActivityPublicationCycleInput,
  ClaimLiveActivityPublicationCycleResult,
  FinalizeLiveActivityPublicationCycleInput,
  LiveActivityPublicationCycleStore,
  RenewLiveActivityPublicationCycleInput,
} from "./publicationCycleStore.js";

function validInstant(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function cloneSummary(
  summary: LiveActivityPublicationCycleSafeSummary | null,
): LiveActivityPublicationCycleSafeSummary | null {
  return summary == null
    ? null
    : createLiveActivityPublicationCycleSafeSummary(summary);
}

function cloneRecord(
  record: LiveActivityPublicationCycleRecord,
): LiveActivityPublicationCycleRecord {
  return Object.freeze({
    ...cloneLiveActivityPublicationCycleOwner(record),
    state: record.state,
    finalizedAt:
      record.finalizedAt == null ? null : new Date(record.finalizedAt),
    updatedAt: new Date(record.updatedAt),
    summary: cloneSummary(record.summary),
    failureCode: record.failureCode,
  });
}

function sameSlot(
  left: LiveActivityPublicationCycleSlot,
  right: LiveActivityPublicationCycleSlot,
): boolean {
  return (
    left.startEpochSeconds === right.startEpochSeconds &&
    left.cadenceSeconds === right.cadenceSeconds
  );
}

function sameOwner(
  record: LiveActivityPublicationCycleRecord,
  owner: ReturnType<typeof cloneLiveActivityPublicationCycleOwner>,
): boolean {
  return (
    record.cycleId === owner.cycleId &&
    record.fenceGeneration === owner.fenceGeneration &&
    sameSlot(record.slot, owner.slot)
  );
}

function ownerFromRecord(
  record: LiveActivityPublicationCycleRecord,
): ReturnType<typeof cloneLiveActivityPublicationCycleOwner> {
  return cloneLiveActivityPublicationCycleOwner(record);
}

/** Process-local deterministic adapter for unit tests; PostgreSQL is authoritative. */
export class InMemoryLiveActivityPublicationCycleStore
  implements LiveActivityPublicationCycleStore
{
  readonly #records = new Map<string, LiveActivityPublicationCycleRecord>();
  #lastFenceGeneration = 0;
  #currentSlot: LiveActivityPublicationCycleSlot | null = null;

  constructor(private readonly createUuid: () => string = randomUUID) {}

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
    if (this.#currentSlot != null) {
      if (slot.startEpochSeconds < this.#currentSlot.startEpochSeconds) {
        return Object.freeze({ status: "STALE_SLOT" });
      }
      if (
        slot.startEpochSeconds === this.#currentSlot.startEpochSeconds &&
        slot.cadenceSeconds !== this.#currentSlot.cadenceSeconds
      ) {
        return Object.freeze({ status: "CADENCE_CONFLICT" });
      }
    }

    const active = [...this.#records.values()].find(
      ({ state }) => state === "CLAIMED" || state === "RUNNING",
    );
    if (active != null) {
      if (active.leaseExpiresAt.getTime() > claimedAt.getTime()) {
        return Object.freeze({
          status: "ALREADY_RUNNING",
          active: ownerFromRecord(active),
        });
      }
      this.#records.set(
        active.cycleId,
        Object.freeze({
          ...cloneRecord(active),
          state: "ABANDONED",
          finalizedAt: new Date(claimedAt),
          updatedAt: new Date(claimedAt),
          summary: null,
          failureCode: "LEASE_EXPIRED",
        }),
      );
    }

    const finalized = [...this.#records.values()]
      .filter(
        (record) =>
          sameSlot(record.slot, slot) &&
          (record.state === "COMPLETED" ||
            record.state === "NO_WORK" ||
            record.state === "FAILED"),
      )
      .sort((left, right) => right.fenceGeneration - left.fenceGeneration)[0];
    if (finalized != null) {
      return Object.freeze({
        status: "ALREADY_FINALIZED",
        cycle: cloneRecord(finalized),
      });
    }

    const cycleId = normalizedLiveActivityPublicationCycleUuid(this.createUuid());
    this.#lastFenceGeneration += 1;
    const leaseExpiresAt = new Date(
      claimedAt.getTime() + leaseDurationMilliseconds,
    );
    const record: LiveActivityPublicationCycleRecord = Object.freeze({
      cycleId,
      slot,
      fenceGeneration: this.#lastFenceGeneration,
      state: "CLAIMED",
      claimedAt,
      startedAt: null,
      leaseExpiresAt,
      finalizedAt: null,
      updatedAt: claimedAt,
      summary: null,
      failureCode: null,
    });
    this.#records.set(cycleId, record);
    this.#currentSlot = slot;
    return Object.freeze({
      status: "CLAIMED",
      owner: ownerFromRecord(record),
    });
  }

  async renewPublicationCycle(
    input: RenewLiveActivityPublicationCycleInput,
  ): Promise<ReturnType<typeof cloneLiveActivityPublicationCycleOwner> | undefined> {
    const owner = cloneLiveActivityPublicationCycleOwner(input.owner);
    const checkedAt = validInstant(input.checkedAt, "checkedAt");
    const leaseDurationMilliseconds =
      liveActivityPublicationCycleLeaseDurationMilliseconds(
        input.leaseDurationMilliseconds,
      );
    const current = this.#records.get(owner.cycleId);
    if (
      current == null ||
      !sameOwner(current, owner) ||
      (current.state !== "CLAIMED" && current.state !== "RUNNING") ||
      current.leaseExpiresAt.getTime() <= checkedAt.getTime()
    ) {
      return undefined;
    }
    const renewed: LiveActivityPublicationCycleRecord = Object.freeze({
      ...cloneRecord(current),
      state: "RUNNING",
      startedAt: current.startedAt ?? new Date(checkedAt),
      leaseExpiresAt: new Date(
        Math.max(
          current.leaseExpiresAt.getTime(),
          checkedAt.getTime() + leaseDurationMilliseconds,
        ),
      ),
      updatedAt: new Date(
        Math.max(current.updatedAt.getTime(), checkedAt.getTime()),
      ),
    });
    this.#records.set(renewed.cycleId, renewed);
    return ownerFromRecord(renewed);
  }

  async finalizePublicationCycle(
    input: FinalizeLiveActivityPublicationCycleInput,
  ): Promise<LiveActivityPublicationCycleRecord | undefined> {
    const owner = cloneLiveActivityPublicationCycleOwner(input.owner);
    const finalizedAt = validInstant(input.finalizedAt, "finalizedAt");
    const summary =
      input.summary == null
        ? null
        : createLiveActivityPublicationCycleSafeSummary(input.summary);
    if (
      (input.state === "FAILED") !== (input.failureCode != null)
    ) {
      throw new RangeError("cycle final state and failure code are inconsistent");
    }
    if (
      input.state === "NO_WORK" &&
      (summary == null || Object.values(summary).some((count) => count !== 0))
    ) {
      throw new RangeError("a no-work cycle must have zero result counters");
    }
    if (input.state !== "FAILED" && summary == null) {
      throw new RangeError("a successful cycle must have result counters");
    }
    const current = this.#records.get(owner.cycleId);
    if (
      current == null ||
      !sameOwner(current, owner) ||
      (current.state !== "CLAIMED" && current.state !== "RUNNING") ||
      current.leaseExpiresAt.getTime() <= finalizedAt.getTime()
    ) {
      return undefined;
    }
    const terminal: LiveActivityPublicationCycleRecord = Object.freeze({
      ...cloneRecord(current),
      state: input.state,
      finalizedAt,
      updatedAt: new Date(
        Math.max(current.updatedAt.getTime(), finalizedAt.getTime()),
      ),
      summary,
      failureCode: input.failureCode,
    });
    this.#records.set(terminal.cycleId, terminal);
    return cloneRecord(terminal);
  }

  listPublicationCyclesForTests(): readonly LiveActivityPublicationCycleRecord[] {
    return Object.freeze(
      [...this.#records.values()]
        .sort((left, right) => left.fenceGeneration - right.fenceGeneration)
        .map(cloneRecord),
    );
  }
}
