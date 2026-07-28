import { describe, expect, it, vi } from "vitest";
import { createSiteDirectory } from "../src/services/siteDirectory.js";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";
import { InvalidSearchQueryError } from "../src/lib/search.js";
import type { SlTransportClient } from "../src/services/slTransportClient.js";
import type { RawSlSite } from "../src/services/upstreamTypes.js";
import sitesFixture from "../fixtures/slSites.sample.json" with { type: "json" };

/**
 * `SiteDirectory.search` must validate the query BEFORE ever loading the site
 * directory (see src/services/siteDirectory.ts). An invalid or excessively long query
 * must cause zero upstream requests — not "validate late, after an avoidable fetch
 * already happened".
 */
function buildDirectory() {
  const fetchAllSites = vi.fn(async (): Promise<RawSlSite[]> => sitesFixture as unknown as RawSlSite[]);
  const fakeClient: SlTransportClient = {
    fetchAllSites,
    async fetchDepartures() {
      throw new Error("not used in this test");
    },
  };
  const siteDirectory = createSiteDirectory(fakeClient, new InMemoryCache(), new InFlightDeduper());
  return { siteDirectory, fetchAllSites };
}

describe("SiteDirectory.search — validates before loading the directory", () => {
  it("rejects an empty query without calling fetchAllSites", async () => {
    const { siteDirectory, fetchAllSites } = buildDirectory();
    await expect(siteDirectory.search("   ")).rejects.toThrow(InvalidSearchQueryError);
    expect(fetchAllSites).not.toHaveBeenCalled();
  });

  it("rejects an excessively long query without calling fetchAllSites", async () => {
    const { siteDirectory, fetchAllSites } = buildDirectory();
    await expect(siteDirectory.search("a".repeat(65))).rejects.toThrow(InvalidSearchQueryError);
    expect(fetchAllSites).not.toHaveBeenCalled();
  });

  it("does call fetchAllSites for a valid query", async () => {
    const { siteDirectory, fetchAllSites } = buildDirectory();
    await siteDirectory.search("Slussen");
    expect(fetchAllSites).toHaveBeenCalledTimes(1);
  });
});
