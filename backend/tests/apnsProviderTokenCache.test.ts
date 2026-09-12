import { generateKeyPairSync } from "node:crypto";
import { inspect } from "node:util";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createEs256ApnsProviderTokenSigner } from "../src/liveCommute/apple/apnsProviderToken.js";
import {
  APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS,
  APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS,
  BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS,
  ApnsProviderTokenCacheError,
  LazyApnsProviderTokenCache,
} from "../src/liveCommute/apple/apnsProviderTokenCache.js";

const TEAM_ID = "TEAM123456";
const KEY_ID = "KEY1234567";
const INITIAL_SECONDS = 1_789_207_200;

afterEach(() => {
  vi.restoreAllMocks();
});

function testCache(refreshAfterSeconds?: number) {
  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  let now = new Date(INITIAL_SECONDS * 1_000);
  const signer = createEs256ApnsProviderTokenSigner({
    teamId: TEAM_ID,
    keyId: KEY_ID,
    privateKey,
  });
  const cache = new LazyApnsProviderTokenCache(signer, {
    now: () => new Date(now),
    refreshAfterSeconds,
  });
  return {
    cache,
    setNowSeconds(value: number) {
      now = new Date(value * 1_000);
    },
  };
}

describe("lazy APNs provider-token cache", () => {
  it("generates once on demand and reuses the exact lease inside Blick's 50-minute window", () => {
    const timeoutSpy = vi.spyOn(globalThis, "setTimeout");
    const intervalSpy = vi.spyOn(globalThis, "setInterval");
    const fixture = testCache();

    expect(BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS).toBe(50 * 60);
    expect(fixture.cache.peek()).toBeUndefined();
    expect(timeoutSpy).not.toHaveBeenCalled();
    expect(intervalSpy).not.toHaveBeenCalled();

    const first = fixture.cache.getToken();
    fixture.setNowSeconds(INITIAL_SECONDS + BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS - 1);
    const reused = fixture.cache.getToken();

    expect(reused).toBe(first);
    expect(first).toMatchObject({ generation: 1, issuedAt: INITIAL_SECONDS });
    expect(first.token.issuedAt).toBe(INITIAL_SECONDS);
    expect(timeoutSpy).not.toHaveBeenCalled();
    expect(intervalSpy).not.toHaveBeenCalled();
  });

  it("lazily refreshes at the configured boundary and never uses a token for one hour", () => {
    const fixture = testCache();
    const first = fixture.cache.getToken();

    fixture.setNowSeconds(INITIAL_SECONDS + BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS);
    const refreshed = fixture.cache.getToken();
    fixture.setNowSeconds(
      INITIAL_SECONDS +
        BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS +
        APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS -
        1,
    );

    expect(refreshed).not.toBe(first);
    expect(refreshed).toMatchObject({
      generation: 2,
      issuedAt: INITIAL_SECONDS + BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS,
    });
    expect(fixture.cache.getToken()).not.toBe(refreshed);
  });

  it("accepts only whole refresh intervals inside Apple's documented safe range", () => {
    expect(APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS).toBe(20 * 60);
    expect(APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS).toBe(60 * 60);

    expect(() => testCache(APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS)).not.toThrow();
    expect(() => testCache(APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS - 1)).not.toThrow();
    for (const invalid of [
      APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS - 1,
      APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS,
      1_200.5,
      Number.NaN,
    ]) {
      expect(() => testCache(invalid)).toThrow(ApnsProviderTokenCacheError);
    }
  });

  it("retains the last issuance boundary after invalidation instead of regenerating too often", () => {
    const fixture = testCache();
    const first = fixture.cache.getToken();
    fixture.setNowSeconds(INITIAL_SECONDS + 10 * 60);

    fixture.cache.invalidate();
    const failure = (() => {
      try {
        fixture.cache.getToken();
      } catch (error) {
        return error;
      }
      throw new Error("expected cache refresh to be refused");
    })();

    expect(failure).toBeInstanceOf(ApnsProviderTokenCacheError);
    expect(failure).toMatchObject({
      code: "APNS_PROVIDER_TOKEN_REFRESH_TOO_SOON",
      retryNotBeforeEpochSeconds:
        first.issuedAt + APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS,
    });

    fixture.setNowSeconds(
      INITIAL_SECONDS + APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS,
    );
    expect(fixture.cache.getToken()).toMatchObject({ generation: 2 });
  });

  it("conditionally invalidates only the provider-token lease used by that response", () => {
    const fixture = testCache();
    const first = fixture.cache.getToken();
    fixture.setNowSeconds(INITIAL_SECONDS + BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS);
    const second = fixture.cache.getToken();

    expect(fixture.cache.invalidateIfCurrent(first)).toBe(false);
    expect(fixture.cache.peek()).toBe(second);
    expect(fixture.cache.invalidateIfCurrent(second)).toBe(true);
    expect(fixture.cache.peek()).toBeUndefined();
  });

  it("fails closed on clock rollback and keeps signing material out of diagnostics", () => {
    const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const privatePem = privateKey.export({ format: "pem", type: "pkcs8" }).toString();
    let now = new Date(INITIAL_SECONDS * 1_000);
    const cache = new LazyApnsProviderTokenCache(
      createEs256ApnsProviderTokenSigner({
        teamId: TEAM_ID,
        keyId: KEY_ID,
        privateKey: privatePem,
      }),
      { now: () => new Date(now) },
    );
    const lease = cache.getToken();
    now = new Date((INITIAL_SECONDS - 1) * 1_000);

    const failure = (() => {
      try {
        cache.getToken();
      } catch (error) {
        return error;
      }
      throw new Error("expected clock rollback to fail");
    })();

    for (const rendered of [inspect(cache), JSON.stringify(cache), inspect(failure)]) {
      expect(rendered).not.toContain(privatePem);
      expect(rendered).not.toContain(lease.token.revealForAuthorization());
    }
    expect(failure).toMatchObject({ code: "APNS_PROVIDER_TOKEN_CLOCK_INVALID" });
  });
});
