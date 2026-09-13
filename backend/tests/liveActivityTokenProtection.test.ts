import { describe, expect, it } from "vitest";
import {
  ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES,
  ACTIVITY_KIT_TOKEN_PROTECTION_KEY_BYTES,
  ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
  ActivityKitTokenProtectionError,
  BLICK_ACTIVITY_KIT_TOKEN_MAX_BYTES,
  createAes256GcmActivityKitTokenProtector,
  normalizeActivityKitToken,
  normalizeProtectedActivityKitToken,
  type ProtectedActivityKitToken,
} from "../src/liveCommute/apple/tokenProtection.js";

const TOKEN = Uint8Array.of(1, 2, 3, 4, 5, 250);
const CONTEXT = "installation:synthetic/session:morning-commute";
const TOKEN_DIGEST = "c0de4abd9bb690afc49a4f6e9583b47c4e51abdd58c72484d4d12955285652be";

function syntheticKey(offset = 0): Uint8Array {
  return Uint8Array.from(
    { length: ACTIVITY_KIT_TOKEN_PROTECTION_KEY_BYTES },
    (_value, index) => (index + offset) % 256,
  );
}

function nonceSource(...fillBytes: number[]): {
  readonly source: (size: number) => Uint8Array;
  readonly requestedSizes: number[];
} {
  const requestedSizes: number[] = [];
  let index = 0;
  return {
    requestedSizes,
    source: (size) => {
      requestedSizes.push(size);
      const fill = fillBytes[index];
      index += 1;
      if (fill == null) throw new Error("synthetic nonce source exhausted");
      return new Uint8Array(size).fill(fill);
    },
  };
}

function mutableProtectedCopy(value: ProtectedActivityKitToken): {
  version: number;
  algorithm: string;
  nonce: Buffer;
  authenticationTag: Buffer;
  ciphertext: Buffer;
  digest: string;
} {
  return {
    version: value.version,
    algorithm: value.algorithm,
    nonce: Buffer.from(value.nonce),
    authenticationTag: Buffer.from(value.authenticationTag),
    ciphertext: Buffer.from(value.ciphertext),
    digest: value.digest,
  };
}

function asProtected(
  value: ReturnType<typeof mutableProtectedCopy>,
): ProtectedActivityKitToken {
  return value as unknown as ProtectedActivityKitToken;
}

function capturedProtectionFailure(action: () => unknown): {
  name: string;
  message: string;
  code: string;
  serialized: string;
} {
  let caught: unknown;
  try {
    action();
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(ActivityKitTokenProtectionError);
  const failure = caught as ActivityKitTokenProtectionError;
  return {
    name: failure.name,
    message: failure.message,
    code: failure.code,
    serialized: `${String(failure)} ${JSON.stringify(failure)}`,
  };
}

describe("ActivityKit token protection", () => {
  it("normalizes a non-empty raw byte token with a defensive copy", () => {
    const input = Uint8Array.of(7, 8, 9);
    const normalized = normalizeActivityKitToken(input);

    expect(Buffer.isBuffer(normalized)).toBe(true);
    expect(normalized).toEqual(Buffer.from([7, 8, 9]));
    input.fill(99);
    expect(normalized).toEqual(Buffer.from([7, 8, 9]));
    expect(() => normalizeActivityKitToken(new Uint8Array())).toThrow(
      "ActivityKit token must not be empty",
    );
    expect(() =>
      normalizeActivityKitToken(
        new Uint8Array(BLICK_ACTIVITY_KIT_TOKEN_MAX_BYTES + 1),
      ),
    ).toThrow("ActivityKit token exceeds Blick's storage limit");
  });

  it("requires an injected 32-byte AES-256 key", () => {
    expect(() => createAes256GcmActivityKitTokenProtector(new Uint8Array(31))).toThrow(
      "ActivityKit token protection requires a 32-byte key",
    );
    expect(() => createAes256GcmActivityKitTokenProtector(new Uint8Array(33))).toThrow(
      "ActivityKit token protection requires a 32-byte key",
    );
  });

  it("protects bytes with a fresh 12-byte nonce, a 16-byte tag, and no plaintext field", () => {
    const nonces = nonceSource(11, 12);
    const protector = createAes256GcmActivityKitTokenProtector(syntheticKey(), {
      randomBytes: nonces.source,
    });

    expect(nonces.requestedSizes).toEqual([]);
    const first = protector.protect(TOKEN, CONTEXT);
    const second = protector.protect(TOKEN, CONTEXT);

    expect(nonces.requestedSizes).toEqual([
      ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
      ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES,
    ]);
    expect(first).toMatchObject({
      version: 1,
      algorithm: "AES-256-GCM",
      digest: TOKEN_DIGEST,
    });
    expect(first.nonce).toHaveLength(ACTIVITY_KIT_TOKEN_PROTECTION_NONCE_BYTES);
    expect(first.authenticationTag).toHaveLength(
      ACTIVITY_KIT_TOKEN_PROTECTION_AUTH_TAG_BYTES,
    );
    expect(first.ciphertext).toHaveLength(TOKEN.byteLength);
    expect(first.ciphertext).not.toEqual(Buffer.from(TOKEN));
    expect(second.nonce).not.toEqual(first.nonce);
    expect(second.ciphertext).not.toEqual(first.ciphertext);
    expect(Object.keys(first).sort()).toEqual([
      "algorithm",
      "authenticationTag",
      "ciphertext",
      "digest",
      "nonce",
      "version",
    ]);
    expect(protector.unprotect(first, CONTEXT)).toEqual(Buffer.from(TOKEN));
    expect(protector.unprotect(second, CONTEXT)).toEqual(Buffer.from(TOKEN));
  });

  it("produces a deterministic lowercase SHA-256 comparison digest", () => {
    const protector = createAes256GcmActivityKitTokenProtector(syntheticKey());

    expect(protector.digest(TOKEN)).toBe(TOKEN_DIGEST);
    expect(protector.digest(Uint8Array.from(TOKEN))).toBe(TOKEN_DIGEST);
    expect(protector.digest(Uint8Array.of(...TOKEN, 0))).not.toBe(TOKEN_DIGEST);
    expect(protector.digest(TOKEN)).toMatch(/^[0-9a-f]{64}$/);
  });

  it("copies the key, plaintext input, protected byte fields, and decrypted output", () => {
    const key = syntheticKey();
    const token = Uint8Array.from(TOKEN);
    const nonces = nonceSource(21);
    const protector = createAes256GcmActivityKitTokenProtector(key, {
      randomBytes: nonces.source,
    });
    const protectedToken = protector.protect(token, CONTEXT);
    const normalizedProtected = normalizeProtectedActivityKitToken(protectedToken);

    expect(Object.isFrozen(normalizedProtected)).toBe(true);
    expect(normalizedProtected).not.toBe(protectedToken);
    expect(normalizedProtected.nonce).not.toBe(protectedToken.nonce);
    expect(normalizedProtected.authenticationTag).not.toBe(
      protectedToken.authenticationTag,
    );
    expect(normalizedProtected.ciphertext).not.toBe(protectedToken.ciphertext);

    key.fill(255);
    token.fill(0);
    protectedToken.nonce.fill(0);
    protectedToken.authenticationTag.fill(0);
    protectedToken.ciphertext.fill(0);

    const firstPlaintext = protector.unprotect(normalizedProtected, CONTEXT);
    expect(firstPlaintext).toEqual(Buffer.from(TOKEN));
    firstPlaintext.fill(0);
    expect(protector.unprotect(normalizedProtected, CONTEXT)).toEqual(Buffer.from(TOKEN));
  });

  it("binds ciphertext and digest to the required caller context", () => {
    const nonces = nonceSource(31);
    const protector = createAes256GcmActivityKitTokenProtector(syntheticKey(), {
      randomBytes: nonces.source,
    });
    const protectedToken = protector.protect(TOKEN, CONTEXT);

    expect(protector.unprotect(protectedToken, CONTEXT)).toEqual(Buffer.from(TOKEN));
    const wrongContext = capturedProtectionFailure(() =>
      protector.unprotect(protectedToken, `${CONTEXT}/other`),
    );
    expect(wrongContext).toMatchObject({
      name: "ActivityKitTokenProtectionError",
      message: "ActivityKit token protection failed",
      code: "ACTIVITY_KIT_TOKEN_PROTECTION_FAILED",
    });
    expect(() => protector.protect(TOKEN, "")).toThrow(
      "ActivityKit token protection context is invalid",
    );
  });

  it("refuses to reuse a nonce returned repeatedly by an injected source", () => {
    const protector = createAes256GcmActivityKitTokenProtector(syntheticKey(), {
      randomBytes: (size) => new Uint8Array(size).fill(41),
    });

    protector.protect(TOKEN, CONTEXT);
    expect(() => protector.protect(TOKEN, CONTEXT)).toThrow(
      new ActivityKitTokenProtectionError(),
    );
  });

  it("sanitizes nonce-source failures", () => {
    const protector = createAes256GcmActivityKitTokenProtector(syntheticKey(), {
      randomBytes: () => {
        throw new Error("source exposed a synthetic secret");
      },
    });

    const failure = capturedProtectionFailure(() => protector.protect(TOKEN, CONTEXT));
    expect(failure.serialized).not.toContain("synthetic secret");
  });

  it("collapses malformed, tampered, wrong-key, and wrong-context input to one error", () => {
    const nonces = nonceSource(51);
    const protector = createAes256GcmActivityKitTokenProtector(syntheticKey(), {
      randomBytes: nonces.source,
    });
    const protectedToken = protector.protect(TOKEN, CONTEXT);
    const cases: Array<() => unknown> = [];

    const alteredNonce = mutableProtectedCopy(protectedToken);
    alteredNonce.nonce[0] = alteredNonce.nonce[0]! ^ 1;
    cases.push(() => protector.unprotect(asProtected(alteredNonce), CONTEXT));

    const alteredTag = mutableProtectedCopy(protectedToken);
    alteredTag.authenticationTag[0] = alteredTag.authenticationTag[0]! ^ 1;
    cases.push(() => protector.unprotect(asProtected(alteredTag), CONTEXT));

    const alteredCiphertext = mutableProtectedCopy(protectedToken);
    alteredCiphertext.ciphertext[0] = alteredCiphertext.ciphertext[0]! ^ 1;
    cases.push(() => protector.unprotect(asProtected(alteredCiphertext), CONTEXT));

    const alteredDigest = mutableProtectedCopy(protectedToken);
    alteredDigest.digest = `${alteredDigest.digest[0] === "0" ? "1" : "0"}${alteredDigest.digest.slice(1)}`;
    cases.push(() => protector.unprotect(asProtected(alteredDigest), CONTEXT));

    const wrongVersion = mutableProtectedCopy(protectedToken);
    wrongVersion.version = 2;
    cases.push(() => protector.unprotect(asProtected(wrongVersion), CONTEXT));

    const wrongAlgorithm = mutableProtectedCopy(protectedToken);
    wrongAlgorithm.algorithm = "AES-128-GCM";
    cases.push(() => protector.unprotect(asProtected(wrongAlgorithm), CONTEXT));

    const shortTag = mutableProtectedCopy(protectedToken);
    shortTag.authenticationTag = shortTag.authenticationTag.subarray(1);
    cases.push(() => protector.unprotect(asProtected(shortTag), CONTEXT));

    const emptyCiphertext = mutableProtectedCopy(protectedToken);
    emptyCiphertext.ciphertext = Buffer.alloc(0);
    cases.push(() => protector.unprotect(asProtected(emptyCiphertext), CONTEXT));

    cases.push(() => protector.unprotect(protectedToken, `${CONTEXT}/wrong`));
    const wrongKeyProtector = createAes256GcmActivityKitTokenProtector(syntheticKey(1));
    cases.push(() => wrongKeyProtector.unprotect(protectedToken, CONTEXT));

    const failures = cases.map(capturedProtectionFailure);
    const publicShapes = failures.map(({ serialized: _serialized, ...shape }) => shape);
    expect(new Set(publicShapes.map((shape) => JSON.stringify(shape)))).toHaveLength(1);

    const secretStrings = [
      Buffer.from(TOKEN).toString("hex"),
      protectedToken.ciphertext.toString("hex"),
      protectedToken.digest,
    ];
    for (const failure of failures) {
      for (const secret of secretStrings) {
        expect(failure.serialized).not.toContain(secret);
      }
    }
  });
});
