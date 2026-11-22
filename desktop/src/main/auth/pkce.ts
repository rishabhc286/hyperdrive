import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

/** 32 random bytes → 43-character base64url verifier (RFC 7636 §4.1). */
export function createCodeVerifier(): string {
  return randomBytes(32).toString('base64url');
}

/** S256 challenge: base64url(SHA-256(verifier)), no padding. */
export function challengeFor(verifier: string): string {
  return createHash('sha256').update(verifier, 'ascii').digest('base64url');
}

/** Random opaque value for the desktop callback state (distinct from Google's OAuth state). */
export function createState(): string {
  return randomBytes(32).toString('base64url');
}

/** Constant-time string comparison (false for different lengths, without early exit on content). */
export function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  if (left.length !== right.length) {
    // Compare against itself to keep timing independent of where the mismatch is.
    timingSafeEqual(left, left);
    return false;
  }
  return timingSafeEqual(left, right);
}
