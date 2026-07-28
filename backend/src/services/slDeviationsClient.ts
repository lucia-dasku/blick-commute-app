import { config } from "../config/env.js";
import { fetchUpstreamJson } from "../lib/upstreamFetch.js";
import { RawDeviationListSchema, type RawDeviation } from "./upstreamTypes.js";

export interface DeviationsQuery {
  siteId?: number;
  lineId?: number;
  transportMode?: string;
  future?: boolean;
}

export interface SlDeviationsClient {
  fetchDeviations(query: DeviationsQuery): Promise<RawDeviation[]>;
}

const UPSTREAM_NAME = "SL Deviations";

/**
 * SL's fair-use guidance for this endpoint asks for at most one request per minute
 * (see docs/api-contract.md, "Caching and fair use"). This client does not itself
 * rate-limit — that's enforced by the route's cache TTL and in-flight deduplication —
 * but it is kept behind this interface specifically so a stricter enforcement layer can
 * be added later without touching call sites.
 */
export function createSlDeviationsClient(baseUrl: string = config.slDeviationsBaseUrl): SlDeviationsClient {
  return {
    async fetchDeviations(query) {
      const params = new URLSearchParams();
      if (query.siteId != null) params.append("site", String(query.siteId));
      if (query.lineId != null) params.append("line", String(query.lineId));
      if (query.transportMode) params.append("transport_mode", query.transportMode);
      if (query.future != null) params.append("future", String(query.future));

      return fetchUpstreamJson(`${baseUrl}/messages?${params.toString()}`, RawDeviationListSchema, {
        upstreamName: UPSTREAM_NAME,
      });
    },
  };
}
