# HyperDrive architecture

Status labels: **Implemented** (exists and is tested in this repository) and **Planned** (designed,
not built). Today only the desktop shell and the backend health endpoint are implemented.

## Components

```
 Windows File Explorer / apps
          │  Win32 file I/O
          ▼
 ┌─────────────────────┐      local, authenticated IPC      ┌──────────────────────────┐
 │ VFS (C++ + WinFsp)  │ ─────────────────────────────────▶ │ Local agent (Java)       │
 │ PLANNED             │                                    │ PLANNED                  │
 └─────────────────────┘                                    │ • 4 MiB chunk reads      │
                                                            │ • on-disk chunk cache ◀──┼── file chunks live HERE
 ┌─────────────────────┐   status / settings (local)        │ • metrics                │
 │ Desktop UI          │ ─────────────────────────────────▶ └────────────┬─────────────┘
 │ Electron + React    │                                                 │ HTTPS (Range)
 │ IMPLEMENTED (shell) │                                                 ▼
 └─────────┬───────────┘                                      Google Drive / Dropbox APIs
           │ HTTPS (health only, today)                                 PLANNED
           ▼
 ┌─────────────────────┐        ┌──────────────┐     ┌──────────────────────────┐
 │ Backend             │ ─────▶ │ PostgreSQL   │     │ Redis (optional)         │
 │ Spring Boot         │        │ PLANNED use  │     │ PLANNED use              │
 │ IMPLEMENTED: health │ ─────▶ │ accounts,    │     │ coordination / short-    │
 └─────────────────────┘        │ metadata     │     │ lived cache, never file  │
                                └──────────────┘     │ contents                 │
                                                     └──────────────────────────┘
```

### Desktop UI: implemented (shell only)

Electron main process + sandboxed React renderer (`desktop/`). Today it shows navigation, honest
empty states and backend reachability. Later it will host sign-in, username onboarding, account
connections, the unified file browser, performance metrics and cache diagnostics.

### Local agent: planned

Java process on the user's machine (`agent/`). It will translate filesystem reads into
provider range requests, own the local chunk cache, hold device credentials, and collect metrics.

### VFS: planned

C++ WinFsp filesystem (`vfs/`) that mounts the read-only drive and forwards every request to the
local agent.

### Remote backend: implemented (health endpoint only)

Spring Boot service (`backend/`). Today it serves only `GET /api/v1/health`. Planned: Google
sign-in that creates a HyperDrive account, username onboarding, and account and cloud-connection
metadata. It has **no authentication yet** and binds to `127.0.0.1` by default.

### PostgreSQL: planned use

Provided by `compose.yaml` for development. It will store users, connected-account records and
metadata. It will **not** store file contents. The backend has no database dependency yet.

### Redis: optional, planned use

Optional Compose profile. It may be used for coordination or short-lived cached values. It will
**not** store file contents.

## Where file data lives

Actual file chunks (4 MiB = 4,194,304 bytes each) will live **only on the local disk** of the
user's machine, in the agent's cache directory. They will **not** be stored in Redis or PostgreSQL.

## Desktop security

Implemented in `desktop/src/main` and covered by unit tests plus a manual end-to-end probe (see
[progress.md](progress.md)):

- `nodeIntegration: false`, `contextIsolation: true`, `sandbox: true`, `webSecurity: true`,
  `webviewTag: false`.
- Preload exposes exactly one function, `window.hyperdrive.checkBackendHealth()`. It takes no
  arguments and cannot be pointed at a URL. Raw `ipcRenderer`, filesystem, shell and URL-opening
  APIs are not exposed.
- The IPC handler rejects calls from any frame that isn't the app's own renderer, and rejects
  any arguments.
- The backend address comes only from `HYPERDRIVE_BACKEND_URL` in the main process. It must be
  an http(s) origin with no credentials, path, query or fragment. Requests use a 3 s timeout and
  `redirect: 'error'`, and any failure becomes an "unavailable" state, never a crash.
- Navigation to anything other than the app's own renderer is blocked, `window.open` is denied,
  `<webview>` attachment is blocked, and all permission requests (camera, notifications, and so
  on) are denied.
- Content Security Policy is injected at build time:
  - **Built app:** `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; …`.
    The renderer cannot make network requests at all.
  - **Dev server only:** additionally allows inline script and style (needed by React Refresh
    and Vite) and the Vite HMR websocket.
- Single-instance lock.
