import { randomUUID } from "node:crypto";
import type { Cache } from "./cache.js";
import type { DistributedLock } from "./distributedLock.js";

/**
 * The minimal subset of `@upstash/redis`'s `Redis` client that `RedisCache`/`RedisLock`
 * actually call. Depending on this narrow interface — rather than the full `Redis`
 * class, which exposes dozens of unrelated commands — is what makes these two classes
 * testable with a lightweight fake standing in for a real Redis connection (see
 * `tests/redisClient.test.ts`); a real `Redis` instance satisfies this structurally, so
 * production wiring (src/app.ts) is unaffected.
 */
export interface RedisLike {
  get<T>(key: string): Promise<T | null>;
  set<T>(key: string, value: T, opts?: { ex?: number; px?: number; nx?: true }): Promise<"OK" | T | null>;
  eval<TArgs extends unknown[], TData = unknown>(script: string, keys: string[], args: TArgs): Promise<TData>;
}

/**
 * Atomically deletes `KEYS[1]` only if its current value equals `ARGV[1]` — the
 * compare-and-delete that makes `RedisLock.release` ownership-safe (see
 * `DistributedLock`'s own doc). A plain `GET` then `DEL` from application code would not
 * be atomic and could race with another instance's legitimate `acquire` in between —
 * this single round trip to Upstash is what closes that race.
 */
const RELEASE_IF_OWNER_SCRIPT = `
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("DEL", KEYS[1])
else
  return 0
end
`;

/**
 * `Cache` backed by Upstash Redis's REST API (`@upstash/redis`) — the ONLY implementation
 * of this interface actually shared across Vercel serverless instances; see
 * `InMemoryCache` (src/lib/cache.ts) for the per-process fallback used in local
 * development and tests. Constructed in src/app.ts from `config.redis`.
 */
export class RedisCache implements Cache {
  constructor(private readonly redis: RedisLike) {}

  async get<T>(key: string): Promise<T | undefined> {
    const value = await this.redis.get<T>(key);
    return value ?? undefined;
  }

  async set<T>(key: string, value: T, ttlSeconds: number): Promise<void> {
    await this.redis.set(key, value, { ex: ttlSeconds });
  }
}

/**
 * `DistributedLock` backed by Upstash Redis — `acquire` uses `SET key token NX PX ttlMs`,
 * a single atomic command: it only writes `token` if `key` does not already exist,
 * returning `"OK"` on success or `null` if some other caller (any instance) already holds
 * it. `release` uses `RELEASE_IF_OWNER_SCRIPT` for a safe, ownership-checked delete. See
 * `DistributedLock`'s own doc for what "safe expiry and ownership protection" means here.
 */
export class RedisLock implements DistributedLock {
  constructor(private readonly redis: RedisLike) {}

  async acquire(key: string, ttlMs: number): Promise<string | undefined> {
    const token = randomUUID();
    const result = await this.redis.set(key, token, { nx: true, px: ttlMs });
    return result === "OK" ? token : undefined;
  }

  async release(key: string, token: string): Promise<void> {
    await this.redis.eval(RELEASE_IF_OWNER_SCRIPT, [key], [token]);
  }
}
