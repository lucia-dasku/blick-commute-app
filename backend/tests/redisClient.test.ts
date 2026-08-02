import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RedisCache, RedisLock, type RedisLike } from "../src/lib/redisClient.js";

/**
 * A lightweight stand-in for `@upstash/redis`'s `Redis` client, implementing just the
 * `RedisLike` surface with real Redis GET/SET/EVAL semantics (TTL expiry, NX refusal,
 * and — for `eval` — the exact compare-and-delete this codebase's own
 * `RELEASE_IF_OWNER_SCRIPT` performs, since that is the only script `RedisLock` ever
 * sends; this is not a general Lua interpreter). Exercising `RedisCache`/`RedisLock`
 * against this fake — rather than only against `InMemoryCache`/`InMemoryLock`, which are
 * entirely separate implementations of the same interfaces — proves these two classes
 * translate the `Cache`/`DistributedLock` contracts into the actual Upstash command
 * shapes (`{ex}`, `{nx, px}`, `eval(script, keys, args)`) correctly.
 */
class FakeRedis implements RedisLike {
  private readonly store = new Map<string, { value: unknown; expiresAt: number | null }>();

  async get<T>(key: string): Promise<T | null> {
    const entry = this.store.get(key);
    if (!entry) return null;
    if (entry.expiresAt != null && Date.now() >= entry.expiresAt) {
      this.store.delete(key);
      return null;
    }
    return entry.value as T;
  }

  async set<T>(key: string, value: T, opts?: { ex?: number; px?: number; nx?: true }): Promise<"OK" | T | null> {
    if (opts?.nx && (await this.get(key)) !== null) {
      return null;
    }
    const ttlMs = opts?.px ?? (opts?.ex != null ? opts.ex * 1000 : undefined);
    this.store.set(key, { value, expiresAt: ttlMs != null ? Date.now() + ttlMs : null });
    return "OK";
  }

  async eval<TArgs extends unknown[], TData = unknown>(_script: string, keys: string[], args: TArgs): Promise<TData> {
    const [key] = keys;
    const [token] = args;
    const current = await this.get<string>(key!);
    if (current === token) {
      this.store.delete(key!);
      return 1 as unknown as TData;
    }
    return 0 as unknown as TData;
  }
}

describe("RedisCache", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-28T08:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("returns undefined for a key that was never set", async () => {
    const cache = new RedisCache(new FakeRedis());
    expect(await cache.get("missing")).toBeUndefined();
  });

  it("returns the value set via the underlying redis.set({ex}) call", async () => {
    const cache = new RedisCache(new FakeRedis());
    await cache.set("key", { hello: "world" }, 60);
    expect(await cache.get("key")).toEqual({ hello: "world" });
  });

  it("expires the value once its ex TTL (in seconds) elapses", async () => {
    const cache = new RedisCache(new FakeRedis());
    await cache.set("key", "value", 60);
    await vi.advanceTimersByTimeAsync(59_000);
    expect(await cache.get("key")).toBe("value");
    await vi.advanceTimersByTimeAsync(2_000);
    expect(await cache.get("key")).toBeUndefined();
  });

  it("treats a null underlying redis.get result as undefined, not null", async () => {
    const redis = new FakeRedis();
    const cache = new RedisCache(redis);
    expect(await redis.get("key")).toBeNull();
    expect(await cache.get("key")).toBeUndefined();
  });
});

describe("RedisLock", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-28T08:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("acquires a free key via redis.set(key, token, {nx: true, px: ttlMs}) and returns a token", async () => {
    const lock = new RedisLock(new FakeRedis());
    const token = await lock.acquire("lock-key", 5_000);
    expect(token).toBeDefined();
    expect(typeof token).toBe("string");
  });

  it("returns undefined when the key is already held (NX refusal)", async () => {
    const redis = new FakeRedis();
    const lock = new RedisLock(redis);
    await lock.acquire("lock-key", 5_000);
    const second = await lock.acquire("lock-key", 5_000);
    expect(second).toBeUndefined();
  });

  it("allows re-acquiring the key once its px TTL (in milliseconds) has expired", async () => {
    const lock = new RedisLock(new FakeRedis());
    await lock.acquire("lock-key", 1_000);
    await vi.advanceTimersByTimeAsync(1_500);
    const token = await lock.acquire("lock-key", 1_000);
    expect(token).toBeDefined();
  });

  it("release with the correct token deletes the lock, freeing it for the next acquire", async () => {
    const lock = new RedisLock(new FakeRedis());
    const token = await lock.acquire("lock-key", 5_000);
    await lock.release("lock-key", token!);
    const reacquired = await lock.acquire("lock-key", 5_000);
    expect(reacquired).toBeDefined();
  });

  it("release with the wrong token is a no-op (ownership-checked via the eval script)", async () => {
    const lock = new RedisLock(new FakeRedis());
    await lock.acquire("lock-key", 5_000);
    await lock.release("lock-key", "some-other-token");
    const stillHeld = await lock.acquire("lock-key", 5_000);
    expect(stillHeld).toBeUndefined();
  });

  it("a late release from an expired holder does not delete a new legitimate holder's lock", async () => {
    const lock = new RedisLock(new FakeRedis());
    const staleToken = await lock.acquire("lock-key", 1_000);
    await vi.advanceTimersByTimeAsync(1_500); // stale holder's TTL expires
    const newToken = await lock.acquire("lock-key", 5_000); // a new holder takes over
    expect(newToken).toBeDefined();

    await lock.release("lock-key", staleToken!); // the stale holder's late release arrives

    const stillHeldByNewHolder = await lock.acquire("lock-key", 5_000);
    expect(stillHeldByNewHolder).toBeUndefined(); // the new holder's lock must survive
  });
});
