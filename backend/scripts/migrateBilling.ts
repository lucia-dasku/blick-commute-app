import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import postgres from "postgres";

const connectionString = process.env.DATABASE_URL?.trim();
if (!connectionString) throw new Error("DATABASE_URL is required to run billing migrations");

const migrationPath = fileURLToPath(new URL("../migrations/001_google_play_billing.sql", import.meta.url));
const migration = await readFile(migrationPath, "utf8");
const sql = postgres(connectionString, { max: 1, prepare: false });

try {
  await sql.begin(async (transaction) => {
    await transaction.unsafe(migration);
  });
} finally {
  await sql.end();
}
