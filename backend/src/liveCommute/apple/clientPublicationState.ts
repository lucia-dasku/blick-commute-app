import type { LiveActivityDeliveryBinding } from "./deliveryModel.js";
import type {
  LiveActivityFrequentPushState,
  LiveActivityPublicationCapability,
} from "./publicationPolicy.js";
import type { LiveActivityClientPublicationStateProvider } from "./publicationWorker.js";
import {
  createLiveCommuteInstallationAuthenticationProof,
  liveCommuteInstallationCredentialMatches,
  LiveCommuteSessionServiceError,
  type LiveCommuteInstallationAuthentication,
} from "../installationService.js";
import type { StoredLiveCommuteInstallation } from "../sessionStore.js";

export type LiveActivityClientLocale = "en" | "sv";

export interface LiveActivityClientPublicationStateInput {
  readonly capability: LiveActivityPublicationCapability;
  readonly frequentPushes: LiveActivityFrequentPushState;
  readonly locale: LiveActivityClientLocale;
}

export interface StoredLiveActivityClientPublicationState
  extends LiveActivityClientPublicationStateInput {
  readonly installationId: string;
  readonly createdAt: Date;
  readonly updatedAt: Date;
}

export interface LiveActivityClientPublicationStateTransaction {
  getInstallation(): Promise<StoredLiveCommuteInstallation | undefined>;
  getState(): Promise<StoredLiveActivityClientPublicationState | undefined>;
  saveState(state: StoredLiveActivityClientPublicationState): Promise<void>;
}

export interface LiveActivityClientPublicationStateStore {
  withInstallationTransaction<T>(
    installationId: string,
    operation: (
      transaction: LiveActivityClientPublicationStateTransaction,
    ) => Promise<T>,
  ): Promise<T>;
  getState(
    installationId: string,
  ): Promise<StoredLiveActivityClientPublicationState | undefined>;
}

const CAPABILITIES: readonly LiveActivityPublicationCapability[] = [
  "DIRECT_LEGACY",
  "DIRECT_IOS18",
  "BROADCAST_CAPABLE",
  "UNKNOWN",
];
const FREQUENT_PUSH_STATES: readonly LiveActivityFrequentPushState[] = [
  "ENABLED",
  "DISABLED",
  "UNKNOWN",
];

function validDate(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value);
}

export function createStoredLiveActivityClientPublicationState(
  input: StoredLiveActivityClientPublicationState,
): StoredLiveActivityClientPublicationState {
  if (input == null || typeof input !== "object") {
    throw new TypeError("client publication state must be an object");
  }
  const installationId = input.installationId.trim();
  if (installationId.length === 0) {
    throw new RangeError("installationId is invalid");
  }
  if (!CAPABILITIES.includes(input.capability)) {
    throw new RangeError("client publication capability is invalid");
  }
  if (!FREQUENT_PUSH_STATES.includes(input.frequentPushes)) {
    throw new RangeError("frequent push state is invalid");
  }
  if (input.locale !== "en" && input.locale !== "sv") {
    throw new RangeError("client locale is invalid");
  }
  const createdAt = validDate(input.createdAt, "clientState.createdAt");
  const updatedAt = validDate(input.updatedAt, "clientState.updatedAt");
  if (updatedAt.getTime() < createdAt.getTime()) {
    throw new RangeError("client state updatedAt cannot precede createdAt");
  }
  return Object.freeze({
    installationId,
    capability: input.capability,
    frequentPushes: input.frequentPushes,
    locale: input.locale,
    createdAt,
    updatedAt,
  });
}

export class LiveActivityClientPublicationStateService {
  constructor(
    private readonly store: LiveActivityClientPublicationStateStore,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async update(
    authentication: LiveCommuteInstallationAuthentication,
    input: LiveActivityClientPublicationStateInput,
  ): Promise<StoredLiveActivityClientPublicationState> {
    const proof = createLiveCommuteInstallationAuthenticationProof(authentication);
    return await this.store.withInstallationTransaction(
      proof.installationId,
      async (transaction) => {
        const installation = await transaction.getInstallation();
        if (
          installation == null ||
          installation.state !== "ACTIVE" ||
          !liveCommuteInstallationCredentialMatches(
            installation.credentialDigest,
            proof.digest,
          )
        ) {
          throw new LiveCommuteSessionServiceError(
            "INSTALLATION_AUTHENTICATION_FAILED",
          );
        }
        const existing = await transaction.getState();
        const requestedAt = validDate(this.now(), "now()");
        const updatedAt =
          existing != null && existing.updatedAt.getTime() > requestedAt.getTime()
            ? existing.updatedAt
            : requestedAt;
        const state = createStoredLiveActivityClientPublicationState({
          installationId: proof.installationId,
          capability: input.capability,
          frequentPushes: input.frequentPushes,
          locale: input.locale,
          createdAt: existing?.createdAt ?? updatedAt,
          updatedAt,
        });
        await transaction.saveState(state);
        return state;
      },
    );
  }
}

export class StoredLiveActivityClientPublicationStateProvider
  implements LiveActivityClientPublicationStateProvider
{
  constructor(private readonly store: LiveActivityClientPublicationStateStore) {}

  async getClientPublicationState(binding: LiveActivityDeliveryBinding) {
    const state = await this.store.getState(binding.installationId);
    return Object.freeze({
      capability: state?.capability ?? "UNKNOWN",
      frequentPushes: state?.frequentPushes ?? "UNKNOWN",
    });
  }
}
