# HyperDrive architecture

Status labels: **Implemented** (exists and is tested in this repository) and **Planned** (designed,
not built). Implemented today: the local agent's process and status API (Batch 5), the desktop shell, backend health/readiness, Google sign-in
with HyperDrive accounts and device sessions (Batch 2), Google Drive connection with
read-only metadata browsing through the backend (Batch 3), and Dropbox plus a unified "All
storage" browser (Batch 4).

## Components

```
 Windows File Explorer / apps
          │  Win32 file I/O
          ▼
 ┌─────────────────────┐      local, authenticated IPC      ┌──────────────────────────┐
 │ VFS (C++ + WinFsp)  │ ─────────────────────────────────▶ │ Local agent (Java)       │
 │ PLANNED             │   transport: OPEN (ADR 0001)       │ IMPLEMENTED: process,    │
 └─────────────────────┘                                    │ status API (loopback)    │
                                                            │ PLANNED:                 │
                                                            │ • 4 MiB chunk reads      │
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

### Local agent: implemented as a process and status API (Batch 5)

Java 21 process on the user's machine (`agent/`), started and supervised by the Electron main
process.
- **Talks over:** an authenticated loopback HTTP/JSON **status** API on `127.0.0.1`, at an
  OS-assigned port (`/local/v1/health`, `/local/v1/status`).
- **Authentication:** a per-launch 256-bit secret sent over stdin, and a validated one-line
  handshake on stdout.
- **Lifetime:** stdin also serves as the parent-lifetime channel.
- **Capabilities:** `providerReads` and `rangeReads` are `true` since Batch 6, and `chunkCache`
  since Batch 7; `virtualDrive` is `false`.

**Chunk cache (Batch 7):**
- **What's cached:** encrypted 4 MiB chunks under `<agent data dir>\cache\v1\`, for version-bound
  reads only (Dropbox `rev:` reads that Dropbox confirms). Google Drive reads always bypass the
  cache.
- **Key:** owned by the desktop main process (DPAPI-wrapped) and handed to the agent per session.
- **Limits and cleanup:** a 5 GiB default budget with LRU eviction, purged on clear, disconnect and
  logout.
- See [cache.md](cache.md) and [ADR 0003](adr/0003-cache-consistency-and-key-lifecycle.md).

**Provider reads (Batch 6):**
- The desktop main process obtains a delegated provider access token from the backend and hands
  it to the agent, keeping only an opaque handle.
- The agent then serves bounded reads (`/local/v1/reads`, at most 4 MiB) by calling the
  provider's fixed content endpoint with `Range`. Bytes go provider → agent → main process,
  where the diagnostic hashes and discards them.
- See [ADR 0002](adr/0002-provider-access-token-delegation.md) and
  [local-agent.md](local-agent.md#bounded-read-contract-batch-6).

**Still planned:** prefetching, throughput metrics, and the transport for content reads through
the drive (Batch 9).

### VFS (Batch 8): read-only, metadata only

- **What:** C++ WinFsp filesystem (`vfs/`, `hyperdrive-vfs.exe`) started by the desktop's
  `VfsManager` (no shell, no arguments, bootstrap on stdin). It mounts a read-only drive letter
  in the user's session; no service.
- **Transport:** authenticated loopback HTTP to the agent via WinHTTP, behind a narrow C++
  interface ([ADR 0004](adr/0004-vfs-transport-and-scoped-metadata-authorization.md)). It closes
  ADR 0001's question for **metadata only**.
- **Authorization chain:** session token → backend **metadata grant** (`/api/v1/vfs/**` only,
  15 min) held by the agent → per-mount **capability** (agent's `/local/v1/vfs/session|accounts|list`
  only) held by the VFS.
- **Data:** one directory listing at a time, assembled by the agent from backend pages (bounded),
  snapshotted and enumerated by the VFS with WinFsp markers. Names are mapped deterministically.
- **Content reads:** not implemented (`STATUS_NOT_SUPPORTED`). Details: [virtual-drive.md](virtual-drive.md).

### Cloud storage (Batches 3–4): implemented for Google Drive and Dropbox metadata

- **Flow:** the desktop asks the backend to start a Drive connection. The backend runs Google's
  consent with `drive.readonly`, stores the Google tokens **encrypted on the backend**, and
  serves folder listings through `/api/v1/accounts/{id}/files`.
- **Provider abstraction, browsing:** a small `FileBrowser` interface (list children, get file;
  the account's `AccountRoot` is passed in), implemented by `GoogleDriveBrowser` and
  `DropboxBrowser`.
  - Both return application-owned `ProviderFile` metadata. Stable `providerFileId` is kept
    separate from `directoryLocator` (Drive: folder ID; Dropbox: `path_lower`).
  - Revision and content hash are labeled by algorithm. Nothing is invented: Dropbox has no MIME
    types, so `mimeType` stays null.
  - Shared HTTP retry and backoff is `ProviderHttp`.
- **Provider abstraction, connecting:** `StorageOAuth` (Spring Security plumbing, with optional
  PKCE) plus one `ConnectionCompleter` per provider, which handles identity, scopes and root.
  This is separate from browsing (`storage/files`).
- **Pagination:** provider cursors are wrapped into encrypted, account-bound continuations
  (`ContinuationCodec`).
- **All storage:** a desktop-side **virtual** navigation root that lists accounts. It's never
  sent to a provider and never merges files across clouds.
- **What's stored:** listings are fetched on demand and not stored.
- **Not built:** content transfer and caching. See [google-drive.md](google-drive.md) and
  [dropbox.md](dropbox.md).
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
- **Storage data (Batches 3–4):** connected storage accounts (Dropbox rows add a typed
  `root_namespace_id` and `root_type`), storage authorization transactions, and Google Drive and
  Dropbox tokens **encrypted** with AES-256-GCM, with the key kept outside the database.
- **Not stored:** file contents, and file listings.

### Redis: optional, planned use

Optional Compose profile. Nothing uses it yet. It may later be used for coordination or
short-lived values, and will **not** store file contents. Rate limiting is currently in memory,
so Redis is not a runtime dependency.

## Where file data lives

Cached file chunks (4 MiB = 4,194,304 bytes each) live **only on the local disk** of the user's
machine, **encrypted**, in the agent's cache directory (`%APPDATA%\HyperDrive\agent\cache\v1\`).
They are **never** stored in Redis or PostgreSQL, and the backend never sees them
([cache.md](cache.md)).

## Sign-in is not storage access

- **Sign-in:** establishes a HyperDrive identity only.
- **Connecting Google Drive (implemented):** a separate flow with its own consent, scopes,
  callback and encrypted token storage. It may use a different Google account.
- **Disconnecting:** never deletes the HyperDrive account or the remote files, and doesn't sign
  the user out.
- **Dropbox (implemented):** the same model, with its own consent, callback and identity
  (`account_id`).

## Provider support matrix

| Capability | Google Drive | Dropbox |
| --- | --- | --- |
| Connect (separate from sign-in, own consent) | Implemented | Implemented |
| Account identity | OIDC subject (validated ID token) | `account_id` from `users/get_current_account` |
| Scopes | `drive.readonly` (restricted) + `openid email profile` | `account_info.read files.metadata.read files.content.read` (content scope since Batch 6; older connections lack it) |
| Root browsing | My Drive root | Account root via `Dropbox-API-Path-Root` (team space for team members) |
| Nested folders | Yes (by folder id) | Yes (by `path_lower`) |
| Pagination | Yes (`nextPageToken`, wrapped) | Yes (`cursor` + `list_folder/continue`, wrapped) |
| Shared drives / "Shared with me" | Not implemented | n/a (team space supported, untested live) |
| Native documents | Labeled, no size, not downloadable | Non-downloadable cloud docs labeled, no size |
| Shortcuts / links | Shown, not followed | Shown as "Link", not followed |
| Content download (whole file) | **Not implemented** | **Not implemented** |
| Bounded range read via agent (≤ 4 MiB, diagnostic) | Implemented: `files.get?alt=media`, ordinary binaries only, **not revision-pinned** | Implemented: `files/download`, ordinary files, **pinned with `rev:`** when the listed revision is known |
| Persistent encrypted chunk cache | **Bypassed: never cached** (`provider_not_version_bound`) | Implemented for `rev:` reads confirmed by `Dropbox-API-Result`; unconfirmed reads served uncached |
| Streaming / playback / prefetching | **Not implemented** | **Not implemented** |
| Writes (upload/edit/rename/move/delete) | **Not implemented** | **Not implemented** |
| Mounted drive (WinFsp, Batch 8) | Folders and files with size/time; native documents and shortcuts not shown; **no content reads** | Folders and files with size/time; **no content reads** |
| Credentials in the agent | Delegated access token only, memory-only, ≤ 20 min retention | Delegated access token only, memory-only, ≤ 20 min retention |
| Live end-to-end verification | Not yet (no OAuth client); reads tested only against local mock servers | Not yet (no Dropbox app); reads tested only against local mock servers |

## Desktop security

Implemented in `desktop/src/main` and covered by unit tests plus manual end-to-end probes (see
[progress.md](progress.md)):

- **Window settings:** `nodeIntegration: false`, `contextIsolation: true`, `sandbox: true`,
  `webSecurity: true`, `webviewTag: false`.
- **Preload bridge:** it exposes `window.hyperdrive.checkBackendHealth()`,
  `window.hyperdrive.auth` (`getStatus`, `signInWithGoogle`, `cancelSignIn`,
  `completeOnboarding({ username })`, `signOut`, `retry`, `onStatusChange`) and
  `window.hyperdrive.storage` (`listAccounts`, `listProviders`, `beginConnection({ provider })`,
  `getConnectionStatus({ transactionId })`, `cancelConnection({ transactionId })`,
  `listFiles({ accountId, directory, continuation })`, `getFileMetadata({ accountId, fileId })`,
  `disconnectAccount({ accountId })`). None of them accepts a URL or returns a token. Raw `ipcRenderer`, filesystem, shell and URL-opening APIs are not
  exposed.
- **IPC handlers:** they answer only the app's own window and main frame, at the trusted renderer
  URL. They reject unexpected arguments; onboarding accepts exactly `{ username: string }`.
- **System browser:** only main-process sign-in and storage code can open it, and only for the
  trusted backend's `/api/v1/auth/desktop/authorize/<uuid>` or
  `/api/v1/storage-connections/{google|dropbox}/authorize/hds_<ticket>` URL, for the provider the
  user chose.
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
