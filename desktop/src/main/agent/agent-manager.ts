import type { AgentCapabilities, AgentErrorCode, AgentState, AgentStatus } from '../../shared/agent';
import type { JavaCandidate } from './java-discovery';
import { REQUIRED_JAVA_MAJOR } from './java-discovery';
import { BoundedLog, HandshakeReader } from './output-buffer';
import { AGENT_PROTOCOL_VERSION, MAX_HANDSHAKE_BYTES, parseHandshake, parseStatus, type Handshake } from './protocol';

/** The subset of Node's ChildProcess the manager uses (so tests can supply a double). */
export interface AgentChild {
  readonly stdin: {
    write(data: string): boolean;
    end(): void;
    on(event: 'error', listener: (error: Error) => void): unknown;
  };
  readonly stdout: { setEncoding(encoding: 'utf8'): unknown; on(event: 'data', listener: (chunk: string) => void): unknown };
  readonly stderr: { setEncoding(encoding: 'utf8'): unknown; on(event: 'data', listener: (chunk: string) => void): unknown };
  on(event: 'exit', listener: (code: number | null, signal: string | null) => void): unknown;
  on(event: 'error', listener: (error: NodeJS.ErrnoException) => void): unknown;
  kill(): boolean;
}

export type LaunchConfig =
  | {
      readonly java: JavaCandidate;
      /** Absolute path of hyperdrive-agent.jar. */
      readonly jarPath: string;
      /** Absolute path of the agent's own directory under Electron userData. */
      readonly dataDir: string;
      readonly logLevel: 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';
    }
  | { readonly error: AgentErrorCode };

export type AgentPath = '/local/v1/health' | '/local/v1/status';
/** The only POST endpoints the desktop may call on the agent (Batch 6). */
export type AgentPostPath = '/local/v1/credentials' | '/local/v1/credentials/clear' | '/local/v1/reads';

export interface AgentPostResponse {
  readonly status: number;
  /** Only the X-HyperDrive-* headers and Content-Type, lower-cased names. */
  readonly headers: Readonly<Record<string, string>>;
  readonly body: Uint8Array;
}

/** The agent isn't ready (or no POST transport is configured); nothing was sent. */
export class AgentUnavailableError extends Error {
  constructor() {
    super('Local agent is not ready');
    this.name = 'AgentUnavailableError';
  }
}

export interface AgentManagerDeps {
  readonly launch: LaunchConfig;
  readonly fileExists: (path: string) => boolean;
  /** Runs `<java> -version` without a shell; reports the major version or why it couldn't. */
  readonly checkJava: (command: string) => Promise<{ readonly major: number } | { readonly error: 'not-found' | 'unknown' }>;
  /** Spawns `<command> <args>` with shell:false and piped stdio. */
  readonly spawn: (command: string, args: readonly string[]) => AgentChild;
  /** Authenticated GET to http://127.0.0.1:<port><path>; resolves with status + parsed JSON. */
  readonly httpGet: (port: number, path: AgentPath, secret: string, timeoutMs: number) => Promise<{ status: number; body: unknown }>;
  /** Authenticated JSON POST to http://127.0.0.1:<port><path> with a bounded raw response body. */
  readonly httpPost?: (
    port: number,
    path: AgentPostPath,
    secret: string,
    body: unknown,
    timeoutMs: number,
    signal?: AbortSignal
  ) => Promise<AgentPostResponse>;
  readonly randomSecret: () => string;
  readonly now?: () => number;
  readonly startupTimeoutMs?: number;
  readonly requestTimeoutMs?: number;
  readonly stopTimeoutMs?: number;
  /** One entry per allowed automatic restart (delay before it). Default: 2 restarts. */
  readonly autoRestartDelaysMs?: readonly number[];
  readonly autoRestartWindowMs?: number;
}

interface Launch {
  readonly generation: number;
  readonly child: AgentChild;
  readonly secret: string;
  readonly exited: Promise<void>;
  port: number | null;
  instanceId: string | null;
  stopping: boolean;
  hasExited: boolean;
}

/**
 * Owns at most one agent child process for this Electron instance. Never attaches to an existing
 * service, never kills by name: every kill() targets the ChildProcess object it spawned itself.
 *
 * Ready means: valid handshake, expected protocol version, a sane port, and a successful
 * authenticated health check. The secret stays here; status snapshots never contain it.
 */
export class AgentManager {
  private state: AgentState = 'stopped';
  private error: AgentErrorCode | null = null;
  private current: Launch | null = null;
  private generation = 0;
  private starting: Promise<void> | null = null;
  private stoppingPromise: Promise<void> | null = null;
  private agentVersion: string | null = null;
  private uptimeMillis: number | null = null;
  private capabilities: AgentCapabilities | null = null;
  private statusInFlight: Promise<void> | null = null;
  private lastStatusAt = 0;
  private autoRestarts: number[] = [];
  private restartTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly listeners = new Set<(status: AgentStatus) => void>();
  private readonly now: () => number;
  private readonly startupTimeoutMs: number;
  private readonly requestTimeoutMs: number;
  private readonly stopTimeoutMs: number;
  private readonly autoRestartDelaysMs: readonly number[];
  private readonly autoRestartWindowMs: number;
  /** Recent stderr lines of the current/last agent (bounded, secret redacted); main-process only. */
  readonly log: BoundedLog;

  constructor(private readonly deps: AgentManagerDeps) {
    this.now = deps.now ?? Date.now;
    this.startupTimeoutMs = deps.startupTimeoutMs ?? 20_000;
    this.requestTimeoutMs = deps.requestTimeoutMs ?? 5_000;
    this.stopTimeoutMs = deps.stopTimeoutMs ?? 5_000;
    this.autoRestartDelaysMs = deps.autoRestartDelaysMs ?? [1_000, 5_000];
    this.autoRestartWindowMs = deps.autoRestartWindowMs ?? 10 * 60_000;
    this.log = new BoundedLog(50, 500, () => this.current?.secret ?? null);
  }

  onChange(listener: (status: AgentStatus) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  snapshot(): AgentStatus {
    const ready = this.state === 'ready';
    return {
      state: this.state,
      agentVersion: this.agentVersion,
      uptimeMillis: ready ? this.uptimeMillis : null,
      capabilities: ready ? this.capabilities : null,
      error: this.error,
      autoRestartsRemaining: Math.max(0, this.autoRestartDelaysMs.length - this.recentAutoRestarts())
    };
  }

  /** Current status; when ready, refreshed from the agent (single-flight, at most once per second). */
  async getStatus(): Promise<AgentStatus> {
    const launch = this.current;
    if (this.state === 'ready' && launch?.port && this.now() - this.lastStatusAt >= 1_000) {
      this.statusInFlight ??= this.refreshStatus(launch).finally(() => {
        this.statusInFlight = null;
      });
      await this.statusInFlight;
    }
    return this.snapshot();
  }

  /** The ready agent's instance id; changes on every (re)start, so callers can drop stale handles. */
  instanceId(): string | null {
    return this.state === 'ready' ? (this.current?.instanceId ?? null) : null;
  }

  /**
   * Authenticated POST to one of the fixed agent endpoints. Main-process only; the secret and port
   * never leave this class. Throws AgentUnavailableError unless the agent is ready.
   */
  async post(path: AgentPostPath, body: unknown, timeoutMs = 60_000, signal?: AbortSignal): Promise<AgentPostResponse & { instanceId: string }> {
    const launch = this.current;
    if (this.state !== 'ready' || !launch?.port || !launch.instanceId || !this.deps.httpPost) {
      throw new AgentUnavailableError();
    }
    const response = await this.deps.httpPost(launch.port, path, launch.secret, body, timeoutMs, signal);
    return { ...response, instanceId: launch.instanceId };
  }

  /** Starts the agent once; concurrent calls share the same attempt. */
  start(): Promise<void> {
    if (this.starting) return this.starting;
    if (this.state === 'ready' || this.state === 'stopping') return this.stoppingPromise ?? Promise.resolve();
    this.starting = this.launch('starting').finally(() => {
      this.starting = null;
    });
    return this.starting;
  }

  /** Manual restart: stop the owned agent (if any), then start a new one. Deduplicated. */
  async restart(): Promise<AgentStatus> {
    if (this.starting) {
      await this.starting;
      return this.snapshot();
    }
    this.cancelAutoRestart();
    await this.stop();
    this.starting = this.launch('restarting').finally(() => {
      this.starting = null;
    });
    await this.starting;
    return this.snapshot();
  }

  /**
   * Graceful stop of the owned process: shutdown message, close the parent channel, wait a bounded
   * time, then kill only that child.
   */
  stop(): Promise<void> {
    this.cancelAutoRestart();
    if (this.stoppingPromise) return this.stoppingPromise;
    const launch = this.current;
    if (!launch) {
      this.generation++; // invalidate any launch still resolving Java etc.
      if (this.state !== 'failed') this.setState('stopped', this.error);
      return Promise.resolve();
    }
    this.stoppingPromise = (async () => {
      launch.stopping = true;
      this.generation++;
      this.setState('stopping', null);
      try {
        launch.child.stdin.write(JSON.stringify({ type: 'shutdown' }) + '\n');
        launch.child.stdin.end();
      } catch {
        // stdin already closed; fall through to waiting/killing
      }
      const exited = await Promise.race([launch.exited.then(() => true), delay(this.stopTimeoutMs).then(() => false)]);
      if (!exited && !launch.hasExited) {
        launch.child.kill();
        await Promise.race([launch.exited, delay(this.stopTimeoutMs)]);
      }
      if (this.current === launch) this.current = null;
      this.setState('stopped', null);
    })().finally(() => {
      this.stoppingPromise = null;
    });
    return this.stoppingPromise;
  }

  private async launch(phase: 'starting' | 'restarting'): Promise<void> {
    const generation = ++this.generation;
    this.agentVersion = null;
    this.uptimeMillis = null;
    this.capabilities = null;
    this.setState(phase, null);

    const config = this.deps.launch;
    if ('error' in config) return this.fail(generation, config.error);
    if (!config.java.ok) return this.fail(generation, 'java-not-found');
    if (!this.deps.fileExists(config.jarPath)) return this.fail(generation, 'artifact-missing');

    const java = await this.deps.checkJava(config.java.command).catch(() => ({ error: 'unknown' as const }));
    if (generation !== this.generation) return;
    if ('error' in java) return this.fail(generation, java.error === 'not-found' ? 'java-not-found' : 'java-incompatible');
    if (java.major < REQUIRED_JAVA_MAJOR) return this.fail(generation, 'java-incompatible');

    const secret = this.deps.randomSecret();
    let child: AgentChild;
    try {
      child = this.deps.spawn(config.java.command, ['-jar', config.jarPath]);
    } catch {
      return this.fail(generation, 'spawn-failed');
    }
    let resolveExit: () => void = () => undefined;
    const launch: Launch = {
      generation,
      child,
      secret,
      exited: new Promise<void>((resolve) => {
        resolveExit = resolve;
      }),
      port: null,
      instanceId: null,
      stopping: false,
      hasExited: false
    };
    this.current = launch;

    type Outcome = Handshake | { invalid: AgentErrorCode } | { exited: true } | { spawnError: boolean } | { timeout: true };
    const outcome = await new Promise<Outcome>((resolve) => {
      const timer = setTimeout(() => resolve({ timeout: true }), this.startupTimeoutMs);
      const settle = (value: Outcome) => {
        clearTimeout(timer);
        resolve(value);
      };
      const reader = new HandshakeReader(MAX_HANDSHAKE_BYTES, (line) => settle(parseHandshake(line)), () =>
        settle({ invalid: 'handshake-invalid' })
      );
      child.stdout.setEncoding('utf8');
      child.stderr.setEncoding('utf8');
      child.stdout.on('data', (chunk) => reader.push(chunk));
      child.stderr.on('data', (chunk) => this.log.push(chunk));
      child.stdin.on('error', () => undefined); // EPIPE if the child dies early; handled via 'exit'
      child.on('error', (error) => {
        launch.hasExited = true;
        resolveExit();
        settle({ spawnError: error.code === 'ENOENT' });
      });
      child.on('exit', () => {
        launch.hasExited = true;
        resolveExit();
        settle({ exited: true });
      });
      const bootstrap = {
        protocolVersion: AGENT_PROTOCOL_VERSION,
        secret,
        dataDir: config.dataDir,
        logLevel: config.logLevel,
        requestTimeoutMs: this.requestTimeoutMs
      };
      try {
        child.stdin.write(JSON.stringify(bootstrap) + '\n');
      } catch {
        settle({ exited: true });
      }
    });

    if (generation !== this.generation) {
      if (!launch.hasExited && !launch.stopping) child.kill();
      return;
    }
    if ('timeout' in outcome) return this.failLaunch(launch, 'startup-timeout');
    if ('exited' in outcome) return this.failLaunch(launch, 'exited-before-ready');
    if ('spawnError' in outcome) return this.failLaunch(launch, outcome.spawnError ? 'java-not-found' : 'spawn-failed');
    if ('invalid' in outcome) return this.failLaunch(launch, outcome.invalid);
    if (outcome.type === 'error') return this.failLaunch(launch, outcome.code);

    launch.port = outcome.port;
    launch.instanceId = outcome.instanceId;
    const health = await this.deps
      .httpGet(outcome.port, '/local/v1/health', secret, this.requestTimeoutMs)
      .catch(() => null);
    if (generation !== this.generation) return;
    if (!health || health.status !== 200 || !isUp(health.body) || launch.hasExited) {
      return this.failLaunch(launch, 'health-failed');
    }

    this.agentVersion = outcome.agentVersion;
    this.setState('ready', null);
    void launch.exited.then(() => this.onExit(launch));
    await this.refreshStatus(launch);
  }

  private async refreshStatus(launch: Launch): Promise<void> {
    if (!launch.port) return;
    const response = await this.deps
      .httpGet(launch.port, '/local/v1/status', launch.secret, this.requestTimeoutMs)
      .catch(() => null);
    if (this.current !== launch || this.state !== 'ready') return;
    this.lastStatusAt = this.now();
    const body = response && response.status === 200 ? parseStatus(response.body) : null;
    if (body) {
      this.agentVersion = body.agentVersion;
      this.uptimeMillis = body.uptimeMillis;
      this.capabilities = body.capabilities;
      this.emit();
    }
  }

  /** A ready agent exited without being asked to: bounded automatic restart. */
  private onExit(launch: Launch): void {
    if (this.current !== launch || launch.stopping) return;
    this.current = null;
    const recent = this.recentAutoRestarts();
    if (recent >= this.autoRestartDelaysMs.length) {
      this.setState('failed', 'unexpected-exit');
      return;
    }
    this.autoRestarts.push(this.now());
    this.setState('restarting', 'unexpected-exit');
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null;
      this.starting ??= this.launch('restarting').finally(() => {
        this.starting = null;
      });
    }, this.autoRestartDelaysMs[recent]);
  }

  private recentAutoRestarts(): number {
    const cutoff = this.now() - this.autoRestartWindowMs;
    this.autoRestarts = this.autoRestarts.filter((t) => t > cutoff);
    return this.autoRestarts.length;
  }

  private cancelAutoRestart(): void {
    if (this.restartTimer) clearTimeout(this.restartTimer);
    this.restartTimer = null;
  }

  private failLaunch(launch: Launch, code: AgentErrorCode): void {
    if (!launch.hasExited) launch.child.kill(); // only the child this manager spawned
    if (this.current === launch) this.current = null;
    this.fail(launch.generation, code);
  }

  private fail(generation: number, code: AgentErrorCode): void {
    if (generation !== this.generation) return;
    this.setState('failed', code);
  }

  private setState(state: AgentState, error: AgentErrorCode | null): void {
    this.state = state;
    this.error = error;
    this.emit();
  }

  private emit(): void {
    const status = this.snapshot();
    for (const listener of this.listeners) listener(status);
  }
}

function isUp(body: unknown): boolean {
  return typeof body === 'object' && body !== null && (body as Record<string, unknown>)['status'] === 'UP';
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
