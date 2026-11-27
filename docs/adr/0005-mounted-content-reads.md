# ADR 0005: File content through the mounted drive (agent-owned read handles)

Status: accepted (Batch 9). Builds on ADR 0001 (agent transport), 0002 (token delegation),
0003 (cache consistency) and 0004 (VFS transport and scoped metadata authorization).

## Context (verified against the Batch 8 code before this batch)

- **WinFsp Read callback** (`vfs/src/fs/filesystem.cpp`): returned `STATUS_END_OF_FILE` at/after
  EOF and `STATUS_NOT_SUPPORTED` otherwise, with 0 bytes. Files were `READONLY|OFFLINE`.
  `PostCleanupWhenModifiedOnly = 1`, so Cleanup is almost never posted on this read-only volume;
  `Close` is the one callback that always ends an open (WinFsp tutorial: "Every `Open` (or
  `Create`) is always matched by `Close`. `Close` is the final call that will be received for an
  open file instance."). The installed `winfsp.h` documents Read as: "PBytesTransferred [out]:
  Pointer to a memory location that will receive the actual number of bytes read. @return
  STATUS_SUCCESS or error code. STATUS_PENDING is supported allowing for asynchronous operation."
  The memfs sample returns `STATUS_END_OF_FILE` for `Offset >= FileSize`, clamps the end to the
  file size and reports the clamped count with `STATUS_SUCCESS`. The header also says Cleanup "is
  **not** the final close operation on a file. The file system must be ready to receive additional
  operations until close time."
- **Agent read contract** (`read/ReadService`): `ReadRequest(handle, fileId, offset, length,
  expectedSize, expectedRevision)`, `1 ≤ length ≤ 4 MiB`, overflow-checked, clamped at EOF;
  `file_changed` on a different total size (Content-Range) or Dropbox revision; the Batch 7 cache
  serves only Dropbox `rev:`-pinned reads. Credentials (`hdh_`) are per account, ≤ 20 min.
- **Mount authorization** (`vfs/MountRegistry`): one mount, a generation, a hashed `hdv_`
  capability that opens only `/local/v1/vfs/session|accounts|list`; the backend grant (`hdm_`)
  stays in the agent.

## Decision

### 1. Agent-owned read contexts on capability routes

New capability routes (mount capability only, POST, bounded JSON body, no query):

| Route | Request | Answer |
| --- | --- | --- |
| `/local/v1/vfs/open` | `{accountId, fileId, expectedSize}` | `{handle, sizeBytes, consistency, revisionPinned}` |
| `/local/v1/vfs/read` | `{handle, offset, length ≤ 4 MiB}` | raw bytes + `X-HyperDrive-Read-Offset/-Length/-EOF` |
| `/local/v1/vfs/close` | `{handle}` | `{closed}` |
| `/local/v1/vfs/cancel` | `{}` | cancels every in-flight read of the mount (used while unmounting) |

OpenRead, in order: mount generation is current → grant not expired → **fresh metadata by
provider id** (including the account's provider) from the new grant-scoped backend route `GET /api/v1/vfs/accounts/{id}/files/{fileId}` (owner-checked by the
same service the app uses) → kind `FILE`, `canDownload`, known size, and for Dropbox a known
`rev` → the size equals the VFS's `expectedSize` (what Windows already shows) → a random 256-bit
`hdf_` handle. The agent stores only the SHA-256 of the handle, with generation, user, account,
provider, file id, size and revision **taken from the backend, never from the VFS**.

- At most **256** open contexts per mount (`too_many_open_files`); failed opens create nothing.
- No idle expiry: a healthy handle lives until `close`, unmount, logout, disconnect of that
  account, a new mount or agent exit. Every one of those drops the contexts and cancels their
  in-flight reads. Unknown or dropped handles get `410 stale_handle`.
- Each read re-checks the generation and the grant's expiry, so a handle is never more useful
  than the mount session that created it.
- `close` racing a read: the context is removed first and its in-flight reads are cancelled; the
  read answers `cancelled`, never bytes after close.

### 2. Version semantics per handle

- **Dropbox:** the revision is fixed at open (`consistency: "dropbox-revision-pinned"`). Every
  read uses `rev:<rev>` and the existing `Dropbox-API-Result` check, and may be answered by the
  Batch 7 cache under its unchanged rules. If that revision disappears the read fails
  (`not_found`/`file_changed`); it never switches to a newer revision.
- **Google Drive:** `consistency: "google-unpinned"`, never cached. Every range is checked
  against the open-time size via `Content-Range`'s total; a different size fails with
  `file_changed`. A same-size modification between two ranges is **not detectable** with the
  current design (Drive's media endpoint can't pin a revision); this is a documented limitation.
- Unknown size or (Dropbox) unknown revision: OpenRead fails with `unsupported_file`. Browsing is
  unaffected; such files are already hidden by Batch 8.

### 3. Credential renewal

The mount needs a delegated provider credential per account; the VFS never sees one.

1. A read finds no live credential for the account (or the provider rejected it once): the
   agent records **one** request per account for the current generation and the read waits
   (≤ 20 s, inside the read's overall 45 s deadline). A credential with < 2 min left triggers a
   background request while the current one keeps working.
2. Electron main long-polls `POST /local/v1/vfs/credential-requests {generation}` (admin secret,
   ≤ 25 s), obtains the delegation with the signed-in session (`POST
   /api/v1/accounts/{id}/read-credentials`, Batch 6) and installs it with `POST
   /local/v1/vfs/mount/credential {generation, accountId, provider, accessToken,
   providerExpiresAt, rootNamespaceId}`. A refusal by the backend is reported with
   `POST /local/v1/vfs/mount/credential-failed {generation, accountId, code}` so waiting reads
   fail at once.
3. Requests and installs are bound to the generation; a stale generation is refused (404) and the
   token is dropped. Unmount/logout/disconnect stop the pump (AbortController) and fail pending
   waits with `cancelled`. A credential for a disconnected account is never installed: the desktop
   unmounts the whole drive on disconnect and clears that account's credentials (Batch 6/8).
4. No storms: `needs_reconnect`, `content_permission_missing`, `account_not_found` stay failed for
   that account until the next mount generation; transient failures back off 15 s; a token
   rejected right after a renewal is not renewed again for 60 s. `permission_missing` (scope) and
   `forbidden` never trigger renewal.

Replacing an account's credential no longer cancels reads that are using the previous one (they
keep their token until they finish; the provider token remains valid). Clearing still cancels.

### 4. VFS read path

- Agent handle opened **lazily on the first Read** of an open (Explorer opens files for metadata
  far more often than it reads them); closed in **Close** (best effort, short timeout), never in
  Cleanup. Permanent failures are remembered per open and for 10 s per file (no retry storms).
- `offset ≥ size` → `STATUS_END_OF_FILE`. Otherwise `want = min(length, size − offset)` (64-bit,
  no overflow possible), split into ≤ 4 MiB subrequests, each written **directly into the WinFsp
  buffer**. Each answer must have status 200, `application/octet-stream`, `Content-Length ==
  X-HyperDrive-Read-Length == requested`, `X-HyperDrive-Read-Offset == requested`, and exactly that
  many bytes. Any failure → the error status with `*PBytesTransferred = 0`; never a partial success,
  never zero-fill.
- Synchronous (blocking) callbacks; no `STATUS_PENDING`. At most 4 content requests in flight per
  VFS (wait ≤ 10 s for a slot → busy). No lock is held across network I/O except the per-open
  mutex that serializes that one open's lazy OpenRead.
- Unmount: new reads fail with `STATUS_VOLUME_DISMOUNTED`, `/local/v1/vfs/cancel` ends in-flight
  ones, then the dispatcher is stopped. Every request is also bounded by WinHTTP timeouts (content
  receive 55 s > agent deadline 45 s; WinFsp's minimum `IrpTimeout` is 60 s per `fsctl.h`).
- Cancellation: the WinFsp header documents no cancellation notification for synchronously
  processed requests, so the VFS doesn't rely on one; reads are bounded by deadlines instead.

### 5. Error mapping (agent code → VFS fault → NTSTATUS)

| Situation | Agent code(s) | NTSTATUS |
| --- | --- | --- |
| Capability revoked, grant expired | 401 `unauthorized`, `authorization_expired` | `STATUS_ACCESS_DENIED` |
| Reconnect needed / token rejected / permission | `needs_reconnect`, `credential_rejected`, `content_permission_missing`, `permission_missing`, `forbidden` | `STATUS_ACCESS_DENIED` |
| File missing | `not_found` | `STATUS_FILE_INVALID` (read) / `STATUS_OBJECT_NAME_NOT_FOUND` (lookup) |
| Version changed or unavailable | `file_changed` | `STATUS_FILE_INVALID` |
| Stale handle | 410 `stale_handle` | `STATUS_FILE_INVALID` |
| Unsupported kind / unknown size or revision | `unsupported_file` | `STATUS_NOT_SUPPORTED` |
| Network / agent / backend unreachable | `unavailable`, `backend_unavailable`, transport | `STATUS_UNEXPECTED_NETWORK_ERROR` |
| Timeout | `timeout` | `STATUS_IO_TIMEOUT` |
| Provider rate limit, agent busy | `rate_limited`, `too_busy` | `STATUS_DEVICE_BUSY` |
| Too many open read contexts | `too_many_open_files` | `STATUS_TOO_MANY_OPENED_FILES` |
| Cancelled | `cancelled` | `STATUS_CANCELLED` |
| Unmount in progress | (VFS-local) | `STATUS_VOLUME_DISMOUNTED` |

### 6. Test-only end-to-end fixture

No fixture endpoint exists in the production jar. A **test-sources** entry point
(`agent/src/test/java/.../FixtureAgentMain`) runs the real `Agent` with `ProviderEndpoints`
pointing at an in-process deterministic fake provider (generated bytes, no allocation of whole
files, sizes beyond 4 GiB). A node script plays the stand-in backend and Electron's credential
pump and drives the real VFS through Windows file APIs.

## Alternatives rejected

- **Provider calls or a cache in C++:** duplicates token handling and the Batch 7 consistency
  rules; the VFS would hold provider tokens.
- **Stateless reads with `(accountId, fileId, size, rev)` from the VFS:** the VFS would decide the
  version and size; a guessed id would only be stopped by the backend's ownership check on
  delegation. Agent contexts keep the backend as the source of truth.
- **`STATUS_PENDING` completion:** correct use needs request-hint bookkeeping and cancellation
  coordination with teardown; bounded blocking callbacks are simpler to get right for this batch.
- **Proxying content through the backend or Electron IPC:** violates the content boundary (ADR 0001).

## Consequences

- Reads reach providers only through the agent; bytes never touch the backend, Electron or React.
- Every open of a not-yet-opened file costs one backend metadata call (accounts + file).
- Windows' cache manager may answer repeated reads without calling the VFS; agent counters show
  only what reached the agent.
