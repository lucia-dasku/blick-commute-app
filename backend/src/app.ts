import { Hono } from "hono";
import { onError, notFoundHandler } from "./middleware/errorHandler.js";
import { healthRoute } from "./routes/health.js";
import { createStopsRoute } from "./routes/stops.js";
import { createDeparturesRoute } from "./routes/departures.js";
import { createDisruptionsRoute } from "./routes/disruptions.js";
import { createSlTransportClient } from "./services/slTransportClient.js";
import { createSlDeviationsClient } from "./services/slDeviationsClient.js";
import { createSiteDirectory } from "./services/siteDirectory.js";
import { InFlightDeduper, InMemoryCache } from "./lib/cache.js";

/**
 * Builds the Hono app with real (network-calling) service implementations. Kept as a
 * factory — rather than a module-level singleton — so tests can build an app wired to
 * fakes instead (see tests/routes.test.ts).
 */
export function createApp() {
  const cache = new InMemoryCache();
  const deduper = new InFlightDeduper();

  const slTransportClient = createSlTransportClient();
  const slDeviationsClient = createSlDeviationsClient();
  const siteDirectory = createSiteDirectory(slTransportClient, cache, deduper);

  const app = new Hono().basePath("/api/v1");

  app.route("/health", healthRoute);
  app.route("/stops", createStopsRoute(siteDirectory));
  app.route("/departures", createDeparturesRoute(slTransportClient));
  app.route("/disruptions", createDisruptionsRoute(slDeviationsClient, cache, deduper));

  app.notFound(notFoundHandler);
  app.onError(onError);

  return app;
}
