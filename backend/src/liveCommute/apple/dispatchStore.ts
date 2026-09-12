import type {
  LiveActivityDispatchCursor,
  LiveActivityDirectDispatchAttempt,
  LiveActivityDirectDispatchOperation,
  LiveActivityDispatchRetryAdvice,
} from "./dispatchModel.js";
import {
  createLiveActivityDirectDispatchAttempt,
  createLiveActivityDispatchCursor,
  normalizedLiveActivityDispatchInstant,
  normalizedLiveActivityDispatchOperation,
  normalizedLiveActivityDispatchUuid,
  normalizedLiveActivityEventTimestamp,
  normalizedPayloadFingerprint,
  normalizedSafeApnsReason,
  sameLiveActivityDispatchTokenGeneration,
} from "./dispatchModel.js";
import {
  createStoredLiveActivityUpdateToken,
  createStoredPushToStartToken,
  normalizedAppleDeliveryIdentifier,
  normalizedLiveActivityBindingId,
  positiveActivityKitGeneration,
  type LiveActivityDeliveryBinding,
  type StoredLiveActivityUpdateToken,
  type StoredPushToStartToken,
} from "./deliveryModel.js";
import type {
  StoredLiveCommuteInstallation,
  StoredLiveCommuteSession,
} from "../sessionStore.js";

export interface ReserveLiveActivityDirectDispatchInput {
  readonly installationId: string;
  readonly bindingId: string;
  readonly sessionRevision: number;
  readonly operation: LiveActivityDirectDispatchOperation;
  readonly eventTimestamp: number;
  readonly dispatchId: string;
  readonly apnsRequestId: string;
  /** Trusted application instant used for the authoritative half-open-window check. */
  readonly reservedAt: Date;
}

export type ReserveLiveActivityDirectDispatchResult =
  | {
      readonly status: "RESERVED";
      readonly attempt: LiveActivityDirectDispatchAttempt;
      readonly supersededDispatchId: string | null;
    }
  | {
      readonly status:
        | "NOT_AUTHORIZED"
        | "NOT_DIRECT"
        | "TOKEN_UNAVAILABLE"
        | "START_BLOCKED"
        | "BUSY"
        | "TERMINAL_INTENT";
      readonly blockingDispatchId: string | null;
    }
  | {
      readonly status: "STALE_EVENT" | "SAME_SECOND";
      readonly lastReservedEventTimestamp: number;
    };

export interface ClaimLiveActivityDirectDispatchInput {
  readonly installationId: string;
  readonly dispatchId: string;
  readonly payloadFingerprint: string;
  /** Trusted application instant used for the final pre-send authority check. */
  readonly claimedAt: Date;
}

export type ClaimLiveActivityDirectDispatchResult =
  | {
      readonly status: "CLAIMED";
      readonly attempt: LiveActivityDirectDispatchAttempt;
    }
  | {
      readonly status: "ABORTED" | "SUPERSEDED" | "NOT_FOUND";
      readonly attempt: LiveActivityDirectDispatchAttempt | null;
    };

export interface AbortLiveActivityDirectDispatchInput {
  readonly installationId: string;
  readonly dispatchId: string;
  readonly completedAt: Date;
  readonly retryAdvice: LiveActivityDispatchRetryAdvice;
  /** Optional explicit lower bound supplied by a trusted cache/policy decision. */
  readonly retryNotBefore?: Date | null;
}

export type CompleteLiveActivityDirectDispatchState =
  | "ACCEPTED"
  | "REJECTED"
  | "RETRYABLE"
  | "OUTCOME_UNKNOWN"
  | "ABORTED";

export interface CompleteLiveActivityDirectDispatchInput {
  readonly installationId: string;
  readonly dispatchId: string;
  readonly completedAt: Date;
  readonly state: CompleteLiveActivityDirectDispatchState;
  readonly apnsStatus: number | null;
  readonly apnsReason: string | null;
  readonly retryAdvice: LiveActivityDispatchRetryAdvice;
  readonly retryNotBefore: Date | null;
  /** True only for a definitively terminal destination-token APNs response. */
  readonly invalidateExactTokenGeneration: boolean;
}

export type CompleteLiveActivityDirectDispatchResult =
  | {
      readonly status: "COMPLETED";
      readonly attempt: LiveActivityDirectDispatchAttempt;
    }
  | {
      readonly status: "NOT_IN_FLIGHT" | "NOT_FOUND";
      readonly attempt: LiveActivityDirectDispatchAttempt | null;
    };

/**
 * Every mutation is a short transaction coordinated by the Phase 3A installation-parent
 * lock. No method in this interface may perform APNs or transit network I/O.
 */
export interface LiveActivityDispatchStore {
  reserveDirectDispatch(
    input: ReserveLiveActivityDirectDispatchInput,
  ): Promise<ReserveLiveActivityDirectDispatchResult>;
  claimDirectDispatch(
    input: ClaimLiveActivityDirectDispatchInput,
  ): Promise<ClaimLiveActivityDirectDispatchResult>;
  abortDirectDispatch(
    input: AbortLiveActivityDirectDispatchInput,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined>;
  completeDirectDispatch(
    input: CompleteLiveActivityDirectDispatchInput,
  ): Promise<CompleteLiveActivityDirectDispatchResult>;
  getDirectDispatchAttempt(
    installationId: string,
    dispatchId: string,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined>;
}

export interface LiveActivityDispatchInstallationTransaction {
  getInstallation(): Promise<StoredLiveCommuteInstallation | undefined>;
  getSession(sessionId: string): Promise<StoredLiveCommuteSession | undefined>;
  getDeliveryBinding(
    bindingId: string,
  ): Promise<LiveActivityDeliveryBinding | undefined>;
  getLatestPushToStartToken(): Promise<StoredPushToStartToken | undefined>;
  getPushToStartToken(
    clientGeneration: number,
  ): Promise<StoredPushToStartToken | undefined>;
  savePushToStartToken(token: StoredPushToStartToken): Promise<void>;
  getLatestUpdateToken(
    bindingId: string,
  ): Promise<StoredLiveActivityUpdateToken | undefined>;
  getUpdateToken(
    bindingId: string,
    clientGeneration: number,
  ): Promise<StoredLiveActivityUpdateToken | undefined>;
  saveUpdateToken(token: StoredLiveActivityUpdateToken): Promise<void>;
  getDispatchCursor(
    bindingId: string,
  ): Promise<LiveActivityDispatchCursor | undefined>;
  insertDispatchCursor(cursor: LiveActivityDispatchCursor): Promise<boolean>;
  updateDispatchCursor(cursor: LiveActivityDispatchCursor): Promise<boolean>;
  getDispatchAttempt(
    dispatchId: string,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined>;
  getActiveDispatchAttempt(
    bindingId: string,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined>;
  getLatestDispatchAttemptForOperation(
    bindingId: string,
    operation: LiveActivityDirectDispatchOperation,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined>;
  insertDispatchAttempt(attempt: LiveActivityDirectDispatchAttempt): Promise<boolean>;
  updateDispatchAttempt(
    expectedState: LiveActivityDirectDispatchAttempt["state"],
    attempt: LiveActivityDirectDispatchAttempt,
  ): Promise<boolean>;
}

interface ResolvedDirectDispatchAuthority {
  readonly binding: LiveActivityDeliveryBinding;
  readonly token: StoredPushToStartToken | StoredLiveActivityUpdateToken;
}

function frozen<T extends object>(value: T): Readonly<T> {
  return Object.freeze(value);
}

function effectiveInstant(requested: Date, floor?: Date): Date {
  const instant = normalizedLiveActivityDispatchInstant(requested, "dispatch instant");
  if (floor != null && instant.getTime() < floor.getTime()) return new Date(floor);
  return instant;
}

function currentTokenGeneration(
  token: StoredPushToStartToken | StoredLiveActivityUpdateToken,
) {
  return frozen({
    clientGeneration: token.clientGeneration,
    serverRevision: token.serverRevision,
  });
}

function sessionIsAuthorized(
  session: StoredLiveCommuteSession | undefined,
  sessionRevision: number,
  at: Date,
): boolean {
  return (
    session != null &&
    session.lifecycle === "REGISTERED" &&
    session.revision === sessionRevision &&
    session.session.startsAt.getTime() <= at.getTime() &&
    session.session.endsAt.getTime() > at.getTime()
  );
}

async function resolveDirectAuthority(
  transaction: LiveActivityDispatchInstallationTransaction,
  input: {
    readonly installationId: string;
    readonly bindingId: string;
    readonly sessionRevision: number;
    readonly operation: LiveActivityDirectDispatchOperation;
    readonly at: Date;
  },
): Promise<
  | { readonly status: "AUTHORIZED"; readonly authority: ResolvedDirectDispatchAuthority }
  | {
      readonly status: "NOT_AUTHORIZED" | "NOT_DIRECT" | "TOKEN_UNAVAILABLE" | "START_BLOCKED";
    }
> {
  const installation = await transaction.getInstallation();
  if (installation?.state !== "ACTIVE") return frozen({ status: "NOT_AUTHORIZED" });
  const binding = await transaction.getDeliveryBinding(input.bindingId);
  if (
    binding == null ||
    binding.installationId !== input.installationId ||
    binding.sessionRevision !== input.sessionRevision ||
    binding.lifecycle !== "PENDING_START"
  ) {
    return frozen({ status: "NOT_AUTHORIZED" });
  }
  if (binding.strategy !== "DIRECT_TOKEN") return frozen({ status: "NOT_DIRECT" });
  if (
    !sessionIsAuthorized(
      await transaction.getSession(binding.sessionId),
      binding.sessionRevision,
      input.at,
    )
  ) {
    return frozen({ status: "NOT_AUTHORIZED" });
  }

  if (input.operation === "START") {
    const updateHistory = await transaction.getLatestUpdateToken(binding.bindingId);
    if (binding.appleActivityId != null || updateHistory != null) {
      return frozen({ status: "START_BLOCKED" });
    }
    const token = await transaction.getLatestPushToStartToken();
    if (token?.lifecycle !== "CURRENT") {
      return frozen({ status: "TOKEN_UNAVAILABLE" });
    }
    return frozen({ status: "AUTHORIZED", authority: frozen({ binding, token }) });
  }

  const token = await transaction.getLatestUpdateToken(binding.bindingId);
  if (token?.lifecycle !== "CURRENT") {
    return frozen({ status: "TOKEN_UNAVAILABLE" });
  }
  return frozen({ status: "AUTHORIZED", authority: frozen({ binding, token }) });
}

function completionAttempt(
  attempt: LiveActivityDirectDispatchAttempt,
  input: {
    readonly state: "ABORTED" | "SUPERSEDED";
    readonly completedAt: Date;
    readonly retryAdvice: LiveActivityDispatchRetryAdvice;
    readonly retryNotBefore?: Date | null;
  },
): LiveActivityDirectDispatchAttempt {
  return createLiveActivityDirectDispatchAttempt({
    ...attempt,
    state: input.state,
    retryAdvice: input.retryAdvice,
    retryNotBefore: input.retryNotBefore ?? null,
    completedAt: effectiveInstant(input.completedAt, attempt.createdAt),
  });
}

/**
 * Shared state machine. Concrete adapters provide a transaction that has already acquired
 * the installation-parent row lock. Its methods deliberately stop before any network seam.
 */
export abstract class CoordinatedLiveActivityDispatchStore
  implements LiveActivityDispatchStore
{
  protected abstract withDispatchInstallationTransaction<T>(
    installationId: string,
    operation: (
      transaction: LiveActivityDispatchInstallationTransaction,
    ) => Promise<T>,
  ): Promise<T>;

  async reserveDirectDispatch(
    input: ReserveLiveActivityDirectDispatchInput,
  ): Promise<ReserveLiveActivityDirectDispatchResult> {
    const installationId = normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    );
    const bindingId = normalizedLiveActivityBindingId(input.bindingId);
    const sessionRevision = positiveActivityKitGeneration(
      input.sessionRevision,
      "sessionRevision",
    );
    const operation = normalizedLiveActivityDispatchOperation(input.operation);
    const eventTimestamp = normalizedLiveActivityEventTimestamp(input.eventTimestamp);
    const dispatchId = normalizedLiveActivityDispatchUuid(input.dispatchId, "dispatchId");
    const apnsRequestId = normalizedLiveActivityDispatchUuid(
      input.apnsRequestId,
      "apnsRequestId",
    );
    const reservedAt = normalizedLiveActivityDispatchInstant(
      input.reservedAt,
      "reservedAt",
    );

    return await this.withDispatchInstallationTransaction(
      installationId,
      async (transaction) => {
        const resolved = await resolveDirectAuthority(transaction, {
          installationId,
          bindingId,
          sessionRevision,
          operation,
          at: reservedAt,
        });
        if (resolved.status !== "AUTHORIZED") {
          return frozen({ status: resolved.status, blockingDispatchId: null });
        }

        const cursor = await transaction.getDispatchCursor(bindingId);
        if (cursor != null) {
          if (eventTimestamp < cursor.lastReservedEventTimestamp) {
            return frozen({
              status: "STALE_EVENT",
              lastReservedEventTimestamp: cursor.lastReservedEventTimestamp,
            });
          }
          if (eventTimestamp === cursor.lastReservedEventTimestamp) {
            return frozen({
              status: "SAME_SECOND",
              lastReservedEventTimestamp: cursor.lastReservedEventTimestamp,
            });
          }
          if (
            operation === "DIRECT_UPDATE" &&
            cursor.terminalIntentEventTimestamp != null
          ) {
            return frozen({ status: "TERMINAL_INTENT", blockingDispatchId: null });
          }
        }

        const active = await transaction.getActiveDispatchAttempt(bindingId);
        let supersededDispatchId: string | null = null;
        if (active != null) {
          const maySupersede =
            active.state === "RESERVED" &&
            active.operation === "DIRECT_UPDATE" &&
            (operation === "DIRECT_UPDATE" || operation === "DIRECT_END");
          if (!maySupersede) {
            return frozen({
              status: operation === "START" ? "START_BLOCKED" : "BUSY",
              blockingDispatchId: active.dispatchId,
            });
          }
          const superseded = completionAttempt(active, {
            state: "SUPERSEDED",
            completedAt: reservedAt,
            retryAdvice: "NO_RETRY",
          });
          if (!(await transaction.updateDispatchAttempt("RESERVED", superseded))) {
            return frozen({ status: "BUSY", blockingDispatchId: active.dispatchId });
          }
          supersededDispatchId = active.dispatchId;
        }

        const latestSameOperation =
          await transaction.getLatestDispatchAttemptForOperation(
            bindingId,
            operation,
          );
        if (operation === "START" && latestSameOperation != null) {
          if (
            latestSameOperation.state === "RESERVED" ||
            latestSameOperation.state === "IN_FLIGHT" ||
            latestSameOperation.state === "ACCEPTED" ||
            latestSameOperation.state === "OUTCOME_UNKNOWN"
          ) {
            return frozen({
              status: "START_BLOCKED",
              blockingDispatchId: latestSameOperation.dispatchId,
            });
          }
          if (
            latestSameOperation.state === "RETRYABLE" &&
            latestSameOperation.retryNotBefore != null &&
            latestSameOperation.retryNotBefore.getTime() > reservedAt.getTime()
          ) {
            return frozen({
              status: "BUSY",
              blockingDispatchId: latestSameOperation.dispatchId,
            });
          }
        }
        if (
          operation === "DIRECT_END" &&
          cursor?.terminalIntentEventTimestamp != null &&
          latestSameOperation != null
        ) {
          if (
            latestSameOperation.state === "RESERVED" ||
            latestSameOperation.state === "IN_FLIGHT" ||
            latestSameOperation.state === "ACCEPTED" ||
            latestSameOperation.state === "OUTCOME_UNKNOWN"
          ) {
            return frozen({
              status:
                latestSameOperation.state === "RESERVED" ||
                latestSameOperation.state === "IN_FLIGHT"
                  ? "BUSY"
                  : "TERMINAL_INTENT",
              blockingDispatchId: latestSameOperation.dispatchId,
            });
          }
          if (
            latestSameOperation.state === "RETRYABLE" &&
            latestSameOperation.retryNotBefore != null &&
            latestSameOperation.retryNotBefore.getTime() > reservedAt.getTime()
          ) {
            return frozen({
              status: "BUSY",
              blockingDispatchId: latestSameOperation.dispatchId,
            });
          }
        }

        const token = resolved.authority.token;
        const nextCursor = createLiveActivityDispatchCursor({
          bindingId,
          installationId,
          sessionRevision,
          lastReservedEventTimestamp: eventTimestamp,
          terminalIntentEventTimestamp:
            cursor?.terminalIntentEventTimestamp ??
            (operation === "DIRECT_END" ? eventTimestamp : null),
          createdAt: cursor?.createdAt ?? reservedAt,
          updatedAt: effectiveInstant(reservedAt, cursor?.updatedAt),
        });
        const cursorSaved =
          cursor == null
            ? await transaction.insertDispatchCursor(nextCursor)
            : await transaction.updateDispatchCursor(nextCursor);
        if (!cursorSaved) throw new Error("dispatch cursor changed concurrently");

        const attempt = createLiveActivityDirectDispatchAttempt({
          dispatchId,
          bindingId,
          installationId,
          sessionRevision,
          operation,
          eventTimestamp,
          tokenGeneration: currentTokenGeneration(token),
          environment: token.environment,
          apnsRequestId,
          payloadFingerprint: null,
          state: "RESERVED",
          apnsStatus: null,
          apnsReason: null,
          retryAdvice: null,
          retryNotBefore: null,
          postSendAuthority: "NOT_CHECKED",
          tokenInvalidationOutcome: "NOT_APPLICABLE",
          createdAt: reservedAt,
          inFlightAt: null,
          completedAt: null,
        });
        if (!(await transaction.insertDispatchAttempt(attempt))) {
          throw new Error("dispatch attempt identity is already reserved");
        }
        return frozen({ status: "RESERVED", attempt, supersededDispatchId });
      },
    );
  }

  async claimDirectDispatch(
    input: ClaimLiveActivityDirectDispatchInput,
  ): Promise<ClaimLiveActivityDirectDispatchResult> {
    const installationId = normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    );
    const dispatchId = normalizedLiveActivityDispatchUuid(input.dispatchId, "dispatchId");
    const payloadFingerprint = normalizedPayloadFingerprint(input.payloadFingerprint);
    const claimedAt = normalizedLiveActivityDispatchInstant(input.claimedAt, "claimedAt");

    return await this.withDispatchInstallationTransaction(
      installationId,
      async (transaction) => {
        const attempt = await transaction.getDispatchAttempt(dispatchId);
        if (attempt == null || attempt.installationId !== installationId) {
          return frozen({ status: "NOT_FOUND", attempt: null });
        }
        if (attempt.state === "SUPERSEDED") {
          return frozen({ status: "SUPERSEDED", attempt });
        }
        if (attempt.state !== "RESERVED") {
          return frozen({ status: "ABORTED", attempt });
        }
        const cursor = await transaction.getDispatchCursor(attempt.bindingId);
        const resolved = await resolveDirectAuthority(transaction, {
          installationId,
          bindingId: attempt.bindingId,
          sessionRevision: attempt.sessionRevision,
          operation: attempt.operation,
          at: claimedAt,
        });
        const stillLatest =
          cursor != null &&
          cursor.lastReservedEventTimestamp === attempt.eventTimestamp;
        const tokenMatches =
          resolved.status === "AUTHORIZED" &&
          resolved.authority.token.environment === attempt.environment &&
          sameLiveActivityDispatchTokenGeneration(
            currentTokenGeneration(resolved.authority.token),
            attempt.tokenGeneration,
          );
        if (!stillLatest || !tokenMatches) {
          const terminalState = stillLatest ? "ABORTED" : "SUPERSEDED";
          const terminal = completionAttempt(attempt, {
            state: terminalState,
            completedAt: claimedAt,
            retryAdvice: "NO_RETRY",
          });
          if (!(await transaction.updateDispatchAttempt("RESERVED", terminal))) {
            return frozen({ status: "ABORTED", attempt });
          }
          return frozen({ status: terminalState, attempt: terminal });
        }

        const claimed = createLiveActivityDirectDispatchAttempt({
          ...attempt,
          payloadFingerprint,
          state: "IN_FLIGHT",
          inFlightAt: effectiveInstant(claimedAt, attempt.createdAt),
        });
        if (!(await transaction.updateDispatchAttempt("RESERVED", claimed))) {
          return frozen({ status: "ABORTED", attempt });
        }
        return frozen({ status: "CLAIMED", attempt: claimed });
      },
    );
  }

  async abortDirectDispatch(
    input: AbortLiveActivityDirectDispatchInput,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined> {
    const installationId = normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    );
    const dispatchId = normalizedLiveActivityDispatchUuid(input.dispatchId, "dispatchId");
    return await this.withDispatchInstallationTransaction(
      installationId,
      async (transaction) => {
        const attempt = await transaction.getDispatchAttempt(dispatchId);
        if (attempt == null || attempt.installationId !== installationId) return undefined;
        if (attempt.state !== "RESERVED") return attempt;
        const aborted = completionAttempt(attempt, {
          state: "ABORTED",
          completedAt: input.completedAt,
          retryAdvice: input.retryAdvice,
          retryNotBefore: input.retryNotBefore,
        });
        return (await transaction.updateDispatchAttempt("RESERVED", aborted))
          ? aborted
          : await transaction.getDispatchAttempt(dispatchId);
      },
    );
  }

  async completeDirectDispatch(
    input: CompleteLiveActivityDirectDispatchInput,
  ): Promise<CompleteLiveActivityDirectDispatchResult> {
    const installationId = normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    );
    const dispatchId = normalizedLiveActivityDispatchUuid(input.dispatchId, "dispatchId");
    const completedAt = normalizedLiveActivityDispatchInstant(
      input.completedAt,
      "completedAt",
    );
    normalizedSafeApnsReason(input.apnsReason);
    if (input.invalidateExactTokenGeneration && input.state !== "REJECTED") {
      throw new RangeError("only a rejected terminal destination may invalidate a token");
    }

    return await this.withDispatchInstallationTransaction(
      installationId,
      async (transaction) => {
        const attempt = await transaction.getDispatchAttempt(dispatchId);
        if (attempt == null || attempt.installationId !== installationId) {
          return frozen({ status: "NOT_FOUND", attempt: null });
        }
        if (attempt.state !== "IN_FLIGHT") {
          return frozen({ status: "NOT_IN_FLIGHT", attempt });
        }

        let postSendAuthority: LiveActivityDirectDispatchAttempt["postSendAuthority"] =
          "NOT_CHECKED";
        if (input.state === "ACCEPTED") {
          const resolved = await resolveDirectAuthority(transaction, {
            installationId,
            bindingId: attempt.bindingId,
            sessionRevision: attempt.sessionRevision,
            operation: attempt.operation,
            at: completedAt,
          });
          postSendAuthority =
            resolved.status === "AUTHORIZED" &&
            resolved.authority.token.environment === attempt.environment &&
            sameLiveActivityDispatchTokenGeneration(
              currentTokenGeneration(resolved.authority.token),
              attempt.tokenGeneration,
            )
              ? "MATCHED"
              : "CHANGED";
        }

        let tokenInvalidationOutcome: LiveActivityDirectDispatchAttempt["tokenInvalidationOutcome"] =
          "NOT_APPLICABLE";
        if (input.invalidateExactTokenGeneration) {
          tokenInvalidationOutcome = await this.invalidateExactGeneration(
            transaction,
            attempt,
            completedAt,
          );
        }

        const terminal = createLiveActivityDirectDispatchAttempt({
          ...attempt,
          state: input.state,
          apnsStatus: input.apnsStatus,
          apnsReason: input.apnsReason,
          retryAdvice: input.retryAdvice,
          retryNotBefore: input.retryNotBefore,
          postSendAuthority,
          tokenInvalidationOutcome,
          completedAt: effectiveInstant(completedAt, attempt.inFlightAt ?? attempt.createdAt),
        });
        if (!(await transaction.updateDispatchAttempt("IN_FLIGHT", terminal))) {
          return frozen({
            status: "NOT_IN_FLIGHT",
            attempt: await transaction.getDispatchAttempt(dispatchId) ?? null,
          });
        }
        return frozen({ status: "COMPLETED", attempt: terminal });
      },
    );
  }

  async getDirectDispatchAttempt(
    installationIdInput: string,
    dispatchIdInput: string,
  ): Promise<LiveActivityDirectDispatchAttempt | undefined> {
    const installationId = normalizedAppleDeliveryIdentifier(
      installationIdInput,
      "installationId",
    );
    const dispatchId = normalizedLiveActivityDispatchUuid(
      dispatchIdInput,
      "dispatchId",
    );
    return await this.withDispatchInstallationTransaction(
      installationId,
      async (transaction) => {
        const attempt = await transaction.getDispatchAttempt(dispatchId);
        return attempt?.installationId === installationId ? attempt : undefined;
      },
    );
  }

  private async invalidateExactGeneration(
    transaction: LiveActivityDispatchInstallationTransaction,
    attempt: LiveActivityDirectDispatchAttempt,
    completedAt: Date,
  ): Promise<LiveActivityDirectDispatchAttempt["tokenInvalidationOutcome"]> {
    if (attempt.operation === "START") {
      const exact = await transaction.getPushToStartToken(
        attempt.tokenGeneration.clientGeneration,
      );
      if (
        exact == null ||
        exact.serverRevision !== attempt.tokenGeneration.serverRevision ||
        exact.environment !== attempt.environment ||
        exact.lifecycle === "REPLACED"
      ) {
        return "GENERATION_NO_LONGER_CURRENT";
      }
      if (exact.lifecycle === "INVALIDATED") return "ALREADY_INVALIDATED";
      const latest = await transaction.getLatestPushToStartToken();
      if (
        latest == null ||
        latest.lifecycle !== "CURRENT" ||
        !sameLiveActivityDispatchTokenGeneration(
          currentTokenGeneration(latest),
          attempt.tokenGeneration,
        )
      ) {
        return "GENERATION_NO_LONGER_CURRENT";
      }
      const invalidatedAt = effectiveInstant(completedAt, exact.updatedAt);
      await transaction.savePushToStartToken(
        createStoredPushToStartToken({
          ...exact,
          lifecycle: "INVALIDATED",
          updatedAt: invalidatedAt,
          replacedAt: null,
          invalidatedAt,
        }),
      );
      return "INVALIDATED_EXACT_GENERATION";
    }

    const exact = await transaction.getUpdateToken(
      attempt.bindingId,
      attempt.tokenGeneration.clientGeneration,
    );
    if (
      exact == null ||
      exact.serverRevision !== attempt.tokenGeneration.serverRevision ||
      exact.environment !== attempt.environment ||
      exact.lifecycle === "REPLACED"
    ) {
      return "GENERATION_NO_LONGER_CURRENT";
    }
    if (exact.lifecycle === "INVALIDATED") return "ALREADY_INVALIDATED";
    const latest = await transaction.getLatestUpdateToken(attempt.bindingId);
    if (
      latest == null ||
      latest.lifecycle !== "CURRENT" ||
      !sameLiveActivityDispatchTokenGeneration(
        currentTokenGeneration(latest),
        attempt.tokenGeneration,
      )
    ) {
      return "GENERATION_NO_LONGER_CURRENT";
    }
    const invalidatedAt = effectiveInstant(completedAt, exact.updatedAt);
    await transaction.saveUpdateToken(
      createStoredLiveActivityUpdateToken({
        ...exact,
        lifecycle: "INVALIDATED",
        updatedAt: invalidatedAt,
        replacedAt: null,
        invalidatedAt,
      }),
    );
    return "INVALIDATED_EXACT_GENERATION";
  }
}
