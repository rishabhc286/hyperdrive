import { useRef, useState } from 'react';
import type { ConnectedAccount, ReadDiagnostic, ReadError, StorageApi, StorageFile } from '../../shared/storage';
import { MAX_READ_BYTES } from '../../shared/storage';
import { formatSize, providerName } from './storage-format';

/** Whether the "Verify file read" diagnostic may be offered for this file. */
export function canVerifyRead(file: StorageFile, account: ConnectedAccount): boolean {
  return (
    file.kind === 'FILE' &&
    file.canDownload &&
    file.sizeBytes !== null &&
    account.status === 'CONNECTED' &&
    account.contentReadGranted
  );
}

const MESSAGES: Record<ReadError, string> = {
  'unsupported-file': 'This item can’t be read as an ordinary file (folders, shortcuts, native documents and restricted files aren’t supported).',
  'content-permission-missing':
    'This account was connected without permission to read file contents. Reconnect it in Cloud Accounts to grant read access.',
  'needs-reconnect': 'This account needs to be reconnected in Cloud Accounts.',
  'agent-unavailable': 'The local agent isn’t running. Check the Local agent card on the Dashboard.',
  'not-found': 'The file wasn’t found. It may have been moved or deleted.',
  forbidden: 'The provider refused access to this file.',
  'file-changed': 'The file changed since it was listed. Refresh the folder and try again.',
  'rate-limited': 'Too many requests right now. Wait a moment and try again.',
  'provider-unavailable': 'The provider didn’t return a valid response. Try again later.',
  'range-not-supported': 'The provider didn’t honor the byte range, so the read was stopped.',
  'credential-rejected': 'The provider rejected the delegated access. Try again; if it persists, reconnect the account.',
  'invalid-request': 'The offset or length isn’t valid.',
  'session-expired': 'Your session has expired. Sign in again.',
  'backend-unreachable': 'The HyperDrive server couldn’t be reached.',
  cancelled: 'The read was cancelled.',
  unknown: 'The read failed for an unexpected reason.'
};

/**
 * Development diagnostic: reads one bounded byte range of a listed file through the local agent and
 * shows only facts about it (sizes, time, SHA-256). The bytes never reach this page. This is not
 * opening, playback, downloading, caching or a mounted drive.
 */
export function VerifyRead({
  storage,
  account,
  file,
  onClose
}: {
  storage: StorageApi;
  account: ConnectedAccount;
  file: StorageFile;
  onClose: () => void;
}) {
  const size = file.sizeBytes ?? 0;
  const [offset, setOffset] = useState('0');
  const [length, setLength] = useState(String(Math.max(1, Math.min(size, MAX_READ_BYTES))));
  const [state, setState] = useState<{ status: 'idle' } | { status: 'running' } | { status: 'done'; result: ReadDiagnostic }>({
    status: 'idle'
  });
  const running = useRef(false);

  const parsedOffset = /^\d{1,16}$/.test(offset) ? Number(offset) : NaN;
  const parsedLength = /^\d{1,8}$/.test(length) ? Number(length) : NaN;
  const valid =
    Number.isSafeInteger(parsedOffset) && Number.isSafeInteger(parsedLength) && parsedLength >= 1 && parsedLength <= MAX_READ_BYTES;

  const run = async () => {
    if (!valid || running.current) return;
    running.current = true;
    setState({ status: 'running' });
    try {
      const result = await storage.verifyFileRead({ accountId: account.id, fileId: file.providerFileId, offset: parsedOffset, length: parsedLength });
      setState({ status: 'done', result });
    } catch {
      setState({ status: 'done', result: { ok: false, error: 'unknown' } });
    } finally {
      running.current = false;
    }
  };

  return (
    <section className="card verify-read" aria-labelledby="verify-read-title">
      <h2 id="verify-read-title">Verify file read</h2>
      <p className="muted small">
        Reads one byte range of “{file.name}” ({formatSize(file.sizeBytes)}) from {providerName(account.provider)} through the
        local agent and shows a SHA-256 digest. The bytes aren’t shown, saved or cached. This is a diagnostic, not opening or
        downloading the file.
      </p>
      <div className="verify-read-fields">
        <label className="field-label" htmlFor="verify-offset">
          Offset (bytes)
        </label>
        <input id="verify-offset" className="input" inputMode="numeric" value={offset} onChange={(e) => setOffset(e.target.value.trim())} />
        <label className="field-label" htmlFor="verify-length">
          Length (bytes, at most 4 MiB = {MAX_READ_BYTES.toLocaleString('en-US')})
        </label>
        <input id="verify-length" className="input" inputMode="numeric" value={length} onChange={(e) => setLength(e.target.value.trim())} />
      </div>
      {!valid && (
        <p className="field-error" role="alert">
          Enter a whole-number offset and a length from 1 to {MAX_READ_BYTES.toLocaleString('en-US')} bytes.
        </p>
      )}
      <div className="actions">
        <button type="button" className="button button-small" disabled={!valid || state.status === 'running'} onClick={() => void run()}>
          {state.status === 'running' ? 'Reading…' : 'Verify read'}
        </button>
        <button type="button" className="button button-secondary button-small" onClick={onClose}>
          Close
        </button>
      </div>
      {state.status === 'done' &&
        (state.result.ok ? (
          <dl className="verify-read-result" aria-label="Read result">
            <dt>Result</dt>
            <dd>Success</dd>
            <dt>Offset</dt>
            <dd>{state.result.offset.toLocaleString('en-US')}</dd>
            <dt>Bytes requested</dt>
            <dd>{state.result.bytesRequested.toLocaleString('en-US')}</dd>
            <dt>Bytes received</dt>
            <dd>
              {state.result.bytesReceived.toLocaleString('en-US')}
              {state.result.eof ? ' (reached end of file)' : ''}
            </dd>
            <dt>Time</dt>
            <dd>{state.result.elapsedMs} ms</dd>
            <dt>SHA-256</dt>
            <dd className="mono">{state.result.sha256}</dd>
            <dt>Revision</dt>
            <dd>
              {state.result.revisionPinned
                ? 'Pinned to the listed revision'
                : 'Not pinned: the provider served its current version (size was checked)'}
            </dd>
          </dl>
        ) : (
          <div className="notice notice-warn" role="alert">
            {MESSAGES[state.result.error]}
          </div>
        ))}
    </section>
  );
}
