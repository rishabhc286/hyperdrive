import type { AgentErrorCode, AgentStatus } from '../../shared/agent';

export function agentStateLabel(status: AgentStatus | null): string {
  switch (status?.state) {
    case undefined:
      return 'Checking…';
    case 'starting':
      return 'Starting';
    case 'ready':
      return 'Ready';
    case 'stopping':
      return 'Stopping';
    case 'stopped':
      return 'Stopped';
    case 'restarting':
      return 'Restarting';
    case 'failed':
      return 'Failed';
  }
}

/** Actionable, path-free messages for each failure code. */
export function agentErrorMessage(code: AgentErrorCode): string {
  switch (code) {
    case 'java-not-found':
      return 'Java 21 or newer wasn’t found. Install a JDK and set JAVA_HOME (or HYPERDRIVE_AGENT_JAVA), then restart the agent.';
    case 'java-incompatible':
      return 'The Java runtime found is older than 21, or its version couldn’t be checked. Point JAVA_HOME or HYPERDRIVE_AGENT_JAVA to Java 21 or newer.';
    case 'artifact-missing':
      return 'The agent hasn’t been built yet. In the agent folder run .\\mvnw.cmd package, then restart the agent.';
    case 'not-packaged':
      return 'This build doesn’t include the local agent. Packaging it with a bundled Java runtime is future installer work.';
    case 'startup-timeout':
      return 'The agent didn’t become ready in time.';
    case 'protocol-mismatch':
    case 'handshake-invalid':
    case 'bootstrap-rejected':
      return 'The agent and this app don’t speak the same protocol version. Rebuild the agent from this checkout.';
    case 'data-dir-locked':
      return 'Another HyperDrive agent is already using this profile’s agent folder. Close other HyperDrive windows or processes, then restart.';
    case 'spawn-failed':
    case 'exited-before-ready':
    case 'health-failed':
      return 'The agent stopped during startup. Details are in the developer console of the main process.';
    case 'unexpected-exit':
      return 'The agent stopped unexpectedly.';
  }
}

export function formatUptime(ms: number | null): string {
  if (ms === null) return '—';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  return `${h} h ${m % 60} min`;
}

interface Props {
  readonly status: AgentStatus | null;
  readonly restarting: boolean;
  readonly onRestart: () => void;
}

/**
 * Local agent card. "Ready" means only that the agent process runs and answers authenticated
 * requests; the card says plainly what it does (bounded reads, encrypted cache, drive metadata).
 */
export function AgentStatusCard({ status, restarting, onRestart }: Props) {
  const busy =
    restarting || status === null || status.state === 'starting' || status.state === 'stopping' || status.state === 'restarting';
  return (
    <article className="card" aria-labelledby="agent-card-title">
      <h2 id="agent-card-title">Local agent</h2>
      <p aria-live="polite" data-testid="agent-state">
        Local agent: {agentStateLabel(status)}
      </p>
      {status?.state === 'ready' && (
        <dl className="facts small">
          <dt>Version</dt>
          <dd>{status.agentVersion ?? '—'}</dd>
          <dt>Uptime</dt>
          <dd>{formatUptime(status.uptimeMillis)}</dd>
        </dl>
      )}
      {status?.error && (
        <p className="small" role={status.state === 'failed' ? 'alert' : undefined}>
          {agentErrorMessage(status.error)}
        </p>
      )}
      <p className="muted small">
        Ready means the HyperDrive agent process is running on this PC and answering.
        {status?.capabilities?.rangeReads
          ? ' It can read bounded byte ranges (at most 4 MiB at a time) of your files from Google Drive or Dropbox when you run “Verify file read”, using short-lived access the HyperDrive server delegates to it in memory.'
          : ' It doesn’t read files from Google Drive or Dropbox.'}{' '}
        {status?.capabilities?.chunkCache ? ' It keeps an encrypted cache of eligible Dropbox file pieces on this PC.' : ''}
        {status?.capabilities?.virtualDrive
          ? ' It serves folder and file details (not contents) to the virtual drive while it’s mounted.'
          : ''}{' '}
        Browsing your cloud accounts in this window doesn’t depend on it.
      </p>
      <button type="button" className="button button-secondary" onClick={onRestart} disabled={busy}>
        {restarting || status?.state === 'restarting' ? 'Restarting…' : 'Restart agent'}
      </button>
    </article>
  );
}
