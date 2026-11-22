# HyperDrive architecture

Status labels: **Implemented** (exists and is tested in this repository) and **Planned** (designed,
not built). Implemented today: the desktop shell, backend health/readiness, and Google sign-in
with HyperDrive accounts and device sessions (Batch 2).

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

### Remote backend: implemented (health, readiness, accounts)

Spring Boot service (`backend/`). Without the `auth` profile it serves only liveness and readiness
and needs no database. With `SPRING_PROFILES_ACTIVE=auth` it adds:
- Google sign-in (Spring Security OAuth2 Client, identity scopes only).
- Automatic account creation and username onboarding.
- Opaque device sessions.

See [authentication.md](authentication.md). Planned: cloud-connection metadata. It binds to
`127.0.0.1` by default; production deployment hardening is future work.

### PostgreSQL: implemented for accounts

Provided by `compose.yaml` for development, and required in auth mode. It stores users, Google
identities (issuer + subject), device sessions, login transactions, and token **hashes**, in a
Flyway-managed schema. Planned: connected-account records and metadata. It will **not** store
file contents, and it stores no Google tokens.

### Redis: optional, planned use

Optional Compose profile. Nothing uses it yet. It may later be used for coordination or
short-lived values, and will **not** store file contents. Rate limiting is currently in memory,
so Redis is not a runtime dependency.

## Where file data lives

Actual file chunks (4 MiB = 4,194,304 bytes each) will live **only on the local disk** of the
user's machine, in the agent's cache directory. They will **not** be stored in Redis or PostgreSQL.

## Sign-in is not storage access

Google sign-in establishes a HyperDrive identity only. Connecting Google Drive or Dropbox will be
a separate flow with its own consent, scopes and token storage. Disconnecting a storage account
will never delete the HyperDrive account, and a user can connect Dropbox without connecting
Google Drive.

## Desktop security

Implemented in `desktop/src/main` and covered by unit tests plus manual end-to-end probes (see
[progress.md](progress.md)):

- **Window settings:** `nodeIntegration: false`, `contextIsolation: true`, `sandbox: true`,
  `webSecurity: true`, `webviewTag: false`.
- **Preload bridge:** it exposes `window.hyperdrive.checkBackendHealth()` and
  `window.hyperdrive.auth` (`getStatus`, `signInWithGoogle`, `cancelSignIn`,
  `completeOnboarding({ username })`, `signOut`, `retry`, `onStatusChange`). None of them accepts a
  URL or returns a token. Raw `ipcRenderer`, filesystem, shell and URL-opening APIs are not
  exposed.
- **IPC handlers:** they answer only the app's own window and main frame, at the trusted renderer
  URL. They reject unexpected arguments; onboarding accepts exactly `{ username: string }`.
- **System browser:** only main-process sign-in code can open it, and only for the trusted
  backend's `/api/v1/auth/desktop/authorize/<uuid>` URL.
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
