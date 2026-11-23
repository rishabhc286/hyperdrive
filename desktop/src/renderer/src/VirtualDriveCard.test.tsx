import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockVfs, VFS_READY } from '../../../test/mock-api';
import { METADATA_ONLY_NOTICE } from '../../shared/vfs';
import { VirtualDriveCard } from './VirtualDriveCard';

describe('VirtualDriveCard', () => {
  it('mounts on request and offers Explorer only after the mount is confirmed', async () => {
    const vfs = createMockVfs();
    render(<VirtualDriveCard vfs={vfs.api} />);
    expect(await screen.findByText('Ready to mount as H:')).toBeInTheDocument();
    expect(screen.getByText(METADATA_ONLY_NOTICE)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open in Explorer' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Mount' }));
    expect(await screen.findByText('Mounted as H: — metadata only')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Open in Explorer' }));
    expect(vfs.api.openInExplorer).toHaveBeenCalledWith();

    await userEvent.click(screen.getByRole('button', { name: 'Unmount' }));
    expect(await screen.findByText('Ready to mount as H:')).toBeInTheDocument();
  });

  it('shows progress and failures pushed by the main process, never a premature Mounted', async () => {
    const vfs = createMockVfs();
    render(<VirtualDriveCard vfs={vfs.api} />);
    await screen.findByText('Ready to mount as H:');
    vfs.emit({ ...VFS_READY, state: 'mounting', phase: 'volume-created' });
    expect(await screen.findByText('Mounting H:…')).toBeInTheDocument();
    expect(screen.getByText('Volume created…')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open in Explorer' })).not.toBeInTheDocument();
    vfs.emit({ ...VFS_READY, state: 'failed', error: 'drive-letter-in-use' });
    expect(await screen.findByRole('alert')).toHaveTextContent(/never replaces an existing drive/);
  });

  it('explains missing prerequisites and lets the user choose a drive letter', async () => {
    const missing = createMockVfs({ ...VFS_READY, state: 'prerequisite-missing', prerequisite: 'winfsp-missing' });
    const { unmount } = render(<VirtualDriveCard vfs={missing.api} />);
    expect(await screen.findByText(/WinFsp isn’t installed/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mount' })).not.toBeInTheDocument();
    unmount();

    const vfs = createMockVfs();
    render(<VirtualDriveCard vfs={vfs.api} />);
    await screen.findByText('Ready to mount as H:');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Drive letter' }), 'K');
    expect(vfs.api.setDriveLetter).toHaveBeenCalledWith({ letter: 'K' });
    await waitFor(() => expect(screen.getByText('Ready to mount as K:')).toBeInTheDocument());
    expect(screen.getAllByRole('option').map((o) => o.textContent)).not.toContain('C:');
  });
});
