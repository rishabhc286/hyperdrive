import type { CacheApi, CacheStatusView } from '../src/shared/cache';
import type { VirtualDriveApi, VirtualDriveStatus } from '../src/shared/vfs';
import { act } from '@testing-library/react';
import { vi } from 'vitest';
import type { AuthApi, AuthStatus, SafeUser } from '../src/shared/auth';
import type { BackendHealth, HyperdriveApi } from '../src/shared/backend-health';
import type { StorageApi } from '../src/shared/storage';
import type { AgentApi, AgentStatus } from '../src/shared/agent';

export const ONBOARDED_USER: SafeUser = {
  id: '11111111-1111-1111-1111-111111111111',
  username: 'Test_User',
  displayName: 'Test User',
  onboardingComplete: true
};

export const NEW_USER: SafeUser = { ...ONBOARDED_USER, username: null, onboardingComplete: false };

export function status(phase: AuthStatus['phase'], extra: Partial<AuthStatus> = {}): AuthStatus {
  return { phase, user: null, sessionPersistence: 'encrypted', ...extra };
}

/** A fake preload bridge. `push` simulates a status event from the main process. */
export function createMockApi(initial: AuthStatus, health: BackendHealth | 'pending' = 'pending') {
  let current = initial;
  let listener: ((s: AuthStatus) => void) | null = null;

  const auth = {
    getStatus: vi.fn<AuthApi['getStatus']>(() => Promise.resolve(current)),
    signInWithGoogle: vi.fn<AuthApi['signInWithGoogle']>(() => Promise.resolve()),
    cancelSignIn: vi.fn<AuthApi['cancelSignIn']>(() => Promise.resolve()),
    completeOnboarding: vi.fn<AuthApi['completeOnboarding']>(() => Promise.resolve({ ok: true })),
    signOut: vi.fn<AuthApi['signOut']>(() => Promise.resolve({ serverRevocationConfirmed: true })),
    retry: vi.fn<AuthApi['retry']>(() => Promise.resolve()),
    onStatusChange: vi.fn<AuthApi['onStatusChange']>((l) => {
      listener = l;
      return () => {
        listener = null;
      };
    })
  };

  const storage = createMockStorage();
  const agent = createMockAgent();
  const cache = createMockCache();
  const vfs = createMockVfs();

  const api: HyperdriveApi = {
    checkBackendHealth: vi.fn(() =>
      health === 'pending' ? new Promise<BackendHealth>(() => {}) : Promise.resolve(health)
    ),
    auth,
    storage,
    agent: agent.api,
    cache,
    vfs: vfs.api
  };

  return {
    api,
    auth,
    storage,
    agent,
    cache,
    vfs,
    push(next: AuthStatus) {
      current = next;
      act(() => listener?.(next));
    }
  };
}

/** Fake storage bridge; every method resolves with an empty success unless a test overrides it. */
export const CACHE_READY: CacheStatusView = {
  enabled: true,
  disabledReason: null,
  keyProtection: 'available',
  budgetBytes: 5 * 1024 * 1024 * 1024,
  encryptedBytesOnDisk: 8 * 1024 * 1024 + 64,
  tempBytes: 0,
  entryCount: 2,
  overBudgetBytes: 0,
  counters: {
    hits: 3,
    misses: 2,
    notAdmitted: 0,
    evictions: 1,
    corruptions: 0,
    bytesRequested: 5000,
    providerBytesFetched: 8388608,
    bytesServedFromCache: 3000
  },
  bypasses: { provider_not_version_bound: 4 },
  lastCleanup: null
};

export const VFS_READY: VirtualDriveStatus = {
  state: 'ready',
  driveLetter: 'H',
  phase: null,
  error: null,
  prerequisite: null,
  activity: null
};

/** Fake virtual-drive bridge; `emit` simulates a main-process status push. */
export function createMockVfs(initial: VirtualDriveStatus = VFS_READY) {
  let listener: ((s: VirtualDriveStatus) => void) | null = null;
  let current = initial;
  const api = {
    getStatus: vi.fn<VirtualDriveApi['getStatus']>(() => Promise.resolve(current)),
    mount: vi.fn<VirtualDriveApi['mount']>(() =>
      Promise.resolve(
        (current = {
          ...current,
          state: 'mounted',
          phase: 'mounted',
          activity: { openFiles: 0, reads: 0, bytesReturned: 0, providerBytes: 0, cacheBytes: 0, lastIssue: null }
        })
      )
    ),
    unmount: vi.fn<VirtualDriveApi['unmount']>(() => Promise.resolve((current = { ...current, state: 'ready', phase: null, activity: null }))),
    openInExplorer: vi.fn<VirtualDriveApi['openInExplorer']>(() => Promise.resolve({ ok: true, value: null })),
    setDriveLetter: vi.fn<VirtualDriveApi['setDriveLetter']>((input) =>
      Promise.resolve({ ok: true, value: (current = { ...current, driveLetter: input.letter }) })
    ),
    onChange: vi.fn<VirtualDriveApi['onChange']>((l) => {
      listener = l;
      return () => {
        listener = null;
      };
    })
  };
  return {
    api,
    set(next: VirtualDriveStatus) {
      current = next;
    },
    emit(next: VirtualDriveStatus) {
      current = next;
      listener?.(next);
    }
  };
}

export function createMockCache() {
  return {
    getStatus: vi.fn<CacheApi['getStatus']>(() => Promise.resolve({ ok: true, value: CACHE_READY })),
    setBudget: vi.fn<CacheApi['setBudget']>((input) =>
      Promise.resolve({ ok: true, value: { ...CACHE_READY, budgetBytes: input.budgetBytes } })
    ),
    clear: vi.fn<CacheApi['clear']>(() => Promise.resolve({ ok: true, value: { removed: 2, failed: 0 } }))
  };
}

export function createMockStorage() {
  return {
    listAccounts: vi.fn<StorageApi['listAccounts']>(() => Promise.resolve({ ok: true, value: [] })),
    listProviders: vi.fn<StorageApi['listProviders']>(() =>
      Promise.resolve({
        ok: true,
        value: [
          { provider: 'GOOGLE_DRIVE', available: true },
          { provider: 'DROPBOX', available: true }
        ]
      })
    ),
    beginConnection: vi.fn<StorageApi['beginConnection']>(() => Promise.resolve({ ok: false, error: 'unknown' })),
    getConnectionStatus: vi.fn<StorageApi['getConnectionStatus']>(() => Promise.resolve({ ok: false, error: 'not-found' })),
    cancelConnection: vi.fn<StorageApi['cancelConnection']>(() => Promise.resolve({ ok: false, error: 'not-found' })),
    listFiles: vi.fn<StorageApi['listFiles']>(() => Promise.resolve({ ok: true, value: { items: [], continuation: null } })),
    getFileMetadata: vi.fn<StorageApi['getFileMetadata']>(() => Promise.resolve({ ok: false, error: 'not-found' })),
    disconnectAccount: vi.fn<StorageApi['disconnectAccount']>(() => Promise.resolve({ ok: true, value: null })),
    revokeAndDisconnect: vi.fn<StorageApi['revokeAndDisconnect']>(() =>
      Promise.resolve({ ok: true, value: { revocation: 'confirmed' as const, reason: 'provider_confirmed', disconnected: true } })
    ),
    verifyFileRead: vi.fn<StorageApi['verifyFileRead']>(() => Promise.resolve({ ok: false, error: 'agent-unavailable' }))
  };
}

export const AGENT_READY: AgentStatus = {
  state: 'ready',
  agentVersion: '0.1.0-SNAPSHOT',
  uptimeMillis: 125_000,
  capabilities: { providerReads: true, rangeReads: true, chunkCache: true, virtualDrive: false, virtualDriveContent: false },
  error: null,
  autoRestartsRemaining: 2
};

/** Fake agent bridge; `push` simulates a status event from the main process. */
export function createMockAgent(initial: AgentStatus = AGENT_READY) {
  let current = initial;
  const listeners = new Set<(s: AgentStatus) => void>();
  const api = {
    getStatus: vi.fn<AgentApi['getStatus']>(() => Promise.resolve(current)),
    restart: vi.fn<AgentApi['restart']>(() => Promise.resolve(current)),
    onStatusChange: vi.fn<AgentApi['onStatusChange']>((l) => {
      listeners.add(l);
      return () => {
        listeners.delete(l);
      };
    })
  };
  return {
    api,
    listeners,
    set(next: AgentStatus) {
      current = next;
    },
    push(next: AgentStatus) {
      current = next;
      act(() => listeners.forEach((l) => l(next)));
    }
  };
}
