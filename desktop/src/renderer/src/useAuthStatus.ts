import { useEffect, useState } from 'react';
import type { AuthApi, AuthStatus } from '../../shared/auth';

const CHECKING: AuthStatus = { phase: 'checking', user: null, sessionPersistence: 'memory-only' };
const NO_BRIDGE: AuthStatus = {
  phase: 'unavailable',
  user: null,
  sessionPersistence: 'memory-only',
  unavailableReason: 'misconfigured'
};

/** Mirrors the main process's auth status (pushed via IPC); never holds tokens. */
export function useAuthStatus(auth: AuthApi | undefined): AuthStatus {
  const [status, setStatus] = useState<AuthStatus>(auth ? CHECKING : NO_BRIDGE);

  useEffect(() => {
    if (!auth) return;
    let active = true;
    const unsubscribe = auth.onStatusChange((next) => {
      if (active) setStatus(next);
    });
    auth.getStatus().then(
      (current) => {
        if (active) setStatus(current);
      },
      () => {
        if (active) setStatus(NO_BRIDGE);
      }
    );
    return () => {
      active = false;
      unsubscribe();
    };
  }, [auth]);

  return status;
}
