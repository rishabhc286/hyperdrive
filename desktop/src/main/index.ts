import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  app,
  BrowserWindow,
  ipcMain,
  safeStorage,
  session,
  shell,
  type IpcMainInvokeEvent
} from 'electron';
import { AgentIpcChannel } from '../shared/agent';
import { AuthIpcChannel } from '../shared/auth';
import { StorageIpcChannel } from '../shared/storage';
import { IpcChannel, type BackendHealth } from '../shared/backend-health';
import { AgentManager } from './agent/agent-manager';
import { resolveLaunchConfig } from './agent/launch-config';
import { agentHttpGet, agentHttpPost, checkJava, isFile, randomSecret, spawnAgent } from './agent/node-runtime';
import { ReadCoordinator } from './storage/read-coordinator';
import { CacheCoordinator, endsCacheSession } from './storage/cache-coordinator';
import { CacheKeyStore } from './storage/cache-key-store';
import { CacheSettings } from './storage/cache-settings';
import { CacheIpcChannel } from '../shared/cache';
import { AuthManager, isTrustedAuthorizeUrl } from './auth/auth-manager';
import { BackendApi } from './auth/backend-api';
import { startLoopbackListener } from './auth/loopback';
import { createTokenStore } from './auth/token-store';
import { checkBackendHealth } from './backend-health';
import { resolveBackendUrl } from './config';
import { deviceName } from './device-name';
import {
  expectNoArgs,
  parseAccountPayload,
  parseBeginConnectionPayload,
  parseFileMetadataPayload,
  parseListFilesPayload,
  parseOnboardingPayload,
  parseCacheBudgetPayload,
  parseTransactionPayload,
  parseVerifyReadPayload,
  parseDriveLetterPayload
} from './ipc-validation';
import { isTrustedStorageAuthorizeUrl, StorageManager } from './storage/storage-manager';
import { isTrustedRendererUrl, type RendererOrigin } from './security';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { VirtualDriveIpcChannel } from '../shared/vfs';
import { resolveVfsConfig } from './vfs/vfs-config';
import { VfsManager } from './vfs/vfs-manager';
import { VirtualDriveCoordinator } from './vfs/virtual-drive';
import { DriveSettings } from './vfs/drive-settings';
import { AgentUnavailableError } from './agent/agent-manager';
import { SessionExpiredError } from './auth/auth-manager';
import { BackendUnreachableError } from './auth/backend-api';

// Set by electron-vite only while running `npm run dev`.
const devServerUrl = app.isPackaged ? undefined : process.env['ELECTRON_RENDERER_URL'];
const rendererPath = join(__dirname, '../renderer/index.html');
const rendererOrigin: RendererOrigin = {
  devServerUrl,
  rendererFileUrl: pathToFileURL(rendererPath).href
};

const backendUrl = resolveBackendUrl(process.env);
if (!backendUrl.ok) {
  console.error(`[hyperdrive] ${backendUrl.error}`);
}

let mainWindow: BrowserWindow | null = null;

function createMainWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1200,
    height: 780,
    minWidth: 360,
    minHeight: 480,
    show: false,
    title: 'HyperDrive',
    backgroundColor: '#0f1217',
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      webviewTag: false,
      spellcheck: false
    }
  });

  window.once('ready-to-show', () => window.show());
  window.on('closed', () => {
    if (mainWindow === window) mainWindow = null;
  });

  if (devServerUrl) {
    void window.loadURL(devServerUrl);
  } else {
    void window.loadFile(rendererPath);
  }
  return window;
}

function hardenWebContents(): void {
  app.on('web-contents-created', (_event, contents) => {
    contents.on('will-navigate', (event, url) => {
      if (!isTrustedRendererUrl(url, rendererOrigin)) {
        event.preventDefault();
      }
    });
    contents.on('will-redirect', (event, url) => {
      if (!isTrustedRendererUrl(url, rendererOrigin)) {
        event.preventDefault();
      }
    });
    contents.on('will-attach-webview', (event) => event.preventDefault());
    contents.setWindowOpenHandler(() => ({ action: 'deny' }));
  });

  // The UI needs no camera, microphone, notifications, geolocation, etc.
  session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
}

/**
 * Registers an IPC handler that only answers the app's own window, main frame, at the trusted
 * renderer URL. Handlers receive the raw argument list and must validate it themselves.
 */
function handleTrusted(channel: string, handler: (args: unknown[]) => unknown): void {
  ipcMain.handle(channel, async (event: IpcMainInvokeEvent, ...args: unknown[]) => {
    const frame = event.senderFrame;
    if (
      !mainWindow ||
      event.sender !== mainWindow.webContents ||
      !frame ||
      frame !== event.sender.mainFrame ||
      !isTrustedRendererUrl(frame.url, rendererOrigin)
    ) {
      throw new Error('Rejected IPC call from untrusted frame');
    }
    return handler(args);
  });
}

function registerIpc(
  auth: AuthManager,
  storage: StorageManager,
  agent: AgentManager,
  reads: ReadCoordinator,
  cache: CacheCoordinator,
  drive: VirtualDriveCoordinator
): void {
  // Virtual drive (Batch 8): status, mount/unmount, open the confirmed mount, drive letter only.
  handleTrusted(VirtualDriveIpcChannel.getStatus, (args) => {
    expectNoArgs(VirtualDriveIpcChannel.getStatus, args);
    return drive.status();
  });
  handleTrusted(VirtualDriveIpcChannel.mount, (args) => {
    expectNoArgs(VirtualDriveIpcChannel.mount, args);
    return drive.mount();
  });
  handleTrusted(VirtualDriveIpcChannel.unmount, (args) => {
    expectNoArgs(VirtualDriveIpcChannel.unmount, args);
    return drive.unmount();
  });
  handleTrusted(VirtualDriveIpcChannel.openInExplorer, async (args) => {
    expectNoArgs(VirtualDriveIpcChannel.openInExplorer, args);
    // The target is computed here from the confirmed mount; the renderer supplies nothing.
    const root = drive.mountedRoot();
    if (!root || !/^[D-Z]:\\$/.test(root)) return { ok: false, error: 'unknown' };
    const error = await shell.openPath(root);
    return error ? { ok: false, error: 'unknown' } : { ok: true, value: null };
  });
  handleTrusted(VirtualDriveIpcChannel.setDriveLetter, (args) => {
    return drive.setDriveLetter(parseDriveLetterPayload(VirtualDriveIpcChannel.setDriveLetter, args).letter);
  });
  drive.onChange((status) => mainWindow?.webContents.send(VirtualDriveIpcChannel.changed, status));
  agent.onChange((status) => {
    // A restarted or failed agent has forgotten the mount capability: tear the drive down.
    const instance = status.state === 'ready' ? agent.instanceId() : null;
    if (mountedAgentInstance !== null && instance !== mountedAgentInstance) void drive.teardown();
    mountedAgentInstance = instance;
  });

  handleTrusted(CacheIpcChannel.getStatus, (args) => {
    expectNoArgs(CacheIpcChannel.getStatus, args);
    return cache.status();
  });
  handleTrusted(CacheIpcChannel.setBudget, (args) => {
    return cache.setBudget(parseCacheBudgetPayload(CacheIpcChannel.setBudget, args).budgetBytes);
  });
  handleTrusted(CacheIpcChannel.clear, (args) => {
    expectNoArgs(CacheIpcChannel.clear, args);
    return cache.clear();
  });
  handleTrusted(StorageIpcChannel.verifyFileRead, (args) => {
    return reads.verifyRead(parseVerifyReadPayload(StorageIpcChannel.verifyFileRead, args));
  });
  // Agent: status and a bounded restart only. No port, secret, path or spawn parameter crosses IPC.
  handleTrusted(AgentIpcChannel.getStatus, (args) => {
    expectNoArgs(AgentIpcChannel.getStatus, args);
    return agent.getStatus();
  });
  handleTrusted(AgentIpcChannel.restart, (args) => {
    expectNoArgs(AgentIpcChannel.restart, args);
    return agent.restart();
  });
  agent.onChange((status) => {
    mainWindow?.webContents.send(AgentIpcChannel.statusChanged, status);
  });

  handleTrusted(IpcChannel.backendHealth, async (args): Promise<BackendHealth> => {
    expectNoArgs(IpcChannel.backendHealth, args);
    if (!backendUrl.ok) {
      return { state: 'unavailable', reason: 'misconfigured' };
    }
    return checkBackendHealth(backendUrl.url);
  });

  handleTrusted(AuthIpcChannel.getStatus, (args) => {
    expectNoArgs(AuthIpcChannel.getStatus, args);
    return auth.getStatus();
  });
  handleTrusted(AuthIpcChannel.signIn, (args) => {
    expectNoArgs(AuthIpcChannel.signIn, args);
    // Runs in the background; progress is pushed via status events.
    void auth.signInWithGoogle();
  });
  handleTrusted(AuthIpcChannel.cancelSignIn, (args) => {
    expectNoArgs(AuthIpcChannel.cancelSignIn, args);
    auth.cancelSignIn();
  });
  handleTrusted(AuthIpcChannel.completeOnboarding, (args) => {
    const { username } = parseOnboardingPayload(AuthIpcChannel.completeOnboarding, args);
    return auth.completeOnboarding(username);
  });
  handleTrusted(AuthIpcChannel.signOut, (args) => {
    expectNoArgs(AuthIpcChannel.signOut, args);
    return auth.signOut();
  });
  handleTrusted(AuthIpcChannel.retry, async (args) => {
    expectNoArgs(AuthIpcChannel.retry, args);
    await auth.initialize();
  });

  handleTrusted(StorageIpcChannel.listAccounts, (args) => {
    expectNoArgs(StorageIpcChannel.listAccounts, args);
    return storage.listAccounts();
  });
  handleTrusted(StorageIpcChannel.listProviders, (args) => {
    expectNoArgs(StorageIpcChannel.listProviders, args);
    return storage.listProviders();
  });
  handleTrusted(StorageIpcChannel.beginConnection, (args) => {
    return storage.beginConnection(parseBeginConnectionPayload(StorageIpcChannel.beginConnection, args).provider);
  });
  handleTrusted(StorageIpcChannel.getConnectionStatus, (args) => {
    return storage.getConnectionStatus(parseTransactionPayload(StorageIpcChannel.getConnectionStatus, args).transactionId);
  });
  handleTrusted(StorageIpcChannel.cancelConnection, (args) => {
    return storage.cancelConnection(parseTransactionPayload(StorageIpcChannel.cancelConnection, args).transactionId);
  });
  handleTrusted(StorageIpcChannel.listFiles, (args) => {
    return storage.listFiles(parseListFilesPayload(StorageIpcChannel.listFiles, args));
  });
  handleTrusted(StorageIpcChannel.getFileMetadata, (args) => {
    const { accountId, fileId } = parseFileMetadataPayload(StorageIpcChannel.getFileMetadata, args);
    return storage.getFileMetadata(accountId, fileId);
  });
  handleTrusted(StorageIpcChannel.disconnectAccount, (args) => {
    return storage.disconnectAccount(parseAccountPayload(StorageIpcChannel.disconnectAccount, args).accountId);
  });
  handleTrusted(StorageIpcChannel.revokeAndDisconnect, (args) => {
    return storage.revokeAndDisconnect(parseAccountPayload(StorageIpcChannel.revokeAndDisconnect, args).accountId);
  });

  auth.onChange((status) => {
    // Sign-out or session expiry: unmount the drive and revoke its capability and grant first.
    if (endsCacheSession(status.phase)) void drive.teardown();
    if (status.phase === 'signed-in') cache.noteUser(status.user?.id ?? null);
    if (status.phase !== 'signed-in') {
      storage.reset();
      // Drop every delegated provider credential held by the agent. Only a real sign-out or session
      // expiry also purges the user's cached chunks and deletes the cache key (docs/cache.md).
      void reads.reset({ endCache: endsCacheSession(status.phase) });
    }
    mainWindow?.webContents.send(AuthIpcChannel.statusChanged, status);
  });
}

function createCacheCoordinator(auth: AuthManager, agent: AgentManager): CacheCoordinator {
  const userData = app.getPath('userData');
  const keys = new CacheKeyStore(safeStorage, join(userData, 'cache-keys'));
  if (!keys.available) {
    console.warn('[hyperdrive] OS-level encryption is unavailable; the local file cache is disabled (reads stay uncached).');
  }
  return new CacheCoordinator({
    agent,
    keys,
    settings: new CacheSettings(join(userData, 'cache-settings.json')),
    currentUserId: () => {
      const status = auth.getStatus();
      return status.phase === 'signed-in' ? (status.user?.id ?? null) : null;
    }
  });
}

function createReadCoordinator(
  api: BackendApi | null,
  auth: AuthManager,
  agent: AgentManager,
  cache: CacheCoordinator
): ReadCoordinator {
  return new ReadCoordinator({
    agent,
    cache,
    issueCredential: async (accountId) => {
      if (!api) throw new Error('Backend not configured');
      return auth.withAccessToken((token) => api.issueReadCredential(token, accountId));
    },
    getFile: async (accountId, fileId) => {
      if (!api) throw new Error('Backend not configured');
      return auth.withAccessToken((token) => api.getFile(token, accountId, fileId));
    }
  });
}

let mountedAgentInstance: string | null = null;

function createStorageManager(
  api: BackendApi | null,
  auth: AuthManager,
  reads?: ReadCoordinator,
  onDisconnect?: () => Promise<unknown>
): StorageManager {
  return new StorageManager({
    // Disconnect unmounts the whole virtual drive (simplest safe behavior in Batch 8), then forgets
    // the account's delegated credential and cached chunks.
    onAccountDisconnected: async (accountId) => {
      await onDisconnect?.().catch(() => undefined);
      await (reads?.forgetAccount(accountId) ?? Promise.resolve());
    },
    api,
    withAccessToken: (call) => auth.withAccessToken(call),
    openExternal: async (url) => {
      // Defense in depth: StorageManager already validated this; re-check right before opening.
      if (!api || !(['GOOGLE_DRIVE', 'DROPBOX'] as const).some((p) => isTrustedStorageAuthorizeUrl(url, api.origin, p))) {
        throw new Error('Refusing to open an untrusted URL');
      }
      await shell.openExternal(url);
    }
  });
}

function createVirtualDrive(
  api: BackendApi | null,
  auth: AuthManager,
  agent: AgentManager,
  listAccounts: () => Promise<boolean>
): VirtualDriveCoordinator {
  const config = resolveVfsConfig({
    env: process.env,
    platform: process.platform,
    arch: process.arch,
    isPackaged: app.isPackaged,
    appPath: app.getAppPath(),
    fileExists: isFile
  });
  const vfs =
    'exePath' in config
      ? new VfsManager({
          exePath: config.exePath,
          // No shell, no arguments; secrets only on stdin.
          spawn: (command, args) => spawn(command, [...args], { shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
        })
      : null;
  return new VirtualDriveCoordinator({
    vfs,
    prerequisite: () => {
      if ('missing' in config) return config.missing;
      return agent.snapshot().state === 'ready' ? null : 'agent-unavailable';
    },
    agent: {
      loopbackPort: () => agent.loopbackPort(),
      instanceId: () => agent.instanceId(),
      post: (path, body, timeoutMs) => agent.post(path, body, timeoutMs)
    },
    issueGrant: async () => {
      if (!api) throw new BackendUnreachableError(false);
      return auth.withAccessToken((token) => api.issueMetadataGrant(token));
    },
    revokeGrant: async () => {
      if (!api || auth.getStatus().phase !== 'signed-in') return;
      await auth.withAccessToken((token) => api.revokeMetadataGrant(token));
    },
    currentUserId: () => {
      const status = auth.getStatus();
      return status.phase === 'signed-in' ? (status.user?.id ?? null) : null;
    },
    hasConnectedAccounts: listAccounts,
    backendOrigin: api?.origin ?? '',
    driveLetterInUse: (letter) => existsSync(`${letter}:\\`),
    settings: new DriveSettings(join(app.getPath('userData'), 'virtual-drive.json')),
    classifyError: (error) => {
      if (error instanceof SessionExpiredError) return 'session-expired';
      if (error instanceof BackendUnreachableError) return 'backend-unreachable';
      if (error instanceof AgentUnavailableError) return 'agent-unavailable';
      return 'unknown';
    }
  });
}

function createAgentManager(): AgentManager {
  const manager = new AgentManager({
    launch: resolveLaunchConfig({
      env: process.env,
      platform: process.platform,
      isPackaged: app.isPackaged,
      appPath: app.getAppPath(),
      userData: app.getPath('userData'),
      fileExists: isFile
    }),
    fileExists: isFile,
    checkJava,
    spawn: spawnAgent,
    httpGet: agentHttpGet,
    httpPost: agentHttpPost,
    randomSecret
  });
  manager.onChange((status) => {
    if (status.state === 'failed') {
      // Main-process console only (never the renderer): the reason code plus redacted agent stderr.
      console.warn(`[hyperdrive] local agent failed: ${status.error ?? 'unknown'}`);
      for (const line of manager.log.snapshot().slice(-10)) console.warn(`[hyperdrive-agent] ${line}`);
    }
  });
  return manager;
}

function createAuthManager(api: BackendApi | null): AuthManager {
  const store = createTokenStore(safeStorage, join(app.getPath('userData'), 'session', 'refresh-token.json'));
  if (store.persistence === 'memory-only') {
    console.warn('[hyperdrive] OS-level encryption is unavailable; sessions will not survive a restart.');
  }
  return new AuthManager({
    api,
    store,
    startLoopback: startLoopbackListener,
    openExternal: async (url) => {
      // Defense in depth: AuthManager already validated this; re-check right before opening.
      if (!api || !isTrustedAuthorizeUrl(url, api.origin)) {
        throw new Error('Refusing to open an untrusted URL');
      }
      await shell.openExternal(url);
    },
    deviceName: deviceName()
  });
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  let agent: AgentManager | null = null;
  let drive: VirtualDriveCoordinator | null = null;
  let agentStopped = false;

  void app.whenReady().then(() => {
    hardenWebContents();
    const api = backendUrl.ok ? new BackendApi(backendUrl.url) : null;
    const auth = createAuthManager(api);
    agent = createAgentManager();
    const cache = createCacheCoordinator(auth, agent);
    const reads = createReadCoordinator(api, auth, agent, cache);
    let storage: StorageManager | null = null;
    const virtualDrive = createVirtualDrive(api, auth, agent, async () => {
      const result = await storage?.listAccounts();
      return !result || !result.ok || result.value.length > 0;
    });
    drive = virtualDrive;
    void virtualDrive.init();
    storage = createStorageManager(api, auth, reads, () => virtualDrive.teardown());
    registerIpc(auth, storage, agent, reads, cache, virtualDrive);
    mainWindow = createMainWindow();
    void auth.initialize();
    // The agent is independent of sign-in and of the remote backend; cloud browsing works without it.
    void agent.start();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) mainWindow = createMainWindow();
    });
  });

  // On quit: stop admitting mounts and unmount the owned drive (revoking its capability and grant),
  // then stop the owned agent gracefully (bounded), then let the app exit.
  app.on('before-quit', (event) => {
    if (!agent || agentStopped) return;
    event.preventDefault();
    agentStopped = true;
    const owned = agent;
    void (drive?.close() ?? Promise.resolve())
      .catch(() => undefined)
      .finally(() => void owned.stop().finally(() => app.quit()));
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
