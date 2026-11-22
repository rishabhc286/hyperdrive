// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { SessionExpiredError } from '../auth/auth-manager';
import { ApiError, BackendApi, BackendUnreachableError } from '../auth/backend-api';
import { isTrustedStorageAuthorizeUrl, StorageManager, storageError } from './storage-manager';

const ORIGIN = 'http://127.0.0.1:18080';
const ACCOUNT = '6f1c2a8e-1b2c-4d3e-8f90-123456789abc';
const TX = '0f8fad5b-d9cb-469f-a165-70867728950e';
const TICKET_URL = `${ORIGIN}/api/v1/storage-connections/google/authorize/hds_${'a'.repeat(43)}`;

type Route = (url: URL, init: RequestInit) => Response | Promise<Response>;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function setup(route: Route) {
  const fetchImpl = vi.fn((url: URL, init: RequestInit) => Promise.resolve(route(url, init)));
  const api = new BackendApi(new URL(ORIGIN), fetchImpl, 2000);
  const openExternal = vi.fn(() => Promise.resolve());
  const manager = new StorageManager({
    api,
    withAccessToken: (call) => call('hda_' + 'x'.repeat(43)),
    openExternal
  });
  return { manager, fetchImpl, openExternal };
}

const account = {
  id: ACCOUNT,
  provider: 'GOOGLE_DRIVE',
  displayName: 'Drive Owner',
  email: 'owner@example.com',
  status: 'CONNECTED',
  grantedScopes: ['https://www.googleapis.com/auth/drive.readonly'],
  connectedAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z',
  lastSuccessAt: null,
  lastErrorCode: null
};

describe('StorageManager: connection start', () => {
  it('opens only the backend storage authorize URL and returns the transaction', async () => {
    const { manager, openExternal, fetchImpl } = setup(() =>
      json(201, { transactionId: TX, authorizationUrl: TICKET_URL, expiresAt: '2026-11-22T10:10:00Z' })
    );
    const result = await manager.beginConnection('GOOGLE_DRIVE');
    expect(result).toEqual({ ok: true, value: { transactionId: TX, expiresAt: '2026-11-22T10:10:00Z' } });
    expect(openExternal).toHaveBeenCalledWith(TICKET_URL);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url.pathname).toBe('/api/v1/storage-connections/google/transactions');
    expect(init.method).toBe('POST');
    expect(init.body).toBeNull();
  });

  it.each([
    ['another origin', `https://evil.example/api/v1/storage-connections/google/authorize/hds_${'a'.repeat(43)}`],
    ['the sign-in authorize path', `${ORIGIN}/api/v1/auth/desktop/authorize/${TX}`],
    ['a query string', `${TICKET_URL}?next=https://evil.example`],
    ['a malformed ticket', `${ORIGIN}/api/v1/storage-connections/google/authorize/hds_short`],
    ['Google directly', 'https://accounts.google.com/o/oauth2/v2/auth?client_id=x']
  ])('refuses to open %s', async (_label, authorizationUrl) => {
    const { manager, openExternal } = setup(() =>
      json(201, { transactionId: TX, authorizationUrl, expiresAt: '2026-11-22T10:10:00Z' })
    );
    expect(await manager.beginConnection('GOOGLE_DRIVE')).toEqual({ ok: false, error: 'unknown' });
    expect(openExternal).not.toHaveBeenCalled();
  });

  it('opens the Dropbox authorize URL only for a Dropbox request', async () => {
    const dropboxUrl = `${ORIGIN}/api/v1/storage-connections/dropbox/authorize/hds_${'b'.repeat(43)}`;
    const { manager, openExternal, fetchImpl } = setup(() =>
      json(201, { transactionId: TX, authorizationUrl: dropboxUrl, expiresAt: '2026-11-22T10:10:00Z' })
    );
    expect((await manager.beginConnection('DROPBOX')).ok).toBe(true);
    expect(fetchImpl.mock.calls[0]![0].pathname).toBe('/api/v1/storage-connections/dropbox/transactions');
    expect(openExternal).toHaveBeenCalledWith(dropboxUrl);

    // A Google request answered with a Dropbox URL (or vice versa) is refused.
    openExternal.mockClear();
    expect(await manager.beginConnection('GOOGLE_DRIVE')).toEqual({ ok: false, error: 'unknown' });
    expect(openExternal).not.toHaveBeenCalled();
  });

  it('reports an unconfigured provider distinctly', async () => {
    const { manager } = setup(() => json(503, { code: 'provider_not_configured' }));
    expect(await manager.beginConnection('DROPBOX')).toEqual({ ok: false, error: 'not-configured' });
  });

  it('validates storage authorize URLs strictly', () => {
    expect(isTrustedStorageAuthorizeUrl(TICKET_URL, ORIGIN, 'GOOGLE_DRIVE')).toBe(true);
    expect(isTrustedStorageAuthorizeUrl(TICKET_URL, 'http://127.0.0.1:18081', 'GOOGLE_DRIVE')).toBe(false);
    expect(isTrustedStorageAuthorizeUrl('not a url', ORIGIN, 'GOOGLE_DRIVE')).toBe(false);
  });
});

describe('StorageManager: transaction status', () => {
  it('does not ask the backend about attempts this app did not start', async () => {
    const { manager, fetchImpl } = setup(() => json(200, {}));
    expect(await manager.getConnectionStatus(TX)).toEqual({ ok: false, error: 'not-found' });
    expect(await manager.cancelConnection(TX)).toEqual({ ok: false, error: 'not-found' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('polls and cancels its own attempt, and forgets attempts on reset', async () => {
    const status = { transactionId: TX, provider: 'GOOGLE_DRIVE', status: 'PENDING', browserOpened: true,
      expiresAt: '2026-11-22T10:10:00Z', accountId: null, reconnected: null, errorCode: null };
    const { manager, fetchImpl } = setup((url) => {
      if (url.pathname.endsWith('/transactions')) {
        return json(201, { transactionId: TX, authorizationUrl: TICKET_URL, expiresAt: '2026-11-22T10:10:00Z' });
      }
      if (url.pathname.endsWith('/cancel')) return json(200, { ...status, status: 'CANCELLED', errorCode: 'cancelled' });
      return json(200, status);
    });
    await manager.beginConnection('GOOGLE_DRIVE');
    const polled = await manager.getConnectionStatus(TX);
    expect(polled).toEqual({ ok: true, value: { transactionId: TX, provider: 'GOOGLE_DRIVE', status: 'PENDING', browserOpened: true,
      expiresAt: '2026-11-22T10:10:00Z', accountId: null, reconnected: null, errorCode: null } });
    const cancelled = await manager.cancelConnection(TX);
    expect(cancelled.ok && cancelled.value.status).toBe('CANCELLED');
    expect(fetchImpl.mock.calls.at(-1)![1].method).toBe('POST');

    manager.reset();
    const calls = fetchImpl.mock.calls.length;
    expect(await manager.getConnectionStatus(TX)).toEqual({ ok: false, error: 'not-found' });
    expect(fetchImpl.mock.calls.length).toBe(calls);
  });
});

describe('StorageManager: accounts and files', () => {
  it('copies only known fields, so token-like extras never reach the renderer', async () => {
    const { manager } = setup((url) => {
      if (url.pathname === '/api/v1/accounts') {
        return json(200, { items: [{ ...account, accessToken: 'ya29.leak', refreshToken: '1//leak' }] });
      }
      return json(200, {
        items: [{ providerFileId: 'f1', name: 'a.pdf', kind: 'FILE', mimeType: 'application/pdf', sizeBytes: 5,
          modifiedAt: null, canDownload: true, shortcutTargetId: null, shortcutTargetMimeType: null, token: 'ya29.leak' }],
        continuation: null,
        accessToken: 'ya29.leak'
      });
    });
    const accounts = await manager.listAccounts();
    const files = await manager.listFiles({ accountId: ACCOUNT });
    expect(JSON.stringify(accounts)).not.toContain('leak');
    expect(JSON.stringify(files)).not.toContain('leak');
    expect(accounts.ok && accounts.value[0]!.email).toBe('owner@example.com');
  });

  it('sends directory and continuation as query parameters', async () => {
    const { manager, fetchImpl } = setup(() => json(200, { items: [], continuation: null }));
    await manager.listFiles({ accountId: ACCOUNT, directory: 'folder_1', continuation: 'hdk_page2' });
    const [url] = fetchImpl.mock.calls[0]!;
    expect(url.pathname).toBe(`/api/v1/accounts/${ACCOUNT}/files`);
    expect(url.searchParams.get('directory')).toBe('folder_1');
    expect(url.searchParams.get('continuation')).toBe('hdk_page2');
  });

  it('rejects malformed backend data instead of passing it on', async () => {
    const { manager } = setup(() => json(200, { items: [{ providerFileId: "bad'id", kind: 'FILE', canDownload: true }] }));
    expect(await manager.listFiles({ accountId: ACCOUNT })).toEqual({ ok: false, error: 'unknown' });
  });

  it('disconnect aborts in-flight browsing of that account', async () => {
    let release: (() => void) | null = null;
    const { manager } = setup((url, init) => {
      if (init.method === 'DELETE') return new Response(null, { status: 204 });
      return new Promise<Response>((resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        release = () => resolve(json(200, { items: [], continuation: null }));
      });
    });
    const listing = manager.listFiles({ accountId: ACCOUNT });
    await vi.waitFor(() => expect(release).not.toBeNull());
    expect(await manager.disconnectAccount(ACCOUNT)).toEqual({ ok: true, value: null });
    expect(await listing).toEqual({ ok: false, error: 'cancelled' });
  });

  it('maps backend failures to coarse error codes', () => {
    expect(storageError(new SessionExpiredError())).toBe('session-expired');
    expect(storageError(new BackendUnreachableError(true))).toBe('backend-unreachable');
    expect(storageError(new ApiError(409, 'account_needs_reconnect'))).toBe('needs-reconnect');
    expect(storageError(new ApiError(403, 'provider_permission_denied'))).toBe('permission-denied');
    expect(storageError(new ApiError(404, 'account_not_found'))).toBe('not-found');
    expect(storageError(new ApiError(429, 'provider_rate_limited'))).toBe('rate-limited');
    expect(storageError(new ApiError(502, 'provider_unavailable'))).toBe('provider-unavailable');
    expect(storageError(new ApiError(400, 'invalid_request'))).toBe('invalid-request');
    expect(storageError(new Error('boom'))).toBe('unknown');
  });

  it('reports a misconfigured backend as unreachable without throwing', async () => {
    const manager = new StorageManager({ api: null, withAccessToken: (c) => c('t'), openExternal: vi.fn() });
    expect(await manager.listAccounts()).toEqual({ ok: false, error: 'backend-unreachable' });
    expect(await manager.beginConnection('GOOGLE_DRIVE')).toEqual({ ok: false, error: 'backend-unreachable' });
  });
});
