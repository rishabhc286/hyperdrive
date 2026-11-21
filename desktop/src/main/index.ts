import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { app, BrowserWindow, ipcMain, session, type IpcMainInvokeEvent } from 'electron';
import { IpcChannel, type BackendHealth } from '../shared/backend-health';
import { checkBackendHealth } from './backend-health';
import { resolveBackendUrl } from './config';
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

function registerIpc(): void {
  ipcMain.handle(
    IpcChannel.backendHealth,
    async (event: IpcMainInvokeEvent, ...args: unknown[]): Promise<BackendHealth> => {
      const senderUrl = event.senderFrame?.url ?? '';
      if (!isTrustedRendererUrl(senderUrl, rendererOrigin)) {
        throw new Error('Rejected IPC call from untrusted frame');
      }
      if (args.length !== 0) {
        throw new Error('checkBackendHealth takes no arguments');
      }
      if (!backendUrl.ok) {
        return { state: 'unavailable', reason: 'misconfigured' };
      }
      return checkBackendHealth(backendUrl.url);
    }
  );
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const [window] = BrowserWindow.getAllWindows();
    if (window) {
      if (window.isMinimized()) window.restore();
      window.focus();
    }
  });

  void app.whenReady().then(() => {
    hardenWebContents();
    registerIpc();
    createMainWindow();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
