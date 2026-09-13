import {
  createLiveActivityDispatchBindingReference,
  createLiveActivityDirectDispatchHistory,
  createLiveActivityDirectDispatchAttempt,
  createLiveActivityDispatchCursor,
  type LiveActivityDispatchBindingReference,
  type LiveActivityDirectDispatchHistory,
  type LiveActivityDirectDispatchAttempt,
  type LiveActivityDispatchCursor,
} from "./dispatchModel.js";
import {
  CoordinatedLiveActivityDispatchStore,
  type LiveActivityDispatchInstallationTransaction,
} from "./dispatchStore.js";
import type { LiveActivityDeliveryStore } from "./deliveryStore.js";

interface InstallationDispatchState {
  readonly cursors: Map<string, LiveActivityDispatchCursor>;
  readonly attempts: Map<string, LiveActivityDirectDispatchAttempt>;
}

function emptyState(): InstallationDispatchState {
  return { cursors: new Map(), attempts: new Map() };
}

function cloneState(
  input: InstallationDispatchState | undefined,
): InstallationDispatchState {
  if (input == null) return emptyState();
  return {
    cursors: new Map(
      [...input.cursors].map(([bindingId, cursor]) => [
        bindingId,
        createLiveActivityDispatchCursor(cursor),
      ]),
    ),
    attempts: new Map(
      [...input.attempts].map(([dispatchId, attempt]) => [
        dispatchId,
        createLiveActivityDirectDispatchAttempt(attempt),
      ]),
    ),
  };
}

function sameCursorIdentity(
  left: LiveActivityDispatchCursor,
  right: LiveActivityDispatchCursor,
): boolean {
  return (
    left.bindingId === right.bindingId &&
    left.installationId === right.installationId &&
    left.sessionRevision === right.sessionRevision &&
    left.createdAt.getTime() === right.createdAt.getTime()
  );
}

function sameAttemptIdentity(
  left: LiveActivityDirectDispatchAttempt,
  right: LiveActivityDirectDispatchAttempt,
): boolean {
  const publicationMetadataMatches =
    left.publicationMetadata == null || right.publicationMetadata == null
      ? left.publicationMetadata === right.publicationMetadata
      : left.publicationMetadata.visibleContentFingerprint ===
          right.publicationMetadata.visibleContentFingerprint &&
        left.publicationMetadata.sourceFetchedAt.getTime() ===
          right.publicationMetadata.sourceFetchedAt.getTime() &&
        (left.publicationMetadata.staleAt == null ||
        right.publicationMetadata.staleAt == null
          ? left.publicationMetadata.staleAt === right.publicationMetadata.staleAt
          : left.publicationMetadata.staleAt.getTime() ===
            right.publicationMetadata.staleAt.getTime());
  return (
    left.dispatchId === right.dispatchId &&
    left.bindingId === right.bindingId &&
    left.installationId === right.installationId &&
    left.sessionRevision === right.sessionRevision &&
    left.operation === right.operation &&
    left.eventTimestamp === right.eventTimestamp &&
    left.tokenGeneration.clientGeneration === right.tokenGeneration.clientGeneration &&
    left.tokenGeneration.serverRevision === right.tokenGeneration.serverRevision &&
    left.environment === right.environment &&
    left.apnsRequestId === right.apnsRequestId &&
    publicationMetadataMatches &&
    left.createdAt.getTime() === right.createdAt.getTime()
  );
}

function validAttemptTransition(
  current: LiveActivityDirectDispatchAttempt,
  next: LiveActivityDirectDispatchAttempt,
): boolean {
  if (!sameAttemptIdentity(current, next)) return false;
  if (current.state === "RESERVED") {
    return (
      next.state === "IN_FLIGHT" ||
      next.state === "ABORTED" ||
      next.state === "SUPERSEDED"
    );
  }
  return (
    current.state === "IN_FLIGHT" &&
    (next.state === "ACCEPTED" ||
      next.state === "REJECTED" ||
      next.state === "RETRYABLE" ||
      next.state === "OUTCOME_UNKNOWN" ||
      next.state === "ABORTED")
  );
}

/**
 * Deterministic test adapter. The supplied Phase 3B store owns the installation lock;
 * dispatch maps are staged inside that transaction and commit only after its callback does.
 */
export class InMemoryLiveActivityDispatchStore extends CoordinatedLiveActivityDispatchStore {
  private readonly states = new Map<string, InstallationDispatchState>();

  constructor(private readonly deliveryStore: LiveActivityDeliveryStore) {
    super();
  }

  async listDirectDispatchHistoryForBindings(
    references: readonly LiveActivityDispatchBindingReference[],
  ): Promise<readonly LiveActivityDirectDispatchHistory[]> {
    if (!Array.isArray(references)) {
      throw new TypeError("dispatch binding references must be an array");
    }
    return Object.freeze(
      references.map((input) => {
        const reference = createLiveActivityDispatchBindingReference(input);
        const attempts = [
          ...(this.states.get(reference.installationId)?.attempts.values() ?? []),
        ]
          .filter(
            (attempt) =>
              attempt.bindingId === reference.bindingId &&
              attempt.installationId === reference.installationId &&
              attempt.sessionRevision === reference.sessionRevision,
          )
          .sort((left, right) => right.eventTimestamp - left.eventTimestamp);
        return createLiveActivityDirectDispatchHistory({
          ...reference,
          latestAcceptedAttempt:
            attempts.find((attempt) => attempt.state === "ACCEPTED") ?? null,
          latestAttempt: attempts[0] ?? null,
        });
      }),
    );
  }

  protected async withDispatchInstallationTransaction<T>(
    installationId: string,
    operation: (
      transaction: LiveActivityDispatchInstallationTransaction,
    ) => Promise<T>,
  ): Promise<T> {
    return await this.deliveryStore.withInstallationTransaction(
      installationId,
      async (deliveryTransaction) => {
        const staged = cloneState(this.states.get(installationId));
        let changed = false;
        const transaction: LiveActivityDispatchInstallationTransaction = {
          ...deliveryTransaction,
          getDispatchCursor: async (bindingId) => {
            const cursor = staged.cursors.get(bindingId);
            return cursor == null ? undefined : createLiveActivityDispatchCursor(cursor);
          },
          insertDispatchCursor: async (cursorInput) => {
            const cursor = createLiveActivityDispatchCursor(cursorInput);
            if (
              cursor.installationId !== installationId ||
              staged.cursors.has(cursor.bindingId)
            ) {
              return false;
            }
            staged.cursors.set(cursor.bindingId, cursor);
            changed = true;
            return true;
          },
          updateDispatchCursor: async (cursorInput) => {
            const cursor = createLiveActivityDispatchCursor(cursorInput);
            const current = staged.cursors.get(cursor.bindingId);
            if (
              current == null ||
              cursor.installationId !== installationId ||
              !sameCursorIdentity(current, cursor) ||
              cursor.lastReservedEventTimestamp <=
                current.lastReservedEventTimestamp ||
              (current.terminalIntentEventTimestamp != null &&
                cursor.terminalIntentEventTimestamp !==
                  current.terminalIntentEventTimestamp)
            ) {
              return false;
            }
            staged.cursors.set(cursor.bindingId, cursor);
            changed = true;
            return true;
          },
          getDispatchAttempt: async (dispatchId) => {
            const attempt = staged.attempts.get(dispatchId);
            return attempt == null
              ? undefined
              : createLiveActivityDirectDispatchAttempt(attempt);
          },
          getActiveDispatchAttempt: async (bindingId) => {
            const active = [...staged.attempts.values()].find(
              (attempt) =>
                attempt.bindingId === bindingId &&
                (attempt.state === "RESERVED" || attempt.state === "IN_FLIGHT"),
            );
            return active == null
              ? undefined
              : createLiveActivityDirectDispatchAttempt(active);
          },
          getLatestDispatchAttemptForOperation: async (bindingId, kind) => {
            const latest = [...staged.attempts.values()]
              .filter(
                (attempt) =>
                  attempt.bindingId === bindingId && attempt.operation === kind,
              )
              .sort(
                (left, right) => right.eventTimestamp - left.eventTimestamp,
              )[0];
            return latest == null
              ? undefined
              : createLiveActivityDirectDispatchAttempt(latest);
          },
          insertDispatchAttempt: async (attemptInput) => {
            const attempt = createLiveActivityDirectDispatchAttempt(attemptInput);
            if (
              attempt.installationId !== installationId ||
              staged.attempts.has(attempt.dispatchId) ||
              [...staged.attempts.values()].some(
                (current) =>
                  current.apnsRequestId === attempt.apnsRequestId ||
                  (current.bindingId === attempt.bindingId &&
                    current.eventTimestamp === attempt.eventTimestamp) ||
                  (current.bindingId === attempt.bindingId &&
                    (current.state === "RESERVED" || current.state === "IN_FLIGHT")) ||
                  (current.bindingId === attempt.bindingId &&
                    attempt.operation === "START" &&
                    current.operation === "START" &&
                    (current.state === "RESERVED" ||
                      current.state === "IN_FLIGHT" ||
                      current.state === "ACCEPTED" ||
                      current.state === "OUTCOME_UNKNOWN")),
              )
            ) {
              return false;
            }
            staged.attempts.set(attempt.dispatchId, attempt);
            changed = true;
            return true;
          },
          updateDispatchAttempt: async (expectedState, attemptInput) => {
            const attempt = createLiveActivityDirectDispatchAttempt(attemptInput);
            const current = staged.attempts.get(attempt.dispatchId);
            if (
              current == null ||
              current.state !== expectedState ||
              !validAttemptTransition(current, attempt)
            ) {
              return false;
            }
            staged.attempts.set(attempt.dispatchId, attempt);
            changed = true;
            return true;
          },
        };
        const result = await operation(transaction);
        if (changed) this.states.set(installationId, cloneState(staged));
        return result;
      },
    );
  }
}
