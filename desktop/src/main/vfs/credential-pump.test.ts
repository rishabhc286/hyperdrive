// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { SessionExpiredError } from '../auth/auth-manager';
import { ApiError, BackendUnreachableError, type ReadCredential } from '../auth/backend-api';
import { classifyRenewalFailure, VfsCredentialPump, type PumpPath } from './credential-pump';

const A = '11111111-2222-4333-8444-555555555555';
const B = '66666666-7777-4888-9999-aaaaaaaaaaaa';
const enc = new TextEncoder();
const dec = new TextDecoder();

function credential(accountId: string): ReadCredential {
  return {
    accountId,
    provider: accountId === A ? 'DROPBOX' : 'GOOGLE_DRIVE',
    accessToken: `token-for-${accountId}`,
    accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    rootNamespaceId: accountId === A ? '777' : null
  };
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/**
 * A scripted agent: each long poll takes the next queued answer (or waits until the test queues one,
 * or until aborted). Installs/failures are recorded.
 */
function scriptedAgent() {
  const polls: { generation: number }[] = [];
  const queue: { status: number; requests?: string[] }[] = [];
  let wake: (() => void) | null = null;
  const installs: Record<string, unknown>[] = [];
  const failures: Record<string, unknown>[] = [];
  let installStatus = 201;
  const post = vi.fn(async (path: PumpPath, body: unknown, _timeout: number, signal: AbortSignal) => {
    const b = body as Record<string, unknown>;
    if (path === '/local/v1/vfs/credential-requests') {
      polls.push({ generation: b['generation'] as number });
      while (queue.length === 0) {
        if (signal.aborted) throw new Error('aborted');
        await new Promise<void>((r) => {
          wake = r;
          signal.addEventListener('abort', () => r(), { once: true });
        });
      }
      const next = queue.shift()!;
      return { status: next.status, body: enc.encode(JSON.stringify({ requests: (next.requests ?? []).map((accountId) => ({ accountId })) })) };
    }
    if (path === '/local/v1/vfs/mount/credential') {
      installs.push(b);
      return { status: installStatus, body: enc.encode('{}') };
    }
    failures.push(b);
    return { status: 200, body: enc.encode('{}') };
  });
  return {
    post,
    polls,
    installs,
    failures,
    answer: (a: { status: number; requests?: string[] }) => {
      queue.push(a);
      wake?.();
    },
    setInstallStatus: (s: number) => (installStatus = s)
  };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('VfsCredentialPump', () => {
  it('renews on the agent\'s request in the main process and installs for the same generation only', async () => {
    const agent = scriptedAgent();
    const issueCredential = vi.fn(async (id: string) => credential(id));
    const pump = new VfsCredentialPump({ post: agent.post, issueCredential, sleep: async () => undefined });
    const done = pump.start(7);
    agent.answer({ status: 200, requests: [A] });
    await vi.waitFor(() => expect(agent.installs).toHaveLength(1));
    expect(agent.installs[0]).toEqual({
      generation: 7,
      accountId: A,
      provider: 'DROPBOX',
      accessToken: `token-for-${A}`,
      providerExpiresAt: expect.any(String),
      rootNamespaceId: '777'
    });
    expect(agent.polls.every((p) => p.generation === 7)).toBe(true);
    agent.answer({ status: 404 }); // the generation ended in the agent: the pump stops by itself
    await done;
    expect(pump.generation()).toBe(7);
    pump.stop();
    expect(pump.generation()).toBeNull();
  });

  it('runs one renewal per account at a time even when the agent asks again', async () => {
    const agent = scriptedAgent();
    const pending = deferred<ReadCredential>();
    const issueCredential = vi.fn((id: string) => (id === A ? pending.promise : Promise.resolve(credential(id))));
    const pump = new VfsCredentialPump({ post: agent.post, issueCredential, sleep: async () => undefined });
    void pump.start(3);
    agent.answer({ status: 200, requests: [A, B, A] });
    agent.answer({ status: 200, requests: [A] }); // redelivered while the first is still running
    await vi.waitFor(() => expect(agent.installs).toHaveLength(1)); // B
    await flush();
    expect(issueCredential.mock.calls.filter(([id]) => id === A)).toHaveLength(1);
    pending.resolve(credential(A));
    await vi.waitFor(() => expect(agent.installs).toHaveLength(2));
    pump.stop();
  });

  it('never installs a credential that arrives after unmount or logout stopped the pump', async () => {
    const agent = scriptedAgent();
    const pending = deferred<ReadCredential>();
    const pump = new VfsCredentialPump({ post: agent.post, issueCredential: () => pending.promise, sleep: async () => undefined });
    void pump.start(5);
    agent.answer({ status: 200, requests: [A] });
    await vi.waitFor(() => expect(agent.polls.length).toBeGreaterThanOrEqual(2));
    pump.stop(); // unmount / logout / disconnect
    pending.resolve(credential(A));
    await flush();
    await flush();
    expect(agent.installs).toHaveLength(0);
    expect(agent.failures).toHaveLength(0);
  });

  it('reports backend refusals so waiting reads fail at once, without retrying', async () => {
    const agent = scriptedAgent();
    const issueCredential = vi.fn(async () => {
      throw new ApiError(409, 'account_needs_reconnect');
    });
    const pump = new VfsCredentialPump({ post: agent.post, issueCredential, sleep: async () => undefined });
    void pump.start(9);
    agent.answer({ status: 200, requests: [A] });
    await vi.waitFor(() => expect(agent.failures).toHaveLength(1));
    expect(agent.failures[0]).toEqual({ generation: 9, accountId: A, code: 'needs_reconnect' });
    expect(issueCredential).toHaveBeenCalledTimes(1);
    pump.stop();
  });

  it('a stale generation refused by the agent installs nothing and the pump ends', async () => {
    const agent = scriptedAgent();
    agent.setInstallStatus(404);
    const pump = new VfsCredentialPump({ post: agent.post, issueCredential: async (id) => credential(id), sleep: async () => undefined });
    const done = pump.start(1);
    agent.answer({ status: 200, requests: [B] });
    await vi.waitFor(() => expect(agent.installs).toHaveLength(1));
    expect(agent.failures).toHaveLength(0); // nothing to report for an old generation
    agent.answer({ status: 404 });
    await done;
  });

  it('ignores malformed account ids and backs off when the agent is unreachable', async () => {
    let calls = 0;
    const sleeps: number[] = [];
    const abort = deferred<undefined>();
    const post = vi.fn(async (path: PumpPath) => {
      if (path !== '/local/v1/vfs/credential-requests') return { status: 201, body: enc.encode('{}') };
      calls++;
      if (calls <= 3) throw new Error('ECONNREFUSED');
      if (calls === 4) return { status: 200, body: enc.encode(JSON.stringify({ requests: [{ accountId: '../../etc' }, { accountId: 42 }] })) };
      await abort.promise;
      return { status: 404, body: enc.encode('{}') };
    });
    const issueCredential = vi.fn(async (id: string) => credential(id));
    const pump = new VfsCredentialPump({ post, issueCredential, sleep: async (ms) => void sleeps.push(ms) });
    const done = pump.start(2);
    await vi.waitFor(() => expect(calls).toBe(5));
    expect(sleeps).toEqual([1_000, 2_000, 4_000]);
    expect(issueCredential).not.toHaveBeenCalled();
    abort.resolve(undefined);
    await done;
  });

  it('classifies delegation failures without leaking messages', () => {
    expect(classifyRenewalFailure(new SessionExpiredError())).toBe('session_expired');
    expect(classifyRenewalFailure(new BackendUnreachableError(false))).toBe('backend_unavailable');
    expect(classifyRenewalFailure(new ApiError(409, 'content_permission_missing'))).toBe('content_permission_missing');
    expect(classifyRenewalFailure(new ApiError(404, 'account_not_found'))).toBe('account_not_found');
    expect(classifyRenewalFailure(new ApiError(429, 'rate_limited'))).toBe('rate_limited');
    expect(classifyRenewalFailure(new Error('boom'))).toBe('backend_unavailable');
    expect(dec.decode(enc.encode('ok'))).toBe('ok');
  });
});
