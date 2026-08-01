import { describe, expect, it, vi } from "vitest";
import { createSlTransportClient } from "../src/services/slTransportClient.js";

const BASE_URL = "https://transport.integration.sl.se/v1";

function stubFetchOnce(body: unknown) {
  const fakeResponse = {
    status: 200,
    ok: true,
    headers: new Headers(),
    json: async () => body,
  } as unknown as Response;
  const fetchMock = vi.fn().mockResolvedValue(fakeResponse);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/**
 * Regression coverage for the real (non-fake) client's URL construction: routes.test.ts and
 * fetchedAt.test.ts only ever exercise a hand-rolled fake SlTransportClient, which would stay
 * green even if createSlTransportClient itself never appended `forecast` to the real upstream
 * URL at all.
 */
describe("createSlTransportClient — fetchDepartures URL construction", () => {
  it("appends ?forecast=1200 to the upstream URL when a forecast value is passed", async () => {
    const fetchMock = stubFetchOnce({ departures: [] });
    try {
      const client = createSlTransportClient(BASE_URL);
      await client.fetchDepartures(9192, 1200);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const requestedUrl = fetchMock.mock.calls[0]?.[0] as string;
      expect(requestedUrl).toBe(`${BASE_URL}/sites/9192/departures?forecast=1200`);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("does not append a forecast query parameter when none is passed", async () => {
    const fetchMock = stubFetchOnce({ departures: [] });
    try {
      const client = createSlTransportClient(BASE_URL);
      await client.fetchDepartures(9192);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const requestedUrl = fetchMock.mock.calls[0]?.[0] as string;
      expect(requestedUrl).toBe(`${BASE_URL}/sites/9192/departures`);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
