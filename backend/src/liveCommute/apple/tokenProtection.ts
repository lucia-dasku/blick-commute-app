import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes as nodeRandomBytes,
  timingSafeEqual,
} from "node:crypto";

export const BLICK_ACTIVITY_KIT_TOKEN_MAX_BYTES = 4_096;
export const ACTIVITY_KIT_TOKEN_PROTECTION_KEY_BYTES = 32;
export const ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES = 12;
export const ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES = 16;

const PROTECTION_VERSION = 1 as const;
const PROTECTION_ALGORITHM = "AES-256-GCM" as const;
const NODE_CIPHER_ALGORITHM = "aes-256-gcm" as const;
const MAX_CONTEXT_BYTES = 1_024;
const NONCE_ATTEMPTS = 16;
const RECENT_NONCE_IDENTITIES_MAX = 4_096;
const AAD_DOMAIN = Buffer.from("blick.activity-kit-token.v1\0", "utf8");
const LOWERCASE_SHA256 = /^[0-9a-f]{64}$/;
const PROTECTION_ERROR_MESSAGE = "ActivityKit token protection failed";

declare const activityKitTokenDigestBrand: unique symbol;

/** A deterministic lookup fingerprint. It is not a bearer token or an authenticator. */
export type ActivityKitTokenDigest = string & {
  readonly [activityKitTokenDigestBrand]: true;
};

export type ProtectedActivityKitToken = Readonly<{
  version: typeof PROTECTION_VERSION;
  algorithm: typeof PROTECTION_ALGORITHM;
  nonce: Buffer;
  authenticationTag: Buffer;
  ciphertext: Buffer;
  digest: ActivityKitTokenDigest;
}>;

export interface ActivityKitTokenProtector {
  protect(token: Uint8Array, context: string): ProtectedActivityKitToken;
  unprotect(protectedToken: ProtectedActivityKitToken, context: string): Buffer;
  digest(token: Uint8Array): ActivityKitTokenDigest;
}

export interface Aes256GcmActivityKitTokenProtectorOptions {
  readonly randomBytes?: (size: number) => Uint8Array;
}

export class ActivityKitTokenProtectionError extends Error {
  readonly code = "ACTIVITY_KIT_TOKEN_PROTECTION_FAILED" as const;

  constructor() {
    super(PROTECTION_ERROR_MESSAGE);
    this.name = "ActivityKitTokenProtectionError";
  }
}

/**
 * Copies and validates the raw token at Blick's internal persistence boundary.
 * The maximum is Blick-owned abuse protection, not a claim about upstream token size.
 */
export function normalizeActivityKitToken(token: Uint8Array): Buffer {
  if (!(token instanceof Uint8Array)) {
    throw new TypeError("ActivityKit token must be a Uint8Array");
  }
  if (token.byteLength === 0) {
    throw new RangeError("ActivityKit token must not be empty");
  }
  if (token.byteLength > BLICK_ACTIVITY_KIT_TOKEN_MAX_BYTES) {
    throw new RangeError("ActivityKit token exceeds Blick's storage limit");
  }
  return Buffer.from(token);
}

function normalizeKey(key: Uint8Array): Buffer {
  if (!(key instanceof Uint8Array)) {
    throw new TypeError("ActivityKit token protection key must be a Uint8Array");
  }
  if (key.byteLength !== ACTIVITY_KIT_TOKEN_PROTECTION_KEY_BYTES) {
    throw new RangeError("ActivityKit token protection requires a 32-byte key");
  }
  return Buffer.from(key);
}

function normalizeContext(context: string): Buffer {
  if (typeof context !== "string") {
    throw new TypeError("ActivityKit token protection context must be a string");
  }
  const encoded = Buffer.from(context, "utf8");
  if (encoded.byteLength === 0 || encoded.byteLength > MAX_CONTEXT_BYTES) {
    throw new RangeError("ActivityKit token protection context is invalid");
  }
  return encoded;
}

function digestNormalizedToken(token: Uint8Array): ActivityKitTokenDigest {
  return createHash("sha256").update(token).digest("hex") as ActivityKitTokenDigest;
}

function digestBytes(digest: ActivityKitTokenDigest): Buffer {
  return Buffer.from(digest, "ascii");
}

function authenticatedData(context: Buffer, digest: ActivityKitTokenDigest): Buffer {
  const contextLength = Buffer.allocUnsafe(4);
  contextLength.writeUInt32BE(context.byteLength);
  return Buffer.concat([AAD_DOMAIN, digestBytes(digest), contextLength, context]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === "object";
}

function copiedBytes(
  value: unknown,
  expectedLength: number | undefined,
  maximumLength: number,
): Buffer {
  if (!(value instanceof Uint8Array)) throw new Error();
  if (expectedLength == null) {
    if (value.byteLength === 0 || value.byteLength > maximumLength) throw new Error();
  } else if (value.byteLength !== expectedLength) {
    throw new Error();
  }
  return Buffer.from(value);
}

function protectionFailure(): ActivityKitTokenProtectionError {
  return new ActivityKitTokenProtectionError();
}

/** Validates an encrypted representation and returns copies of every byte field. */
export function normalizeProtectedActivityKitToken(
  value: unknown,
): ProtectedActivityKitToken {
  try {
    const candidate = value;
    if (!isRecord(candidate)) throw new Error();
    if (
      candidate.version !== PROTECTION_VERSION ||
      candidate.algorithm !== PROTECTION_ALGORITHM ||
      typeof candidate.digest !== "string" ||
      !LOWERCASE_SHA256.test(candidate.digest)
    ) {
      throw new Error();
    }

    return Object.freeze({
      version: PROTECTION_VERSION,
      algorithm: PROTECTION_ALGORITHM,
      nonce: copiedBytes(
        candidate.nonce,
        ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
        ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
      ),
      authenticationTag: copiedBytes(
        candidate.authenticationTag,
        ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES,
        ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES,
      ),
      ciphertext: copiedBytes(
        candidate.ciphertext,
        undefined,
        BLICK_ACTIVITY_KIT_TOKEN_MAX_BYTES,
      ),
      digest: candidate.digest as ActivityKitTokenDigest,
    });
  } catch {
    throw protectionFailure();
  }
}

function digestsMatch(left: ActivityKitTokenDigest, right: ActivityKitTokenDigest): boolean {
  return timingSafeEqual(digestBytes(left), digestBytes(right));
}

export function createAes256GcmActivityKitTokenProtector(
  key: Uint8Array,
  options: Aes256GcmActivityKitTokenProtectorOptions = {},
): ActivityKitTokenProtector {
  const encryptionKey = normalizeKey(key);
  const randomBytes = options.randomBytes ?? nodeRandomBytes;
  // Cryptographic uniqueness comes from the CSPRNG. This bounded window additionally
  // detects a broken source that repeats recent output without retaining process state forever.
  const recentNonceIdentities = new Set<string>();

  const nextNonce = (): Buffer => {
    for (let attempt = 0; attempt < NONCE_ATTEMPTS; attempt += 1) {
      let candidate: Buffer;
      try {
        candidate = copiedBytes(
          randomBytes(ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES),
          ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
          ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
        );
      } catch {
        throw protectionFailure();
      }
      const identity = candidate.toString("hex");
      if (!recentNonceIdentities.has(identity)) {
        recentNonceIdentities.add(identity);
        if (recentNonceIdentities.size > RECENT_NONCE_IDENTITIES_MAX) {
          const oldest = recentNonceIdentities.values().next().value;
          if (oldest != null) recentNonceIdentities.delete(oldest);
        }
        return candidate;
      }
    }
    throw protectionFailure();
  };

  const digest = (token: Uint8Array): ActivityKitTokenDigest =>
    digestNormalizedToken(normalizeActivityKitToken(token));

  const protect = (
    token: Uint8Array,
    context: string,
  ): ProtectedActivityKitToken => {
    const plaintext = normalizeActivityKitToken(token);
    const normalizedContext = normalizeContext(context);
    const tokenDigest = digestNormalizedToken(plaintext);

    try {
      const nonce = nextNonce();
      const cipher = createCipheriv(NODE_CIPHER_ALGORITHM, encryptionKey, nonce, {
        authTagLength: ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES,
      });
      cipher.setAAD(authenticatedData(normalizedContext, tokenDigest));
      const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
      const authenticationTag = cipher.getAuthTag();
      return normalizeProtectedActivityKitToken({
        version: PROTECTION_VERSION,
        algorithm: PROTECTION_ALGORITHM,
        nonce,
        authenticationTag,
        ciphertext,
        digest: tokenDigest,
      });
    } catch {
      throw protectionFailure();
    }
  };

  const unprotect = (
    protectedToken: ProtectedActivityKitToken,
    context: string,
  ): Buffer => {
    try {
      const normalized = normalizeProtectedActivityKitToken(protectedToken);
      const normalizedContext = normalizeContext(context);
      const decipher = createDecipheriv(
        NODE_CIPHER_ALGORITHM,
        encryptionKey,
        normalized.nonce,
        { authTagLength: ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES },
      );
      decipher.setAAD(authenticatedData(normalizedContext, normalized.digest));
      decipher.setAuthTag(normalized.authenticationTag);
      const plaintext = normalizeActivityKitToken(
        Buffer.concat([decipher.update(normalized.ciphertext), decipher.final()]),
      );
      if (!digestsMatch(digestNormalizedToken(plaintext), normalized.digest)) {
        throw new Error();
      }
      return plaintext;
    } catch {
      throw protectionFailure();
    }
  };

  return Object.freeze({ protect, unprotect, digest });
}
