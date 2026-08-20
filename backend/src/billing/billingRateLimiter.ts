import type { RedisLike } from "../lib/redisClient.js";

const WINDOW_SECONDS = 60;
const PER_TOKEN_LIMIT = 10;
const GLOBAL_LIMIT = 120;

const INCREMENT_WINDOW_SCRIPT = `
local tokenCount = redis.call("INCR", KEYS[1])
if tokenCount == 1 then redis.call("EXPIRE", KEYS[1], ARGV[1]) end
local globalCount = redis.call("INCR", KEYS[2])
if globalCount == 1 then redis.call("EXPIRE", KEYS[2], ARGV[1]) end
if tokenCount > tonumber(ARGV[2]) or globalCount > tonumber(ARGV[3]) then
  return 0
end
return 1
`;

export interface BillingRateLimiter {
  allow(tokenFingerprint: string): Promise<boolean>;
}

export class RedisBillingRateLimiter implements BillingRateLimiter {
  constructor(private readonly redis: RedisLike) {}

  async allow(tokenFingerprint: string): Promise<boolean> {
    const result = await this.redis.eval<[number, number, number], number>(
      INCREMENT_WINDOW_SCRIPT,
      [`billing:verify:token:${tokenFingerprint}`, "billing:verify:global"],
      [WINDOW_SECONDS, PER_TOKEN_LIMIT, GLOBAL_LIMIT],
    );
    return result === 1;
  }
}

interface Counter {
  count: number;
  expiresAt: number;
}

/** Local-development fallback. Production uses the shared Redis implementation above. */
export class InMemoryBillingRateLimiter implements BillingRateLimiter {
  private readonly counters = new Map<string, Counter>();

  async allow(tokenFingerprint: string): Promise<boolean> {
    const now = Date.now();
    const tokenCount = this.increment(`token:${tokenFingerprint}`, now);
    const globalCount = this.increment("global", now);
    return tokenCount <= PER_TOKEN_LIMIT && globalCount <= GLOBAL_LIMIT;
  }

  private increment(key: string, now: number): number {
    const existing = this.counters.get(key);
    if (!existing || existing.expiresAt <= now) {
      this.counters.set(key, { count: 1, expiresAt: now + WINDOW_SECONDS * 1_000 });
      return 1;
    }
    existing.count += 1;
    return existing.count;
  }
}
