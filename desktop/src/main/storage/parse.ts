import type {
  AccountStatus,
  ConnectedAccount,
  ConnectionState,
  ConnectionStatus,
  FileKind,
  FilePage,
  ProviderAvailability,
  StorageFile
} from '../../shared/storage';
import {
  isStorageProvider,
  isValidContinuation,
  isValidDirectory,
  PROVIDER_FILE_ID,
  STORAGE_PROVIDERS,
  UUID
} from '../../shared/storage';

/**
 * Strict parsers for backend storage responses. Each copies only known fields, so anything
 * unexpected (including any token-like field a buggy backend might add) never reaches the renderer.
 */

export class StorageResponseError extends Error {
  constructor() {
    super('Backend storage response had an unexpected shape');
    this.name = 'StorageResponseError';
  }
}

type Json = Record<string, unknown>;

function object(value: unknown): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new StorageResponseError();
  return value as Json;
}

function str(v: Json, key: string): string {
  const value = v[key];
  if (typeof value !== 'string') throw new StorageResponseError();
  return value;
}

function optStr(v: Json, key: string, max = 2048): string | null {
  const value = v[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string' || value.length > max) throw new StorageResponseError();
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw new StorageResponseError();
  return value as T;
}

const ACCOUNT_STATUSES: readonly AccountStatus[] = ['CONNECTED', 'NEEDS_RECONNECT'];
const KINDS: readonly FileKind[] = ['FILE', 'FOLDER', 'SHORTCUT', 'PROVIDER_DOCUMENT'];
const CONNECTION_STATES: readonly ConnectionState[] = ['PENDING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'];
const ERROR_CODE = /^[a-z_]{1,64}$/;

export function parseAccount(value: unknown): ConnectedAccount {
  const v = object(value);
  const id = str(v, 'id');
  if (!UUID.test(id)) throw new StorageResponseError();
  const lastErrorCode = optStr(v, 'lastErrorCode', 64);
  const rootType = v['rootType'];
  return {
    id,
    provider: oneOf(v['provider'], STORAGE_PROVIDERS),
    displayName: optStr(v, 'displayName', 200),
    email: optStr(v, 'email', 320),
    status: oneOf(v['status'], ACCOUNT_STATUSES),
    rootType: rootType === null || rootType === undefined ? null : oneOf(rootType, ['USER', 'TEAM'] as const),
    contentReadGranted: v['contentReadGranted'] === true,
    connectedAt: str(v, 'connectedAt'),
    lastSuccessAt: optStr(v, 'lastSuccessAt', 64),
    lastErrorCode: lastErrorCode !== null && ERROR_CODE.test(lastErrorCode) ? lastErrorCode : null
  };
}

export function parseFile(value: unknown): StorageFile {
  const v = object(value);
  const providerFileId = str(v, 'providerFileId');
  if (!PROVIDER_FILE_ID.test(providerFileId)) throw new StorageResponseError();
  const size = v['sizeBytes'];
  if (size !== null && size !== undefined && (typeof size !== 'number' || !Number.isSafeInteger(size) || size < 0)) {
    throw new StorageResponseError();
  }
  const canDownload = v['canDownload'];
  if (typeof canDownload !== 'boolean') throw new StorageResponseError();
  const target = optStr(v, 'shortcutTargetId', 256);
  const locator = optStr(v, 'directoryLocator', 4096);
  return {
    providerFileId,
    name: optStr(v, 'name', 4096) ?? '',
    kind: oneOf(v['kind'], KINDS),
    mimeType: optStr(v, 'mimeType', 255),
    sizeBytes: typeof size === 'number' ? size : null,
    modifiedAt: optStr(v, 'modifiedAt', 64),
    canDownload,
    directoryLocator: locator !== null && isValidDirectory(locator) ? locator : null,
    providerRevision: optStr(v, 'providerRevision', 256),
    contentHash: optStr(v, 'contentHash', 256),
    contentHashAlgorithm: optStr(v, 'contentHashAlgorithm', 64),
    shortcutTargetId: target !== null && PROVIDER_FILE_ID.test(target) ? target : null,
    shortcutTargetMimeType: optStr(v, 'shortcutTargetMimeType', 255)
  };
}

export function parseFilePage(value: unknown): FilePage {
  const v = object(value);
  const items = v['items'];
  if (!Array.isArray(items)) throw new StorageResponseError();
  const continuation = optStr(v, 'continuation', 8192);
  if (continuation !== null && !isValidContinuation(continuation)) throw new StorageResponseError();
  return { items: items.map(parseFile), continuation };
}

export function parseProviders(value: unknown): ProviderAvailability[] {
  const items = object(value)['items'];
  if (!Array.isArray(items)) throw new StorageResponseError();
  return items
    .map((item) => object(item))
    .filter((item) => isStorageProvider(item['provider']))
    .map((item) => ({ provider: item['provider'] as ProviderAvailability['provider'], available: item['available'] === true }));
}

export function parseConnectionStatus(value: unknown): ConnectionStatus {
  const v = object(value);
  const transactionId = str(v, 'transactionId');
  const accountId = optStr(v, 'accountId', 36);
  const errorCode = optStr(v, 'errorCode', 64);
  const reconnected = v['reconnected'];
  const browserOpened = v['browserOpened'];
  if (!UUID.test(transactionId) || (accountId !== null && !UUID.test(accountId)) || typeof browserOpened !== 'boolean') {
    throw new StorageResponseError();
  }
  return {
    transactionId,
    provider: oneOf(v['provider'], STORAGE_PROVIDERS),
    status: oneOf(v['status'], CONNECTION_STATES),
    browserOpened,
    expiresAt: str(v, 'expiresAt'),
    accountId,
    reconnected: typeof reconnected === 'boolean' ? reconnected : null,
    errorCode: errorCode !== null && ERROR_CODE.test(errorCode) ? errorCode : null
  };
}
