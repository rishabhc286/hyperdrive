# Virtual drive: read-only, with file content (Batches 8 and 9)

HyperDrive can mount a Windows drive (default `H:`) that shows your connected cloud accounts in
Explorer and lets applications **read** their files on demand. Nothing can be written, renamed or
deleted through the drive. Batch 8 added folders and file details; Batch 9 added file content.

> Read-only virtual drive: files are read from your cloud accounts on demand through the local
> agent. Nothing can be written, renamed or deleted here, and files aren't available offline.

**What has actually been tested** (see [Tested matrix](#tested-matrix)): Windows file APIs
(`ReadFile` buffered and non-cached, `CopyFile`, directory listing, attribute queries) from Node.js
and .NET on a real WinFsp mount, through the real agent and VFS, against a **test-only fixture
provider**. **Not tested:** live Google Drive or Dropbox accounts (no OAuth credentials configured),
and no media player, PDF viewer, Office or other application. No compatibility with those is claimed.

## Prerequisites

| Requirement | Why | Check |
| --- | --- | --- |
| Windows 11/10, **x64** | Only architecture built and tested | Settings → System → About |
| **WinFsp** (runtime; plus the **Developer** feature to build) | The user-mode filesystem driver | `C:\Program Files (x86)\WinFsp\bin\winfsp-x64.dll`, and `inc\`, `lib\` for building |
| Visual Studio 2022 Build Tools with **C++ (x64)** and its bundled CMake/Ninja | Builds `hyperdrive-vfs.exe` | `vfs\build.cmd` finds them via `vswhere` |
| Java 21+ and the agent jar | The VFS talks only to the local agent | Batch 5 setup |

HyperDrive never installs WinFsp, drivers or SDKs. Install WinFsp yourself from
<https://winfsp.dev> (its MSI installs a signed kernel driver and the `WinFsp.Launcher` service).

## Build and run (Windows PowerShell)

```powershell
# 1. Native VFS + its unit tests (downloads nlohmann/json source once, pinned by SHA-256)
cd C:\path\to\Hyperdrive\vfs
.\build.cmd                    # -> vfs\build\hyperdrive-vfs.exe; runs ctest

# 2. Agent jar
cd ..\agent; .\mvnw.cmd -q package -DskipTests

# 3. Backend and desktop as usual (docs/live-verification.md for the env variables)
cd ..\desktop; npm run dev
```

The desktop finds `..\vfs\build\hyperdrive-vfs.exe` in development. To use another build, set
`HYPERDRIVE_VFS_EXE` to an **absolute** `.exe` path in the desktop's window. A packaged app has no
bundled VFS yet (no installer) and reports "virtual drive program missing".

**Use it:** sign in → connect an account → **Overview → Virtual drive → Mount** → **Open in
Explorer**. Choose another letter (D–Z) in the card before mounting if `H:` is taken.

### Opt-in checks on a real WinFsp mount (never run by unit tests)

```powershell
# Fixture mode: fixed, labeled test data ("HyperDrive FIXTURE"); no agent, no backend, no cloud.
# Fixture files have no content: reads fail with "not supported".
node vfs\tests\fixture-mount-check.mjs vfs\build\hyperdrive-vfs.exe

# Real agent jar + real VFS against a LOCAL stand-in backend: capability auth, metadata, revocation.
node vfs\tests\agent-mount-check.mjs agent\target\hyperdrive-agent.jar vfs\build\hyperdrive-vfs.exe

# Batch 9: CONTENT through the real agent and VFS with a TEST-ONLY fixture provider.
cd agent
.\mvnw.cmd -q test-compile
.\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/fixture-classpath.txt"
cd ..
node vfs\tests\content-mount-check.mjs agent vfs\build\hyperdrive-vfs.exe
```

`content-mount-check.mjs` starts `com.hyperdrive.agent.FixtureAgentMain` from the agent's **test
classes** (never part of `hyperdrive-agent.jar`): the real `Agent`, with its provider endpoints
pointed at an in-process fixture provider that generates deterministic bytes (no file is held in
memory, so a 5 GiB file costs nothing). The script plays the backend (metadata routes only) and
Electron main (cache key session, mount, credential renewal), mounts the real VFS on a free letter
(Z→I) and checks through Windows file APIs. Its results are **fixture evidence**, not live
Google/Dropbox verification.

## How a read works

```
Application ─ReadFile─► Windows (cache manager may answer from memory)
                          │ non-cached / paging read
                          ▼
                       WinFsp driver ─► hyperdrive-vfs.exe  Read(offset, length)
                                          │ clamp to size, split ≤ 4 MiB
                                          ▼  loopback HTTP, mount capability, opaque hdf_ handle
                                       agent /local/v1/vfs/read
                                          │ ReadService (Batch 6/7): same limits, same checks
                          ┌───────────────┴──────────────────┐
              Dropbox rev:-pinned chunk in the          direct provider range read
              encrypted cache (Batch 7)                 (Google always; Dropbox misses)
                                                             │ delegated access token
                                                             ▼
                                                   Google Drive / Dropbox
```

1. **Mount** (desktop main process): metadata grant at the backend (15 min, renewed every 10 min)
   → mount capability from the agent → `hyperdrive-vfs.exe` with a stdin bootstrap → the
   **credential renewal pump** for that mount generation starts.
2. Explorer lists folders as in Batch 8 (`/local/v1/vfs/accounts|list`).
3. An application opens a file: WinFsp `Open` creates only a local context. **No agent call.**
4. The first `Read` of that open calls `/local/v1/vfs/open {accountId, fileId, expectedSize}`. The
   agent asks the backend for the file's current metadata **by provider id**
   (`GET /api/v1/vfs/accounts/{id}/files/{fileId}`, owner-checked) and refuses folders, native
   documents, shortcuts, unknown sizes and (Dropbox) unknown revisions, and a size different from
   what Windows was shown. It returns an opaque `hdf_` handle.
5. Each read: `/local/v1/vfs/read {handle, offset, length ≤ 4 MiB}` → raw bytes with
   `X-HyperDrive-Read-Offset/-Length/-EOF`. The VFS writes them straight into the WinFsp buffer.
6. `Close` (the final WinFsp call for an open; not Cleanup, after which paging reads can still
   arrive) calls `/local/v1/vfs/close`.
7. When the agent has no usable credential for the account, the read waits (≤ 20 s) while Electron
   main obtains one with the signed-in session and installs it for that mount generation.

Design and alternatives: [ADR 0005](adr/0005-mounted-content-reads.md); metadata design:
[ADR 0004](adr/0004-vfs-transport-and-scoped-metadata-authorization.md).

## Read semantics

| Case | Result |
| --- | --- |
| Offset at or past the end (incl. zero-byte files) | `STATUS_END_OF_FILE`, 0 bytes, no agent call |
| Read extending past the end | Clamped: exactly the remaining bytes, `STATUS_SUCCESS` |
| Read larger than 4 MiB (e.g. non-cached I/O) | Split into ≤ 4 MiB agent reads written into the WinFsp buffer in order |
| Any piece fails | The whole read fails with that status and **0 bytes transferred**; no partial success, no zero-fill, no retry inside the read |
| Answer with a wrong length, offset, type, EOF flag, or a short/oversized body | Rejected (`STATUS_UNEXPECTED_NETWORK_ERROR`) |
| Files over 4 GiB | 64-bit offsets end to end; tested at and beyond 2³² with a 5 GiB fixture |

All offsets and sizes are 64-bit; the clamp happens before any 32-bit length is formed, and the
agent re-checks `offset + length` for overflow. There is **no read-ahead**: the VFS asks only for
what Windows asked for. Windows' cache manager does its own read-ahead and caching for buffered
handles; those reads are real application-driven reads at the WinFsp level.

### Windows' cache vs. the agent's cache

- Windows may answer repeated reads of a buffered handle from memory **without calling the VFS**.
  Agent counters therefore show what reached the agent, not every application read.
- The agent's encrypted cache (Batch 7) answers **Dropbox** reads of the pinned revision; Google
  reads always go to Google. The fixture check forces agent reads with non-cached I/O to observe
  this: a Dropbox re-read showed agent-cache bytes and no provider bytes; a Google re-read showed
  provider bytes and no cache bytes.
- Other software can read files when they are opened. In the fixture run, each non-cached test read
  brought ~128 KiB of additional agent reads, consistent with real-time antivirus scanning on open.
  This means opening a file can cost provider traffic beyond what the application itself reads.

## Versions and consistency

| Provider | Per handle | Limits |
| --- | --- | --- |
| Dropbox | **Pinned** to the revision the backend reported at open (`consistency: dropbox-revision-pinned`); every read uses `rev:<rev>` and the `Dropbox-API-Result` check; cached chunks of that revision may answer | If the revision disappears, reads fail (`STATUS_FILE_INVALID`); never another revision |
| Google Drive | **Not pinned** (`google-unpinned`), never cached; every range is checked against the open-time size via `Content-Range` | A modification that keeps the size is **not detected**: a long-open file could mix old and new bytes. A size change fails the read |

A file that changed between the listing and the first read fails to open (`file_changed`), because
Windows already shows the listing's size. Close and reopen the file after the folder refreshes
(≤ 30 s).

## Authorization

- The VFS holds only the mount capability (`hdv_`) and opaque read handles (`hdf_`, 256-bit random,
  stored by the agent only as SHA-256). It never receives provider tokens, HyperDrive tokens, the
  backend grant, cache keys, credential handles or the agent's launch secret, and it can't name a
  URL, path or provider.
- A handle opens nothing by itself: every read also needs the current capability, the same mount
  generation, and an unexpired backend grant. Handles die with close, unmount, logout, account
  disconnect, a new mount and agent exit. A guessed provider id is checked by the backend against
  the signed-in user's accounts; a guessed handle is unknown (`410 stale_handle`).
- At most **256** open read handles per mount (`STATUS_TOO_MANY_OPENED_FILES` beyond that).

### Credential renewal

The agent needs a delegated provider access token per account (Batch 6). For the drive:

1. A read finds no usable credential (or the provider rejected it once). The agent records one
   request per account and the read waits up to 20 s (inside its 45 s overall deadline). A
   credential with less than 2 minutes left triggers a background renewal while it keeps working.
2. The desktop **main process** long-polls the agent (`/local/v1/vfs/credential-requests`,
   admin secret), calls `POST /api/v1/accounts/{id}/read-credentials` with the signed-in session,
   and installs the token for the same generation (`/local/v1/vfs/mount/credential`). No renderer
   window is involved.
3. If the backend refuses (reconnect needed, no content permission, account gone, session ended),
   the desktop reports it (`/local/v1/vfs/mount/credential-failed`) and waiting reads fail at once.
   Permanent refusals aren't retried until the next mount; transient ones back off 15 s; a token
   rejected again right after a renewal isn't renewed again for 60 s. Permission/scope errors never
   trigger renewal.
4. Unmount, logout, disconnect, agent restart and quit stop the pump first; a credential obtained
   after that is dropped, and the agent refuses installs for an old generation.

The agent never holds a refresh token. Ordinary Disconnect stays local unlinking: an access token
already delegated to the agent is deleted locally, **not revoked at the provider**, and stays valid
at the provider until it expires (≤ 1 h for Google, ≤ 4 h for Dropbox; ADR 0002).

## Errors Windows sees

| Situation | Status |
| --- | --- |
| Missing name / missing parent (lookup) | `STATUS_OBJECT_NAME_NOT_FOUND` / `STATUS_OBJECT_PATH_NOT_FOUND` |
| Any write, create, rename, delete, attribute or security change | `STATUS_MEDIA_WRITE_PROTECTED` |
| Read at/after the end, zero-byte files | `STATUS_END_OF_FILE` |
| Authorization ended, reconnect needed, token rejected, no content permission | `STATUS_ACCESS_DENIED` |
| File gone, changed remotely, revision gone, stale handle | `STATUS_FILE_INVALID` |
| Not readable through the drive (kind, unknown size/revision); fixture-mode files | `STATUS_NOT_SUPPORTED` |
| Agent/backend/provider unreachable, malformed answer | `STATUS_UNEXPECTED_NETWORK_ERROR` |
| Timeout | `STATUS_IO_TIMEOUT` |
| Provider rate limit, agent busy | `STATUS_DEVICE_BUSY` |
| More than 256 open read handles | `STATUS_TOO_MANY_OPENED_FILES` |
| Read cancelled (close, disconnect, unmount in the agent) | `STATUS_CANCELLED` |
| Drive is being unmounted | `STATUS_VOLUME_DISMOUNTED` |
| Directory over the listing limits | `STATUS_INSUFFICIENT_RESOURCES` |

**No retry storms:** permanent failures (unsupported, changed, gone, access denied) are remembered
for the rest of that open and for 10 s per file across opens; a transient open failure is retried
at most once per second per open. The agent's provider reader keeps its Batch 6 bounded retries.

## Limits, blocking and teardown

- WinFsp callbacks **block** (no `STATUS_PENDING`). Bounds: at most **4** content requests from the
  VFS at once (waiting ≤ 10 s for a slot → busy); agent read deadline **45 s** including a renewal
  wait of ≤ 20 s; WinHTTP content receive timeout 55 s. WinFsp's documented minimum `IrpTimeout` is
  60 s (`fsctl.h`). The agent itself allows 8 concurrent reads (4 per account).
- The installed `winfsp.h` documents no cancellation notification for synchronously processed
  requests, so the VFS does not rely on one; an application that cancels its I/O may wait for the
  bounded read to finish.
- No lock is held across network I/O except the per-open mutex that serializes that open's first
  OpenRead. Each open's agent context lives until WinFsp's `Close`.

| Event | What happens to reads |
| --- | --- |
| **Unmount** | The VFS fails new reads (`STATUS_VOLUME_DISMOUNTED`), asks the agent to cancel in-flight ones (`/local/v1/vfs/cancel`), then stops the dispatcher; the agent's revoke drops every handle. Tested: an outstanding read failed with `ECANCELED` and the VFS exited in < 1 s |
| **Sign-out / session expiry** | Same as unmount (before the Batch 6/7 cleanup), plus all delegated credentials cleared |
| **Disconnect an account** | The whole drive is unmounted; that account's credentials and handles are dropped |
| **Agent crash/restart** | In-flight reads fail at once (connection lost); the VFS heartbeat unmounts the drive within ~30 s (`agent-unreachable`). Tested with the fixture |
| **VFS crash** | Windows/WinFsp remove the volume when the process dies. Tested by killing the VFS process |
| **Backend outage** | Opens fail (`STATUS_UNEXPECTED_NETWORK_ERROR`); renewals fail and back off; grant renewal failure unmounts the drive |
| **Provider timeout** | Bounded by the provider reader's timeouts and the 45 s read deadline (`STATUS_IO_TIMEOUT`) |

## Namespace and what is shown

```
H:\
  Google Drive - Personal [1a2b3c4d]\
  Dropbox - Team space [5e6f7a8b]\
     <the account's root folder>
```

- Account directories: provider + "Personal"/"Team space" + an 8-hex suffix derived from the
  connection's id. No emails or display names.
- Windows names are sanitized deterministically and collisions get stable suffixes (table below).
- Files are shown `READONLY | OFFLINE`. `OFFLINE` keeps Explorer from opening files for previews
  and thumbnails (which would download content nobody asked for). Copying a file out keeps the
  read-only attribute on the copy (observed: `ReadOnly, Archive`), as with files copied from other
  read-only media.

| Provider name | Shown as | Rule |
| --- | --- | --- |
| `a:b?c*.txt` | `a_b_c_.txt` | `< > : " / \ | ? *` and control characters → `_` |
| `notes.` / `x  ` | `notes_` / `x__` | trailing dots/spaces → `_` |
| `CON`, `con.txt`, `LPT1.log` | `CON_`, `con_.txt`, `LPT1_.log` | reserved device names get `_` on the base name |
| 300-character name | ≤ 255 UTF-16 units | extension kept, surrogate pairs never split |
| `Report.txt` and `report.TXT` | `Report [45c20762].txt`, `report [45c20a62].TXT` | every member of a case-insensitive collision gets a suffix from its provider id |

| Item | On the drive |
| --- | --- |
| Folders | Directories (size 0, provider modified time when known) |
| Files with a known size | Read-only, offline files with real size; readable on demand |
| Google Docs/Sheets/Slides, shortcuts/links, files without a known size, shared drives | Not shown |

### Directory listings and limits (Batch 8, unchanged)

One directory at a time, complete listings up to **10,000 entries / 100 pages / 30 s / 4
concurrent**, otherwise an explicit failure; the VFS caches snapshots for 30 s (512 directories)
and serves WinFsp continuation markers from one stable snapshot per open directory handle.

## Desktop card

**Mounted as H: — read-only virtual drive**, the notice above, **Open in Explorer**, **Unmount**,
and the agent's real counters since it started: open files, reads by the drive, bytes returned to
Windows, bytes downloaded from the cloud, bytes from the local encrypted cache, plus a safe message
for the last read problem (reconnect needed, no content permission, session ended, network,
timeout, rate limited, file changed, unsupported, not found). No file names, ids or bytes.

## Tested matrix

| What | How | Result |
| --- | --- | --- |
| Listing, sizes > 4 GiB, full read + SHA-256, CopyFile + SHA-256 | Node.js `fs` (CreateFile/ReadFile/CopyFile) on a real mount, fixture provider | Pass |
| Nonzero offsets, 25 random reads, cross-chunk, final partial, EOF | Node.js positional reads | Pass |
| 2³² boundary, beyond 4 GiB, last bytes of 5 GiB, EOF | Node.js positional reads | Pass |
| 12 MiB non-cached read (split into 4 MiB agent reads), non-cached EOF reads | .NET `ReadFile` with `FILE_FLAG_NO_BUFFERING` | Pass |
| 6 concurrent readers | Node.js, separate handles | Pass |
| Dropbox agent-cache hit, Google bypass | Agent counters around non-cached reads | Pass |
| Create, write-open, delete, rename, mkdir rejected | Node.js `fs` | Pass |
| Unmount with an outstanding read, VFS killed, agent killed during a read | Node.js + process control | Pass |
| Google Drive / Dropbox live | — | **Not run** (no OAuth credentials) |
| Explorer double-click, media players, PDF viewers, Office, memory-mapped workloads, byte-range locks | — | **Not run**; no compatibility claimed |

## Not implemented / limitations

- No writes, uploads, renames, deletes, prefetching, offline pinning, native document export or
  shortcut traversal. No installer, service or auto-mount.
- Disconnecting any account unmounts the whole drive.
- Google: same-size remote modifications during an open read are not detected (see above).
- Blocking callbacks: a slow provider holds a WinFsp dispatcher thread for up to 45 s; with many
  slow reads, other operations on the drive wait.
- Cancellation of an individual application read isn't propagated to the agent; teardown is.
- Explorer shows files as offline; some applications may treat offline files differently (untested).
- **Not verified live:** mounting and reading real Google Drive or Dropbox accounts.
