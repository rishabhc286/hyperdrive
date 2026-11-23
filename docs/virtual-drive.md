# Virtual drive (Batch 8): read-only, metadata only

HyperDrive can mount a Windows drive (default `H:`) that shows your connected cloud accounts,
their folders and file details in Explorer. **File contents can't be read through the drive yet**
(Batch 9). The desktop's own browsing and **Verify file read** keep working as before.

> Folders and file details are available. Reading cloud file contents through this drive will be
> added in the next batch.

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

# 2. Agent jar (if not built yet)
cd ..\agent; .\mvnw.cmd -q package -DskipTests

# 3. Backend and desktop as usual (docs/live-verification.md for the env variables)
cd ..\desktop; npm run dev
```

The desktop finds `..\vfs\build\hyperdrive-vfs.exe` in development. To use another build, set
`HYPERDRIVE_VFS_EXE` to an **absolute** `.exe` path in the desktop's window. A packaged app has no
bundled VFS yet (no installer in this batch) and reports "virtual drive program missing".

**Use it:** sign in → connect an account → **Overview → Virtual drive → Mount** → **Open in
Explorer**. Choose another letter (D–Z) in the card before mounting if `H:` is taken.

### Opt-in checks on a real WinFsp mount (never run by unit tests)

```powershell
# Fixture mode: fixed, labeled test data ("HyperDrive FIXTURE"); no agent, no backend, no cloud.
node vfs\tests\fixture-mount-check.mjs vfs\build\hyperdrive-vfs.exe

# Real agent jar + real VFS in normal mode against a LOCAL stand-in backend (not the real backend,
# not a provider): capability auth, grant-scoped metadata, revocation-driven unmount.
node vfs\tests\agent-mount-check.mjs agent\target\hyperdrive-agent.jar vfs\build\hyperdrive-vfs.exe
```

Both pick a free drive letter (Z→I), verify through real Windows file APIs and unmount.
`hyperdrive-vfs.exe --fixture-mount <letter>` is the manual fixture entry point; the desktop never
uses it.

## How it works

```
Explorer ─► WinFsp driver ─► hyperdrive-vfs.exe ──(loopback HTTP, mount capability)──► agent
                                                                                    │ metadata grant
                                                                                    ▼
                                                                    backend /api/v1/vfs/** ─► Google / Dropbox
```

1. **Mount** (desktop main process):
   1. issues a **metadata grant** at the backend with the signed-in session (15 min, renewed every
      10 min while mounted);
   2. gives it to the agent (`POST /local/v1/vfs/mount`, launch secret), which returns a new
      **mount capability** and generation;
   3. starts `hyperdrive-vfs.exe` (no shell, no arguments) and writes one bootstrap line to its
      stdin: protocol version, agent port, capability, drive letter.
2. The VFS proves the capability (`/local/v1/vfs/session`), checks the letter is free, creates the
   read-only volume, mounts it, and reports **mounted** only after Windows shows the drive.
3. Explorer requests become agent calls: `/local/v1/vfs/accounts` (root) and `/local/v1/vfs/list`
   (one directory). The agent calls the backend's two grant-scoped routes and returns metadata.

See [ADR 0004](adr/0004-vfs-transport-and-scoped-metadata-authorization.md) for the design and the
alternatives.

## States

The desktop shows exactly what the native process confirmed:

| Card state | Meaning |
| --- | --- |
| Not available on this PC | WinFsp missing, VFS not built/configured, agent not running, or not Windows x64 |
| Sign in / Connect an account first | Nothing to mount |
| Ready to mount as H: | Can mount now |
| Mounting H:… | Progress: drive process started → connected to the agent → volume created |
| **Mounted as H: — metadata only** | Only after the VFS reported `mounted` (Windows sees the drive) |
| Unmounting… | Stopping the owned process, revoking the capability and grant |
| Not mounted + reason | Drive letter in use, startup timeout, unexpected answer, process exited, authorization lost, agent unavailable, … |

Native events (stdout, one JSON object per line, never secrets):
`started` → `agent-authenticated` → `volume-created` → `mounted` → … → `unmounted{reason}`, or
`failed{code}`. Anything unexpected (unknown event, wrong order, wrong letter, protocol mismatch,
fixture mode, oversized line) stops the process as an invalid handshake. Startup has a 30 s limit.

## Lifecycle and cleanup

| Event | What happens |
| --- | --- |
| **Unmount** | Desktop writes `unmount` to the VFS stdin → it stops the dispatcher and deletes the volume (removes the drive letter) → agent revokes the capability → backend revokes the grant |
| **Sign-out / session expiry** | Same as unmount, before the Batch 6/7 cleanup |
| **Disconnect an account** | **The whole drive is unmounted** (simplest safe behavior); mount again to see the remaining accounts |
| **Agent restart or failure** | The capability lived only in the old agent; the desktop tears down the drive. The VFS also detects it itself (below) |
| **Capability revoked / agent gone while mounted** | The VFS heartbeat (every 10 s) gets 401 → unmounts itself (`authorization-lost`); 3 failed heartbeats → `agent-unreachable`. Requests in between fail with access-denied/timeout errors |
| **Grant renewal fails** (session ended, backend down) | Desktop unmounts and shows why |
| **App quit** | No new mounts; unmount, revoke, then the agent stops |
| **Desktop crash** | The VFS's stdin closes → it unmounts by itself (`parent-closed`). Not tested with a real Electron crash |
| **VFS crash** | Windows/WinFsp removes the volume when the process dies; the desktop marks the drive failed. Not tested with a real crash |

Only the owned child is ever terminated (if it ignores `unmount` for 10 s). Nothing is killed by
name, and other WinFsp filesystems are never touched.

## Namespace

```
H:\
  Google Drive - Personal [1a2b3c4d]\
  Dropbox - Team space [5e6f7a8b]\
     <the account's root folder>
```

- Account directories: provider + "Personal"/"Team space" + an 8-hex suffix derived from the
  connection's id. No emails or display names.
- Identities stay separate: the virtual root and account directories have no provider id; folders
  and files carry their provider id and (folders) the opaque directory locator. Lookups resolve by
  walking snapshots (Windows name → provider id), never by sending a Windows name to a provider.

### Windows name mapping (deterministic)

| Provider name | Shown as | Rule |
| --- | --- | --- |
| `a:b?c*.txt` | `a_b_c_.txt` | `< > : " / \ | ? *` and control characters → `_` |
| `notes.` / `x  ` | `notes_` / `x__` | trailing dots/spaces → `_` |
| `CON`, `con.txt`, `LPT1.log` | `CON_`, `con_.txt`, `LPT1_.log` | reserved device names (incl. COM¹²³) get `_` on the base name |
| `` (empty) | `_` | |
| 300-character name | ≤ 255 UTF-16 units | extension kept, surrogate pairs never split |
| `Report.txt` and `report.TXT` | `Report [45c20762].txt`, `report [45c20a62].TXT` | **every** member of a case-insensitive collision gets a suffix from its provider id |
| Two files both named `Same` | `Same [….]`, `Same [….]` | same rule; nothing is dropped or overwritten |

Suffixes come from a stable hash of the provider id, so the result doesn't depend on page order.
A name gains or loses its suffix when a colliding sibling appears or disappears. Case-insensitive
comparison uses invariant uppercase/ordinal rules, close to but not guaranteed identical to NTFS's
table for every exotic character.

### What is shown

| Item | On the drive |
| --- | --- |
| Folders | Directories (size 0, provider modified time when known) |
| Files with a known size | Read-only, **offline** files (real size and modified time; unknown time shows as blank) |
| Google Docs/Sheets/Slides (native documents) | **Not shown** (not ordinary readable files) |
| Shortcuts / links | **Not shown** (no shortcut traversal) |
| Files without a known size | **Not shown** |
| Shared drives / other team roots | Not shown (only the connection's root, as in the app) |

Exclusions are counted per listing (not logged with names). `FILE_ATTRIBUTE_OFFLINE` tells Explorer
the content isn't local, so it doesn't open files for previews or thumbnails.

## Directory listings and limits

- The agent reads **one directory** at a time from the backend, page by page (200 per page), and
  returns it complete. Limits: **10,000 entries**, **100 pages**, **30 s**, **4 concurrent
  listings**; beyond them the listing **fails** (`directory_too_large`, `timeout`, `too_busy`) and
  Explorer shows an error. Nothing is truncated silently.
- Provider pagination is not WinFsp continuation. The VFS sorts the complete listing
  case-insensitively and serves Explorer's `ReadDirectory` calls from that snapshot with WinFsp
  markers (`.` and `..` first, then names strictly after the marker; end marker when done).
- **Stable enumeration:** an open directory handle keeps one snapshot from its first read until it
  is cleaned up or rewound, so continuation never mixes two versions.
- **Cache:** snapshots are reused for 30 s (at most 512 directories). The VFS fetches at most 4
  directories at once (waiting up to 15 s for a slot) and holds no lock during network calls.
- **Blocking:** WinFsp callbacks block while a listing is fetched (bounded by the transport's
  timeouts: 2 s connect, 35 s receive). The VFS does not use WinFsp's asynchronous
  `STATUS_PENDING` completion.
- **Staleness:** what Explorer shows can be up to ~30 s old (plus Windows' own 1 s file-info cache).
  If a folder changes while the agent is paginating, the provider may skip or repeat entries;
  repeats are dropped by provider id; the listing is not a point-in-time snapshot of the cloud.

## Errors Windows sees

| Situation | Status |
| --- | --- |
| Missing name / missing parent | `STATUS_OBJECT_NAME_NOT_FOUND` / `STATUS_OBJECT_PATH_NOT_FOUND` |
| Any write, create, rename, delete, attribute or security change | `STATUS_MEDIA_WRITE_PROTECTED` (read-only volume) |
| Reading a non-empty file's content | `STATUS_NOT_SUPPORTED`, immediately (never zeros, never a fake success) |
| Reading at/after the end of a file (incl. zero-byte files) | `STATUS_END_OF_FILE` |
| Capability revoked, grant rejected, needs reconnect | `STATUS_ACCESS_DENIED` |
| Agent/backend unreachable / timeout / busy | `STATUS_UNEXPECTED_NETWORK_ERROR` / `STATUS_IO_TIMEOUT` / `STATUS_DEVICE_BUSY` |
| Directory over the limits | `STATUS_INSUFFICIENT_RESOURCES` |

Volume size and free space are reported as 0 (no capacity concept is invented).

## Security summary

- The VFS never receives provider tokens, HyperDrive session/refresh tokens, cache keys or the
  agent's launch secret. Its capability opens only `/local/v1/vfs/session|accounts|list`, which
  return metadata. There is no URL, path or proxy parameter anywhere.
- The capability (`hdv_`, 256-bit) is created per mount, stored only as a hash in the agent's
  memory, sent only on the VFS's stdin, and dies on unmount, logout, agent restart or a new mount.
- The backend grant (`hdm_`, 256-bit, hashed at rest) authorizes only `GET /api/v1/vfs/accounts`
  and `GET /api/v1/vfs/accounts/{id}/files` for its session's user, expires after 15 minutes and
  ends with the session. It can't delegate credentials, manage accounts or call any other API.
- Security descriptor: read/execute for everyone, no write access; the volume is read-only.
- Diagnostics on stderr contain only status numbers and fixed error codes.

## Not implemented / limitations

- Content reads, playback, prefetching, cache use through the drive (Batch 9).
- No installer, service or auto-mount; the drive exists only while the desktop runs and you
  clicked Mount.
- Disconnecting any account unmounts the whole drive.
- Shortcuts, native documents, shared drives are not shown.
- Explorer may show the files as offline and refuse previews: that is intended.
- **Not verified live:** mounting real Google Drive or Dropbox accounts (no OAuth credentials on
  the development machine). Verified: a real WinFsp mount with fixture data, and the real agent +
  VFS chain against a local stand-in backend (see [progress.md](progress.md)).
- Crash cleanup (desktop or VFS killed abruptly) is designed for but not tested.
