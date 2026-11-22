import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  ConnectedAccount,
  ConnectionStatus,
  ProviderAvailability,
  StorageApi,
  StorageError,
  StorageProvider
} from '../../shared/storage';

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

/**
 * Which providers this HyperDrive server can connect. Unknown (null) until loaded or if the call
 * fails; the UI then still offers the action and reports the backend's answer.
 */
export function useProviderAvailability(storage: StorageApi): ReadonlyMap<StorageProvider, boolean> | null {
  const [available, setAvailable] = useState<ReadonlyMap<StorageProvider, boolean> | null>(null);
  useEffect(() => {
    let live = true;
    void storage
      .listProviders()
      .catch(() => ({ ok: false as const, error: 'unknown' as const }))
      .then((result) => {
        if (live && result.ok) {
          setAvailable(new Map(result.value.map((p: ProviderAvailability) => [p.provider, p.available])));
        }
      });
    return () => {
      live = false;
    };
  }, [storage]);
  return available;
}

export type ConnectionFlow =
  | { readonly phase: 'idle' }
  | { readonly phase: 'starting'; readonly provider: StorageProvider }
  | {
      readonly phase: 'waiting';
      readonly provider: StorageProvider;
      readonly transactionId: string;
      readonly browserOpened: boolean;
    }
  | { readonly phase: 'cancelling'; readonly provider: StorageProvider; readonly transactionId: string }
  | { readonly phase: 'completed'; readonly provider: StorageProvider; readonly reconnected: boolean }
  | { readonly phase: 'failed'; readonly provider: StorageProvider; readonly errorCode: string | null }
  | { readonly phase: 'cancelled'; readonly provider: StorageProvider }
  | { readonly phase: 'expired'; readonly provider: StorageProvider }
  /** We stopped checking; the server-side attempt may still complete or expire on its own. */
  | {
      readonly phase: 'stopped';
      readonly provider: StorageProvider;
      readonly reason: 'cancel-unconfirmed' | 'status-unavailable';
    }
  | { readonly phase: 'start-failed'; readonly provider: StorageProvider; readonly error: StorageError };

export const POLL_INTERVAL_MS = 2000;
/** Hard upper bound on polling regardless of the server's expiry (the server TTL is 10 minutes). */
export const MAX_POLL_MS = 11 * 60_000;
const MAX_CONSECUTIVE_POLL_ERRORS = 3;

/**
 * One storage connection attempt at a time (any provider): opens the browser via the main process,
 * then polls this attempt's status with a bounded interval and duration. Cancel asks the backend
 * to cancel; if that cannot be confirmed the UI says it only stopped waiting.
 */
export function useConnection(storage: StorageApi, onCompleted: () => void, pollIntervalMs = POLL_INTERVAL_MS) {
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

  const finish = useCallback((provider: StorageProvider, status: ConnectionStatus): boolean => {
    switch (status.status) {
      case 'PENDING':
        return false;
      case 'COMPLETED':
        setFlow({ phase: 'completed', provider, reconnected: status.reconnected === true });
        onCompletedRef.current();
        return true;
      case 'FAILED':
        setFlow({ phase: 'failed', provider, errorCode: status.errorCode });
        return true;
      case 'CANCELLED':
        setFlow({ phase: 'cancelled', provider });
        return true;
      case 'EXPIRED':
        setFlow({ phase: 'expired', provider });
        return true;
    }
  }, []);

  const start = useCallback(
    async (provider: StorageProvider) => {
      clearTimer();
      const mine = ++attempt.current;
      setFlow({ phase: 'starting', provider });
      const started = await storage
        .beginConnection({ provider })
        .catch(() => ({ ok: false as const, error: 'unknown' as const }));
      if (mine !== attempt.current) return;
      if (!started.ok) {
        setFlow({ phase: 'start-failed', provider, error: started.error });
        return;
      }
      const { transactionId } = started.value;
      const deadline = Math.min(Date.parse(started.value.expiresAt) + 5_000, Date.now() + MAX_POLL_MS);
      let errors = 0;
      setFlow({ phase: 'waiting', provider, transactionId, browserOpened: false });

      const poll = async () => {
        if (mine !== attempt.current) return;
        const result = await storage
          .getConnectionStatus({ transactionId })
          .catch(() => ({ ok: false as const, error: 'unknown' as const }));
        if (mine !== attempt.current) return;
        if (result.ok) {
          errors = 0;
          if (finish(provider, result.value)) return;
          setFlow({ phase: 'waiting', provider, transactionId, browserOpened: result.value.browserOpened });
        } else if (++errors >= MAX_CONSECUTIVE_POLL_ERRORS) {
          setFlow({ phase: 'stopped', provider, reason: 'status-unavailable' });
          return;
        }
        if (Date.now() >= deadline) {
          setFlow({ phase: 'expired', provider });
          return;
        }
        timer.current = setTimeout(() => void poll(), pollIntervalMs);
      };
      timer.current = setTimeout(() => void poll(), pollIntervalMs);
    },
    [storage, finish, pollIntervalMs]
  );

  const cancel = useCallback(async () => {
    if (flow.phase !== 'waiting') return;
    clearTimer();
    const mine = ++attempt.current;
    const { transactionId, provider } = flow;
    setFlow({ phase: 'cancelling', provider, transactionId });
    const result = await storage
      .cancelConnection({ transactionId })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== attempt.current) return;
    if (!result.ok || !finish(provider, result.value)) {
      // Not confirmed by the server (or still pending): we only stopped waiting.
      setFlow({ phase: 'stopped', provider, reason: 'cancel-unconfirmed' });
    }
  }, [flow, storage, finish]);

  const dismiss = useCallback(() => {
    clearTimer();
    attempt.current += 1;
    setFlow({ phase: 'idle' });
  }, []);

  return { flow, start, cancel, dismiss };
}
