import { randomUUID } from "node:crypto";
import postgres from "postgres";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { runLiveActivityClientPublicationStateMigration } from "../scripts/migrateLiveActivityClientPublicationState.js";
import { runLiveCommuteMigration } from "../scripts/migrateLiveCommute.js";
import { createLiveCommuteInstallationService } from "../src/liveCommute/installationService.js";
import { PostgresLiveCommuteSessionStore } from "../src/liveCommute/postgresLiveCommuteSessionStore.js";
import {
  LiveActivityClientPublicationStateService,
  StoredLiveActivityClientPublicationStateProvider,
} from "../src/liveCommute/apple/clientPublicationState.js";
import { PostgresLiveActivityClientPublicationStateStore } from "../src/liveCommute/apple/postgresLiveActivityClientPublicationStateStore.js";
import {
  checkedLocalLiveCommuteTestDatabaseUrl,
  LIVE_COMMUTE_TEST_SCHEMA_PREFIX,
  liveCommuteTestSchemaConnectionUrl,
} from "./liveCommutePostgresTestSupport.js";

const RAW_TEST_DATABASE_URL =
  process.env.LIVE_COMMUTE_TEST_DATABASE_URL?.trim() || undefined;
const describeWithPostgres = RAW_TEST_DATABASE_URL == null ? describe.skip : describe;
const NOW = new Date("2026-09-13T08:00:00.000Z");

describeWithPostgres("PostgreSQL Live Activity client publication state", () => {
  const schema = `${LIVE_COMMUTE_TEST_SCHEMA_PREFIX}client_${randomUUID().replaceAll("-", "")}`;
  let adminSql: ReturnType<typeof postgres> | undefined;
  let firstSql: ReturnType<typeof postgres> | undefined;
  let secondSql: ReturnType<typeof postgres> | undefined;

  beforeAll(async () => {
    const connectionString = checkedLocalLiveCommuteTestDatabaseUrl(
      RAW_TEST_DATABASE_URL!,
    );
    adminSql = postgres(connectionString, { max: 1, prepare: false });
    await adminSql`CREATE SCHEMA ${adminSql(schema)}`;
    const scoped = liveCommuteTestSchemaConnectionUrl(connectionString, schema);
    firstSql = postgres(scoped, { max: 1, prepare: false });
    secondSql = postgres(scoped, { max: 1, prepare: false });
    for (let application = 0; application < 2; application += 1) {
      await runLiveCommuteMigration(scoped);
      await runLiveActivityClientPublicationStateMigration(scoped);
    }
  });

  beforeEach(async () => {
    await firstSql!`
      TRUNCATE live_activity_client_publication_state,
               live_commute_sessions,
               live_commute_installations
    `;
  });

  afterAll(async () => {
    await firstSql?.end({ timeout: 5 });
    await secondSql?.end({ timeout: 5 });
    if (adminSql != null) {
      if (!schema.startsWith(LIVE_COMMUTE_TEST_SCHEMA_PREFIX)) {
        throw new Error("refusing to remove an unexpected client state test schema");
      }
      await adminSql`DROP SCHEMA IF EXISTS ${adminSql(schema)} CASCADE`;
      await adminSql.end({ timeout: 5 });
    }
  });

  it("persists exact values across adapters and supplies worker policy state", async () => {
    const sessionStore = new PostgresLiveCommuteSessionStore(firstSql!);
    const auth = await createLiveCommuteInstallationService(sessionStore, {
      now: () => new Date(NOW),
    }).registerInstallation();
    const firstStore = new PostgresLiveActivityClientPublicationStateStore(firstSql!);
    await new LiveActivityClientPublicationStateService(
      firstStore,
      () => new Date(NOW),
    ).update(auth, {
      capability: "DIRECT_IOS18",
      frequentPushes: "ENABLED",
      locale: "sv",
    });

    const secondStore = new PostgresLiveActivityClientPublicationStateStore(secondSql!);
    expect(await secondStore.getState(auth.installationId)).toMatchObject({
      capability: "DIRECT_IOS18",
      frequentPushes: "ENABLED",
      locale: "sv",
    });
    const provider = new StoredLiveActivityClientPublicationStateProvider(secondStore);
    await expect(
      provider.getClientPublicationState({ installationId: auth.installationId } as never),
    ).resolves.toEqual({ capability: "DIRECT_IOS18", frequentPushes: "ENABLED" });
  });

  it("rejects cross-installation authentication and schema-invalid values", async () => {
    const sessionStore = new PostgresLiveCommuteSessionStore(firstSql!);
    const installationService = createLiveCommuteInstallationService(sessionStore, {
      now: () => new Date(NOW),
    });
    const first = await installationService.registerInstallation();
    const second = await installationService.registerInstallation();
    const store = new PostgresLiveActivityClientPublicationStateStore(firstSql!);
    const service = new LiveActivityClientPublicationStateService(store);
    await expect(
      service.update(
        { installationId: first.installationId, bearerCredential: second.bearerCredential },
        { capability: "UNKNOWN", frequentPushes: "UNKNOWN", locale: "en" },
      ),
    ).rejects.toMatchObject({ code: "INSTALLATION_AUTHENTICATION_FAILED" });

    await expect(firstSql!`
      INSERT INTO live_activity_client_publication_state (
        installation_id, capability, frequent_pushes, locale, created_at, updated_at
      ) VALUES (
        ${first.installationId}, 'INVALID', 'UNKNOWN', 'en', ${NOW}, ${NOW}
      )
    `).rejects.toBeDefined();
  });
});
