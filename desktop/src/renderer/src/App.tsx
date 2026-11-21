import { useState } from 'react';
import type { HyperdriveApi } from '../../shared/backend-health';
import { useBackendHealth, type BackendStatus } from './useBackendHealth';
import { SECTIONS, SectionView, type SectionId } from './sections';

export interface AppProps {
  /** Preload bridge. Injected so tests can supply a mock. */
  readonly api: HyperdriveApi | undefined;
}

export function App({ api }: AppProps) {
  const [active, setActive] = useState<SectionId>('overview');
  const { status, recheck } = useBackendHealth(api);

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <BrandMark />
          <span className="brand-name">HyperDrive</span>
        </div>
        <nav aria-label="Main">
          <ul className="nav-list">
            {SECTIONS.map((section) => (
              <li key={section.id}>
                <button
                  type="button"
                  className="nav-item"
                  aria-current={active === section.id ? 'page' : undefined}
                  onClick={() => setActive(section.id)}
                >
                  {section.label}
                </button>
              </li>
            ))}
          </ul>
        </nav>
      </aside>

      <div className="main">
        <header className="topbar">
          <span className="badge badge-dev">Development build</span>
          <BackendIndicator status={status} />
        </header>
        <main className="content" id="content">
          <SectionView
            section={active}
            backendStatus={status}
            onRecheckBackend={recheck}
            onNavigate={setActive}
          />
        </main>
      </div>
    </div>
  );
}

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

function BackendIndicator({ status }: { status: BackendStatus }) {
  return (
    <span className={`status status-${status.state}`} role="status" aria-live="polite">
      <span className="status-dot" aria-hidden="true" />
      {backendStatusLabel(status)}
    </span>
  );
}

function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
      <rect x="2" y="5" width="20" height="14" rx="3" fill="none" stroke="currentColor" strokeWidth="2" />
      <path d="M6 15h6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <circle cx="17" cy="15" r="1.5" fill="currentColor" />
    </svg>
  );
}
