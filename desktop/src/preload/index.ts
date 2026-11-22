import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { AuthIpcChannel, type AuthApi, type AuthStatus } from '../shared/auth';
import { IpcChannel, type HyperdriveApi } from '../shared/backend-health';

// Narrow, purpose-built API. Raw ipcRenderer is never exposed, no method accepts a URL, and no
// method returns a token.
const auth: AuthApi = {
  getStatus: () => ipcRenderer.invoke(AuthIpcChannel.getStatus),
  signInWithGoogle: () => ipcRenderer.invoke(AuthIpcChannel.signIn),
  cancelSignIn: () => ipcRenderer.invoke(AuthIpcChannel.cancelSignIn),
  // Rebuild the payload so only a string username crosses the bridge.
  completeOnboarding: (input) =>
    ipcRenderer.invoke(AuthIpcChannel.completeOnboarding, { username: String(input.username) }),
  signOut: () => ipcRenderer.invoke(AuthIpcChannel.signOut),
  retry: () => ipcRenderer.invoke(AuthIpcChannel.retry),
  onStatusChange: (listener) => {
    const wrapped = (_event: IpcRendererEvent, status: AuthStatus): void => listener(status);
    ipcRenderer.on(AuthIpcChannel.statusChanged, wrapped);
    return () => {
      ipcRenderer.removeListener(AuthIpcChannel.statusChanged, wrapped);
    };
  }
};

const api: HyperdriveApi = {
  checkBackendHealth: () => ipcRenderer.invoke(IpcChannel.backendHealth),
  auth
};

contextBridge.exposeInMainWorld('hyperdrive', api);
