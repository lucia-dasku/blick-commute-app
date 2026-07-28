import { describe, expect, it } from "vitest";
import {
  RawDeparturesResponseSchema,
  RawDeviationListSchema,
  RawSlSiteListSchema,
} from "../src/services/upstreamTypes.js";
import { RawDeviationSchema } from "../src/services/upstreamTypes.js";
import departuresFixture from "../fixtures/slTransportDeparturesSlussen.sample.json" with { type: "json" };
import deviationsFixture from "../fixtures/slDeviationsSlussen.sample.json" with { type: "json" };
import sitesFixture from "../fixtures/slSites.sample.json" with { type: "json" };

describe("RawSlSiteListSchema", () => {
  it("accepts the real sites fixture", () => {
    expect(RawSlSiteListSchema.safeParse(sitesFixture).success).toBe(true);
  });

  it("rejects a site missing a required field", () => {
    const broken = (sitesFixture as unknown[]).map((s) => ({ ...(s as object) }));
    delete (broken[0] as Record<string, unknown>).name;
    expect(RawSlSiteListSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a site with a wrong-typed field", () => {
    const broken = JSON.parse(JSON.stringify(sitesFixture));
    broken[0].id = "not-a-number";
    expect(RawSlSiteListSchema.safeParse(broken).success).toBe(false);
  });

  it("allows unrelated additional fields without rejecting the payload", () => {
    const withExtra = JSON.parse(JSON.stringify(sitesFixture));
    withExtra[0].someBrandNewUpstreamField = { anything: true };
    const result = RawSlSiteListSchema.safeParse(withExtra);
    expect(result.success).toBe(true);
  });

  // Regression test for a 2026-07-28 production incident: some real (non-fixture) SL
  // Transport sites are returned with `lat`/`lon` entirely missing, and since these were
  // required fields, that single non-conforming site failed the ENTIRE `z.array(...)`
  // parse — taking down all stop search, not just that one site.
  it("accepts a site missing lat/lon entirely (some real sites have no coordinates)", () => {
    const withMissingCoords = JSON.parse(JSON.stringify(sitesFixture));
    delete withMissingCoords[0].lat;
    delete withMissingCoords[0].lon;
    expect(RawSlSiteListSchema.safeParse(withMissingCoords).success).toBe(true);
  });

  it("accepts a site with lat/lon explicitly null", () => {
    const withNullCoords = JSON.parse(JSON.stringify(sitesFixture));
    withNullCoords[0].lat = null;
    withNullCoords[0].lon = null;
    expect(RawSlSiteListSchema.safeParse(withNullCoords).success).toBe(true);
  });
});

describe("RawDeparturesResponseSchema", () => {
  it("accepts the real departures fixture", () => {
    expect(RawDeparturesResponseSchema.safeParse(departuresFixture).success).toBe(true);
  });

  it("rejects a departure missing a required field (line)", () => {
    const broken = JSON.parse(JSON.stringify(departuresFixture));
    delete broken.departures[0].line;
    expect(RawDeparturesResponseSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a departure with a wrong-typed field (scheduled as a number)", () => {
    const broken = JSON.parse(JSON.stringify(departuresFixture));
    broken.departures[0].scheduled = 12345;
    expect(RawDeparturesResponseSchema.safeParse(broken).success).toBe(false);
  });

  it("allows unrelated additional fields on a departure without rejecting the payload", () => {
    const withExtra = JSON.parse(JSON.stringify(departuresFixture));
    withExtra.departures[0].totallyNewField = "surprise";
    withExtra.someTopLevelFieldWeDontKnowAboutYet = 42;
    expect(RawDeparturesResponseSchema.safeParse(withExtra).success).toBe(true);
  });

  it("treats malformed JSON as invalid (parse failure before schema validation)", () => {
    const malformed = "{ this is not valid json ";
    expect(() => JSON.parse(malformed)).toThrow();
  });
});

describe("RawDeviationListSchema", () => {
  it("accepts the real deviations fixture", () => {
    expect(RawDeviationListSchema.safeParse(deviationsFixture).success).toBe(true);
  });

  it("rejects a deviation missing a required field (message_variants)", () => {
    const broken = JSON.parse(JSON.stringify(deviationsFixture));
    delete broken[0].message_variants;
    expect(RawDeviationListSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a deviation with a wrong-typed field (priority as a string)", () => {
    const broken = JSON.parse(JSON.stringify(deviationsFixture));
    broken[0].priority = "high";
    expect(RawDeviationListSchema.safeParse(broken).success).toBe(false);
  });

  it("allows unrelated additional fields without rejecting the payload", () => {
    const withExtra = JSON.parse(JSON.stringify(deviationsFixture));
    withExtra[0].scope.someNewScopeField = ["x"];
    expect(RawDeviationListSchema.safeParse(withExtra).success).toBe(true);
  });
});

describe("RawDeviationSchema — contract integer fields", () => {
  it("rejects a non-integer id field (e.g. version as a fractional number)", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.version = 1.5;
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects an unsafe-integer id field (beyond Number.MAX_SAFE_INTEGER)", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.deviation_case_id = Number.MAX_SAFE_INTEGER + 10;
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a non-integer priority level", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.priority.importance_level = 2.2;
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("accepts safe-integer id fields (the real fixture)", () => {
    const valid = (deviationsFixture as unknown[])[0];
    expect(RawDeviationSchema.safeParse(valid).success).toBe(true);
  });
});

describe("RawDeviationSchema — explicit-offset timestamps", () => {
  it("rejects a naive (offset-less) created timestamp", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.created = "2026-07-22T20:10:49";
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a naive (offset-less) modified timestamp", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.modified = "2026-07-22T20:10:49";
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a naive (offset-less) publish.from timestamp", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.publish.from = "2026-07-31T10:30:00.000";
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("rejects a naive (offset-less) publish.upto timestamp", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.publish.upto = "2026-08-05T19:30:00.000";
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("accepts a Z-suffixed UTC timestamp as a valid explicit offset", () => {
    const withZ = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    withZ.created = "2026-07-22T18:10:49.01Z";
    expect(RawDeviationSchema.safeParse(withZ).success).toBe(true);
  });

  it("accepts the real fixture's +02:00-offset timestamps", () => {
    const valid = (deviationsFixture as unknown[])[0];
    expect(RawDeviationSchema.safeParse(valid).success).toBe(true);
  });

  it("allows modified/publish.from/publish.upto to be null", () => {
    const withNulls = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    withNulls.modified = null;
    withNulls.publish.from = null;
    withNulls.publish.upto = null;
    expect(RawDeviationSchema.safeParse(withNulls).success).toBe(true);
  });
});

describe("RawDeviationSchema — message_variants must be non-empty", () => {
  it("rejects an empty message_variants array", () => {
    const broken = JSON.parse(JSON.stringify((deviationsFixture as unknown[])[0]));
    broken.message_variants = [];
    expect(RawDeviationSchema.safeParse(broken).success).toBe(false);
  });

  it("accepts a single-item message_variants array (the real fixture)", () => {
    const valid = (deviationsFixture as unknown[])[0];
    expect(RawDeviationSchema.safeParse(valid).success).toBe(true);
  });
});
