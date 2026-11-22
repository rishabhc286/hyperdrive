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
import { AuthIpcChannel } from '../shared/auth';
import { StorageIpcChannel } from '../shared/storage';
import { IpcChannel, type BackendHealth } from '../shared/backend-health';
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
  parseTransactionPayload
} from './ipc-validation';
import { isTrustedStorageAuthorizeUrl, StorageManager } from './storage/storage-manager';
import { isTrustedRendererUrl, type RendererOrigin } from './security';

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

function registerIpc(auth: AuthManager, storage: StorageManager): void {
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

  auth.onChange((status) => {
    if (status.phase !== 'signed-in') storage.reset();
    mainWindow?.webContents.send(AuthIpcChannel.statusChanged, status);
  });
}

function createStorageManager(api: BackendApi | null, auth: AuthManager): StorageManager {
  return new StorageManager({
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

  void app.whenReady().then(() => {
    hardenWebContents();
    const api = backendUrl.ok ? new BackendApi(backendUrl.url) : null;
    const auth = createAuthManager(api);
    registerIpc(auth, createStorageManager(api, auth));
    mainWindow = createMainWindow();
    void auth.initialize();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) mainWindow = createMainWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
