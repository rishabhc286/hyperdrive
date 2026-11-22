import { useState, type ReactNode } from 'react';
import type { ConnectedAccount, StorageApi, StorageError } from '../../shared/storage';
import { accountLabel, connectionFailureMessage, formatDate, storageErrorMessage } from './storage-format';
import type { AccountsState, ConnectionFlow } from './useStorage';

export interface CloudAccountsProps {
  readonly storage: StorageApi;
  readonly accounts: AccountsState;
  readonly onReloadAccounts: () => void;
  readonly connection: ConnectionFlow;
  readonly onConnect: () => void;
  readonly onCancelConnection: () => void;
  readonly onDismissConnection: () => void;
}

export function CloudAccounts(props: CloudAccountsProps) {
  const { connection } = props;
  const busy = connection.phase === 'starting' || connection.phase === 'waiting' || connection.phase === 'cancelling';

  return (
    <section aria-labelledby="accounts-title">
      <h1 id="accounts-title">Cloud Accounts</h1>
      <p className="muted">
        Connecting a storage account is separate from signing in to HyperDrive. You can connect any Google
        account’s Drive, including one different from the account you signed in with. Access is read-only.
      </p>

      <ConnectionNotice {...props} />

      <h2 className="subheading">Connected accounts</h2>
      <AccountList {...props} busy={busy} />

      <h2 className="subheading">Add an account</h2>
      <ul className="providers" aria-label="Cloud storage providers">
        <li className="provider">
          <span className="provider-name">Google Drive</span>
          <button type="button" className="button" onClick={props.onConnect} disabled={busy}>
            Connect Google Drive
          </button>
        </li>
        <li className="provider">
          <span className="provider-name">Dropbox</span>
          <span className="muted small" id="dropbox-unavailable-note">
            Not available yet; Dropbox support arrives in a later development batch.
          </span>
          <button type="button" className="button" disabled aria-describedby="dropbox-unavailable-note">
            Connect Dropbox
          </button>
        </li>
      </ul>
    </section>
  );
}

function ConnectionNotice({ connection, onCancelConnection, onDismissConnection, onConnect }: CloudAccountsProps) {
  switch (connection.phase) {
    case 'idle':
      return null;
    case 'starting':
      return (
        <div className="notice notice-info" role="status">
          Opening your browser…
        </div>
      );
    case 'waiting':
    case 'cancelling':
      return (
        <div className="notice notice-info" role="status">
          <p>
            {connection.phase === 'cancelling'
              ? 'Cancelling…'
              : 'Finish connecting Google Drive in your browser. Choose the Google account whose Drive you want to use, then allow read-only access.'}
          </p>
          <div className="actions">
            <button
              type="button"
              className="button button-secondary button-small"
              onClick={onCancelConnection}
              disabled={connection.phase === 'cancelling'}
            >
              Cancel
            </button>
          </div>
        </div>
      );
    case 'completed':
      return (
        <Dismissable kind="info" onDismiss={onDismissConnection}>
          {connection.reconnected ? 'Google Drive reconnected.' : 'Google Drive connected.'}
        </Dismissable>
      );
    case 'failed':
      return (
        <Dismissable kind="warn" onDismiss={onDismissConnection} onRetry={onConnect}>
          {connectionFailureMessage(connection.errorCode)}
        </Dismissable>
      );
    case 'cancelled':
      return (
        <Dismissable kind="info" onDismiss={onDismissConnection}>
          Connection cancelled. Nothing was connected.
        </Dismissable>
      );
    case 'expired':
      return (
        <Dismissable kind="warn" onDismiss={onDismissConnection} onRetry={onConnect}>
          The connection attempt timed out. Nothing was connected.
        </Dismissable>
      );
    case 'stopped':
      return (
        <Dismissable kind="warn" onDismiss={onDismissConnection}>
          {connection.reason === 'cancel-unconfirmed'
            ? 'Stopped waiting, but HyperDrive couldn’t confirm the cancellation with the backend. If you finish in the browser, the account may still be connected.'
            : 'Stopped checking because the backend didn’t respond. If you finished in the browser, the account may still appear after a refresh.'}
        </Dismissable>
      );
    case 'start-failed':
      return (
        <Dismissable kind="warn" onDismiss={onDismissConnection} onRetry={onConnect}>
          {storageErrorMessage(connection.error)}
        </Dismissable>
      );
  }
}

function Dismissable({
  kind,
  children,
  onDismiss,
  onRetry
}: {
  kind: 'info' | 'warn';
  children: ReactNode;
  onDismiss: () => void;
  onRetry?: () => void;
}) {
  return (
    <div className={`notice notice-${kind}`} role={kind === 'warn' ? 'alert' : 'status'}>
      <p>{children}</p>
      <div className="actions">
        {onRetry && (
          <button type="button" className="button button-small" onClick={onRetry}>
            Try again
          </button>
        )}
        <button type="button" className="button button-secondary button-small" onClick={onDismiss}>
          Dismiss
        </button>
      </div>
    </div>
  );
}

function AccountList({ storage, accounts, onReloadAccounts, onConnect, busy }: CloudAccountsProps & { busy: boolean }) {
  const [confirming, setConfirming] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [error, setError] = useState<StorageError | null>(null);

  if (accounts.status === 'loading' && accounts.accounts.length === 0) {
    return <p className="muted">Loading connected accounts…</p>;
  }
  if (accounts.status === 'error' && accounts.accounts.length === 0) {
    return (
      <div className="notice notice-warn" role="alert">
        <p>Connected accounts couldn’t be loaded. {storageErrorMessage(accounts.error)}</p>
        <button type="button" className="button button-small" onClick={onReloadAccounts}>
          Try again
        </button>
      </div>
    );
  }
  if (accounts.accounts.length === 0) {
    return (
      <div className="empty">
        <p className="empty-title">No cloud accounts connected.</p>
      </div>
    );
  }

  const disconnect = async (account: ConnectedAccount) => {
    setRemoving(account.id);
    setError(null);
    const result = await storage.disconnectAccount({ accountId: account.id }).catch(() => ({ ok: false as const, error: 'unknown' as const }));
    setRemoving(null);
    setConfirming(null);
    if (!result.ok && result.error !== 'not-found') setError(result.error);
    onReloadAccounts();
  };

  return (
    <>
      {error && (
        <div className="notice notice-warn" role="alert">
          The account couldn’t be disconnected. {storageErrorMessage(error)}
        </div>
      )}
      <ul className="accounts" aria-label="Connected accounts">
        {accounts.accounts.map((account) => (
          <li key={account.id} className="account-row">
            <div className="account-info">
              <span className="provider-name">Google Drive</span>
              <span>{accountLabel(account)}</span>
              {account.displayName && account.email && <span className="muted small">{account.displayName}</span>}
              <span className="muted small">Connected {formatDate(account.connectedAt)} · read-only</span>
              {account.status === 'NEEDS_RECONNECT' ? (
                <span className="badge badge-warn">Needs reconnect</span>
              ) : (
                <span className="badge badge-ok">Connected</span>
              )}
            </div>
            {confirming === account.id ? (
              <div className="confirm" role="group" aria-label={`Confirm disconnecting ${accountLabel(account)}`}>
                <p className="small">
                  Disconnect {accountLabel(account)}? HyperDrive deletes its stored access for this account. Your
                  files stay in Google Drive and you stay signed in to HyperDrive. To also remove HyperDrive from
                  your Google Account, use Google Account → Security → Your connections to third-party apps.
                </p>
                <div className="actions">
                  <button
                    type="button"
                    className="button button-small button-danger"
                    onClick={() => void disconnect(account)}
                    disabled={removing === account.id}
                  >
                    {removing === account.id ? 'Disconnecting…' : 'Disconnect'}
                  </button>
                  <button
                    type="button"
                    className="button button-secondary button-small"
                    onClick={() => setConfirming(null)}
                    disabled={removing === account.id}
                  >
                    Keep connected
                  </button>
                </div>
              </div>
            ) : (
              <div className="actions">
                {account.status === 'NEEDS_RECONNECT' && (
                  <button type="button" className="button button-small" onClick={onConnect} disabled={busy}>
                    Reconnect
                  </button>
                )}
                <button
                  type="button"
                  className="button button-secondary button-small"
                  onClick={() => setConfirming(account.id)}
                  aria-label={`Disconnect ${accountLabel(account)}`}
                >
                  Disconnect…
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
