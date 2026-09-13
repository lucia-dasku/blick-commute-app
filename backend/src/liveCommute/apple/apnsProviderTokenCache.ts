import { inspect } from "node:util";
import type {
  ApnsProviderTokenSigner,
  SensitiveApnsProviderToken,
} from "./apnsProviderToken.js";

export const APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS = 20 * 60;
export const APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS = 60 * 60;
export const BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS = 50 * 60;

export type ApnsProviderTokenCacheErrorCode =
  | "APNS_PROVIDER_TOKEN_CACHE_CONFIGURATION_INVALID"
  | "APNS_PROVIDER_TOKEN_CLOCK_INVALID"
  | "APNS_PROVIDER_TOKEN_REFRESH_TOO_SOON"
  | "APNS_PROVIDER_TOKEN_SIGNING_FAILED";

const CACHE_ERROR_MESSAGES: Readonly<Record<ApnsProviderTokenCacheErrorCode, string>> =
  Object.freeze({
    APNS_PROVIDER_TOKEN_CACHE_CONFIGURATION_INVALID:
      "APNs provider-token cache configuration is invalid",
    APNS_PROVIDER_TOKEN_CLOCK_INVALID: "APNs provider-token clock is invalid",
    APNS_PROVIDER_TOKEN_REFRESH_TOO_SOON:
      "APNs provider token cannot be refreshed yet",
    APNS_PROVIDER_TOKEN_SIGNING_FAILED: "APNs provider-token cache signing failed",
  });

export class ApnsProviderTokenCacheError extends Error {
  readonly code: ApnsProviderTokenCacheErrorCode;
  readonly retryNotBeforeEpochSeconds: number | null;

  constructor(
    code: ApnsProviderTokenCacheErrorCode,
    retryNotBeforeEpochSeconds: number | null = null,
  ) {
    super(CACHE_ERROR_MESSAGES[code]);
    this.name = "ApnsProviderTokenCacheError";
    this.code = code;
    this.retryNotBeforeEpochSeconds = retryNotBeforeEpochSeconds;
  }
}

export interface ApnsProviderTokenLease {
  /** Process-local correlation used to invalidate only the token sent on one request. */
  readonly generation: number;
  readonly issuedAt: number;
  readonly token: SensitiveApnsProviderToken;
}

export interface LazyApnsProviderTokenCacheOptions {
  /** Trusted application clock. Reading the cache never falls back to Date.now(). */
  readonly now: () => Date;
  /** Named Blick policy override; must remain inside Apple's documented safe interval. */
  readonly refreshAfterSeconds?: number;
}

function validRefreshPolicy(value: number): number {
  if (
    !Number.isSafeInteger(value) ||
    value < APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS ||
    value >= APNS_PROVIDER_TOKEN_MAXIMUM_AGE_SECONDS
  ) {
    throw new ApnsProviderTokenCacheError(
      "APNS_PROVIDER_TOKEN_CACHE_CONFIGURATION_INVALID",
    );
  }
  return value;
}

function clockSeconds(now: () => Date): number {
  let value: Date;
  try {
    value = now();
  } catch {
    throw new ApnsProviderTokenCacheError("APNS_PROVIDER_TOKEN_CLOCK_INVALID");
  }
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new ApnsProviderTokenCacheError("APNS_PROVIDER_TOKEN_CLOCK_INVALID");
  }
  const seconds = Math.floor(value.getTime() / 1_000);
  if (!Number.isSafeInteger(seconds) || seconds < 0) {
    throw new ApnsProviderTokenCacheError("APNS_PROVIDER_TOKEN_CLOCK_INVALID");
  }
  return seconds;
}

/**
 * On-demand provider-JWT cache. It owns no timer and performs no signing until getToken().
 * Invalidating a lease retains the issuance boundary so an authentication failure cannot
 * accidentally create Apple's TooManyProviderTokenUpdates regeneration loop.
 */
export class LazyApnsProviderTokenCache {
  readonly #signer: ApnsProviderTokenSigner;
  readonly #now: () => Date;
  readonly #refreshAfterSeconds: number;
  #current: ApnsProviderTokenLease | undefined;
  #lastIssuedAt: number | undefined;
  #nextGeneration = 1;

  constructor(
    signer: ApnsProviderTokenSigner,
    options: LazyApnsProviderTokenCacheOptions,
  ) {
    if (signer == null || typeof signer.sign !== "function") {
      throw new ApnsProviderTokenCacheError(
        "APNS_PROVIDER_TOKEN_CACHE_CONFIGURATION_INVALID",
      );
    }
    if (options == null || typeof options.now !== "function") {
      throw new ApnsProviderTokenCacheError(
        "APNS_PROVIDER_TOKEN_CACHE_CONFIGURATION_INVALID",
      );
    }
    this.#signer = signer;
    this.#now = options.now;
    this.#refreshAfterSeconds = validRefreshPolicy(
      options.refreshAfterSeconds ?? BLICK_APNS_PROVIDER_TOKEN_REFRESH_SECONDS,
    );
    Object.freeze(this);
  }

  get refreshAfterSeconds(): number {
    return this.#refreshAfterSeconds;
  }

  peek(): ApnsProviderTokenLease | undefined {
    return this.#current;
  }

  getToken(): ApnsProviderTokenLease {
    const now = clockSeconds(this.#now);
    if (this.#lastIssuedAt != null && now < this.#lastIssuedAt) {
      throw new ApnsProviderTokenCacheError("APNS_PROVIDER_TOKEN_CLOCK_INVALID");
    }

    const current = this.#current;
    if (current != null && now - current.issuedAt < this.#refreshAfterSeconds) {
      return current;
    }

    if (
      current == null &&
      this.#lastIssuedAt != null &&
      now - this.#lastIssuedAt < APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS
    ) {
      throw new ApnsProviderTokenCacheError(
        "APNS_PROVIDER_TOKEN_REFRESH_TOO_SOON",
        this.#lastIssuedAt + APNS_PROVIDER_TOKEN_MINIMUM_ROTATION_SECONDS,
      );
    }

    let token: SensitiveApnsProviderToken;
    try {
      token = this.#signer.sign({ issuedAt: now });
      if (
        token == null ||
        token.issuedAt !== now ||
        typeof token.revealForAuthorization !== "function"
      ) {
        throw new Error();
      }
    } catch {
      throw new ApnsProviderTokenCacheError("APNS_PROVIDER_TOKEN_SIGNING_FAILED");
    }
    if (!Number.isSafeInteger(this.#nextGeneration) || this.#nextGeneration <= 0) {
      throw new ApnsProviderTokenCacheError("APNS_PROVIDER_TOKEN_SIGNING_FAILED");
    }
    const lease = Object.freeze({
      generation: this.#nextGeneration,
      issuedAt: now,
      token,
    });
    this.#nextGeneration += 1;
    this.#lastIssuedAt = now;
    this.#current = lease;
    return lease;
  }

  invalidate(): boolean {
    if (this.#current == null) return false;
    this.#current = undefined;
    return true;
  }

  invalidateIfCurrent(lease: ApnsProviderTokenLease): boolean {
    if (this.#current !== lease) return false;
    this.#current = undefined;
    return true;
  }

  toJSON(): string {
    return "[APNs provider-token cache]";
  }

  toString(): string {
    return "[APNs provider-token cache]";
  }

  [inspect.custom](): string {
    return "[APNs provider-token cache]";
  }
}
