import type { ConnectedAccount, StorageError, StorageFile } from '../../shared/storage';

const GOOGLE_NATIVE: Record<string, string> = {
  'application/vnd.google-apps.document': 'Google Docs',
  'application/vnd.google-apps.spreadsheet': 'Google Sheets',
  'application/vnd.google-apps.presentation': 'Google Slides',
  'application/vnd.google-apps.form': 'Google Forms',
  'application/vnd.google-apps.drawing': 'Google Drawings',
  'application/vnd.google-apps.jam': 'Google Jamboard',
  'application/vnd.google-apps.site': 'Google Sites',
  'application/vnd.google-apps.map': 'Google My Maps',
  'application/vnd.google-apps.script': 'Apps Script'
};

const COMMON: Record<string, string> = {
  'application/pdf': 'PDF',
  'application/zip': 'ZIP archive',
  'text/plain': 'Text',
  'text/csv': 'CSV',
  'application/json': 'JSON'
};

/** Human type label. Never claims a native Google document is an ordinary file. */
export function kindLabel(file: StorageFile): string {
  switch (file.kind) {
    case 'FOLDER':
      return 'Folder';
    case 'SHORTCUT':
      return file.shortcutTargetMimeType === 'application/vnd.google-apps.folder' ? 'Shortcut to folder' : 'Shortcut';
    case 'PROVIDER_DOCUMENT':
      return (file.mimeType && GOOGLE_NATIVE[file.mimeType]) ?? 'Google Workspace item';
    case 'FILE': {
      const mime = file.mimeType ?? '';
      if (COMMON[mime]) return COMMON[mime];
      if (mime.startsWith('image/')) return 'Image';
      if (mime.startsWith('video/')) return 'Video';
      if (mime.startsWith('audio/')) return 'Audio';
      if (mime.startsWith('text/')) return 'Text';
      return 'File';
    }
  }
}

const UNITS = ['KB', 'MB', 'GB', 'TB'];

/** Binary units (1 KB = 1024 bytes); null means the size is unknown or not meaningful. */
export function formatSize(bytes: number | null): string {
  if (bytes === null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${UNITS[unit]}`;
}

export function formatDate(iso: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

export function accountLabel(account: ConnectedAccount): string {
  return account.email ?? account.displayName ?? 'Google Drive account';
}

export function storageErrorMessage(error: StorageError): string {
  switch (error) {
    case 'session-expired':
      return 'Your HyperDrive session has expired. Sign in again.';
    case 'backend-unreachable':
      return 'The HyperDrive backend could not be reached. Check that it is running and try again.';
    case 'not-found':
      return 'This item no longer exists or is not accessible.';
    case 'needs-reconnect':
      return 'This Google Drive account needs to be connected again.';
    case 'rate-limited':
      return 'Google Drive is limiting requests right now. Wait a moment and try again.';
    case 'provider-unavailable':
      return 'Google Drive could not be reached or returned an unexpected response. Try again.';
    case 'permission-denied':
      return 'Google Drive denied access to this item.';
    case 'invalid-request':
      return 'HyperDrive sent a request the backend did not accept. Refresh and try again.';
    case 'cancelled':
      return 'The request was cancelled.';
    case 'unknown':
      return 'Something went wrong. Try again.';
  }
}

/** Messages for the transaction's errorCode after a failed connection attempt. */
export function connectionFailureMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'access_denied':
      return 'Access wasn’t allowed in the browser, so Google Drive was not connected.';
    case 'drive_scope_not_granted':
      return 'Google Drive access wasn’t granted. Try again and keep the permission to see your Drive files selected.';
    case 'browser_mismatch':
      return 'The connection was finished in a different browser than the one HyperDrive opened. Try again.';
    case 'identity_invalid':
    case 'token_exchange_failed':
      return 'Google’s response couldn’t be verified, so nothing was connected. Try again.';
    case 'drive_unavailable':
      return 'Google Drive couldn’t be reached while connecting. Try again.';
    default:
      return 'Google Drive was not connected. Try again.';
  }
}
