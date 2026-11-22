/**
 * Authentication types shared by main, preload and renderer. Nothing here ever carries a token:
 * the renderer only learns *whether* someone is signed in and who, never how.
 */

export type AuthPhase =
  | 'checking'
  | 'signed-out'
  | 'signing-in'
  | 'onboarding'
  | 'signed-in'
  | 'session-expired'
  | 'unavailable';

export type UnavailableReason =
  | 'auth-disabled'
  | 'backend-unreachable'
  | 'backend-not-ready'
  | 'misconfigured';

/** One-off messages shown on the welcome screen after something happened. */
export type AuthNotice =
  | 'sign-in-cancelled'
  | 'sign-in-failed'
  | 'sign-in-timed-out'
  | 'signed-out'
  | 'signed-out-unconfirmed';

/** Safe user shape (mirrors the backend's /api/v1/me response). */
export interface SafeUser {
  readonly id: string;
  readonly username: string | null;
  readonly displayName: string | null;
  readonly onboardingComplete: boolean;
}

export interface AuthStatus {
  readonly phase: AuthPhase;
  readonly user: SafeUser | null;
  /** Whether the session survives an app restart (encrypted with the OS) or is memory-only. */
  readonly sessionPersistence: 'encrypted' | 'memory-only';
  readonly unavailableReason?: UnavailableReason;
  readonly notice?: AuthNotice;
}

export type OnboardingError =
  | 'invalid_username'
  | 'username_reserved'
  | 'username_taken'
  | 'onboarding_already_complete'
  | 'session-expired'
  | 'backend-unreachable'
  | 'rate-limited'
  | 'unknown';

export type OnboardingResult = { readonly ok: true } | { readonly ok: false; readonly error: OnboardingError };

export interface SignOutResult {
  /** False when the backend could not be reached; local credentials were cleared regardless. */
  readonly serverRevocationConfirmed: boolean;
}

export interface AuthApi {
  getStatus(): Promise<AuthStatus>;
  signInWithGoogle(): Promise<void>;
  cancelSignIn(): Promise<void>;
  completeOnboarding(input: { username: string }): Promise<OnboardingResult>;
  signOut(): Promise<SignOutResult>;
  /** Re-checks the backend and stored session (e.g. after the backend was started). */
  retry(): Promise<void>;
  /** Subscribes to status pushes from the main process. Returns an unsubscribe function. */
  onStatusChange(listener: (status: AuthStatus) => void): () => void;
}

export const AuthIpcChannel = {
  getStatus: 'hyperdrive:auth:get-status',
  signIn: 'hyperdrive:auth:sign-in',
  cancelSignIn: 'hyperdrive:auth:cancel-sign-in',
  completeOnboarding: 'hyperdrive:auth:complete-onboarding',
  signOut: 'hyperdrive:auth:sign-out',
  retry: 'hyperdrive:auth:retry',
  statusChanged: 'hyperdrive:auth:status-changed'
} as const;
