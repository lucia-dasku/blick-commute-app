import { config } from "../config/env.js";
import { fetchUpstreamJson, fetchUpstreamJsonLossless } from "../lib/upstreamFetch.js";
import {
  RawDeparturesResponseSchema,
  RawSlSiteListSchema,
  RawStopPointListSchema,
  type RawDeparturesResponse,
  type RawSlSite,
  type RawStopPoint,
} from "./upstreamTypes.js";

export interface SlTransportClient {
  fetchAllSites(): Promise<RawSlSite[]>;
  fetchDepartures(siteId: number, forecastMinutes?: number): Promise<RawDeparturesResponse>;
  /** `GET /v1/stop-points` — the ONLY caller of `fetchUpstreamJsonLossless` (see that
   * function's own doc): the real payload's `gid`/`pattern_point_gid` fields routinely exceed
   * `Number.MAX_SAFE_INTEGER`, so this is read losslessly rather than through the ordinary
   * `fetchAllSites`/`fetchDepartures` path above. Used only by `StopPointDirectory`
   * (`services/stopPointDirectory.ts`) — never called from a request's own critical path (see
   * that service's own doc). */
  fetchStopPoints(): Promise<RawStopPoint[]>;
}

const UPSTREAM_NAME = "SL Transport";

export function createSlTransportClient(baseUrl: string = config.slTransportBaseUrl): SlTransportClient {
  return {
    async fetchAllSites() {
      return fetchUpstreamJson(`${baseUrl}/sites?expand=true`, RawSlSiteListSchema, {
        upstreamName: UPSTREAM_NAME,
      });
    },
    async fetchDepartures(siteId: number, forecastMinutes?: number) {
      const url = `${baseUrl}/sites/${siteId}/departures` + (forecastMinutes != null ? `?forecast=${forecastMinutes}` : "");
      return fetchUpstreamJson(url, RawDeparturesResponseSchema, {
        upstreamName: UPSTREAM_NAME,
      });
    },
    async fetchStopPoints() {
      return fetchUpstreamJsonLossless(`${baseUrl}/stop-points`, RawStopPointListSchema, {
        upstreamName: UPSTREAM_NAME,
      });
    },
  };
}
