import type {
  ConnectedAccount,
  ConnectionStarted,
  ConnectionStatus,
  FilePage,
  ListFilesRequest,
  ProviderAvailability,
  StorageError,
  StorageFile,
  StorageProvider,
  StorageResult
} from '../../shared/storage';
import { SessionExpiredError } from '../auth/auth-manager';
import {
  ApiError,
  BackendUnreachableError,
  PROVIDER_SEGMENT,
  RequestAbortedError,
  type BackendApi
} from '../auth/backend-api';

export interface StorageManagerDeps {
  readonly api: BackendApi | null;
  /** Runs a call with a valid HyperDrive access token (AuthManager.withAccessToken). */
  readonly withAccessToken: <T>(call: (accessToken: string) => Promise<T>) => Promise<T>;
  /** Opens a URL in the system browser. Only called with a validated storage authorize URL. */
  readonly openExternal: (url: string) => Promise<void>;
  /** Called after a disconnect attempt so delegated read credentials for that account are dropped. */
  readonly onAccountDisconnected?: (accountId: string) => Promise<void>;
}

/** Remember at most this many connection attempts started by this app run. */
const MAX_KNOWN_TRANSACTIONS = 20;

/**
 * Cloud-storage operations for the renderer. Runs in the main process: the HyperDrive access token
 * is attached here, provider tokens never exist on this machine, and every response is re-parsed so
 * only known fields cross the bridge.
 */
export class StorageManager {
  private readonly knownTransactions: string[] = [];
  private readonly inFlight = new Map<string, Set<AbortController>>();

  constructor(private readonly deps: StorageManagerDeps) {}

  listAccounts(): Promise<StorageResult<readonly ConnectedAccount[]>> {
    return this.run((api, token) => api.listAccounts(token));
  }

  listProviders(): Promise<StorageResult<readonly ProviderAvailability[]>> {
    return this.run((api, token) => api.listProviders(token));
  }

  async beginConnection(provider: StorageProvider): Promise<StorageResult<ConnectionStarted>> {
    const api = this.deps.api;
    if (!api) return { ok: false, error: 'backend-unreachable' };
    const created = await this.run((a, token) => a.createStorageTransaction(token, provider));
    if (!created.ok) return created;
    const { transactionId, authorizationUrl, expiresAt } = created.value;
    // The browser may only go to this backend's authorize endpoint for the provider that was asked for.
    if (!isTrustedStorageAuthorizeUrl(authorizationUrl, api.origin, provider)) {
      return { ok: false, error: 'unknown' };
    }
    this.remember(transactionId);
    try {
      await this.deps.openExternal(authorizationUrl);
    } catch {
      return { ok: false, error: 'unknown' };
    }
    return { ok: true, value: { transactionId, expiresAt } };
  }

  /** Only for attempts this app started; anything else is answered locally as not found. */
  getConnectionStatus(transactionId: string): Promise<StorageResult<ConnectionStatus>> {
    if (!this.knownTransactions.includes(transactionId)) return Promise.resolve({ ok: false, error: 'not-found' });
    return this.run((api, token) => api.storageTransactionStatus(token, transactionId));
  }

  cancelConnection(transactionId: string): Promise<StorageResult<ConnectionStatus>> {
    if (!this.knownTransactions.includes(transactionId)) return Promise.resolve({ ok: false, error: 'not-found' });
    return this.run((api, token) => api.cancelStorageTransaction(token, transactionId));
  }

  listFiles(request: ListFilesRequest): Promise<StorageResult<FilePage>> {
    return this.tracked(request.accountId, (api, token, signal) =>
      api.listFiles(token, request.accountId, request.directory ?? null, request.continuation ?? null, signal)
    );
  }

  getFileMetadata(accountId: string, fileId: string): Promise<StorageResult<StorageFile>> {
    return this.tracked(accountId, (api, token, signal) => api.getFile(token, accountId, fileId, signal));
  }

  /** Aborts this account's in-flight browsing requests, then removes the connection. */
  async disconnectAccount(accountId: string): Promise<StorageResult<null>> {
    for (const controller of this.inFlight.get(accountId) ?? []) controller.abort();
    this.inFlight.delete(accountId);
    const result = await this.run((api, token) => api.disconnectAccount(token, accountId));
    // Drop any delegated credential for this account in the agent, whatever the backend answered.
    await this.deps.onAccountDisconnected?.(accountId).catch(() => undefined);
    return result.ok ? { ok: true, value: null } : result;
  }

  /** Called on sign-out: forget attempts and stop in-flight requests. */
  reset(): void {
    this.knownTransactions.length = 0;
    for (const set of this.inFlight.values()) for (const controller of set) controller.abort();
    this.inFlight.clear();
  }

  private remember(transactionId: string): void {
    this.knownTransactions.push(transactionId);
    if (this.knownTransactions.length > MAX_KNOWN_TRANSACTIONS) this.knownTransactions.shift();
  }

  private async tracked<T>(
    accountId: string,
    call: (api: BackendApi, token: string, signal: AbortSignal) => Promise<T>
  ): Promise<StorageResult<T>> {
    const controller = new AbortController();
    let set = this.inFlight.get(accountId);
    if (!set) {
      set = new Set();
      this.inFlight.set(accountId, set);
    }
    set.add(controller);
    try {
      return await this.run((api, token) => call(api, token, controller.signal));
    } finally {
      set.delete(controller);
      if (set.size === 0 && this.inFlight.get(accountId) === set) this.inFlight.delete(accountId);
    }
  }

  private async run<T>(call: (api: BackendApi, token: string) => Promise<T>): Promise<StorageResult<T>> {
    const api = this.deps.api;
    if (!api) return { ok: false, error: 'backend-unreachable' };
    try {
      return { ok: true, value: await this.deps.withAccessToken((token) => call(api, token)) };
    } catch (error) {
      return { ok: false, error: storageError(error) };
    }
  }
}

export function storageError(error: unknown): StorageError {
  if (error instanceof SessionExpiredError) return 'session-expired';
  if (error instanceof RequestAbortedError) return 'cancelled';
  if (error instanceof BackendUnreachableError) return 'backend-unreachable';
  if (error instanceof ApiError) {
    if (error.code === 'account_needs_reconnect') return 'needs-reconnect';
    if (error.code === 'continuation_expired') return 'continuation-expired';
    if (error.code === 'provider_not_configured') return 'not-configured';
    if (error.code === 'provider_permission_denied') return 'permission-denied';
    switch (error.status) {
      case 400:
        return 'invalid-request';
      case 401:
        return 'session-expired';
      case 404:
        return 'not-found';
      case 409:
        return 'needs-reconnect';
      case 429:
        return 'rate-limited';
      case 502:
      case 503:
      case 504:
        return 'provider-unavailable';
    }
  }
  return 'unknown';
}

/** The browser may only be sent to the configured backend's own storage authorize endpoint. */
export function isTrustedStorageAuthorizeUrl(
  candidate: string,
  backendOrigin: string,
  provider: StorageProvider
): boolean {
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return false;
  }
  return (
    (url.protocol === 'http:' || url.protocol === 'https:') &&
    url.origin === backendOrigin &&
    url.username === '' &&
    url.password === '' &&
    url.pathname.startsWith(`/api/v1/storage-connections/${PROVIDER_SEGMENT[provider]}/authorize/`) &&
    /^\/api\/v1\/storage-connections\/(google|dropbox)\/authorize\/hds_[A-Za-z0-9_-]{43}$/.test(url.pathname) &&
    url.search === '' &&
    url.hash === ''
  );
}
