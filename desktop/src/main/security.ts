export interface RendererOrigin {
  /** electron-vite dev server URL, set only during `npm run dev`. */
  readonly devServerUrl?: string | undefined;
  /** file:// URL of the bundled renderer entry, used in built/production mode. */
  readonly rendererFileUrl: string;
}

/**
 * Decides which URLs the app's own renderer may be loaded from. Used to block unexpected
 * navigation and to reject IPC calls from any frame that is not the app's renderer.
 */
export function isTrustedRendererUrl(candidate: string, origin: RendererOrigin): boolean {
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return false;
  }

  if (origin.devServerUrl) {
    // Development: only the electron-vite dev server origin.
    try {
      return url.origin === new URL(origin.devServerUrl).origin;
    } catch {
      return false;
    }
  }

  // Built app: only the exact bundled renderer file (ignoring any #fragment or ?query).
  if (url.protocol !== 'file:') {
    return false;
  }
  url.hash = '';
  url.search = '';
  return url.href === new URL(origin.rendererFileUrl).href;
}
