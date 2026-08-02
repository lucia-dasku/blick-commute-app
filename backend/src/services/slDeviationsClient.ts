import { config } from "../config/env.js";
import { fetchUpstreamJson } from "../lib/upstreamFetch.js";
import { RawDeviationListSchema, type RawDeviation } from "./upstreamTypes.js";

export interface SlDeviationsClient {
  /**
   * Fetches the ENTIRE SL network's currently-published deviations, plus every
   * future-published one (`future=true`), in ONE call — no `site`/`line`/
   * `transport_mode` filter is ever forwarded upstream. Every `/api/v1/disruptions`
   * request's own filters are applied locally instead, against this single shared
   * snapshot (see src/services/deviationsSnapshotService.ts and
   * src/services/deviationsFilter.ts) — this is what makes SL's fair-use "at most one
   * request per minute" guidance enforceable in aggregate across every distinct
   * site/line/mode/future combination, not just repeat requests for the SAME
   * combination the way the previous per-query design did (see docs/api-contract.md,
   * "Caching and fair use").
   *
   * `future=true` is used here specifically so the one snapshot includes not-yet-started
   * deviations too — individual requests filter `future` back down locally
   * (`deviationsFilter.ts`), never by re-fetching per query.
   */
  fetchAllDeviations(): Promise<RawDeviation[]>;
}

const UPSTREAM_NAME = "SL Deviations";

export function createSlDeviationsClient(baseUrl: string = config.slDeviationsBaseUrl): SlDeviationsClient {
  return {
    async fetchAllDeviations() {
      return fetchUpstreamJson(`${baseUrl}/messages?future=true`, RawDeviationListSchema, {
        upstreamName: UPSTREAM_NAME,
      });
    },
  };
}
