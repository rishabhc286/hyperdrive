/** Validation of renderer-supplied IPC payloads. Anything unexpected is rejected. */
import { isValidPageToken, PROVIDER_FILE_ID, UUID, type ListFilesRequest } from '../shared/storage';

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

/** `[{ accountId: uuid, parentId?: id | null, pageToken?: cursor | null }]`. */
export function parseListFilesPayload(channel: string, args: unknown[]): ListFilesRequest {
  const payload = singleObject(channel, args, ['accountId', 'parentId', 'pageToken']);
  const accountId = uuidField(channel, payload, 'accountId');
  const parentId = payload['parentId'];
  const pageToken = payload['pageToken'];
  if (parentId !== undefined && parentId !== null && (typeof parentId !== 'string' || !PROVIDER_FILE_ID.test(parentId))) {
    throw new InvalidIpcPayloadError(channel);
  }
  if (pageToken !== undefined && pageToken !== null && (typeof pageToken !== 'string' || !isValidPageToken(pageToken))) {
    throw new InvalidIpcPayloadError(channel);
  }
  return { accountId, parentId: parentId ?? null, pageToken: pageToken ?? null };
}

/** Exactly `[{ accountId: uuid, fileId: id }]`. */
export function parseFileMetadataPayload(channel: string, args: unknown[]): { accountId: string; fileId: string } {
  const payload = singleObject(channel, args, ['accountId', 'fileId']);
  const fileId = payload['fileId'];
  if (typeof fileId !== 'string' || !PROVIDER_FILE_ID.test(fileId)) throw new InvalidIpcPayloadError(channel);
  return { accountId: uuidField(channel, payload, 'accountId'), fileId };
}
