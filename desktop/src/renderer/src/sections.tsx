import type { ReactNode } from 'react';
import type { BackendStatus } from './useBackendHealth';

export const SECTIONS = [
  { id: 'overview', label: 'Overview' },
  { id: 'files', label: 'Files' },
  { id: 'accounts', label: 'Cloud Accounts' },
  { id: 'performance', label: 'Performance' },
  { id: 'settings', label: 'Settings' }
] as const;

export type SectionId = (typeof SECTIONS)[number]['id'];

interface SectionViewProps {
  readonly section: SectionId;
  readonly backendStatus: BackendStatus;
  readonly onRecheckBackend: () => void;
  readonly onNavigate: (section: SectionId) => void;
}

export function SectionView(props: SectionViewProps) {
  switch (props.section) {
    case 'overview':
      return <Overview {...props} />;
    case 'files':
      return <Files />;
    case 'accounts':
      return <CloudAccounts />;
    case 'performance':
      return <Performance />;
    case 'settings':
      return <Settings backendStatus={props.backendStatus} />;
  }
}

const NO_ACCOUNTS = 'No cloud accounts connected.';
const LATER_BATCH_NOTE =
  'Account connection is not available yet. Sign-in and cloud connections arrive in a later development batch.';

function Overview({ backendStatus, onRecheckBackend, onNavigate }: SectionViewProps) {
  return (
    <section aria-labelledby="overview-title">
      <h1 id="overview-title">Overview</h1>
      <div className="cards">
        <article className="card" aria-labelledby="backend-card-title">
          <h2 id="backend-card-title">Backend</h2>
          <p>{backendDescription(backendStatus)}</p>
          <button
            type="button"
            className="button"
            onClick={onRecheckBackend}
            disabled={backendStatus.state === 'checking'}
          >
            Check again
          </button>
        </article>

        <article className="card" aria-labelledby="accounts-card-title">
          <h2 id="accounts-card-title">Cloud accounts</h2>
          <p className="empty-title">{NO_ACCOUNTS}</p>
          <p className="muted">Google Drive and Dropbox support is planned for the first release.</p>
          <button type="button" className="button button-secondary" onClick={() => onNavigate('accounts')}>
            View Cloud Accounts
          </button>
        </article>

        <article className="card" aria-labelledby="drive-card-title">
          <h2 id="drive-card-title">Virtual drive</h2>
          <p className="muted">
            The read-only Windows virtual drive is not part of this build, so no drive is created.
          </p>
        </article>
      </div>
    </section>
  );
}

function backendDescription(status: BackendStatus): string {
  switch (status.state) {
    case 'checking':
      return 'Checking whether the HyperDrive backend is reachable…';
    case 'connected':
      return 'The HyperDrive backend is running and responded to its health check.';
    case 'unavailable':
      switch (status.reason) {
        case 'timeout':
          return 'The HyperDrive backend did not respond in time.';
        case 'unexpected-response':
          return 'Something answered at the backend address, but it did not respond like the HyperDrive backend.';
        case 'misconfigured':
          return 'The backend address is not configured correctly. Check HYPERDRIVE_BACKEND_URL.';
        case 'no-bridge':
          return 'The desktop bridge is unavailable, so the backend cannot be checked.';
        case 'unreachable':
          return 'The HyperDrive backend could not be reached. Start it and check again.';
      }
  }
}

function EmptyState({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      <div className="muted">{children}</div>
    </div>
  );
}

function Files() {
  return (
    <section aria-labelledby="files-title">
      <h1 id="files-title">Files</h1>
      <EmptyState title="No files to show.">
        <p>
          Files from connected cloud accounts will appear here. There are no connected accounts, and
          file browsing is not implemented in this build.
        </p>
      </EmptyState>
    </section>
  );
}

function CloudAccounts() {
  return (
    <section aria-labelledby="accounts-title">
      <h1 id="accounts-title">Cloud Accounts</h1>
      <EmptyState title={NO_ACCOUNTS}>
        <p id="connect-unavailable-note">{LATER_BATCH_NOTE}</p>
      </EmptyState>
      <ul className="providers" aria-label="Cloud storage providers">
        <li className="provider">
          <span className="provider-name">Google Drive</span>
          <button type="button" className="button" disabled aria-describedby="connect-unavailable-note">
            Connect Google Drive
          </button>
        </li>
        <li className="provider">
          <span className="provider-name">Dropbox</span>
          <button type="button" className="button" disabled aria-describedby="connect-unavailable-note">
            Connect Dropbox
          </button>
        </li>
      </ul>
    </section>
  );
}

function Performance() {
  return (
    <section aria-labelledby="performance-title">
      <h1 id="performance-title">Performance</h1>
      <EmptyState title="No performance data.">
        <p>
          Latency, throughput and cache hit ratio will be measured once file streaming exists. Nothing
          is measured or recorded in this build.
        </p>
      </EmptyState>
    </section>
  );
}

function Settings({ backendStatus }: { backendStatus: BackendStatus }) {
  return (
    <section aria-labelledby="settings-title">
      <h1 id="settings-title">Settings</h1>
      <EmptyState title="No settings to change yet.">
        <p>Cache location, cache size limit and startup behavior will become configurable in later batches.</p>
      </EmptyState>
      <h2 className="subheading">About this build</h2>
      <dl className="facts">
        <dt>Build</dt>
        <dd>Development build</dd>
        <dt>Backend</dt>
        <dd>{backendStatus.state === 'checking' ? 'Checking' : backendStatus.state === 'connected' ? 'Connected' : 'Unavailable'}</dd>
      </dl>
    </section>
  );
}
