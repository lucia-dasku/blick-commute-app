import { describe, expect, it } from "vitest";
import { createJourneyEndpointSiteResolver } from "../src/services/journeyEndpointSiteResolver.js";
import { InMemoryCache } from "../src/lib/cache.js";
import type { SlJourneyPlannerClient } from "../src/services/slJourneyPlannerClient.js";
import type { SiteDirectory } from "../src/services/siteDirectory.js";
import type { Site } from "../src/models/site.js";

function site(siteId: number, name: string): Site {
  return { siteId, name, note: null, lat: null, lon: null, stopAreaIds: [] };
}

function fakeSiteDirectory(sites: Site[]): SiteDirectory {
  return {
    async search() {
      throw new Error("not used in this test");
    },
    async getAllSites() {
      return sites;
    },
  };
}

function fakeJourneyPlannerClient(locations: Array<{ id: string; name: string; properties?: { stopId?: string } }>): Pick<SlJourneyPlannerClient, "searchStops"> {
  return {
    async searchStops() {
      return locations as never;
    },
  };
}

describe("createJourneyEndpointSiteResolver", () => {
  it("resolves a real live-verified case: Akalla's location id -> site id 9300", async () => {
    const resolver = createJourneyEndpointSiteResolver(
      fakeJourneyPlannerClient([{ id: "9091001000009300", name: "Stockholm, Akalla", properties: { stopId: "18009300" } }]),
      fakeSiteDirectory([site(9300, "Akalla")]),
      new InMemoryCache(),
    );
    expect(await resolver.resolveSiteId("9091001000009300")).toBe(9300);
  });

  it("a derived id that does not correspond to any real known site resolves to null (the real Vaxholm/ferry case)", async () => {
    const resolver = createJourneyEndpointSiteResolver(
      fakeJourneyPlannerClient([{ id: "9091001001002800", name: "Vaxholm, Vaxholm", properties: { stopId: "18002858" } }]),
      fakeSiteDirectory([site(9300, "Akalla")]), // 2858 is not among the known sites
      new InMemoryCache(),
    );
    expect(await resolver.resolveSiteId("9091001001002800")).toBeNull();
  });

  it("bug repro (item D): the requested id is absent from the search response, but OTHER locations are present -- must be null, never the first other result", async () => {
    // Construct: requested id = X, but the stop-finder response contains only Y and Z (no X at
    // all) -- e.g. a fuzzy/nearby-name search match. Accepting Y or Z here would treat an
    // unrelated nearby place as if it were the requested id's own identity.
    const resolver = createJourneyEndpointSiteResolver(
      fakeJourneyPlannerClient([
        { id: "9091001000009192", name: "Stockholm, Slussen (Y)", properties: { stopId: "18009192" } },
        { id: "9091001000009117", name: "Stockholm, Odenplan (Z)", properties: { stopId: "18009117" } },
      ]),
      fakeSiteDirectory([site(9192, "Slussen"), site(9117, "Odenplan")]),
      new InMemoryCache(),
    );
    expect(await resolver.resolveSiteId("9091001000009999" /* X -- never present in the response above */)).toBeNull();
  });

  it("a location with no properties.stopId at all resolves to null, never guessed", async () => {
    const resolver = createJourneyEndpointSiteResolver(
      fakeJourneyPlannerClient([{ id: "some-id", name: "Somewhere" }]),
      fakeSiteDirectory([site(9300, "Akalla")]),
      new InMemoryCache(),
    );
    expect(await resolver.resolveSiteId("some-id")).toBeNull();
  });

  it("an upstream failure resolves to null, never throws", async () => {
    const throwingClient: Pick<SlJourneyPlannerClient, "searchStops"> = {
      async searchStops() {
        throw new Error("upstream down");
      },
    };
    const resolver = createJourneyEndpointSiteResolver(throwingClient, fakeSiteDirectory([]), new InMemoryCache());
    await expect(resolver.resolveSiteId("9091001000009300")).resolves.toBeNull();
  });

  it("caches a resolved id across calls -- a second lookup does not re-query Journey Planner", async () => {
    let callCount = 0;
    const client: Pick<SlJourneyPlannerClient, "searchStops"> = {
      async searchStops() {
        callCount++;
        return [{ id: "9091001000009300", name: "Akalla", properties: { stopId: "18009300" } }] as never;
      },
    };
    const resolver = createJourneyEndpointSiteResolver(client, fakeSiteDirectory([site(9300, "Akalla")]), new InMemoryCache());
    await resolver.resolveSiteId("9091001000009300");
    await resolver.resolveSiteId("9091001000009300");
    expect(callCount).toBe(1);
  });

  it("caches a null (unresolved) result too -- does not retry every time", async () => {
    let callCount = 0;
    const client: Pick<SlJourneyPlannerClient, "searchStops"> = {
      async searchStops() {
        callCount++;
        return [];
      },
    };
    const resolver = createJourneyEndpointSiteResolver(client, fakeSiteDirectory([]), new InMemoryCache());
    await resolver.resolveSiteId("unknown-id");
    await resolver.resolveSiteId("unknown-id");
    expect(callCount).toBe(1);
  });
});
