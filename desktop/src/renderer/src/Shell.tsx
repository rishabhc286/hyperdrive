import { useState } from 'react';
import type { SafeUser } from '../../shared/auth';
import type { StorageApi } from '../../shared/storage';
import { BackendIndicator, BrandMark } from './BackendIndicator';
import { SECTIONS, SectionView, type SectionId } from './sections';
import type { BackendStatus } from './useBackendHealth';
import { useConnectedAccounts, useGoogleConnection } from './useStorage';

export interface ShellProps {
  readonly user: SafeUser;
  readonly backendStatus: BackendStatus;
  readonly onRecheckBackend: () => void;
  readonly onSignOut: () => void;
  readonly storage: StorageApi;
  /** Test hook: connection status polling interval. */
  readonly pollIntervalMs?: number | undefined;
}

/** The signed-in application layout. */
export function Shell({ user, backendStatus, onRecheckBackend, onSignOut, storage, pollIntervalMs }: ShellProps) {
  const [active, setActive] = useState<SectionId>('overview');
  // Owned here so a pending connection keeps polling while the user switches sections.
  const accounts = useConnectedAccounts(storage);
  const connection = useGoogleConnection(storage, () => void accounts.reload(), pollIntervalMs);

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
          <BackendIndicator status={backendStatus} />
          <span className="account" aria-label="Signed in as">
            @{user.username}
          </span>
          <button type="button" className="button button-secondary button-small" onClick={onSignOut}>
            Sign out
          </button>
        </header>
        <main className="content" id="content">
          <SectionView
            section={active}
            user={user}
            backendStatus={backendStatus}
            onRecheckBackend={onRecheckBackend}
            onNavigate={setActive}
            storage={storage}
            accounts={accounts.state}
            onReloadAccounts={() => void accounts.reload()}
            connection={connection.flow}
            onConnect={() => void connection.start()}
            onCancelConnection={() => void connection.cancel()}
            onDismissConnection={connection.dismiss}
          />
        </main>
      </div>
    </div>
  );
}
