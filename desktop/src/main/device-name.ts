import { hostname } from 'node:os';

/**
 * A human-readable device label sent to the user's own backend (for a future "signed-in devices"
 * list). Restricted to the characters the backend accepts.
 */
export function deviceName(rawHostname: string = hostname()): string {
  const cleaned = rawHostname.replace(/[^\p{L}\p{N} ._()'-]/gu, '').trim().slice(0, 64);
  return cleaned.length > 0 ? cleaned : 'Windows PC';
}
