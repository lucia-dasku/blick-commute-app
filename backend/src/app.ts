import { Hono } from "hono";
import { Redis } from "@upstash/redis";
import { onError, notFoundHandler } from "./middleware/errorHandler.js";
import { healthRoute } from "./routes/health.js";
import { createStopsRoute } from "./routes/stops.js";
import { createDeparturesRoute } from "./routes/departures.js";
import { createDisruptionsRoute } from "./routes/disruptions.js";
import { createSlTransportClient } from "./services/slTransportClient.js";
import { createSlDeviationsClient } from "./services/slDeviationsClient.js";
import { createSiteDirectory } from "./services/siteDirectory.js";
import { createDeviationsSnapshotService } from "./services/deviationsSnapshotService.js";
import { InFlightDeduper, InMemoryCache, type Cache } from "./lib/cache.js";
import { InMemoryLock, type DistributedLock } from "./lib/distributedLock.js";
import { RedisCache, RedisLock } from "./lib/redisClient.js";
import { config } from "./config/env.js";
import { createBillingRoute } from "./routes/billing.js";
import { createGooglePlayPurchaseVerifier } from "./services/googlePlayPurchaseVerifier.js";
import { createJourneyRoutes } from "./routes/journeys.js";
import { createJourneyDisruptionsRoute } from "./routes/journeyDisruptions.js";
import { createSlJourneyPlannerClient } from "./services/slJourneyPlannerClient.js";

/**
 * Builds the Hono app with real (network-calling) service implementations. Kept as a
 * factory — rather than a module-level singleton — so tests can build an app wired to
 * fakes instead (see tests/routes.test.ts).
 */
export function createApp() {
  // Unchanged: the site directory's own snapshot cache stays a plain, per-process
  // InMemoryCache — this scaffold's shared-protection work is scoped specifically to
  // SL Deviations (see docs/api-contract.md, "Caching and fair use"); site data changes
  // "at most once per day" per SL Transport's own docs and is not subject to a
  // per-minute fair-use limit the way SL Deviations is.
  const cache = new InMemoryCache();
  const deduper = new InFlightDeduper();

  const slTransportClient = createSlTransportClient();
  const slDeviationsClient = createSlDeviationsClient();
  const siteDirectory = createSiteDirectory(slTransportClient, cache, deduper);

  // Redis-backed cache/lock in production (or whenever Upstash credentials are
  // configured) — the ONLY implementation actually shared across Vercel instances; see
  // config.redis's own doc (src/config/env.ts) for why production fails to start rather
  // than silently falling back to the in-memory implementations below.
  const redisClient = config.redis ? new Redis({ url: config.redis.url, token: config.redis.token }) : undefined;
  const deviationsCache: Cache = redisClient ? new RedisCache(redisClient) : new InMemoryCache();
  const deviationsLock: DistributedLock = redisClient ? new RedisLock(redisClient) : new InMemoryLock();
  const deviationsSnapshotService = createDeviationsSnapshotService(slDeviationsClient, deviationsCache, deviationsLock);

  const app = new Hono().basePath("/api/v1");

  app.route("/health", healthRoute);
  app.route("/stops", createStopsRoute(siteDirectory));
  app.route("/departures", createDeparturesRoute(slTransportClient));
  app.route("/disruptions", createDisruptionsRoute(deviationsSnapshotService, siteDirectory));
  app.route("/billing", createBillingRoute(createGooglePlayPurchaseVerifier(config.googlePlay)));
  app.route("/journeys", createJourneyRoutes(createSlJourneyPlannerClient()));
  // A separate top-level mount, not nested inside createJourneyRoutes -- see
  // createJourneyDisruptionsRoute's own doc for why this must stay a genuinely independent
  // route/HTTP call rather than a field on /api/v1/journeys itself. Reuses the SAME
  // deviationsSnapshotService/siteDirectory instances /api/v1/disruptions already uses -- no new
  // upstream SL request is introduced by this route's existence.
  app.route("/journeys/disruptions", createJourneyDisruptionsRoute(deviationsSnapshotService, siteDirectory));

  app.notFound(notFoundHandler);
  app.onError(onError);

  return app;
}
