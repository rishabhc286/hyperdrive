import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { BackendHealth, HyperdriveApi } from '../../shared/backend-health';
import { App } from './App';

function mockApi(result: BackendHealth | Promise<BackendHealth>): HyperdriveApi {
  return { checkBackendHealth: vi.fn(() => Promise.resolve(result)) };
}

function pending(): HyperdriveApi {
  return { checkBackendHealth: vi.fn(() => new Promise<BackendHealth>(() => {})) };
}

describe('App shell', () => {
  it('shows branding and the development build indicator', () => {
    render(<App api={pending()} />);
    expect(screen.getByText('HyperDrive')).toBeInTheDocument();
    expect(screen.getAllByText('Development build').length).toBeGreaterThan(0);
  });

  it('shows the honest empty state on the overview', () => {
    render(<App api={pending()} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument();
    expect(screen.getByText('No cloud accounts connected.')).toBeInTheDocument();
  });

  it('never claims a mounted drive, files or metrics', () => {
    render(<App api={pending()} />);
    expect(screen.queryByText(/mounted/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\d+(\.\d+)?\s?(MB|GB|MiB|GiB)\/s/)).not.toBeInTheDocument();
    expect(screen.queryByText(/hit ratio\s*[:=]?\s*\d/i)).not.toBeInTheDocument();
  });
});

describe('backend status', () => {
  it('shows "checking" while the health check is in flight', () => {
    render(<App api={pending()} />);
    expect(screen.getByRole('status')).toHaveTextContent('Backend: checking');
  });

  it('shows "connected" when the backend is healthy', async () => {
    render(<App api={mockApi({ state: 'connected' })} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: connected'));
    expect(screen.getByText(/responded to its health check/)).toBeInTheDocument();
  });

  it('shows "unavailable" when the backend cannot be reached', async () => {
    render(<App api={mockApi({ state: 'unavailable', reason: 'unreachable' })} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));
    expect(screen.getByText(/could not be reached/)).toBeInTheDocument();
  });

  it('shows "unavailable" when the bridge call rejects instead of crashing', async () => {
    const api: HyperdriveApi = { checkBackendHealth: vi.fn(() => Promise.reject(new Error('ipc failed'))) };
    render(<App api={api} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));
  });

  it('shows "unavailable" when no preload bridge exists', async () => {
    render(<App api={undefined} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));
  });

  it('re-checks on demand and reflects a recovered backend', async () => {
    const check = vi
      .fn<HyperdriveApi['checkBackendHealth']>()
      .mockResolvedValueOnce({ state: 'unavailable', reason: 'timeout' })
      .mockResolvedValueOnce({ state: 'connected' });
    render(<App api={{ checkBackendHealth: check }} />);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: unavailable'));

    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend: connected'));
    expect(check).toHaveBeenCalledTimes(2);
  });
});

describe('sidebar navigation', () => {
  const nav = () => within(screen.getByRole('navigation', { name: 'Main' }));

  it('lists the five sections and marks Overview as current', () => {
    render(<App api={pending()} />);
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
    render(<App api={pending()} />);
    await userEvent.click(nav().getByRole('button', { name: label }));

    expect(screen.getByRole('heading', { level: 1, name: label })).toBeInTheDocument();
    expect(screen.getByText(emptyText)).toBeInTheDocument();
    expect(nav().getByRole('button', { name: label })).toHaveAttribute('aria-current', 'page');
    expect(nav().getByRole('button', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });

  it('is keyboard operable', async () => {
    render(<App api={pending()} />);
    const user = userEvent.setup();
    await user.tab();
    expect(nav().getByRole('button', { name: 'Overview' })).toHaveFocus();
    await user.tab();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('heading', { level: 1, name: 'Files' })).toBeInTheDocument();
  });
});

describe('cloud account connection buttons', () => {
  it.each(['Connect Google Drive', 'Connect Dropbox'])('"%s" is disabled and explains why', async (name) => {
    render(<App api={pending()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Cloud Accounts' }));

    const button = screen.getByRole('button', { name });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/later development batch/);
  });
});
