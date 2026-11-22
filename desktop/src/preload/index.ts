import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { AuthIpcChannel, type AuthApi, type AuthStatus } from '../shared/auth';
import { IpcChannel, type HyperdriveApi } from '../shared/backend-health';
import { StorageIpcChannel, type StorageApi } from '../shared/storage';

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

// Payloads are rebuilt with only the expected keys; the main process validates them again.
const storage: StorageApi = {
  listAccounts: () => ipcRenderer.invoke(StorageIpcChannel.listAccounts),
  beginGoogleConnection: () => ipcRenderer.invoke(StorageIpcChannel.beginGoogleConnection),
  getConnectionStatus: (input) =>
    ipcRenderer.invoke(StorageIpcChannel.getConnectionStatus, { transactionId: String(input.transactionId) }),
  cancelConnection: (input) =>
    ipcRenderer.invoke(StorageIpcChannel.cancelConnection, { transactionId: String(input.transactionId) }),
  listFiles: (input) =>
    ipcRenderer.invoke(StorageIpcChannel.listFiles, {
      accountId: String(input.accountId),
      parentId: input.parentId == null ? null : String(input.parentId),
      pageToken: input.pageToken == null ? null : String(input.pageToken)
    }),
  getFileMetadata: (input) =>
    ipcRenderer.invoke(StorageIpcChannel.getFileMetadata, {
      accountId: String(input.accountId),
      fileId: String(input.fileId)
    }),
  disconnectAccount: (input) =>
    ipcRenderer.invoke(StorageIpcChannel.disconnectAccount, { accountId: String(input.accountId) })
};

const api: HyperdriveApi = {
  checkBackendHealth: () => ipcRenderer.invoke(IpcChannel.backendHealth),
  auth,
  storage
};

contextBridge.exposeInMainWorld('hyperdrive', api);
