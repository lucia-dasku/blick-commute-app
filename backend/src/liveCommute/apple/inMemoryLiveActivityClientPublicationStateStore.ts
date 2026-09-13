import {
  createStoredLiveActivityClientPublicationState,
  type LiveActivityClientPublicationStateStore,
  type LiveActivityClientPublicationStateTransaction,
  type StoredLiveActivityClientPublicationState,
} from "./clientPublicationState.js";
import type { LiveCommuteSessionStore } from "../sessionStore.js";

export class InMemoryLiveActivityClientPublicationStateStore
  implements LiveActivityClientPublicationStateStore
{
  private readonly states = new Map<
    string,
    StoredLiveActivityClientPublicationState
  >();

  constructor(private readonly sessionStore: LiveCommuteSessionStore) {}

  async withInstallationTransaction<T>(
    installationId: string,
    operation: (
      transaction: LiveActivityClientPublicationStateTransaction,
    ) => Promise<T>,
  ): Promise<T> {
    return await this.sessionStore.withInstallationTransaction(
      installationId,
      async (sessionTransaction) => {
        let staged = this.states.get(installationId);
        let changed = false;
        const transaction: LiveActivityClientPublicationStateTransaction = {
          getInstallation: async () => await sessionTransaction.getInstallation(),
          getState: async () =>
            staged == null
              ? undefined
              : createStoredLiveActivityClientPublicationState(staged),
          saveState: async (input) => {
            const state = createStoredLiveActivityClientPublicationState(input);
            if (state.installationId !== installationId) {
              throw new Error("client state ownership mismatch");
            }
            staged = state;
            changed = true;
          },
        };
        const result = await operation(transaction);
        if (changed && staged != null) this.states.set(installationId, staged);
        return result;
      },
    );
  }

  async getState(
    installationId: string,
  ): Promise<StoredLiveActivityClientPublicationState | undefined> {
    const state = this.states.get(installationId);
    return state == null
      ? undefined
      : createStoredLiveActivityClientPublicationState(state);
  }
}
