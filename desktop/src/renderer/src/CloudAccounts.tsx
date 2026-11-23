import { useState, type ReactNode } from 'react';
import type { ConnectedAccount, RevokeResult, StorageApi, StorageError, StorageProvider } from '../../shared/storage';
import { ProviderIcon } from './ProviderIcon';
import {
  accountLabel,
  connectionFailureMessage,
  formatDate,
  providerName,
  storageErrorMessage
} from './storage-format';
import type { AccountsState, ConnectionFlow } from './useStorage';

export interface CloudAccountsProps {
  readonly storage: StorageApi;
  readonly accounts: AccountsState;
  readonly onReloadAccounts: () => void;
  readonly connection: ConnectionFlow;
  /** null until the backend has said which providers it can connect. */
  readonly availability: ReadonlyMap<StorageProvider, boolean> | null;
  readonly onConnect: (provider: StorageProvider) => void;
  readonly onCancelConnection: () => void;
  readonly onDismissConnection: () => void;
}

const PROVIDERS: readonly StorageProvider[] = ['GOOGLE_DRIVE', 'DROPBOX'];

export function CloudAccounts(props: CloudAccountsProps) {
  const { connection, availability } = props;
  const busy = connection.phase === 'starting' || connection.phase === 'waiting' || connection.phase === 'cancelling';

  return (
    <section aria-labelledby="accounts-title">
      <h1 id="accounts-title">Cloud Accounts</h1>
      <p className="muted">
        Connecting a storage account is separate from signing in to HyperDrive. You can connect several Google Drive
        and Dropbox accounts, including ones that use a different email from your HyperDrive sign-in. Access is
        read-only.
      </p>

      <ConnectionNotice {...props} />

      <h2 className="subheading">Connected accounts</h2>
      <AccountList {...props} busy={busy} />

      <h2 className="subheading">Add an account</h2>
      <ul className="providers" aria-label="Cloud storage providers">
        {PROVIDERS.map((provider) => {
          const unavailable = availability?.get(provider) === false;
          const noteId = `${provider.toLowerCase()}-unavailable-note`;
          return (
            <li className="provider" key={provider}>
              <span className="provider-name">
                <ProviderIcon provider={provider} /> {providerName(provider)}
              </span>
              {unavailable && (
                <span className="muted small" id={noteId}>
                  Not configured on this HyperDrive server.
                </span>
              )}
              <button
                type="button"
                className="button"
                onClick={() => props.onConnect(provider)}
                disabled={busy || unavailable}
                aria-describedby={unavailable ? noteId : undefined}
              >
                Connect {providerName(provider)}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function ConnectionNotice({ connection, onCancelConnection, onDismissConnection, onConnect }: CloudAccountsProps) {
  if (connection.phase === 'idle') return null;
  const name = providerName(connection.provider);
  const retry = () => onConnect(connection.provider);
  switch (connection.phase) {
    case 'starting':
      return (
        <div className="notice notice-info" role="status">
          Opening your browser for {name}…
        </div>
      );
    case 'waiting':
    case 'cancelling':
      return (
        <div className="notice notice-info" role="status">
          <p>
            {connection.phase === 'cancelling'
              ? 'Cancelling…'
              : `Finish connecting ${name} in your browser. Sign in to the ${name} account you want to use, then allow read-only access.`}
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
          {connection.reconnected
            ? `${name} reconnected. This account was already connected, so its existing connection was updated.`
            : `${name} connected.`}
        </Dismissable>
      );
    case 'failed':
      return (
        <Dismissable kind="warn" onDismiss={onDismissConnection} onRetry={retry}>
          {connectionFailureMessage(connection.provider, connection.errorCode)}
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
        <Dismissable kind="warn" onDismiss={onDismissConnection} onRetry={retry}>
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
        <Dismissable kind="warn" onDismiss={onDismissConnection} onRetry={retry}>
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

/** Manual, provider-side removal (official account pages). Shown with Disconnect and after a failed revoke. */
export function manualRevokeHint(provider: StorageProvider): string {
  return provider === 'DROPBOX'
    ? 'To remove HyperDrive’s authorization in Dropbox yourself, open dropbox.com/account/connected_apps (Settings → Apps), choose HyperDrive and click Disconnect.'
    : 'To remove HyperDrive’s authorization in Google, open myaccount.google.com/permissions (Third-party apps with account access) and remove HyperDrive. Google removes everything this Google account granted to this HyperDrive server’s Google project: every Drive connection of this Google account on this server stops working, and signing in to HyperDrive with it may ask for consent again.';
}

/** What the user is told after "Revoke access and disconnect". Never claims more than the backend reported. */
export function revokeOutcomeMessage(label: string, result: RevokeResult): string {
  const local = result.disconnected
    ? `${label} was disconnected from HyperDrive and its stored access was deleted.`
    : `${label} was already gone from HyperDrive.`;
  switch (result.revocation) {
    case 'confirmed':
      return `Dropbox confirmed that this connection’s authorization was revoked. ${local}`;
    case 'failed':
      return `Dropbox did not confirm the revocation. ${local} ${manualRevokeHint('DROPBOX')}`;
    case 'unknown':
      return `Dropbox didn’t answer clearly, so it’s unknown whether the authorization was revoked. ${local} ${manualRevokeHint('DROPBOX')}`;
  }
}

type Confirming = { readonly id: string; readonly mode: 'disconnect' | 'revoke' };

function AccountList({ storage, accounts, onReloadAccounts, onConnect, busy }: CloudAccountsProps & { busy: boolean }) {
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [error, setError] = useState<StorageError | null>(null);
  const [outcome, setOutcome] = useState<{ text: string; warn: boolean } | null>(null);

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
  const outcomeNotice = outcome && (
    <div className={outcome.warn ? 'notice notice-warn' : 'notice'} role="status">
      {outcome.text}
    </div>
  );
  if (accounts.accounts.length === 0) {
    return (
      <>
        {outcomeNotice}
        <div className="empty">
          <p className="empty-title">No cloud accounts connected.</p>
        </div>
      </>
    );
  }

  const disconnect = async (account: ConnectedAccount) => {
    setRemoving(account.id);
    setError(null);
    setOutcome(null);
    const result = await storage
      .disconnectAccount({ accountId: account.id })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }));
    setRemoving(null);
    setConfirming(null);
    if (!result.ok && result.error !== 'not-found') setError(result.error);
    onReloadAccounts();
  };

  const revoke = async (account: ConnectedAccount) => {
    setRemoving(account.id);
    setError(null);
    setOutcome(null);
    const result = await storage
      .revokeAndDisconnect({ accountId: account.id })
      .catch(() => ({ ok: false as const, error: 'unknown' as const }));
    setRemoving(null);
    setConfirming(null);
    if (result.ok) {
      setOutcome({
        text: revokeOutcomeMessage(accountLabel(account), result.value),
        warn: result.value.revocation !== 'confirmed'
      });
    } else if (result.error !== 'not-found') {
      setError(result.error);
    }
    onReloadAccounts();
  };

  return (
    <>
      {outcomeNotice}
      {error && (
        <div className="notice notice-warn" role="alert">
          The account couldn’t be disconnected. {storageErrorMessage(error)}
        </div>
      )}
      <ul className="accounts" aria-label="Connected accounts">
        {accounts.accounts.map((account) => {
          const name = providerName(account.provider);
          const label = accountLabel(account);
          return (
            <li key={account.id} className="account-row">
              <div className="account-info">
                <span className="provider-name">
                  <ProviderIcon provider={account.provider} /> {name}
                  {account.rootType === 'TEAM' ? ' (team space)' : ''}
                </span>
                <span>{label}</span>
                {account.displayName && account.email && <span className="muted small">{account.displayName}</span>}
                <span className="muted small">Connected {formatDate(account.connectedAt)} · read-only</span>
                {account.status === 'NEEDS_RECONNECT' ? (
                  <span className="badge badge-warn">Needs reconnect</span>
                ) : (
                  <span className="badge badge-ok">Connected</span>
                )}
                {account.status === 'CONNECTED' && !account.contentReadGranted && (
                  <span className="muted small">
                    Browsing works, but this connection can’t read file contents. Reconnect to grant read access to file
                    contents (still read-only).
                  </span>
                )}
              </div>
              {confirming?.id === account.id && confirming.mode === 'revoke' ? (
                <div className="confirm" role="group" aria-label={`Confirm revoking ${name} access for ${label}`}>
                  <p className="small">
                    Revoke HyperDrive’s {name} access and disconnect {label}? HyperDrive asks {name} to disable the
                    authorization this connection uses (its refresh token and the access tokens issued from it), then
                    disconnects it here even if {name} doesn’t confirm. Other HyperDrive connections to the same {name}{' '}
                    account (other HyperDrive users or servers) hold their own authorizations and aren’t revoked. Nothing
                    in your {name} is deleted. To use this account in HyperDrive again, connect it and approve access
                    again.
                  </p>
                  <div className="actions">
                    <button
                      type="button"
                      className="button button-small button-danger"
                      onClick={() => void revoke(account)}
                      disabled={removing === account.id}
                    >
                      {removing === account.id ? 'Revoking…' : 'Revoke access and disconnect'}
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
              ) : confirming?.id === account.id ? (
                <div className="confirm" role="group" aria-label={`Confirm disconnecting ${name} ${label}`}>
                  <p className="small">
                    Disconnect {name} {label} from HyperDrive? HyperDrive deletes its stored access and cached file data
                    for this account only. This does not revoke HyperDrive’s authorization at {name} and does not delete
                    anything in {name}. Your other accounts stay connected, and you stay signed in to HyperDrive.{' '}
                    {manualRevokeHint(account.provider)}
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
                  {(account.status === 'NEEDS_RECONNECT' || !account.contentReadGranted) && (
                    <button
                      type="button"
                      className="button button-small"
                      onClick={() => onConnect(account.provider)}
                      disabled={busy}
                      aria-label={`Reconnect ${name} ${label}`}
                    >
                      Reconnect
                    </button>
                  )}
                  <button
                    type="button"
                    className="button button-secondary button-small"
                    onClick={() => setConfirming({ id: account.id, mode: 'disconnect' })}
                    aria-label={`Disconnect ${name} ${label}`}
                  >
                    Disconnect…
                  </button>
                  {account.provider === 'DROPBOX' && (
                    <button
                      type="button"
                      className="button button-secondary button-small"
                      onClick={() => setConfirming({ id: account.id, mode: 'revoke' })}
                      aria-label={`Revoke ${name} access and disconnect ${label}`}
                    >
                      Revoke access…
                    </button>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}
