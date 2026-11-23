/**
 * Types shared between the Electron main process, the preload script and the renderer.
 * This file must stay free of runtime imports so it can be used in every context.
 */
import type { AgentApi } from './agent';
import type { CacheApi } from './cache';
import type { VirtualDriveApi } from './vfs';
import type { AuthApi } from './auth';
import type { StorageApi } from './storage';

/** Why the backend is considered unavailable. Deliberately coarse: no URLs or error text. */
export type BackendUnavailableReason =
  | 'unreachable'
  | 'timeout'
  | 'unexpected-response'
  | 'misconfigured';

export type BackendHealth =
  | { readonly state: 'connected' }
  | { readonly state: 'unavailable'; readonly reason: BackendUnavailableReason };

/** IPC channel names. The renderer never sees these; only the preload script uses them. */
export const IpcChannel = {
  backendHealth: 'hyperdrive:backend-health'
} as const;

/** The only API exposed to the renderer through the context bridge. */
export interface HyperdriveApi {
  checkBackendHealth(): Promise<BackendHealth>;
  readonly auth: AuthApi;
  readonly storage: StorageApi;
  readonly agent: AgentApi;
  readonly cache: CacheApi;
  readonly vfs: VirtualDriveApi;
}
