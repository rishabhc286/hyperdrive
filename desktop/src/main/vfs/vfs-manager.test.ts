// @vitest-environment node
import { EventEmitter } from 'node:events';
import { describe, expect, it, vi } from 'vitest';
import { VfsManager, parseEvent, type VfsChild, type VfsEvent } from './vfs-manager';

const CAP = `hdv_${'A'.repeat(43)}`;
const REQUEST = { agentPort: 51234, capability: CAP, driveLetter: 'H' };

class FakeChild extends EventEmitter implements VfsChild {
  readonly pid = 4242;
  readonly written: string[] = [];
  ended = false;
  killed = 0;
  readonly stdoutEmitter = new EventEmitter();
  readonly stderrEmitter = new EventEmitter();
  readonly stdin = {
    write: (data: string) => {
      this.written.push(data);
      return true;
    },
    end: () => {
      this.ended = true;
    },
    on: () => this.stdin
  };
  readonly stdout = { setEncoding: () => this.stdout, on: (e: 'data', l: (c: string) => void) => this.stdoutEmitter.on(e, l) };
  readonly stderr = { setEncoding: () => this.stderr, on: (e: 'data', l: (c: string) => void) => this.stderrEmitter.on(e, l) };
  kill(): boolean {
    this.killed++;
    setImmediate(() => this.emit('exit', 1, null));
    return true;
  }
  say(event: Record<string, unknown>): void {
    this.stdoutEmitter.emit('data', `${JSON.stringify(event)}\n`);
  }
}

function setup(options: { startupTimeoutMs?: number; stopTimeoutMs?: number } = {}) {
  const child = new FakeChild();
  const spawn = vi.fn((command: string, args: readonly string[]) => {
    void command;
    void args;
    return child;
  });
  const manager = new VfsManager({ exePath: 'C:\\trusted\\hyperdrive-vfs.exe', spawn, ...options });
  const events: VfsEvent[] = [];
  manager.onEvent((e) => events.push(e));
  return { child, spawn, manager, events };
}

async function mountedSetup(options: { stopTimeoutMs?: number } = {}) {
  const t = setup(options);
  const p = t.manager.mount(REQUEST);
  t.child.say({ event: 'started', protocolVersion: 1 });
  t.child.say({ event: 'agent-authenticated', generation: 3 });
  t.child.say({ event: 'volume-created' });
  t.child.say({ event: 'mounted', mountPoint: 'H:', fixture: false });
  await p;
  return t;
}

describe('VfsManager', () => {
  it('spawns the trusted exe without arguments, bootstraps over stdin once, and resolves only on mounted', async () => {
    const t = await mountedSetup();
    expect(t.spawn).toHaveBeenCalledWith('C:\\trusted\\hyperdrive-vfs.exe', []);
    expect(t.child.written).toHaveLength(1);
    expect(JSON.parse(t.child.written[0]!)).toEqual({
      protocolVersion: 1,
      agentPort: 51234,
      capability: CAP,
      mount: { driveLetter: 'H' }
    });
    expect(t.events.map((e) => (e.kind === 'phase' ? e.phase : e.kind))).toEqual([
      'process-started',
      'agent-authenticated',
      'volume-created',
      'mounted'
    ]);
    t.child.stderrEmitter.emit('data', 'some diagnostic line\n');
    expect(t.manager.log.snapshot().join('\n')).not.toContain(CAP);
  });

  it.each([
    ['an unknown event', [{ event: 'started', protocolVersion: 1 }, { event: 'teleported' }]],
    ['events out of order', [{ event: 'started', protocolVersion: 1 }, { event: 'mounted', mountPoint: 'H:', fixture: false }]],
    ['a protocol mismatch', [{ event: 'started', protocolVersion: 2 }]],
    ['a fixture-mode process', [{ event: 'started', protocolVersion: 1 }, { event: 'fixture-mode' }]],
    [
      'a mount on another letter',
      [
        { event: 'started', protocolVersion: 1 },
        { event: 'agent-authenticated', generation: 1 },
        { event: 'volume-created' },
        { event: 'mounted', mountPoint: 'Z:', fixture: false }
      ]
    ],
    ['extra fields', [{ event: 'started', protocolVersion: 1, url: 'http://x' }]]
  ])('rejects %s and terminates only its own child', async (_name, events) => {
    const t = setup();
    const p = t.manager.mount(REQUEST);
    for (const e of events) t.child.say(e);
    await expect(p).rejects.toMatchObject({ code: 'invalid-handshake' });
    expect(t.child.killed).toBe(1);
  });

  it('reports a native failure code (occupied drive letter) and stops the child', async () => {
    const t = setup();
    const p = t.manager.mount(REQUEST);
    t.child.say({ event: 'started', protocolVersion: 1 });
    t.child.say({ event: 'failed', code: 'drive-letter-in-use' });
    await expect(p).rejects.toMatchObject({ code: 'drive-letter-in-use' });
  });

  it('times out a startup that never confirms the mount', async () => {
    const t = setup({ startupTimeoutMs: 30 });
    const p = t.manager.mount(REQUEST);
    t.child.say({ event: 'started', protocolVersion: 1 });
    await expect(p).rejects.toMatchObject({ code: 'startup-timeout' });
    expect(t.child.killed).toBe(1);
  });

  it('detects an early exit and an oversized output line', async () => {
    const early = setup();
    const p1 = early.manager.mount(REQUEST);
    early.child.emit('exit', 10, null);
    await expect(p1).rejects.toMatchObject({ code: 'vfs-exited' });

    const noisy = setup();
    const p2 = noisy.manager.mount(REQUEST);
    noisy.child.stdoutEmitter.emit('data', 'x'.repeat(5000));
    await expect(p2).rejects.toMatchObject({ code: 'invalid-handshake' });
  });

  it('refuses invalid requests before spawning anything', async () => {
    const t = setup();
    await expect(t.manager.mount({ ...REQUEST, driveLetter: 'C' })).rejects.toMatchObject({ code: 'invalid-drive-letter' });
    await expect(t.manager.mount({ ...REQUEST, capability: 'hda_x' })).rejects.toBeTruthy();
    await expect(t.manager.mount({ ...REQUEST, agentPort: 80 })).rejects.toBeTruthy();
    expect(t.spawn).not.toHaveBeenCalled();
  });

  it('unmounts through stdin and only kills its own child if it does not exit in time', async () => {
    const t = await mountedSetup();
    const done = t.manager.unmount();
    expect(t.child.written.at(-1)).toBe('unmount\n');
    t.child.say({ event: 'unmounted', reason: 'requested' });
    t.child.emit('exit', 0, null);
    await done;
    expect(t.child.killed).toBe(0);
    expect(t.manager.isRunning()).toBe(false);

    const stuck = await mountedSetup({ stopTimeoutMs: 20 });
    await stuck.manager.unmount(); // the child ignores "unmount": only it is killed
    expect(stuck.child.killed).toBe(1);
  });

  it('reports a self-unmount (authorization lost) and a crash while mounted', async () => {
    const t = await mountedSetup();
    t.child.say({ event: 'unmounted', reason: 'authorization-lost' });
    expect(t.events.at(-1)).toEqual({ kind: 'unmounted', reason: 'authorization-lost' });

    const crash = await mountedSetup();
    crash.child.emit('exit', 3221225477, null);
    expect(crash.events.at(-1)).toEqual({ kind: 'unmounted', reason: 'exited' });
  });

  it('parses events strictly', () => {
    expect(parseEvent('{"event":"mounted","mountPoint":"H:","fixture":false}')).not.toBeNull();
    expect(parseEvent('{"event":"failed","code":"Drive In Use!"}')).toBeNull();
    expect(parseEvent('[]')).toBeNull();
    expect(parseEvent('nope')).toBeNull();
  });
});
