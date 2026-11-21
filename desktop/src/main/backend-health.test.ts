// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { checkBackendHealth, HEALTH_PATH } from './backend-health';

const base = new URL('http://127.0.0.1:18080');

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}

describe('checkBackendHealth', () => {
  it('reports connected for the expected health body and calls only the health path', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(jsonResponse({ status: 'UP', service: 'hyperdrive-backend' })));

    await expect(checkBackendHealth(base, fetchImpl)).resolves.toEqual({ state: 'connected' });

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.href).toBe(`http://127.0.0.1:18080${HEALTH_PATH}`);
    expect(init.method).toBe('GET');
    expect(init.redirect).toBe('error');
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('reports unreachable when the request fails', async () => {
    const fetchImpl = vi.fn(() => Promise.reject(new TypeError('fetch failed')));
    await expect(checkBackendHealth(base, fetchImpl)).resolves.toEqual({
      state: 'unavailable',
      reason: 'unreachable'
    });
  });

  it('reports timeout when the backend does not answer in time', async () => {
    const fetchImpl = vi.fn(
      (_url: URL, init: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(init.signal?.reason));
        })
    );
    await expect(checkBackendHealth(base, fetchImpl, 20)).resolves.toEqual({
      state: 'unavailable',
      reason: 'timeout'
    });
  });

  it.each([
    ['a non-2xx status', () => jsonResponse({ status: 'UP', service: 'hyperdrive-backend' }, 503)],
    ['a different service', () => jsonResponse({ status: 'UP', service: 'something-else' })],
    ['a DOWN status', () => jsonResponse({ status: 'DOWN', service: 'hyperdrive-backend' })],
    ['a non-JSON body', () => new Response('<html>hello</html>', { status: 200 })]
  ])('reports unexpected-response for %s', async (_label, makeResponse) => {
    const fetchImpl = vi.fn(() => Promise.resolve(makeResponse()));
    await expect(checkBackendHealth(base, fetchImpl)).resolves.toEqual({
      state: 'unavailable',
      reason: 'unexpected-response'
    });
  });
});
