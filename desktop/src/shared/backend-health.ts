/**
 * Types shared between the Electron main process, the preload script and the renderer.
 * This file must stay free of runtime imports so it can be used in every context.
 */

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
}
