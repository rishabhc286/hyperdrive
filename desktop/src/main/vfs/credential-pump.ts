import { SessionExpiredError } from '../auth/auth-manager';
import { ApiError, BackendUnreachableError, type ReadCredential } from '../auth/backend-api';

/** Failure codes the agent accepts on /local/v1/vfs/mount/credential-failed (CredentialBroker.FAILURE_CODES). */
export type RenewalFailureCode =
  | 'needs_reconnect'
  | 'content_permission_missing'
  | 'account_not_found'
  | 'session_expired'
  | 'backend_unavailable'
  | 'rate_limited'
  | 'agent_rejected';

export type PumpPath = '/local/v1/vfs/credential-requests' | '/local/v1/vfs/mount/credential' | '/local/v1/vfs/mount/credential-failed';

export interface CredentialPumpDeps {
  /** Authenticated POST to the agent (launch secret), main process only. */
  readonly post: (path: PumpPath, body: unknown, timeoutMs: number, signal: AbortSignal) => Promise<{ status: number; body: Uint8Array }>;
  /** POST /api/v1/accounts/{id}/read-credentials with the signed-in session (Batch 6 delegation). */
  readonly issueCredential: (accountId: string) => Promise<ReadCredential>;
  readonly sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const POLL_TIMEOUT_MS = 30_000; // the agent answers within 25 s
const MAX_ACCOUNTS_PER_POLL = 32;
const DECODER = new TextDecoder();

/** Maps a failed delegation to the agent's renewal failure codes (never a token or message). */
export function classifyRenewalFailure(error: unknown): RenewalFailureCode {
  if (error instanceof SessionExpiredError) return 'session_expired';
  if (error instanceof BackendUnreachableError) return 'backend_unavailable';
  if (error instanceof ApiError) {
    if (error.code === 'account_needs_reconnect') return 'needs_reconnect';
    if (error.code === 'content_permission_missing') return 'content_permission_missing';
    if (error.status === 404) return 'account_not_found';
    if (error.status === 401) return 'session_expired';
    if (error.status === 429) return 'rate_limited';
  }
  return 'backend_unavailable';
}

/**
 * Credential renewal for mounted reads (Batch 9, ADR 0005), entirely in the main process: no
 * renderer window is involved. While a drive generation is mounted it long-polls the agent for
 * accounts that need a delegated provider credential, obtains each with the signed-in session and
 * installs it for that same generation. One renewal per account at a time; stop() (unmount, logout,
 * disconnect, agent restart, quit) aborts everything, and a credential obtained after stop() is
 * never handed to the agent. The agent itself refuses installs for an old generation.
 */
export class VfsCredentialPump {
  private current: { generation: number; abort: AbortController } | null = null;
  private readonly inFlight = new Set<string>();
  private readonly sleep: (ms: number, signal: AbortSignal) => Promise<void>;

  constructor(private readonly deps: CredentialPumpDeps) {
    this.sleep = deps.sleep ?? abortableDelay;
  }

  /** The generation currently served, or null. */
  generation(): number | null {
    return this.current?.generation ?? null;
  }

  start(generation: number): Promise<void> {
    this.stop();
    const abort = new AbortController();
    this.current = { generation, abort };
    return this.loop(generation, abort.signal);
  }

  stop(): void {
    this.current?.abort.abort();
    this.current = null;
    this.inFlight.clear();
  }

  private async loop(generation: number, signal: AbortSignal): Promise<void> {
    let backoff = 0;
    while (!signal.aborted) {
      let response: { status: number; body: Uint8Array };
      try {
        response = await this.deps.post('/local/v1/vfs/credential-requests', { generation }, POLL_TIMEOUT_MS, signal);
      } catch {
        if (signal.aborted) return;
        backoff = Math.min(backoff === 0 ? 1_000 : backoff * 2, 10_000);
        await this.sleep(backoff, signal).catch(() => undefined);
        continue;
      }
      if (response.status === 404) return; // this generation ended in the agent
      if (response.status !== 200) {
        backoff = Math.min(backoff === 0 ? 1_000 : backoff * 2, 10_000);
        await this.sleep(backoff, signal).catch(() => undefined);
        continue;
      }
      backoff = 0;
      for (const accountId of requestedAccounts(response.body)) {
        if (this.inFlight.has(accountId)) continue; // one renewal per account at a time
        this.inFlight.add(accountId);
        void this.renew(generation, accountId, signal).finally(() => {
          if (!signal.aborted) this.inFlight.delete(accountId);
        });
      }
    }
  }

  private async renew(generation: number, accountId: string, signal: AbortSignal): Promise<void> {
    let credential: ReadCredential;
    try {
      credential = await this.deps.issueCredential(accountId);
    } catch (error) {
      if (signal.aborted) return;
      await this.report(generation, accountId, classifyRenewalFailure(error), signal);
      return;
    }
    // Logout/unmount/disconnect while the backend answered: the credential is dropped here.
    if (signal.aborted || credential.accountId !== accountId) return;
    try {
      const r = await this.deps.post(
        '/local/v1/vfs/mount/credential',
        {
          generation,
          accountId,
          provider: credential.provider,
          accessToken: credential.accessToken,
          providerExpiresAt: credential.accessTokenExpiresAt,
          ...(credential.rootNamespaceId ? { rootNamespaceId: credential.rootNamespaceId } : {})
        },
        10_000,
        signal
      );
      if (r.status === 400) await this.report(generation, accountId, 'agent_rejected', signal);
      // 404: old generation (nothing installed); 409: request no longer pending (nothing installed).
    } catch {
      // Agent gone or aborted: nothing to do; waiting reads time out in the agent.
    }
  }

  private async report(generation: number, accountId: string, code: RenewalFailureCode, signal: AbortSignal): Promise<void> {
    if (signal.aborted) return;
    await this.deps.post('/local/v1/vfs/mount/credential-failed', { generation, accountId, code }, 10_000, signal).catch(() => undefined);
  }
}

function requestedAccounts(body: Uint8Array): string[] {
  try {
    const parsed: unknown = JSON.parse(DECODER.decode(body));
    const requests = (parsed as { requests?: unknown }).requests;
    if (!Array.isArray(requests)) return [];
    const ids = requests
      .map((r) => (typeof r === 'object' && r !== null ? (r as { accountId?: unknown }).accountId : undefined))
      .filter((id): id is string => typeof id === 'string' && UUID_RE.test(id));
    return [...new Set(ids)].slice(0, MAX_ACCOUNTS_PER_POLL);
  } catch {
    return [];
  }
}

function abortableDelay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new Error('aborted'));
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new Error('aborted'));
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
}
