import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { CACHE_READY, createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import { App } from './App';

async function open(section: 'Performance' | 'Settings', setup?: (mock: ReturnType<typeof createMockApi>) => void) {
  const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
  setup?.(mock);
  render(<App api={mock.api} />);
  await userEvent.click(await screen.findByRole('button', { name: section }));
  return mock;
}

describe('Performance: cache metrics', () => {
  it('shows real counters, actual encrypted bytes and the honest Google bypass', async () => {
    await open('Performance');
    const card = await screen.findByRole('article', { name: 'Local chunk cache' });
    expect(within(card).getByTestId('cache-state')).toHaveTextContent('Disk cache: On (encrypted)');
    expect(card).toHaveTextContent(/8.0 MB in 2 chunk files \(ciphertext, including 32 bytes per chunk\)/);
    expect(card).toHaveTextContent(/Bytes fetched from providers8,388,608/);
    expect(card).toHaveTextContent(/Bytes served from cache3,000/);
    expect(card).toHaveTextContent(/Google Drive: reads can’t be pinned to a file version, so they’re never cached: 4/);
    expect(card).toHaveTextContent(/Counters reset when the agent restarts/);
    expect(document.body.textContent).not.toMatch(/space saved|savings|drive mounted|is mounted|playback ready|available offline/i);
    expect(document.body.innerHTML).not.toMatch(/AppData|\.hdc|[A-Za-z0-9+/]{43}=/);
  });

  it('explains when Windows key protection is unavailable', async () => {
    await open('Performance', (m) =>
      m.cache.getStatus.mockResolvedValue({
        ok: true,
        value: { ...CACHE_READY, enabled: false, disabledReason: 'key-protection-unavailable', keyProtection: 'unavailable' }
      })
    );
    expect(await screen.findByTestId('cache-state')).toHaveTextContent(
      /Off — Windows-protected key storage isn’t available on this PC.*Reads still work, uncached/
    );
  });

  it('reports an unavailable agent instead of zeros', async () => {
    await open('Performance', (m) => m.cache.getStatus.mockResolvedValue({ ok: false, error: 'agent-unavailable' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/local agent isn’t running/);
  });
});

describe('Settings: cache controls', () => {
  it('validates the limit and saves it in bytes', async () => {
    const mock = await open('Settings');
    const input = await screen.findByLabelText(/Cache size limit \(MiB/);
    expect(input).toHaveValue('5120');
    await userEvent.clear(input);
    await userEvent.type(input, '100');
    expect(screen.getByRole('button', { name: 'Save limit' })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent(/from 256 to 1,048,576/);
    await userEvent.clear(input);
    await userEvent.type(input, '256');
    await userEvent.click(screen.getByRole('button', { name: 'Save limit' }));
    expect(mock.cache.setBudget).toHaveBeenCalledWith({ budgetBytes: 256 * 1024 * 1024 });
    expect(await screen.findByText('Cache limit set to 256 MB.')).toBeInTheDocument();
  });

  it('clear cache needs confirmation and reports partial failures', async () => {
    const mock = await open('Settings', (m) => m.cache.clear.mockResolvedValue({ ok: true, value: { removed: 3, failed: 1 } }));
    await userEvent.click(await screen.findByRole('button', { name: 'Clear cache…' }));
    const confirm = screen.getByRole('group', { name: 'Confirm clearing the cache' });
    expect(confirm).toHaveTextContent(/files in Google\s+Drive and Dropbox and your connected accounts aren’t affected/);
    expect(confirm).toHaveTextContent(/doesn’t securely erase/);
    await userEvent.click(within(confirm).getByRole('button', { name: 'Keep cache' }));
    expect(mock.cache.clear).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Clear cache…' }));
    await userEvent.click(within(screen.getByRole('group', { name: /Confirm/ })).getByRole('button', { name: 'Clear cache' }));
    expect(mock.cache.clear).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('alert')).toHaveTextContent(/Removed 3 cached chunk file\(s\); 1 couldn’t be deleted/);
  });

  it('says the cache is not offline access and is deleted on sign-out', async () => {
    await open('Settings');
    const card = await screen.findByRole('article', { name: 'File cache' });
    expect(card).toHaveTextContent(/isn’t offline access/);
    expect(card).toHaveTextContent(/Signing out\s+deletes your cached files and the key/);
    await waitFor(() => expect(screen.getByTestId('cache-state')).toHaveTextContent('On'));
  });
});
