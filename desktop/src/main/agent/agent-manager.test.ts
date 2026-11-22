// @vitest-environment node
import { EventEmitter } from 'node:events';
import { describe, expect, it, vi } from 'vitest';
import type { AgentStatus } from '../../shared/agent';
import { AgentManager, type AgentChild, type AgentManagerDeps, type LaunchConfig } from './agent-manager';
import { parseJavaMajor, resolveJava } from './java-discovery';
import { resolveLaunchConfig } from './launch-config';
import { BoundedLog, HandshakeReader } from './output-buffer';
import { parseHandshake } from './protocol';

const SECRET = 'S'.repeat(43);
const INSTANCE = '2f788d69-d602-4478-b2bb-a7e44a56a63e';
const READY = JSON.stringify({ type: 'ready', protocolVersion: 1, port: 50123, instanceId: INSTANCE, agentVersion: '0.1.0' });
const STATUS = {
  protocolVersion: 1,
  agentVersion: '0.1.0',
  instanceId: INSTANCE,
  readiness: 'READY',
  uptimeMillis: 1234,
  capabilities: { providerReads: false, rangeReads: false, chunkCache: false, virtualDrive: false },
  lastStartupError: null
};

/** A scriptable stand-in for the agent's ChildProcess. */
class FakeChild extends EventEmitter implements AgentChild {
  readonly written: string[] = [];
  stdinEnded = false;
  killed = 0;
  readonly stdout = Object.assign(new EventEmitter(), { setEncoding: () => undefined });
  readonly stderr = Object.assign(new EventEmitter(), { setEncoding: () => undefined });
  readonly stdin = {
    write: (data: string) => {
      this.written.push(data);
      this.onWrite?.(data);
      return true;
    },
    end: () => {
      this.stdinEnded = true;
      this.onEnd?.();
    },
    on: () => undefined
  };
  onWrite?: (data: string) => void;
  onEnd?: () => void;

  kill(): boolean {
    this.killed++;
    setTimeout(() => this.exit(null), 0);
    return true;
  }

  out(text: string): void {
    this.stdout.emit('data', text);
  }

  err(text: string): void {
    this.stderr.emit('data', text);
  }

  exit(code: number | null): void {
    this.emit('exit', code, null);
  }
}

const LAUNCH: LaunchConfig = {
  java: { ok: true, command: 'C:\\jdk\\bin\\java.exe', source: 'JAVA_HOME' },
  jarPath: 'C:\\repo\\agent\\target\\hyperdrive-agent.jar',
  dataDir: 'C:\\Users\\me\\AppData\\Roaming\\hyperdrive-desktop\\agent',
  logLevel: 'INFO'
};

/** A child that answers the bootstrap with a ready handshake, and exits on shutdown or EOF. */
function cooperativeChild(): FakeChild {
  const child = new FakeChild();
  child.onWrite = (data) => {
    if (data.includes('"secret"')) setTimeout(() => child.out(`${READY}\n`), 0);
    if (data.includes('"shutdown"')) setTimeout(() => child.exit(0), 0);
  };
  return child;
}

function setup(overrides: Partial<AgentManagerDeps> = {}, makeChild: () => FakeChild = cooperativeChild) {
  const children: FakeChild[] = [];
  const spawn = vi.fn((command: string, args: readonly string[]) => {
    void command;
    void args;
    const child = makeChild();
    children.push(child);
    return child;
  });
  const httpGet = vi.fn(async (_port: number, path: string) =>
    path === '/local/v1/health' ? { status: 200, body: { status: 'UP' } } : { status: 200, body: STATUS }
  );
  const deps: AgentManagerDeps = {
    launch: LAUNCH,
    fileExists: () => true,
    checkJava: async () => ({ major: 25 }),
    spawn,
    httpGet,
    randomSecret: () => SECRET,
    startupTimeoutMs: 200,
    requestTimeoutMs: 100,
    stopTimeoutMs: 100,
    autoRestartDelaysMs: [5, 5],
    ...overrides
  };
  const manager = new AgentManager(deps);
  const states: AgentStatus[] = [];
  manager.onChange((s) => states.push(s));
  return { manager, spawn, httpGet, children, states };
}

describe('AgentManager: startup', () => {
  it('starts one owned process, sends the secret only over stdin, and is ready after authenticated health', async () => {
    const { manager, spawn, httpGet, children, states } = setup();
    await manager.start();

    expect(spawn).toHaveBeenCalledTimes(1);
    const [command, args] = spawn.mock.calls[0]!;
    expect(command).toBe(LAUNCH.java.ok && LAUNCH.java.command);
    expect(args).toEqual(['-jar', 'C:\\repo\\agent\\target\\hyperdrive-agent.jar']);
    expect(args.join(' ')).not.toContain(SECRET);

    const bootstrap = JSON.parse(children[0]!.written[0]!) as Record<string, unknown>;
    expect(bootstrap).toEqual({ protocolVersion: 1, secret: SECRET, dataDir: LAUNCH.dataDir, logLevel: 'INFO', requestTimeoutMs: 100 });
    expect(httpGet).toHaveBeenCalledWith(50123, '/local/v1/health', SECRET, 100);

    const status = manager.snapshot();
    expect(status.state).toBe('ready');
    expect(status.agentVersion).toBe('0.1.0');
    expect(status.uptimeMillis).toBe(1234);
    expect(status.capabilities).toEqual({ providerReads: false, rangeReads: false, chunkCache: false, virtualDrive: false });
    expect(states.map((s) => s.state)).toEqual(['starting', 'ready', 'ready']);
    expect(JSON.stringify(states)).not.toMatch(new RegExp(`${SECRET}|50123|java\\.exe|AppData`));
  });

  it('deduplicates concurrent start requests', async () => {
    const { manager, spawn } = setup();
    await Promise.all([manager.start(), manager.start(), manager.start()]);
    await manager.start();
    expect(spawn).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['wrong protocol', JSON.stringify({ type: 'ready', protocolVersion: 2, port: 50123, instanceId: INSTANCE, agentVersion: '1' }), 'protocol-mismatch'],
    ['not JSON', 'Started application in 2s', 'handshake-invalid'],
    ['a port out of range', JSON.stringify({ type: 'ready', protocolVersion: 1, port: 80, instanceId: INSTANCE, agentVersion: '1' }), 'handshake-invalid'],
    ['an extra url field', JSON.stringify({ type: 'ready', protocolVersion: 1, port: 50123, instanceId: INSTANCE, agentVersion: '1', url: 'http://evil' }), 'handshake-invalid'],
    ['a locked data directory', JSON.stringify({ type: 'error', code: 'data_dir_locked' }), 'data-dir-locked'],
    ['a rejected bootstrap', JSON.stringify({ type: 'error', code: 'bootstrap_invalid' }), 'bootstrap-rejected']
  ])('rejects %s and kills only its own child', async (_label, line, code) => {
    const { manager, children, httpGet } = setup({}, () => {
      const child = new FakeChild();
      child.onWrite = () => setTimeout(() => child.out(`${line}\n`), 0);
      return child;
    });
    await manager.start();
    expect(manager.snapshot()).toMatchObject({ state: 'failed', error: code });
    expect(children[0]!.killed).toBe(1);
    expect(httpGet).not.toHaveBeenCalled();
  });

  it('rejects oversized output before the handshake', async () => {
    const { manager } = setup({}, () => {
      const child = new FakeChild();
      child.onWrite = () => setTimeout(() => child.out('x'.repeat(5000)), 0);
      return child;
    });
    await manager.start();
    expect(manager.snapshot().error).toBe('handshake-invalid');
  });

  it('times out when no handshake arrives', async () => {
    const { manager, children } = setup({}, () => new FakeChild());
    await manager.start();
    expect(manager.snapshot()).toMatchObject({ state: 'failed', error: 'startup-timeout' });
    expect(children[0]!.killed).toBe(1);
  });

  it('reports a process that exits before readiness', async () => {
    const { manager } = setup({}, () => {
      const child = new FakeChild();
      child.onWrite = () => setTimeout(() => child.exit(1), 0);
      return child;
    });
    await manager.start();
    expect(manager.snapshot()).toMatchObject({ state: 'failed', error: 'exited-before-ready' });
  });

  it('fails when the authenticated health check fails', async () => {
    const { manager, children } = setup({ httpGet: async () => ({ status: 401, body: { code: 'unauthorized' } }) });
    await manager.start();
    expect(manager.snapshot()).toMatchObject({ state: 'failed', error: 'health-failed' });
    expect(children[0]!.killed).toBe(1);
  });

  it('reports missing Java, incompatible Java and a missing artifact without spawning', async () => {
    const notFound = setup({ checkJava: async () => ({ error: 'not-found' }) });
    await notFound.manager.start();
    expect(notFound.manager.snapshot().error).toBe('java-not-found');

    const old = setup({ checkJava: async () => ({ major: 17 }) });
    await old.manager.start();
    expect(old.manager.snapshot().error).toBe('java-incompatible');

    const noJar = setup({ fileExists: () => false });
    await noJar.manager.start();
    expect(noJar.manager.snapshot().error).toBe('artifact-missing');

    const explicitMissing = setup({ launch: { ...LAUNCH, java: { ok: false, reason: 'java-not-found' } } as LaunchConfig });
    await explicitMissing.manager.start();
    expect(explicitMissing.manager.snapshot().error).toBe('java-not-found');

    for (const s of [notFound, old, noJar, explicitMissing]) expect(s.spawn).not.toHaveBeenCalled();
  });

  it('maps a spawn ENOENT to java-not-found', async () => {
    const { manager } = setup({}, () => {
      const child = new FakeChild();
      setTimeout(() => child.emit('error', Object.assign(new Error('spawn java ENOENT'), { code: 'ENOENT' })), 0);
      return child;
    });
    await manager.start();
    expect(manager.snapshot().error).toBe('java-not-found');
  });
});

describe('AgentManager: running, restart and shutdown', () => {
  it('restarts automatically after an unexpected exit, but only a bounded number of times', async () => {
    const { manager, spawn, children } = setup();
    await manager.start();

    for (let i = 0; i < 2; i++) {
      children.at(-1)!.exit(1);
      await vi.waitFor(() => expect(spawn).toHaveBeenCalledTimes(i + 2));
      await vi.waitFor(() => expect(manager.snapshot().state).toBe('ready'));
    }
    expect(spawn).toHaveBeenCalledTimes(3);
    expect(manager.snapshot().autoRestartsRemaining).toBe(0);

    children.at(-1)!.exit(1);
    await vi.waitFor(() => expect(manager.snapshot()).toMatchObject({ state: 'failed', error: 'unexpected-exit' }));
    await new Promise((r) => setTimeout(r, 30));
    expect(spawn).toHaveBeenCalledTimes(3);
  });

  it('manual restart stops the owned agent gracefully, then starts a new one', async () => {
    const { manager, spawn, children } = setup();
    await manager.start();
    const first = children[0]!;
    const status = await manager.restart();
    expect(first.written.some((w) => w.includes('"shutdown"'))).toBe(true);
    expect(first.stdinEnded).toBe(true);
    expect(first.killed).toBe(0);
    expect(spawn).toHaveBeenCalledTimes(2);
    expect(status.state).toBe('ready');
  });

  it('concurrent restarts are deduplicated', async () => {
    const { manager, spawn } = setup();
    await manager.start();
    await Promise.all([manager.restart(), manager.restart()]);
    expect(spawn.mock.calls.length).toBeLessThanOrEqual(3);
    expect(manager.snapshot().state).toBe('ready');
  });

  it('stop escalates to killing only the owned child when it ignores shutdown', async () => {
    const { manager, children } = setup({}, () => {
      const child = cooperativeChild();
      child.onWrite = (data) => {
        if (data.includes('"secret"')) setTimeout(() => child.out(`${READY}\n`), 0);
        // ignores shutdown and EOF
      };
      return child;
    });
    await manager.start();
    await manager.stop();
    expect(children[0]!.killed).toBe(1);
    expect(manager.snapshot().state).toBe('stopped');
  });

  it('an intentional stop does not trigger an automatic restart', async () => {
    const { manager, spawn } = setup();
    await manager.start();
    await manager.stop();
    await new Promise((r) => setTimeout(r, 30));
    expect(spawn).toHaveBeenCalledTimes(1);
    expect(manager.snapshot().state).toBe('stopped');
  });

  it('status refreshes are single-flight and keep the secret in the main process', async () => {
    let calls = 0;
    const { manager } = setup({
      httpGet: async (_p, path) => {
        if (path === '/local/v1/status') calls++;
        await new Promise((r) => setTimeout(r, 10));
        return path === '/local/v1/health' ? { status: 200, body: { status: 'UP' } } : { status: 200, body: STATUS };
      },
      now: (() => {
        let t = 0;
        return () => (t += 2000);
      })()
    });
    await manager.start();
    calls = 0;
    const results = await Promise.all([manager.getStatus(), manager.getStatus(), manager.getStatus()]);
    expect(calls).toBe(1);
    for (const r of results) expect(Object.keys(r).sort()).toEqual(['agentVersion', 'autoRestartsRemaining', 'capabilities', 'error', 'state', 'uptimeMillis']);
  });

  it('captures stderr with bounds and redacts the secret', async () => {
    const { manager, children } = setup();
    await manager.start();
    for (let i = 0; i < 80; i++) children[0]!.err(`line ${i} ${SECRET}\n`);
    children[0]!.err('y'.repeat(10_000));
    const log = manager.log.snapshot();
    expect(log.length).toBe(50);
    expect(log.join('\n')).not.toContain(SECRET);
  });
});

describe('agent launch helpers', () => {
  it('parses Java versions', () => {
    expect(parseJavaMajor('openjdk version "21.0.5" 2024-10-15')).toBe(21);
    expect(parseJavaMajor('java version "25.0.5" 2025-10-21 LTS')).toBe(25);
    expect(parseJavaMajor('java version "1.8.0_401"')).toBe(8);
    expect(parseJavaMajor('openjdk version "17" 2021-09-14')).toBe(17);
    expect(parseJavaMajor('nothing here')).toBeNull();
  });

  it('resolves Java from the explicit setting, JAVA_HOME, then PATH', () => {
    const exists = (p: string) => p === 'C:\\jdk\\bin\\java.exe' || p === 'C:\\custom\\java.exe';
    expect(resolveJava({ HYPERDRIVE_AGENT_JAVA: 'C:\\custom\\java.exe' }, 'win32', exists)).toMatchObject({ ok: true, source: 'HYPERDRIVE_AGENT_JAVA' });
    expect(resolveJava({ HYPERDRIVE_AGENT_JAVA: 'relative\\java.exe' }, 'win32', exists)).toEqual({ ok: false, reason: 'java-not-found' });
    expect(resolveJava({ HYPERDRIVE_AGENT_JAVA: 'C:\\missing\\java.exe', JAVA_HOME: 'C:\\jdk' }, 'win32', exists)).toEqual({
      ok: false,
      reason: 'java-not-found'
    });
    expect(resolveJava({ JAVA_HOME: 'C:\\jdk' }, 'win32', exists)).toMatchObject({ ok: true, source: 'JAVA_HOME' });
    expect(resolveJava({}, 'win32', exists)).toEqual({ ok: true, command: 'java', source: 'PATH' });
  });

  it('computes the dev jar and data directory, and refuses a packaged app without an explicit jar', () => {
    const base = { platform: 'win32' as const, appPath: 'C:\\repo\\desktop', userData: 'C:\\Users\\me\\AppData\\Roaming\\hd', fileExists: () => false };
    const dev = resolveLaunchConfig({ ...base, env: {}, isPackaged: false });
    expect(dev).toMatchObject({ dataDir: expect.stringMatching(/hd[\\/]agent$/), logLevel: 'INFO' });
    expect('jarPath' in dev && dev.jarPath).toMatch(/agent[\\/]target[\\/]hyperdrive-agent\.jar$/);
    expect(resolveLaunchConfig({ ...base, env: {}, isPackaged: true })).toEqual({ error: 'not-packaged' });
    expect(resolveLaunchConfig({ ...base, env: { HYPERDRIVE_AGENT_JAR: 'rel.jar' }, isPackaged: false })).toEqual({ error: 'artifact-missing' });
  });

  it('handshake parsing never accepts a child-supplied host', () => {
    expect(parseHandshake(READY)).toMatchObject({ type: 'ready', port: 50123 });
    expect(parseHandshake(JSON.stringify({ ...JSON.parse(READY), host: '0.0.0.0' }))).toEqual({ invalid: 'handshake-invalid' });
  });

  it('bounded buffers stay bounded', () => {
    const lines: string[] = [];
    const reader = new HandshakeReader(10, (l) => lines.push(l), () => lines.push('overflow'));
    reader.push('abc');
    reader.push('def\nrest');
    reader.push('more\n');
    expect(lines).toEqual(['abcdef']);
    const log = new BoundedLog(2, 5);
    log.push('1234567\nb\nc\n');
    expect(log.snapshot()).toEqual(['b', 'c']);
  });
});
