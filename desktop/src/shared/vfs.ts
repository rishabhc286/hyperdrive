/**
 * Virtual drive (Batch 8): read-only, metadata-only WinFsp drive. Shared between main, preload and
 * renderer. Nothing here carries a capability, grant, port or path other than the drive letter.
 */

/** What the Virtual Drive card shows. "mounted" is only reported after the VFS confirmed it. */
export type VirtualDriveState =
  | 'prerequisite-missing'
  | 'signed-out'
  | 'no-accounts'
  | 'ready'
  | 'mounting'
  | 'mounted'
  | 'unmounting'
  | 'failed';

/** Progress reported by the native process while mounting (in order). */
export type VirtualDrivePhase = 'process-started' | 'agent-authenticated' | 'volume-created' | 'mounted';

export type VirtualDrivePrerequisite = 'unsupported-platform' | 'winfsp-missing' | 'vfs-missing' | 'agent-unavailable';

export type VirtualDriveError =
  | 'drive-letter-in-use'
  | 'invalid-drive-letter'
  | 'startup-timeout'
  | 'invalid-handshake'
  | 'vfs-exited'
  | 'authorization-lost'
  | 'agent-unavailable'
  | 'backend-unreachable'
  | 'session-expired'
  | 'mount-failed'
  | 'winfsp-runtime-missing'
  | 'unknown';

export interface VirtualDriveStatus {
  readonly state: VirtualDriveState;
  /** Configured letter, "D".."Z". */
  readonly driveLetter: string;
  readonly phase: VirtualDrivePhase | null;
  readonly error: VirtualDriveError | null;
  readonly prerequisite: VirtualDrivePrerequisite | null;
}

export type VirtualDriveResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly error: VirtualDriveError };

export interface VirtualDriveApi {
  getStatus(): Promise<VirtualDriveStatus>;
  mount(): Promise<VirtualDriveStatus>;
  unmount(): Promise<VirtualDriveStatus>;
  /** Opens the mounted drive's root in Explorer; refused unless mounted. */
  openInExplorer(): Promise<VirtualDriveResult<null>>;
  /** Only while not mounted. */
  setDriveLetter(input: { letter: string }): Promise<VirtualDriveResult<VirtualDriveStatus>>;
  onChange(listener: (status: VirtualDriveStatus) => void): () => void;
}

export const VirtualDriveIpcChannel = {
  getStatus: 'hyperdrive:vfs:get-status',
  mount: 'hyperdrive:vfs:mount',
  unmount: 'hyperdrive:vfs:unmount',
  openInExplorer: 'hyperdrive:vfs:open-in-explorer',
  setDriveLetter: 'hyperdrive:vfs:set-drive-letter',
  changed: 'hyperdrive:vfs:changed'
} as const;

export const DEFAULT_DRIVE_LETTER = 'H';

/** D..Z (A/B legacy floppy, C system). Uppercase single letter only. */
export function isAllowedDriveLetter(value: unknown): value is string {
  return typeof value === 'string' && /^[D-Z]$/.test(value);
}

export const METADATA_ONLY_NOTICE =
  'Folders and file details are available. Reading cloud file contents through this drive will be added in the next batch.';
