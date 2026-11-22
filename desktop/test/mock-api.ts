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

  const api: HyperdriveApi = {
    checkBackendHealth: vi.fn(() =>
      health === 'pending' ? new Promise<BackendHealth>(() => {}) : Promise.resolve(health)
    ),
    auth,
    storage,
    agent: agent.api
  };

  return {
    api,
    auth,
    storage,
    agent,
    push(next: AuthStatus) {
      current = next;
      act(() => listener?.(next));
    }
  };
}

/** Fake storage bridge; every method resolves with an empty success unless a test overrides it. */
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
    verifyFileRead: vi.fn<StorageApi['verifyFileRead']>(() => Promise.resolve({ ok: false, error: 'agent-unavailable' }))
  };
}

export const AGENT_READY: AgentStatus = {
  state: 'ready',
  agentVersion: '0.1.0-SNAPSHOT',
  uptimeMillis: 125_000,
  capabilities: { providerReads: true, rangeReads: true, chunkCache: false, virtualDrive: false },
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
