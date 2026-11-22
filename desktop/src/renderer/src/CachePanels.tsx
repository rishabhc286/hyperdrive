import { useCallback, useEffect, useRef, useState } from 'react';
import type { CacheApi, CacheBypassReason, CacheDisabledReason, CacheError, CacheStatusView } from '../../shared/cache';
import { MAX_CACHE_BUDGET, MIN_CACHE_BUDGET } from '../../shared/cache';
import { formatSize } from './storage-format';

const MIB = 1024 * 1024;

export const DISABLED_MESSAGES: Record<CacheDisabledReason, string> = {
  'key-unavailable': 'The cache key hasn’t reached the local agent yet. Reads are served without caching.',
  'key-protection-unavailable':
    'Windows-protected key storage isn’t available on this PC, so HyperDrive doesn’t store file contents on disk. Reads still work, uncached.',
  'disabled-by-budget': 'The disk cache is turned off (limit set to 0). Reads are served without caching.',
  'agent-unavailable': 'The local agent isn’t running.',
  'signed-out': 'Sign in to use the cache.'
};

export const BYPASS_LABELS: Record<CacheBypassReason, string> = {
  provider_not_version_bound: 'Google Drive: reads can’t be pinned to a file version, so they’re never cached',
  version_unknown: 'No file version was known for the read',
  cache_key_unavailable: 'No cache key was available',
  cache_disabled: 'Cache turned off'
};

const ERRORS: Record<CacheError, string> = {
  'agent-unavailable': 'The local agent isn’t running, so cache information isn’t available.',
  'signed-out': 'Sign in to see the cache.',
  'invalid-request': 'That value isn’t valid.',
  unknown: 'The cache status couldn’t be read.'
};

function useCacheStatus(cache: CacheApi) {
  const [state, setState] = useState<{ status: 'loading' } | { status: 'ready'; view: CacheStatusView } | { status: 'error'; error: CacheError }>({
    status: 'loading'
  });
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    const result = await cache.getStatus().catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== seq.current) return;
    setState(result.ok ? { status: 'ready', view: result.value } : { status: 'error', error: result.error });
  }, [cache]);
  useEffect(() => {
    let active = true;
    void cache.getStatus().then(
      (result) => {
        if (active) setState(result.ok ? { status: 'ready', view: result.value } : { status: 'error', error: result.error });
      },
      () => {
        if (active) setState({ status: 'error', error: 'unknown' });
      }
    );
    return () => {
      active = false;
    };
  }, [cache]);
  return { state, setState, reload: load };
}

function CacheState({ view }: { view: CacheStatusView }) {
  return (
    <p data-testid="cache-state">
      Disk cache: {view.enabled ? 'On (encrypted)' : 'Off'}
      {view.disabledReason ? <span className="muted"> — {DISABLED_MESSAGES[view.disabledReason]}</span> : null}
    </p>
  );
}

/** Performance page: real, process-lifetime counters plus the persistent on-disk totals. */
export function CacheMetrics({ cache }: { cache: CacheApi }) {
  const { state, reload } = useCacheStatus(cache);
  if (state.status === 'loading') return <p className="muted">Loading cache status…</p>;
  if (state.status === 'error') {
    return (
      <div className="notice notice-warn" role="alert">
        {ERRORS[state.error]}{' '}
        <button type="button" className="button button-small" onClick={() => void reload()}>
          Try again
        </button>
      </div>
    );
  }
  const v = state.view;
  const c = v.counters;
  const bypasses = Object.entries(v.bypasses) as [CacheBypassReason, number][];
  return (
    <article className="card" aria-labelledby="cache-metrics-title">
      <h2 id="cache-metrics-title">Local chunk cache</h2>
      <CacheState view={v} />
      <h3 className="subheading">On disk now</h3>
      <dl className="facts small" aria-label="On disk now">
        <dt>Size limit</dt>
        <dd>{v.budgetBytes === 0 ? 'Off' : formatSize(v.budgetBytes)}</dd>
        <dt>Encrypted chunks on disk</dt>
        <dd>
          {formatSize(v.encryptedBytesOnDisk)} in {v.entryCount.toLocaleString('en-US')} chunk file
          {v.entryCount === 1 ? '' : 's'} (ciphertext, including 32 bytes per chunk)
        </dd>
        <dt>Being written</dt>
        <dd>{formatSize(v.tempBytes)}</dd>
        {v.overBudgetBytes > 0 && (
          <>
            <dt>Over limit (in use)</dt>
            <dd>{formatSize(v.overBudgetBytes)}, removed as soon as those chunks are no longer being read</dd>
          </>
        )}
      </dl>
      <h3 className="subheading">Since the local agent started</h3>
      <dl className="facts small" aria-label="Since the local agent started">
        <dt>Chunk hits</dt>
        <dd>{c.hits.toLocaleString('en-US')}</dd>
        <dt>Chunk misses</dt>
        <dd>{c.misses.toLocaleString('en-US')}</dd>
        <dt>Fetched but not stored</dt>
        <dd>{c.notAdmitted.toLocaleString('en-US')}</dd>
        <dt>Evictions</dt>
        <dd>{c.evictions.toLocaleString('en-US')}</dd>
        <dt>Corrupt chunks rejected</dt>
        <dd>{c.corruptions.toLocaleString('en-US')}</dd>
        <dt>Bytes requested</dt>
        <dd>{c.bytesRequested.toLocaleString('en-US')}</dd>
        <dt>Bytes fetched from providers</dt>
        <dd>{c.providerBytesFetched.toLocaleString('en-US')}</dd>
        <dt>Bytes served from cache</dt>
        <dd>{c.bytesServedFromCache.toLocaleString('en-US')}</dd>
      </dl>
      {bypasses.length > 0 && (
        <>
          <h3 className="subheading">Reads that bypassed the cache</h3>
          <ul className="small" aria-label="Cache bypasses">
            {bypasses.map(([reason, count]) => (
              <li key={reason}>
                {BYPASS_LABELS[reason]}: {count.toLocaleString('en-US')}
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="muted small">
        Google Drive reads are never cached: Drive can’t serve a byte range pinned to one file version. Dropbox reads are
        cached only when Dropbox confirms the exact version that was read. Fetched bytes can exceed requested bytes because
        cached reads fetch whole 4 MiB chunks. Counters reset when the agent restarts.
      </p>
      <button type="button" className="button button-secondary button-small" onClick={() => void reload()}>
        Refresh
      </button>
    </article>
  );
}

/** Settings: cache size limit and Clear cache (with confirmation). */
export function CacheSettingsPanel({ cache }: { cache: CacheApi }) {
  const { state, setState } = useCacheStatus(cache);
  const [draft, setDraft] = useState<string | null>(null);
  const [message, setMessage] = useState<{ kind: 'info' | 'warn'; text: string } | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  if (state.status === 'loading') return <p className="muted">Loading cache settings…</p>;
  if (state.status === 'error') {
    return (
      <div className="notice notice-warn" role="alert">
        {ERRORS[state.error]}
      </div>
    );
  }
  const view = state.view;
  const value = draft ?? String(Math.round(view.budgetBytes / MIB));
  const mib = /^\d{1,7}$/.test(value) ? Number(value) : NaN;
  const bytes = mib * MIB;
  const valid = Number.isSafeInteger(bytes) && (bytes === 0 || (bytes >= MIN_CACHE_BUDGET && bytes <= MAX_CACHE_BUDGET));

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    const result = await cache.setBudget({ budgetBytes: bytes }).catch(() => ({ ok: false as const, error: 'unknown' as const }));
    setBusy(false);
    if (result.ok) {
      setState({ status: 'ready', view: result.value });
      setDraft(null);
      setMessage({ kind: 'info', text: bytes === 0 ? 'Disk cache turned off.' : `Cache limit set to ${formatSize(bytes)}.` });
    } else {
      setMessage({ kind: 'warn', text: ERRORS[result.error] });
    }
  };

  const clear = async () => {
    if (busy) return;
    setBusy(true);
    const result = await cache.clear().catch(() => ({ ok: false as const, error: 'unknown' as const }));
    setBusy(false);
    setConfirming(false);
    if (!result.ok) {
      setMessage({ kind: 'warn', text: `The cache couldn’t be cleared. ${ERRORS[result.error]}` });
      return;
    }
    const { removed, failed } = result.value;
    setMessage(
      failed > 0
        ? { kind: 'warn', text: `Removed ${removed} cached chunk file(s); ${failed} couldn’t be deleted and will be retried.` }
        : { kind: 'info', text: `Cache cleared: removed ${removed} cached chunk file(s).` }
    );
    const refreshed = await cache.getStatus().catch(() => null);
    if (refreshed?.ok) setState({ status: 'ready', view: refreshed.value });
  };

  return (
    <article className="card" aria-labelledby="cache-settings-title">
      <h2 id="cache-settings-title">File cache</h2>
      <CacheState view={view} />
      <p className="muted small">
        Pieces of files read through HyperDrive can be kept on this PC, encrypted, so repeat reads don’t download them
        again. The cache isn’t offline access: you still need to be signed in with the account connected. Signing out
        deletes your cached files and the key that encrypts them.
      </p>
      <label className="field-label" htmlFor="cache-budget">
        Cache size limit (MiB; 0 turns it off, otherwise 256 – 1,048,576)
      </label>
      <input
        id="cache-budget"
        className="input"
        inputMode="numeric"
        value={value}
        onChange={(e) => setDraft(e.target.value.trim())}
        disabled={view.keyProtection === 'unavailable'}
      />
      {!valid && (
        <p className="field-error" role="alert">
          Enter 0, or a whole number of MiB from 256 to 1,048,576.
        </p>
      )}
      <div className="actions">
        <button type="button" className="button button-small" disabled={!valid || busy || draft === null} onClick={() => void save()}>
          Save limit
        </button>
        <button
          type="button"
          className="button button-secondary button-small"
          disabled={busy || view.keyProtection === 'unavailable'}
          onClick={() => setConfirming(true)}
        >
          Clear cache…
        </button>
      </div>
      {confirming && (
        <div className="confirm" role="group" aria-label="Confirm clearing the cache">
          <p className="small">
            Delete all of your cached file pieces on this PC ({formatSize(view.encryptedBytesOnDisk)})? Your files in Google
            Drive and Dropbox and your connected accounts aren’t affected. Deleting doesn’t securely erase the disk blocks; what
            they held was encrypted.
          </p>
          <div className="actions">
            <button type="button" className="button button-small button-danger" disabled={busy} onClick={() => void clear()}>
              {busy ? 'Clearing…' : 'Clear cache'}
            </button>
            <button type="button" className="button button-secondary button-small" disabled={busy} onClick={() => setConfirming(false)}>
              Keep cache
            </button>
          </div>
        </div>
      )}
      {message && (
        <div className={`notice ${message.kind === 'warn' ? 'notice-warn' : 'notice-info'}`} role={message.kind === 'warn' ? 'alert' : 'status'}>
          {message.text}
        </div>
      )}
    </article>
  );
}
