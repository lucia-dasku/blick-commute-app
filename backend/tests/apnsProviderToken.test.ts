import {
  generateKeyPairSync,
  verify as verifySignature,
  type KeyObject,
} from "node:crypto";
import { inspect } from "node:util";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApnsProviderTokenSigningError,
  createEs256ApnsProviderTokenSigner,
} from "../src/liveCommute/apple/apnsProviderToken.js";

const TEAM_ID = "TEAM123456";
const KEY_ID = "KEY1234567";
const ISSUED_AT = new Date("2026-09-12T12:34:56.789Z");

afterEach(() => {
  vi.restoreAllMocks();
});

function p256KeyPair(): { privateKey: KeyObject; publicKey: KeyObject } {
  return generateKeyPairSync("ec", { namedCurve: "prime256v1" });
}

function decodeJsonSegment(segment: string): unknown {
  return JSON.parse(Buffer.from(segment, "base64url").toString("utf8"));
}

describe("APNs ES256 provider-token signer", () => {
  it("creates the exact APNs JWT header and claims from an explicit instant", () => {
    const { privateKey } = p256KeyPair();
    const signer = createEs256ApnsProviderTokenSigner({
      teamId: TEAM_ID,
      keyId: KEY_ID,
      privateKey,
    });

    const token = signer.sign({ issuedAt: ISSUED_AT });
    const segments = token.revealForAuthorization().split(".");

    expect(segments).toHaveLength(3);
    expect(decodeJsonSegment(segments[0]!)).toEqual({ alg: "ES256", kid: KEY_ID });
    expect(decodeJsonSegment(segments[1]!)).toEqual({
      iss: TEAM_ID,
      iat: Math.floor(ISSUED_AT.getTime() / 1_000),
    });
    expect(token.issuedAt).toBe(Math.floor(ISSUED_AT.getTime() / 1_000));
    expect(segments.every((segment) => !/[+/=]/.test(segment))).toBe(true);
  });

  it("emits a 64-byte JOSE/P1363 ECDSA signature verifiable by the synthetic public key", () => {
    const { privateKey, publicKey } = p256KeyPair();
    const token = createEs256ApnsProviderTokenSigner({
      teamId: TEAM_ID,
      keyId: KEY_ID,
      privateKey,
    }).sign({ issuedAt: 1_789_123_456 });
    const [header, claims, encodedSignature] = token
      .revealForAuthorization()
      .split(".");
    const signature = Buffer.from(encodedSignature!, "base64url");

    expect(signature).toHaveLength(64);
    expect(
      verifySignature(
        "sha256",
        Buffer.from(`${header}.${claims}`, "ascii"),
        { key: publicKey, dsaEncoding: "ieee-p1363" },
        signature,
      ),
    ).toBe(true);

    const unrelated = p256KeyPair();
    expect(
      verifySignature(
        "sha256",
        Buffer.from(`${header}.${claims}`, "ascii"),
        { key: unrelated.publicKey, dsaEncoding: "ieee-p1363" },
        signature,
      ),
    ).toBe(false);
  });

  it("keeps the bearer token out of enumerable, JSON, string, and inspection surfaces", () => {
    const { privateKey } = p256KeyPair();
    const syntheticPrivateKeyPem = privateKey.export({
      format: "pem",
      type: "pkcs8",
    }).toString();
    const signer = createEs256ApnsProviderTokenSigner({
      teamId: TEAM_ID,
      keyId: KEY_ID,
      privateKey: syntheticPrivateKeyPem,
    });
    const token = signer.sign({ issuedAt: ISSUED_AT });
    const revealed = token.revealForAuthorization();

    expect(inspect(signer)).not.toContain(syntheticPrivateKeyPem);
    expect(Object.keys(token)).toEqual([]);
    expect(String(token)).toBe("[REDACTED APNs provider token]");
    expect(JSON.stringify({ token })).toBe(
      '{"token":"[REDACTED APNs provider token]"}',
    );
    expect(inspect(token)).toBe("[REDACTED APNs provider token]");
    for (const safeView of [String(token), JSON.stringify(token), inspect(token)]) {
      expect(safeView).not.toContain(revealed);
      expect(safeView).not.toContain(TEAM_ID);
      expect(safeView).not.toContain(KEY_ID);
    }
  });

  it("rejects public, non-EC, wrong-curve, and malformed signing keys with one sanitized error", () => {
    const p256 = p256KeyPair();
    const p384 = generateKeyPairSync("ec", { namedCurve: "secp384r1" });
    const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const malformedSecret = "synthetic-private-key-marker";
    const invalidKeys = [
      p256.publicKey,
      p384.privateKey,
      rsa.privateKey,
      malformedSecret,
    ];

    for (const privateKey of invalidKeys) {
      let failure: unknown;
      try {
        createEs256ApnsProviderTokenSigner({
          teamId: TEAM_ID,
          keyId: KEY_ID,
          privateKey,
        });
      } catch (error) {
        failure = error;
      }
      expect(failure).toBeInstanceOf(ApnsProviderTokenSigningError);
      expect(failure).toMatchObject({
        code: "APNS_PROVIDER_TOKEN_SIGNING_FAILED",
        message: "APNs provider token signing failed",
      });
      expect(inspect(failure)).not.toContain(malformedSecret);
    }
  });

  it("rejects invalid identifiers and issued-at values without consulting a clock or timer", () => {
    const { privateKey } = p256KeyPair();
    const timeoutSpy = vi.spyOn(globalThis, "setTimeout");
    const dateNowSpy = vi.spyOn(Date, "now");

    expect(() =>
      createEs256ApnsProviderTokenSigner({
        teamId: "short",
        keyId: KEY_ID,
        privateKey,
      }),
    ).toThrow(ApnsProviderTokenSigningError);
    expect(() =>
      createEs256ApnsProviderTokenSigner({
        teamId: TEAM_ID,
        keyId: "bad-key-id",
        privateKey,
      }),
    ).toThrow(ApnsProviderTokenSigningError);

    const signer = createEs256ApnsProviderTokenSigner({
      teamId: TEAM_ID,
      keyId: KEY_ID,
      privateKey,
    });
    for (const issuedAt of [new Date(Number.NaN), -1, 1.5, Number.POSITIVE_INFINITY]) {
      expect(() => signer.sign({ issuedAt })).toThrow(ApnsProviderTokenSigningError);
    }
    expect(timeoutSpy).not.toHaveBeenCalled();
    expect(dateNowSpy).not.toHaveBeenCalled();
  });
});
