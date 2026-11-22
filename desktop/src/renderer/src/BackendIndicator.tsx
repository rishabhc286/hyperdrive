import type { BackendStatus } from './useBackendHealth';

export function backendStatusLabel(status: BackendStatus): string {
  switch (status.state) {
    case 'checking':
      return 'Backend: checking';
    case 'connected':
      return 'Backend: connected';
    case 'unavailable':
      return 'Backend: unavailable';
  }
}

export function BackendIndicator({ status }: { status: BackendStatus }) {
  return (
    <span className={`status status-${status.state}`} role="status" aria-live="polite">
      <span className="status-dot" aria-hidden="true" />
      {backendStatusLabel(status)}
    </span>
  );
}

export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
      <rect x="2" y="5" width="20" height="14" rx="3" fill="none" stroke="currentColor" strokeWidth="2" />
      <path d="M6 15h6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <circle cx="17" cy="15" r="1.5" fill="currentColor" />
    </svg>
  );
}
