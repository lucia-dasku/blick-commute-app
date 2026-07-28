import { Hono } from "hono";
import { successEnvelope } from "../models/common.js";
import { AppError } from "../lib/errors.js";
import { InvalidSearchQueryError } from "../lib/search.js";
import type { SiteDirectory } from "../services/siteDirectory.js";

export function createStopsRoute(siteDirectory: SiteDirectory) {
  const route = new Hono();

  route.get("/search", async (c) => {
    const query = c.req.query("query");
    if (query == null) {
      throw new AppError("VALIDATION_ERROR", "Query parameter 'query' is required");
    }

    let sites;
    try {
      sites = await siteDirectory.search(query);
    } catch (err) {
      if (err instanceof InvalidSearchQueryError) {
        throw new AppError("VALIDATION_ERROR", err.message);
      }
      throw err;
    }

    c.header("Cache-Control", "public, s-maxage=3600, stale-while-revalidate=86400");
    return c.json(successEnvelope({ query, sites }));
  });

  return route;
}
