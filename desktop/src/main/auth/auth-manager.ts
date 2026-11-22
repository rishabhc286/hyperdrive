import type {
  AuthNotice,
  AuthStatus,
  OnboardingError,
  OnboardingResult,
  SafeUser,
  SignOutResult,
  UnavailableReason
} from '../../shared/auth';
import { checkUsername } from '../../shared/username';
import {
  ApiError,
  BackendUnreachableError,
  type BackendApi,
  type TokenSet
} from './backend-api';
import { LoopbackAbortedError, LoopbackTimeoutError, type LoopbackHandle, type LoopbackOptions } from './loopback';
import { challengeFor, createCodeVerifier, createState } from './pkce';
import type { TokenStore } from './token-store';

/** Refresh the access token this long before it expires. */
const ACCESS_TOKEN_SKEW_MS = 30_000;
export const SIGN_IN_TIMEOUT_MS = 5 * 60_000;

export interface AuthManagerDeps {
  readonly api: BackendApi | null; // null when the backend URL is misconfigured
  readonly store: TokenStore;
  readonly startLoopback: (options: LoopbackOptions) => Promise<LoopbackHandle>;
  /** Opens a URL in the system browser. Only ever called with a validated authorize URL. */
  readonly openExternal: (url: string) => Promise<void>;
  readonly deviceName: string;
  readonly now?: () => number;
  readonly signInTimeoutMs?: number;
}

type RefreshOutcome = 'ok' | 'expired' | 'unreachable';

/** The HyperDrive session ended (refresh rejected); AuthManager already switched to session-expired. */
export class SessionExpiredError extends Error {
  constructor() {
    super('Session expired');
  }
}

/**
 * Owns the desktop session. Tokens live only here (main process): the access token in memory,
 * the refresh token in memory plus the TokenStore. The renderer receives AuthStatus only.
 */
export class AuthManager {
  private status: AuthStatus;
  private accessToken: { value: string; expiresAt: number } | null = null;
  private refreshToken: string | null = null;
  private refreshInFlight: Promise<RefreshOutcome> | null = null;
  private signInAttempt: AbortController | null = null;
  private readonly listeners = new Set<(status: AuthStatus) => void>();
  private readonly now: () => number;

  constructor(private readonly deps: AuthManagerDeps) {
    this.now = deps.now ?? Date.now;
    this.status = { phase: 'checking', user: null, sessionPersistence: deps.store.persistence };
  }

  getStatus(): AuthStatus {
    return this.status;
  }

  onChange(listener: (status: AuthStatus) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Startup (and "Try again"): check the backend, then resume a stored session if any. */
  async initialize(): Promise<void> {
    this.setStatus({ phase: 'checking', user: null });
    const api = this.deps.api;
    if (!api) {
      this.setUnavailable('misconfigured');
      return;
    }
    try {
      const readiness = await api.readiness();
      if (readiness.auth === 'DISABLED') {
        this.setUnavailable('auth-disabled');
        return;
      }
      if (!readiness.ready) {
        this.setUnavailable('backend-not-ready');
        return;
      }
    } catch {
      this.setUnavailable('backend-unreachable');
      return;
    }

    if (!this.refreshToken) {
      this.refreshToken = await this.deps.store.load().catch(() => null);
    }
    if (!this.refreshToken) {
      this.setStatus({ phase: 'signed-out', user: null });
      return;
    }
    try {
      const user = await this.withAccessToken((token) => api.me(token));
      this.setUserStatus(user);
    } catch (error) {
      if (error instanceof SessionExpiredError) return; // status already set
      this.setUnavailable('backend-unreachable');
    }
  }

  async signInWithGoogle(): Promise<void> {
    const api = this.deps.api;
    if (!api) {
      this.setUnavailable('misconfigured');
      return;
    }
    this.signInAttempt?.abort(); // A new attempt supersedes any previous one.
    const attempt = new AbortController();
    this.signInAttempt = attempt;
    this.setStatus({ phase: 'signing-in', user: null });

    const verifier = createCodeVerifier();
    const state = createState();
    let loopback: LoopbackHandle | null = null;
    try {
      loopback = await this.deps.startLoopback({
        expectedState: state,
        timeoutMs: this.deps.signInTimeoutMs ?? SIGN_IN_TIMEOUT_MS,
        signal: attempt.signal
      });
      const transaction = await api.createTransaction({
        codeChallenge: challengeFor(verifier),
        codeChallengeMethod: 'S256',
        callbackPort: loopback.port,
        callbackState: state,
        deviceName: this.deps.deviceName
      });
      if (!isTrustedAuthorizeUrl(transaction.authorizationUrl, api.origin)) {
        throw new Error('Backend returned an untrusted authorization URL');
      }
      if (attempt.signal.aborted) throw new LoopbackAbortedError();
      await this.deps.openExternal(transaction.authorizationUrl);

      const result = await loopback.result;
      if (result.type === 'error') {
        this.finishAttempt(attempt, result.error === 'access_denied' ? 'sign-in-cancelled' : 'sign-in-failed');
        return;
      }
      const { tokens, user } = await api.exchange(transaction.transactionId, result.code, verifier);
      if (this.signInAttempt !== attempt) return; // superseded meanwhile
      await this.establishSession(tokens);
      this.setUserStatus(user);
    } catch (error) {
      if (error instanceof LoopbackAbortedError) this.finishAttempt(attempt, 'sign-in-cancelled');
      else if (error instanceof LoopbackTimeoutError) this.finishAttempt(attempt, 'sign-in-timed-out');
      else if (error instanceof BackendUnreachableError && this.signInAttempt === attempt) {
        this.setUnavailable('backend-unreachable');
      } else this.finishAttempt(attempt, 'sign-in-failed');
    } finally {
      loopback?.close();
      if (this.signInAttempt === attempt) this.signInAttempt = null;
    }
  }

  cancelSignIn(): void {
    this.signInAttempt?.abort();
  }

  async completeOnboarding(username: string): Promise<OnboardingResult> {
    const api = this.deps.api;
    if (!api || this.status.phase !== 'onboarding') return { ok: false, error: 'unknown' };
    if (checkUsername(username) === 'reserved') return { ok: false, error: 'username_reserved' };
    if (checkUsername(username) !== null) return { ok: false, error: 'invalid_username' };
    try {
      const user = await this.withAccessToken((token) => api.completeOnboarding(token, username));
      this.setUserStatus(user);
      return { ok: true };
    } catch (error) {
      return { ok: false, error: onboardingError(error) };
    }
  }

  /** Revokes server-side when possible; always clears local credentials. */
  async signOut(): Promise<SignOutResult> {
    this.signInAttempt?.abort();
    const confirmed = await this.revokeRemotely();
    await this.clearCredentials();
    this.setStatus({ phase: 'signed-out', user: null, notice: confirmed ? 'signed-out' : 'signed-out-unconfirmed' });
    return { serverRevocationConfirmed: confirmed };
  }

  private async revokeRemotely(): Promise<boolean> {
    const api = this.deps.api;
    if (!api || (!this.accessToken && !this.refreshToken)) return true; // nothing to revoke
    try {
      await this.withAccessToken((token) => api.logout(token));
      return true;
    } catch (error) {
      // Already expired/revoked server-side counts as signed out there.
      return error instanceof SessionExpiredError;
    }
  }

  /**
   * Runs an authenticated call. Refreshes first if the access token is missing or about to expire,
   * and once more after a 401. Refreshes are single-flight, so parallel 401s cause one refresh.
   * Public for other main-process services (cloud storage); the token never leaves the main process.
   */
  async withAccessToken<T>(call: (accessToken: string) => Promise<T>): Promise<T> {
    const token = await this.currentAccessToken();
    try {
      return await call(token);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      if (this.accessToken?.value === token) this.accessToken = null;
      return call(await this.currentAccessToken());
    }
  }

  private async currentAccessToken(): Promise<string> {
    if (this.accessToken && this.accessToken.expiresAt - ACCESS_TOKEN_SKEW_MS > this.now()) {
      return this.accessToken.value;
    }
    const outcome = await this.refreshSession();
    if (outcome === 'unreachable') throw new BackendUnreachableError(false);
    if (outcome === 'expired' || !this.accessToken) throw new SessionExpiredError();
    return this.accessToken.value;
  }

  private refreshSession(): Promise<RefreshOutcome> {
    this.refreshInFlight ??= this.doRefresh().finally(() => {
      this.refreshInFlight = null;
    });
    return this.refreshInFlight;
  }

  private async doRefresh(): Promise<RefreshOutcome> {
    const api = this.deps.api;
    const refreshToken = this.refreshToken;
    if (!api || !refreshToken) {
      await this.expireSession();
      return 'expired';
    }
    try {
      await this.establishSession(await api.refresh(refreshToken));
      return 'ok';
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 400)) {
        await this.expireSession();
        return 'expired';
      }
      return 'unreachable';
    }
  }

  private async establishSession(tokens: TokenSet): Promise<void> {
    this.accessToken = { value: tokens.accessToken, expiresAt: tokens.accessTokenExpiresAt };
    this.refreshToken = tokens.refreshToken;
    try {
      await this.deps.store.save(tokens.refreshToken);
    } catch {
      // Could not persist: the session still works for this run.
    }
  }

  private async expireSession(): Promise<void> {
    await this.clearCredentials();
    this.setStatus({ phase: 'session-expired', user: null });
  }

  private async clearCredentials(): Promise<void> {
    this.accessToken = null;
    this.refreshToken = null;
    await this.deps.store.clear().catch(() => undefined);
  }

  private finishAttempt(attempt: AbortController, notice: AuthNotice): void {
    if (this.signInAttempt !== attempt) return; // a newer attempt owns the status
    this.setStatus({ phase: 'signed-out', user: null, notice });
  }

  private setUserStatus(user: SafeUser): void {
    this.setStatus({ phase: user.onboardingComplete ? 'signed-in' : 'onboarding', user });
  }

  private setUnavailable(reason: UnavailableReason): void {
    this.setStatus({ phase: 'unavailable', user: null, unavailableReason: reason });
  }

  private setStatus(next: Omit<AuthStatus, 'sessionPersistence'>): void {
    this.status = { ...next, sessionPersistence: this.deps.store.persistence };
    for (const listener of this.listeners) listener(this.status);
  }
}

/** The browser may only be sent to the configured backend's own authorize endpoint. */
export function isTrustedAuthorizeUrl(candidate: string, backendOrigin: string): boolean {
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
    /^\/api\/v1\/auth\/desktop\/authorize\/[0-9a-f-]{36}$/.test(url.pathname) &&
    url.search === '' &&
    url.hash === ''
  );
}

function onboardingError(error: unknown): OnboardingError {
  if (error instanceof SessionExpiredError) return 'session-expired';
  if (error instanceof BackendUnreachableError) return 'backend-unreachable';
  if (error instanceof ApiError) {
    if (error.status === 429) return 'rate-limited';
    switch (error.code) {
      case 'invalid_username':
      case 'username_reserved':
      case 'username_taken':
      case 'onboarding_already_complete':
        return error.code;
    }
  }
  return 'unknown';
}
