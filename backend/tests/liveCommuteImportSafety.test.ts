import { afterEach, describe, expect, it, vi } from "vitest";

const ORIGINAL_NODE_ENV = process.env.NODE_ENV;
const ORIGINAL_REDIS_URL = process.env.UPSTASH_REDIS_REST_URL;
const ORIGINAL_REDIS_TOKEN = process.env.UPSTASH_REDIS_REST_TOKEN;

function restoreEnvironment(): void {
  if (ORIGINAL_NODE_ENV == null) delete process.env.NODE_ENV;
  else process.env.NODE_ENV = ORIGINAL_NODE_ENV;

  if (ORIGINAL_REDIS_URL == null) delete process.env.UPSTASH_REDIS_REST_URL;
  else process.env.UPSTASH_REDIS_REST_URL = ORIGINAL_REDIS_URL;

  if (ORIGINAL_REDIS_TOKEN == null) delete process.env.UPSTASH_REDIS_REST_TOKEN;
  else process.env.UPSTASH_REDIS_REST_TOKEN = ORIGINAL_REDIS_TOKEN;
}

afterEach(() => {
  restoreEnvironment();
  vi.resetModules();
});
describe("live commute module import safety", () => {
  it("does not require production infrastructure configuration just to load the engine", async () => {
    process.env.NODE_ENV = "production";
    delete process.env.UPSTASH_REDIS_REST_URL;
    delete process.env.UPSTASH_REDIS_REST_TOKEN;
    vi.resetModules();

    const module = await import("../src/liveCommute/engine.js");

    expect(module.runLiveCommuteTick).toBeTypeOf("function");
  });
});
