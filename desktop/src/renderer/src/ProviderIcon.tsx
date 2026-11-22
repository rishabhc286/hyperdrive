import type { StorageProvider } from '../../shared/storage';

/**
 * Small provider marks drawn locally (no remote assets, no third-party logo files): a stacked
 * triangle glyph for Google Drive and an open-box glyph for Dropbox. Decorative only; the provider
 * name is always shown as text next to it.
 */
export function ProviderIcon({ provider }: { provider: StorageProvider }) {
  if (provider === 'DROPBOX') {
    return (
      <svg className="provider-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
        <path d="M4 8l4-3 4 3-4 3zM12 8l4-3 4 3-4 3zM4 14l4-3 4 3-4 3zM12 14l4-3 4 3-4 3z" fill="#4c8dff" />
        <path d="M8 18.2l4 2.8 4-2.8" stroke="#4c8dff" strokeWidth="1.6" fill="none" />
      </svg>
    );
  }
  return (
    <svg className="provider-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
      <path d="M9 3h6l7 12-3 5H5l-3-5z" fill="none" stroke="#4cc38a" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M9 3l-7 12M15 3l-7 12M5 20l4-5h13" fill="none" stroke="#e5b450" strokeWidth="1.6" />
    </svg>
  );
}
