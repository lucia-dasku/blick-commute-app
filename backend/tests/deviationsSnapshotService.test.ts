import { describe, expect, it, vi } from "vitest";
import { createDeviationsSnapshotService } from "../src/services/deviationsSnapshotService.js";
import { InMemoryCache } from "../src/lib/cache.js";
import { InMemoryLock } from "../src/lib/distributedLock.js";
import { AppError, isAppError } from "../src/lib/errors.js";
import type { SlDeviationsClient } from "../src/services/slDeviationsClient.js";
import type { RawDeviation } from "../src/services/upstreamTypes.js";

function fakeDeviation(caseId: number): RawDeviation {
  return {
    version: 1,
    created: "2026-07-27T20:12:47.15+02:00",
    modified: null,
    deviation_case_id: caseId,
    priority: { importance_level: 1, influence_level: 1, urgency_level: 1 },
    message_variants: [{ header: "h", details: "d", language: "sv" }],
    scope: {},
  };
}

/** A fake client whose call count is observable and whose response (or failure) is
 * scripted per call — the one seam every test in this file drives. */
function scriptedClient(script: Array<(() => Promise<RawDeviation[]>) | Error>) {
  let callCount = 0;
  const calls: number[] = [];
  const client: SlDeviationsClient = {
    async fetchAllDeviations() {
      const index = callCount;
      callCount += 1;
      calls.push(Date.now());
      const entry = script[index];
      if (entry == null) {
        throw new Error(`scriptedClient: no script entry for call #${index}`);
      }
      if (entry instanceof Error) throw entry;
      return entry();
    },
  };
  return { client, callCount: () => callCount, calls };
}

describe("createDeviationsSnapshotService — basic fetch/cache behavior", () => {
  it("fetches on a cold cache and returns a fresh fetchedAt", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([async () => [fakeDeviation(1)]]);
      const service = createDeviationsSnapshotService(client, new InMemoryCache(), new InMemoryLock());

      const snapshot = await service.getSnapshot();

      expect(callCount()).toBe(1);
      expect(snapshot.fetchedAt).toBe("2026-07-27T05:00:00.000Z");
      expect(snapshot.deviations).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("serves the cached snapshot (same fetchedAt) on a second call within the fresh window, without a new upstream call", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([async () => [fakeDeviation(1)]]);
      const service = createDeviationsSnapshotService(client, new InMemoryCache(), new InMemoryLock());

      const first = await service.getSnapshot();
      vi.setSystemTime(new Date("2026-07-27T05:00:30Z")); // 30s later, still within 60s
      const second = await service.getSnapshot();

      expect(callCount()).toBe(1);
      expect(second.fetchedAt).toBe(first.fetchedAt);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("createDeviationsSnapshotService — the 60-second fair-use limit", () => {
  it("fetches again once the freshness window has fully elapsed", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([async () => [fakeDeviation(1)], async () => [fakeDeviation(2)]]);
      const service = createDeviationsSnapshotService(client, new InMemoryCache(), new InMemoryLock());

      const first = await service.getSnapshot();
      vi.setSystemTime(new Date("2026-07-27T05:01:01Z")); // 61s later
      const second = await service.getSnapshot();

      expect(callCount()).toBe(2);
      expect(second.fetchedAt).not.toBe(first.fetchedAt);
      expect(second.fetchedAt).toBe("2026-07-27T05:01:01.000Z");
    } finally {
      vi.useRealTimers();
    }
  });

  it("a FAILED attempt still counts against the 60s window — no immediate retry, cooldown enforced", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([
        new AppError("UPSTREAM_ERROR", "boom"),
        async () => [fakeDeviation(1)],
      ]);
      const service = createDeviationsSnapshotService(client, new InMemoryCache(), new InMemoryLock());

      // First call: fetch fails, no stale data exists yet -- propagates the error.
      await expect(service.getSnapshot()).rejects.toThrow("boom");
      expect(callCount()).toBe(1);

      // 10 seconds later, still well within the 60s cooldown from the FAILED attempt.
      // Must not attempt a second upstream call -- with nothing cached, it must keep
      // reporting the controlled "not yet available" condition, not silently retry.
      vi.setSystemTime(new Date("2026-07-27T05:00:10Z"));
      await expect(service.getSnapshot()).rejects.toThrow();
      expect(callCount()).toBe(1); // still just the one attempt

      // 61 seconds after the FAILED attempt: the cooldown has elapsed, a new attempt is
      // allowed and this time succeeds.
      vi.setSystemTime(new Date("2026-07-27T05:01:01Z"));
      const snapshot = await service.getSnapshot();
      expect(callCount()).toBe(2);
      expect(snapshot.deviations).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("createDeviationsSnapshotService — stale fallback", () => {
  it("on a failed refresh, returns the last successful snapshot with its ORIGINAL fetchedAt", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([
        async () => [fakeDeviation(1)],
        new AppError("UPSTREAM_ERROR", "SL Deviations returned HTTP 500 for test"),
      ]);
      const service = createDeviationsSnapshotService(client, new InMemoryCache(), new InMemoryLock());

      const first = await service.getSnapshot();
      expect(first.fetchedAt).toBe("2026-07-27T05:00:00.000Z");

      // Well past the freshness window -- a refresh is attempted and fails.
      vi.setSystemTime(new Date("2026-07-27T06:00:00Z"));
      const second = await service.getSnapshot();

      expect(callCount()).toBe(2); // the failed refresh WAS attempted
      expect(second.fetchedAt).toBe(first.fetchedAt); // stale, original fetchedAt preserved
      expect(second.deviations).toEqual(first.deviations);
    } finally {
      vi.useRealTimers();
    }
  });

  it("when no snapshot exists at all and the fetch fails, preserves the existing controlled upstream error unchanged", async () => {
    const originalError = new AppError("UPSTREAM_TIMEOUT", "SL Deviations did not complete within 10000ms");
    const { client } = scriptedClient([originalError]);
    const service = createDeviationsSnapshotService(client, new InMemoryCache(), new InMemoryLock());

    try {
      await service.getSnapshot();
      expect.fail("expected getSnapshot() to throw");
    } catch (err) {
      expect(err).toBe(originalError); // the SAME error instance, not wrapped or replaced
      expect(isAppError(err) && err.code).toBe("UPSTREAM_TIMEOUT");
    }
  });
});

describe("createDeviationsSnapshotService — concurrency across separate simulated instances", () => {
  it("concurrent getSnapshot() calls from separate service instances sharing one cache/lock (simulating separate Vercel instances) make exactly one upstream call", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([
        async () => {
          await new Promise<void>((resolve) => setTimeout(resolve, 200));
          return [fakeDeviation(1)];
        },
      ]);

      // ONE shared cache and lock, simulating ONE shared Redis -- but a SEPARATE
      // DeviationsSnapshotService object per call, simulating separate Vercel instances
      // that each only have their own process memory, never each other's.
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      const instanceA = createDeviationsSnapshotService(client, sharedCache, sharedLock);
      const instanceB = createDeviationsSnapshotService(client, sharedCache, sharedLock);
      const instanceC = createDeviationsSnapshotService(client, sharedCache, sharedLock);

      const requestA = instanceA.getSnapshot();
      const requestB = instanceB.getSnapshot();
      const requestC = instanceC.getSnapshot();
      // The losers of the refresh-lock race poll the shared cache every 200ms rather
      // than racing the winner's own 200ms fetch tick-for-tick — advance well past both
      // so the test doesn't depend on the exact fake-timer firing order at the same
      // instant.
      await vi.advanceTimersByTimeAsync(1500);
      const [snapshotA, snapshotB, snapshotC] = await Promise.all([requestA, requestB, requestC]);

      expect(callCount()).toBe(1);
      expect(snapshotA.fetchedAt).toBe(snapshotB.fetchedAt);
      expect(snapshotB.fetchedAt).toBe(snapshotC.fetchedAt);
      expect(snapshotA.deviations).toEqual(snapshotB.deviations);
    } finally {
      vi.useRealTimers();
    }
  });

  it("an instance that loses the refresh-lock race falls back to the winner's fresh result, without ever fetching itself", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      const { client, callCount } = scriptedClient([
        async () => {
          await new Promise<void>((resolve) => setTimeout(resolve, 300));
          return [fakeDeviation(1)];
        },
      ]);
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      const winner = createDeviationsSnapshotService(client, sharedCache, sharedLock);
      const loser = createDeviationsSnapshotService(client, sharedCache, sharedLock);

      const winnerPromise = winner.getSnapshot();
      // Give the winner a head start to acquire the lock first, then start the loser --
      // it must poll/wait rather than fetch a second time. The loser polls every 200ms,
      // so give it comfortable room for a couple of poll rounds beyond the winner's own
      // 300ms fetch, rather than depending on exact same-tick fake-timer ordering.
      await vi.advanceTimersByTimeAsync(10);
      const loserPromise = loser.getSnapshot();
      await vi.advanceTimersByTimeAsync(1500);

      const [winnerSnapshot, loserSnapshot] = await Promise.all([winnerPromise, loserPromise]);
      expect(callCount()).toBe(1);
      expect(loserSnapshot.fetchedAt).toBe(winnerSnapshot.fetchedAt);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("createDeviationsSnapshotService — refresh-lock expiry and recovery", () => {
  it("recovers and completes a fresh fetch after a stuck holder's lock (and the 60s rate-limit window it claimed) naturally expire", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-07-27T05:00:00Z"));
      let stuckCallCount = 0;
      const stuckClient: SlDeviationsClient = {
        async fetchAllDeviations() {
          stuckCallCount += 1;
          // Simulate a holder that never comes back (crashed, hung connection, etc.) --
          // resolves far later than either the refresh lock's or the rate limit's TTL,
          // but not literally never, so the test itself stays well-behaved.
          await new Promise<void>((resolve) => setTimeout(resolve, 10 * 60 * 1000));
          return [fakeDeviation(999)];
        },
      };
      const sharedCache = new InMemoryCache();
      const sharedLock = new InMemoryLock();
      const stuckInstance = createDeviationsSnapshotService(stuckClient, sharedCache, sharedLock);

      // Fire-and-forget: this instance's fetch will hang well past this test's own
      // assertions below.
      void stuckInstance.getSnapshot();
      await vi.advanceTimersByTimeAsync(5); // let it actually acquire the lock first

      // Advance well past BOTH the refresh lock's TTL and the 60s rate-limit window the
      // stuck instance claimed before hanging.
      await vi.advanceTimersByTimeAsync(61_000);

      const { client: recoveredClient, callCount } = scriptedClient([async () => [fakeDeviation(1)]]);
      const recoveredInstance = createDeviationsSnapshotService(recoveredClient, sharedCache, sharedLock);
      const snapshot = await recoveredInstance.getSnapshot();

      expect(callCount()).toBe(1);
      expect(snapshot.deviations).toEqual([fakeDeviation(1)]);
      expect(stuckCallCount).toBe(1); // the stuck instance's own single attempt, still pending
    } finally {
      vi.useRealTimers();
    }
  });
});
