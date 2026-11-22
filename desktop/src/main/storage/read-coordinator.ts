import { createHash } from 'node:crypto';
import type { ReadDiagnostic, ReadError, StorageFile, StorageProvider, VerifyReadRequest } from '../../shared/storage';
import { MAX_READ_BYTES, PROVIDER_FILE_ID, UUID } from '../../shared/storage';
import { AgentUnavailableError, type AgentPostPath, type AgentPostResponse } from '../agent/agent-manager';
import { SessionExpiredError } from '../auth/auth-manager';
import { ApiError, BackendUnreachableError, type ReadCredential } from '../auth/backend-api';

/** The parts of AgentManager the coordinator needs (a double in tests). */
export interface AgentPort {
  instanceId(): string | null;
  post(path: AgentPostPath, body: unknown, timeoutMs?: number, signal?: AbortSignal): Promise<AgentPostResponse & { instanceId: string }>;
  restart(): Promise<unknown>;
}

export interface ReadCoordinatorDeps {
  /** POST /accounts/{id}/read-credentials through the signed-in session (may throw ApiError etc.). */
  readonly issueCredential: (accountId: string) => Promise<ReadCredential>;
  /** Fresh metadata of the file to read (backend, ownership-checked). */
  readonly getFile: (accountId: string, fileId: string) => Promise<StorageFile>;
  readonly agent: AgentPort;
  readonly now?: () => number;
}

interface Handle {
  readonly handle: string;
  readonly provider: StorageProvider;
  readonly instanceId: string;
  readonly retentionExpiresAt: number;
}

/** Re-delegate when a handle has less than this left. */
const RENEW_MARGIN_MS = 60_000;
const READ_TIMEOUT_MS = 60_000;
const DECODER = new TextDecoder();

/**
 * Main-process read coordinator (Batch 6). Delegation flow:
 * backend issues a provider access token for an owned account → this class hands it to the agent
 * immediately → only the agent's opaque handle is kept here. Renewals are single-flight per
 * account; one controlled re-delegation follows a rejected/expired credential. File bytes from the
 * agent are hashed and zeroed here; they never reach the renderer, disk or logs.
 */
export class ReadCoordinator {
  private readonly handles = new Map<string, Handle>();
  private readonly renewals = new Map<string, Promise<Handle>>();
  private readonly now: () => number;

  constructor(private readonly deps: ReadCoordinatorDeps) {
    this.now = deps.now ?? Date.now;
  }

  async verifyRead(request: VerifyReadRequest): Promise<ReadDiagnostic> {
    const { accountId, fileId, offset, length } = request;
    if (
      !UUID.test(accountId) ||
      !PROVIDER_FILE_ID.test(fileId) ||
      !Number.isSafeInteger(offset) ||
      offset < 0 ||
      !Number.isSafeInteger(length) ||
      length < 1 ||
      length > MAX_READ_BYTES
    ) {
      return fail('invalid-request');
    }
    let file: StorageFile;
    try {
      file = await this.deps.getFile(accountId, fileId);
    } catch (error) {
      return fail(backendError(error));
    }
    // Only real, ordinary, downloadable files with a known size (needed for EOF semantics).
    if (file.kind !== 'FILE' || !file.canDownload || file.sizeBytes === null || file.providerFileId !== fileId) {
      return fail('unsupported-file');
    }
    const body = {
      fileId,
      offset,
      length,
      expectedSize: file.sizeBytes,
      expectedRevision: file.providerRevision
    };

    for (let attempt = 0; attempt < 2; attempt++) {
      let handle: Handle;
      try {
        handle = await this.ensureHandle(accountId, attempt > 0);
      } catch (error) {
        return fail(error instanceof DelegationError ? error.code : backendError(error));
      }
      const started = this.now();
      let response: AgentPostResponse & { instanceId: string };
      try {
        response = await this.deps.agent.post('/local/v1/reads', { handle: handle.handle, ...body }, READ_TIMEOUT_MS);
      } catch {
        return fail('agent-unavailable');
      }
      const elapsedMs = Math.max(0, this.now() - started);
      if (response.status === 200 && (response.headers['content-type'] ?? '').startsWith('application/octet-stream')) {
        return this.digest(response, handle.provider, file, offset, length, elapsedMs);
      }
      const code = errorCode(response);
      if (attempt === 0 && (code === 'credential_rejected' || code === 'credential_expired' || code === 'unknown_credential')) {
        this.handles.delete(accountId); // one controlled re-delegation, then give up
        continue;
      }
      return fail(readError(code));
    }
    return fail('credential-rejected');
  }

  /** Forget one account (disconnect): drop the handle here and its credential in the agent. */
  async forgetAccount(accountId: string): Promise<void> {
    this.handles.delete(accountId);
    await this.clearInAgent({ accountId });
  }

  /** Forget everything (sign-out, session expiry). */
  async reset(): Promise<void> {
    this.handles.clear();
    await this.clearInAgent({});
  }

  private async clearInAgent(body: { accountId?: string }): Promise<void> {
    if (this.deps.agent.instanceId() === null) return; // not running: nothing is held
    try {
      const response = await this.deps.agent.post('/local/v1/credentials/clear', body, 10_000);
      if (response.status === 200) return;
    } catch (error) {
      if (error instanceof AgentUnavailableError) return;
    }
    // Couldn't confirm the clear: restart the owned agent so no delegated credential survives.
    await this.deps.agent.restart().catch(() => undefined);
  }

  private ensureHandle(accountId: string, force: boolean): Promise<Handle> {
    const existing = this.handles.get(accountId);
    const instance = this.deps.agent.instanceId();
    if (
      !force &&
      existing &&
      instance !== null &&
      existing.instanceId === instance &&
      existing.retentionExpiresAt - this.now() > RENEW_MARGIN_MS
    ) {
      return Promise.resolve(existing);
    }
    const pending = this.renewals.get(accountId);
    if (pending) return pending;
    const renewal = this.delegate(accountId).finally(() => this.renewals.delete(accountId));
    this.renewals.set(accountId, renewal);
    return renewal;
  }

  private async delegate(accountId: string): Promise<Handle> {
    if (this.deps.agent.instanceId() === null) throw new DelegationError('agent-unavailable');
    const credential = await this.deps.issueCredential(accountId);
    let response: AgentPostResponse & { instanceId: string };
    try {
      response = await this.deps.agent.post(
        '/local/v1/credentials',
        {
          provider: credential.provider,
          accountId,
          accessToken: credential.accessToken,
          providerExpiresAt: credential.accessTokenExpiresAt,
          ...(credential.rootNamespaceId ? { rootNamespaceId: credential.rootNamespaceId } : {})
        },
        10_000
      );
    } catch {
      throw new DelegationError('agent-unavailable');
    }
    if (response.status !== 201) throw new DelegationError(readError(errorCode(response)));
    const parsed = safeJson(response.body);
    const handle = parsed['handle'];
    const retention = Date.parse(String(parsed['retentionExpiresAt']));
    if (typeof handle !== 'string' || !/^hdh_[A-Za-z0-9_-]{20,64}$/.test(handle) || Number.isNaN(retention)) {
      throw new DelegationError('unknown');
    }
    const record: Handle = { handle, provider: credential.provider, instanceId: response.instanceId, retentionExpiresAt: retention };
    this.handles.set(accountId, record);
    return record;
  }

  private digest(
    response: AgentPostResponse,
    provider: StorageProvider,
    file: StorageFile,
    offset: number,
    length: number,
    elapsedMs: number
  ): ReadDiagnostic {
    const bytes = response.body;
    try {
      const declared = Number(response.headers['x-hyperdrive-read-length']);
      const size = file.sizeBytes ?? 0;
      const expected = offset >= size ? 0 : Math.min(length, size - offset);
      if (bytes.byteLength > MAX_READ_BYTES || bytes.byteLength !== declared || bytes.byteLength !== expected) {
        return fail('unknown');
      }
      return {
        ok: true,
        provider,
        offset,
        bytesRequested: length,
        bytesReceived: bytes.byteLength,
        eof: response.headers['x-hyperdrive-eof'] === 'true',
        elapsedMs,
        sha256: createHash('sha256').update(bytes).digest('hex'),
        revisionPinned: response.headers['x-hyperdrive-revision-pinned'] === 'true'
      };
    } finally {
      bytes.fill(0); // diagnostic bytes are not kept anywhere
    }
  }
}

class DelegationError extends Error {
  constructor(readonly code: ReadError) {
    super(code);
  }
}

function fail(error: ReadError): ReadDiagnostic {
  return { ok: false, error };
}

function safeJson(bytes: Uint8Array): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(DECODER.decode(bytes));
    return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function errorCode(response: AgentPostResponse): string {
  const code = safeJson(response.body)['code'];
  return typeof code === 'string' && /^[a-z_]{1,64}$/.test(code) ? code : 'unknown';
}

function readError(code: string): ReadError {
  switch (code) {
    case 'not_found':
      return 'not-found';
    case 'forbidden':
      return 'forbidden';
    case 'unsupported_file':
      return 'unsupported-file';
    case 'file_changed':
      return 'file-changed';
    case 'rate_limited':
      return 'rate-limited';
    case 'unavailable':
    case 'timeout':
    case 'redirect_refused':
    case 'invalid_provider_response':
    case 'truncated_response':
    case 'provider_error':
      return 'provider-unavailable';
    case 'range_not_supported':
      return 'range-not-supported';
    case 'credential_rejected':
    case 'credential_expired':
    case 'unknown_credential':
      return 'credential-rejected';
    case 'permission_missing':
      return 'content-permission-missing';
    case 'too_busy':
    case 'too_many_credentials':
      return 'rate-limited';
    case 'cancelled':
      return 'cancelled';
    case 'invalid_request':
    case 'invalid_range':
      return 'invalid-request';
    default:
      return 'unknown';
  }
}

function backendError(error: unknown): ReadError {
  if (error instanceof SessionExpiredError) return 'session-expired';
  if (error instanceof BackendUnreachableError) return 'backend-unreachable';
  if (error instanceof ApiError) {
    if (error.code === 'content_permission_missing') return 'content-permission-missing';
    if (error.code === 'account_needs_reconnect') return 'needs-reconnect';
    if (error.status === 404) return 'not-found';
    if (error.status === 401) return 'session-expired';
    if (error.status === 429) return 'rate-limited';
    if (error.status >= 500) return 'provider-unavailable';
  }
  return 'unknown';
}
