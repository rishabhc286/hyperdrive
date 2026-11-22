import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import type { ConnectedAccount, FilePage, StorageFile, StorageResult } from '../../shared/storage';
import { App } from './App';

const DRIVE: ConnectedAccount = {
  id: '6f1c2a8e-1b2c-4d3e-8f90-123456789abc',
  provider: 'GOOGLE_DRIVE',
  displayName: 'Drive Owner',
  email: 'drive.owner@example.com',
  status: 'CONNECTED',
  rootType: null,
  connectedAt: '2026-11-22T10:00:00Z',
  lastSuccessAt: null,
  lastErrorCode: null
};
const BOX: ConnectedAccount = {
  ...DRIVE,
  id: '7a1c2a8e-1b2c-4d3e-8f90-123456789abc',
  provider: 'DROPBOX',
  email: 'box.owner@example.com',
  rootType: 'USER'
};

function file(id: string, name: string, extra: Partial<StorageFile> = {}): StorageFile {
  return { providerFileId: id, name, kind: 'FILE', mimeType: 'application/pdf', sizeBytes: 2048,
    modifiedAt: '2026-02-03T04:05:06Z', canDownload: true, directoryLocator: null, providerRevision: null,
    contentHash: null, contentHashAlgorithm: null, shortcutTargetId: null, shortcutTargetMimeType: null, ...extra };
}
const driveFolder = (id: string, name: string) =>
  file(id, name, { kind: 'FOLDER', mimeType: 'application/vnd.google-apps.folder', sizeBytes: null, canDownload: false, directoryLocator: id });
const boxFolder = (path: string, name: string) =>
  file(`id:${name}`, name, { kind: 'FOLDER', mimeType: null, sizeBytes: null, modifiedAt: null, canDownload: false, directoryLocator: path });
const page = (items: StorageFile[], continuation: string | null = null): StorageResult<FilePage> => ({
  ok: true,
  value: { items, continuation }
});

async function openFiles(setup: (mock: ReturnType<typeof createMockApi>) => void, accounts: ConnectedAccount[] = [DRIVE, BOX]) {
  const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
  mock.storage.listAccounts.mockResolvedValue({ ok: true, value: accounts });
  setup(mock);
  render(<App api={mock.api} />);
  await userEvent.click(await screen.findByRole('button', { name: 'Files' }));
  return mock;
}

const crumbs = () => screen.getByRole('navigation', { name: 'Folder path' });
const openAccount = async (title: string) => userEvent.click(await screen.findByRole('button', { name: `Open ${title}` }));

describe('Files: All storage', () => {
  it('starts at a virtual root listing accounts, without calling any provider', async () => {
    const mock = await openFiles(() => undefined);
    const table = await screen.findByRole('table', { name: 'All storage' });
    expect(within(table).getByText('drive.owner@example.com')).toBeInTheDocument();
    expect(within(table).getByText('box.owner@example.com')).toBeInTheDocument();
    expect(within(table).getByText('Dropbox')).toBeInTheDocument();
    expect(within(crumbs()).getByText('All storage')).toHaveAttribute('aria-current', 'page');
    expect(mock.storage.listFiles).not.toHaveBeenCalled();
  });

  it('opening an account lists its root; the virtual root id never reaches the provider API', async () => {
    const mock = await openFiles((m) => m.storage.listFiles.mockResolvedValue(page([file('b1', 'notes.txt', { mimeType: null })])));
    await openAccount('Dropbox — box.owner@example.com');
    expect(await screen.findByText('notes.txt')).toBeInTheDocument();
    expect(mock.storage.listFiles).toHaveBeenCalledWith({ accountId: BOX.id, directory: null });
    expect(within(crumbs()).getByText('Dropbox — box.owner@example.com')).toHaveAttribute('aria-current', 'page');
    // Dropbox has no MIME type: the label comes from the extension only.
    expect(screen.getByRole('table')).toHaveTextContent('TXT file');
  });

  it('renders both providers in the same table with honest cache and missing-metadata cells', async () => {
    await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ accountId }) =>
        Promise.resolve(
          accountId === DRIVE.id
            ? page([driveFolder('fA', 'Projects'), file('f1', 'report.pdf', { sizeBytes: 1536 })])
            : page([
                boxFolder('/photos', 'Photos'),
                file('id:x', 'mystery', { mimeType: null, sizeBytes: null, modifiedAt: null }),
                file('id:p', 'Notes.paper', { kind: 'PROVIDER_DOCUMENT', mimeType: null, sizeBytes: null, canDownload: false }),
                file('id:l', 'Link', { kind: 'SHORTCUT', mimeType: null, sizeBytes: null, canDownload: false })
              ])
        )
      )
    );
    await openAccount('Google Drive — drive.owner@example.com');
    let rows = within(await screen.findByRole('table')).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Folder');
    expect(rows[2]).toHaveTextContent('1.5 KB');
    expect(rows[2]).toHaveTextContent('Not cached');

    await userEvent.selectOptions(screen.getByLabelText('Location'), BOX.id);
    await screen.findByText('Photos');
    rows = within(screen.getByRole('table')).getAllByRole('row');
    expect(rows[2]).toHaveTextContent('File');
    expect(within(rows[2]!).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    expect(rows[3]).toHaveTextContent('Cloud document');
    expect(rows[4]).toHaveTextContent('Link');
    expect(document.body.textContent).not.toMatch(/Mounted|H:\\|cached on this PC:/);
    expect(screen.getByText(/virtual drive is not implemented yet/)).toBeInTheDocument();
  });
});

describe('Files: navigation', () => {
  it('opens Dropbox folders by their locator, keeps breadcrumbs and returns to All storage', async () => {
    const mock = await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ directory }) =>
        Promise.resolve(directory === '/photos' ? page([file('id:p1', 'beach.jpg', { mimeType: null })]) : page([boxFolder('/photos', 'Photos')]))
      )
    );
    await openAccount('Dropbox — box.owner@example.com');
    await userEvent.click(await screen.findByRole('button', { name: 'Open folder Photos' }));
    expect(await screen.findByText('beach.jpg')).toBeInTheDocument();
    expect(mock.storage.listFiles).toHaveBeenLastCalledWith({ accountId: BOX.id, directory: '/photos' });
    expect(within(crumbs()).getByText('Photos')).toHaveAttribute('aria-current', 'page');

    await userEvent.click(within(crumbs()).getByRole('button', { name: 'Dropbox — box.owner@example.com' }));
    expect(await screen.findByRole('button', { name: 'Open folder Photos' })).toBeInTheDocument();
    await userEvent.click(within(crumbs()).getByRole('button', { name: 'All storage' }));
    expect(await screen.findByRole('table', { name: 'All storage' })).toBeInTheDocument();
  });

  it('switching accounts resets the path and never mixes pagination state', async () => {
    const mock = await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ accountId, continuation }) => {
        if (accountId === DRIVE.id) {
          return Promise.resolve(continuation ? page([file('d2', 'drive-2.pdf')]) : page([file('d1', 'drive-1.pdf')], 'hdk_drive_next'));
        }
        return Promise.resolve(continuation ? page([file('id:b2', 'box-2.txt')]) : page([file('id:b1', 'box-1.txt')], 'hdk_box_next'));
      })
    );
    await openAccount('Google Drive — drive.owner@example.com');
    await screen.findByText('drive-1.pdf');
    await userEvent.selectOptions(screen.getByLabelText('Location'), BOX.id);
    await screen.findByText('box-1.txt');
    expect(screen.queryByText('drive-1.pdf')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('box-2.txt')).toBeInTheDocument();
    expect(mock.storage.listFiles).toHaveBeenLastCalledWith({ accountId: BOX.id, directory: null, continuation: 'hdk_box_next' });
    expect(mock.storage.listFiles).not.toHaveBeenCalledWith(expect.objectContaining({ accountId: BOX.id, continuation: 'hdk_drive_next' }));
  });

  it('ignores a slow response from an account the user already left', async () => {
    let releaseSlow: (() => void) | null = null;
    await openFiles((m) =>
      m.storage.listFiles.mockImplementation(({ accountId }) => {
        if (accountId === DRIVE.id) {
          return new Promise((resolve) => {
            releaseSlow = () => resolve(page([file('x', 'from-drive.pdf')]));
          });
        }
        return Promise.resolve(page([file('id:y', 'from-dropbox.txt')]));
      })
    );
    await openAccount('Google Drive — drive.owner@example.com');
    await userEvent.selectOptions(screen.getByLabelText('Location'), BOX.id);
    expect(await screen.findByText('from-dropbox.txt')).toBeInTheDocument();

    releaseSlow!();
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('from-drive.pdf')).not.toBeInTheDocument();
    // The late response must not replace (or blank) the current listing either.
    expect(screen.getByText('from-dropbox.txt')).toBeInTheDocument();
  });

  it('a disconnect during an in-flight listing removes the account and drops its response', async () => {
    let release: (() => void) | null = null;
    const mock = await openFiles((m) =>
      m.storage.listFiles.mockImplementation(
        () =>
          new Promise((resolve) => {
            release = () => resolve(page([file('id:late', 'late.txt')]));
          })
      )
    );
    await openAccount('Dropbox — box.owner@example.com');
    await waitFor(() => expect(release).not.toBeNull());

    // Disconnect elsewhere (Cloud Accounts), then come back.
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [DRIVE] });
    await userEvent.click(screen.getByRole('button', { name: 'Cloud Accounts' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect Dropbox box.owner@example.com' }));
    await userEvent.click(within(screen.getByRole('group', { name: /Confirm/ })).getByRole('button', { name: 'Disconnect' }));
    await waitFor(() => expect(screen.queryByText('box.owner@example.com')).not.toBeInTheDocument());
    release!();
    await userEvent.click(screen.getByRole('button', { name: 'Files' }));

    expect(await screen.findByRole('table', { name: 'All storage' })).toBeInTheDocument();
    expect(screen.queryByText('box.owner@example.com')).not.toBeInTheDocument();
    expect(screen.queryByText('late.txt')).not.toBeInTheDocument();
    expect(screen.getByText('drive.owner@example.com')).toBeInTheDocument();
  });

  it('a needs-reconnect account shows the reconnect path without calling the provider', async () => {
    const mock = await openFiles(() => undefined, [{ ...BOX, status: 'NEEDS_RECONNECT' }]);
    await openAccount('Dropbox — box.owner@example.com');
    expect(await screen.findByRole('alert')).toHaveTextContent(/needs to be connected again/);
    expect(mock.storage.listFiles).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect in Cloud Accounts' }));
    expect(await screen.findByRole('heading', { name: 'Cloud Accounts' })).toBeInTheDocument();
  });

  it('shows empty, permission, network and expired-listing states', async () => {
    const mock = await openFiles((m) => m.storage.listFiles.mockResolvedValue(page([])));
    await openAccount('Dropbox — box.owner@example.com');
    expect(await screen.findByText('This folder is empty.')).toBeInTheDocument();

    for (const [error, text] of [
      ['permission-denied', /denied access/],
      ['backend-unreachable', /backend could not be reached/],
      ['continuation-expired', /listing expired/]
    ] as const) {
      mock.storage.listFiles.mockResolvedValue({ ok: false, error });
      await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(text);
    }
  });

  it('explains that opening files, documents and links is not implemented', async () => {
    await openFiles((m) =>
      m.storage.listFiles.mockResolvedValue(
        page([file('id:f1', 'report.pdf', { mimeType: null }), file('id:l', 'Link', { kind: 'SHORTCUT', mimeType: null, sizeBytes: null })])
      )
    );
    await openAccount('Dropbox — box.owner@example.com');
    await userEvent.click(await screen.findByRole('button', { name: 'report.pdf (PDF file)' }));
    expect(screen.getByText(/Opening “report.pdf” isn’t implemented yet/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Link (Link)' }));
    expect(screen.getByText(/Following shortcuts and links isn’t implemented yet/)).toBeInTheDocument();
  });

  it('without accounts, points to Cloud Accounts', async () => {
    await openFiles(() => undefined, []);
    await userEvent.click(await screen.findByRole('button', { name: 'Go to Cloud Accounts' }));
    expect(await screen.findByRole('heading', { name: 'Cloud Accounts' })).toBeInTheDocument();
  });
});
