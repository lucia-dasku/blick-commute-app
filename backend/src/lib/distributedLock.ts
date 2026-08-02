import { randomUUID } from "node:crypto";

/**
 * A mutual-exclusion lock coordinating work across all Vercel instances (see
 * docs/api-contract.md, "Caching and fair use", and src/services/deviationsSnapshotService.ts,
 * the one caller of this interface today). `acquire` never blocks waiting for the lock to
 * free up — it returns immediately, either with a unique ownership token (lock acquired)
 * or `undefined` (already held by someone else); callers decide for themselves whether to
 * wait briefly and retry, or fall back to cached/stale data.
 *
 * **Safe expiry**: every lock is acquired with an explicit `ttlMs`, so a crashed or hung
 * holder can never keep it forever — eventual release is guaranteed by expiry alone, with
 * no separate cleanup process required.
 *
 * **Ownership protection**: `release` only removes the lock if the caller's own `token`
 * still matches the value currently stored for `key`. This defends against the classic
 * distributed-lock bug: holder A's TTL expires, holder B then legitimately acquires the
 * same key, and A's own (now-late) `release` call would otherwise delete B's still-valid
 * lock. The Redis-backed implementation (`src/lib/redisClient.ts`) performs this
 * compare-and-delete atomically via a Lua script — never as two separate GET/DEL round
 * trips, which would themselves be racy.
 */
export interface DistributedLock {
  acquire(key: string, ttlMs: number): Promise<string | undefined>;
  release(key: string, token: string): Promise<void>;
}

interface HeldLock {
  token: string;
  expiresAt: number;
}

/**
 * Per-process, in-memory lock — correct for local development and tests (including
 * simulating multiple "separate instances" that share one Redis by simply sharing one
 * `InMemoryLock` object across several service instances in a test — see
 * tests/deviationsSnapshotService.test.ts), but provides NO cross-instance protection
 * whatsoever on real, multi-instance Vercel traffic, matching `InMemoryCache`'s own
 * documented limitation (see src/lib/cache.ts). Production must never fall back to this
 * silently — see `config.redis`'s own doc in src/config/env.ts.
 */
export class InMemoryLock implements DistributedLock {
  private readonly held = new Map<string, HeldLock>();

  async acquire(key: string, ttlMs: number): Promise<string | undefined> {
    const existing = this.held.get(key);
    if (existing && Date.now() < existing.expiresAt) {
      return undefined;
    }
    const token = randomUUID();
    this.held.set(key, { token, expiresAt: Date.now() + ttlMs });
    return token;
  }

  async release(key: string, token: string): Promise<void> {
    const existing = this.held.get(key);
    if (existing && existing.token === token) {
      this.held.delete(key);
    }
  }

  /** Test/diagnostic helper, not part of the DistributedLock interface. */
  isHeld(key: string): boolean {
    const existing = this.held.get(key);
    return existing != null && Date.now() < existing.expiresAt;
  }
}
