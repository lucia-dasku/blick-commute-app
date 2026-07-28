import { describe, expect, it } from "vitest";
import { readPort, readUpstreamTimeoutMs } from "../src/config/env.js";

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
