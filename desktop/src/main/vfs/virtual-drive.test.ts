// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import type { VirtualDrivePrerequisite } from '../../shared/vfs';
import type { MountRequest, VfsEvent } from './vfs-manager';
import { VfsMountError } from './vfs-manager';
import { parseActivity, readIssueFor, VirtualDriveCoordinator, type VirtualDriveDeps } from './virtual-drive';

const USER = '22222222-3333-4444-8555-666666666666';
const CAP = `hdv_${'C'.repeat(43)}`;
const GRANT = `hdm_${'G'.repeat(43)}`;
const enc = new TextEncoder();

function setup(overrides: Partial<VirtualDriveDeps> = {}) {
  let user: string | null = USER;
  let prerequisite: VirtualDrivePrerequisite | null = null;
  let generation = 0;
  const listeners: ((e: VfsEvent) => void)[] = [];
  let running = false;
  const posts: { path: string; body: Record<string, unknown> }[] = [];
  const vfs = {
    mount: vi.fn(async (request: MountRequest) => {
      void request;
      running = true;
    }),
    unmount: vi.fn(async () => {
      running = false;
    }),
    isRunning: () => running,
    onEvent: (l: (e: VfsEvent) => void) => {
      listeners.push(l);
      return () => undefined;
    }
  };
  const agent = {
    loopbackPort: vi.fn(() => 51234 as number | null),
    instanceId: vi.fn(() => 'inst-1' as string | null),
    post: vi.fn(async (path: string, body: unknown) => {
      posts.push({ path, body: body as Record<string, unknown> });
      if (path === '/local/v1/vfs/mount') {
        generation++;
        return { status: 201, body: enc.encode(JSON.stringify({ generation, capability: CAP })) };
      }
      return { status: 200, body: enc.encode('{}') };
    })
  };
  const issueGrant = vi.fn(async () => ({ grant: GRANT, expiresAt: new Date(Date.now() + 900_000).toISOString() }));
  const revokeGrant = vi.fn(async () => undefined);
  let saved = 'H';
  const deps: VirtualDriveDeps = {
    vfs,
    prerequisite: () => prerequisite,
    agent,
    issueGrant,
    revokeGrant,
    currentUserId: () => user,
    hasConnectedAccounts: async () => true,
    backendOrigin: 'http://127.0.0.1:18080',
    driveLetterInUse: () => false,
    settings: { load: async () => saved, save: async (l) => void (saved = l) },
    ...overrides
  };
  const drive = new VirtualDriveCoordinator(deps);
  return {
    drive,
    vfs,
    agent,
    posts,
    issueGrant,
    revokeGrant,
    setUser: (u: string | null) => (user = u),
    setPrerequisite: (p: VirtualDrivePrerequisite | null) => (prerequisite = p),
    emitVfs: (e: VfsEvent) => listeners.forEach((l) => l(e))
  };
}

describe('VirtualDriveCoordinator', () => {
  it('mounts with a scoped grant and capability, and never exposes them in status', async () => {
    const t = setup();
    const status = await t.drive.mount();
    expect(status).toMatchObject({ state: 'mounted', driveLetter: 'H', phase: 'mounted', error: null });
    expect(t.posts[0]).toEqual({
      path: '/local/v1/vfs/mount',
      body: { userId: USER, backendOrigin: 'http://127.0.0.1:18080', grant: GRANT, grantExpiresAt: expect.any(String) }
    });
    expect(t.vfs.mount).toHaveBeenCalledWith({ agentPort: 51234, capability: CAP, driveLetter: 'H' });
    expect(JSON.stringify(status)).not.toMatch(/hdv_|hdm_|51234/);
    expect(t.drive.mountedRoot()).toBe('H:\\');
  });

  it('refuses an occupied drive letter before issuing anything', async () => {
    const t = setup({ driveLetterInUse: () => true });
    const status = await t.drive.mount();
    expect(status).toMatchObject({ state: 'failed', error: 'drive-letter-in-use' });
    expect(t.issueGrant).not.toHaveBeenCalled();
    expect(t.vfs.mount).not.toHaveBeenCalled();
    expect(t.drive.mountedRoot()).toBeNull();
  });

  it('cleans up the capability and grant when the native mount fails', async () => {
    const t = setup();
    t.vfs.mount.mockRejectedValueOnce(new VfsMountError('startup-timeout'));
    const status = await t.drive.mount();
    expect(status).toMatchObject({ state: 'failed', error: 'startup-timeout' });
    expect(t.posts.map((p) => p.path)).toEqual(['/local/v1/vfs/mount', '/local/v1/vfs/mount/revoke']);
    expect(t.revokeGrant).toHaveBeenCalled();
    expect(t.vfs.unmount).toHaveBeenCalled();
  });

  it('unmount stops the owned process, revokes the capability, then the grant', async () => {
    const t = setup();
    await t.drive.mount();
    const order: string[] = [];
    t.vfs.unmount.mockImplementationOnce(async () => void order.push('vfs'));
    t.agent.post.mockImplementationOnce(async (path: string) => {
      order.push(path);
      return { status: 200, body: enc.encode('{}') };
    });
    t.revokeGrant.mockImplementationOnce(async () => void order.push('grant'));
    const status = await t.drive.unmount();
    expect(status.state).toBe('ready');
    expect(order).toEqual(['vfs', '/local/v1/vfs/mount/revoke', 'grant']);
    expect(t.drive.mountedRoot()).toBeNull();
  });

  it('logout teardown leaves nothing mounted and reports signed-out', async () => {
    const t = setup();
    await t.drive.mount();
    t.setUser(null);
    const status = await t.drive.teardown();
    expect(status.state).toBe('signed-out');
    expect(t.vfs.unmount).toHaveBeenCalled();
    expect(t.posts.at(-1)?.path).toBe('/local/v1/vfs/mount/revoke');
  });

  it('a native self-unmount (authorization lost) stops showing a healthy mount', async () => {
    const t = setup();
    await t.drive.mount();
    t.emitVfs({ kind: 'unmounted', reason: 'authorization-lost' });
    await vi.waitFor(async () => expect((await t.drive.status()).state).toBe('failed'));
    expect((await t.drive.status()).error).toBe('authorization-lost');
    expect(t.posts.at(-1)?.path).toBe('/local/v1/vfs/mount/revoke');
  });

  it('renews the grant with the current generation and unmounts when renewal fails', async () => {
    const t = setup({ renewEveryMs: 15 });
    await t.drive.mount();
    await vi.waitFor(() => expect(t.posts.some((p) => p.path === '/local/v1/vfs/mount/grant')).toBe(true));
    expect(t.posts.find((p) => p.path === '/local/v1/vfs/mount/grant')?.body).toMatchObject({ generation: 1, grant: GRANT });
    t.issueGrant.mockRejectedValue(new Error('session gone'));
    await vi.waitFor(async () => expect((await t.drive.status()).state).toBe('failed'));
    expect(t.vfs.unmount).toHaveBeenCalled();
  });

  it('reports prerequisites, signed-out and no-accounts without mounting', async () => {
    const t = setup();
    t.setPrerequisite('winfsp-missing');
    expect(await t.drive.mount()).toMatchObject({ state: 'prerequisite-missing', prerequisite: 'winfsp-missing' });
    t.setPrerequisite(null);
    t.setUser(null);
    expect((await t.drive.mount()).state).toBe('signed-out');
    expect(t.issueGrant).not.toHaveBeenCalled();
    const none = setup({ hasConnectedAccounts: async () => false });
    expect((await none.drive.status()).state).toBe('no-accounts');
  });

  it('admits no mounts after close and validates the drive letter', async () => {
    const t = setup();
    await t.drive.init();
    expect(await t.drive.setDriveLetter('C')).toEqual({ ok: false, error: 'invalid-drive-letter' });
    expect(await t.drive.setDriveLetter('Q')).toMatchObject({ ok: true, value: { driveLetter: 'Q' } });
    await t.drive.mount();
    expect((await t.drive.setDriveLetter('R')).ok).toBe(false);
    await t.drive.close();
    expect((await t.drive.mount()).state).not.toBe('mounted');
    expect(t.vfs.mount).toHaveBeenCalledTimes(1);
  });

  it('starts credential renewal for the mounted generation and stops it first on every teardown', async () => {
    const order: string[] = [];
    const pump = { start: vi.fn(async (g: number) => void order.push(`start:${g}`)), stop: vi.fn(() => void order.push('stop')) };
    const t = setup({ pump });
    t.vfs.unmount.mockImplementation(async () => void order.push('vfs-unmount'));
    await t.drive.mount();
    expect(pump.start).toHaveBeenCalledWith(1);
    await t.drive.teardown(); // logout / disconnect / agent restart
    expect(order).toEqual(['start:1', 'stop', 'vfs-unmount']);
    await t.drive.mount(); // a new mount is a new generation; the old one is never served again
    expect(pump.start).toHaveBeenLastCalledWith(2);
    await t.drive.unmount();
    expect(order.slice(-2)).toEqual(['stop', 'vfs-unmount']);
  });

  it('a failed mount never starts renewal', async () => {
    const pump = { start: vi.fn(async () => undefined), stop: vi.fn() };
    const t = setup({ pump });
    t.vfs.mount.mockRejectedValueOnce(new VfsMountError('startup-timeout'));
    expect((await t.drive.mount()).state).toBe('failed');
    expect(pump.start).not.toHaveBeenCalled();
  });

  it("reports the agent's real counters only while mounted", async () => {
    const readStatus = vi.fn(async (): Promise<unknown> => ({
      active: true,
      generation: 1,
      reads: { openHandles: 3, reads: 40, bytesReturned: 1000, providerBytes: 4_194_304, cacheBytes: 500, lastError: 'cancelled', renewals: { lastFailure: null } }
    }));
    const t = setup({ readStatus });
    expect((await t.drive.status()).activity).toBeNull();
    const mounted = await t.drive.mount();
    expect(mounted.activity).toEqual({ openFiles: 3, reads: 40, bytesReturned: 1000, providerBytes: 4_194_304, cacheBytes: 500, lastIssue: null });
    readStatus.mockRejectedValueOnce(new Error('agent gone'));
    expect((await t.drive.status()).activity).toBeNull(); // unknown, not invented
    await t.drive.unmount();
    expect((await t.drive.status()).activity).toBeNull();
  });

  it('maps agent read codes to safe user-level issues', () => {
    expect(readIssueFor('needs_reconnect')).toBe('reconnect-needed');
    expect(readIssueFor('content_permission_missing')).toBe('content-permission-missing');
    expect(readIssueFor('authorization_expired')).toBe('session-expired');
    expect(readIssueFor('file_changed')).toBe('file-changed');
    expect(readIssueFor('timeout')).toBe('timeout');
    expect(readIssueFor('stale_handle')).toBeNull();
    expect(readIssueFor('something-new')).toBe('other');
    expect(
      parseActivity({ reads: { reads: -5, bytesReturned: 1.5, renewals: { lastFailure: 'needs_reconnect' }, lastError: 'timeout' } })
    ).toEqual({ openFiles: 0, reads: 0, bytesReturned: 0, providerBytes: 0, cacheBytes: 0, lastIssue: 'reconnect-needed' });
    expect(parseActivity('nope')).toBeNull();
  });
});
