import { useCallback, useEffect, useRef, useState } from 'react';
import type { BackendUnavailableReason, HyperdriveApi } from '../../shared/backend-health';

export type BackendStatus =
  | { readonly state: 'checking' }
  | { readonly state: 'connected' }
  | { readonly state: 'unavailable'; readonly reason: BackendUnavailableReason | 'no-bridge' };

export const HEALTH_POLL_INTERVAL_MS = 15_000;

/**
 * Tracks backend availability via the preload API: checks on mount, re-checks periodically,
 * and exposes a manual re-check. Failures are reported as state, never thrown.
 */
export function useBackendHealth(api: HyperdriveApi | undefined): {
  status: BackendStatus;
  recheck: () => void;
} {
  const [status, setStatus] = useState<BackendStatus>({ state: 'checking' });
  const mounted = useRef(true);

  const runCheck = useCallback(async () => {
    let next: BackendStatus;
    if (!api) {
      next = { state: 'unavailable', reason: 'no-bridge' };
    } else {
      try {
        next = await api.checkBackendHealth();
      } catch {
        next = { state: 'unavailable', reason: 'unreachable' };
      }
    }
    if (mounted.current) {
      setStatus(next);
    }
  }, [api]);

  useEffect(() => {
    mounted.current = true;
    void runCheck();
    const timer = setInterval(() => void runCheck(), HEALTH_POLL_INTERVAL_MS);
    return () => {
      mounted.current = false;
      clearInterval(timer);
    };
  }, [runCheck]);

  const recheck = useCallback(() => {
    setStatus({ state: 'checking' });
    void runCheck();
  }, [runCheck]);

  return { status, recheck };
}
