import type { SafeUser } from '../../shared/auth';
import type {
  ConnectedAccount,
  ConnectionStatus,
  FilePage,
  ProviderAvailability,
  StorageFile,
  StorageProvider
} from '../../shared/storage';
import { parseAccount, parseConnectionStatus, parseFile, parseFilePage, parseProviders } from '../storage/parse';

/** URL segment per provider in /api/v1/storage-connections/{segment}/... */
export const PROVIDER_SEGMENT: Readonly<Record<StorageProvider, string>> = {
  GOOGLE_DRIVE: 'google',
  DROPBOX: 'dropbox'
};

export const DEFAULT_TIMEOUT_MS = 10_000;

/** The backend answered with an error status. `code` is the backend's stable error code. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined
  ) {
    super(`Backend responded ${status}${code ? ` (${code})` : ''}`);
    this.name = 'ApiError';
  }
}

/** The backend could not be reached (network failure or timeout). */
export class BackendUnreachableError extends Error {
  constructor(readonly timedOut: boolean) {
    super(timedOut ? 'Backend request timed out' : 'Backend unreachable');
    this.name = 'BackendUnreachableError';
  }
}

/** The request was aborted by the caller (for example, the account was disconnected meanwhile). */
export class RequestAbortedError extends Error {
  constructor() {
    super('Request aborted');
    this.name = 'RequestAbortedError';
  }
}

export class UnexpectedResponseError extends Error {
  constructor() {
    super('Backend response had an unexpected shape');
    this.name = 'UnexpectedResponseError';
  }
}

export interface Readiness {
  readonly ready: boolean;
  readonly auth: 'ENABLED' | 'DISABLED';
}

export interface TransactionCreated {
  readonly transactionId: string;
  readonly authorizationUrl: string;
}

export interface TokenSet {
  readonly accessToken: string;
  readonly accessTokenExpiresAt: number;
  readonly refreshToken: string;
  readonly refreshTokenExpiresAt: number;
}

export interface StorageTransactionCreated {
  readonly transactionId: string;
  readonly authorizationUrl: string;
  readonly expiresAt: string;
}

export interface CreateTransactionRequest {
  readonly codeChallenge: string;
  readonly codeChallengeMethod: 'S256';
  readonly callbackPort: number;
  readonly callbackState: string;
  readonly deviceName: string;
}

type FetchLike = (input: URL, init: RequestInit) => Promise<Response>;

/**
 * Typed client for the HyperDrive backend, used only by the main process. The base origin comes
 * from trusted main-process configuration; paths are fixed here. Error messages never contain
 * request bodies or tokens.
 */
export class BackendApi {
  constructor(
    private readonly baseUrl: URL,
    private readonly fetchImpl: FetchLike = fetch,
    private readonly timeoutMs: number = DEFAULT_TIMEOUT_MS
  ) {}

  get origin(): string {
    return this.baseUrl.origin;
  }

  async readiness(): Promise<Readiness> {
    const response = await this.send('GET', '/api/v1/ready', undefined, undefined, [200, 503]);
    const body = await this.json(response);
    const auth = body['auth'];
    if (auth !== 'ENABLED' && auth !== 'DISABLED') throw new UnexpectedResponseError();
    return { ready: response.status === 200 && body['status'] === 'READY', auth };
  }

  async createTransaction(request: CreateTransactionRequest): Promise<TransactionCreated> {
    const body = await this.json(await this.send('POST', '/api/v1/auth/desktop/transactions', request));
    const transactionId = body['transactionId'];
    const authorizationUrl = body['authorizationUrl'];
    if (typeof transactionId !== 'string' || typeof authorizationUrl !== 'string') {
      throw new UnexpectedResponseError();
    }
    return { transactionId, authorizationUrl };
  }

  async exchange(transactionId: string, code: string, codeVerifier: string): Promise<{ tokens: TokenSet; user: SafeUser }> {
    const body = await this.json(
      await this.send('POST', '/api/v1/auth/desktop/exchange', { transactionId, code, codeVerifier })
    );
    return { tokens: parseTokens(body), user: parseUser(body['user']) };
  }

  async refresh(refreshToken: string): Promise<TokenSet> {
    return parseTokens(await this.json(await this.send('POST', '/api/v1/auth/refresh', { refreshToken })));
  }

  async logout(accessToken: string): Promise<void> {
    await this.send('POST', '/api/v1/auth/logout', undefined, accessToken, [204]);
  }

  async me(accessToken: string): Promise<SafeUser> {
    return parseUser(await this.json(await this.send('GET', '/api/v1/me', undefined, accessToken)));
  }

  async completeOnboarding(accessToken: string, username: string): Promise<SafeUser> {
    return parseUser(
      await this.json(await this.send('POST', '/api/v1/me/onboarding', { username }, accessToken))
    );
  }

  // --- Cloud storage (Batch 3). Provider tokens never come back from these endpoints. ---

  async listProviders(accessToken: string): Promise<ProviderAvailability[]> {
    return parseProviders(await this.json(await this.send('GET', '/api/v1/storage-connections/providers', undefined, accessToken)));
  }

  async createStorageTransaction(accessToken: string, provider: StorageProvider): Promise<StorageTransactionCreated> {
    const path = `/api/v1/storage-connections/${PROVIDER_SEGMENT[provider]}/transactions`;
    const body = await this.json(await this.send('POST', path, undefined, accessToken, [201]));
    const { transactionId, authorizationUrl, expiresAt } = body;
    if (typeof transactionId !== 'string' || typeof authorizationUrl !== 'string' || typeof expiresAt !== 'string') {
      throw new UnexpectedResponseError();
    }
    return { transactionId, authorizationUrl, expiresAt };
  }

  async storageTransactionStatus(accessToken: string, transactionId: string): Promise<ConnectionStatus> {
    const path = `/api/v1/storage-connections/transactions/${encodeURIComponent(transactionId)}`;
    return parseConnectionStatus(await this.json(await this.send('GET', path, undefined, accessToken)));
  }

  async cancelStorageTransaction(accessToken: string, transactionId: string): Promise<ConnectionStatus> {
    const path = `/api/v1/storage-connections/transactions/${encodeURIComponent(transactionId)}/cancel`;
    return parseConnectionStatus(await this.json(await this.send('POST', path, undefined, accessToken, [200])));
  }

  async listAccounts(accessToken: string): Promise<ConnectedAccount[]> {
    const body = await this.json(await this.send('GET', '/api/v1/accounts', undefined, accessToken));
    const items = body['items'];
    if (!Array.isArray(items)) throw new UnexpectedResponseError();
    return items.map(parseAccount);
  }

  async listFiles(
    accessToken: string,
    accountId: string,
    directory: string | null,
    continuation: string | null,
    signal?: AbortSignal
  ): Promise<FilePage> {
    const query = new URLSearchParams();
    if (directory) query.set('directory', directory);
    if (continuation) query.set('continuation', continuation);
    const suffix = query.size > 0 ? `?${query.toString()}` : '';
    const path = `/api/v1/accounts/${encodeURIComponent(accountId)}/files${suffix}`;
    return parseFilePage(await this.json(await this.send('GET', path, undefined, accessToken, [200], signal)));
  }

  async getFile(accessToken: string, accountId: string, fileId: string, signal?: AbortSignal): Promise<StorageFile> {
    const path = `/api/v1/accounts/${encodeURIComponent(accountId)}/files/${encodeURIComponent(fileId)}`;
    return parseFile(await this.json(await this.send('GET', path, undefined, accessToken, [200], signal)));
  }

  async disconnectAccount(accessToken: string, accountId: string): Promise<void> {
    await this.send('DELETE', `/api/v1/accounts/${encodeURIComponent(accountId)}`, undefined, accessToken, [204]);
  }

  private async send(
    method: 'GET' | 'POST' | 'DELETE',
    path: string,
    body?: unknown,
    accessToken?: string,
    okStatuses: readonly number[] = [200, 201],
    signal?: AbortSignal
  ): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
    let response: Response;
    try {
      response = await this.fetchImpl(new URL(path, this.baseUrl), {
        method,
        headers,
        body: body === undefined ? null : JSON.stringify(body),
        redirect: 'error',
        cache: 'no-store',
        signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(this.timeoutMs)]) : AbortSignal.timeout(this.timeoutMs)
      });
    } catch (error) {
      if (signal?.aborted) throw new RequestAbortedError();
      throw new BackendUnreachableError(error instanceof DOMException && error.name === 'TimeoutError');
    }
    if (!okStatuses.includes(response.status)) {
      throw new ApiError(response.status, await errorCode(response));
    }
    return response;
  }

  private async json(response: Response): Promise<Record<string, unknown>> {
    try {
      const value: unknown = await response.json();
      if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
        return value as Record<string, unknown>;
      }
    } catch {
      // fall through
    }
    throw new UnexpectedResponseError();
  }
}

async function errorCode(response: Response): Promise<string | undefined> {
  try {
    const value: unknown = await response.json();
    if (typeof value === 'object' && value !== null) {
      const code = (value as Record<string, unknown>)['code'];
      return typeof code === 'string' && /^[a-z_]{1,64}$/.test(code) ? code : undefined;
    }
  } catch {
    // not JSON
  }
  return undefined;
}

function parseTokens(body: Record<string, unknown>): TokenSet {
  const accessToken = body['accessToken'];
  const refreshToken = body['refreshToken'];
  const accessExp = Date.parse(String(body['accessTokenExpiresAt']));
  const refreshExp = Date.parse(String(body['refreshTokenExpiresAt']));
  if (
    typeof accessToken !== 'string' ||
    !/^hda_[A-Za-z0-9_-]{43}$/.test(accessToken) ||
    typeof refreshToken !== 'string' ||
    !/^hdr_[A-Za-z0-9_-]{43}$/.test(refreshToken) ||
    Number.isNaN(accessExp) ||
    Number.isNaN(refreshExp)
  ) {
    throw new UnexpectedResponseError();
  }
  return { accessToken, accessTokenExpiresAt: accessExp, refreshToken, refreshTokenExpiresAt: refreshExp };
}

/** Copies only the known safe fields (drops anything unexpected). */
export function parseUser(value: unknown): SafeUser {
  if (typeof value !== 'object' || value === null) throw new UnexpectedResponseError();
  const v = value as Record<string, unknown>;
  const { id, username, displayName, onboardingComplete } = v;
  if (
    typeof id !== 'string' ||
    (username !== null && typeof username !== 'string') ||
    (displayName !== null && displayName !== undefined && typeof displayName !== 'string') ||
    typeof onboardingComplete !== 'boolean'
  ) {
    throw new UnexpectedResponseError();
  }
  return {
    id,
    username,
    displayName: typeof displayName === 'string' ? displayName : null,
    onboardingComplete
  };
}
