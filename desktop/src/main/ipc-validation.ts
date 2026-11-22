/** Validation of renderer-supplied IPC payloads. Anything unexpected is rejected. */
import {
  isStorageProvider,
  isValidContinuation,
  isValidDirectory,
  MAX_READ_BYTES,
  PROVIDER_FILE_ID,
  UUID,
  type ListFilesRequest,
  type StorageProvider
} from '../shared/storage';

export class InvalidIpcPayloadError extends Error {
  constructor(channel: string) {
    super(`Invalid payload for ${channel}`);
    this.name = 'InvalidIpcPayloadError';
  }
}

export function expectNoArgs(channel: string, args: unknown[]): void {
  if (args.length !== 0) throw new InvalidIpcPayloadError(channel);
}

/** Exactly `[{ username: string }]` with no other keys and a sane length. */
export function parseOnboardingPayload(channel: string, args: unknown[]): { username: string } {
  if (args.length !== 1) throw new InvalidIpcPayloadError(channel);
  const [payload] = args;
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
    throw new InvalidIpcPayloadError(channel);
  }
  const keys = Object.keys(payload);
  const username = (payload as Record<string, unknown>)['username'];
  if (keys.length !== 1 || keys[0] !== 'username' || typeof username !== 'string' || username.length > 64) {
    throw new InvalidIpcPayloadError(channel);
  }
  return { username };
}

// --- Cloud storage payloads -----------------------------------------------------------------

function singleObject(channel: string, args: unknown[], allowedKeys: readonly string[]): Record<string, unknown> {
  if (args.length !== 1) throw new InvalidIpcPayloadError(channel);
  const [payload] = args;
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
    throw new InvalidIpcPayloadError(channel);
  }
  if (Object.keys(payload).some((key) => !allowedKeys.includes(key))) throw new InvalidIpcPayloadError(channel);
  return payload as Record<string, unknown>;
}

function uuidField(channel: string, payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  if (typeof value !== 'string' || !UUID.test(value)) throw new InvalidIpcPayloadError(channel);
  return value;
}

/** Exactly `[{ transactionId: uuid }]`. */
export function parseTransactionPayload(channel: string, args: unknown[]): { transactionId: string } {
  return { transactionId: uuidField(channel, singleObject(channel, args, ['transactionId']), 'transactionId') };
}

/** Exactly `[{ accountId: uuid }]`. */
export function parseAccountPayload(channel: string, args: unknown[]): { accountId: string } {
  return { accountId: uuidField(channel, singleObject(channel, args, ['accountId']), 'accountId') };
}

/** Exactly `[{ provider: 'GOOGLE_DRIVE' | 'DROPBOX' }]`. */
export function parseBeginConnectionPayload(channel: string, args: unknown[]): { provider: StorageProvider } {
  const provider = singleObject(channel, args, ['provider'])['provider'];
  if (!isStorageProvider(provider)) throw new InvalidIpcPayloadError(channel);
  return { provider };
}

/**
 * `[{ accountId: uuid, directory?: locator | null, continuation?: token | null }]`. Locators are
 * provider-specific (Drive id or Dropbox path); the backend validates them per provider.
 */
export function parseListFilesPayload(channel: string, args: unknown[]): ListFilesRequest {
  const payload = singleObject(channel, args, ['accountId', 'directory', 'continuation']);
  const accountId = uuidField(channel, payload, 'accountId');
  const directory = payload['directory'];
  const continuation = payload['continuation'];
  if (directory !== undefined && directory !== null && (typeof directory !== 'string' || !isValidDirectory(directory))) {
    throw new InvalidIpcPayloadError(channel);
  }
  if (
    continuation !== undefined &&
    continuation !== null &&
    (typeof continuation !== 'string' || !isValidContinuation(continuation))
  ) {
    throw new InvalidIpcPayloadError(channel);
  }
  return { accountId, directory: directory ?? null, continuation: continuation ?? null };
}

/** Exactly `[{ accountId, fileId, offset, length }]` with integer bounds (length ≤ 4 MiB). */
export function parseVerifyReadPayload(
  channel: string,
  args: unknown[]
): { accountId: string; fileId: string; offset: number; length: number } {
  const payload = singleObject(channel, args, ['accountId', 'fileId', 'offset', 'length']);
  const fileId = payload['fileId'];
  const offset = payload['offset'];
  const length = payload['length'];
  if (
    typeof fileId !== 'string' ||
    !PROVIDER_FILE_ID.test(fileId) ||
    typeof offset !== 'number' ||
    !Number.isSafeInteger(offset) ||
    offset < 0 ||
    typeof length !== 'number' ||
    !Number.isSafeInteger(length) ||
    length < 1 ||
    length > MAX_READ_BYTES
  ) {
    throw new InvalidIpcPayloadError(channel);
  }
  return { accountId: uuidField(channel, payload, 'accountId'), fileId, offset, length };
}

/** Exactly `[{ accountId: uuid, fileId: id }]`. */
export function parseFileMetadataPayload(channel: string, args: unknown[]): { accountId: string; fileId: string } {
  const payload = singleObject(channel, args, ['accountId', 'fileId']);
  const fileId = payload['fileId'];
  if (typeof fileId !== 'string' || !PROVIDER_FILE_ID.test(fileId)) throw new InvalidIpcPayloadError(channel);
  return { accountId: uuidField(channel, payload, 'accountId'), fileId };
}
