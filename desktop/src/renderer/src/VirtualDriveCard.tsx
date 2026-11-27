import { useCallback, useEffect, useState } from 'react';
import type { VirtualDriveActivity, VirtualDriveApi, VirtualDriveError, VirtualDriveReadIssue, VirtualDriveStatus } from '../../shared/vfs';
import { READ_ONLY_NOTICE } from '../../shared/vfs';
import { formatSize } from './storage-format';

const LETTERS = 'DEFGHIJKLMNOPQRSTUVWXYZ'.split('');

export const PREREQUISITE_MESSAGES: Record<NonNullable<VirtualDriveStatus['prerequisite']>, string> = {
  'unsupported-platform': 'The virtual drive is available on 64-bit Windows only.',
  'winfsp-missing': 'WinFsp isn’t installed. Install WinFsp from winfsp.dev, then restart HyperDrive.',
  'vfs-missing': 'The virtual drive program (hyperdrive-vfs.exe) isn’t built or configured. See docs/virtual-drive.md.',
  'agent-unavailable': 'The local agent isn’t running. Restart it from the Local agent card.'
};

export const ERROR_MESSAGES: Record<VirtualDriveError, string> = {
  'drive-letter-in-use': 'That drive letter is already in use. Choose another letter; HyperDrive never replaces an existing drive.',
  'invalid-drive-letter': 'Choose a drive letter from D to Z.',
  'startup-timeout': 'The virtual drive didn’t start in time and was stopped.',
  'invalid-handshake': 'The virtual drive program answered unexpectedly and was stopped.',
  'vfs-exited': 'The virtual drive program stopped unexpectedly.',
  'authorization-lost': 'The drive’s authorization ended (sign-out, agent restart or expired session), so it was unmounted.',
  'agent-unavailable': 'The local agent isn’t available, so the drive can’t be mounted or was unmounted.',
  'backend-unreachable': 'The HyperDrive backend couldn’t be reached.',
  'session-expired': 'Your HyperDrive session has expired. Sign in again.',
  'mount-failed': 'Windows didn’t accept the drive. Check WinFsp and try again.',
  'winfsp-runtime-missing': 'The WinFsp runtime couldn’t be loaded. Reinstall WinFsp.',
  unknown: 'Something went wrong with the virtual drive.'
};

export const READ_ISSUE_MESSAGES: Record<VirtualDriveReadIssue, string> = {
  'reconnect-needed': 'A cloud account needs to be connected again before its files can be read. Reconnect it under Accounts.',
  'content-permission-missing': 'HyperDrive isn’t allowed to read file contents for an account. Reconnect it to allow reads.',
  'session-expired': 'Your HyperDrive session ended, so files can’t be read. Sign in again.',
  network: 'A file couldn’t be read because the cloud service or the HyperDrive server wasn’t reachable.',
  timeout: 'Reading a file took too long and was stopped.',
  'rate-limited': 'The cloud service is limiting requests. Try again shortly.',
  'file-changed': 'A file changed in the cloud while it was open. Close it and open it again.',
  'unsupported-file': 'A file can’t be read through the drive (for example, its size or version isn’t known).',
  'not-found': 'A file no longer exists in the cloud.',
  other: 'A file couldn’t be read through the drive.'
};

function Activity({ activity }: { activity: VirtualDriveActivity }) {
  return (
    <>
      <dl className="facts small" aria-label="Drive activity">
        <dt>Open files</dt>
        <dd>{activity.openFiles}</dd>
        <dt>Reads by the drive</dt>
        <dd>{activity.reads}</dd>
        <dt>Returned to Windows</dt>
        <dd>{formatSize(activity.bytesReturned)}</dd>
        <dt>Downloaded from the cloud</dt>
        <dd>{formatSize(activity.providerBytes)}</dd>
        <dt>From the local encrypted cache</dt>
        <dd>{formatSize(activity.cacheBytes)}</dd>
      </dl>
      <p className="muted small">Counted by the local agent since it started. Windows may answer repeated reads from its own memory without asking the drive.</p>
      {activity.lastIssue && (
        <div className="notice notice-warn" role="status">
          {READ_ISSUE_MESSAGES[activity.lastIssue]}
        </div>
      )}
    </>
  );
}

function headline(s: VirtualDriveStatus): string {
  switch (s.state) {
    case 'prerequisite-missing':
      return 'Not available on this PC yet';
    case 'signed-out':
      return 'Sign in to use the virtual drive';
    case 'no-accounts':
      return 'Connect a cloud account first';
    case 'ready':
      return `Ready to mount as ${s.driveLetter}:`;
    case 'mounting':
      return `Mounting ${s.driveLetter}:…`;
    case 'mounted':
      return `Mounted as ${s.driveLetter}: — read-only virtual drive`;
    case 'unmounting':
      return 'Unmounting…';
    case 'failed':
      return 'The virtual drive isn’t mounted';
  }
}

const PHASES: Record<NonNullable<VirtualDriveStatus['phase']>, string> = {
  'process-started': 'Drive process started',
  'agent-authenticated': 'Connected to the local agent',
  'volume-created': 'Volume created',
  mounted: 'Mounted'
};

/** Virtual Drive card (Batch 8/9). Shows only states and counters the main process confirmed. */
export function VirtualDriveCard({ vfs }: { vfs: VirtualDriveApi }) {
  const [status, setStatus] = useState<VirtualDriveStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [openError, setOpenError] = useState(false);

  const refresh = useCallback(() => {
    void vfs.getStatus().then(setStatus).catch(() => setStatus(null));
  }, [vfs]);

  useEffect(() => {
    refresh();
    return vfs.onChange(setStatus);
  }, [vfs, refresh]);

  // While mounted, refresh the agent's counters now and then (display only; reads never depend on it).
  const mounted = status?.state === 'mounted';
  useEffect(() => {
    if (!mounted) return undefined;
    const timer = setInterval(refresh, 5_000);
    return () => clearInterval(timer);
  }, [mounted, refresh]);

  const run = async (action: () => Promise<VirtualDriveStatus>) => {
    setBusy(true);
    setOpenError(false);
    try {
      setStatus(await action());
    } finally {
      setBusy(false);
    }
  };

  const changeLetter = async (letter: string) => {
    const result = await vfs.setDriveLetter({ letter });
    if (result.ok) setStatus(result.value);
  };

  const open = async () => {
    const result = await vfs.openInExplorer();
    setOpenError(!result.ok);
  };

  return (
    <article className="card" aria-labelledby="drive-card-title">
      <h2 id="drive-card-title">Virtual drive</h2>
      {!status ? (
        <p className="muted">Checking…</p>
      ) : (
        <>
          <p className="empty-title">{headline(status)}</p>
          {status.state === 'prerequisite-missing' && status.prerequisite && (
            <p className="muted small">{PREREQUISITE_MESSAGES[status.prerequisite]}</p>
          )}
          {status.state === 'failed' && status.error && (
            <div className="notice notice-warn" role="alert">
              {ERROR_MESSAGES[status.error]}
            </div>
          )}
          {status.state === 'mounting' && status.phase && <p className="muted small">{PHASES[status.phase]}…</p>}
          <p className="muted small">{READ_ONLY_NOTICE}</p>
          {status.state === 'mounted' && status.activity && <Activity activity={status.activity} />}
          {(status.state === 'ready' || status.state === 'failed') && (
            <label className="small">
              Drive letter{' '}
              <select
                aria-label="Drive letter"
                value={status.driveLetter}
                onChange={(e) => void changeLetter(e.target.value)}
                disabled={busy}
              >
                {LETTERS.map((l) => (
                  <option key={l} value={l}>
                    {l}:
                  </option>
                ))}
              </select>
            </label>
          )}
          <div className="actions">
            {(status.state === 'ready' || status.state === 'failed') && (
              <button type="button" className="button" onClick={() => void run(() => vfs.mount())} disabled={busy}>
                Mount
              </button>
            )}
            {status.state === 'mounted' && (
              <>
                <button type="button" className="button" onClick={() => void open()}>
                  Open in Explorer
                </button>
                <button type="button" className="button button-secondary" onClick={() => void run(() => vfs.unmount())} disabled={busy}>
                  Unmount
                </button>
              </>
            )}
          </div>
          {openError && <p className="muted small">Explorer couldn’t be opened.</p>}
        </>
      )}
    </article>
  );
}
