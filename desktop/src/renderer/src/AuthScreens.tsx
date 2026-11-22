import type { ReactNode } from 'react';
import type { AuthNotice, UnavailableReason } from '../../shared/auth';
import { BackendIndicator, BrandMark } from './BackendIndicator';
import type { BackendStatus } from './useBackendHealth';

export function AuthLayout({ backendStatus, children }: { backendStatus: BackendStatus; children: ReactNode }) {
  return (
    <div className="auth-shell">
      <header className="topbar auth-topbar">
        <div className="brand">
          <BrandMark />
          <span className="brand-name">HyperDrive</span>
        </div>
        <div className="topbar-right">
          <span className="badge badge-dev">Development build</span>
          <BackendIndicator status={backendStatus} />
        </div>
      </header>
      <main className="auth-main">
        <div className="auth-card">{children}</div>
      </main>
    </div>
  );
}

export function CheckingScreen() {
  return (
    <section aria-labelledby="checking-title">
      <h1 id="checking-title">Checking your session…</h1>
      <p className="muted">One moment while HyperDrive checks whether you’re signed in.</p>
    </section>
  );
}

const NOTICES: Record<AuthNotice, { tone: 'info' | 'warn'; text: string }> = {
  'sign-in-cancelled': { tone: 'info', text: 'Sign-in was cancelled.' },
  'sign-in-failed': { tone: 'warn', text: 'Sign-in didn’t complete. Please try again.' },
  'sign-in-timed-out': { tone: 'warn', text: 'Sign-in timed out because the browser step wasn’t finished. Please try again.' },
  'signed-out': { tone: 'info', text: 'You’ve signed out.' },
  'signed-out-unconfirmed': {
    tone: 'warn',
    text: 'You’ve been signed out on this computer, but the backend couldn’t be reached to confirm the session was revoked there.'
  }
};

export function WelcomeScreen(props: {
  expired: boolean;
  notice: AuthNotice | undefined;
  persistence: 'encrypted' | 'memory-only';
  onContinue: () => void;
}) {
  const notice = props.notice ? NOTICES[props.notice] : null;
  return (
    <section aria-labelledby="welcome-title">
      <h1 id="welcome-title">Welcome to HyperDrive</h1>
      <p>
        HyperDrive will bring your cloud storage together in one place on this PC. Sign in with Google to
        create or open your HyperDrive account.
      </p>
      <p className="muted">
        Signing in only confirms who you are. It does not give HyperDrive access to your Google Drive files;
        connecting storage is a separate step.
      </p>
      {props.expired && (
        <p className="notice notice-warn" role="alert">
          Your session has expired. Please sign in again.
        </p>
      )}
      {notice && (
        <p className={`notice notice-${notice.tone}`} role={notice.tone === 'warn' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      )}
      <button type="button" className="button button-large" onClick={props.onContinue}>
        Continue with Google
      </button>
      {props.persistence === 'memory-only' && (
        <p className="muted small">
          Secure storage isn’t available on this system, so you’ll need to sign in again each time you start HyperDrive.
        </p>
      )}
    </section>
  );
}

export function SigningInScreen({ onCancel }: { onCancel: () => void }) {
  return (
    <section aria-labelledby="signing-in-title">
      <h1 id="signing-in-title">Continue in your browser</h1>
      <p>Finish signing in with Google in the browser window that just opened. This screen updates automatically.</p>
      <p className="muted small">If no browser window appeared, cancel and try again.</p>
      <button type="button" className="button button-secondary" onClick={onCancel}>
        Cancel sign-in
      </button>
    </section>
  );
}

const UNAVAILABLE: Record<UnavailableReason, { title: string; text: string }> = {
  'auth-disabled': {
    title: 'Sign-in isn’t enabled',
    text: 'The HyperDrive backend is running in health-only mode, so accounts and sign-in are turned off. Start it with the "auth" profile to sign in (see the README).'
  },
  'backend-unreachable': {
    title: 'Can’t reach HyperDrive',
    text: 'The HyperDrive backend isn’t responding. Make sure it’s running, then try again.'
  },
  'backend-not-ready': {
    title: 'HyperDrive isn’t ready',
    text: 'The backend is running but can’t reach its database yet. Start PostgreSQL, then try again.'
  },
  misconfigured: {
    title: 'HyperDrive is misconfigured',
    text: 'The backend address is not configured correctly. Check HYPERDRIVE_BACKEND_URL and restart HyperDrive.'
  }
};

export function UnavailableScreen({ reason, onRetry }: { reason: UnavailableReason | undefined; onRetry: () => void }) {
  const content = UNAVAILABLE[reason ?? 'backend-unreachable'];
  return (
    <section aria-labelledby="unavailable-title">
      <h1 id="unavailable-title">{content.title}</h1>
      <p>{content.text}</p>
      {reason !== 'misconfigured' && (
        <button type="button" className="button" onClick={onRetry}>
          Try again
        </button>
      )}
    </section>
  );
}
