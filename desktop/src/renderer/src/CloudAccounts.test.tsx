import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import type { ConnectedAccount, ConnectionStatus, StorageProvider } from '../../shared/storage';
import { App } from './App';

const TX = '0f8fad5b-d9cb-469f-a165-70867728950e';
const DRIVE: ConnectedAccount = {
  id: '6f1c2a8e-1b2c-4d3e-8f90-123456789abc',
  provider: 'GOOGLE_DRIVE',
  displayName: 'Drive Owner',
  email: 'drive.owner@example.com',
  status: 'CONNECTED',
  rootType: null,
  connectedAt: '2026-11-22T10:00:00Z',
  lastSuccessAt: null,
  lastErrorCode: null,
  contentReadGranted: true
};
const BOX: ConnectedAccount = {
  ...DRIVE,
  id: '7a1c2a8e-1b2c-4d3e-8f90-123456789abc',
  provider: 'DROPBOX',
  email: 'box.owner@example.com',
  rootType: 'USER'
};

function txStatus(s: ConnectionStatus['status'], extra: Partial<ConnectionStatus> = {}): ConnectionStatus {
  return { transactionId: TX, provider: 'GOOGLE_DRIVE', status: s, browserOpened: true,
    expiresAt: new Date(Date.now() + 600_000).toISOString(), accountId: null, reconnected: null, errorCode: null, ...extra };
}

async function openAccounts(setup?: (mock: ReturnType<typeof createMockApi>) => void) {
  const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
  setup?.(mock);
  render(<App api={mock.api} pollIntervalMs={5} />);
  await userEvent.click(await screen.findByRole('button', { name: 'Cloud Accounts' }));
  return mock;
}

function beginsOk(mock: ReturnType<typeof createMockApi>) {
  mock.storage.beginConnection.mockResolvedValue({
    ok: true,
    value: { transactionId: TX, expiresAt: new Date(Date.now() + 600_000).toISOString() }
  });
}

describe('Cloud Accounts: connecting', () => {
  it.each<[StorageProvider, string, ConnectedAccount]>([
    ['GOOGLE_DRIVE', 'Google Drive', DRIVE],
    ['DROPBOX', 'Dropbox', BOX]
  ])('%s: waits for the browser, then shows the connected account', async (provider, name, account) => {
    let finished = false;
    const mock = await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockImplementation(() =>
        Promise.resolve({
          ok: true,
          value: finished
            ? txStatus('COMPLETED', { provider, accountId: account.id, reconnected: false })
            : txStatus('PENDING', { provider, browserOpened: false })
        })
      );
    });
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [account] });

    await userEvent.click(screen.getByRole('button', { name: `Connect ${name}` }));

    expect(mock.storage.beginConnection).toHaveBeenCalledWith({ provider });
    expect(await screen.findByText(new RegExp(`Finish connecting ${name} in your browser`))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect Google Drive' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Connect Dropbox' })).toBeDisabled();
    finished = true;
    expect(await screen.findByText(`${name} connected.`)).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Connected accounts' });
    expect(within(list).getByText(account.email!)).toBeInTheDocument();
  });

  it('reports a duplicate connection as a reconnect of the existing account', async () => {
    await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockResolvedValue({
        ok: true,
        value: txStatus('COMPLETED', { provider: 'DROPBOX', accountId: BOX.id, reconnected: true })
      });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Connect Dropbox' }));
    expect(await screen.findByText(/Dropbox reconnected\. This account was already connected/)).toBeInTheDocument();
  });

  it('explains provider-specific failures using the server error code', async () => {
    await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockResolvedValue({
        ok: true,
        value: txStatus('FAILED', { provider: 'DROPBOX', errorCode: 'scope_not_granted' })
      });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Connect Dropbox' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Dropbox didn’t grant the read permissions/);
  });

  it('disables a provider the server has not configured', async () => {
    await openAccounts((m) =>
      m.storage.listProviders.mockResolvedValue({
        ok: true,
        value: [
          { provider: 'GOOGLE_DRIVE', available: true },
          { provider: 'DROPBOX', available: false }
        ]
      })
    );
    const dropbox = await screen.findByRole('button', { name: 'Connect Dropbox' });
    await waitFor(() => expect(dropbox).toBeDisabled());
    expect(dropbox).toHaveAccessibleDescription(/Not configured on this HyperDrive server/);
    expect(screen.getByRole('button', { name: 'Connect Google Drive' })).toBeEnabled();
  });

  it('cancel asks the backend to cancel and reports it', async () => {
    const mock = await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockResolvedValue({ ok: true, value: txStatus('PENDING') });
      m.storage.cancelConnection.mockResolvedValue({ ok: true, value: txStatus('CANCELLED', { errorCode: 'cancelled' }) });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Connect Google Drive' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(mock.storage.cancelConnection).toHaveBeenCalledWith({ transactionId: TX });
    expect(await screen.findByText('Connection cancelled. Nothing was connected.')).toBeInTheDocument();
  });

  it('says it only stopped waiting when cancellation cannot be confirmed', async () => {
    await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockResolvedValue({ ok: true, value: txStatus('PENDING') });
      m.storage.cancelConnection.mockResolvedValue({ ok: false, error: 'backend-unreachable' });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Connect Google Drive' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Stopped waiting, but HyperDrive couldn’t confirm/);
  });

  it('stops polling after repeated status errors', async () => {
    const mock = await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockResolvedValue({ ok: false, error: 'backend-unreachable' });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Connect Google Drive' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Stopped checking/);
    const calls = mock.storage.getConnectionStatus.mock.calls.length;
    await new Promise((r) => setTimeout(r, 50));
    expect(mock.storage.getConnectionStatus.mock.calls.length).toBe(calls);
  });

  it('reports an unconfigured provider returned by the backend', async () => {
    await openAccounts((m) => m.storage.beginConnection.mockResolvedValue({ ok: false, error: 'not-configured' }));
    await userEvent.click(screen.getByRole('button', { name: 'Connect Dropbox' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/not configured on your HyperDrive server/);
  });
});

describe('Cloud Accounts: connected accounts', () => {
  it('lists Google Drive and Dropbox accounts together', async () => {
    await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE, { ...BOX, rootType: 'TEAM' }] }));
    const list = await screen.findByRole('list', { name: 'Connected accounts' });
    expect(within(list).getByText('drive.owner@example.com')).toBeInTheDocument();
    expect(within(list).getByText('box.owner@example.com')).toBeInTheDocument();
    expect(within(list).getByText(/Dropbox \(team space\)/)).toBeInTheDocument();
  });

  it('disconnect requires confirmation and removes only that account', async () => {
    const mock = await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE, BOX] }));
    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect Dropbox box.owner@example.com' }));

    const confirm = screen.getByRole('group', { name: /Confirm disconnecting Dropbox/ });
    expect(confirm).toHaveTextContent(/does not revoke HyperDrive’s authorization at Dropbox/);
    expect(confirm).toHaveTextContent(/does not delete anything in Dropbox/);
    expect(confirm).toHaveTextContent(/other accounts stay connected/);
    expect(confirm).toHaveTextContent('dropbox.com/account/connected_apps');
    await userEvent.click(within(confirm).getByRole('button', { name: 'Keep connected' }));
    expect(mock.storage.disconnectAccount).not.toHaveBeenCalled();

    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE] });
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect Dropbox box.owner@example.com' }));
    await userEvent.click(within(screen.getByRole('group', { name: /Confirm/ })).getByRole('button', { name: 'Disconnect' }));

    expect(mock.storage.disconnectAccount).toHaveBeenCalledWith({ accountId: BOX.id });
    await waitFor(() => expect(screen.queryByText('box.owner@example.com')).not.toBeInTheDocument());
    expect(screen.getByText('drive.owner@example.com')).toBeInTheDocument();
    expect(mock.auth.signOut).not.toHaveBeenCalled();
  });

  it('ordinary disconnect never calls provider revocation', async () => {
    const mock = await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [BOX] }));
    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect Dropbox box.owner@example.com' }));
    await userEvent.click(within(screen.getByRole('group', { name: /Confirm disconnecting/ })).getByRole('button', { name: 'Disconnect' }));
    expect(mock.storage.disconnectAccount).toHaveBeenCalledWith({ accountId: BOX.id });
    expect(mock.storage.revokeAndDisconnect).not.toHaveBeenCalled();
  });

  it('revoke is a separate, confirmed Dropbox-only action that reports the actual outcome', async () => {
    const mock = await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE, BOX] }));
    await screen.findByText('box.owner@example.com');
    // Google Drive: no automatic revoke; manual instructions explain the project-wide effect instead.
    expect(screen.queryByRole('button', { name: /Revoke Google Drive/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect Google Drive drive.owner@example.com' }));
    const googleConfirm = screen.getByRole('group', { name: /Confirm disconnecting Google Drive/ });
    expect(googleConfirm).toHaveTextContent('myaccount.google.com/permissions');
    expect(googleConfirm).toHaveTextContent(/every Drive connection of this Google account on this server stops working/);
    await userEvent.click(within(googleConfirm).getByRole('button', { name: 'Keep connected' }));

    await userEvent.click(screen.getByRole('button', { name: 'Revoke Dropbox access and disconnect box.owner@example.com' }));
    const confirm = screen.getByRole('group', { name: /Confirm revoking Dropbox access/ });
    expect(confirm).toHaveTextContent(/disconnects it here even if Dropbox doesn’t confirm/);
    expect(confirm).toHaveTextContent(/Nothing in your Dropbox is deleted/);
    expect(mock.storage.revokeAndDisconnect).not.toHaveBeenCalled();

    mock.storage.revokeAndDisconnect.mockResolvedValueOnce({
      ok: true,
      value: { revocation: 'unknown', reason: 'no_definite_answer', disconnected: true }
    });
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE] });
    await userEvent.click(within(confirm).getByRole('button', { name: 'Revoke access and disconnect' }));

    expect(mock.storage.revokeAndDisconnect).toHaveBeenCalledWith({ accountId: BOX.id });
    expect(mock.storage.disconnectAccount).not.toHaveBeenCalled();
    const notice = await screen.findByText(/unknown whether the authorization was revoked/);
    expect(notice).toHaveTextContent(/unknown whether the authorization was revoked/);
    expect(notice).toHaveTextContent(/was disconnected from HyperDrive/);
    expect(notice).toHaveTextContent('dropbox.com/account/connected_apps');
    expect(notice).not.toHaveTextContent(/confirmed that/);
  });

  it('reconnect starts the flow for that account’s provider', async () => {
    const mock = await openAccounts((m) =>
      m.storage.listAccounts.mockResolvedValue({ ok: true, value: [{ ...BOX, status: 'NEEDS_RECONNECT' }] })
    );
    expect(await screen.findByText('Needs reconnect')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect Dropbox box.owner@example.com' }));
    expect(mock.storage.beginConnection).toHaveBeenCalledWith({ provider: 'DROPBOX' });
  });

  it('explains a connection without content permission and offers reconnect', async () => {
    const mock = await openAccounts((m) =>
      m.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE, { ...BOX, contentReadGranted: false }] })
    );
    expect(await screen.findByText(/can’t read file contents. Reconnect to grant read access/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reconnect Google Drive drive.owner@example.com' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect Dropbox box.owner@example.com' }));
    expect(mock.storage.beginConnection).toHaveBeenCalledWith({ provider: 'DROPBOX' });
  });

  it('shows a retryable error when accounts cannot be loaded', async () => {
    const mock = await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: false, error: 'backend-unreachable' }));
    expect(await screen.findByText(/Connected accounts couldn’t be loaded/)).toBeInTheDocument();
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE] });
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('drive.owner@example.com')).toBeInTheDocument());
  });

  it('never exposes token-shaped values or mounted status in the rendered page', async () => {
    await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE, BOX] }));
    await screen.findByText('drive.owner@example.com');
    expect(document.body.innerHTML).not.toMatch(/ya29\.|sl\.fake|hda_|hdr_|hds_|refresh_token|mounted|H:\\/i);
  });
});
