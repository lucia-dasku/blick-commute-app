import { describe, expect, it } from "vitest";
import { readPort, readUpstreamTimeoutMs, readRedisConfig } from "../src/config/env.js";

describe("readPort", () => {
  it("defaults to 8787 when unset", () => {
    expect(readPort(undefined)).toBe(8787);
  });

  it("accepts a valid port", () => {
    expect(readPort("3000")).toBe(3000);
  });

  it("accepts the minimum valid port", () => {
    expect(readPort("1")).toBe(1);
  });

  it("accepts the maximum valid port", () => {
    expect(readPort("65535")).toBe(65535);
  });

  it("rejects 0", () => {
    expect(() => readPort("0")).toThrow(/Invalid PORT/);
  });

  it("rejects a value above 65535", () => {
    expect(() => readPort("65536")).toThrow(/Invalid PORT/);
  });

  it("rejects a negative value", () => {
    expect(() => readPort("-1")).toThrow(/Invalid PORT/);
  });

  it("rejects a non-numeric value", () => {
    expect(() => readPort("not-a-port")).toThrow(/Invalid PORT/);
  });

  it("rejects a fractional value", () => {
    expect(() => readPort("3000.5")).toThrow(/Invalid PORT/);
  });

  it("rejects an empty string", () => {
    expect(() => readPort("")).toThrow(/Invalid PORT/);
  });
});

describe("readUpstreamTimeoutMs", () => {
  it("defaults to 10000 when unset", () => {
    expect(readUpstreamTimeoutMs(undefined)).toBe(10_000);
  });

  it("accepts a valid positive integer", () => {
    expect(readUpstreamTimeoutMs("5000")).toBe(5000);
  });

  it("rejects zero", () => {
    expect(() => readUpstreamTimeoutMs("0")).toThrow(/Invalid UPSTREAM_TIMEOUT_MS/);
  });

  it("rejects a negative value", () => {
    expect(() => readUpstreamTimeoutMs("-500")).toThrow(/Invalid UPSTREAM_TIMEOUT_MS/);
  });

  it("rejects a non-numeric value", () => {
    expect(() => readUpstreamTimeoutMs("soon")).toThrow(/Invalid UPSTREAM_TIMEOUT_MS/);
  });

  it("rejects a fractional value", () => {
    expect(() => readUpstreamTimeoutMs("1000.5")).toThrow(/Invalid UPSTREAM_TIMEOUT_MS/);
  });

  it("rejects Infinity", () => {
    expect(() => readUpstreamTimeoutMs("Infinity")).toThrow(/Invalid UPSTREAM_TIMEOUT_MS/);
  });
});

describe("readRedisConfig", () => {
  it("returns undefined outside production when both variables are unset", () => {
    expect(readRedisConfig(undefined, undefined, "development")).toBeUndefined();
    expect(readRedisConfig(undefined, undefined, "test")).toBeUndefined();
  });

  it("throws in production when both variables are unset", () => {
    expect(() => readRedisConfig(undefined, undefined, "production")).toThrow(
      /UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN are required in production/,
    );
  });

  it("returns the parsed config when both variables are valid, in any environment", () => {
    const result = readRedisConfig("https://example.upstash.io", "secret-token", "production");
    expect(result).toEqual({ url: "https://example.upstash.io", token: "secret-token" });
  });

  it("returns the parsed config outside production too", () => {
    const result = readRedisConfig("https://example.upstash.io", "secret-token", "development");
    expect(result).toEqual({ url: "https://example.upstash.io", token: "secret-token" });
  });

  it("throws when only the URL is set (partial configuration)", () => {
    expect(() => readRedisConfig("https://example.upstash.io", undefined, "development")).toThrow(
      /must both be set, or both left unset/,
    );
  });

  it("throws when only the token is set (partial configuration)", () => {
    expect(() => readRedisConfig(undefined, "secret-token", "development")).toThrow(
      /must both be set, or both left unset/,
    );
  });

  it("throws when the URL is not a valid URL", () => {
    expect(() => readRedisConfig("not-a-url", "secret-token", "development")).toThrow(
      /Invalid UPSTASH_REDIS_REST_URL/,
    );
  });

  it("treats an empty or whitespace-only value the same as unset", () => {
    expect(readRedisConfig("", "", "development")).toBeUndefined();
    expect(readRedisConfig("   ", "   ", "development")).toBeUndefined();
  });

  it("treats an empty URL alongside a real token as a partial configuration", () => {
    expect(() => readRedisConfig("", "secret-token", "development")).toThrow(
      /must both be set, or both left unset/,
    );
  });
});
