import {
  BYPASS_REASONS,
  isValidCacheBudget,
  type CacheBypassReason,
  type CacheCounters,
  type CacheDisabledReason,
  type CacheResult,
  type CacheStatusView
} from '../../shared/cache';
import { AgentUnavailableError, type AgentPostPath, type AgentPostResponse } from '../agent/agent-manager';

/** The parts of AgentManager the cache coordinator uses. */
export interface CacheAgentPort {
  instanceId(): string | null;
  post(path: AgentPostPath, body: unknown, timeoutMs?: number): Promise<AgentPostResponse & { instanceId: string }>;
  get(path: '/local/v1/cache/status', timeoutMs?: number): Promise<{ status: number; body: unknown; instanceId: string }>;
}

export interface CacheKeys {
  readonly available: boolean;
  getOrCreate(userId: string): Promise<{ key: Buffer; fresh: boolean } | null>;
  remove(userId: string): Promise<void>;
}

export interface CacheBudgetSettings {
  budgetBytes(): Promise<number>;
  setBudgetBytes(bytes: number): Promise<void>;
}

export interface CacheCoordinatorDeps {
  readonly agent: CacheAgentPort;
  readonly keys: CacheKeys;
  readonly settings: CacheBudgetSettings;
  /** The signed-in HyperDrive user's id, or null. */
  readonly currentUserId: () => string | null;
}

/**
 * Only a real end of the session purges the cache and deletes the key. "checking", "unavailable"
 * (backend unreachable) and "onboarding" are not logouts and must not wipe anything.
 */
export function endsCacheSession(phase: string): boolean {
  return phase === 'signed-out' || phase === 'session-expired';
}

export interface CleanupReport {
  readonly agentReached: boolean;
  readonly removed: number;
  readonly failed: number;
}

const DECODER = new TextDecoder();
const COUNTER_KEYS: readonly (keyof CacheCounters)[] = [
  'hits',
  'misses',
  'notAdmitted',
  'evictions',
  'corruptions',
  'bytesRequested',
  'providerBytesFetched',
  'bytesServedFromCache'
];

/**
 * Main-process owner of the cache key and cache policy (Batch 7; docs/cache.md):
 * - hands the user's key to each agent instance (single-flight, re-sent after an agent restart);
 * - budget and clear-cache for the Settings UI;
 * - purges an account's chunks on disconnect (retried at the next session if the agent didn't confirm);
 * - on logout ends the agent session (purging the user's chunks) and deletes the wrapped key.
 * The key only ever travels main → agent; status for the renderer is re-parsed field by field.
 */
export class CacheCoordinator {
  private session: { userId: string; instanceId: string } | null = null;
  private starting: Promise<boolean> | null = null;
  private readonly pendingAccountPurges = new Set<string>();
  private lastUserId: string | null = null;

  constructor(private readonly deps: CacheCoordinatorDeps) {}

  /** Remember who is signed in, so logout cleanup knows whose cache to purge. */
  noteUser(userId: string | null): void {
    if (userId) this.lastUserId = userId;
  }

  /** Makes sure the running agent has this user's key. False means "serve uncached". */
  ensureSession(): Promise<boolean> {
    const userId = this.deps.currentUserId();
    const instance = this.deps.agent.instanceId();
    if (!userId || !instance || !this.deps.keys.available) return Promise.resolve(false);
    this.lastUserId = userId;
    if (this.session?.userId === userId && this.session.instanceId === instance) return Promise.resolve(true);
    if (!this.starting) {
      this.starting = this.start(userId).finally(() => {
        this.starting = null;
      });
    }
    return this.starting;
  }

  private async start(userId: string): Promise<boolean> {
    const material = await this.deps.keys.getOrCreate(userId);
    if (!material) return false;
    const budgetBytes = await this.deps.settings.budgetBytes();
    let response: AgentPostResponse & { instanceId: string };
    try {
      response = await this.deps.agent.post(
        '/local/v1/cache/session',
        { userId, key: material.key.toString('base64'), fresh: material.fresh, budgetBytes },
        10_000
      );
    } catch {
      return false;
    } finally {
      material.key.fill(0); // best effort; the base64 string copy can't be wiped
    }
    if (response.status !== 200) return false;
    this.session = { userId, instanceId: response.instanceId };
    for (const accountId of [...this.pendingAccountPurges]) {
      if (await this.clearAccount(accountId)) this.pendingAccountPurges.delete(accountId);
    }
    return true;
  }

  async status(): Promise<CacheResult<CacheStatusView>> {
    if (!this.deps.currentUserId()) return { ok: false, error: 'signed-out' };
    if (!this.deps.agent.instanceId()) return { ok: false, error: 'agent-unavailable' };
    await this.ensureSession();
    try {
      const response = await this.deps.agent.get('/local/v1/cache/status');
      if (response.status !== 200) return { ok: false, error: 'unknown' };
      const view = this.parseStatus(response.body);
      return view ? { ok: true, value: view } : { ok: false, error: 'unknown' };
    } catch (error) {
      return { ok: false, error: error instanceof AgentUnavailableError ? 'agent-unavailable' : 'unknown' };
    }
  }

  async setBudget(budgetBytes: number): Promise<CacheResult<CacheStatusView>> {
    if (!isValidCacheBudget(budgetBytes)) return { ok: false, error: 'invalid-request' };
    if (!this.deps.currentUserId()) return { ok: false, error: 'signed-out' };
    await this.deps.settings.setBudgetBytes(budgetBytes);
    if (this.deps.agent.instanceId()) {
      try {
        const response = await this.deps.agent.post('/local/v1/cache/budget', { budgetBytes }, 10_000);
        if (response.status !== 200) return { ok: false, error: 'unknown' };
      } catch {
        return { ok: false, error: 'agent-unavailable' };
      }
    }
    return this.status();
  }

  /** Clear cache (after the renderer's confirmation): this user's chunks only. */
  async clear(): Promise<CacheResult<{ removed: number; failed: number }>> {
    if (!this.deps.currentUserId()) return { ok: false, error: 'signed-out' };
    if (!(await this.ensureSession())) {
      return { ok: false, error: this.deps.agent.instanceId() ? 'unknown' : 'agent-unavailable' };
    }
    try {
      const response = await this.deps.agent.post('/local/v1/cache/clear', {}, 30_000);
      const counts = parseCounts(response);
      return counts ? { ok: true, value: counts } : { ok: false, error: 'unknown' };
    } catch {
      return { ok: false, error: 'agent-unavailable' };
    }
  }

  /** Disconnect: purge this account's chunks; retried at the next session if not confirmed now. */
  async forgetAccount(accountId: string): Promise<void> {
    if (!this.deps.keys.available) return; // no key → nothing was ever cached
    if ((await this.ensureSession()) && (await this.clearAccount(accountId))) return;
    this.pendingAccountPurges.add(accountId);
  }

  private async clearAccount(accountId: string): Promise<boolean> {
    try {
      const response = await this.deps.agent.post('/local/v1/cache/clear', { accountId }, 30_000);
      const counts = parseCounts(response);
      return counts !== null && counts.failed === 0;
    } catch {
      return false;
    }
  }

  /**
   * Logout: purge the user's chunks in the agent and forget the key there, then delete the wrapped
   * key file. If the agent can't be reached, the files stay on disk but can't be decrypted any more;
   * they are deleted at the next cache session start.
   */
  async endSession(): Promise<CleanupReport> {
    const userId = this.session?.userId ?? this.lastUserId;
    this.session = null;
    this.pendingAccountPurges.clear();
    if (!userId) return { agentReached: false, removed: 0, failed: 0 };
    let report: CleanupReport = { agentReached: false, removed: 0, failed: 0 };
    try {
      if (this.deps.agent.instanceId()) {
        const counts = parseCounts(await this.deps.agent.post('/local/v1/cache/session/end', { userId }, 30_000));
        if (counts) report = { agentReached: true, ...counts };
      }
    } catch {
      // reported below
    } finally {
      await this.deps.keys.remove(userId).catch(() => undefined);
    }
    if (!report.agentReached || report.failed > 0) {
      console.warn(
        `[hyperdrive] cache cleanup after sign-out incomplete (agent reached: ${report.agentReached}, files not deleted: ${report.failed}); the key was deleted, so remaining files can't be decrypted and are removed at the next session.`
      );
    }
    return report;
  }

  private parseStatus(body: unknown): CacheStatusView | null {
    if (typeof body !== 'object' || body === null) return null;
    const v = body as Record<string, unknown>;
    const n = (x: unknown): number | null => (typeof x === 'number' && Number.isSafeInteger(x) && x >= 0 ? x : null);
    const counters = v['counters'] as Record<string, unknown> | undefined;
    const bypassesIn = v['bypasses'] as Record<string, unknown> | undefined;
    const budgetBytes = n(v['budgetBytes']);
    const encryptedBytesOnDisk = n(v['encryptedBytesOnDisk']);
    const tempBytes = n(v['tempBytes']);
    const entryCount = n(v['entryCount']);
    const overBudgetBytes = n(v['overBudgetBytes']);
    if (
      typeof v['enabled'] !== 'boolean' ||
      budgetBytes === null ||
      encryptedBytesOnDisk === null ||
      tempBytes === null ||
      entryCount === null ||
      overBudgetBytes === null ||
      typeof counters !== 'object' ||
      !counters
    ) {
      return null;
    }
    const parsedCounters = {} as Record<keyof CacheCounters, number>;
    for (const key of COUNTER_KEYS) {
      const value = n(counters[key]);
      if (value === null) return null;
      parsedCounters[key] = value;
    }
    const bypasses: Partial<Record<CacheBypassReason, number>> = {};
    for (const reason of BYPASS_REASONS) {
      const value = n(bypassesIn?.[reason]);
      if (value !== null) bypasses[reason] = value;
    }
    const last = v['lastCleanup'] as Record<string, unknown> | null | undefined;
    const lastCleanup =
      last && typeof last === 'object' && typeof last['scope'] === 'string' && /^[a-z-]{1,20}$/.test(last['scope'])
        ? { scope: last['scope'], removed: n(last['removed']) ?? 0, failed: n(last['failed']) ?? 0 }
        : null;
    const reason = v['disabledReason'];
    let disabledReason: CacheDisabledReason | null = null;
    if (reason === 'cache_key_unavailable') {
      disabledReason = this.deps.keys.available ? 'key-unavailable' : 'key-protection-unavailable';
    } else if (reason === 'cache_disabled') {
      disabledReason = 'disabled-by-budget';
    }
    return {
      enabled: v['enabled'] === true,
      disabledReason,
      keyProtection: this.deps.keys.available ? 'available' : 'unavailable',
      budgetBytes,
      encryptedBytesOnDisk,
      tempBytes,
      entryCount,
      overBudgetBytes,
      counters: parsedCounters,
      bypasses,
      lastCleanup
    };
  }
}

function parseCounts(response: AgentPostResponse): { removed: number; failed: number } | null {
  if (response.status !== 200) return null;
  try {
    const body = JSON.parse(DECODER.decode(response.body)) as Record<string, unknown>;
    const removed = body['removed'];
    const failed = body['failed'];
    if (typeof removed === 'number' && Number.isSafeInteger(removed) && typeof failed === 'number' && Number.isSafeInteger(failed)) {
      return { removed, failed };
    }
  } catch {
    // fall through
  }
  return null;
}
