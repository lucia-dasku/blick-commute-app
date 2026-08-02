import { describe, expect, it, vi } from "vitest";
import { InMemoryLock } from "../src/lib/distributedLock.js";

describe("InMemoryLock", () => {
  it("acquires a free lock and returns a token", async () => {
    const lock = new InMemoryLock();
    const token = await lock.acquire("k", 1000);
    expect(token).toBeDefined();
    expect(lock.isHeld("k")).toBe(true);
  });

  it("returns undefined when the lock is already held", async () => {
    const lock = new InMemoryLock();
    await lock.acquire("k", 1000);
    const second = await lock.acquire("k", 1000);
    expect(second).toBeUndefined();
  });

  it("issues a different token on each successful acquire", async () => {
    const lock = new InMemoryLock();
    const first = await lock.acquire("k", 1000);
    await lock.release("k", first!);
    const second = await lock.acquire("k", 1000);
    expect(first).not.toBe(second);
  });

  it("does not hold across different keys", async () => {
    const lock = new InMemoryLock();
    const a = await lock.acquire("a", 1000);
    const b = await lock.acquire("b", 1000);
    expect(a).toBeDefined();
    expect(b).toBeDefined();
  });

  describe("release — ownership protection", () => {
    it("releases the lock when the caller presents its own valid token", async () => {
      const lock = new InMemoryLock();
      const token = await lock.acquire("k", 1000);
      await lock.release("k", token!);
      expect(lock.isHeld("k")).toBe(false);
      // Now free again for a new acquirer.
      expect(await lock.acquire("k", 1000)).toBeDefined();
    });

    it("does NOT release the lock when presented with the wrong token", async () => {
      const lock = new InMemoryLock();
      const token = await lock.acquire("k", 1000);
      await lock.release("k", "some-other-token");
      expect(lock.isHeld("k")).toBe(true);
      expect(token).toBeDefined();
    });

    it("the classic lost-lock scenario: a late release from an expired holder must not delete a new holder's lock", async () => {
      vi.useFakeTimers();
      try {
        const lock = new InMemoryLock();
        const staleToken = await lock.acquire("k", 1000);

        // The lock expires...
        vi.advanceTimersByTime(1001);
        expect(lock.isHeld("k")).toBe(false);

        // ...and a completely different holder legitimately acquires it.
        const newToken = await lock.acquire("k", 1000);
        expect(newToken).toBeDefined();

        // The FIRST holder's release call arrives late (e.g. its own fetch was still
        // finishing up). It must not delete the second holder's still-valid lock.
        await lock.release("k", staleToken!);
        expect(lock.isHeld("k")).toBe(true);

        // The rightful (second) holder can still release its own lock correctly.
        await lock.release("k", newToken!);
        expect(lock.isHeld("k")).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });

    it("releasing an already-released lock is a harmless no-op", async () => {
      const lock = new InMemoryLock();
      const token = await lock.acquire("k", 1000);
      await lock.release("k", token!);
      await expect(lock.release("k", token!)).resolves.toBeUndefined();
    });
  });

  describe("safe expiry", () => {
    it("automatically frees the lock once its TTL elapses, with no explicit release", async () => {
      vi.useFakeTimers();
      try {
        const lock = new InMemoryLock();
        await lock.acquire("k", 1000);
        expect(lock.isHeld("k")).toBe(true);

        vi.advanceTimersByTime(999);
        expect(lock.isHeld("k")).toBe(true);

        vi.advanceTimersByTime(2);
        expect(lock.isHeld("k")).toBe(false);
        expect(await lock.acquire("k", 1000)).toBeDefined();
      } finally {
        vi.useRealTimers();
      }
    });

    it("a fresh acquire on an expired key gets its own new expiry, not the stale one", async () => {
      vi.useFakeTimers();
      try {
        const lock = new InMemoryLock();
        await lock.acquire("k", 100);
        vi.advanceTimersByTime(150); // expired
        const token = await lock.acquire("k", 1000); // fresh 1000ms lease
        expect(token).toBeDefined();

        vi.advanceTimersByTime(200); // well within the new lease
        expect(lock.isHeld("k")).toBe(true);
      } finally {
        vi.useRealTimers();
      }
    });
  });
});
