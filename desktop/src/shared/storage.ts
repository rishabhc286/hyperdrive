import type { CacheBypassReason, CacheNotAdmittedReason, CacheOutcome } from './cache';
/**
 * Cloud-storage types shared by main, preload and renderer. Nothing here carries a provider or
 * HyperDrive token: the renderer only sees account metadata, connection status and file metadata.
 */

export type StorageProvider = 'GOOGLE_DRIVE' | 'DROPBOX';
export const STORAGE_PROVIDERS: readonly StorageProvider[] = ['GOOGLE_DRIVE', 'DROPBOX'];
export type AccountStatus = 'CONNECTED' | 'NEEDS_RECONNECT';

export interface ConnectedAccount {
  readonly id: string;
  readonly provider: StorageProvider;
  readonly displayName: string | null;
  /** Only present when the provider reported it as verified. */
  readonly email: string | null;
  readonly status: AccountStatus;
  /** Dropbox only: USER (personal/member root) or TEAM (team space root). */
  readonly rootType: 'USER' | 'TEAM' | null;
  readonly connectedAt: string;
  readonly lastSuccessAt: string | null;
  readonly lastErrorCode: string | null;
  /** Whether the stored grant allows reading file contents (Dropbox accounts from before Batch 6: false). */
  readonly contentReadGranted: boolean;
}

export interface ProviderAvailability {
  readonly provider: StorageProvider;
  /** False when this HyperDrive server has no app credentials for the provider. */
  readonly available: boolean;
}

export type FileKind = 'FILE' | 'FOLDER' | 'SHORTCUT' | 'PROVIDER_DOCUMENT';

export interface StorageFile {
  /** Stable, opaque provider id (Drive file id, Dropbox "id:..."). Never a path. */
  readonly providerFileId: string;
  readonly name: string;
  readonly kind: FileKind;
  /** Null when the provider has none (Dropbox) — never guessed. */
  readonly mimeType: string | null;
  /** Null when unknown or not meaningful (folders, links, native provider documents). */
  readonly sizeBytes: number | null;
  readonly modifiedAt: string | null;
  readonly canDownload: boolean;
  /** Folders only: what to send as `directory` to list this folder (distinct from the id). */
  readonly directoryLocator: string | null;
  readonly providerRevision: string | null;
  readonly contentHash: string | null;
  readonly contentHashAlgorithm: string | null;
  readonly shortcutTargetId: string | null;
  readonly shortcutTargetMimeType: string | null;
}

export interface FilePage {
  readonly items: readonly StorageFile[];
  /** Opaque, account-bound HyperDrive continuation; null when there are no more items. */
  readonly continuation: string | null;
}

export type ConnectionState = 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';

export interface ConnectionStarted {
  readonly transactionId: string;
  readonly expiresAt: string;
}

export interface ConnectionStatus {
  readonly transactionId: string;
  readonly provider: StorageProvider;
  readonly status: ConnectionState;
  readonly browserOpened: boolean;
  readonly expiresAt: string;
  readonly accountId: string | null;
  readonly reconnected: boolean | null;
  /** Stable code such as access_denied or scope_not_granted; never free text. */
  readonly errorCode: string | null;
}

export type StorageError =
  | 'session-expired'
  | 'backend-unreachable'
  | 'not-found'
  | 'needs-reconnect'
  | 'rate-limited'
  | 'provider-unavailable'
  | 'permission-denied'
  | 'invalid-request'
  | 'not-configured'
  | 'continuation-expired'
  | 'cancelled'
  | 'revocation-unsupported'
  | 'unknown';

/** Outcome of the provider-side revocation attempt in "Revoke provider access and disconnect". */
export type RevocationOutcome = 'confirmed' | 'failed' | 'unknown';

export interface RevokeResult {
  readonly revocation: RevocationOutcome;
  /** Fixed machine code from the backend (never provider text). */
  readonly reason: string;
  /** The local link and stored credentials were removed. */
  readonly disconnected: boolean;
}

export type StorageResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: StorageError };

export interface ListFilesRequest {
  readonly accountId: string;
  /** A folder's `directoryLocator`; omitted or null for the account's root. */
  readonly directory?: string | null;
  /** The `continuation` from the previous page of the same account and directory. */
  readonly continuation?: string | null;
}

/** Largest single diagnostic read: one future cache chunk, 4 MiB = 4,194,304 bytes. */
export const MAX_READ_BYTES = 4 * 1024 * 1024;

export interface VerifyReadRequest {
  readonly accountId: string;
  readonly fileId: string;
  readonly offset: number;
  readonly length: number;
}

export type ReadError =
  | 'unsupported-file'
  | 'content-permission-missing'
  | 'needs-reconnect'
  | 'agent-unavailable'
  | 'not-found'
  | 'forbidden'
  | 'file-changed'
  | 'rate-limited'
  | 'provider-unavailable'
  | 'range-not-supported'
  | 'credential-rejected'
  | 'invalid-request'
  | 'session-expired'
  | 'backend-unreachable'
  | 'cancelled'
  | 'unknown';

/**
 * Safe diagnostic facts about one real provider read through the agent. Never the bytes: the main
 * process hashes them and discards them.
 */
export type ReadDiagnostic =
  | {
      readonly ok: true;
      readonly provider: StorageProvider;
      readonly offset: number;
      readonly bytesRequested: number;
      readonly bytesReceived: number;
      readonly eof: boolean;
      readonly elapsedMs: number;
      /** SHA-256 of the received bytes, hex. */
      readonly sha256: string;
      /** True only for Dropbox reads pinned to the listed revision. */
      readonly revisionPinned: boolean;
      /** What the local chunk cache did for this read (Batch 7). */
      readonly cache: CacheOutcome;
      readonly cacheBypassReason: CacheBypassReason | null;
      readonly cacheNotAdmittedReason: CacheNotAdmittedReason | null;
      /** Bytes the agent actually received from the provider (whole 4 MiB chunks on cached misses). */
      readonly providerBytesFetched: number;
      /** Requested bytes answered from encrypted cached chunks. */
      readonly bytesFromCache: number;
    }
  | { readonly ok: false; readonly error: ReadError };

export interface StorageApi {
  listAccounts(): Promise<StorageResult<readonly ConnectedAccount[]>>;
  listProviders(): Promise<StorageResult<readonly ProviderAvailability[]>>;
  /** Opens the system browser for the provider's consent; poll getConnectionStatus afterwards. */
  beginConnection(input: { provider: StorageProvider }): Promise<StorageResult<ConnectionStarted>>;
  getConnectionStatus(input: { transactionId: string }): Promise<StorageResult<ConnectionStatus>>;
  /** Server-side cancellation: a later provider callback for this attempt is rejected. */
  cancelConnection(input: { transactionId: string }): Promise<StorageResult<ConnectionStatus>>;
  listFiles(input: ListFilesRequest): Promise<StorageResult<FilePage>>;
  getFileMetadata(input: { accountId: string; fileId: string }): Promise<StorageResult<StorageFile>>;
  /** Removes HyperDrive's link and stored credentials. Does not delete files or sign out. */
  disconnectAccount(input: { accountId: string }): Promise<StorageResult<null>>;
  /**
   * Separate, explicitly confirmed action (Dropbox only): ask the provider to revoke this
   * connection's grant, then disconnect locally whatever the provider answered.
   */
  revokeAndDisconnect(input: { accountId: string }): Promise<StorageResult<RevokeResult>>;
  /** Development diagnostic: one bounded real read of a listed file through the local agent. */
  verifyFileRead(input: VerifyReadRequest): Promise<ReadDiagnostic>;
}

export const StorageIpcChannel = {
  listAccounts: 'hyperdrive:storage:list-accounts',
  listProviders: 'hyperdrive:storage:list-providers',
  beginConnection: 'hyperdrive:storage:begin-connection',
  getConnectionStatus: 'hyperdrive:storage:get-connection-status',
  cancelConnection: 'hyperdrive:storage:cancel-connection',
  listFiles: 'hyperdrive:storage:list-files',
  getFileMetadata: 'hyperdrive:storage:get-file-metadata',
  disconnectAccount: 'hyperdrive:storage:disconnect-account',
  revokeAndDisconnect: 'hyperdrive:storage:revoke-and-disconnect',
  verifyFileRead: 'hyperdrive:storage:verify-file-read'
} as const;

/** Stable provider file ids: Drive's id alphabet, or Dropbox "id:" + that alphabet. */
export const PROVIDER_FILE_ID = /^(id:)?[A-Za-z0-9_-]{1,256}$/;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function isStorageProvider(value: unknown): value is StorageProvider {
  return typeof value === 'string' && (STORAGE_PROVIDERS as readonly string[]).includes(value);
}

/** Directory locators are provider-specific (id or path); bounded, no control characters. */
export function isValidDirectory(value: string): boolean {
  // eslint-disable-next-line no-control-regex
  return value.length > 0 && value.length <= 4096 && !/[\u0000-\u001f\u007f]/.test(value);
}

/** Opaque continuation: printable ASCII without spaces, bounded. */
export function isValidContinuation(value: string): boolean {
  return value.length > 0 && value.length <= 8192 && /^[\x21-\x7e]+$/.test(value);
}
