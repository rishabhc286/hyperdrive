/**
 * Main-process configuration. Read from the process environment only; the renderer has no
 * influence over these values. No .env file is loaded automatically (see docs/development.md).
 */

export const DEFAULT_BACKEND_URL = 'http://127.0.0.1:18080';
export const BACKEND_URL_ENV = 'HYPERDRIVE_BACKEND_URL';
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);

export type BackendUrlResult =
  | { readonly ok: true; readonly url: URL }
  | { readonly ok: false; readonly error: string };

/**
 * Resolves the backend base URL. Accepts only an http(s) origin with no credentials, path,
 * query or fragment, so configuration cannot smuggle in an arbitrary request target.
 */
export function resolveBackendUrl(env: NodeJS.ProcessEnv): BackendUrlResult {
  const raw = env[BACKEND_URL_ENV]?.trim() || DEFAULT_BACKEND_URL;

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { ok: false, error: `${BACKEND_URL_ENV} is not a valid URL` };
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { ok: false, error: `${BACKEND_URL_ENV} must use http or https` };
  }
  // Provider access tokens are delegated over this connection (Batch 6): plain http is allowed only
  // for an explicit loopback development backend. TLS certificate validation is never disabled.
  if (url.protocol === 'http:' && !LOOPBACK_HOSTS.has(url.hostname)) {
    return { ok: false, error: `${BACKEND_URL_ENV} must use https unless it points at this machine (127.0.0.1 or localhost)` };
  }
  if (url.username || url.password) {
    return { ok: false, error: `${BACKEND_URL_ENV} must not contain credentials` };
  }
  if ((url.pathname !== '/' && url.pathname !== '') || url.search || url.hash) {
    return { ok: false, error: `${BACKEND_URL_ENV} must be an origin only, e.g. ${DEFAULT_BACKEND_URL}` };
  }

  return { ok: true, url: new URL(url.origin) };
}
