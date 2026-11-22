# HyperDrive architecture

Status labels: **Implemented** (exists and is tested in this repository) and **Planned** (designed,
not built). Implemented today: the desktop shell, backend health/readiness, Google sign-in
with HyperDrive accounts and device sessions (Batch 2), and Google Drive connection with
read-only metadata browsing through the backend (Batch 3).

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
 ┌──────────────────────┐  status / settings (local)        │ • metrics                │
 │ Desktop UI           │ ────────────────────────────────▶ └────────────┬─────────────┘
 │ Electron + React     │                                                │ HTTPS (Range)
 │ IMPLEMENTED: shell,  │                                                ▼
 │ sign-in, onboarding  │                                   Google Drive / Dropbox APIs
 └─────────┬────────────┘                                               PLANNED
           │ HTTP(S): health, sign-in handoff, bearer API
           ▼
 ┌──────────────────────┐       ┌───────────────────┐    ┌──────────────────────────┐
 │ Backend (Spring Boot)│ ────▶ │ PostgreSQL        │    │ Redis (optional)         │
 │ IMPLEMENTED: health, │       │ IMPLEMENTED: users│    │ PLANNED use              │
 │ readiness, Google    │       │ identities,       │    │ coordination / short-    │
 │ sign-in, accounts,   │       │ sessions (hashes) │    │ lived values, never file │
 │ device sessions      │       │ PLANNED: metadata │    │ contents                 │
 └─────────┬────────────┘       └───────────────────┘    └──────────────────────────┘
           │ OpenID Connect (openid, profile, email only)
           ▼
   Google sign-in (accounts.google.com)
```

### Desktop UI: implemented (shell, sign-in, onboarding)

Electron main process and sandboxed React renderer (`desktop/`). Implemented:
- Sign-in through the system browser, using a loopback + PKCE handoff.
- Username onboarding.
- Session refresh and sign-out, all handled in the main process.
- The signed-in shell, with honest empty states and backend reachability.

The renderer only ever receives auth *status*, never tokens. Later the app will host
cloud-storage connections, the unified file browser, performance metrics and cache diagnostics.

### Local agent: planned

Java process on the user's machine (`agent/`). It will translate filesystem reads into
provider range requests, own the local chunk cache, hold device credentials, and collect metrics.

### VFS: planned

C++ WinFsp filesystem (`vfs/`) that mounts the read-only drive and forwards every request to the
local agent.

### Cloud storage (Batch 3): implemented for Google Drive metadata

- **Flow:** the desktop asks the backend to start a Drive connection. The backend runs Google's
  consent with `drive.readonly`, stores the Google tokens **encrypted on the backend**, and
  serves folder listings through `/api/v1/accounts/{id}/files`.
- **Provider abstraction:** a small `FileBrowser` interface (root, list children, get file),
  implemented by `GoogleDriveBrowser`. It returns application-owned `ProviderFile` metadata, so
  Drive wire models stay inside the adapter. OAuth orchestration (`storage/connect`) is
  separate from browsing (`storage/files`).
- **What's stored:** listings are fetched on demand and not stored.
- **Not built:** content transfer, caching and Dropbox. They come in later batches through the
  same abstraction. See [google-drive.md](google-drive.md).
- **Token location, for now:** in this batch provider tokens live on the backend. Where they
  should live once the local agent streams content (device-held credentials, per the agent
  plan below) is an open design decision for that batch.

### Remote backend: implemented (health, readiness, accounts, storage connections)

Spring Boot service (`backend/`). Without the `auth` profile it serves only liveness and readiness
and needs no database. With `SPRING_PROFILES_ACTIVE=auth` it adds:
- Google sign-in (Spring Security OAuth2 Client, identity scopes only).
- Automatic account creation and username onboarding.
- Opaque device sessions.

See [authentication.md](authentication.md). Planned: cloud-connection metadata. It binds to
`127.0.0.1` by default; production deployment hardening is future work.

### PostgreSQL: implemented for accounts

Provided by `compose.yaml` for development, and required in auth mode, with a Flyway-managed
schema.
- **Sign-in data:** users, Google sign-in identities (issuer + subject), device sessions, login
  transactions, and token **hashes**.
- **Storage data (Batch 3):** connected storage accounts, storage authorization transactions,
  and Google Drive tokens **encrypted** with AES-256-GCM, with the key kept outside the
  database.
- **Not stored:** file contents, and file listings.

### Redis: optional, planned use

Optional Compose profile. Nothing uses it yet. It may later be used for coordination or
short-lived values, and will **not** store file contents. Rate limiting is currently in memory,
so Redis is not a runtime dependency.

## Where file data lives

Actual file chunks (4 MiB = 4,194,304 bytes each) will live **only on the local disk** of the
user's machine, in the agent's cache directory. They will **not** be stored in Redis or PostgreSQL.

## Sign-in is not storage access

- **Sign-in:** establishes a HyperDrive identity only.
- **Connecting Google Drive (implemented):** a separate flow with its own consent, scopes,
  callback and encrypted token storage. It may use a different Google account.
- **Disconnecting:** never deletes the HyperDrive account or the remote files, and doesn't sign
  the user out.
- **Dropbox:** will follow the same model.

## Desktop security

Implemented in `desktop/src/main` and covered by unit tests plus manual end-to-end probes (see
[progress.md](progress.md)):

- **Window settings:** `nodeIntegration: false`, `contextIsolation: true`, `sandbox: true`,
  `webSecurity: true`, `webviewTag: false`.
- **Preload bridge:** it exposes `window.hyperdrive.checkBackendHealth()`,
  `window.hyperdrive.auth` (`getStatus`, `signInWithGoogle`, `cancelSignIn`,
  `completeOnboarding({ username })`, `signOut`, `retry`, `onStatusChange`) and
  `window.hyperdrive.storage` (`listAccounts`, `beginGoogleConnection`,
  `getConnectionStatus({ transactionId })`, `cancelConnection({ transactionId })`,
  `listFiles({ accountId, parentId, pageToken })`, `getFileMetadata({ accountId, fileId })`,
  `disconnectAccount({ accountId })`). None of them accepts a URL or returns a token. Raw `ipcRenderer`, filesystem, shell and URL-opening APIs are not
  exposed.
- **IPC handlers:** they answer only the app's own window and main frame, at the trusted renderer
  URL. They reject unexpected arguments; onboarding accepts exactly `{ username: string }`.
- **System browser:** only main-process sign-in and storage code can open it, and only for the
  trusted backend's `/api/v1/auth/desktop/authorize/<uuid>` or
  `/api/v1/storage-connections/google/authorize/hds_<ticket>` URL.
- **Backend address:** it comes only from `HYPERDRIVE_BACKEND_URL` in the main process, and must be
  an http(s) origin with no credentials, path, query or fragment. Requests use a timeout and
  `redirect: 'error'`, and failures become an "unavailable" state, never a crash.
- **Navigation:** anything other than the app's own renderer is blocked. `window.open` is denied,
  `<webview>` attachment is blocked, and all permission requests are denied.
- **Content Security Policy,** injected at build time:
  - **Built app:** `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; …`.
    The renderer cannot make network requests at all.
  - **Dev server only:** additionally allows inline script and style (needed by React Refresh
    and Vite) and the Vite HMR websocket.
- **Single instance:** a single-instance lock is enforced.

More in [security.md](security.md).
