import {
  KeyObject,
  createPrivateKey,
  sign as signBytes,
} from "node:crypto";
import { inspect } from "node:util";

const APNS_IDENTIFIER_PATTERN = /^[A-Z0-9]{10}$/;
const P256_CURVE_NAMES = new Set(["prime256v1", "secp256r1", "P-256"]);
const ES256_JOSE_SIGNATURE_BYTES = 64;
const REDACTED_PROVIDER_TOKEN = "[REDACTED APNs provider token]";
const SIGNING_ERROR_MESSAGE = "APNs provider token signing failed";

export type ApnsPrivateSigningKey = string | Buffer | KeyObject;

export interface ApnsProviderTokenSignerConfiguration {
  readonly teamId: string;
  readonly keyId: string;
  readonly privateKey: ApnsPrivateSigningKey;
}

export interface SignApnsProviderTokenInput {
  /** A `number` is interpreted as whole UNIX epoch seconds. */
  readonly issuedAt: Date | number;
}

/**
 * Sensitive bearer credential. Its value is deliberately absent from enumerable,
 * JSON, string, and Node inspection surfaces. Reveal it only while constructing the
 * immediate APNs authorization header and do not retain the returned string.
 */
export interface SensitiveApnsProviderToken {
  readonly issuedAt: number;
  revealForAuthorization(): string;
  toJSON(): string;
  toString(): string;
}

export interface ApnsProviderTokenSigner {
  sign(input: SignApnsProviderTokenInput): SensitiveApnsProviderToken;
}

export class ApnsProviderTokenSigningError extends Error {
  readonly code = "APNS_PROVIDER_TOKEN_SIGNING_FAILED" as const;

  constructor() {
    super(SIGNING_ERROR_MESSAGE);
    this.name = "ApnsProviderTokenSigningError";
  }
}

class RedactedApnsProviderToken implements SensitiveApnsProviderToken {
  readonly #token: string;
  readonly #issuedAt: number;

  constructor(token: string, issuedAt: number) {
    this.#token = token;
    this.#issuedAt = issuedAt;
    Object.freeze(this);
  }

  get issuedAt(): number {
    return this.#issuedAt;
  }

  revealForAuthorization(): string {
    return this.#token;
  }

  toJSON(): string {
    return REDACTED_PROVIDER_TOKEN;
  }

  toString(): string {
    return REDACTED_PROVIDER_TOKEN;
  }

  [inspect.custom](): string {
    return REDACTED_PROVIDER_TOKEN;
  }
}

function signingFailure(): ApnsProviderTokenSigningError {
  return new ApnsProviderTokenSigningError();
}

function normalizedIdentifier(value: string): string {
  if (typeof value !== "string" || !APNS_IDENTIFIER_PATTERN.test(value)) {
    throw signingFailure();
  }
  return value;
}

function issuedAtSeconds(value: Date | number): number {
  if (value instanceof Date) {
    const milliseconds = value.getTime();
    if (!Number.isFinite(milliseconds)) throw signingFailure();
    const seconds = Math.floor(milliseconds / 1_000);
    if (!Number.isSafeInteger(seconds) || seconds < 0) throw signingFailure();
    return seconds;
  }
  if (!Number.isSafeInteger(value) || value < 0) throw signingFailure();
  return value;
}

function privateP256Key(input: ApnsPrivateSigningKey): KeyObject {
  try {
    const key = input instanceof KeyObject ? input : createPrivateKey(input);
    const curve = key.asymmetricKeyDetails?.namedCurve;
    if (
      key.type !== "private" ||
      key.asymmetricKeyType !== "ec" ||
      curve == null ||
      !P256_CURVE_NAMES.has(curve)
    ) {
      throw signingFailure();
    }
    return key;
  } catch {
    throw signingFailure();
  }
}

function base64UrlJson(value: Readonly<Record<string, string | number>>): string {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
}

/**
 * Creates a synchronous ES256 signing primitive. It intentionally owns no clock,
 * provider-token cache, refresh timer, environment lookup, or APNs connection.
 */
export function createEs256ApnsProviderTokenSigner(
  configuration: ApnsProviderTokenSignerConfiguration,
): ApnsProviderTokenSigner {
  let teamId: string;
  let keyId: string;
  let signingKey: KeyObject;
  try {
    teamId = normalizedIdentifier(configuration.teamId);
    keyId = normalizedIdentifier(configuration.keyId);
    signingKey = privateP256Key(configuration.privateKey);
  } catch {
    throw signingFailure();
  }

  const sign = (input: SignApnsProviderTokenInput): SensitiveApnsProviderToken => {
    try {
      const issuedAt = issuedAtSeconds(input.issuedAt);
      const encodedHeader = base64UrlJson({ alg: "ES256", kid: keyId });
      const encodedClaims = base64UrlJson({ iss: teamId, iat: issuedAt });
      const signingInput = `${encodedHeader}.${encodedClaims}`;
      const signature = signBytes("sha256", Buffer.from(signingInput, "ascii"), {
        key: signingKey,
        dsaEncoding: "ieee-p1363",
      });
      if (signature.byteLength !== ES256_JOSE_SIGNATURE_BYTES) {
        throw signingFailure();
      }
      return new RedactedApnsProviderToken(
        `${signingInput}.${signature.toString("base64url")}`,
        issuedAt,
      );
    } catch {
      throw signingFailure();
    }
  };

  return Object.freeze({ sign });
}
