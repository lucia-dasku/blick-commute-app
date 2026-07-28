/**
 * Cache abstraction used by the services layer.
 *
 * IMPORTANT (see docs/api-contract.md, "Caching"): the only implementation provided
 * here (`InMemoryCache`) is a best-effort, per-process cache. On Vercel, each
 * serverless function invocation may run in a fresh instance with no shared memory,
 * so this cache should be treated as "sometimes helps, never guaranteed" — it is not
 * a substitute for the HTTP Cache-Control headers set on each route, which are the
 * layer that actually protects the upstream under real traffic.
 *
 * A shared implementation (e.g. backed by Upstash Redis) can replace `InMemoryCache`
 * by implementing the same `Cache` interface, without touching call sites.
 */
export interface Cache {
  get<T>(key: string): Promise<T | undefined>;
  set<T>(key: string, value: T, ttlSeconds: number): Promise<void>;
}

interface Entry {
  value: unknown;
  expiresAt: number;
}

export class InMemoryCache implements Cache {
  private readonly store = new Map<string, Entry>();

  async get<T>(key: string): Promise<T | undefined> {
    const entry = this.store.get(key);
    if (!entry) return undefined;
    if (Date.now() >= entry.expiresAt) {
      this.store.delete(key);
      return undefined;
    }
    return entry.value as T;
  }

  async set<T>(key: string, value: T, ttlSeconds: number): Promise<void> {
    this.store.set(key, { value, expiresAt: Date.now() + ttlSeconds * 1000 });
  }

  /** Test/diagnostic helper, not part of the Cache interface. */
  size(): number {
    return this.store.size;
  }
}

/**
 * Coalesces concurrent calls for the same key into a single in-flight promise, so that
 * simultaneous cold requests within one serverless instance do not each independently
 * hit the upstream (e.g. many concurrent stop-search requests before the site snapshot
 * has finished loading).
 */
export class InFlightDeduper {
  private readonly inFlight = new Map<string, Promise<unknown>>();

  async run<T>(key: string, factory: () => Promise<T>): Promise<T> {
    const existing = this.inFlight.get(key);
    if (existing) {
      return existing as Promise<T>;
    }
    const promise = factory().finally(() => {
      this.inFlight.delete(key);
    });
    this.inFlight.set(key, promise);
    return promise;
  }

  /** Test/diagnostic helper. */
  pendingCount(): number {
    return this.inFlight.size;
  }
}
