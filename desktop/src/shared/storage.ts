/**
 * Cloud-storage types shared by main, preload and renderer. Nothing here carries a provider or
 * HyperDrive token: the renderer only sees account metadata, connection status and file metadata.
 */

export type StorageProvider = 'GOOGLE_DRIVE';
export type AccountStatus = 'CONNECTED' | 'NEEDS_RECONNECT';

export interface ConnectedAccount {
  readonly id: string;
  readonly provider: StorageProvider;
  readonly displayName: string | null;
  /** Only present when the provider reported it as verified. */
  readonly email: string | null;
  readonly status: AccountStatus;
  readonly connectedAt: string;
  readonly lastSuccessAt: string | null;
  readonly lastErrorCode: string | null;
}

export type FileKind = 'FILE' | 'FOLDER' | 'SHORTCUT' | 'PROVIDER_DOCUMENT';

export interface StorageFile {
  readonly providerFileId: string;
  readonly name: string;
  readonly kind: FileKind;
  readonly mimeType: string | null;
  /** Null when unknown or not meaningful (folders, shortcuts, native provider documents). */
  readonly sizeBytes: number | null;
  readonly modifiedAt: string | null;
  readonly canDownload: boolean;
  readonly shortcutTargetId: string | null;
  readonly shortcutTargetMimeType: string | null;
}

export interface FilePage {
  readonly items: readonly StorageFile[];
  readonly nextPageToken: string | null;
}

export type ConnectionState = 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';

export interface ConnectionStarted {
  readonly transactionId: string;
  readonly expiresAt: string;
}

export interface ConnectionStatus {
  readonly transactionId: string;
  readonly status: ConnectionState;
  readonly browserOpened: boolean;
  readonly expiresAt: string;
  readonly accountId: string | null;
  readonly reconnected: boolean | null;
  /** Stable code such as access_denied or drive_scope_not_granted; never free text. */
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
  | 'cancelled'
  | 'unknown';

export type StorageResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: StorageError };

export interface ListFilesRequest {
  readonly accountId: string;
  /** Omitted or null for the account's root folder. */
  readonly parentId?: string | null;
  readonly pageToken?: string | null;
}

export interface StorageApi {
  listAccounts(): Promise<StorageResult<readonly ConnectedAccount[]>>;
  /** Opens the system browser for Google Drive consent; poll getConnectionStatus afterwards. */
  beginGoogleConnection(): Promise<StorageResult<ConnectionStarted>>;
  getConnectionStatus(input: { transactionId: string }): Promise<StorageResult<ConnectionStatus>>;
  /** Server-side cancellation: a later Google callback for this attempt is rejected. */
  cancelConnection(input: { transactionId: string }): Promise<StorageResult<ConnectionStatus>>;
  listFiles(input: ListFilesRequest): Promise<StorageResult<FilePage>>;
  getFileMetadata(input: { accountId: string; fileId: string }): Promise<StorageResult<StorageFile>>;
  /** Removes HyperDrive's link and stored credentials. Does not delete files or sign out. */
  disconnectAccount(input: { accountId: string }): Promise<StorageResult<null>>;
}

export const StorageIpcChannel = {
  listAccounts: 'hyperdrive:storage:list-accounts',
  beginGoogleConnection: 'hyperdrive:storage:begin-google-connection',
  getConnectionStatus: 'hyperdrive:storage:get-connection-status',
  cancelConnection: 'hyperdrive:storage:cancel-connection',
  listFiles: 'hyperdrive:storage:list-files',
  getFileMetadata: 'hyperdrive:storage:get-file-metadata',
  disconnectAccount: 'hyperdrive:storage:disconnect-account'
} as const;

/** Provider file ids: Drive's id alphabet only (no quotes, slashes or spaces). */
export const PROVIDER_FILE_ID = /^[A-Za-z0-9_-]{1,256}$/;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Opaque provider cursor: printable ASCII without spaces, bounded. */
export function isValidPageToken(value: string): boolean {
  return value.length > 0 && value.length <= 2048 && /^[\x21-\x7e]+$/.test(value);
}
