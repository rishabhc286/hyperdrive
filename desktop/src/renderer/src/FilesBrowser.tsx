import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConnectedAccount, StorageApi, StorageError, StorageFile } from '../../shared/storage';
import { accountLabel, formatDate, formatSize, kindLabel, storageErrorMessage } from './storage-format';
import type { AccountsState } from './useStorage';

export interface FilesBrowserProps {
  readonly storage: StorageApi;
  readonly accounts: AccountsState;
  readonly onReloadAccounts: () => void;
  readonly onOpenAccounts: () => void;
}

interface Crumb {
  /** Null = the account's root folder (My Drive). */
  readonly id: string | null;
  readonly name: string;
}

type Listing =
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly items: readonly StorageFile[]; readonly nextPageToken: string | null; readonly loadingMore: boolean; readonly moreError: StorageError | null }
  | { readonly status: 'error'; readonly error: StorageError };

/** A listing tagged with the request it answers; any other key renders as "loading". */
type KeyedListing = { readonly key: string; readonly listing: Listing };

const ROOT: Crumb = { id: null, name: 'My Drive' };

/**
 * Read-only folder browser for one connected account. Every request carries a sequence number;
 * a response that belongs to an older account, folder or refresh is dropped, so rapid navigation
 * can never show the wrong folder.
 */
export function FilesBrowser({ storage, accounts, onReloadAccounts, onOpenAccounts }: FilesBrowserProps) {
  const list = accounts.accounts;
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const account = list.find((a) => a.id === selectedId) ?? list.find((a) => a.status === 'CONNECTED') ?? list[0] ?? null;
  const [path, setPath] = useState<readonly Crumb[]>([ROOT]);
  const [keyed, setKeyed] = useState<KeyedListing | null>(null);
  const [refreshCount, setRefreshCount] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const seq = useRef(0);

  const folder = path[path.length - 1] ?? ROOT;
  const accountId = account?.id ?? null;
  const requestKey = `${accountId ?? ''}|${path.map((c) => c.id ?? '').join('/')}|${refreshCount}`;
  const listing: Listing = keyed?.key === requestKey ? keyed.listing : { status: 'loading' };

  useEffect(() => {
    if (!accountId) return;
    const mine = ++seq.current;
    void storage
      .listFiles({ accountId, parentId: folder.id })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }))
      .then((result) => {
        if (mine !== seq.current) return; // stale: the user moved on
        setKeyed({
          key: requestKey,
          listing: result.ok
            ? { status: 'ready', items: result.value.items, nextPageToken: result.value.nextPageToken, loadingMore: false, moreError: null }
            : { status: 'error', error: result.error }
        });
        if (!result.ok && result.error === 'needs-reconnect') onReloadAccounts();
      });
  }, [accountId, folder.id, requestKey, storage, onReloadAccounts]);

  const refresh = useCallback(() => {
    setNotice(null);
    setRefreshCount((n) => n + 1);
  }, []);

  const loadMore = async () => {
    if (!accountId || listing.status !== 'ready' || !listing.nextPageToken || listing.loadingMore) return;
    const mine = ++seq.current;
    const key = requestKey;
    const token = listing.nextPageToken;
    setKeyed({ key, listing: { ...listing, loadingMore: true, moreError: null } });
    const result = await storage
      .listFiles({ accountId, parentId: folder.id, pageToken: token })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }));
    if (mine !== seq.current) return;
    setKeyed((current) => {
      if (!current || current.key !== key || current.listing.status !== 'ready') return current;
      const prev = current.listing;
      return {
        key,
        listing: result.ok
          ? { status: 'ready', items: [...prev.items, ...result.value.items], nextPageToken: result.value.nextPageToken, loadingMore: false, moreError: null }
          : { ...prev, loadingMore: false, moreError: result.error }
      };
    });
  };

  const openFolder = (file: StorageFile) => {
    setNotice(null);
    setPath((p) => [...p, { id: file.providerFileId, name: file.name || 'Untitled folder' }]);
  };

  const activate = (file: StorageFile) => {
    switch (file.kind) {
      case 'FOLDER':
        openFolder(file);
        return;
      case 'SHORTCUT':
        setNotice(`“${file.name}” is a shortcut. Following shortcuts isn’t implemented yet.`);
        return;
      case 'PROVIDER_DOCUMENT':
        setNotice(
          `“${file.name}” is a ${kindLabel(file)} document. It has no downloadable file of its own, and opening or exporting it isn’t implemented yet.`
        );
        return;
      case 'FILE':
        setNotice(`Opening “${file.name}” isn’t implemented yet. This build only shows file details.`);
        return;
    }
  };

  const selectAccount = (id: string) => {
    setSelectedId(id);
    setPath([ROOT]);
    setNotice(null);
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
              <p>Connect a Google Drive account to browse its files here.</p>
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
          Account
        </label>
        <select
          id="files-account"
          className="input select"
          value={account?.id ?? ''}
          onChange={(e) => selectAccount(e.target.value)}
        >
          {list.map((a: ConnectedAccount) => (
            <option key={a.id} value={a.id}>
              Google Drive · {accountLabel(a)}
              {a.status === 'NEEDS_RECONNECT' ? ' (needs reconnect)' : ''}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="button button-secondary button-small"
          onClick={refresh}
          disabled={!accountId}
        >
          Refresh
        </button>
      </div>

      <nav aria-label="Folder path" className="breadcrumbs">
        <ol>
          {path.map((crumb, index) => {
            const last = index === path.length - 1;
            return (
              <li key={`${crumb.id ?? 'root'}-${index}`}>
                {last ? (
                  <span aria-current="page">{crumb.name}</span>
                ) : (
                  <button type="button" className="link-button" onClick={() => setPath(path.slice(0, index + 1))}>
                    {crumb.name}
                  </button>
                )}
              </li>
            );
          })}
        </ol>
      </nav>

      <p className="muted small">
        Read-only view of My Drive (not shared drives or “Shared with me”). Opening, downloading and caching files
        aren’t implemented yet: nothing is cached on this PC.
      </p>

      {notice && (
        <div className="notice notice-info" role="status">
          {notice}
        </div>
      )}

      <ListingView
        listing={listing}
        onActivate={activate}
        onLoadMore={() => void loadMore()}
        onRetry={refresh}
        onOpenAccounts={onOpenAccounts}
      />
    </section>
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
              Try again
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
            </tr>
          ))}
        </tbody>
      </table>
      {listing.moreError && (
        <div className="notice notice-warn" role="alert">
          More items couldn’t be loaded. {storageErrorMessage(listing.moreError)}
        </div>
      )}
      {listing.nextPageToken && (
        <button type="button" className="button button-secondary" onClick={onLoadMore} disabled={listing.loadingMore}>
          {listing.loadingMore ? 'Loading more…' : 'Load more'}
        </button>
      )}
    </>
  );
}
