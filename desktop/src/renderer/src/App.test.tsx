import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import type { BackendHealth, HyperdriveApi } from '../../shared/backend-health';
import { App } from './App';

const signedIn = () => status('signed-in', { user: ONBOARDED_USER });

async function renderSignedIn(health: BackendHealth | 'pending' = 'pending') {
  const mock = createMockApi(signedIn(), health);
  render(<App api={mock.api} />);
  await screen.findByRole('navigation', { name: 'Main' });
  return mock;
}

describe('signed-in shell', () => {
  it('shows branding, the development build indicator and the username', async () => {
    await renderSignedIn();
    expect(screen.getByText('HyperDrive')).toBeInTheDocument();
    expect(screen.getAllByText('Development build').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Signed in as')).toHaveTextContent('@Test_User');
  });

  it('keeps the honest empty state and never implies Drive is connected', async () => {
    await renderSignedIn();
    expect(await screen.findByText('No cloud accounts connected.')).toBeInTheDocument();
    expect(screen.queryByText(/mounted/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/drive connected/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\d+(\.\d+)?\s?(MB|GB|MiB|GiB)\/s/)).not.toBeInTheDocument();
  });

  it('sign out calls the bridge', async () => {
    const mock = await renderSignedIn();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(mock.auth.signOut).toHaveBeenCalledTimes(1);
  });
});

describe('backend status', () => {
  it('shows "checking" while the health check is in flight', async () => {
    await renderSignedIn('pending');
    expect(screen.getByRole('status')).toHaveTextContent('Backend: checking');
  });

  it('shows "connected" when the backend is healthy', async () => {
    await renderSignedIn({ state: 'connected' });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: connected'));
    expect(screen.getByText(/responded to its health check/)).toBeInTheDocument();
  });

  it('shows "unavailable" when the backend cannot be reached', async () => {
    await renderSignedIn({ state: 'unavailable', reason: 'unreachable' });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));
    expect(screen.getByText(/could not be reached/)).toBeInTheDocument();
  });

  it('shows "unavailable" when the bridge call rejects instead of crashing', async () => {
    const mock = createMockApi(signedIn());
    const api: HyperdriveApi = { ...mock.api, checkBackendHealth: vi.fn(() => Promise.reject(new Error('ipc failed'))) };
    render(<App api={api} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));
  });

  it('re-checks on demand and reflects a recovered backend', async () => {
    const mock = createMockApi(signedIn());
    const check = vi
      .fn<HyperdriveApi['checkBackendHealth']>()
      .mockResolvedValueOnce({ state: 'unavailable', reason: 'timeout' })
      .mockResolvedValueOnce({ state: 'connected' });
    render(<App api={{ ...mock.api, checkBackendHealth: check }} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));

    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: connected'));
    expect(check).toHaveBeenCalledTimes(2);
  });
});

describe('sidebar navigation', () => {
  const nav = () => within(screen.getByRole('navigation', { name: 'Main' }));

  it('lists the five sections and marks Overview as current', async () => {
    await renderSignedIn();
    const labels = nav()
      .getAllByRole('button')
      .map((b) => b.textContent);
    expect(labels).toEqual(['Overview', 'Files', 'Cloud Accounts', 'Performance', 'Settings']);
    expect(nav().getByRole('button', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
  });

  it.each([
    ['Files', 'No files to show.'],
    ['Cloud Accounts', 'No cloud accounts connected.'],
    ['Performance', 'No performance data.'],
    ['Settings', 'No settings to change yet.']
  ])('switches to %s with its own empty state', async (label, emptyText) => {
    await renderSignedIn();
    await userEvent.click(nav().getByRole('button', { name: label }));

    expect(screen.getByRole('heading', { level: 1, name: label })).toBeInTheDocument();
    expect(screen.getByText(emptyText)).toBeInTheDocument();
    expect(nav().getByRole('button', { name: label })).toHaveAttribute('aria-current', 'page');
    expect(nav().getByRole('button', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });

  it('is keyboard operable', async () => {
    await renderSignedIn();
    const user = userEvent.setup();
    await user.tab();
    expect(nav().getByRole('button', { name: 'Overview' })).toHaveFocus();
    await user.tab();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('heading', { level: 1, name: 'Files' })).toBeInTheDocument();
  });

  it('settings shows the account and that sign-in has no Drive access', async () => {
    await renderSignedIn();
    await userEvent.click(nav().getByRole('button', { name: 'Settings' }));
    expect(screen.getByText('@Test_User', { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText(/sign-in identity only; Drive is connected separately/)).toBeInTheDocument();
  });
});

describe('cloud account connection buttons', () => {
  it('"Connect Google Drive" is available (Batch 3)', async () => {
    await renderSignedIn();
    await userEvent.click(screen.getByRole('button', { name: 'Cloud Accounts' }));
    expect(screen.getByRole('button', { name: 'Connect Google Drive' })).toBeEnabled();
    expect(screen.getByText(/separate from signing in to HyperDrive/)).toBeInTheDocument();
  });

  it('"Connect Dropbox" stays disabled and explains why', async () => {
    await renderSignedIn();
    await userEvent.click(screen.getByRole('button', { name: 'Cloud Accounts' }));
    const button = screen.getByRole('button', { name: 'Connect Dropbox' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/later development batch/);
  });
});
