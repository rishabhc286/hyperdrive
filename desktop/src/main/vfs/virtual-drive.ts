import type {
  VirtualDriveError,
  VirtualDrivePhase,
  VirtualDrivePrerequisite,
  VirtualDriveResult,
  VirtualDriveStatus
} from '../../shared/vfs';
import { DEFAULT_DRIVE_LETTER, isAllowedDriveLetter } from '../../shared/vfs';
import type { MountRequest, VfsEvent } from './vfs-manager';
import { VfsMountError } from './vfs-manager';

/** The parts of the agent the coordinator needs (a double in tests). */
export interface VirtualDriveAgentPort {
  /** The ready agent's loopback port, or null. Not a secret; never sent to React. */
  loopbackPort(): number | null;
  instanceId(): string | null;
  post(
    path: '/local/v1/vfs/mount' | '/local/v1/vfs/mount/grant' | '/local/v1/vfs/mount/revoke',
    body: unknown,
    timeoutMs?: number
  ): Promise<{ status: number; body: Uint8Array }>;
}

export interface VfsPort {
  mount(request: MountRequest): Promise<void>;
  unmount(): Promise<void>;
  isRunning(): boolean;
  onEvent(listener: (event: VfsEvent) => void): () => void;
}

export interface MetadataGrant {
  readonly grant: string;
  readonly expiresAt: string;
}

export interface VirtualDriveDeps {
  readonly vfs: VfsPort | null;
  /** Why the drive can't work at all on this machine/build, or null. */
  readonly prerequisite: () => VirtualDrivePrerequisite | null;
  readonly agent: VirtualDriveAgentPort;
  /** POST/DELETE /api/v1/metadata-grants with the signed-in session (may throw ApiError etc.). */
  readonly issueGrant: () => Promise<MetadataGrant>;
  readonly revokeGrant: () => Promise<void>;
  readonly currentUserId: () => string | null;
  readonly hasConnectedAccounts: () => Promise<boolean>;
  readonly backendOrigin: string;
  readonly driveLetterInUse: (letter: string) => boolean;
  readonly settings: { load(): Promise<string>; save(letter: string): Promise<void> };
  /** Grant renewal interval (the grant lives 15 min). */
  readonly renewEveryMs?: number;
  readonly classifyError?: (error: unknown) => VirtualDriveError;
}

type Internal = 'idle' | 'mounting' | 'mounted' | 'unmounting' | 'failed';
const DECODER = new TextDecoder();

/**
 * Main-process owner of the virtual drive (Batch 8). Mount: metadata grant (backend, session) →
 * mount capability (agent, launch secret) → hyperdrive-vfs.exe (stdin bootstrap). Unmount, logout,
 * disconnect and app shutdown run the reverse: stop admitting mounts, unmount the owned process,
 * revoke the capability in the agent, revoke the grant at the backend.
 * Nothing secret is kept after it's handed on, and nothing secret reaches the renderer.
 */
export class VirtualDriveCoordinator {
  private state: Internal = 'idle';
  private phase: VirtualDrivePhase | null = null;
  private error: VirtualDriveError | null = null;
  private letter = DEFAULT_DRIVE_LETTER;
  private generation: number | null = null;
  private renewTimer: ReturnType<typeof setInterval> | null = null;
  private closed = false;
  private pending: Promise<unknown> = Promise.resolve();
  private readonly listeners = new Set<(status: VirtualDriveStatus) => void>();

  constructor(private readonly deps: VirtualDriveDeps) {
    deps.vfs?.onEvent((event) => this.onVfsEvent(event));
  }

  async init(): Promise<void> {
    const saved = await this.deps.settings.load().catch(() => DEFAULT_DRIVE_LETTER);
    this.letter = isAllowedDriveLetter(saved) ? saved : DEFAULT_DRIVE_LETTER;
  }

  onChange(listener: (status: VirtualDriveStatus) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async status(): Promise<VirtualDriveStatus> {
    const prerequisite = this.deps.prerequisite();
    const base = { driveLetter: this.letter, phase: this.phase, prerequisite };
    if (this.state === 'mounting' || this.state === 'mounted' || this.state === 'unmounting') {
      return { ...base, state: this.state, error: null };
    }
    if (prerequisite) return { ...base, state: 'prerequisite-missing', error: null, phase: null };
    if (!this.deps.currentUserId()) return { ...base, state: 'signed-out', error: null, phase: null };
    if (this.state === 'failed') return { ...base, state: 'failed', error: this.error };
    const any = await this.deps.hasConnectedAccounts().catch(() => true);
    return { ...base, state: any ? 'ready' : 'no-accounts', error: null, phase: null };
  }

  /** Serialized with unmount so a mount and its teardown never interleave. */
  mount(): Promise<VirtualDriveStatus> {
    return this.serialize(() => this.doMount());
  }

  unmount(): Promise<VirtualDriveStatus> {
    return this.serialize(() => this.doUnmount());
  }

  /** Logout, account disconnect, agent restart: tear down whatever is mounted. */
  teardown(): Promise<VirtualDriveStatus> {
    return this.serialize(() => this.doUnmount());
  }

  /** App shutdown: no new mounts are admitted afterwards. */
  async close(): Promise<void> {
    this.closed = true;
    await this.teardown();
  }

  async setDriveLetter(letter: string): Promise<VirtualDriveResult<VirtualDriveStatus>> {
    if (!isAllowedDriveLetter(letter)) return { ok: false, error: 'invalid-drive-letter' };
    if (this.state === 'mounting' || this.state === 'mounted' || this.state === 'unmounting') return { ok: false, error: 'unknown' };
    await this.deps.settings.save(letter);
    this.letter = letter;
    if (this.state === 'failed') this.state = 'idle';
    this.emit();
    return { ok: true, value: await this.status() };
  }

  /** The mount point to open in Explorer, only when the mount is confirmed. */
  mountedRoot(): string | null {
    return this.state === 'mounted' ? `${this.letter}:\\` : null;
  }

  private serialize<T>(task: () => Promise<T>): Promise<T> {
    const next = this.pending.then(task, task);
    this.pending = next.catch(() => undefined);
    return next;
  }

  private async doMount(): Promise<VirtualDriveStatus> {
    if (this.closed || this.state === 'mounted' || this.state === 'mounting') return this.status();
    const vfs = this.deps.vfs;
    const userId = this.deps.currentUserId();
    if (!vfs || this.deps.prerequisite() || !userId) return this.status();
    if (this.deps.driveLetterInUse(this.letter)) return this.fail('drive-letter-in-use');
    const port = this.deps.agent.loopbackPort();
    if (port === null || this.deps.agent.instanceId() === null) return this.fail('agent-unavailable');

    this.state = 'mounting';
    this.phase = null;
    this.error = null;
    this.emit();
    try {
      const grant = await this.deps.issueGrant();
      const created = await this.deps.agent.post('/local/v1/vfs/mount', {
        userId,
        backendOrigin: this.deps.backendOrigin,
        grant: grant.grant,
        grantExpiresAt: grant.expiresAt
      });
      const body = parse(created.body);
      if (created.status !== 201 || typeof body['capability'] !== 'string' || typeof body['generation'] !== 'number') {
        throw new VfsMountError('agent-unavailable');
      }
      this.generation = body['generation'];
      // The capability is passed straight to the owned process and not kept here.
      await vfs.mount({ agentPort: port, capability: body['capability'], driveLetter: this.letter });
      if (this.closed || this.deps.currentUserId() !== userId) {
        // Signed out or shutting down while mounting: don't keep it.
        await this.cleanup();
        return this.status();
      }
      this.state = 'mounted';
      this.phase = 'mounted';
      this.startRenewal();
      this.emit();
      return this.status();
    } catch (error) {
      await this.cleanup();
      return this.fail(error instanceof VfsMountError ? error.code : (this.deps.classifyError?.(error) ?? 'unknown'));
    }
  }

  private async doUnmount(): Promise<VirtualDriveStatus> {
    if (this.state === 'idle' && !this.deps.vfs?.isRunning() && this.generation === null) return this.status();
    if (this.state !== 'failed') {
      this.state = 'unmounting';
      this.emit();
    }
    await this.cleanup();
    if (this.state === 'unmounting') this.state = 'idle';
    this.phase = null;
    this.emit();
    return this.status();
  }

  /** Reverse order of mount; every step best effort, all of them always attempted. */
  private async cleanup(): Promise<void> {
    this.stopRenewal();
    await this.deps.vfs?.unmount().catch(() => undefined);
    if (this.generation !== null) {
      this.generation = null;
      await this.deps.agent.post('/local/v1/vfs/mount/revoke', {}, 10_000).catch(() => undefined);
    }
    await this.deps.revokeGrant().catch(() => undefined);
  }

  private startRenewal(): void {
    this.stopRenewal();
    this.renewTimer = setInterval(() => {
      void this.serialize(async () => {
        if (this.state !== 'mounted' || this.generation === null) return;
        try {
          const grant = await this.deps.issueGrant();
          const r = await this.deps.agent.post('/local/v1/vfs/mount/grant', {
            generation: this.generation,
            grant: grant.grant,
            grantExpiresAt: grant.expiresAt
          });
          if (r.status !== 200) throw new VfsMountError('authorization-lost');
        } catch (error) {
          // Can't keep the backend authorization fresh (session ended, agent restarted…): unmount.
          await this.cleanup();
          this.fail(error instanceof VfsMountError ? error.code : (this.deps.classifyError?.(error) ?? 'authorization-lost'));
        }
      });
    }, this.deps.renewEveryMs ?? 10 * 60_000);
  }

  private stopRenewal(): void {
    if (this.renewTimer) clearInterval(this.renewTimer);
    this.renewTimer = null;
  }

  private onVfsEvent(event: VfsEvent): void {
    if (event.kind === 'phase' && this.state === 'mounting') {
      this.phase = event.phase;
      this.emit();
    }
    if (event.kind === 'unmounted' && this.state === 'mounted') {
      // The native process unmounted on its own (authorization lost, agent gone, crash).
      void this.serialize(async () => {
        await this.cleanup();
        this.fail(event.reason === 'authorization-lost' ? 'authorization-lost' : event.reason === 'agent-unreachable' ? 'agent-unavailable' : 'vfs-exited');
      });
    }
  }

  private fail(error: VirtualDriveError): Promise<VirtualDriveStatus> {
    this.state = 'failed';
    this.error = error;
    this.phase = null;
    this.emit();
    return this.status();
  }

  private emit(): void {
    void this.status().then((s) => {
      for (const listener of this.listeners) listener(s);
    });
  }
}

function parse(bytes: Uint8Array): Record<string, unknown> {
  try {
    const v: unknown = JSON.parse(DECODER.decode(bytes));
    return typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}
