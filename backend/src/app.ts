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
import { createStopPointDirectory } from "./services/stopPointDirectory.js";
import { InFlightDeduper, InMemoryCache, type Cache } from "./lib/cache.js";
import { InMemoryLock, type DistributedLock } from "./lib/distributedLock.js";
import { RedisCache, RedisLock } from "./lib/redisClient.js";
import { config } from "./config/env.js";
import { createBillingRoute } from "./routes/billing.js";
import { createGooglePlayPurchaseVerifier } from "./services/googlePlayPurchaseVerifier.js";
import { createJourneyRoutes } from "./routes/journeys.js";
import { createJourneyDisruptionsRoute } from "./routes/journeyDisruptions.js";
import { createSlJourneyPlannerClient } from "./services/slJourneyPlannerClient.js";
import { createGtfsFeedSource, createGtfsStopIdResolver, createLineTopologyDirectory, type LineTopologyDirectory } from "./services/lineTopologyDirectory.js";
import { createJourneyEndpointSiteResolver, type JourneyEndpointSiteResolver } from "./services/journeyEndpointSiteResolver.js";

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
  // than silently falling back to the in-memory implementations below. Shared by BOTH the SL
  // Deviations snapshot (`deviationsSnapshotService`) and the StopPointDirectory index below —
  // two independently-keyed snapshots on the same underlying Redis connection, never one
  // fetching/refreshing the other's data.
  const redisClient = config.redis ? new Redis({ url: config.redis.url, token: config.redis.token }) : undefined;
  const sharedRedisCache: Cache = redisClient ? new RedisCache(redisClient) : new InMemoryCache();
  const sharedRedisLock: DistributedLock = redisClient ? new RedisLock(redisClient) : new InMemoryLock();
  const deviationsSnapshotService = createDeviationsSnapshotService(slDeviationsClient, sharedRedisCache, sharedRedisLock);
  // See services/stopPointDirectory.ts's own "Caching" doc for why this reuses the same
  // Redis-backed Cache/DistributedLock as Deviations above, despite SL Transport's own
  // stop-points endpoint carrying no comparable strict fair-use requirement.
  // Also serves the segment-parsing enhancement's own name-based StopArea lookup
  // (`findStopAreaIdsByName`, passed to createLineTopologyDirectory below as its `nameIndex`) from
  // this SAME cached snapshot — see stopPointDirectory.ts's own "One upstream snapshot, two
  // derived indexes" doc for why this is one shared snapshot, not two independently-cached ones.
  const stopPointDirectory = createStopPointDirectory(slTransportClient, sharedRedisCache, sharedRedisLock, new InFlightDeduper());

  const slJourneyPlannerClient = createSlJourneyPlannerClient();

  // The segment-parsing disruption-relevance enhancement (see routes/journeyDisruptions.ts's own
  // "segment-parsing relevance enhancement" doc) is wired ONLY when TRAFIKLAB_API_KEY is
  // configured (production-readiness review, item 21) — both collaborators stay `undefined`
  // otherwise, which routes/journeyDisruptions.ts's own doc guarantees behaves byte-for-byte like
  // this enhancement never existed: zero GTFS work, zero segment-topology cache checks, zero
  // requested-endpoint resolver construction, for a request that will only ever reach
  // LINE_RELEVANT for a line-only deviation anyway. This deliberately does NOT wire the OLD
  // permanently-inert placeholders (`createUnavailableGtfsFeedSource`/
  // `createUnprovenGtfsStopIdResolver`) as a fallback — production-readiness review, item 21: "Do
  // not retain [them] as production architecture once the real implementation exists." They
  // remain exported from lineTopologyDirectory.ts purely as documented, tested fakes for that
  // module's own test suite.
  //
  // IMPORTANT (human-operator-facing, not something this code can enforce): "TRAFIKLAB_API_KEY is
  // configured" is NOT the same claim as "the GTFS-stop-id identity bridge has been verified".
  // createGtfsStopIdResolver's own doc documents a genuine, evidence-backed HYPOTHESIS (matching
  // class-id prefixes between SL Transport's own `gid` and GTFS Regional's own `stop_id`, both
  // from SL's pubtrans/NOPTIS source system) — not a proven fact. Before setting this key in any
  // real deployment, run scripts/verifyGtfsStopIdentityBridge.ts against the real feed and review
  // its resolved/unresolved/ambiguous results; this backend has no way to enforce that step
  // itself, since verification is an inherently manual, one-time judgment call a boolean
  // environment variable cannot capture on its own.
  let lineTopologyDirectory: LineTopologyDirectory | undefined;
  let journeyEndpointSiteResolver: JourneyEndpointSiteResolver | undefined;
  if (config.trafiklabApiKey) {
    lineTopologyDirectory = createLineTopologyDirectory(
      createGtfsFeedSource(),
      createGtfsStopIdResolver(stopPointDirectory),
      stopPointDirectory,
      sharedRedisCache,
      sharedRedisLock,
      new InFlightDeduper(),
    );
    journeyEndpointSiteResolver = createJourneyEndpointSiteResolver(slJourneyPlannerClient, siteDirectory, sharedRedisCache);
  }

  const app = new Hono().basePath("/api/v1");

  app.route("/health", healthRoute);
  app.route("/stops", createStopsRoute(siteDirectory));
  app.route("/departures", createDeparturesRoute(slTransportClient));
  app.route("/disruptions", createDisruptionsRoute(deviationsSnapshotService, siteDirectory));
  app.route("/billing", createBillingRoute(createGooglePlayPurchaseVerifier(config.googlePlay)));
  app.route("/journeys", createJourneyRoutes(slJourneyPlannerClient));
  // A separate top-level mount, not nested inside createJourneyRoutes -- see
  // createJourneyDisruptionsRoute's own doc for why this must stay a genuinely independent
  // route/HTTP call rather than a field on /api/v1/journeys itself. Reuses the SAME
  // deviationsSnapshotService/siteDirectory/stopPointDirectory instances -- no new upstream SL
  // request is introduced by this route's existence beyond what those services already make.
  // lineTopologyDirectory/journeyEndpointSiteResolver power the segment-parsing enhancement --
  // see that route's own doc; both remain safely inert until TRAFIKLAB_API_KEY is configured.
  app.route(
    "/journeys/disruptions",
    createJourneyDisruptionsRoute(deviationsSnapshotService, siteDirectory, stopPointDirectory, lineTopologyDirectory, journeyEndpointSiteResolver),
  );

  app.notFound(notFoundHandler);
  app.onError(onError);

  return app;
}
