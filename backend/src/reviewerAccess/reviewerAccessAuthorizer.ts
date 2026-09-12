import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { isIP } from "node:net";

export interface ReviewerAccessAuthorizer {
  authorize(code: string): Promise<boolean>;
}

export interface ReviewerAccessClientFingerprinter {
  fingerprint(request: Request): string;
}

/**
 * Stateless reviewer-code validation. Only the SHA-256 digest is configured on the
 * server; the submitted code is never retained. Both operands passed to
 * `timingSafeEqual` are always 32-byte SHA-256 digests, avoiding both an early-exit
 * string comparison and its unequal-length exception.
 */
export class Sha256ReviewerAccessAuthorizer implements ReviewerAccessAuthorizer {
  private readonly configuredDigest: Buffer;

  constructor(configuredHashHex: string) {
    if (!/^[a-fA-F0-9]{64}$/.test(configuredHashHex)) {
      throw new Error("Reviewer access hash must be a SHA-256 digest");
    }
    this.configuredDigest = Buffer.from(configuredHashHex, "hex");
  }

  async authorize(code: string): Promise<boolean> {
    const submittedDigest = createHash("sha256").update(code.trim(), "utf8").digest();
    return timingSafeEqual(submittedDigest, this.configuredDigest);
  }
}

/** A domain-separated digest for short-lived rate-limit keys, never the raw code. */
export function reviewerAttemptFingerprint(code: string): string {
  return createHash("sha256")
    .update("blick-reviewer-access-attempt-v1\0", "utf8")
    .update(code.trim(), "utf8")
    .digest("hex");
}

const UNIDENTIFIED_CLIENT = "unidentified-client";
const MAX_FORWARDED_FOR_LENGTH = 256;

/**
 * Produces a non-reversible, domain-separated client key for the short-lived reviewer
 * rate-limit bucket. Vercel overwrites `x-vercel-forwarded-for`, so it is used only
 * when the deployment explicitly identifies itself as Vercel. Other forwarding
 * headers are intentionally ignored: in local or unknown hosting environments they
 * are client-controlled, and trusting them would make the client bucket trivial to
 * evade. Requests without a trustworthy address share the conservative fallback
 * bucket.
 */
export class HmacReviewerAccessClientFingerprinter implements ReviewerAccessClientFingerprinter {
  private readonly key: Buffer;

  constructor(configuredHashHex: string, private readonly trustVercelForwardedFor: boolean) {
    if (!/^[a-fA-F0-9]{64}$/.test(configuredHashHex)) {
      throw new Error("Reviewer access client-fingerprint key must be a SHA-256 digest");
    }
    this.key = Buffer.from(configuredHashHex, "hex");
  }

  fingerprint(request: Request): string {
    const clientIdentifier = this.resolveClientIdentifier(request);
    return createHmac("sha256", this.key)
      .update("blick-reviewer-access-client-v1\0", "utf8")
      .update(clientIdentifier, "utf8")
      .digest("hex");
  }

  private resolveClientIdentifier(request: Request): string {
    if (!this.trustVercelForwardedFor) return UNIDENTIFIED_CLIENT;

    const forwardedFor = request.headers.get("x-vercel-forwarded-for");
    if (!forwardedFor || forwardedFor.length > MAX_FORWARDED_FOR_LENGTH) {
      return UNIDENTIFIED_CLIENT;
    }

    const firstAddress = forwardedFor.split(",", 1)[0]?.trim();
    return firstAddress && isIP(firstAddress) !== 0
      ? firstAddress.toLowerCase()
      : UNIDENTIFIED_CLIENT;
  }
}
