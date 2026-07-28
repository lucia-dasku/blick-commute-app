import { describe, expect, it, vi } from "vitest";
import { InFlightDeduper, InMemoryCache } from "../src/lib/cache.js";

describe("InMemoryCache", () => {
  it("returns undefined for a missing key", async () => {
    const cache = new InMemoryCache();
    expect(await cache.get("missing")).toBeUndefined();
  });

  it("returns a value that has not yet expired", async () => {
    const cache = new InMemoryCache();
    await cache.set("k", { hello: "world" }, 60);
    expect(await cache.get("k")).toEqual({ hello: "world" });
  });

  it("expires a value after its TTL elapses", async () => {
    vi.useFakeTimers();
    try {
      const cache = new InMemoryCache();
      await cache.set("k", "v", 1); // 1 second TTL
      expect(await cache.get("k")).toBe("v");
      vi.advanceTimersByTime(1500);
      expect(await cache.get("k")).toBeUndefined();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("InFlightDeduper", () => {
  it("coalesces concurrent calls for the same key into a single factory invocation", async () => {
    const deduper = new InFlightDeduper();
    let callCount = 0;
    const factory = () =>
      new Promise<number>((resolve) => {
        callCount += 1;
        setTimeout(() => resolve(42), 10);
      });

    const [a, b, c] = await Promise.all([
      deduper.run("site-snapshot", factory),
      deduper.run("site-snapshot", factory),
      deduper.run("site-snapshot", factory),
    ]);

    expect(callCount).toBe(1);
    expect([a, b, c]).toEqual([42, 42, 42]);
  });

  it("runs the factory again for a later, non-overlapping call with the same key", async () => {
    const deduper = new InFlightDeduper();
    let callCount = 0;
    const factory = async () => {
      callCount += 1;
      return callCount;
    };

    const first = await deduper.run("k", factory);
    const second = await deduper.run("k", factory);

    expect(first).toBe(1);
    expect(second).toBe(2);
  });

  it("does not coalesce calls with different keys", async () => {
    const deduper = new InFlightDeduper();
    let callCount = 0;
    const factory = () =>
      new Promise<number>((resolve) => {
        callCount += 1;
        setTimeout(() => resolve(callCount), 5);
      });

    await Promise.all([deduper.run("a", factory), deduper.run("b", factory)]);
    expect(callCount).toBe(2);
  });
});
