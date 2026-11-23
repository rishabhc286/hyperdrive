import type { VirtualDriveError, VirtualDrivePhase } from '../../shared/vfs';
import { isAllowedDriveLetter } from '../../shared/vfs';
import { BoundedLog } from '../agent/output-buffer';

/** The parts of a Node ChildProcess the manager uses (a double in tests). */
export interface VfsChild {
  readonly pid?: number | undefined;
  readonly stdin: { write(data: string): boolean; end(): void; on(event: 'error', listener: (error: Error) => void): unknown };
  readonly stdout: { setEncoding(encoding: 'utf8'): unknown; on(event: 'data', listener: (chunk: string) => void): unknown };
  readonly stderr: { setEncoding(encoding: 'utf8'): unknown; on(event: 'data', listener: (chunk: string) => void): unknown };
  on(event: 'exit', listener: (code: number | null, signal: string | null) => void): unknown;
  on(event: 'error', listener: (error: NodeJS.ErrnoException) => void): unknown;
  kill(): boolean;
}

export interface VfsManagerDeps {
  /** Absolute, trusted path of hyperdrive-vfs.exe (resolved by main-process configuration). */
  readonly exePath: string;
  /** Spawns WITHOUT a shell and WITHOUT arguments (normal mode never passes any). */
  readonly spawn: (command: string, args: readonly string[]) => VfsChild;
  readonly startupTimeoutMs?: number;
  readonly stopTimeoutMs?: number;
}

export interface MountRequest {
  readonly agentPort: number;
  /** Mount capability from the agent; written once to the child's stdin, never stored or logged. */
  readonly capability: string;
  readonly driveLetter: string;
}

export type VfsEvent =
  | { readonly kind: 'phase'; readonly phase: VirtualDrivePhase }
  | { readonly kind: 'failed'; readonly error: VirtualDriveError }
  | { readonly kind: 'unmounted'; readonly reason: 'requested' | 'authorization-lost' | 'agent-unreachable' | 'parent-closed' | 'exited' };

const MAX_EVENT_LINE = 1024;
const PROTOCOL_VERSION = 1;
const CAPABILITY = /^hdv_[A-Za-z0-9_-]{43}$/;

const FAILURE_CODES: Readonly<Record<string, VirtualDriveError>> = {
  'drive-letter-in-use': 'drive-letter-in-use',
  invalid_drive_letter: 'invalid-drive-letter',
  'agent-unauthorized': 'authorization-lost',
  'agent-unreachable': 'agent-unavailable',
  'winfsp-runtime-missing': 'winfsp-runtime-missing',
  'mount-failed': 'mount-failed',
  'mount-not-visible': 'mount-failed',
  'volume-create-failed': 'mount-failed',
  'dispatcher-failed': 'mount-failed'
};

/**
 * Owns at most one hyperdrive-vfs.exe child (Batch 8). Lifecycle:
 * spawn (no shell, no args) → one bounded bootstrap line on stdin → JSON events on stdout →
 * "mounted" only after the native process confirmed Windows shows the drive.
 *
 * Unmount writes "unmount" to stdin and waits; only if the child doesn't exit in time is THIS child
 * killed (never by name, never another process). Closing stdin (Electron exit or crash) makes the
 * child unmount by itself.
 */
export class VfsManager {
  private child: VfsChild | null = null;
  private exited: Promise<number | null> | null = null;
  readonly log = new BoundedLog(100, 400);
  private readonly listeners = new Set<(event: VfsEvent) => void>();

  constructor(private readonly deps: VfsManagerDeps) {}

  onEvent(listener: (event: VfsEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  isRunning(): boolean {
    return this.child !== null;
  }

  /** Resolves when "mounted" arrives; rejects with a VirtualDriveError code otherwise. */
  mount(request: MountRequest): Promise<void> {
    if (this.child) return Promise.reject(new VfsMountError('unknown'));
    if (!Number.isInteger(request.agentPort) || request.agentPort < 1024 || request.agentPort > 65535 ||
        !CAPABILITY.test(request.capability) || !isAllowedDriveLetter(request.driveLetter)) {
      return Promise.reject(new VfsMountError(isAllowedDriveLetter(request.driveLetter) ? 'unknown' : 'invalid-drive-letter'));
    }
    let child: VfsChild;
    try {
      child = this.deps.spawn(this.deps.exePath, []);
    } catch {
      return Promise.reject(new VfsMountError('vfs-exited'));
    }
    this.child = child;
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => this.log.push(chunk));
    child.stdin.on('error', () => undefined);

    return new Promise<void>((resolve, reject) => {
      let settled = false;
      let mounted = false;
      let buffer = '';
      let expected = 0; // index into the phase order
      const order: readonly string[] = ['started', 'agent-authenticated', 'volume-created', 'mounted'];
      const settle = (error: VirtualDriveError | null) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) {
          this.kill(child);
          reject(new VfsMountError(error));
        } else {
          resolve();
        }
      };
      const timer = setTimeout(() => settle('startup-timeout'), this.deps.startupTimeoutMs ?? 30_000);

      this.exited = new Promise((resolveExit) => {
        child.on('exit', (code) => {
          if (this.child === child) this.child = null;
          settle('vfs-exited');
          if (mounted) this.emit({ kind: 'unmounted', reason: 'exited' });
          resolveExit(code);
        });
      });
      child.on('error', () => settle('vfs-exited'));

      const handle = (line: string) => {
        const event = parseEvent(line);
        if (!event) {
          settle('invalid-handshake');
          return;
        }
        if (event.event === 'failed') {
          const code = FAILURE_CODES[event.code ?? ''] ?? 'mount-failed';
          this.emit({ kind: 'failed', error: code });
          settle(code);
          return;
        }
        if (event.event === 'unmounted') {
          const reason = event.reason === 'authorization-lost' || event.reason === 'agent-unreachable' ||
            event.reason === 'parent-closed' ? event.reason : 'requested';
          mounted = false;
          this.emit({ kind: 'unmounted', reason });
          return;
        }
        if (mounted || event.event !== order[expected]) {
          settle('invalid-handshake');
          return;
        }
        if (event.event === 'started' && event.protocolVersion !== PROTOCOL_VERSION) {
          settle('invalid-handshake');
          return;
        }
        expected += 1;
        const phase: VirtualDrivePhase =
          event.event === 'started' ? 'process-started' : (event.event as VirtualDrivePhase);
        this.emit({ kind: 'phase', phase });
        if (event.event === 'started') {
          // Bootstrap: one bounded line; the capability exists only in this write.
          const line = JSON.stringify({
            protocolVersion: PROTOCOL_VERSION,
            agentPort: request.agentPort,
            capability: request.capability,
            mount: { driveLetter: request.driveLetter }
          });
          child.stdin.write(`${line}\n`);
        }
        if (event.event === 'mounted') {
          if (event.mountPoint !== `${request.driveLetter}:` || event.fixture !== false) {
            settle('invalid-handshake');
            return;
          }
          mounted = true;
          settle(null);
        }
      };

      child.stdout.on('data', (chunk) => {
        buffer += chunk;
        let nl: number;
        while ((nl = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, nl).replace(/\r$/, '');
          buffer = buffer.slice(nl + 1);
          handle(line);
        }
        if (buffer.length > MAX_EVENT_LINE) {
          buffer = '';
          settle('invalid-handshake');
        }
      });
    });
  }

  /** Asks the owned child to unmount; kills only that child if it doesn't exit in time. */
  async unmount(): Promise<void> {
    const child = this.child;
    if (!child) return;
    try {
      child.stdin.write('unmount\n');
      child.stdin.end();
    } catch {
      // stdin already closed: fall through to waiting/killing
    }
    const exited = this.exited ?? Promise.resolve(null);
    const timeout = new Promise<'timeout'>((r) => setTimeout(() => r('timeout'), this.deps.stopTimeoutMs ?? 10_000));
    if ((await Promise.race([exited, timeout])) === 'timeout') this.kill(child);
    await Promise.race([exited, new Promise((r) => setTimeout(r, 2_000))]);
    if (this.child === child) this.child = null;
  }

  private kill(child: VfsChild): void {
    try {
      child.kill(); // the owned child only; never by executable name
    } catch {
      // already gone
    }
  }

  private emit(event: VfsEvent): void {
    for (const listener of this.listeners) listener(event);
  }
}

export class VfsMountError extends Error {
  constructor(readonly code: VirtualDriveError) {
    super(code);
    this.name = 'VfsMountError';
  }
}

interface RawEvent {
  event: string;
  protocolVersion?: unknown;
  code?: string;
  reason?: string;
  mountPoint?: unknown;
  fixture?: unknown;
  generation?: unknown;
}

const EVENTS = new Set(['started', 'agent-authenticated', 'volume-created', 'mounted', 'failed', 'unmounted', 'fixture-mode']);

/** Strict: known event names, known keys, bounded strings. */
export function parseEvent(line: string): RawEvent | null {
  if (line.length > MAX_EVENT_LINE) return null;
  let value: unknown;
  try {
    value = JSON.parse(line);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const v = value as Record<string, unknown>;
  if (typeof v['event'] !== 'string' || !EVENTS.has(v['event']) || v['event'] === 'fixture-mode') return null;
  const allowed = new Set(['event', 'protocolVersion', 'code', 'reason', 'mountPoint', 'fixture', 'generation']);
  for (const key of Object.keys(v)) if (!allowed.has(key)) return null;
  for (const key of ['code', 'reason']) {
    const s = v[key];
    if (s !== undefined && (typeof s !== 'string' || !/^[a-z_-]{1,40}$/.test(s))) return null;
  }
  return v as unknown as RawEvent;
}
