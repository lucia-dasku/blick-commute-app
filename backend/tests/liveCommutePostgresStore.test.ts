import { describe, expect, it } from "vitest";
import {
  LiveCommutePersistenceError,
  PostgresLiveCommuteSessionStore,
  type LiveCommutePostgresSql,
} from "../src/liveCommute/postgresLiveCommuteSessionStore.js";
import {
  createStoredLiveCommuteInstallation,
  installationCredentialDigest,
} from "../src/liveCommute/sessionStore.js";

describe("PostgreSQL live commute adapter diagnostics", () => {
  it("removes hidden bound parameters from installation-creation failures", async () => {
    const digest = "a".repeat(64);
    const driverError = new Error("database connection failed");
    Object.defineProperty(driverError, "parameters", {
      value: [digest],
      enumerable: false,
    });
    const failingSql = (() => Promise.reject(driverError)) as unknown as LiveCommutePostgresSql;
    const store = new PostgresLiveCommuteSessionStore(failingSql);
    const createdAt = new Date("2026-09-12T06:00:00.000Z");

    const error = await store
      .createInstallation(
        createStoredLiveCommuteInstallation({
          installationId: "00000000-0000-4000-8000-000000000001",
          credentialDigest: installationCredentialDigest(digest),
          state: "ACTIVE",
          createdAt,
          updatedAt: createdAt,
          revokedAt: null,
        }),
      )
      .catch((failure: unknown) => failure);

    expect(error).toEqual(expect.any(LiveCommutePersistenceError));
    expect(error).toMatchObject({
      code: "LIVE_COMMUTE_PERSISTENCE_FAILED",
      message: "Live commute persistence operation failed",
    });
    expect(Reflect.ownKeys(error as object)).not.toContain("parameters");
    expect(JSON.stringify(Object.getOwnPropertyDescriptors(error as object))).not.toContain(
      digest,
    );
  });
});
