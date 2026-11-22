// @vitest-environment node
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { DEFAULT_CACHE_BUDGET } from '../../shared/cache';
import { AgentUnavailableError, type AgentPostPath, type AgentPostResponse } from '../agent/agent-manager';
import type { SafeStorageLike } from '../auth/token-store';
import { CacheCoordinator, endsCacheSession, type CacheAgentPort, type CacheKeys } from './cache-coordinator';
import { CacheKeyStore } from './cache-key-store';
import { CacheSettings } from './cache-settings';

const USER = '11111111-2222-4333-8444-555555555555';
const ACCOUNT = '6f1c2a8e-1b2c-4d3e-8f90-123456789abc';
const enc = new TextEncoder();
const root = mkdtempSync(join(tmpdir(), 'hyperdrive-cache-test-'));
afterAll(() => rmSync(root, { recursive: true, force: true }));
let dirCounter = 0;
const freshDir = () => join(root, `d${++dirCounter}`);

/** Reversible "OS encryption" double: real safeStorage is DPAPI; this only proves we never store plaintext. */
const fakeSafeStorage = (available = true, backend?: string): SafeStorageLike => ({
  isEncryptionAvailable: () => available,
  encryptString: (s) => Buffer.from(`wrapped:${Buffer.from(s).toString('hex')}`),
  decryptString: (b) => {
    const text = b.toString();
    if (!text.startsWith('wrapped:')) throw new Error('not ours');
    return Buffer.from(text.slice(8), 'hex').toString();
  },
  getSelectedStorageBackend: () => backend ?? 'dpapi'
});

const STATUS_BODY = {
  enabled: true,
  disabledReason: null,
  budgetBytes: DEFAULT_CACHE_BUDGET,
  encryptedBytesOnDisk: 4194336,
  tempBytes: 0,
  entryCount: 1,
  overBudgetBytes: 0,
  startupRemovedFiles: 0,
  counters: {
    hits: 1,
    misses: 1,
    notAdmitted: 0,
    evictions: 0,
    corruptions: 0,
    bytesRequested: 200,
    providerBytesFetched: 4194304,
    bytesServedFromCache: 100
  },
  bypasses: { provider_not_version_bound: 2, some_new_reason: 9 },
  lastCleanup: null,
  path: 'C:\\Users\\someone\\AppData\\cache', // never forwarded
  key: 'c2VjcmV0'
};

const ok = (body: unknown): AgentPostResponse => ({
  status: 200,
  headers: { 'content-type': 'application/json' },
  body: enc.encode(JSON.stringify(body))
});

function setup(options: { keys?: CacheKeys; user?: string | null; instance?: string | null } = {}) {
  let instance: string | null = options.instance === undefined ? 'inst-1' : options.instance;
  let user: string | null = options.user === undefined ? USER : options.user;
  const calls: { path: AgentPostPath; body: Record<string, unknown> }[] = [];
  const replies = new Map<AgentPostPath, () => Promise<AgentPostResponse>>();
  const agent: CacheAgentPort = {
    instanceId: () => instance,
    post: vi.fn(async (path: AgentPostPath, body: unknown) => {
      calls.push({ path, body: body as Record<string, unknown> });
      if (!instance) throw new AgentUnavailableError();
      const reply = replies.get(path);
      const response = reply ? await reply() : path === '/local/v1/cache/session' ? ok(STATUS_BODY) : ok({ removed: 1, failed: 0 });
      return { ...response, instanceId: instance };
    }),
    get: vi.fn(async () => {
      if (!instance) throw new AgentUnavailableError();
      return { status: 200, body: STATUS_BODY, instanceId: instance };
    })
  };
  const keys = options.keys ?? new CacheKeyStore(fakeSafeStorage(), freshDir(), 'win32');
  const settings = new CacheSettings(join(freshDir(), 'cache-settings.json'));
  const coordinator = new CacheCoordinator({ agent, keys, settings, currentUserId: () => user });
  return {
    coordinator,
    agent,
    calls,
    replies,
    keys,
    settings,
    setInstance: (v: string | null) => (instance = v),
    setUser: (v: string | null) => (user = v),
    paths: () => calls.map((c) => c.path)
  };
}

describe('CacheKeyStore', () => {
  it('creates a random key once, wrapped, and reloads the same key', async () => {
    const dir = freshDir();
    const store = new CacheKeyStore(fakeSafeStorage(), dir, 'win32');
    const first = await store.getOrCreate(USER);
    expect(first?.fresh).toBe(true);
    expect(first?.key).toHaveLength(32);
    const again = await store.getOrCreate(USER);
    expect(again?.fresh).toBe(false);
    expect(again?.key.equals(first!.key)).toBe(true);

    const files = readdirSync(dir);
    expect(files).toHaveLength(1);
    expect(files[0]).toMatch(/^[0-9a-f]{32}\.json$/);
    expect(files[0]).not.toContain(USER);
    const onDisk = readFileSync(join(dir, files[0]!), 'utf8');
    expect(onDisk).not.toContain(first!.key.toString('base64'));
    expect(onDisk).not.toContain(first!.key.toString('hex'));

    await store.remove(USER);
    expect(readdirSync(dir)).toHaveLength(0);
  });

  it('replaces an unreadable key file with a fresh key', async () => {
    const dir = freshDir();
    const store = new CacheKeyStore(fakeSafeStorage(), dir, 'win32');
    await store.getOrCreate(USER);
    const file = join(dir, readdirSync(dir)[0]!);
    writeFileSync(file, JSON.stringify({ v: 1, data: Buffer.from('garbage').toString('base64') }));
    expect((await store.getOrCreate(USER))?.fresh).toBe(true);
  });

  it('has no plaintext fallback when OS protection is missing', async () => {
    for (const store of [
      new CacheKeyStore(fakeSafeStorage(false), freshDir(), 'win32'),
      new CacheKeyStore(fakeSafeStorage(true, 'basic_text'), freshDir(), 'linux')
    ]) {
      expect(store.available).toBe(false);
      expect(await store.getOrCreate(USER)).toBeNull();
    }
  });
});

describe('CacheCoordinator', () => {
  it('hands the key to each agent instance once, single-flight, and again after a restart', async () => {
    const t = setup();
    const results = await Promise.all([t.coordinator.ensureSession(), t.coordinator.ensureSession(), t.coordinator.ensureSession()]);
    expect(results).toEqual([true, true, true]);
    expect(t.paths()).toEqual(['/local/v1/cache/session']);
    const body = t.calls[0]!.body;
    expect(body).toMatchObject({ userId: USER, fresh: true, budgetBytes: DEFAULT_CACHE_BUDGET });
    expect(body['key']).toMatch(/^[A-Za-z0-9+/]{43}=$/);

    await t.coordinator.ensureSession();
    expect(t.paths()).toHaveLength(1);
    t.setInstance('inst-2');
    await t.coordinator.ensureSession();
    expect(t.paths()).toEqual(['/local/v1/cache/session', '/local/v1/cache/session']);
    expect(t.calls[1]!.body['fresh']).toBe(false); // same stored key; the agent keeps the entries
    expect(t.calls[1]!.body['key']).toBe(body['key']);
  });

  it('sends nothing when signed out, the agent is down, or keys are unprotected', async () => {
    expect(await setup({ user: null }).coordinator.ensureSession()).toBe(false);
    expect(await setup({ instance: null }).coordinator.ensureSession()).toBe(false);
    const unprotected = setup({ keys: new CacheKeyStore(fakeSafeStorage(false), freshDir(), 'win32') });
    expect(await unprotected.coordinator.ensureSession()).toBe(false);
    expect(unprotected.calls).toHaveLength(0);
  });

  it('re-parses status so only known fields reach the renderer', async () => {
    const t = setup();
    const result = await t.coordinator.status();
    if (!result.ok) throw new Error('expected ok');
    const json = JSON.stringify(result.value);
    expect(json).not.toContain('AppData');
    expect(json).not.toContain('c2VjcmV0');
    expect(json).not.toContain('some_new_reason');
    expect(json).not.toMatch(/[A-Za-z0-9+/]{43}=/); // no key
    expect(result.value.bypasses).toEqual({ provider_not_version_bound: 2 });
    expect(result.value.keyProtection).toBe('available');
    expect(result.value.counters.providerBytesFetched).toBe(4194304);
  });

  it('explains a disabled cache when keys are unprotected', async () => {
    const t = setup({ keys: new CacheKeyStore(fakeSafeStorage(false), freshDir(), 'win32') });
    vi.mocked(t.agent.get).mockResolvedValue({
      status: 200,
      body: { ...STATUS_BODY, enabled: false, disabledReason: 'cache_key_unavailable' },
      instanceId: 'inst-1'
    });
    const result = await t.coordinator.status();
    if (!result.ok) throw new Error('expected ok');
    expect(result.value).toMatchObject({ enabled: false, disabledReason: 'key-protection-unavailable', keyProtection: 'unavailable' });
  });

  it('validates and persists the budget', async () => {
    const t = setup();
    expect(await t.coordinator.setBudget(1000)).toEqual({ ok: false, error: 'invalid-request' });
    expect(await t.coordinator.setBudget(2 ** 60)).toEqual({ ok: false, error: 'invalid-request' });
    expect(t.paths()).not.toContain('/local/v1/cache/budget');
    const ok256 = 256 * 1024 * 1024;
    expect((await t.coordinator.setBudget(ok256)).ok).toBe(true);
    expect(t.calls.find((c) => c.path === '/local/v1/cache/budget')?.body).toEqual({ budgetBytes: ok256 });
    expect(await t.settings.budgetBytes()).toBe(ok256);
  });

  it('clear reports what the agent removed', async () => {
    const t = setup();
    t.replies.set('/local/v1/cache/clear', () => Promise.resolve(ok({ removed: 3, failed: 1 })));
    expect(await t.coordinator.clear()).toEqual({ ok: true, value: { removed: 3, failed: 1 } });
    expect(t.calls.at(-1)).toEqual({ path: '/local/v1/cache/clear', body: {} });
  });

  it('retries an unconfirmed account purge at the next session', async () => {
    const t = setup();
    await t.coordinator.ensureSession();
    t.replies.set('/local/v1/cache/clear', () => Promise.reject(new Error('socket hang up')));
    await t.coordinator.forgetAccount(ACCOUNT);
    t.replies.delete('/local/v1/cache/clear');
    t.setInstance('inst-2'); // agent restarted
    await t.coordinator.ensureSession();
    const clears = t.calls.filter((c) => c.path === '/local/v1/cache/clear');
    expect(clears).toHaveLength(2);
    expect(clears[1]!.body).toEqual({ accountId: ACCOUNT });
  });

  it('logout ends the agent session for the right user and deletes the key, even if the agent is gone', async () => {
    const t = setup();
    await t.coordinator.ensureSession();
    expect(await t.coordinator.endSession()).toEqual({ agentReached: true, removed: 1, failed: 0 });
    expect(t.calls.at(-1)).toEqual({ path: '/local/v1/cache/session/end', body: { userId: USER } });
    expect((await t.keys.getOrCreate(USER))?.fresh).toBe(true); // the old key is gone

    const u = setup();
    u.coordinator.noteUser(USER);
    await u.keys.getOrCreate(USER);
    u.setInstance(null);
    u.setUser(null);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(await u.coordinator.endSession()).toEqual({ agentReached: false, removed: 0, failed: 0 });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('cache cleanup after sign-out incomplete'));
    warn.mockRestore();
    expect((await u.keys.getOrCreate(USER))?.fresh).toBe(true);
  });

  it('purges only on a real sign-out or expiry, not on transient phases', () => {
    expect(endsCacheSession('signed-out')).toBe(true);
    expect(endsCacheSession('session-expired')).toBe(true);
    for (const phase of ['checking', 'unavailable', 'onboarding', 'signing-in', 'signed-in']) {
      expect(endsCacheSession(phase)).toBe(false);
    }
  });
});
