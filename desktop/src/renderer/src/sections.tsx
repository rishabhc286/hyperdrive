import type { AgentStatus } from '../../shared/agent';
import { AgentStatusCard, agentStateLabel } from './AgentStatusCard';
import type { SafeUser } from '../../shared/auth';
import type { StorageApi, StorageProvider } from '../../shared/storage';
import type { CacheApi } from '../../shared/cache';
import { CacheMetrics, CacheSettingsPanel } from './CachePanels';
import type { VirtualDriveApi } from '../../shared/vfs';
import { VirtualDriveCard } from './VirtualDriveCard';
import { CloudAccounts } from './CloudAccounts';
import { FilesBrowser } from './FilesBrowser';
import { accountTitle } from './storage-format';
import type { AccountsState, ConnectionFlow } from './useStorage';
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
  readonly user: SafeUser;
  readonly backendStatus: BackendStatus;
  readonly onRecheckBackend: () => void;
  readonly onNavigate: (section: SectionId) => void;
  readonly storage: StorageApi;
  readonly cache: CacheApi;
  readonly vfs: VirtualDriveApi;
  readonly accounts: AccountsState;
  readonly onReloadAccounts: () => void;
  readonly connection: ConnectionFlow;
  readonly availability: ReadonlyMap<StorageProvider, boolean> | null;
  readonly onConnect: (provider: StorageProvider) => void;
  readonly onCancelConnection: () => void;
  readonly onDismissConnection: () => void;
  readonly agentStatus: AgentStatus | null;
  readonly agentRestarting: boolean;
  readonly onRestartAgent: () => void;
}

export function SectionView(props: SectionViewProps) {
  switch (props.section) {
    case 'overview':
      return <Overview {...props} />;
    case 'files':
      return (
        <FilesBrowser
          storage={props.storage}
          accounts={props.accounts}
          onReloadAccounts={props.onReloadAccounts}
          onOpenAccounts={() => props.onNavigate('accounts')}
        />
      );
    case 'accounts':
      return (
        <CloudAccounts
          storage={props.storage}
          accounts={props.accounts}
          onReloadAccounts={props.onReloadAccounts}
          connection={props.connection}
          availability={props.availability}
          onConnect={props.onConnect}
          onCancelConnection={props.onCancelConnection}
          onDismissConnection={props.onDismissConnection}
        />
      );
    case 'performance':
      return <Performance cache={props.cache} />;
    case 'settings':
      return <Settings user={props.user} backendStatus={props.backendStatus} agentStatus={props.agentStatus} cache={props.cache} />;
  }
}

const NO_ACCOUNTS = 'No cloud accounts connected.';

function Overview({
  user,
  backendStatus,
  onRecheckBackend,
  onNavigate,
  accounts,
  agentStatus,
  agentRestarting,
  onRestartAgent,
  vfs
}: SectionViewProps) {
  const connected = accounts.accounts;
  return (
    <section aria-labelledby="overview-title">
      <h1 id="overview-title">Overview</h1>
      <p className="muted">Signed in as @{user.username}.</p>
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
          {connected.length === 0 ? (
            <>
              <p className="empty-title">{accounts.status === 'loading' ? 'Loading…' : NO_ACCOUNTS}</p>
              <p className="muted">Connect Google Drive or Dropbox to browse it here (read-only).</p>
            </>
          ) : (
            <>
              <p>
                {connected.length} storage {connected.length === 1 ? 'account' : 'accounts'} connected (read-only).
              </p>
              <ul className="small">
                {connected.map((a) => (
                  <li key={a.id}>
                    {accountTitle(a)}
                    {a.status === 'NEEDS_RECONNECT' ? ' (needs reconnect)' : ''}
                  </li>
                ))}
              </ul>
            </>
          )}
          <button type="button" className="button button-secondary" onClick={() => onNavigate('accounts')}>
            View Cloud Accounts
          </button>
        </article>

        <AgentStatusCard status={agentStatus} restarting={agentRestarting} onRestart={onRestartAgent} />

        <VirtualDriveCard vfs={vfs} />
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

function Performance({ cache }: { cache: CacheApi }) {
  return (
    <section aria-labelledby="performance-title">
      <h1 id="performance-title">Performance</h1>
      <p className="muted small">
        Real counters from the local agent's chunk cache. They come only from “Verify file read” diagnostics today:
        the virtual drive shows metadata only (no content reads yet), there is no playback or prefetching, and no
        throughput is measured.
      </p>
      <CacheMetrics cache={cache} />
    </section>
  );
}

function Settings({
  user,
  backendStatus,
  agentStatus,
  cache
}: {
  user: SafeUser;
  backendStatus: BackendStatus;
  agentStatus: AgentStatus | null;
  cache: CacheApi;
}) {
  return (
    <section aria-labelledby="settings-title">
      <h1 id="settings-title">Settings</h1>
      <CacheSettingsPanel cache={cache} />
      <h2 className="subheading">Account</h2>
      <dl className="facts">
        <dt>Username</dt>
        <dd>@{user.username}</dd>
        <dt>Name</dt>
        <dd>{user.displayName ?? "Not provided"}</dd>
        <dt>Signed in with</dt>
        <dd>Google (sign-in identity only; Drive is connected separately in Cloud Accounts)</dd>
      </dl>
      <h2 className="subheading">About this build</h2>
      <dl className="facts">
        <dt>Build</dt>
        <dd>Development build</dd>
        <dt>Backend</dt>
        <dd>{backendStatus.state === 'checking' ? 'Checking' : backendStatus.state === 'connected' ? 'Connected' : 'Unavailable'}</dd>
        <dt>Local agent</dt>
        <dd>
          {agentStateLabel(agentStatus)}
          {agentStatus?.state === 'ready' && agentStatus.agentVersion ? ` (version ${agentStatus.agentVersion})` : ''}
        </dd>
        <dt>Virtual drive</dt>
        <dd>Read-only, metadata only (manage it on Overview)</dd>
      </dl>
    </section>
  );
}
