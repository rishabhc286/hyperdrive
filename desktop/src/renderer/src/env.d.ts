import type { HyperdriveApi } from '../../shared/backend-health';

declare global {
  interface Window {
    /** Provided by the preload script. Undefined if the renderer runs outside Electron. */
    readonly hyperdrive?: HyperdriveApi;
  }
}

export {};
