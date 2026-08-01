import { Hono } from "hono";
import { successEnvelope } from "../models/common.js";
import { AppError } from "../lib/errors.js";
import { normalizeDeparturesResponse } from "../normalize/normalizeDeparture.js";
import { isInvalidStockholmTimestampError } from "../lib/stockholmTime.js";
import type { SlTransportClient } from "../services/slTransportClient.js";

// Empirically confirmed against the real SL Transport API (2026-08-01): `forecast` values
// above 1200 silently return zero departures rather than an error, so 1200 is the real
// upper bound, not merely a guess or a value copied from unverified docs. See
// docs/api-contract.md, "Departures" for the account of how this was verified.
const MAX_FORECAST_MINUTES = 1200;

/**
 * `forecast` is optional and, when absent, is not forwarded to SL Transport at all --
 * that upstream applies its own undocumented default (empirically ~60 minutes) in that
 * case, unchanged from this route's pre-existing behaviour. Supplying it lets a caller
 * (specifically Android's direction-discovery flow during routine setup -- see
 * `LiveDeparturesDirectionOptionsSource`) request a much longer window than the live
 * display ever needs, to surface lines/directions that aren't running RIGHT NOW but will
 * be later today.
 */
function parseForecastMinutes(raw: string | undefined): number | undefined {
  if (raw == null) return undefined;
  const forecast = Number(raw);
  if (!Number.isInteger(forecast) || forecast <= 0 || forecast > MAX_FORECAST_MINUTES) {
    throw new AppError(
      "VALIDATION_ERROR",
      `Query parameter 'forecast' must be a positive integer no greater than ${MAX_FORECAST_MINUTES}`,
    );
  }
  return forecast;
}

export function createDeparturesRoute(client: SlTransportClient) {
  const route = new Hono();

  route.get("/", async (c) => {
    const siteIdRaw = c.req.query("siteId");
    if (siteIdRaw == null) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'siteId' is required");
    }
    const siteId = Number(siteIdRaw);
    if (!Number.isInteger(siteId) || siteId <= 0) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'siteId' must be a positive integer");
    }

    const forecastMinutes = parseForecastMinutes(c.req.query("forecast") ?? undefined);

    const raw = await client.fetchDepartures(siteId, forecastMinutes);

    // fetchedAt is captured only now — immediately after the upstream response body has
    // been fully received — never before the request was sent. This is what feeds the
    // DST-disambiguation input in stockholmTime.ts and is the value returned to the
    // client for every response, fresh (see docs/api-contract.md, "fetchedAt
    // semantics"). Departures are never cached at this layer, so there is no cached
    // path here to preserve an older fetchedAt for.
    const fetchedAt = new Date();

    let normalized;
    try {
      normalized = normalizeDeparturesResponse(siteId, raw, fetchedAt);
    } catch (err) {
      if (isInvalidStockholmTimestampError(err)) {
        throw new AppError(
          "UPSTREAM_ERROR",
          "SL Transport returned a departure with an invalid or nonexistent local timestamp",
          { cause: err },
        );
      }
      throw err;
    }

    // See docs/api-contract.md, "Caching": departures are near-real-time, so this is a
    // short edge cache, not a substitute for the client polling sensibly.
    c.header("Cache-Control", "public, s-maxage=30, stale-while-revalidate=30");
    return c.json(successEnvelope(normalized));
  });

  return route;
}
