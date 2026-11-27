# Local agent (Batch 5)

The HyperDrive local agent is a separate **Java 21** process on the user's PC. The desktop app
starts and owns it, talks to it over an authenticated loopback API, and shows its real status.

> **What exists today:** the process, its lifecycle and a status/health API. Nothing else.
>
> The agent does **not** read files, talk to Google Drive or Dropbox, hold provider credentials,
> cache anything, or create a drive. Its status reports `providerReads`, `rangeReads`,
> `chunkCache` and `virtualDrive` all as `false`.
>
> Cloud browsing goes through the backend and works whether or not the agent is running.

## Design note

| Topic | Decision |
| --- | --- |
| Process model | **One** agent child process per desktop instance, spawned by the Electron main process as `java -jar hyperdrive-agent.jar` (`shell:false`, argv array, hidden window). Electron's single-instance lock plus the agent's data-directory lock prevent duplicates. |
| Transport | HTTP/JSON on `127.0.0.1`, OS-assigned port, `/local/v1/...`. It keeps the Electron side small (Node's `fetch`) with no native gRPC tooling, and is enough for status traffic. **The future VFS data-plane transport is an open decision**; see [ADR 0001](adr/0001-local-agent-transport-and-content-boundary.md). |
| Local authentication | A fresh 256-bit secret per launch, made by Node's `crypto.randomBytes`. It is sent once over the child's **stdin**, and every request must carry `Authorization: Bearer <secret>`, compared in constant time. It never appears in arguments, the environment, files, logs, the handshake or the renderer. |
| Startup handshake | The agent writes exactly one JSON line to **stdout**: `{"type":"ready","protocolVersion":1,"port":…,"instanceId":…,"agentVersion":…}` or `{"type":"error","code":…}`. stdout carries nothing else; logs go to stderr. |
| Ownership and shutdown | stdin stays open as the **parent-lifetime channel**. Electron sends `{"type":"shutdown"}` and closes stdin, waits up to 5 s, then kills **only the `ChildProcess` it spawned**. If Electron dies, stdin closes and the agent exits by itself. |
| Data directory | `<Electron userData>/agent`, computed by Electron and passed in the bootstrap. The agent creates it and takes an exclusive OS lock on `agent.lock`. No cache, no log files, nothing else is written. |
| Java discovery (development) | `HYPERDRIVE_AGENT_JAVA` (absolute path) → `JAVA_HOME\bin\java.exe` → `java` on `PATH`. Then a `java -version` preflight requires **≥ 21**. Nothing is downloaded or installed. |
| Production packaging | **Not done.** A packaged app has no bundled agent or JRE and reports `not-packaged` (unless `HYPERDRIVE_AGENT_JAR` is set). Bundling a runtime is installer work; a permanent user-installed Java is **not** the intended end-user requirement. |
| Native VFS (Batches 8/9) | The C++ WinFsp process calls **capability-scoped** routes (below) over the same loopback server: metadata (Batch 8) and file content through agent-owned read handles (Batch 9). See [ADR 0004](adr/0004-vfs-transport-and-scoped-metadata-authorization.md) and [ADR 0005](adr/0005-mounted-content-reads.md). |

## Build and run (Windows PowerShell)

```powershell
cd C:\path\to\Hyperdrive\agent
.\mvnw.cmd verify          # unit + process tests, then builds the jar
# artifact: agent\target\hyperdrive-agent.jar

cd ..\desktop
npm run dev                # or: npm run build; npm start
```

- **Automatic start:** the desktop app starts the agent when it launches, whatever the sign-in
  state, and shows it under **Overview → Local agent** and **Settings → About this build**.
- **Not standalone:** the agent isn't meant to be run by hand. Started without the bootstrap
  message, it waits 10 s and then exits with `bootstrap_timeout`. Started with any
  command-line argument, it exits immediately.

### Configuration (Electron main process environment)

| Variable | Default | Meaning |
| --- | --- | --- |
| `HYPERDRIVE_AGENT_JAVA` | unset | Absolute path to a `java.exe` to use. If set but wrong, the agent reports `java-not-found`; there's no silent fallback. |
| `JAVA_HOME` | (system) | Used when `HYPERDRIVE_AGENT_JAVA` isn't set. |
| `HYPERDRIVE_AGENT_JAR` | `<repo>\agent\target\hyperdrive-agent.jar` (development only) | Absolute path to the agent jar. |
| `HYPERDRIVE_AGENT_LOG_LEVEL` | `INFO` | `ERROR`, `WARN`, `INFO` or `DEBUG` (agent stderr). |

**Fixed timeouts:**
- Startup: 20 s, covering the Java check, spawn, handshake and health check.
- Requests: 5 s.
- Graceful stop: 5 s before the child is killed.
- Bootstrap wait inside the agent: 10 s.

## Local API (protocol version 1)

Every endpoint requires the bearer secret and returns `Cache-Control: no-store`.

| Endpoint | Response |
| --- | --- |
| `GET /local/v1/health` | `{"status":"UP"}` |
| `GET /local/v1/status` | `protocolVersion`, `agentVersion`, `instanceId`, `readiness` (`READY`), `startedAt`, `uptimeMillis`, `capabilities` (`providerReads`/`rangeReads`/`chunkCache` `true`, `virtualDrive` `false`), `lastStartupError` (`null`) |
| `POST /local/v1/credentials` | Install a delegated credential: `{provider, accountId, accessToken, providerExpiresAt, rootNamespaceId?}` → `201 {handle, retentionExpiresAt, providerExpiresAt}` |
| `POST /local/v1/credentials/clear` | `{accountId?}` (omit for all) → `{cleared}`; cancels that account's in-flight reads |
| `POST /local/v1/reads` | One bounded read (below) → `200 application/octet-stream`, or a JSON `{code}` error |
| `GET /local/v1/cache/status` | Cache enabled/disabled reason, budget, encrypted bytes on disk, temp bytes, entry count, process-lifetime counters, bypass counts, last cleanup. No paths, keys or provider IDs. |
| `POST /local/v1/cache/session` | `{userId, key (base64, 32 bytes), fresh, budgetBytes}`: activate a user's cache key (memory only) |
| `POST /local/v1/cache/session/end` | `{userId}` → `{removed, failed}`: logout purge of that user's chunks; forgets the key |
| `POST /local/v1/cache/budget` | `{budgetBytes}`: `0` or 256 MiB–1 TiB |
| `POST /local/v1/cache/clear` | `{accountId?}` → `{removed, failed}`: this user's chunks, or one account's |

POST bodies must be `application/json`, have a `Content-Length` of at most 16 KiB, and contain
only the listed fields. A refresh token or any other unknown field is rejected with `400`.

**Rejected requests:**

| Request | Response |
| --- | --- |
| Missing or wrong secret | `401` |
| `Host` header other than `127.0.0.1:<port>` | `403` |
| Any `Origin` header | `403` |
| Wrong method for a path | `405` |
| A body on GET, a non-JSON or oversized POST body, chunked POST | `400` |
| A query string | `400` |
| Any other path | `404` |

**What the API never offers:** shutdown, configuration writes, local files, environment, paths,
arbitrary URLs or proxying, token read-back, or shell execution. The provider endpoints are
fixed in code; only in-process tests can substitute them.

## Bounded read contract (Batch 6)

This is an **application contract**, not HTTP `Range` passthrough.

**Request** `POST /local/v1/reads`:
`{ handle, fileId, offset, length, expectedSize, expectedRevision? }`
- `handle`: the opaque `hdh_…` value from `/credentials`, which selects the account and provider.
- `fileId`: a Drive ID (`[A-Za-z0-9_-]{1,256}`) or a Dropbox `id:…`. Never a path or URL.
- `offset ≥ 0`, `0 < length ≤ 4,194,304`. `offset + length` overflow is rejected.
- `expectedSize`: the size from the listing. `expectedRevision`: Drive `headRevisionId` or
  Dropbox `rev`.

**Semantics:**
- **Clamping:** the agent requests `Range: bytes=offset-min(offset+length, expectedSize)-1`. A
  range that runs past EOF is clamped to the file end (a short read, `X-HyperDrive-EOF: true`).
- **At EOF:** `offset ≥ expectedSize`, or a zero-byte file, returns **0 bytes** with `EOF: true`
  and makes **no provider call**. HTTP would answer 416; this contract doesn't.
- **Success:** `200 application/octet-stream`, with exactly `X-HyperDrive-Read-Length` bytes plus
  `X-HyperDrive-Read-Offset`, `-EOF`, `-Revision-Pinned` and `-Revision` (when known).
  Never base64 or JSON.
- **Changed file:** a provider-reported total size different from `expectedSize`, a 416 inside
  the expected size, or a different Dropbox `rev` all return `409 file_changed`. Partial data is
  never returned.
- **Revision pinning:**
  - Dropbox reads with `expectedRevision` use `rev:<rev>` and are pinned. A revision that no
    longer exists returns `not_found`, never the current content.
  - Google ranged reads are **not** pinned: Drive serves its current content, and only the size is
    checked.
  - No read is labeled safe for caching yet.
- **Cancellation:** clearing the credential (logout, disconnect, shutdown) cancels its in-flight
  reads (`cancelled`).

**Provider transport:**
- **Connection:** fixed HTTPS endpoints, redirects **never followed** (`redirect_refused`, so
  `Authorization` can't leak to another origin), HTTP/1.1, `Accept-Encoding: identity`
  (compressed bodies rejected), and a 5 s connect and 30 s request timeout.
- **Status 206:** `Content-Range` must be exactly `bytes start-end/total` for the asked range,
  and the body must be exactly that long (`truncated_response` / `invalid_provider_response`).
  Bodies are read with a hard cap even without `Content-Length`.
- **Status 200 (Range ignored):** accepted **only** when the request covered the whole file (offset 0
  through EOF, so at most 4 MiB) and `Content-Length` matches. Otherwise the connection is closed
  and the read fails with `range_not_supported`. The agent never downloads a whole large file.
- **Errors:**
  - `401` → `credential_rejected`; Dropbox `missing_scope` → `permission_missing`.
  - Google `fileNotDownloadable` → `unsupported_file`; other `403`s → `forbidden` (a 403 does not
    mean expiry).
  - `404` or Dropbox `path/not_found` → `not_found`.
  - `429` and Google rate-limit 403s → retried, then `rate_limited` with `retryAfterSeconds`.
  - `5xx` → retried, then `unavailable`; a timeout → `timeout`.
  - Retries: at most 3 attempts, waits capped at 2 s.

**Limits:**
- At most 8 concurrent reads in total and 4 per account, beyond which the read fails with
  `too_busy`.
- At most 32 credentials, retained ≤ 20 min and never past the provider expiry minus 15 s.
- No disk persistence of credentials or bytes.

## Lifecycle as seen by the app

`stopped → starting → ready`, plus `failed`, `restarting` and `stopping`. Ready requires all of:
1. A valid handshake.
2. Protocol version 1.
3. A port between 1024 and 65535.
4. A successful **authenticated** health check against `http://127.0.0.1:<port>`. Electron builds
   that URL itself; the child never supplies a URL or a host.

**Restarts:**
- **Unexpected exit while ready:** up to **2 automatic restarts in 10 minutes** (after 1 s,
  then 5 s), then `failed`.
- **Manual Restart:** always possible from the UI, and duplicate clicks are deduplicated.
- **Failed startups:** never retried automatically.

**Error codes and what the UI tells you:**

| Code | Meaning | What to do |
| --- | --- | --- |
| `java-not-found` | No Java at the configured or found location | Install JDK 21+, set `JAVA_HOME` or `HYPERDRIVE_AGENT_JAVA`, Restart |
| `java-incompatible` | Java < 21, or `java -version` was unreadable | Point to Java 21+ |
| `artifact-missing` | The jar doesn't exist | `cd agent; .\mvnw.cmd package` |
| `not-packaged` | Packaged build without a bundled agent | Future installer work |
| `startup-timeout` | No handshake within 20 s | Check the main-process console |
| `protocol-mismatch` / `handshake-invalid` / `bootstrap-rejected` | The agent and the desktop disagree | Rebuild the agent from the same checkout |
| `data-dir-locked` | Another agent owns `<userData>\agent` | Close the other HyperDrive instance or process |
| `exited-before-ready` / `spawn-failed` / `health-failed` | Startup failed | Check the main-process console (the last 10 redacted stderr lines are printed there) |
| `unexpected-exit` | A ready agent died | Automatic restart (bounded), or Restart |

## Logging

- **Agent:** single-line logs to **stderr** only. Messages are fixed text, the bootstrap input is
  never echoed, and the secret is redacted from every line as a second safeguard.
- **Electron:** keeps the last **50** stderr lines (each ≤ 500 characters, secret redacted) in
  memory only. It prints the last 10 to the main-process console when the agent fails, and never
  sends them to the renderer.
- **No log files:** there's no log-file retention to manage, and there's no "send diagnostics"
  upload.

## Loopback threat model (honest scope)

**Protects against:**
- Other users and other machines: it binds to 127.0.0.1 only, never 0.0.0.0.
- Web pages in a browser: `Origin` and wrong-`Host` requests are rejected and no CORS headers are
  sent, which also guards against DNS rebinding.
- Local programs that merely find the port: they don't have the per-launch secret.
- Stale or duplicate agents: they're refused by the data-directory lock.

**Does not protect against:**
- Malware already running as the **same Windows user**. It could read process memory, attach a
  debugger or inject into Electron or Java. That's inherent to any desktop app; the secret raises
  the bar but isn't a sandbox.
- The secret in memory: it lives in the memory of both processes for the lifetime of a launch.

## Orphan-process behavior

- **Normal quit:** stop, and the agent exits. Verified on Windows 11.
- **Electron killed abruptly** (Task Manager): the stdin pipe closes and the agent exits within
  about a second. Verified on Windows 11.
- **Remaining limitation:** if the agent itself hangs in a way that stops it reading stdin, only
  Electron's kill of its own child stops it. If Electron is also gone, that agent would linger
  until the user ends it; it still holds the data-directory lock, so a new agent then reports
  `data-dir-locked`. No Windows Job Object is used yet.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "Java 21 or newer wasn't found" | Check `java -version` in a new PowerShell window, or set `$env:JAVA_HOME` / `$env:HYPERDRIVE_AGENT_JAVA` before `npm run dev`. |
| "The agent hasn't been built yet" | `cd agent; .\mvnw.cmd package` |
| "Another HyperDrive agent is already using…" | Close other HyperDrive windows. If needed, find the leftover agent with `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` and end only the one whose command line contains `hyperdrive-agent.jar`. |
| Agent never becomes ready | Look at the Electron main-process console for `[hyperdrive-agent]` lines. |

### Cache headers on reads (Batch 7)

Successful reads also carry:
- `X-HyperDrive-Cache`: `hit`, `miss`, `partial`, `bypass` or `none`.
- `X-HyperDrive-Cache-Bypass`: why the cache wasn't used (`provider_not_version_bound`,
  `version_unknown`, `cache_key_unavailable` or `cache_disabled`).
- `X-HyperDrive-Cache-Not-Admitted`: why fetched bytes weren't stored (`revision_unconfirmed`,
  `cache_full`, `cache_invalidated`, `cache_error` or `cache_disabled`).
- `X-HyperDrive-Provider-Bytes` and `X-HyperDrive-Cache-Bytes`.

**Fetch size:** eligible misses fetch whole aligned 4 MiB chunks (at most two per read), while
bypassed reads fetch exactly the requested range. Details: [cache.md](cache.md).

## Virtual-drive routes (Batch 8)

Two separate credentials, neither accepted on the other's routes:

| Route | Credential | Purpose |
| --- | --- | --- |
| `POST /local/v1/vfs/mount` | launch secret | `{userId, backendOrigin, grant, grantExpiresAt}` → `201 {generation, capability}`. Replaces any previous mount. `backendOrigin`: https, or http to 127.0.0.1/localhost/[::1]; origin only. `grant`: `hdm_…`, unexpired. |
| `POST /local/v1/vfs/mount/grant` | launch secret | `{generation, grant, grantExpiresAt}`: renew the backend grant of the current mount (404 for an old generation) |
| `POST /local/v1/vfs/mount/revoke` | launch secret | `{}` → `{revoked}` |
| `GET /local/v1/vfs/status` | launch secret | `{active, generation}` |
| `POST /local/v1/vfs/session` | mount capability | `{}` → `{protocolVersion: 1, generation}` (VFS hello/heartbeat) |
| `POST /local/v1/vfs/accounts` | mount capability | `{}` → the user's accounts (`id, provider, rootType, status`) |
| `POST /local/v1/vfs/list` | mount capability | `{accountId, directory?}` → one complete directory listing (`id, name, kind, sizeBytes, modifiedMillis, directoryLocator`) |

- **Capability:** `Authorization: Bearer hdv_<43 chars>`, 256-bit, stored as a SHA-256 hash in
  memory, constant-time comparison, dies on revoke, a new mount, or agent restart. Never logged.
- **Backend calls:** only `GET {origin}/api/v1/vfs/accounts` and
  `GET {origin}/api/v1/vfs/accounts/{id}/files` with the grant; HTTP/1.1, no redirects, 5 s
  connect, 20 s per request, 2 MiB per page, strict parsing. No URL or path ever comes from the
  VFS.
- **Listing limits:** 10,000 entries, 100 pages, 30 s, 4 concurrent listings; exceeding them
  answers `413 directory_too_large`, `504 timeout` or `503 too_busy`. Repeated ids across pages
  are dropped once. The agent keeps no snapshot after answering.
- **Codes passed to the VFS:** `authorization_expired`/`authorization_lost` (401), `forbidden`,
  `not_found`, `needs_reconnect` (409), `rate_limited`, `backend_unavailable`,
  `invalid_backend_response`.
- Capability `virtualDrive` is `true` (metadata support).

## Mounted content routes (Batch 9)

| Route | Credential | Purpose |
| --- | --- | --- |
| `POST /local/v1/vfs/open` | mount capability | `{accountId, fileId, expectedSize}` → `201 {handle, sizeBytes, consistency, revisionPinned}`. Validates the file with the backend (by provider id); refuses non-files, unknown size, Dropbox without revision (`415 unsupported_file`) and a size different from `expectedSize` (`409 file_changed`); `503 too_many_open_files` beyond 256 |
| `POST /local/v1/vfs/read` | mount capability | `{handle, offset, length ≤ 4 MiB}` → `200 application/octet-stream` with `Content-Length`, `X-HyperDrive-Read-Offset`, `-Read-Length`, `-EOF`, `-Cache`. Same contract as `/local/v1/reads` (EOF, clamping, `file_changed`) |
| `POST /local/v1/vfs/close` | mount capability | `{handle}` → `{closed}`; cancels that handle's in-flight reads |
| `POST /local/v1/vfs/cancel` | mount capability | `{}` → `{cancelled}`; cancels every in-flight read of the mount (unmounting) |
| `POST /local/v1/vfs/credential-requests` | launch secret | `{generation}` → `{requests:[{accountId}]}`, long poll ≤ 25 s; `404` for an old generation |
| `POST /local/v1/vfs/mount/credential` | launch secret | `{generation, provider, accountId, accessToken, providerExpiresAt, rootNamespaceId?}` → `201 {installed, retentionExpiresAt}`; `404` old generation, `409 no_pending_request` |
| `POST /local/v1/vfs/mount/credential-failed` | launch secret | `{generation, accountId, code}` with code ∈ `needs_reconnect, content_permission_missing, account_not_found, session_expired, backend_unavailable, rate_limited, agent_rejected, cancelled` |

- **Contexts:** `vfs/VfsReadService`. Handles are `hdf_` + 43 base64url characters, stored as
  SHA-256; no idle expiry; ended by close, revoke, a new mount, `credentials/clear` for the account
  (disconnect) and shutdown. Reads re-check the generation and the grant expiry
  (`401 authorization_expired`), and are cancelled after **45 s** (`504 timeout`).
- **Renewal:** `vfs/CredentialBroker` keeps one pending request per account and generation; readers
  wait ≤ 20 s. Permanent failures stick until the next mount; transient ones back off 15 s; a
  rejected token is renewed at most once per 60 s. Credentials are looked up per account in the
  same `CredentialRegistry` as Batch 6 reads (≤ 20 min retention).
- **Backend:** one more fixed route, `GET {origin}/api/v1/vfs/accounts/{id}/files/{fileId}`
  (grant), strictly parsed; its `id` must equal the requested id.
- **Concurrency:** the HTTP pool has 16 workers (8 reads, 4 per account, plus metadata, close,
  cancel, the long poll and status). `GET /local/v1/vfs/status` adds `reads`: `openHandles, opens,
  reads, bytesReturned, providerBytes, cacheBytes, failures{code→count}, lastError, renewals{...}`.
- Capability `virtualDriveContent` is `true`.
- **Test-only entry point:** `FixtureAgentMain` (agent **test** sources) runs this agent against a
  deterministic fixture provider for `vfs/tests/content-mount-check.mjs`; it is not in the jar.

## Not implemented

- Prefetching, full-file downloads and playback.
- Writes and staging.
- A Windows service, auto-start at login, an installer, and a bundled JRE.
- Background sync and metrics.
