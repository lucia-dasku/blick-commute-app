import type { SlTransportClient } from "./slTransportClient.js";
import type { Cache, InFlightDeduper } from "../lib/cache.js";
import { normalizeSite } from "../normalize/normalizeSite.js";
import type { Site } from "../models/site.js";
import { normalizeSearchQuery, rankSites } from "../lib/search.js";

const SITE_SNAPSHOT_CACHE_KEY = "sl-transport:sites:v1";

// Upstream site data "is updated when changes are made, at most once per day" per
// SL Transport's own documentation, so a long TTL is appropriate here. See
// docs/api-contract.md, "Caching" for why this is still described as best-effort only.
const SITE_SNAPSHOT_TTL_SECONDS = 60 * 60 * 24;

export interface SiteDirectory {
  search(rawQuery: string): Promise<Site[]>;
  getAllSites(): Promise<Site[]>;
}

export function createSiteDirectory(
  client: SlTransportClient,
  cache: Cache,
  deduper: InFlightDeduper,
): SiteDirectory {
  async function getAllSites(): Promise<Site[]> {
    const cached = await cache.get<Site[]>(SITE_SNAPSHOT_CACHE_KEY);
    if (cached) return cached;

    // Deduplicated so N simultaneous cold requests in one instance trigger exactly one
    // upstream fetch instead of N.
    return deduper.run(SITE_SNAPSHOT_CACHE_KEY, async () => {
      const cachedAgain = await cache.get<Site[]>(SITE_SNAPSHOT_CACHE_KEY);
      if (cachedAgain) return cachedAgain;

      const rawSites = await client.fetchAllSites();
      const sites = rawSites.map(normalizeSite);
      await cache.set(SITE_SNAPSHOT_CACHE_KEY, sites, SITE_SNAPSHOT_TTL_SECONDS);
      return sites;
    });
  }

  return {
    getAllSites,
    async search(rawQuery: string) {
      // Validate the query BEFORE loading the site directory. An invalid or excessively
      // long query must cause zero upstream requests — `normalizeSearchQuery` throws
      // `InvalidSearchQueryError` synchronously, so this short-circuits before
      // `getAllSites()` (and therefore before any possible upstream fetch on a cache
      // miss) ever runs. `rankSites` re-validates internally too, but only after the
      // (by then unavoidable) directory load, which is too late for this guarantee.
      normalizeSearchQuery(rawQuery);
      const sites = await getAllSites();
      return rankSites(sites, rawQuery);
    },
  };
}
