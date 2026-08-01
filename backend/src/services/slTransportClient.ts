import { config } from "../config/env.js";
import { fetchUpstreamJson } from "../lib/upstreamFetch.js";
import {
  RawDeparturesResponseSchema,
  RawSlSiteListSchema,
  type RawDeparturesResponse,
  type RawSlSite,
} from "./upstreamTypes.js";

export interface SlTransportClient {
  fetchAllSites(): Promise<RawSlSite[]>;
  fetchDepartures(siteId: number, forecastMinutes?: number): Promise<RawDeparturesResponse>;
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
  };
}
