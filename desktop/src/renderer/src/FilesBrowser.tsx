import { useEffect, useRef, useState } from 'react';
import type { ConnectedAccount, StorageApi, StorageError, StorageFile } from '../../shared/storage';
import { ProviderIcon } from './ProviderIcon';
import {
  accountLabel,
  accountTitle,
  formatDate,
  formatSize,
  kindLabel,
  providerName,
  storageErrorMessage
} from './storage-format';
import type { AccountsState } from './useStorage';

export interface FilesBrowserProps {
  readonly storage: StorageApi;
  readonly accounts: AccountsState;
  readonly onReloadAccounts: () => void;
  readonly onOpenAccounts: () => void;
}

/** A provider directory inside one account. `locator` null = that account's root. */
interface Crumb {
  readonly locator: string | null;
  readonly name: string;
}

/**
 * Navigation location. "All storage" is a virtual node listing accounts; it is never sent to any
 * provider API. Inside an account, `path` holds real provider directory locators.
 */
type Location = { readonly kind: 'all' } | { readonly kind: 'account'; readonly accountId: string; readonly path: readonly Crumb[] };

type Listing =
  | { readonly status: 'loading' }
  | {
      readonly status: 'ready';
      readonly items: readonly StorageFile[];
      readonly continuation: string | null;
      readonly loadingMore: boolean;
      readonly moreError: StorageError | null;
    }
  | { readonly status: 'error'; readonly error: StorageError };

/** A listing tagged with the request it answers; any other key renders as "loading". */
type KeyedListing = { readonly key: string; readonly listing: Listing };

const ALL: Location = { kind: 'all' };

/**
 * Read-only browser across connected accounts. Every request carries a sequence number and a key
 * (account + directory path + refresh count); a response for an older account, folder or refresh
 * is dropped, so rapid navigation or a disconnect can never show the wrong listing. Pagination
 * state lives inside the keyed listing, so it can't leak between accounts or providers.
 */
export function FilesBrowser({ storage, accounts, onReloadAccounts, onOpenAccounts }: FilesBrowserProps) {
  const list = accounts.accounts;
  const [requested, setRequested] = useState<Location>(ALL);
  const [keyed, setKeyed] = useState<KeyedListing | null>(null);
  const [refreshCount, setRefreshCount] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const seq = useRef(0);

  // A disconnected (or unknown) account falls back to All storage; its listing is never shown again.
  const account = requested.kind === 'account' ? (list.find((a) => a.id === requested.accountId) ?? null) : null;
  const location: Location = account ? requested : ALL;
  const path: readonly Crumb[] = location.kind === 'account' ? location.path : [];
  const folder = path[path.length - 1] ?? null;
  const accountId = account?.id ?? null;
  const needsReconnect = account?.status === 'NEEDS_RECONNECT';
  const requestKey = `${accountId ?? ''}|${path.map((c) => c.locator ?? '').join('\u0000')}|${refreshCount}`;
  const listing: Listing = needsReconnect
    ? { status: 'error', error: 'needs-reconnect' }
    : keyed?.key === requestKey
      ? keyed.listing
      : { status: 'loading' };

  useEffect(() => {
    const mine = ++seq.current; // also invalidates anything in flight when leaving an account
    if (!accountId || needsReconnect) return;
    void storage
      .listFiles({ accountId, directory: folder?.locator ?? null })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }))
      .then((result) => {
        if (mine !== seq.current) return; // stale: the user moved on
        setKeyed({
          key: requestKey,
          listing: result.ok
            ? { status: 'ready', items: result.value.items, continuation: result.value.continuation, loadingMore: false, moreError: null }
            : { status: 'error', error: result.error }
        });
        if (!result.ok && result.error === 'needs-reconnect') onReloadAccounts();
      });
  }, [accountId, needsReconnect, folder?.locator, requestKey, storage, onReloadAccounts]);

  const refresh = () => {
    setNotice(null);
    setRefreshCount((n) => n + 1);
    if (!accountId) onReloadAccounts();
  };

  const loadMore = async () => {
    if (!accountId || listing.status !== 'ready' || !listing.continuation || listing.loadingMore) return;
    const mine = ++seq.current;
    const key = requestKey;
    const continuation = listing.continuation;
    setKeyed({ key, listing: { ...listing, loadingMore: true, moreError: null } });
    const result = await storage
      .listFiles({ accountId, directory: folder?.locator ?? null, continuation })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== seq.current) return;
    setKeyed((current) => {
      if (!current || current.key !== key || current.listing.status !== 'ready') return current;
      const prev = current.listing;
      return {
        key,
        listing: result.ok
          ? {
              status: 'ready',
              items: [...prev.items, ...result.value.items],
              continuation: result.value.continuation,
              loadingMore: false,
              moreError: null
            }
          : { ...prev, loadingMore: false, moreError: result.error }
      };
    });
  };

  const openAccount = (target: ConnectedAccount) => {
    setNotice(null);
    setRequested({ kind: 'account', accountId: target.id, path: [{ locator: null, name: accountTitle(target) }] });
  };

  const goTo = (index: number) => {
    setNotice(null);
    if (index < 0 || location.kind === 'all') {
      setRequested(ALL);
      return;
    }
    setRequested({ ...location, path: location.path.slice(0, index + 1) });
  };

  const activate = (file: StorageFile) => {
    switch (file.kind) {
      case 'FOLDER':
        if (location.kind === 'account' && file.directoryLocator) {
          setNotice(null);
          setRequested({ ...location, path: [...location.path, { locator: file.directoryLocator, name: file.name || 'Untitled folder' }] });
        } else {
          setNotice(`“${file.name}” can’t be opened: the provider gave no way to list it.`);
        }
        return;
      case 'SHORTCUT':
        setNotice(`“${file.name}” is a ${kindLabel(file).toLowerCase()}. Following shortcuts and links isn’t implemented yet.`);
        return;
      case 'PROVIDER_DOCUMENT':
        setNotice(
          `“${file.name}” is a ${kindLabel(file)} item. It has no downloadable file of its own, and opening or exporting it isn’t implemented yet.`
        );
        return;
      case 'FILE':
        setNotice(`Opening “${file.name}” isn’t implemented yet. This build only shows file details.`);
        return;
    }
  };

  if (list.length === 0) {
    return (
      <section aria-labelledby="files-title">
        <h1 id="files-title">Files</h1>
        {accounts.status === 'loading' ? (
          <p className="muted">Loading connected accounts…</p>
        ) : (
          <div className="empty">
            <p className="empty-title">No files to show.</p>
            <div className="muted">
              <p>Connect a Google Drive or Dropbox account to browse its files here.</p>
              <button type="button" className="button" onClick={onOpenAccounts}>
                Go to Cloud Accounts
              </button>
            </div>
          </div>
        )}
      </section>
    );
  }

  return (
    <section aria-labelledby="files-title">
      <h1 id="files-title">Files</h1>
      <div className="files-toolbar">
        <label className="field-label" htmlFor="files-account">
          Location
        </label>
        <select
          id="files-account"
          className="input select"
          value={accountId ?? 'all'}
          onChange={(e) => {
            const target = list.find((a) => a.id === e.target.value);
            if (target) openAccount(target);
            else goTo(-1);
          }}
        >
          <option value="all">All storage</option>
          {list.map((a) => (
            <option key={a.id} value={a.id}>
              {accountTitle(a)}
              {a.status === 'NEEDS_RECONNECT' ? ' (needs reconnect)' : ''}
            </option>
          ))}
        </select>
        <button type="button" className="button button-secondary button-small" onClick={refresh}>
          Refresh
        </button>
      </div>

      <nav aria-label="Folder path" className="breadcrumbs">
        <ol>
          <li>
            {location.kind === 'all' ? (
              <span aria-current="page">All storage</span>
            ) : (
              <button type="button" className="link-button" onClick={() => goTo(-1)}>
                All storage
              </button>
            )}
          </li>
          {path.map((crumb, index) => (
            <li key={`${crumb.locator ?? 'root'}-${index}`}>
              {index === path.length - 1 ? (
                <span aria-current="page">{crumb.name}</span>
              ) : (
                <button type="button" className="link-button" onClick={() => goTo(index)}>
                  {crumb.name}
                </button>
              )}
            </li>
          ))}
        </ol>
      </nav>

      <p className="muted small">
        {account ? scopeNote(account) : 'Each connected account has its own folder tree; open one to browse it.'} Opening,
        downloading and caching files aren’t implemented yet: nothing is cached on this PC, and the virtual drive is not
        implemented yet.
      </p>

      {notice && (
        <div className="notice notice-info" role="status">
          {notice}
        </div>
      )}

      {location.kind === 'all' || !account ? (
        <AllStorage accounts={list} onOpen={openAccount} />
      ) : (
        <ListingView
          listing={listing}
          onActivate={activate}
          onLoadMore={() => void loadMore()}
          onRetry={refresh}
          onOpenAccounts={onOpenAccounts}
        />
      )}
    </section>
  );
}

function scopeNote(account: ConnectedAccount): string {
  if (account.provider === 'DROPBOX') {
    return account.rootType === 'TEAM'
      ? 'Read-only view of this Dropbox team space (the folders this member can access).'
      : 'Read-only view of this Dropbox account.';
  }
  return 'Read-only view of My Drive (not shared drives or “Shared with me”).';
}

/** The virtual root: one row per connected account, never a merged file list. */
function AllStorage({ accounts, onOpen }: { accounts: readonly ConnectedAccount[]; onOpen: (a: ConnectedAccount) => void }) {
  return (
    <table className="files-table" aria-label="All storage">
      <thead>
        <tr>
          <th scope="col">Account</th>
          <th scope="col">Provider</th>
          <th scope="col">Status</th>
        </tr>
      </thead>
      <tbody>
        {accounts.map((a) => (
          <tr key={a.id}>
            <td>
              <button type="button" className="link-button" onClick={() => onOpen(a)} aria-label={`Open ${accountTitle(a)}`}>
                <ProviderIcon provider={a.provider} /> {accountLabel(a)}
              </button>
            </td>
            <td>
              {providerName(a.provider)}
              {a.rootType === 'TEAM' ? ' (team space)' : ''}
            </td>
            <td>{a.status === 'NEEDS_RECONNECT' ? 'Needs reconnect' : 'Connected · read-only'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ListingView({
  listing,
  onActivate,
  onLoadMore,
  onRetry,
  onOpenAccounts
}: {
  listing: Listing;
  onActivate: (file: StorageFile) => void;
  onLoadMore: () => void;
  onRetry: () => void;
  onOpenAccounts: () => void;
}) {
  if (listing.status === 'loading') {
    return (
      <p className="muted" role="status">
        Loading…
      </p>
    );
  }
  if (listing.status === 'error') {
    return (
      <div className="notice notice-warn" role="alert">
        <p>{storageErrorMessage(listing.error)}</p>
        <div className="actions">
          {listing.error === 'needs-reconnect' ? (
            <button type="button" className="button button-small" onClick={onOpenAccounts}>
              Reconnect in Cloud Accounts
            </button>
          ) : (
            <button type="button" className="button button-small" onClick={onRetry}>
              {listing.error === 'continuation-expired' ? 'Refresh' : 'Try again'}
            </button>
          )}
        </div>
      </div>
    );
  }
  if (listing.items.length === 0) {
    return (
      <div className="empty">
        <p className="empty-title">This folder is empty.</p>
      </div>
    );
  }
  return (
    <>
      <table className="files-table">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Type</th>
            <th scope="col">Size</th>
            <th scope="col">Modified</th>
            <th scope="col">Cache</th>
          </tr>
        </thead>
        <tbody>
          {listing.items.map((file) => (
            <tr key={file.providerFileId}>
              <td>
                {/* One activation path (click, Enter or Space) so a folder can never be opened twice. */}
                <button
                  type="button"
                  className="link-button"
                  onClick={() => onActivate(file)}
                  aria-label={file.kind === 'FOLDER' ? `Open folder ${file.name}` : `${file.name} (${kindLabel(file)})`}
                >
                  <span aria-hidden="true" className="file-icon">
                    {file.kind === 'FOLDER' ? '📁' : file.kind === 'SHORTCUT' ? '↪' : file.kind === 'PROVIDER_DOCUMENT' ? '📝' : '📄'}
                  </span>
                  {file.name || 'Untitled'}
                </button>
              </td>
              <td>{kindLabel(file)}</td>
              <td>{formatSize(file.sizeBytes)}</td>
              <td>{formatDate(file.modifiedAt)}</td>
              <td>{file.kind === 'FOLDER' ? '—' : 'Not cached'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {listing.moreError && (
        <div className="notice notice-warn" role="alert">
          More items couldn’t be loaded. {storageErrorMessage(listing.moreError)}
        </div>
      )}
      {listing.continuation && (
        <button type="button" className="button button-secondary" onClick={onLoadMore} disabled={listing.loadingMore}>
          {listing.loadingMore ? 'Loading more…' : 'Load more'}
        </button>
      )}
    </>
  );
}
