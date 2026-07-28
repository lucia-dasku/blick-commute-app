import { z } from "zod";

export const SiteSchema = z.object({
  siteId: z.number().int(),
  name: z.string(),
  note: z.string().nullable(),
  // Some SL Transport sites have no coordinates at all — see the matching comment on
  // RawSlSiteSchema in upstreamTypes.ts. Normalized to explicit `null` (never `undefined`)
  // by normalizeSite.ts, consistent with how `note` is already handled.
  lat: z.number().nullable(),
  lon: z.number().nullable(),
  stopAreaIds: z.array(z.number().int()),
});
export type Site = z.infer<typeof SiteSchema>;

export const StopSearchResponseSchema = z.object({
  query: z.string(),
  sites: z.array(SiteSchema),
});
export type StopSearchResponse = z.infer<typeof StopSearchResponseSchema>;
