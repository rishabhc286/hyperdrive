import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import type { ConnectedAccount, ConnectionStatus } from '../../shared/storage';
import { App } from './App';

const TX = '0f8fad5b-d9cb-469f-a165-70867728950e';
const ACCOUNT: ConnectedAccount = {
  id: '6f1c2a8e-1b2c-4d3e-8f90-123456789abc',
  provider: 'GOOGLE_DRIVE',
  displayName: 'Drive Owner',
  email: 'drive.owner@example.com',
  status: 'CONNECTED',
  connectedAt: '2026-11-22T10:00:00Z',
  lastSuccessAt: null,
  lastErrorCode: null
};

function txStatus(s: ConnectionStatus['status'], extra: Partial<ConnectionStatus> = {}): ConnectionStatus {
  return { transactionId: TX, status: s, browserOpened: true, expiresAt: new Date(Date.now() + 600_000).toISOString(),
    accountId: null, reconnected: null, errorCode: null, ...extra };
}

async function openAccounts(setup?: (mock: ReturnType<typeof createMockApi>) => void) {
  const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
  setup?.(mock);
  render(<App api={mock.api} pollIntervalMs={5} />);
  await userEvent.click(await screen.findByRole('button', { name: 'Cloud Accounts' }));
  return mock;
}

function beginsOk(mock: ReturnType<typeof createMockApi>) {
  mock.storage.beginGoogleConnection.mockResolvedValue({
    ok: true,
    value: { transactionId: TX, expiresAt: new Date(Date.now() + 600_000).toISOString() }
  });
}

describe('Cloud Accounts: connecting Google Drive', () => {
  it('waits for the browser, then shows the connected account', async () => {
    // Stays PENDING until the test has checked the waiting state, then completes.
    let finished = false;
    const mock = await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockImplementation(() =>
        Promise.resolve({
          ok: true,
          value: finished
            ? txStatus('COMPLETED', { accountId: ACCOUNT.id, reconnected: false })
            : txStatus('PENDING', { browserOpened: false })
        })
      );
    });
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [ACCOUNT] });

    await userEvent.click(screen.getByRole('button', { name: 'Connect Google Drive' }));

    expect(await screen.findByText(/Finish connecting Google Drive in your browser/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect Google Drive' })).toBeDisabled();
    finished = true;
    expect(await screen.findByText('Google Drive connected.')).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Connected accounts' });
    expect(within(list).getByText('drive.owner@example.com')).toBeInTheDocument();
    expect(within(list).getByText('Connected')).toBeInTheDocument();
    expect(mock.storage.getConnectionStatus).toHaveBeenCalledWith({ transactionId: TX });
  });

  it('explains a failed attempt using the server error code', async () => {
    await openAccounts((m) => {
      beginsOk(m);
      m.storage.getConnectionStatus.mockResolvedValue({
        ok: true,
        value: txStatus('FAILED', { errorCode: 'drive_scope_not_granted' })
      });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Connect Google Drive' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Google Drive access wasn’t granted/);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
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

  it('reports when the browser could not be opened', async () => {
    await openAccounts((m) => m.storage.beginGoogleConnection.mockResolvedValue({ ok: false, error: 'backend-unreachable' }));
    await userEvent.click(screen.getByRole('button', { name: 'Connect Google Drive' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/backend could not be reached/);
  });
});

describe('Cloud Accounts: connected accounts', () => {
  it('disconnect requires confirmation and explains what it does', async () => {
    const mock = await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [ACCOUNT] }));
    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect drive.owner@example.com' }));

    const confirm = screen.getByRole('group', { name: /Confirm disconnecting/ });
    expect(confirm).toHaveTextContent(/files stay in Google Drive/);
    expect(confirm).toHaveTextContent(/stay signed in to HyperDrive/);
    await userEvent.click(within(confirm).getByRole('button', { name: 'Keep connected' }));
    expect(mock.storage.disconnectAccount).not.toHaveBeenCalled();

    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [] });
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect drive.owner@example.com' }));
    await userEvent.click(within(screen.getByRole('group', { name: /Confirm/ })).getByRole('button', { name: 'Disconnect' }));

    expect(mock.storage.disconnectAccount).toHaveBeenCalledWith({ accountId: ACCOUNT.id });
    expect(await screen.findByText('No cloud accounts connected.')).toBeInTheDocument();
    expect(mock.auth.signOut).not.toHaveBeenCalled();
  });

  it('shows a reconnect action for accounts that need it', async () => {
    const mock = await openAccounts((m) =>
      m.storage.listAccounts.mockResolvedValue({ ok: true, value: [{ ...ACCOUNT, status: 'NEEDS_RECONNECT' }] })
    );
    expect(await screen.findByText('Needs reconnect')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }));
    expect(mock.storage.beginGoogleConnection).toHaveBeenCalledTimes(1);
  });

  it('shows a retryable error when accounts cannot be loaded', async () => {
    const mock = await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: false, error: 'backend-unreachable' }));
    expect(await screen.findByText(/Connected accounts couldn’t be loaded/)).toBeInTheDocument();
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [ACCOUNT] });
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('drive.owner@example.com')).toBeInTheDocument());
  });

  it('never exposes token-shaped values in the rendered page', async () => {
    await openAccounts((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [ACCOUNT] }));
    await screen.findByText('drive.owner@example.com');
    expect(document.body.innerHTML).not.toMatch(/ya29\.|hda_|hdr_|hds_|refresh_token/);
  });
});
