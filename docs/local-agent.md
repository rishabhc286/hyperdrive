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
| Future native VFS | This API is a control/status plane only. A C++ WinFsp client will need its own authenticated, high-throughput channel and a credential/delegation design ([ADR 0001](adr/0001-local-agent-transport-and-content-boundary.md)). No content endpoints exist. |

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
| `GET /local/v1/status` | `protocolVersion`, `agentVersion`, `instanceId`, `readiness` (`READY`), `startedAt`, `uptimeMillis`, `capabilities` (`providerReads`/`rangeReads` `true`, `chunkCache`/`virtualDrive` `false`), `lastStartupError` (`null`) |
| `POST /local/v1/credentials` | Install a delegated credential: `{provider, accountId, accessToken, providerExpiresAt, rootNamespaceId?}` → `201 {handle, retentionExpiresAt, providerExpiresAt}` |
| `POST /local/v1/credentials/clear` | `{accountId?}` (omit for all) → `{cleared}`; cancels that account's in-flight reads |
| `POST /local/v1/reads` | One bounded read (below) → `200 application/octet-stream`, or a JSON `{code}` error |

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

## Not implemented

- The chunk cache and cache encryption, prefetching, full-file downloads and playback.
- Writes and staging.
- WinFsp and drive mounting.
- A Windows service, auto-start at login, an installer, and a bundled JRE.
- Background sync and metrics.
