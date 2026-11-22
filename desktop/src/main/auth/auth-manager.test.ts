// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AuthStatus, SafeUser } from '../../shared/auth';
import { AuthManager, isTrustedAuthorizeUrl, type AuthManagerDeps } from './auth-manager';
import {
  ApiError,
  BackendUnreachableError,
  type BackendApi,
  type CreateTransactionRequest,
  type Readiness,
  type TokenSet
} from './backend-api';
import { startLoopbackListener, type LoopbackHandle, type LoopbackOptions } from './loopback';
import { challengeFor } from './pkce';
import type { TokenStore } from './token-store';

const ORIGIN = 'http://127.0.0.1:18080';
const TX_ID = '0f8fad5b-d9cb-469f-a165-70867728950e';
const NEW_USER: SafeUser = { id: 'u1', username: null, displayName: 'Test', onboardingComplete: false };
const ONBOARDED: SafeUser = { ...NEW_USER, username: 'Test_User', onboardingComplete: true };

let counter = 0;
function token(prefix: 'hda_' | 'hdr_' | 'hdc_'): string {
  counter += 1;
  return prefix + String(counter).padStart(43, 'x');
}

function tokenSet(): TokenSet {
  return {
    accessToken: token('hda_'),
    accessTokenExpiresAt: Date.now() + 15 * 60_000,
    refreshToken: token('hdr_'),
    refreshTokenExpiresAt: Date.now() + 30 * 86_400_000
  };
}

/** In-memory fake of the backend; each method is a spy tests can override. */
class FakeBackend {
  origin = ORIGIN;
  lastTransaction: CreateTransactionRequest | null = null;
  issuedCode = token('hdc_');
  validAccess = new Set<string>();
  readiness = vi.fn(async (): Promise<Readiness> => ({ ready: true, auth: 'ENABLED' }));
  createTransaction = vi.fn(async (req: CreateTransactionRequest) => {
    this.lastTransaction = req;
    return { transactionId: TX_ID, authorizationUrl: `${ORIGIN}/api/v1/auth/desktop/authorize/${TX_ID}` };
  });
  exchange = vi.fn(async (_tx: string, code: string, verifier: string) => {
    if (code !== this.issuedCode || challengeFor(verifier) !== this.lastTransaction?.codeChallenge) {
      throw new ApiError(400, 'invalid_grant');
    }
    return { tokens: this.issue(), user: NEW_USER };
  });
  refresh = vi.fn(async (_refreshToken: string) => this.issue());
  logout = vi.fn(async (_access: string) => undefined);
  me = vi.fn(async (access: string) => {
    if (!this.validAccess.has(access)) throw new ApiError(401, 'unauthenticated');
    return ONBOARDED;
  });
  completeOnboarding = vi.fn(async (access: string, username: string) => {
    if (!this.validAccess.has(access)) throw new ApiError(401, 'unauthenticated');
    return { ...ONBOARDED, username };
  });

  issue(): TokenSet {
    const set = tokenSet();
    this.validAccess.add(set.accessToken);
    return set;
  }
}

class FakeStore implements TokenStore {
  persistence: 'encrypted' | 'memory-only' = 'encrypted';
  value: string | null = null;
  saves: string[] = [];
  async load() {
    return this.value;
  }
  async save(t: string) {
    this.value = t;
    this.saves.push(t);
  }
  async clear() {
    this.value = null;
  }
}

const openHandles: LoopbackHandle[] = [];
afterEach(() => openHandles.splice(0).forEach((h) => h.close()));

function setup(overrides: Partial<AuthManagerDeps> = {}) {
  const backend = new FakeBackend();
  const store = new FakeStore();
  const statuses: AuthStatus[] = [];
  let loopback: LoopbackHandle | null = null;
  const browser = vi.fn(async (_url: string) => {
    // Default "browser": completes Google sign-in and hits the loopback callback.
    const req = backend.lastTransaction!;
    await fetch(`http://127.0.0.1:${req.callbackPort}/callback?code=${backend.issuedCode}&state=${req.callbackState}`);
  });
  const manager = new AuthManager({
    api: backend as unknown as BackendApi,
    store,
    startLoopback: async (options: LoopbackOptions) => {
      loopback = await startLoopbackListener(options);
      openHandles.push(loopback);
      return loopback;
    },
    openExternal: browser,
    deviceName: 'Test PC',
    ...overrides
  });
  manager.onChange((s) => statuses.push(s));
  return { manager, backend, store, statuses, browser, loopback: () => loopback };
}

describe('startup', () => {
  it('reports auth disabled honestly', async () => {
    const { manager, backend } = setup();
    backend.readiness.mockResolvedValueOnce({ ready: true, auth: 'DISABLED' });
    await manager.initialize();
    expect(manager.getStatus()).toMatchObject({ phase: 'unavailable', unavailableReason: 'auth-disabled' });
  });

  it('reports an unreachable backend without crashing', async () => {
    const { manager, backend } = setup();
    backend.readiness.mockRejectedValueOnce(new BackendUnreachableError(false));
    await manager.initialize();
    expect(manager.getStatus()).toMatchObject({ phase: 'unavailable', unavailableReason: 'backend-unreachable' });
  });

  it('reports a backend whose database is down', async () => {
    const { manager, backend } = setup();
    backend.readiness.mockResolvedValueOnce({ ready: false, auth: 'ENABLED' });
    await manager.initialize();
    expect(manager.getStatus()).toMatchObject({ phase: 'unavailable', unavailableReason: 'backend-not-ready' });
  });

  it('is signed out without a stored session', async () => {
    const { manager } = setup();
    await manager.initialize();
    expect(manager.getStatus().phase).toBe('signed-out');
  });

  it('resumes a remembered session by refreshing, and persists the rotated token', async () => {
    const { manager, store, backend } = setup();
    store.value = token('hdr_');
    const old = store.value;
    await manager.initialize();
    expect(manager.getStatus()).toMatchObject({ phase: 'signed-in', user: ONBOARDED });
    expect(backend.refresh).toHaveBeenCalledWith(old);
    expect(store.value).not.toBe(old);
  });

  it('expires a remembered session the backend rejects and clears it', async () => {
    const { manager, store, backend } = setup();
    store.value = token('hdr_');
    backend.refresh.mockRejectedValueOnce(new ApiError(401, 'invalid_refresh_token'));
    await manager.initialize();
    expect(manager.getStatus().phase).toBe('session-expired');
    expect(store.value).toBeNull();
  });
});

describe('sign-in', () => {
  it('runs the full loopback + PKCE handoff and reaches onboarding', async () => {
    const { manager, backend, store, browser, loopback } = setup();
    await manager.signInWithGoogle();

    expect(browser).toHaveBeenCalledWith(`${ORIGIN}/api/v1/auth/desktop/authorize/${TX_ID}`);
    expect(backend.lastTransaction).toMatchObject({ codeChallengeMethod: 'S256', deviceName: 'Test PC' });
    expect(backend.lastTransaction!.callbackState).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(backend.exchange).toHaveBeenCalledTimes(1);
    expect(manager.getStatus()).toMatchObject({ phase: 'onboarding', user: NEW_USER });
    expect(store.saves).toHaveLength(1);
    expect(loopback()!.isListening()).toBe(false);
  });

  it('never exposes tokens in any status sent towards the renderer', async () => {
    const { manager, statuses } = setup();
    await manager.signInWithGoogle();
    await manager.completeOnboarding('Test_User');
    await manager.signOut();

    const serialized = JSON.stringify(statuses);
    expect(serialized).not.toMatch(/hd[acr]_/);
    expect(statuses.map((s) => s.phase)).toEqual(['signing-in', 'onboarding', 'signed-in', 'signed-out']);
  });

  it('rejects a callback with the wrong state and keeps waiting', async () => {
    const { manager, backend, loopback } = setup({
      openExternal: async () => {
        const req = backend.lastTransaction!;
        const res = await fetch(`http://127.0.0.1:${req.callbackPort}/callback?code=${backend.issuedCode}&state=wrong`);
        expect(res.status).toBe(400);
        setTimeout(() => manager.cancelSignIn(), 20);
      }
    });
    await manager.signInWithGoogle();

    expect(backend.exchange).not.toHaveBeenCalled();
    expect(manager.getStatus()).toMatchObject({ phase: 'signed-out', notice: 'sign-in-cancelled' });
    expect(loopback()!.isListening()).toBe(false);
  });

  it('times out when the browser step is never finished and closes the listener', async () => {
    const { manager, loopback } = setup({ openExternal: async () => undefined, signInTimeoutMs: 50 });
    await manager.signInWithGoogle();
    expect(manager.getStatus()).toMatchObject({ phase: 'signed-out', notice: 'sign-in-timed-out' });
    expect(loopback()!.isListening()).toBe(false);
  });

  it('reports a sign-in cancelled at Google', async () => {
    const { manager, backend } = setup({
      openExternal: async () => {
        const req = backend.lastTransaction!;
        await fetch(`http://127.0.0.1:${req.callbackPort}/callback?error=access_denied&state=${req.callbackState}`);
      }
    });
    await manager.signInWithGoogle();
    expect(manager.getStatus()).toMatchObject({ phase: 'signed-out', notice: 'sign-in-cancelled' });
    expect(backend.exchange).not.toHaveBeenCalled();
  });

  it('refuses to open an authorization URL outside the trusted backend', async () => {
    const { manager, backend, browser, loopback } = setup();
    backend.createTransaction.mockImplementationOnce(async (req) => {
      backend.lastTransaction = req;
      return { transactionId: TX_ID, authorizationUrl: `https://evil.example/api/v1/auth/desktop/authorize/${TX_ID}` };
    });
    await manager.signInWithGoogle();
    expect(browser).not.toHaveBeenCalled();
    expect(manager.getStatus()).toMatchObject({ phase: 'signed-out', notice: 'sign-in-failed' });
    expect(loopback()!.isListening()).toBe(false);
  });

  it('a second sign-in attempt supersedes the first', async () => {
    let firstPort = 0;
    const { manager, backend } = setup({
      openExternal: async () => {
        if (!firstPort) {
          firstPort = backend.lastTransaction!.callbackPort;
          return; // first attempt: browser never completes
        }
        const req = backend.lastTransaction!;
        await fetch(`http://127.0.0.1:${req.callbackPort}/callback?code=${backend.issuedCode}&state=${req.callbackState}`);
      }
    });
    const first = manager.signInWithGoogle();
    await vi.waitFor(() => expect(firstPort).toBeGreaterThan(0));
    await manager.signInWithGoogle();
    await first;

    expect(manager.getStatus().phase).toBe('onboarding');
    await expect(fetch(`http://127.0.0.1:${firstPort}/callback`)).rejects.toThrow();
  });
});

describe('session handling', () => {
  async function signedInManager(overrides: Partial<AuthManagerDeps> = {}) {
    const ctx = setup(overrides);
    await ctx.manager.signInWithGoogle();
    return ctx;
  }

  it('concurrent 401s trigger exactly one refresh', async () => {
    const ctx = await signedInManager();
    ctx.backend.validAccess.clear(); // every current access token is now rejected
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => (release = r));
    ctx.backend.refresh.mockImplementation(async () => {
      await gate;
      return ctx.backend.issue();
    });

    const calls = Array.from({ length: 5 }, () => ctx.manager.completeOnboarding('Test_User'));
    await vi.waitFor(() => expect(ctx.backend.refresh).toHaveBeenCalled());
    release();
    const results = await Promise.all(calls);

    expect(ctx.backend.refresh).toHaveBeenCalledTimes(1);
    expect(results.every((r) => r.ok)).toBe(true);
  });

  it('a rejected refresh expires the session and clears credentials', async () => {
    const ctx = await signedInManager();
    ctx.backend.validAccess.clear();
    ctx.backend.refresh.mockRejectedValueOnce(new ApiError(401, 'invalid_refresh_token'));

    const result = await ctx.manager.completeOnboarding('Test_User');

    expect(result).toEqual({ ok: false, error: 'session-expired' });
    expect(ctx.manager.getStatus().phase).toBe('session-expired');
    expect(ctx.store.value).toBeNull();
  });

  it('maps backend onboarding errors', async () => {
    const ctx = await signedInManager();
    ctx.backend.completeOnboarding.mockRejectedValueOnce(new ApiError(409, 'username_taken'));
    expect(await ctx.manager.completeOnboarding('Taken')).toEqual({ ok: false, error: 'username_taken' });
    expect(ctx.manager.getStatus().phase).toBe('onboarding');
  });

  it('validates usernames before calling the backend', async () => {
    const ctx = await signedInManager();
    expect(await ctx.manager.completeOnboarding('ab')).toEqual({ ok: false, error: 'invalid_username' });
    expect(await ctx.manager.completeOnboarding('admin')).toEqual({ ok: false, error: 'username_reserved' });
    expect(ctx.backend.completeOnboarding).not.toHaveBeenCalled();
  });

  it('sign-out revokes remotely and clears local credentials', async () => {
    const ctx = await signedInManager();
    const result = await ctx.manager.signOut();
    expect(result).toEqual({ serverRevocationConfirmed: true });
    expect(ctx.backend.logout).toHaveBeenCalledTimes(1);
    expect(ctx.store.value).toBeNull();
    expect(ctx.manager.getStatus()).toMatchObject({ phase: 'signed-out', notice: 'signed-out', user: null });
  });

  it('sign-out clears local credentials even when the backend is unreachable', async () => {
    const ctx = await signedInManager();
    ctx.backend.logout.mockRejectedValueOnce(new BackendUnreachableError(false));
    const result = await ctx.manager.signOut();
    expect(result).toEqual({ serverRevocationConfirmed: false });
    expect(ctx.store.value).toBeNull();
    expect(ctx.manager.getStatus()).toMatchObject({ phase: 'signed-out', notice: 'signed-out-unconfirmed' });
  });
});

describe('isTrustedAuthorizeUrl', () => {
  it.each([
    [`${ORIGIN}/api/v1/auth/desktop/authorize/${TX_ID}`, true],
    [`http://localhost:18080/api/v1/auth/desktop/authorize/${TX_ID}`, false],
    [`${ORIGIN}/api/v1/auth/desktop/authorize/${TX_ID}?next=https://evil.example`, false],
    [`${ORIGIN}/somewhere-else`, false],
    [`http://user:pw@127.0.0.1:18080/api/v1/auth/desktop/authorize/${TX_ID}`, false],
    ['file:///C:/Windows/System32/calc.exe', false],
    ['not a url', false]
  ])('%s → %s', (url, expected) => {
    expect(isTrustedAuthorizeUrl(url, ORIGIN)).toBe(expected);
  });
});
