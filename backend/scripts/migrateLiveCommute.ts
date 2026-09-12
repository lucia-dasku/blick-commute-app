import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import postgres from "postgres";

export async function runLiveCommuteMigration(
  configuredConnectionString = process.env.LIVE_COMMUTE_MIGRATION_DATABASE_URL,
): Promise<void> {
  const connectionString = configuredConnectionString?.trim();
  if (!connectionString) {
    throw new Error(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL is required to run live commute migrations",
    );
  }

  let connectionUrl: URL;
  try {
    connectionUrl = new URL(connectionString);
  } catch {
    throw new Error(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL must use the postgres or postgresql protocol",
    );
  }
  if (connectionUrl.protocol !== "postgres:" && connectionUrl.protocol !== "postgresql:") {
    throw new Error(
      "LIVE_COMMUTE_MIGRATION_DATABASE_URL must use the postgres or postgresql protocol",
    );
  }

  const migrationPath = fileURLToPath(
    new URL("../migrations/002_live_commute_sessions.sql", import.meta.url),
  );
  const migration = await readFile(migrationPath, "utf8");
  const sql = postgres(connectionString, { max: 1, prepare: false });

  try {
    await sql.begin(async (transaction) => {
      await transaction.unsafe(migration);
    });
  } finally {
    await sql.end();
  }
}

const entryPath = process.argv[1];
if (entryPath != null && pathToFileURL(resolve(entryPath)).href === import.meta.url) {
  await runLiveCommuteMigration();
}
