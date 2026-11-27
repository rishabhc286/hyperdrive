/**
 * Local agent status as seen by the renderer. Deliberately contains no port, secret, process id,
 * path or launch parameter: the renderer can only read status and ask for a bounded restart.
 */

export type AgentState = 'stopped' | 'starting' | 'ready' | 'stopping' | 'failed' | 'restarting';

/** Stable, non-sensitive failure reasons (mapped to actionable text in the UI). */
export type AgentErrorCode =
  | 'java-not-found'
  | 'java-incompatible'
  | 'artifact-missing'
  | 'not-packaged'
  | 'spawn-failed'
  | 'startup-timeout'
  | 'handshake-invalid'
  | 'protocol-mismatch'
  | 'bootstrap-rejected'
  | 'data-dir-locked'
  | 'exited-before-ready'
  | 'health-failed'
  | 'unexpected-exit';

/** What the agent itself reports it can do. */
export interface AgentCapabilities {
  readonly providerReads: boolean;
  readonly rangeReads: boolean;
  readonly chunkCache: boolean;
  readonly virtualDrive: boolean;
  /** Batch 9: read-only file content through the mounted drive. */
  readonly virtualDriveContent: boolean;
}

export interface AgentStatus {
  readonly state: AgentState;
  readonly agentVersion: string | null;
  /** From the agent's authenticated status endpoint; null unless ready. */
  readonly uptimeMillis: number | null;
  readonly capabilities: AgentCapabilities | null;
  readonly error: AgentErrorCode | null;
  /** Remaining automatic restarts after unexpected exits (bounded). */
  readonly autoRestartsRemaining: number;
}

export interface AgentApi {
  getStatus(): Promise<AgentStatus>;
  /** Stops the owned agent (if any) and starts a new one. Deduplicated in the main process. */
  restart(): Promise<AgentStatus>;
  onStatusChange(listener: (status: AgentStatus) => void): () => void;
}

export const AgentIpcChannel = {
  getStatus: 'hyperdrive:agent:get-status',
  restart: 'hyperdrive:agent:restart',
  statusChanged: 'hyperdrive:agent:status-changed'
} as const;
