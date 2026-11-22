import type { HyperdriveApi } from '../../shared/backend-health';
import { AuthLayout, CheckingScreen, SigningInScreen, UnavailableScreen, WelcomeScreen } from './AuthScreens';
import { OnboardingScreen } from './OnboardingScreen';
import { Shell } from './Shell';
import { useAuthStatus } from './useAuthStatus';
import { useBackendHealth } from './useBackendHealth';

export interface AppProps {
  /** Preload bridge. Injected so tests can supply a mock. */
  readonly api: HyperdriveApi | undefined;
  /** Test hook: storage connection polling interval. */
  readonly pollIntervalMs?: number | undefined;
}

/** Root: routes between the auth states and the signed-in shell. */
export function App({ api, pollIntervalMs }: AppProps) {
  const auth = useAuthStatus(api?.auth);
  const { status: backend, recheck } = useBackendHealth(api);

  if (auth.phase === 'signed-in' && auth.user && api) {
    return (
      <Shell
        user={auth.user}
        backendStatus={backend}
        onRecheckBackend={recheck}
        onSignOut={() => void api.auth.signOut()}
        storage={api.storage}
        pollIntervalMs={pollIntervalMs}
      />
    );
  }

  return (
    <AuthLayout backendStatus={backend}>
      {renderAuthScreen()}
    </AuthLayout>
  );

  function renderAuthScreen() {
    switch (auth.phase) {
      case 'checking':
        return <CheckingScreen />;
      case 'unavailable':
        return <UnavailableScreen reason={auth.unavailableReason} onRetry={() => void api?.auth.retry()} />;
      case 'signing-in':
        return <SigningInScreen onCancel={() => void api?.auth.cancelSignIn()} />;
      case 'onboarding':
        return api && auth.user ? <OnboardingScreen api={api.auth} user={auth.user} /> : <CheckingScreen />;
      case 'signed-out':
      case 'session-expired':
      case 'signed-in': // signed-in without a user cannot happen; fall back to the welcome screen
        return (
          <WelcomeScreen
            expired={auth.phase === 'session-expired'}
            notice={auth.notice}
            persistence={auth.sessionPersistence}
            onContinue={() => void api?.auth.signInWithGoogle()}
          />
        );
    }
  }
}
