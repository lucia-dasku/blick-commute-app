import type { RedisLike } from "../lib/redisClient.js";

export const REVIEWER_ACCESS_RATE_LIMIT_WINDOW_SECONDS = 60;
export const REVIEWER_ACCESS_PER_ATTEMPT_LIMIT = 10;
export const REVIEWER_ACCESS_PER_CLIENT_LIMIT = 30;
export const REVIEWER_ACCESS_EMERGENCY_GLOBAL_LIMIT = 10_000;

const INCREMENT_WINDOW_SCRIPT = `
local clientCount = redis.call("INCR", KEYS[2])
if clientCount == 1 then redis.call("EXPIRE", KEYS[2], ARGV[1]) end
if clientCount > tonumber(ARGV[3]) then
  return 0
end

local attemptCount = redis.call("INCR", KEYS[1])
if attemptCount == 1 then redis.call("EXPIRE", KEYS[1], ARGV[1]) end
if attemptCount > tonumber(ARGV[2]) then
  return 0
end

local globalCount = redis.call("INCR", KEYS[3])
if globalCount == 1 then redis.call("EXPIRE", KEYS[3], ARGV[1]) end
if globalCount > tonumber(ARGV[4]) then
  return 0
end
return 1
`;

export interface ReviewerAccessRateLimiter {
  allow(attemptFingerprint: string, clientFingerprint: string): Promise<boolean>;
}

/** Uses a dedicated namespace; reviewer attempts cannot consume billing counters. */
export class RedisReviewerAccessRateLimiter implements ReviewerAccessRateLimiter {
  constructor(private readonly redis: RedisLike) {}

  async allow(attemptFingerprint: string, clientFingerprint: string): Promise<boolean> {
    const result = await this.redis.eval<[number, number, number, number], number>(
      INCREMENT_WINDOW_SCRIPT,
      [
        `reviewer-access:validate:v2:attempt:${attemptFingerprint}`,
        `reviewer-access:validate:v2:client:${clientFingerprint}`,
        "reviewer-access:validate:v2:global",
      ],
      [
        REVIEWER_ACCESS_RATE_LIMIT_WINDOW_SECONDS,
        REVIEWER_ACCESS_PER_ATTEMPT_LIMIT,
        REVIEWER_ACCESS_PER_CLIENT_LIMIT,
        REVIEWER_ACCESS_EMERGENCY_GLOBAL_LIMIT,
      ],
    );
    return result === 1;
  }
}

interface Counter {
  count: number;
  expiresAt: number;
}

/** Local-development fallback. Production uses the shared Redis implementation above. */
export class InMemoryReviewerAccessRateLimiter implements ReviewerAccessRateLimiter {
  private readonly counters = new Map<string, Counter>();

  async allow(attemptFingerprint: string, clientFingerprint: string): Promise<boolean> {
    const now = Date.now();
    const clientCount = this.increment(`client:${clientFingerprint}`, now);
    if (clientCount > REVIEWER_ACCESS_PER_CLIENT_LIMIT) return false;

    const attemptCount = this.increment(`attempt:${attemptFingerprint}`, now);
    if (attemptCount > REVIEWER_ACCESS_PER_ATTEMPT_LIMIT) return false;

    const globalCount = this.increment("global", now);
    return globalCount <= REVIEWER_ACCESS_EMERGENCY_GLOBAL_LIMIT;
  }

  private increment(key: string, now: number): number {
    const existing = this.counters.get(key);
    if (!existing || existing.expiresAt <= now) {
      this.counters.set(key, {
        count: 1,
        expiresAt: now + REVIEWER_ACCESS_RATE_LIMIT_WINDOW_SECONDS * 1_000,
      });
      return 1;
    }
    existing.count += 1;
    return existing.count;
  }
}
