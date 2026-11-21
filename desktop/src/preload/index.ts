import { contextBridge, ipcRenderer } from 'electron';
import { IpcChannel, type HyperdriveApi } from '../shared/backend-health';

// Narrow, purpose-built API. Raw ipcRenderer is never exposed, and no method accepts a URL.
const api: HyperdriveApi = {
  checkBackendHealth: () => ipcRenderer.invoke(IpcChannel.backendHealth)
};

contextBridge.exposeInMainWorld('hyperdrive', api);
