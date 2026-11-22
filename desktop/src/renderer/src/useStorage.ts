import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConnectedAccount, ConnectionStatus, StorageApi, StorageError } from '../../shared/storage';

export type AccountsState =
  | { readonly status: 'loading'; readonly accounts: readonly ConnectedAccount[] }
  | { readonly status: 'ready'; readonly accounts: readonly ConnectedAccount[] }
  | { readonly status: 'error'; readonly accounts: readonly ConnectedAccount[]; readonly error: StorageError };

/** Connected accounts for the signed-in user; `reload` re-fetches (latest request wins). */
export function useConnectedAccounts(storage: StorageApi) {
  const [state, setState] = useState<AccountsState>({ status: 'loading', accounts: [] });
  const seq = useRef(0);

  // State is only set after the await, so this is safe to start from an effect.
  const fetchAccounts = useCallback(async () => {
    const mine = ++seq.current;
    const result = await storage.listAccounts().catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== seq.current) return;
    setState((s) =>
      result.ok ? { status: 'ready', accounts: result.value } : { status: 'error', accounts: s.accounts, error: result.error }
    );
  }, [storage]);

  useEffect(() => {
    void fetchAccounts();
  }, [fetchAccounts]);

  const reload = useCallback(async () => {
    setState((s) => ({ status: 'loading', accounts: s.accounts }));
    await fetchAccounts();
  }, [fetchAccounts]);

  return { state, reload };
}

export type ConnectionFlow =
  | { readonly phase: 'idle' }
  | { readonly phase: 'starting' }
  | { readonly phase: 'waiting'; readonly transactionId: string; readonly browserOpened: boolean }
  | { readonly phase: 'cancelling'; readonly transactionId: string }
  | { readonly phase: 'completed'; readonly reconnected: boolean }
  | { readonly phase: 'failed'; readonly errorCode: string | null }
  | { readonly phase: 'cancelled' }
  | { readonly phase: 'expired' }
  /** We stopped checking; the server-side attempt may still complete or expire on its own. */
  | { readonly phase: 'stopped'; readonly reason: 'cancel-unconfirmed' | 'status-unavailable' }
  | { readonly phase: 'start-failed'; readonly error: StorageError };

export const POLL_INTERVAL_MS = 2000;
/** Hard upper bound on polling regardless of the server's expiry (the server TTL is 10 minutes). */
export const MAX_POLL_MS = 11 * 60_000;
const MAX_CONSECUTIVE_POLL_ERRORS = 3;

/**
 * Google Drive connection attempt: opens the browser via the main process, then polls this
 * attempt's status with a bounded interval and duration. Cancel asks the backend to cancel; if that
 * cannot be confirmed the UI says it only stopped waiting.
 */
export function useGoogleConnection(storage: StorageApi, onCompleted: () => void, pollIntervalMs = POLL_INTERVAL_MS) {
  const [flow, setFlow] = useState<ConnectionFlow>({ phase: 'idle' });
  const attempt = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onCompletedRef = useRef(onCompleted);
  useEffect(() => {
    onCompletedRef.current = onCompleted;
  }, [onCompleted]);

  const clearTimer = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };

  useEffect(
    () => () => {
      attempt.current += 1; // unmount: ignore anything still in flight
      clearTimer();
    },
    []
  );

  const finish = useCallback((status: ConnectionStatus): boolean => {
    switch (status.status) {
      case 'PENDING':
        return false;
      case 'COMPLETED':
        setFlow({ phase: 'completed', reconnected: status.reconnected === true });
        onCompletedRef.current();
        return true;
      case 'FAILED':
        setFlow({ phase: 'failed', errorCode: status.errorCode });
        return true;
      case 'CANCELLED':
        setFlow({ phase: 'cancelled' });
        return true;
      case 'EXPIRED':
        setFlow({ phase: 'expired' });
        return true;
    }
  }, []);

  const start = useCallback(async () => {
    clearTimer();
    const mine = ++attempt.current;
    setFlow({ phase: 'starting' });
    const started = await storage.beginGoogleConnection().catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== attempt.current) return;
    if (!started.ok) {
      setFlow({ phase: 'start-failed', error: started.error });
      return;
    }
    const { transactionId } = started.value;
    const deadline = Math.min(Date.parse(started.value.expiresAt) + 5_000, Date.now() + MAX_POLL_MS);
    let errors = 0;
    setFlow({ phase: 'waiting', transactionId, browserOpened: false });

    const poll = async () => {
      if (mine !== attempt.current) return;
      const result = await storage.getConnectionStatus({ transactionId }).catch(() => ({ ok: false as const, error: 'unknown' as const }));
      if (mine !== attempt.current) return;
      if (result.ok) {
        errors = 0;
        if (finish(result.value)) return;
        setFlow({ phase: 'waiting', transactionId, browserOpened: result.value.browserOpened });
      } else if (++errors >= MAX_CONSECUTIVE_POLL_ERRORS) {
        setFlow({ phase: 'stopped', reason: 'status-unavailable' });
        return;
      }
      if (Date.now() >= deadline) {
        setFlow({ phase: 'expired' });
        return;
      }
      timer.current = setTimeout(() => void poll(), pollIntervalMs);
    };
    timer.current = setTimeout(() => void poll(), pollIntervalMs);
  }, [storage, finish, pollIntervalMs]);

  const cancel = useCallback(async () => {
    if (flow.phase !== 'waiting') return;
    clearTimer();
    const mine = ++attempt.current;
    const { transactionId } = flow;
    setFlow({ phase: 'cancelling', transactionId });
    const result = await storage.cancelConnection({ transactionId }).catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== attempt.current) return;
    if (!result.ok || !finish(result.value)) {
      // Not confirmed by the server (or still pending): we only stopped waiting.
      setFlow({ phase: 'stopped', reason: 'cancel-unconfirmed' });
    }
  }, [flow, storage, finish]);

  const dismiss = useCallback(() => {
    clearTimer();
    attempt.current += 1;
    setFlow({ phase: 'idle' });
  }, []);

  return { flow, start, cancel, dismiss };
}
