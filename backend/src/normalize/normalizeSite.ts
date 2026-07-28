import type { RawSlSite } from "../services/upstreamTypes.js";
import type { Site } from "../models/site.js";

export function normalizeSite(raw: RawSlSite): Site {
  return {
    siteId: raw.id,
    name: raw.name,
    note: raw.note ?? null,
    lat: raw.lat ?? null,
    lon: raw.lon ?? null,
    stopAreaIds: raw.stop_areas,
  };
}
