import { afterEach, describe, expect, it, vi } from "vitest";

const ORIGINAL_NODE_ENV = process.env.NODE_ENV;
const ORIGINAL_REDIS_URL = process.env.UPSTASH_REDIS_REST_URL;
const ORIGINAL_REDIS_TOKEN = process.env.UPSTASH_REDIS_REST_TOKEN;
const ORIGINAL_DATABASE_URL = process.env.DATABASE_URL;
const ORIGINAL_LIVE_COMMUTE_DATABASE_URL =
  process.env.LIVE_COMMUTE_MIGRATION_DATABASE_URL;

function restoreEnvironment(): void {
  if (ORIGINAL_NODE_ENV == null) delete process.env.NODE_ENV;
  else process.env.NODE_ENV = ORIGINAL_NODE_ENV;

  if (ORIGINAL_REDIS_URL == null) delete process.env.UPSTASH_REDIS_REST_URL;
  else process.env.UPSTASH_REDIS_REST_URL = ORIGINAL_REDIS_URL;

  if (ORIGINAL_REDIS_TOKEN == null) delete process.env.UPSTASH_REDIS_REST_TOKEN;
  else process.env.UPSTASH_REDIS_REST_TOKEN = ORIGINAL_REDIS_TOKEN;

  if (ORIGINAL_DATABASE_URL == null) delete process.env.DATABASE_URL;
  else process.env.DATABASE_URL = ORIGINAL_DATABASE_URL;

  if (ORIGINAL_LIVE_COMMUTE_DATABASE_URL == null) {
    delete process.env.LIVE_COMMUTE_MIGRATION_DATABASE_URL;
  } else {
    process.env.LIVE_COMMUTE_MIGRATION_DATABASE_URL =
      ORIGINAL_LIVE_COMMUTE_DATABASE_URL;
  }
}

afterEach(() => {
  restoreEnvironment();
  vi.resetModules();
});
describe("live commute module import safety", () => {
  it("does not require production infrastructure configuration just to load live commute modules", async () => {
    process.env.NODE_ENV = "production";
    delete process.env.UPSTASH_REDIS_REST_URL;
    delete process.env.UPSTASH_REDIS_REST_TOKEN;
    delete process.env.DATABASE_URL;
    delete process.env.LIVE_COMMUTE_MIGRATION_DATABASE_URL;
    vi.resetModules();

    const [
      engine,
      coordinator,
      installationService,
      memoryStore,
      postgresStore,
      migrationRunner,
      tokenProtection,
      deliveryService,
      deliveryResolver,
      memoryDeliveryStore,
      postgresDeliveryStore,
      deliveryMigrationRunner,
    ] =
      await Promise.all([
        import("../src/liveCommute/engine.js"),
        import("../src/liveCommute/coordinator.js"),
        import("../src/liveCommute/installationService.js"),
        import("../src/liveCommute/inMemoryLiveCommuteSessionStore.js"),
        import("../src/liveCommute/postgresLiveCommuteSessionStore.js"),
        import("../scripts/migrateLiveCommute.js"),
        import("../src/liveCommute/apple/tokenProtection.js"),
        import("../src/liveCommute/apple/deliveryService.js"),
        import("../src/liveCommute/apple/deliveryResolver.js"),
        import("../src/liveCommute/apple/inMemoryLiveActivityDeliveryStore.js"),
        import("../src/liveCommute/apple/postgresLiveActivityDeliveryStore.js"),
        import("../scripts/migrateLiveActivityDelivery.js"),
      ]);

    expect(engine.runLiveCommuteTick).toBeTypeOf("function");
    expect(coordinator.runStoredLiveCommuteTick).toBeTypeOf("function");
    expect(installationService.createLiveCommuteInstallationService).toBeTypeOf(
      "function",
    );
    expect(memoryStore.InMemoryLiveCommuteSessionStore).toBeTypeOf("function");
    expect(postgresStore.PostgresLiveCommuteSessionStore).toBeTypeOf("function");
    expect(migrationRunner.runLiveCommuteMigration).toBeTypeOf("function");
    expect(tokenProtection.createAes256GcmActivityKitTokenProtector).toBeTypeOf(
      "function",
    );
    expect(deliveryService.createLiveActivityDeliveryService).toBeTypeOf("function");
    expect(deliveryResolver.createLiveActivityDeliveryResolver).toBeTypeOf("function");
    expect(memoryDeliveryStore.InMemoryLiveActivityDeliveryStore).toBeTypeOf("function");
    expect(postgresDeliveryStore.PostgresLiveActivityDeliveryStore).toBeTypeOf(
      "function",
    );
    expect(deliveryMigrationRunner.runLiveActivityDeliveryMigration).toBeTypeOf(
      "function",
    );
    await expect(migrationRunner.runLiveCommuteMigration("")).rejects.toThrow(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL is required",
    );
    await expect(
      deliveryMigrationRunner.runLiveActivityDeliveryMigration(""),
    ).rejects.toThrow("LIVE_COMMUTE_MIGRATION_DATABASE_URL is required");
  });
});
