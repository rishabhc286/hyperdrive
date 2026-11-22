import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import type { ConnectedAccount, FilePage, StorageFile, StorageResult } from '../../shared/storage';
import { App } from './App';

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
const SECOND: ConnectedAccount = { ...ACCOUNT, id: '7f1c2a8e-1b2c-4d3e-8f90-123456789abc', email: 'work@example.com' };

function file(id: string, name: string, extra: Partial<StorageFile> = {}): StorageFile {
  return { providerFileId: id, name, kind: 'FILE', mimeType: 'application/pdf', sizeBytes: 2048,
    modifiedAt: '2026-02-03T04:05:06Z', canDownload: true, shortcutTargetId: null, shortcutTargetMimeType: null, ...extra };
}
const folder = (id: string, name: string) =>
  file(id, name, { kind: 'FOLDER', mimeType: 'application/vnd.google-apps.folder', sizeBytes: null, canDownload: false });
const page = (items: StorageFile[], nextPageToken: string | null = null): StorageResult<FilePage> => ({
  ok: true,
  value: { items, nextPageToken }
});

async function openFiles(setup: (mock: ReturnType<typeof createMockApi>) => void) {
  const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
  mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [ACCOUNT] });
  setup(mock);
  render(<App api={mock.api} />);
  await userEvent.click(await screen.findByRole('button', { name: 'Files' }));
  return mock;
}

describe('Files: browsing', () => {
  it('lists the root folder with honest type, size and date columns', async () => {
    await openFiles((m) =>
      m.storage.listFiles.mockResolvedValue(
        page([
          folder('fA', 'Projects'),
          file('f1', 'report.pdf', { sizeBytes: 1536 }),
          file('d1', 'Notes', { kind: 'PROVIDER_DOCUMENT', mimeType: 'application/vnd.google-apps.document', sizeBytes: null, canDownload: false }),
          file('s1', 'Link', { kind: 'SHORTCUT', mimeType: 'application/vnd.google-apps.shortcut', sizeBytes: null, shortcutTargetId: 'fA', shortcutTargetMimeType: 'application/vnd.google-apps.folder' }),
          file('u1', 'mystery', { mimeType: 'application/octet-stream', sizeBytes: null, modifiedAt: null })
        ])
      )
    );
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(6);
    expect(rows[1]).toHaveTextContent('Projects');
    expect(rows[1]).toHaveTextContent('Folder');
    expect(rows[2]).toHaveTextContent('PDF');
    expect(rows[2]).toHaveTextContent('1.5 KB');
    expect(rows[3]).toHaveTextContent('Google Docs');
    expect(rows[4]).toHaveTextContent('Shortcut to folder');
    expect(rows[5]).toHaveTextContent('—');
    expect(screen.getByText(/nothing is cached on this PC/)).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Folder path' })).toHaveTextContent('My Drive');
  });

  it('opens folders, shows breadcrumbs and navigates back', async () => {
    const mock = await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ parentId }) =>
        Promise.resolve(parentId === 'fA' ? page([file('in1', 'inside.pdf')]) : page([folder('fA', 'Projects')]))
      )
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Open folder Projects' }));

    expect(await screen.findByText('inside.pdf')).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: 'Folder path' });
    expect(within(crumbs).getByText('Projects')).toHaveAttribute('aria-current', 'page');
    expect(mock.storage.listFiles).toHaveBeenLastCalledWith({ accountId: ACCOUNT.id, parentId: 'fA' });

    await userEvent.click(within(crumbs).getByRole('button', { name: 'My Drive' }));
    expect(await screen.findByRole('button', { name: 'Open folder Projects' })).toBeInTheDocument();
    expect(mock.storage.listFiles).toHaveBeenLastCalledWith({ accountId: ACCOUNT.id, parentId: null });
  });

  it('loads more items with the provider cursor', async () => {
    const mock = await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ pageToken }) =>
        Promise.resolve(pageToken === 'next~1' ? page([file('b', 'b.pdf')]) : page([file('a', 'a.pdf')], 'next~1'))
      )
    );
    await screen.findByText('a.pdf');
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('b.pdf')).toBeInTheDocument();
    expect(screen.getByText('a.pdf')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
    expect(mock.storage.listFiles).toHaveBeenLastCalledWith({ accountId: ACCOUNT.id, parentId: null, pageToken: 'next~1' });
  });

  it('ignores a slow response from a folder the user already left', async () => {
    let releaseSlow: (() => void) | null = null;
    await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ parentId }) => {
        if (parentId === 'slow') {
          return new Promise((resolve) => {
            releaseSlow = () => resolve(page([file('x', 'from-slow-folder.pdf')]));
          });
        }
        if (parentId === 'fast') return Promise.resolve(page([file('y', 'from-fast-folder.pdf')]));
        return Promise.resolve(page([folder('slow', 'Slow'), folder('fast', 'Fast')]));
      })
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Open folder Slow' }));
    // Go back and into another folder before the first one answers.
    await userEvent.click(within(screen.getByRole('navigation', { name: 'Folder path' })).getByRole('button', { name: 'My Drive' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open folder Fast' }));
    expect(await screen.findByText('from-fast-folder.pdf')).toBeInTheDocument();

    releaseSlow!();
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('from-slow-folder.pdf')).not.toBeInTheDocument();
    expect(screen.getByText('from-fast-folder.pdf')).toBeInTheDocument();
  });

  it('shows an empty folder state', async () => {
    await openFiles((m) => m.storage.listFiles.mockResolvedValue(page([])));
    expect(await screen.findByText('This folder is empty.')).toBeInTheDocument();
  });

  it('explains that opening files and documents is not implemented', async () => {
    await openFiles((m) =>
      m.storage.listFiles.mockResolvedValue(
        page([file('f1', 'report.pdf'), file('d1', 'Notes', { kind: 'PROVIDER_DOCUMENT', mimeType: 'application/vnd.google-apps.document', sizeBytes: null, canDownload: false })])
      )
    );
    await userEvent.click(await screen.findByRole('button', { name: 'report.pdf (PDF)' }));
    expect(screen.getByText(/Opening “report.pdf” isn’t implemented yet/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Notes (Google Docs)' }));
    expect(screen.getByText(/has no downloadable file of its own/)).toBeInTheDocument();
  });

  it('shows errors with retry, and a reconnect path when the account needs it', async () => {
    const mock = await openFiles((m) => m.storage.listFiles.mockResolvedValue({ ok: false, error: 'provider-unavailable' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Google Drive could not be reached/);
    mock.storage.listFiles.mockResolvedValue({ ok: false, error: 'needs-reconnect' });
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Reconnect in Cloud Accounts' }));
    expect(await screen.findByRole('heading', { name: 'Cloud Accounts' })).toBeInTheDocument();
  });

  it('refresh re-fetches the current folder', async () => {
    const mock = await openFiles((m) => m.storage.listFiles.mockResolvedValue(page([file('a', 'a.pdf')])));
    await screen.findByText('a.pdf');
    const calls = mock.storage.listFiles.mock.calls.length;
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(mock.storage.listFiles.mock.calls.length).toBe(calls + 1));
  });

  it('switching accounts starts again at that account’s root', async () => {
    const mock = await openFiles((m) => {
      m.storage.listAccounts.mockResolvedValue({ ok: true, value: [ACCOUNT, SECOND] });
      m.storage.listFiles.mockImplementation(({ accountId, parentId }) =>
        Promise.resolve(
          accountId === SECOND.id ? page([file('w', 'work.pdf')]) : parentId ? page([]) : page([folder('fA', 'Projects')])
        )
      );
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Open folder Projects' }));
    await screen.findByText('This folder is empty.');
    await userEvent.selectOptions(screen.getByLabelText('Account'), SECOND.id);
    expect(await screen.findByText('work.pdf')).toBeInTheDocument();
    expect(mock.storage.listFiles).toHaveBeenLastCalledWith({ accountId: SECOND.id, parentId: null });
    expect(screen.getByRole('navigation', { name: 'Folder path' })).not.toHaveTextContent('Projects');
  });

  it('without accounts, points to Cloud Accounts', async () => {
    await openFiles((m) => m.storage.listAccounts.mockResolvedValue({ ok: true, value: [] }));
    await userEvent.click(await screen.findByRole('button', { name: 'Go to Cloud Accounts' }));
    expect(await screen.findByRole('heading', { name: 'Cloud Accounts' })).toBeInTheDocument();
  });
});
