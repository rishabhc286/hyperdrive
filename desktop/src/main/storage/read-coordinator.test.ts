// @vitest-environment node
import { createHash } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { StorageFile } from '../../shared/storage';
import { AgentUnavailableError, type AgentPostPath, type AgentPostResponse } from '../agent/agent-manager';
import { SessionExpiredError } from '../auth/auth-manager';
import { ApiError, type ReadCredential } from '../auth/backend-api';
import { ReadCoordinator, type AgentPort } from './read-coordinator';

const ACCOUNT = '6f1c2a8e-1b2c-4d3e-8f90-123456789abc';
const TOKEN = 'ya29.coordinator-test-token-0123456789';
const enc = new TextEncoder();

const FILE: StorageFile = {
  providerFileId: 'file1',
  name: 'a.bin',
  kind: 'FILE',
  mimeType: 'application/octet-stream',
  sizeBytes: 1000,
  modifiedAt: null,
  canDownload: true,
  directoryLocator: null,
  providerRevision: 'rev1',
  contentHash: null,
  contentHashAlgorithm: null,
  shortcutTargetId: null,
  shortcutTargetMimeType: null
};

const credential = (): ReadCredential => ({
  accountId: ACCOUNT,
  provider: 'GOOGLE_DRIVE',
  accessToken: TOKEN,
  accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  rootNamespaceId: null
});

const json = (status: number, body: unknown): AgentPostResponse => ({
  status,
  headers: { 'content-type': 'application/json' },
  body: enc.encode(JSON.stringify(body))
});

const binary = (bytes: Uint8Array, eof = false): AgentPostResponse => ({
  status: 200,
  headers: {
    'content-type': 'application/octet-stream',
    'x-hyperdrive-read-length': String(bytes.byteLength),
    'x-hyperdrive-eof': String(eof),
    'x-hyperdrive-revision-pinned': 'false'
  },
  body: bytes
});

/** A scriptable agent double; reads answer from `readReplies` in order (default: success). */
function setup(options: { readReplies?: AgentPostResponse[]; instance?: string | null } = {}) {
  let handleCounter = 0;
  const calls: { path: AgentPostPath; body: Record<string, unknown> }[] = [];
  const readReplies = [...(options.readReplies ?? [])];
  let instance: string | null = options.instance === undefined ? 'inst-1' : options.instance;
  const agent: AgentPort & { setInstance(v: string | null): void; clearReply?: () => Promise<AgentPostResponse> } = {
    instanceId: () => instance,
    setInstance: (v) => {
      instance = v;
    },
    restart: vi.fn(() => Promise.resolve()),
    post: vi.fn(async (path: AgentPostPath, body: unknown) => {
      calls.push({ path, body: body as Record<string, unknown> });
      if (instance === null) throw new AgentUnavailableError();
      if (path === '/local/v1/credentials') {
        handleCounter += 1;
        return {
          ...json(201, {
            handle: `hdh_${'h'.repeat(30)}${handleCounter}`,
            retentionExpiresAt: new Date(Date.now() + 1_200_000).toISOString()
          }),
          instanceId: instance
        };
      }
      if (path === '/local/v1/credentials/clear') {
        if (agent.clearReply) return { ...(await agent.clearReply()), instanceId: instance };
        return { ...json(200, { cleared: 1 }), instanceId: instance };
      }
      const reply = readReplies.shift() ?? binary(new Uint8Array(100).fill(7));
      return { ...reply, instanceId: instance };
    })
  };
  const issueCredential = vi.fn((accountId: string) => {
    void accountId;
    return Promise.resolve(credential());
  });
  const getFile = vi.fn(() => Promise.resolve(FILE));
  const coordinator = new ReadCoordinator({ agent, issueCredential, getFile });
  const paths = () => calls.map((c) => c.path);
  return { coordinator, agent, issueCredential, getFile, calls, paths };
}

const request = { accountId: ACCOUNT, fileId: 'file1', offset: 0, length: 100 };

describe('ReadCoordinator', () => {
  it('delegates once, reads through the agent and returns only a digest', async () => {
    const t = setup();
    const result = await t.coordinator.verifyRead(request);

    expect(result).toMatchObject({ ok: true, provider: 'GOOGLE_DRIVE', bytesRequested: 100, bytesReceived: 100, offset: 0 });
    if (!result.ok) throw new Error('expected ok');
    expect(result.sha256).toBe(createHash('sha256').update(new Uint8Array(100).fill(7)).digest('hex'));
    expect(JSON.stringify(result)).not.toContain(TOKEN);
    expect(Object.keys(result)).not.toContain('bytes');
    expect(t.paths()).toEqual(['/local/v1/credentials', '/local/v1/reads']);
    // The token goes to the agent only; the read carries the opaque handle plus the listed metadata.
    expect(t.calls[0]!.body['accessToken']).toBe(TOKEN);
    expect(t.calls[1]!.body).toMatchObject({ fileId: 'file1', offset: 0, length: 100, expectedSize: 1000, expectedRevision: 'rev1' });
    expect(JSON.stringify(t.calls[1]!.body)).not.toContain(TOKEN);

    await t.coordinator.verifyRead(request);
    expect(t.issueCredential).toHaveBeenCalledTimes(1); // handle reused
  });

  it('zeroes the received bytes after hashing', async () => {
    const bytes = new Uint8Array(100).fill(9);
    const t = setup({ readReplies: [binary(bytes)] });
    await t.coordinator.verifyRead(request);
    expect(bytes.every((b) => b === 0)).toBe(true);
  });

  it('rejects out-of-bounds requests before any backend or agent call', async () => {
    const t = setup();
    for (const bad of [
      { ...request, length: 0 },
      { ...request, length: 4 * 1024 * 1024 + 1 },
      { ...request, offset: -1 },
      { ...request, offset: 1.5 },
      { ...request, offset: Number.MAX_SAFE_INTEGER + 1 },
      { ...request, accountId: 'not-a-uuid' },
      { ...request, fileId: 'https://evil.example/x' }
    ]) {
      expect(await t.coordinator.verifyRead(bad)).toEqual({ ok: false, error: 'invalid-request' });
    }
    expect(t.getFile).not.toHaveBeenCalled();
    expect(t.agent.post).not.toHaveBeenCalled();
  });

  it('refuses folders, native documents and files without a known size', async () => {
    for (const file of [
      { ...FILE, kind: 'FOLDER' as const },
      { ...FILE, canDownload: false },
      { ...FILE, sizeBytes: null },
      { ...FILE, kind: 'SHORTCUT' as const }
    ]) {
      const t = setup();
      t.getFile.mockResolvedValue(file);
      expect(await t.coordinator.verifyRead(request)).toEqual({ ok: false, error: 'unsupported-file' });
      expect(t.issueCredential).not.toHaveBeenCalled();
    }
  });

  it('re-delegates exactly once after a rejected credential, then gives up', async () => {
    const t = setup({ readReplies: [json(401, { code: 'credential_rejected' }), json(401, { code: 'credential_rejected' })] });
    expect(await t.coordinator.verifyRead(request)).toEqual({ ok: false, error: 'credential-rejected' });
    expect(t.issueCredential).toHaveBeenCalledTimes(2);
    expect(t.paths().filter((p) => p === '/local/v1/reads')).toHaveLength(2);
  });

  it('recovers when the agent restarted and no longer knows the handle', async () => {
    const t = setup();
    await t.coordinator.verifyRead(request);
    t.agent.setInstance('inst-2'); // restart: handles from inst-1 are stale and re-delegated up front
    const result = await t.coordinator.verifyRead(request);
    expect(result.ok).toBe(true);
    expect(t.issueCredential).toHaveBeenCalledTimes(2);
  });

  it('serializes renewals: concurrent reads share one delegation', async () => {
    const t = setup();
    let release: () => void = () => undefined;
    t.issueCredential.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = () => resolve(credential());
        })
    );
    const reads = [t.coordinator.verifyRead(request), t.coordinator.verifyRead(request), t.coordinator.verifyRead(request)];
    await vi.waitFor(() => expect(t.issueCredential).toHaveBeenCalled());
    release();
    const results = await Promise.all(reads);
    expect(results.every((r) => r.ok)).toBe(true);
    expect(t.issueCredential).toHaveBeenCalledTimes(1);
    expect(t.paths().filter((p) => p === '/local/v1/credentials')).toHaveLength(1);
  });

  it('maps backend delegation refusals without contacting the agent', async () => {
    const cases: [unknown, string][] = [
      [new ApiError(409, 'content_permission_missing'), 'content-permission-missing'],
      [new ApiError(409, 'account_needs_reconnect'), 'needs-reconnect'],
      [new ApiError(404, 'account_not_found'), 'not-found'],
      [new SessionExpiredError(), 'session-expired']
    ];
    for (const [error, code] of cases) {
      const t = setup();
      t.issueCredential.mockRejectedValue(error);
      expect(await t.coordinator.verifyRead(request)).toEqual({ ok: false, error: code });
      expect(t.agent.post).not.toHaveBeenCalled();
    }
  });

  it('reports an unavailable agent without asking the backend for a token', async () => {
    const t = setup({ instance: null });
    expect(await t.coordinator.verifyRead(request)).toEqual({ ok: false, error: 'agent-unavailable' });
    expect(t.issueCredential).not.toHaveBeenCalled();
  });

  it('maps agent read errors to safe codes', async () => {
    for (const [code, expected] of [
      ['file_changed', 'file-changed'],
      ['range_not_supported', 'range-not-supported'],
      ['redirect_refused', 'provider-unavailable'],
      ['unsupported_file', 'unsupported-file'],
      ['rate_limited', 'rate-limited'],
      ['something_new', 'unknown']
    ]) {
      const t = setup({ readReplies: [json(502, { code })] });
      expect(await t.coordinator.verifyRead(request)).toEqual({ ok: false, error: expected });
    }
  });

  it('rejects a body whose length disagrees with what was asked', async () => {
    const t = setup({ readReplies: [binary(new Uint8Array(50))] });
    expect(await t.coordinator.verifyRead(request)).toEqual({ ok: false, error: 'unknown' });
  });

  it('disconnect clears only that account; sign-out clears everything', async () => {
    const t = setup();
    await t.coordinator.verifyRead(request);
    await t.coordinator.forgetAccount(ACCOUNT);
    expect(t.calls.at(-1)).toEqual({ path: '/local/v1/credentials/clear', body: { accountId: ACCOUNT } });
    await t.coordinator.verifyRead(request);
    expect(t.issueCredential).toHaveBeenCalledTimes(2); // handle was dropped locally too

    await t.coordinator.reset();
    expect(t.calls.at(-1)).toEqual({ path: '/local/v1/credentials/clear', body: {} });
    expect(t.agent.restart).not.toHaveBeenCalled();
  });

  it('restarts the agent when a clear cannot be confirmed, so no credential survives', async () => {
    const t = setup();
    t.agent.clearReply = () => Promise.reject(new Error('socket hang up'));
    await t.coordinator.reset();
    expect(t.agent.restart).toHaveBeenCalledTimes(1);

    const u = setup();
    u.agent.clearReply = () => Promise.resolve(json(500, { code: 'internal_error' }));
    await u.coordinator.forgetAccount(ACCOUNT);
    expect(u.agent.restart).toHaveBeenCalledTimes(1);
  });

  it('does nothing on cleanup when no agent is running', async () => {
    const t = setup({ instance: null });
    await t.coordinator.reset();
    expect(t.agent.post).not.toHaveBeenCalled();
    expect(t.agent.restart).not.toHaveBeenCalled();
  });
});
