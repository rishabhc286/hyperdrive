// @vitest-environment node
import { request } from 'node:http';
import { afterEach, describe, expect, it } from 'vitest';
import { LoopbackAbortedError, LoopbackTimeoutError, startLoopbackListener, type LoopbackHandle } from './loopback';

const STATE = 'expected-state-value-0123456789abcdef';
const CODE = `hdc_${'A'.repeat(43)}`;
const handles: LoopbackHandle[] = [];

async function start(timeoutMs = 5000, signal?: AbortSignal): Promise<LoopbackHandle> {
  const handle = await startLoopbackListener({ expectedState: STATE, timeoutMs, ...(signal ? { signal } : {}) });
  handles.push(handle);
  return handle;
}

/** Raw HTTP GET so the Host header can be controlled. */
function get(port: number, path: string, host = `127.0.0.1:${port}`): Promise<number> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, path, method: 'GET', headers: { Host: host } }, (res) => {
      res.resume();
      resolve(res.statusCode ?? 0);
    });
    req.on('error', reject);
    req.end();
  });
}

afterEach(() => {
  handles.splice(0).forEach((h) => h.close());
});

describe('loopback callback listener', () => {
  it('binds to 127.0.0.1 on an OS-assigned port', async () => {
    const handle = await start();
    expect(handle.port).toBeGreaterThan(1023);
    expect(handle.isListening()).toBe(true);
  });

  it('accepts the expected state with a handoff code, then closes', async () => {
    const handle = await start();
    expect(await get(handle.port, `/callback?code=${CODE}&state=${STATE}`)).toBe(200);
    await expect(handle.result).resolves.toEqual({ type: 'code', code: CODE });
    expect(handle.isListening()).toBe(false);
    await expect(get(handle.port, '/callback')).rejects.toThrow();
  });

  it('rejects a wrong state without ending the attempt', async () => {
    const handle = await start();
    expect(await get(handle.port, `/callback?code=${CODE}&state=attacker-state`)).toBe(400);
    expect(await get(handle.port, `/callback?code=${CODE}`)).toBe(400);
    expect(handle.isListening()).toBe(true);

    // The genuine callback still works afterwards.
    expect(await get(handle.port, `/callback?code=${CODE}&state=${STATE}`)).toBe(200);
    await expect(handle.result).resolves.toMatchObject({ type: 'code' });
  });

  it('rejects malformed codes, unknown errors, other paths and foreign Host headers', async () => {
    const handle = await start();
    expect(await get(handle.port, `/callback?code=not-a-code&state=${STATE}`)).toBe(400);
    expect(await get(handle.port, `/callback?error=something_else&state=${STATE}`)).toBe(400);
    expect(await get(handle.port, `/other?code=${CODE}&state=${STATE}`)).toBe(404);
    expect(await get(handle.port, `/callback?code=${CODE}&state=${STATE}`, `evil.example:${handle.port}`)).toBe(400);
    expect(handle.isListening()).toBe(true);
  });

  it('reports a cancelled Google sign-in', async () => {
    const handle = await start();
    expect(await get(handle.port, `/callback?error=access_denied&state=${STATE}`)).toBe(200);
    await expect(handle.result).resolves.toEqual({ type: 'error', error: 'access_denied' });
    expect(handle.isListening()).toBe(false);
  });

  it('times out and closes', async () => {
    const handle = await start(50);
    await expect(handle.result).rejects.toBeInstanceOf(LoopbackTimeoutError);
    expect(handle.isListening()).toBe(false);
  });

  it('closes when the attempt is aborted', async () => {
    const controller = new AbortController();
    const handle = await start(5000, controller.signal);
    controller.abort();
    await expect(handle.result).rejects.toBeInstanceOf(LoopbackAbortedError);
    expect(handle.isListening()).toBe(false);
  });

  it('close() is idempotent', async () => {
    const handle = await start();
    handle.close();
    handle.close();
    expect(handle.isListening()).toBe(false);
  });
});
