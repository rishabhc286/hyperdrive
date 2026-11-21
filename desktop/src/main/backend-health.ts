import type { BackendHealth } from '../shared/backend-health';

export const HEALTH_PATH = '/api/v1/health';
export const HEALTH_TIMEOUT_MS = 3000;
const EXPECTED_SERVICE = 'hyperdrive-backend';

type FetchLike = (input: URL, init: RequestInit) => Promise<Response>;

/**
 * Calls the backend health endpoint. Never throws: every failure maps to an "unavailable" state
 * so a missing backend cannot crash the desktop app.
 */
export async function checkBackendHealth(
  baseUrl: URL,
  fetchImpl: FetchLike = fetch,
  timeoutMs: number = HEALTH_TIMEOUT_MS
): Promise<BackendHealth> {
  let response: Response;
  try {
    response = await fetchImpl(new URL(HEALTH_PATH, baseUrl), {
      method: 'GET',
      headers: { Accept: 'application/json' },
      redirect: 'error',
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs)
    });
  } catch (error) {
    const timedOut = error instanceof DOMException && error.name === 'TimeoutError';
    return { state: 'unavailable', reason: timedOut ? 'timeout' : 'unreachable' };
  }

  if (!response.ok) {
    return { state: 'unavailable', reason: 'unexpected-response' };
  }

  try {
    const body: unknown = await response.json();
    if (isHealthyBody(body)) {
      return { state: 'connected' };
    }
  } catch {
    // Body was not JSON (or the read timed out); fall through.
  }
  return { state: 'unavailable', reason: 'unexpected-response' };
}

function isHealthyBody(body: unknown): boolean {
  if (typeof body !== 'object' || body === null) {
    return false;
  }
  const record = body as Record<string, unknown>;
  return record.status === 'UP' && record.service === EXPECTED_SERVICE;
}
