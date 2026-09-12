export const LIVE_COMMUTE_TEST_SCHEMA_PREFIX = "blick_live_commute_test_";

export function checkedLocalLiveCommuteTestDatabaseUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must be a valid PostgreSQL URL");
  }
  if (url.protocol !== "postgres:" && url.protocol !== "postgresql:") {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must use PostgreSQL");
  }
  const database = decodeURIComponent(url.pathname.replace(/^\/+/, ""));
  if (
    !/(^|[-_])test([-_]|$)/i.test(database) ||
    /(^|[-_])(prod|production)([-_]|$)/i.test(database)
  ) {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must name a dedicated test database");
  }
  if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
    throw new Error("LIVE_COMMUTE_TEST_DATABASE_URL must point to a local database");
  }
  return url.toString();
}

export function liveCommuteTestSchemaConnectionUrl(
  connectionString: string,
  schema: string,
): string {
  if (!schema.startsWith(LIVE_COMMUTE_TEST_SCHEMA_PREFIX)) {
    throw new Error("refusing to use an unexpected live commute test schema");
  }
  const url = new URL(connectionString);
  url.searchParams.set("search_path", schema);
  return url.toString();
}
