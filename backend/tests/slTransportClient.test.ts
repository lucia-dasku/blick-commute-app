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

/** `fetchStopPoints` reads the body via `response.text()`, not `.json()` (see
 * `fetchUpstreamJsonLossless`'s own doc) — a stub built for the ordinary JSON path above would
 * silently short-circuit real parsing and could never catch a precision-loss regression. */
function stubFetchOnceWithText(rawText: string) {
  const fakeResponse = {
    status: 200,
    ok: true,
    headers: new Headers(),
    text: async () => rawText,
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

describe("createSlTransportClient — fetchStopPoints", () => {
  it("requests GET /stop-points", async () => {
    const fetchMock = stubFetchOnceWithText("[]");
    try {
      const client = createSlTransportClient(BASE_URL);
      await client.fetchStopPoints();

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const requestedUrl = fetchMock.mock.calls[0]?.[0] as string;
      expect(requestedUrl).toBe(`${BASE_URL}/stop-points`);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("preserves a real >MAX_SAFE_INTEGER pattern_point_gid as an exact digit string, never a rounded number", async () => {
    // Real shape confirmed live against the production upstream (Stavsnäs pier, stop point 101).
    const rawText = `[{
      "id": 101,
      "gid": 9022001000101001,
      "pattern_point_gid": 9025001000000101,
      "name": "Stavsnäs",
      "type": "PIER",
      "stop_area": {"id": 101, "name": "Stavsnäs", "type": "SHIPBER"}
    }]`;
    stubFetchOnceWithText(rawText);
    try {
      const client = createSlTransportClient(BASE_URL);
      const stopPoints = await client.fetchStopPoints();

      expect(stopPoints).toHaveLength(1);
      expect(stopPoints[0]!.pattern_point_gid).toBe("9025001000000101");
      expect(stopPoints[0]!.gid).toBe("9022001000101001");
      expect(stopPoints[0]!.id).toBe(101);
      expect(stopPoints[0]!.stop_area).toEqual({ id: 101, name: "Stavsnäs", type: "SHIPBER" });
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("rejects a malformed stop-points payload the same way other upstream schema failures are rejected", async () => {
    stubFetchOnceWithText(`[{"id": 101}]`); // missing required fields
    try {
      const client = createSlTransportClient(BASE_URL);
      await expect(client.fetchStopPoints()).rejects.toMatchObject({ code: "UPSTREAM_ERROR" });
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
