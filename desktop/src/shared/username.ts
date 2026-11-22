/**
 * Client-side copy of the backend username rules, for instant feedback only.
 * The backend is authoritative (it re-validates and enforces uniqueness).
 */

export const USERNAME_MIN = 3;
export const USERNAME_MAX = 24;
const FORMAT = /^[A-Za-z0-9_]{3,24}$/;

export const RESERVED_USERNAMES: readonly string[] = [
  'admin',
  'administrator',
  'support',
  'system',
  'hyperdrive',
  'root',
  'security',
  'help'
];

export type UsernameProblem = 'too-short' | 'too-long' | 'invalid-characters' | 'reserved';

export function checkUsername(username: string): UsernameProblem | null {
  if (username.length < USERNAME_MIN) return 'too-short';
  if (username.length > USERNAME_MAX) return 'too-long';
  if (!FORMAT.test(username)) return 'invalid-characters';
  if (RESERVED_USERNAMES.includes(username.toLowerCase())) return 'reserved';
  return null;
}
