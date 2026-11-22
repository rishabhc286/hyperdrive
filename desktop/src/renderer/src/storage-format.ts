import type { ConnectedAccount, StorageError, StorageFile, StorageProvider } from '../../shared/storage';

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

export function providerName(provider: StorageProvider): string {
  return provider === 'DROPBOX' ? 'Dropbox' : 'Google Drive';
}

/**
 * Human type label. Never claims a native document is an ordinary file. When the provider has no
 * MIME type (Dropbox), the label comes from the file name's extension only — no MIME type is
 * invented or reported.
 */
export function kindLabel(file: StorageFile): string {
  switch (file.kind) {
    case 'FOLDER':
      return 'Folder';
    case 'SHORTCUT':
      if (file.mimeType === null) return 'Link';
      return file.shortcutTargetMimeType === 'application/vnd.google-apps.folder' ? 'Shortcut to folder' : 'Shortcut';
    case 'PROVIDER_DOCUMENT':
      if (file.mimeType === null) return 'Cloud document';
      return GOOGLE_NATIVE[file.mimeType] ?? 'Google Workspace item';
    case 'FILE': {
      const mime = file.mimeType;
      if (mime === null) {
        const ext = /\.([A-Za-z0-9]{1,8})$/.exec(file.name)?.[1];
        return ext ? `${ext.toUpperCase()} file` : 'File';
      }
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

/** "someone@example.com" (or name), falling back to the provider name. */
export function accountLabel(account: ConnectedAccount): string {
  return account.email ?? account.displayName ?? `${providerName(account.provider)} account`;
}

/** "Dropbox — someone@example.com" for navigation where several providers appear together. */
export function accountTitle(account: ConnectedAccount): string {
  return `${providerName(account.provider)} — ${accountLabel(account)}`;
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
      return 'This storage account needs to be connected again.';
    case 'rate-limited':
      return 'The storage provider is limiting requests right now. Wait a moment and try again.';
    case 'provider-unavailable':
      return 'The storage provider could not be reached or returned an unexpected response. Try again.';
    case 'permission-denied':
      return 'The storage provider denied access to this item.';
    case 'invalid-request':
      return 'HyperDrive sent a request the backend did not accept. Refresh and try again.';
    case 'not-configured':
      return 'This provider is not configured on your HyperDrive server. See docs/dropbox.md.';
    case 'continuation-expired':
      return 'This folder changed or the listing expired. Refresh to load it again.';
    case 'cancelled':
      return 'The request was cancelled.';
    case 'unknown':
      return 'Something went wrong. Try again.';
  }
}

/** Messages for the transaction's errorCode after a failed connection attempt. */
export function connectionFailureMessage(provider: StorageProvider, errorCode: string | null): string {
  const name = providerName(provider);
  switch (errorCode) {
    case 'access_denied':
      return `Access wasn’t allowed in the browser, so ${name} was not connected.`;
    case 'drive_scope_not_granted':
      return 'Google Drive access wasn’t granted. Try again and keep the permission to see your Drive files selected.';
    case 'scope_not_granted':
      return `${name} didn’t grant the read permissions HyperDrive needs. Check the app’s permissions and try again.`;
    case 'unsupported_account':
      return `This ${name} account type isn’t supported by HyperDrive yet.`;
    case 'browser_mismatch':
      return 'The connection was finished in a different browser than the one HyperDrive opened. Try again.';
    case 'identity_invalid':
    case 'token_exchange_failed':
      return `${name}’s response couldn’t be verified, so nothing was connected. Try again.`;
    case 'drive_unavailable':
    case 'provider_unavailable':
      return `${name} couldn’t be reached while connecting. Try again.`;
    default:
      return `${name} was not connected. Try again.`;
  }
}
