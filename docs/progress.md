# Progress

## Batch 10: verification, defect fixing and reliability hardening (2026-11-27, in progress)

Full record: [verification.md](verification.md). **Live provider checks are pending the OAuth setup**
(no Google OAuth client or Dropbox app configured yet); everything below ran on this PC.

### Baseline

Repository at `fd80840`, clean. Backend 228, agent 111, desktop 296, native 43 passed; typecheck,
lint and build clean. Batch 9 complete; nothing blocked.

### Defects found and fixed

- **Orphaned agent JVM** when `java` comes from PATH (Oracle `javapath` launcher): forced stops now
  end the owned child's process tree by PID (`taskkill /PID n /T /F`, absolute System32 path).
- **Overstated "downloaded" counters** when reads share one chunk fetch: bytes now attributed once.
- **Possible renew-ahead loop** with short-lived credentials: at most one per account per minute.

### New opt-in checks (real mount / real OS components, fixture provider)

`vfs/tests/reliability-check.mjs` (latency, throughput, amplification, memory over 5 GiB, renewal
during a long read, backend outage, network drop, retry storm, desktop process killed),
`vfs/tests/app-compat-check.mjs` (Explorer shell, zip folder, thumbnails, WIC, Edge PDF, VLC
play/seek, concurrent apps), `vfs/tests/key-protection-check.mjs` (real Electron safeStorage/DPAPI,
restart, encrypted cache hit, corrupted/truncated/missing key, plaintext scan). Shared harness in
`vfs/tests/lib/`; the fixture provider gained a test-only network switch and file-backed entries.

### Checks

| Check | Result |
| --- | --- |
| Backend `mvnw verify` | 228 passed |
| Agent `mvnw verify` | 113 passed (+2 regression tests) |
| Desktop typecheck, lint, build | Pass |
| Desktop Vitest | 298 passed (+2 `taskkill` argv tests) |
| Native build and `hdvfs_tests` | Pass; 43 tests |
| `reliability-check.mjs` | Passed on this PC: 20/20 (fixture provider) |
| `app-compat-check.mjs` | Passed on this PC: 21/21 (synthetic files, fixture provider) |
| `key-protection-check.mjs` | **Passed live** (real DPAPI): 11/11 |
| `content-mount-check.mjs`, `agent-mount-check.mjs`, `fixture-mount-check.mjs` | Re-run after this batch's fixes: 44/44, 13/13, 24/24 |
| `npm audit` (desktop), OSV (84 Maven artifacts) | 0 known vulnerabilities; native deps not scanned |
| Live Google/Dropbox sign-in, browsing, reads, cache evidence, live apps | **Not executed:** OAuth not configured |

## Batch 9: read-only file content through the mounted drive (2026-11-27)

### Foundation check before starting

- Repository at `416d454` (Batch 8), clean. Baseline re-run: backend 227, agent 91, desktop 284,
  native 28 tests passed; real-mount checks `fixture-mount-check.mjs` 24/24 and
  `agent-mount-check.mjs` 13/13 passed on this machine. Batch 8 was complete; nothing blocked.
- WinFsp semantics taken from the installed `winfsp.h`/`fsctl.h`, the memfs sample and the
  official `doc/` sources in the WinFsp GitHub repository (winfsp.dev itself failed TLS
  verification from this machine).

### Completed

- **Design:** [ADR 0005](adr/0005-mounted-content-reads.md) (agent-owned read handles, version
  semantics, renewal, VFS read algorithm, error mapping); [virtual-drive.md](virtual-drive.md)
  rewritten.
- **Backend:** grant-scoped `GET /api/v1/vfs/accounts/{id}/files/{fileId}` (owner-checked; id,
  provider, kind, size, modified, revision, canDownload; no name/hash/path).
- **Agent:** `vfs/VfsReadService` (OpenRead/Read/Close/Cancel contexts, `hdf_` handles hashed in
  memory, 256 per mount, generation + grant checks on every read, 45 s deadline, counters),
  `vfs/CredentialBroker` (one renewal per account and generation, bounded waits, sticky/backoff
  failures), capability routes `/local/v1/vfs/open|read|close|cancel`, admin routes
  `/local/v1/vfs/credential-requests`, `/mount/credential`, `/mount/credential-failed`.
  `ReadService` accepts an external cancel token; `CredentialRegistry` adds per-account lookup and
  retirement; HTTP pool 12 → 16. Capability `virtualDriveContent`.
- **VFS:** `core/content.*` (lazy open on first Read, close in `Close`, EOF/clamp, ≤ 4 MiB split
  straight into the WinFsp buffer, all-or-nothing, sticky permanent failures + 10 s per-file
  negative cache, 4 concurrent requests, unmount cancels), binary `PostForBinary` transport with
  strict header/length validation, content fault → NTSTATUS mapping, unmount ordering in `main.cpp`.
- **Desktop:** `VfsCredentialPump` (main process; long poll → session delegation → install for the
  same generation; stopped first on every teardown), coordinator wiring, Virtual drive card
  ("read-only virtual drive", real agent counters, safe read-issue messages), agent card copy.
- **Test-only E2E:** `FixtureAgentMain` (agent test sources), `vfs/tests/content-mount-check.mjs`,
  `vfs/tests/unbuffered-read.ps1`.

### Fixes found on the way

- **Replacing a credential cancelled reads using the old one** (Batch 6 behavior): a Verify read
  in the app would have cancelled in-flight drive reads of the same account. Replacement now lets
  those reads finish; clearing still cancels them.
- **Oversized mounted reads waited for a credential** before being rejected; validation now comes
  first (found by `VfsContentApiTest`).

### Checks

| Check | Result |
| --- | --- |
| Backend `mvnw verify` | **228 passed** (+1: file-by-id grant route) |
| Agent `mvnw verify` | **111 passed** (+ `VfsReadServiceTest` 15, `VfsContentApiTest` 4, `BackendMetadataClientTest` +1; capability test updated) |
| Desktop typecheck, lint, build | Pass |
| Desktop Vitest | **296 passed** (23 files; + credential pump 7, coordinator 4, card 1) |
| Native build (`/W4 /WX`) and `hdvfs_tests` | **Pass; 43 tests** (+15 content) |
| Real WinFsp mount, content through real agent + fixture provider (`content-mount-check.mjs`) | **Passed on this machine: 44/44** (see below) |
| `fixture-mount-check.mjs`, `agent-mount-check.mjs` (regression) | **24/24, 13/13** |
| Live Google Drive / Dropbox reads through the drive | **Not executed:** no OAuth client / Dropbox app |
| PDF viewer, video player, Office, Explorer double-click | **Not executed** (no live account; no app tested) |

Fixture evidence (`content-mount-check.mjs`, TEST-ONLY provider, not live): listing of two
accounts; 5 GiB size exact; full read and `CopyFile` of a 1,000,003-byte file match SHA-256;
cross-chunk, 25 random, final partial and EOF reads exact; reads across 2³², beyond 4 GiB and the
last bytes of a 5 GiB file exact; 6 concurrent readers exact; a 12 MiB non-cached read reached the
agent as 4 MiB pieces and matched; non-cached EOF reads return only real bytes / `ERROR_HANDLE_EOF`;
Dropbox re-read served by the agent cache with 0 provider bytes, Google re-read went to the
provider; one credential renewal per account; create/write/delete/rename/mkdir rejected; unmount
with an outstanding read finished in < 1 s and the read failed (`ECANCELED`); a remount (new
generation) reads again; killing the VFS removed the drive; killing the agent failed the
outstanding read at once and the VFS unmounted itself (`agent-unreachable`, ~37 s); no
capability, token, grant or handle in any output. Observed: copies keep `ReadOnly`; ~128 KiB of
extra agent reads per non-cached open, consistent with antivirus scanning on open.

### Remaining limitations

- Google: same-size modifications during an open read aren't detected.
- Blocking callbacks (≤ 45 s per agent read); per-read application cancellation isn't propagated.
- No application compatibility verified beyond Windows file APIs; files are shown offline.
- Disconnect unmounts the whole drive; delegated tokens are deleted locally, not revoked.
- Live provider behavior (Range/`Content-Range`, `Dropbox-API-Result` on 206) still unverified.

## Batch 8: read-only, metadata-only WinFsp virtual drive (2026-11-23)

### Foundation check before starting

- Repository at `d1cea6b` (verification checkpoint), clean.
- **Checkpoint status:** live checks still **not executed** for Google sign-in, Drive, Dropbox,
  reads and provider caching (no OAuth client or Dropbox app configured). Passed live: Windows
  `safeStorage` key protection. Everything else: mocks only.
- **Claims verified against code:** Electron/React desktop, Spring Boot backend + PostgreSQL,
  sign-in/onboarding, separate Drive/Dropbox connections, metadata browsing, supervised agent with
  authenticated loopback API, token delegation (refresh tokens stay on the backend), bounded reads,
  encrypted 4 MiB cache with conservative eligibility (Google bypassed, Dropbox `rev`-confirmed).
- **Prerequisites on this machine (nothing installed):** Windows 11 x64; VS 2022 Build Tools
  17.14 with MSVC 14.44 and bundled CMake/Ninja; Windows SDK 10.0.22621; WinFsp 2.1 (2025) runtime
  **and** Developer SDK; Docker running; Java 25 (agent targets 21).

### Completed

- **Design:** [virtual-drive.md](virtual-drive.md) and
  [ADR 0004](adr/0004-vfs-transport-and-scoped-metadata-authorization.md): loopback HTTP via
  WinHTTP behind a narrow interface (metadata only; not a decision for content), per-mount agent
  capability, short-lived backend metadata grant.
- **Backend:**
  - Flyway `V4__metadata_grants.sql`; `hdm_` grants (hash-only, 15 min, one per session, deleted
    on logout); `MetadataPrincipal` with only `METADATA_READ`.
  - `POST/DELETE /api/v1/metadata-grants` (session), `GET /api/v1/vfs/accounts` and
    `GET /api/v1/vfs/accounts/{id}/files` (grant only; owner-checked; no emails, names, hashes or
    cursors). Session tokens refused on `/api/v1/vfs/**`; grants refused everywhere else.
  - Rate-limit bucket `vfsBrowse` (600/min).
- **Agent:** `vfs/MountRegistry` (generation + hashed `hdv_` capability, memory only),
  `BackendMetadataClient` (two fixed routes, HTTP/1.1, no redirects, bounded, strict),
  `VfsMetadataService` (complete listings; 10,000 entries / 100 pages / 30 s / 4 concurrent;
  no silent truncation). Admin routes `/local/v1/vfs/mount|mount/grant|mount/revoke|status`
  (launch secret) and capability-only routes `/local/v1/vfs/session|accounts|list`.
  Capability `virtualDrive` is `true`.
- **Native VFS (`vfs/`, C++20/CMake, x64):** name mapping, snapshots and marker enumeration,
  strict agent client, WinHTTP transport, path resolution with a 30 s snapshot cache and bounded
  concurrency, read-only WinFsp volume (writes → `STATUS_MEDIA_WRITE_PROTECTED`, non-empty reads →
  `STATUS_NOT_SUPPORTED`, EOF → `STATUS_END_OF_FILE`), stdin bootstrap, JSON events, heartbeat
  self-unmount, opt-in fixture mode. nlohmann/json 3.11.3 pinned by SHA-256.
- **Desktop:** `VfsManager` (no shell/args, strict events, 30 s startup, owned-child-only kill),
  `VirtualDriveCoordinator` (grant → capability → process; renewal every 10 min; teardown on
  unmount, logout, disconnect, agent restart, renewal failure, quit), drive-letter settings (D–Z),
  IPC (`vfs.getStatus|mount|unmount|openInExplorer|setDriveLetter`), Virtual Drive card.
  Disconnect unmounts the whole drive.

### Fixes found on the way

- **Stale UI copy:** the agent card still said it "doesn't cache anything or create a drive yet"
  (wrong since Batch 7); it now states what the agent does from its capabilities.
- **Agent HTTP client:** pinned to HTTP/1.1 for backend calls (no `h2c` upgrade negotiation on
  `http://` loopback origins).
- **Test harness:** the first real-chain check used blocking fs calls in the process hosting the
  stand-in backend; it now uses async calls (the product behaved correctly: it reported
  `backend_unavailable` after its timeout).

### Checks

| Check | Result |
| --- | --- |
| Backend `mvnw verify` | **227 passed** (223 at the checkpoint + 4 grant tests; migration check updated for V4) |
| Agent `mvnw verify` | **91 passed** (+ `VfsApiTest` 5, `BackendMetadataClientTest` 3) |
| Desktop typecheck, lint, build | Pass |
| Desktop Vitest | **284 passed** (22 files), incl. the real-jar smoke test |
| Native build (`vfs\build.cmd`, `/W4 /WX`) and `hdvfs_tests` | **Pass; 28 tests** |
| Real WinFsp mount, fixture data (`fixture-mount-check.mjs`) | **Passed live on this machine: 24/24** (listing, mapped names, collisions, Unicode, exclusions, sizes, mtimes, nested + 300-entry dirs, write/create/mkdir/delete/rename rejected, non-empty read fails, zero-byte read empty, unmount, process exit, letter released) |
| Occupied drive letter (temporary `subst`) | **Passed live:** `drive-letter-in-use`, existing mapping untouched |
| Real agent jar + real VFS, normal mode, local stand-in backend (`agent-mount-check.mjs`) | **Passed live: 13/13** (capability auth, grant-only backend routes, listing, revocation → self-unmount within the heartbeat, no secrets in logs) |
| Desktop `VfsManager` (compiled) + real VFS + real agent, stand-in backend | **Passed live: 7/7** (scratch harness, not committed) |
| Live Google Drive / Dropbox through the mount | **Not executed:** no OAuth client / Dropbox app configured |
| Electron UI-driven mount with a signed-in user | **Not executed:** needs live sign-in (same blocker); covered by coordinator/card tests with mocks |
| Desktop or VFS crash cleanup | **Not executed** (designed: stdin EOF → unmount; WinFsp removes a dead process's volume) |

### Remaining limitations

- No content reads through the drive; Explorer shows files as offline/read-only.
- Shortcuts, native Google documents, files of unknown size and shared drives aren't shown.
- Metadata up to ~30 s stale; listings over 10,000 entries fail visibly.
- Callbacks block during listing fetches (bounded timeouts); no asynchronous WinFsp completion.
- Disconnect unmounts the whole drive. No auto-mount, service or installer; dev-build exe only
  (or `HYPERDRIVE_VFS_EXE`).

### Prerequisites for Batch 9 (content through the drive)

- Live verification of Batch 6–8 with real accounts (runbook: [live-verification.md](live-verification.md)).
- Decide the content transport and whether the mount capability may trigger reads (ADR 0001/0004).
- Map WinFsp `Read` onto the bounded read and cache path (Google uncached), with cancellation,
  per-open revalidation and the bounded authorization-renewal policy proposed at the checkpoint.
- Decide how Explorer previews/thumbnails should behave once content is readable.

## Verification checkpoint before Batch 8 (2026-11-23)

Not a feature batch: decisions on Disconnect and revocation, targeted fixes found by auditing, and
live verification where it was possible. Live OAuth checks are **blocked**: no Google OAuth client
or Dropbox app credentials are configured on this machine (checked by variable presence only).

### Disconnect decision (implemented)

- **"Disconnect"** stays local unlinking: delete the connection and its encrypted credentials,
  cancel its reads, clear its delegated credential in the agent, purge its cached chunks. The
  HyperDrive account, other connections and remote files are untouched. It never calls a provider
  revocation endpoint and the UI no longer implies it does.
- **"Revoke access and disconnect"** is a separate, confirmed action, **Dropbox only**
  (`POST /api/v1/accounts/{id}/revoke-and-disconnect`): one `auth/token/revoke` call, then an
  unconditional local disconnect; outcome `confirmed` / `failed` / `unknown`; manual instructions
  when not confirmed. Credentials are never kept to retry.
- **Google revocation deferred:** Google documents that revocation removes everything the account
  granted to the Cloud **project** (all its clients). HyperDrive's sign-in and Drive share one
  client by default, and several HyperDrive users can connect the same Google account, so a
  per-connection revoke isn't possible. Manual instructions (myaccount.google.com/permissions) and
  the effects are shown instead. **Needs owner approval** to implement one of:
  1. Revoke only when no other connection on this server uses the same Google account, with the
     sign-in-consent effect stated in the confirmation.
  2. Use a separate Google Cloud project for Drive, then option 1 (sign-in unaffected).
  3. Keep manual-only (current state).
- **Compliance flag:** Google's OAuth policies ask apps to revoke tokens they no longer need;
  Disconnect deletes but doesn't revoke Google tokens. Resolve before a public, verified Google
  release. Dropbox terms read contain no revoke-on-unlink requirement.

### Fixes from the audit (with regression tests)

- **Disconnect/logout race (desktop):** a credential delegation or read still in flight when the
  account was disconnected (or the user signed out) could finish afterwards and re-install a
  delegated credential in the agent. `ReadCoordinator` now has per-account and global epochs:
  late results are discarded (`cancelled`), late-installed credentials are cleared again, late
  read bytes are zeroed. Three tests; all three fail on the previous code.
- **Owed account purges survived only in memory:** a disconnect while the agent was down left
  that account's encrypted chunks on disk after an app restart. Pending purges are now persisted in
  `cache-settings.json` and retried at the next session (test).
- **Outdated setup docs:** the Dropbox app setup (and README) still listed two scopes;
  `files.content.read` is required since Batch 6. security.md still said "no content scopes".
- **Wording:** cache/security/ADR docs now distinguish deleting encrypted files (Clear cache,
  Disconnect: key kept) from deleting the key (logout only), and make no cryptographic- or
  physical-erasure claim where the key still exists.
- **Live-QA evidence:** the agent logs one sanitized line per chunk fetch (provider, 4-char rev
  prefix, chunk index, range, validated bytes, whether the response revision was absent/matching,
  admission or reason). Test proves no token, handle, file id or full rev appears.

### Verification status

| Item | Status |
| --- | --- |
| Google sign-in, onboarding, returning login | **Not executed**: no Google OAuth client configured |
| Google Drive connection, browsing, reads, Google cache bypass | **Not executed**: same blocker (mock-tested) |
| Dropbox connection, browsing, reads, revision-pinned cache path | **Not executed**: no Dropbox app (mock-tested) |
| Dropbox revoke-and-disconnect | **Passed with mocks only** (backend fake + desktop tests) |
| Permission/reconnect errors, logout/disconnect cleanup | **Passed with mocks only** |
| Real Windows `safeStorage` key creation, restart recovery, corrupt/missing/foreign key handling, no plaintext | **Passed live** (Electron 45.4.1, this machine) |
| Encrypted cache hit with the real key | **Not executed**: needs an eligible live Dropbox read |
| Cache disabled when OS protection unavailable | **Passed with mocks only** (DPAPI can't be disabled here) |

Runbook and details: [live-verification.md](live-verification.md).

**Automated checks:** agent 83 tests passed; desktop typecheck, lint, build and 255 tests passed
(including the real-jar smoke test); backend `mvnw verify` 223 tests passed (218 + 5 new
revoke/disconnect tests, Testcontainers PostgreSQL).

### Cached-access policy (as enforced today)

- A read (hit or miss) needs a signed-in session, a connected owned account, and a delegated
  credential issued by the backend; handles live at most 20 minutes and are re-issued only through
  the backend. Each diagnostic read first fetches fresh metadata through the backend (a live
  provider call for that file).
- Logout and disconnect clear the credential (cancelling reads and shared fetches), purge chunks,
  and now discard late in-flight delegations and reads. Generation counters block late
  publication.
- Not offline access. A hit doesn't prove current permission at the provider.
- **Proposed for Batch 8 (not implemented):** when WinFsp reads bypass the per-read metadata
  check, serve cached chunks only while the account's delegated credential is valid (≤ 20 min),
  and revalidate file metadata through the backend at file open and at least on that interval.
  This bounds, but doesn't eliminate, the window after a remote permission change.

## Batch 7: encrypted local 4 MiB chunk cache (2026-11-22)

### Foundation check before starting

- Repository at `15aa1cb`, clean. Baselines: agent 51 tests and desktop 226 passed; backend 218
  (unchanged from Batch 6, re-run at the end).
- **Checkpoint verified against code:**
  - Dropbox reads are request-pinned with `rev:`. However, `checkRevision` accepts a response
    **without** `Dropbox-API-Result` (revision `null`), so cache admission additionally requires
    the header's `rev` to match.
  - Google reads are unpinned.

### Completed

- **Design first:** [docs/cache.md](cache.md) and [ADR 0003](adr/0003-cache-consistency-and-key-lifecycle.md).
  The Dropbox `rev` semantics were re-checked in `files.stone` (dropbox-api-spec).
- **Agent** (`read/CacheCrypto`, `ChunkStore`, `ChunkCache`, `RangeReader`):
  - Aligned 4 MiB chunks (at most two per read); AES-256-GCM with a random nonce and the chunk
    identity in the authenticated data; HMAC-tag file names.
  - The file system is the index, with atomic temp→rename publication and startup reconciliation.
  - Budget with reservations, LRU eviction that skips pinned entries, deduplicated shared fetches
    with per-waiter cancellation, generation counters against late publication, and purge per
    user or account.
  - Eligibility: Dropbox `rev:` reads confirmed by `Dropbox-API-Result`. Google always bypasses
    with `provider_not_version_bound`.
  - Endpoints: `/local/v1/cache/status`, `/cache/session`, `/cache/session/end`, `/cache/budget`
    and `/cache/clear`. Read responses carry cache headers.
  - Capability `chunkCache` is `true`.
- **Desktop:**
  - `CacheKeyStore`: a per-user random key wrapped by `safeStorage`, with no plaintext fallback.
  - `CacheSettings`: the persisted budget, 5 GiB by default.
  - `CacheCoordinator`: single-flight key hand-off per agent instance, status re-parsing,
    budget, clear, account purge with retry, and logout purge plus key deletion.
  - Logout purge runs only for `signed-out` and `session-expired`, never for transient phases.
  - IPC `cache.getStatus` / `setBudget` / `clear` with validation.
  - UI: real metrics on Performance; size limit and confirmed Clear cache in Settings; Verify file
    read shows hit, miss or bypass, the reason, and bytes fetched or served from cache.

### Checks executed (Windows 11, Docker, JDK 25.0.5, Node 24.19.0)

- Backend `mvnw verify`: 218 tests, 0 failures (regression only; no backend changes).
- Agent `mvnw verify`: 82 tests, 0 failures, including 25 `ChunkCacheTest` and 6 `CacheApiTest`
  tests against local mock providers.
- Desktop: typecheck, lint and build exit 0. Vitest: 247 tests, 0 failures, including the real-jar
  smoke test of the cache endpoints.
- **Mutation checks:** each of these was caught by a failing test:
  - removing the generation check;
  - evicting pinned entries;
  - dropping the chunk identity from the authenticated data;
  - skipping the revision confirmation;
  - cancelling a shared fetch when the first waiter leaves.

### Defects found during this batch and fixed

- A logout racing a cached read would have thrown an internal error; it now falls back to the
  uncached path (`cache_key_unavailable`), with a test.
- The first wiring purged the cache on **any** non-signed-in auth phase. That would have wiped the
  cache and key during a transient "checking" or "backend unavailable" phase. Purge now happens
  only on a real sign-out or session expiry (`endsCacheSession`, tested).

### Requires manual verification (not executed)

- Live Dropbox and Google reads, and with them the cache. See the checklist in [cache.md](cache.md).
  The key open question is whether Dropbox returns `Dropbox-API-Result` on ranged downloads; if
  not, nothing is cached and the diagnostic says "not stored".
- DPAPI-backed key protection on a real Windows profile. Tests use a stand-in for `safeStorage`.

### Known limitations

- **Google Drive content is never cached.**
- **Hits don't contact the provider**, so access revoked moments ago isn't detected until
  disconnect or logout.
- **Deletion isn't secure erasure.**
- **Restart behavior:** counters are process-lifetime, and LRU order after a restart is
  approximate (file modification times).
- **Amplification:** an eligible miss fetches whole chunks, up to 8 MiB for one unaligned 4 MiB
  read.
- **Scope:** one active cache user at a time. Other users' leftovers are deleted at session start.

## Batch 6: provider-read delegation and bounded range reads (2026-11-22)

### Foundation check before starting

- The working tree was clean at `1157d46`. Baseline: backend 211, agent 28 and desktop 201 tests,
  all passing.

### Completed

- **Backend:** `POST /api/v1/accounts/{id}/read-credentials`.
  - Checks session, onboarding, ownership, connection status and content scope **before** any
    refresh or provider call.
  - Returns only the access token (valid at least 5 more minutes), its expiry and the Dropbox root
    namespace.
  - `no-store`, a per-user rate limit (30 per minute) and a token-free audit log line.
- **Scopes and listings:**
  - Dropbox consent now requests `files.content.read`. Older grants report
    `contentReadGranted=false`, which gives 409 `content_permission_missing` on delegation.
  - Drive listings carry `headRevisionId` and `md5Checksum`.
- **Agent:**
  - Endpoints: `POST /local/v1/credentials`, `/credentials/clear` and `/reads`.
  - An in-memory credential registry: ≤ 32 credentials, one per account, ≤ 20 min retention,
    never past provider expiry.
  - A bounded read service: ≤ 4 MiB, overflow-safe, EOF and zero-byte reads answered without a
    provider call, 8 global and 4 per-account concurrent reads.
  - Provider reader: fixed endpoints, no redirects, identity encoding (compressed bodies
    rejected), exact `Content-Range` and length validation, a controlled ignored-Range rule,
    classified errors and bounded retries.
  - Capabilities `providerReads` and `rangeReads` are `true`. Credentials are cleared on shutdown.
- **Desktop:**
  - `ReadCoordinator`: delegation, handles bound to the agent `instanceId`, single-flight renewals,
    one re-delegation, clear-or-restart on disconnect and sign-out, and a main-process SHA-256 with
    the buffer zeroed afterwards.
  - IPC `storage.verifyFileRead` with strict validation.
  - UI: a **Verify file read** panel, a Cloud Accounts note and Reconnect for a missing content
    permission, and accurate agent-card text.
  - `HYPERDRIVE_BACKEND_URL` must be https unless it is loopback.
- **Docs:** ADR 0002 (ADR 0001 now links to it), a read-contract section in local-agent.md,
  security, the architecture matrix, Google Drive and Dropbox content-read sections with live
  checklists, README and `.env.example`.

### Checks executed (Windows 11, Docker, JDK 25.0.5, Node 24.19.0)

- Backend `mvnw verify`: 218 tests, 0 failures (Testcontainers PostgreSQL 18).
- Agent `mvnw verify`: 51 tests, 0 failures (local mock Google and Dropbox servers).
- Desktop: typecheck, lint and build exit 0. Vitest: 226 tests, 0 failures, including the
  real-jar smoke test of the credential and read endpoints.
- Mutation checks: removing buffer zeroing, removing single-flight renewal, and accepting an
  ignored Range for a partial request were each caught by a failing test.

### Defects found during this batch and fixed

- Provider timeouts were reported as `unavailable`; they're now `timeout`.
- The agent worker pool never grew past 2 threads: `ThreadPoolExecutor` only grows when its
  queue is full. That silently serialized reads, so the core size is now 12.
- The docs claimed compressed bodies were rejected before the code did. A `Content-Encoding`
  check and a test now back the claim.

### Requires manual verification (not executed)

- Live Google Drive and Dropbox reads: see the "Content reads" checklists in google-drive.md
  and dropbox.md. Range, redirect and error-body behavior has been tested only against mocks.

### Known limitations

- **Account-wide tokens:** delegated tokens are account-wide at the provider, and clearing the
  agent doesn't revoke them (ADR 0002).
- **No pinning on Google:** Google reads aren't revision-pinned. No read is marked cache-safe.
- **Loopback transport:** the read contract runs over loopback HTTP and isn't the final VFS
  transport.

## License added (2026-11-22)

- **License file:** added `LICENSE` with the official GPL-3.0 text from
  <https://www.gnu.org/licenses/gpl-3.0.txt> (35,149 bytes, SHA-256
  `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`).
- **Consistency:** the project license is `GPL-3.0-only`, matching `desktop/package.json`,
  `backend/pom.xml` and `agent/pom.xml`. The README's License section is updated.
- **Batch 1 limitation resolved:** the Batch 1 note "no `LICENSE` file" below is now resolved.

## Batch 5: local agent process, authenticated loopback API, Electron lifecycle (2026-11-22)

### Foundation check before starting

- **Batch 4 re-verified unchanged:** commit `eb4f537`, backend `mvnw verify` 211 tests with
  0 skipped; desktop `npm run check` 167 tests.
- **Starting point:** `agent/` held only a README. There was no existing local transport,
  including gRPC, to reuse.

### Completed

- **Agent** (`agent/`, Java 21, an independent Maven project with its own wrapper):
  - Plain Java on the JDK HTTP server plus Jackson. The Spring Boot parent is used only for
    dependency versions and the runnable jar `agent/target/hyperdrive-agent.jar`. No database,
    Redis or backend dependency.
  - Stdin bootstrap: at most 8 KiB, exactly protocol 1, required fields only, a 10 s timeout,
    fail closed, and the input is never echoed.
  - Exclusive `agent.lock` in `<userData>/agent`.
  - API bound to `127.0.0.1` on an OS-assigned port. Every request needs the bearer secret
    (constant-time), the exact `Host`, and no `Origin`. GET only, no bodies or queries.
  - Endpoints `/local/v1/health` and `/local/v1/status`, with truthful capabilities: all
    `false`.
  - One-line stdout handshake (`ready`/`error`); logs to stderr with secret redaction.
  - Stdin is the parent-lifetime channel: a `shutdown` message, or EOF, gives a graceful exit.
- **Desktop main process:**
  - `AgentManager` owns one child process: `spawn` with `shell:false`, a 256-bit secret per
    launch sent over stdin only, and deduplicated start and restart.
  - Handshake checks: schema, protocol, port range, and no child-supplied host or URL.
    **Authenticated health** is required before Ready, with startup and request timeouts.
  - Bounded stdout before the handshake (4 KiB) and a bounded, redacted stderr ring buffer.
  - States stopped, starting, ready, stopping, failed and restarting. Automatic restart after an
    unexpected exit up to 2 times in 10 minutes; failed starts aren't retried automatically.
  - Graceful stop on quit, then a kill of **only the owned child**.
  - Java discovery: `HYPERDRIVE_AGENT_JAVA`, then `JAVA_HOME`, then `PATH`, with a
    `java -version` check for 21 or newer. The jar comes from `HYPERDRIVE_AGENT_JAR` or the
    development default; a packaged app reports `not-packaged`.
- **IPC and preload:** `agent.getStatus()`, `agent.restart()` and `agent.onStatusChange()`
  (unsubscribe on unmount), with validated sender and frame and no arguments. No port, secret,
  path or spawn parameter is exposed.
- **UI:**
  - The Overview **Local agent** card shows state, version, uptime, an actionable error and
    Restart (disabled while pending). It says plainly that Ready doesn't mean connected, cached,
    streaming or mounted.
  - Settings lists the backend, local agent and virtual drive separately.
  - Cloud browsing doesn't depend on the agent.
- **Docs:**
  - New: [local-agent.md](local-agent.md) (design note, threat model, troubleshooting) and
    [ADR 0001](adr/0001-local-agent-transport-and-content-boundary.md) (transport and the
    future content-transfer boundary).
  - Rewritten: `agent/README.md`.
  - Updated: README, architecture, development, security and `.env.example`.

### Foundation changes (separate from new features)

- **Repository:** `.gitignore` now ignores `agent/target/`.
- **Shared types:** `HyperdriveApi` gained `agent`, and the test mock bridge gained an agent
  double.
- **Overview card:** the agent card uses `aria-live` rather than `role="status"`, so existing
  tests that find the backend indicator by its `status` role stay unambiguous.

### Defects found during this batch and fixed

- **Agent version showed "unknown":** the Spring Boot parent filters resources with `@…@`, not
  `${…}`. It was found by running the real jar, and a test now pins the version.
- **Restart test timing:** the auto-restart test checked state before the exit was processed.
  It now waits for each spawn.

### Checks executed (Windows 11, JDK 25.0.5, Node 24.19.0)

| Check | Command | Result |
| --- | --- | --- |
| Agent unit + real-process tests, jar | `cd agent; .\mvnw.cmd verify` | **Passed**: 28 tests (bootstrap validation, auth, Host/Origin, loopback binding, lock, shutdown and EOF, a real separate JVM process) |
| Desktop type check / lint | `npm run typecheck`, `npm run lint` | **Passed** |
| Desktop tests | `npm test` | **Passed**: 201 tests in 15 files, including the **real agent smoke test** (production `AgentManager`, real Java and real jar) |
| Desktop build | `npm run build` | **Passed**; no token-format strings in the renderer bundle |
| Backend regression | `cd backend; .\mvnw.cmd verify` | **Passed**: 211 tests, 0 skipped (no backend code changed) |
| Real Electron window (built app, read over CDP) | `electron.exe .` | **Passed** (details below) |
| Abrupt Electron kill | `Stop-Process` on the Electron PID started for the test | **Passed**: the agent exited by itself about 1 s later (stdin EOF) |

Real Electron window check:
- **Status:** the agent reached **ready** through the real bridge (version `0.1.0-SNAPSHOT`,
  all capabilities `false`).
- **Bridge:** exposes only `getStatus`, `restart` and `onStatusChange`.
- **Renderer:** a direct `fetch` from the renderer is blocked by the CSP.
- **Restart:** returned ready again.
- **Process:** one `java.exe` child of `electron.exe`, with arguments only `-jar <jar path>`
  (no secret), listening only on `127.0.0.1`.
- **Quit:** closing the window left **0** agent processes and **0** Electron processes, and the
  port was closed.

Mutation checks; each was caught and reverted:
- Agent auth bypassed.
- `Host` check removed.
- Protocol-version check removed.
- Parent EOF ignored.
- Desktop health check skipped.
- Restart budget removed.

### Requires manual verification (not executed)

- **Signed-in Overview card:** the card was not viewed in a signed-in real window. Signing in
  needs Google credentials, and the card lives in the signed-in shell; it's covered by jsdom
  tests. Status, restart and quit were verified in the real app through the bridge.
- **Other machines:** only this machine was tested, with a single JDK 25. The real
  Java-not-found and Java-older-than-21 paths were tested with doubles only.
- **Packaging:** packaged-app behavior (`not-packaged`), installers and a bundled JRE don't
  exist yet.

### Known limitations

- **Hung agent after a desktop crash:** an agent that hangs without reading stdin after Electron
  has died isn't cleaned up. There's no Windows Job Object yet.
- **Development Java:** development needs a JDK 21+ on the machine. That isn't the intended
  end-user requirement; a bundled runtime is installer work.
- **Same-user malware:** loopback plus the secret doesn't protect against malware already running
  as the same Windows user.
- **Status plane only:** there's no data plane. Provider reads, delegation, caching and the VFS
  transport are open decisions (ADR 0001).

### Suggested prerequisites for the next batch

- **Content-transfer design:** decide it before writing any read path. That means short-lived
  access-token delegation from the backend, or another explicit design. The agent ↔ backend
  authentication is bound to the device session.
- **Dropbox content scope:** plan the `files.content.read` reconnect for Dropbox.
- **Live provider checklists:** run both, since content reads build on real provider behavior.

## Batch 4: Dropbox connection, read-only Dropbox browsing, unified storage view (2026-11-22)

### Foundation check before starting

- **Batch 3 re-verified unchanged:** commit `c43b02f`, `mvnw verify` gave 176 tests, 0 skipped;
  `npm run check` passed with 156 tests.
- **Prerequisites:** sign-in, onboarding, Google Drive connection and browsing are all present,
  so none was missing.
- **Dropbox docs:** read on 2026-11-22 (OAuth guide, endpoint reference, team-files and developer
  guides). Points they left unclear and that HyperDrive therefore doesn't rely on:
  - PKCE together with a client secret.
  - OIDC scopes.
  - Whether `list_folder` accepts `id:` paths.

### Completed

- **Dropbox consent flow:**
  - `POST /api/v1/storage-connections/dropbox/transactions`, with its own callback.
  - Scopes `account_info.read files.metadata.read` and `token_access_type=offline`. No PKCE: it's
    explicitly stripped, and a test checks that.
  - HTTP Basic client authentication.
  - Identity comes from `users/get_current_account`, and `account_id` must equal the token
    response's.
  - The root is `root_info.root_namespace_id`, with `user` and `team` supported. Reconnecting
    updates the existing row.
- **Optional provider:** without `DROPBOX_APP_KEY`/`SECRET` the backend still starts and
  `GET /api/v1/storage-connections/providers` reports Dropbox as unavailable. A partial
  configuration fails startup.
- **Dropbox adapter** (raw HTTP, documented fields only):
  - `list_folder` and `list_folder/continue` stay separate calls; `get_metadata` by `id:`.
  - Every files call pins `Dropbox-API-Path-Root` to the account's root namespace.
  - Errors are classified: 401 auth vs. `missing_scope`, 403, 409 `not_found`/`reset`, 422 root,
    429 with Retry-After, 5xx. Provider text is never echoed.
- **Provider abstraction refactor:**
  - `StorageOAuth` (shared Spring Security OAuth plumbing) with `GoogleStorageOAuth` extending it.
  - One `ConnectionCompleter` per provider.
  - `ProviderHttp` for shared retries; `FileBrowser` takes an `AccountRoot`.
  - `ProviderFile` gains `directoryLocator`, `providerRevision`, `contentHash` and
    `contentHashAlgorithm`; `ProviderIdentity` is shared.
  - The credential store refreshes with each provider's own OAuth client.
- **Continuations:** provider cursors are wrapped into AES-GCM continuations bound to account,
  provider and directory (`ContinuationCodec`). The listing API now uses
  `?directory=&continuation=` and returns `{ items, continuation }`.
- **Desktop:**
  - `beginConnection({ provider })` and `listProviders()`. The authorize URL must match the
    requested provider. Validated IPC accepts provider-specific locators.
  - Cloud Accounts: Google Drive and Dropbox connect actions with locally drawn icons, "not
    configured" state, per-provider failure messages, reconnect and duplicate handling,
    "(team space)" label, and a per-provider disconnect confirmation with revoke hints.
  - Files:
    - The **All storage** virtual root lists accounts; it's never sent to a provider.
    - Location selector, breadcrumbs from All storage, one table for both providers, a Cache
      column reading "Not cached", and "Virtual drive not implemented yet".
    - Pagination state is keyed per account and folder.
    - Stale responses are dropped, and a disconnected account falls back to All storage.
- **Database:** Flyway `V3__dropbox.sql`:
  - Allows the `DROPBOX` provider, and adds typed `root_namespace_id` and `root_type` columns.
  - Each provider must have its own kind of root.
  - The PKCE verifier is now required only for Google attempts.
- **Docs:**
  - New: [dropbox.md](dropbox.md).
  - Provider support matrix in [architecture.md](architecture.md#provider-support-matrix).
  - Updated: README, development, security, google-drive (API parameter rename),
    authentication pointer and `.env.example`.

### Foundation changes (separate from new features)

- **API rename (breaking for old desktop builds):** the listing API changed from
  `parentId`/`pageToken` → `{items, nextPageToken}` to `directory`/`continuation` →
  `{items, continuation}`. Drive's `nextPageToken` is now wrapped like Dropbox's cursor. The
  desktop was updated in the same batch.
- **New DTO fields:** the account DTO gained `rootType` and the transaction status gained
  `provider`. The exact-fields test was updated.
- **Shared transport:** the Drive adapter moved onto the shared `ProviderHttp` (behavior
  unchanged; all Drive tests pass).
- **Updated tests:** the migration test now expects V1–V3. The old "Connect Dropbox stays
  disabled" test was replaced, because Dropbox is now available.
- **Wording:** the Overview text changed to "Virtual drive not implemented yet".

### Defects found during this batch and fixed

- **Unwanted PKCE:** Spring Security 7's authorization-request resolver added PKCE to the
  Dropbox request even though none was configured. The authorization-parameter test caught it,
  and it's now stripped explicitly.
- **Weak stale-response test:** the renderer test passed even with the sequence guard removed,
  because the listing key also hid stale data. Without the guard, a late response blanks the
  current listing. The test now also asserts the current listing survives, and the mutation is
  caught.
- **Non-deterministic header:** the `Dropbox-API-Path-Root` JSON was built with `Map.of`, whose key
  order varies between JVM runs. A later full run showed it (the JSON meant the same thing). It
  now uses a fixed order matching Dropbox's documented form.

### Checks executed (Windows 11, Docker 29.7.2, JDK 25.0.5, Node 24.19.0)

| Check | Command | Result |
| --- | --- | --- |
| Backend unit + integration tests | `.\mvnw.cmd verify` | **Passed**: 211 tests, 0 failures, **0 skipped** (PostgreSQL 18 via Testcontainers; fake Google + fake Dropbox) |
| Google regression (sign-in, onboarding, sessions, Drive connect/browse) | included above | **Passed**, all Batch 2/3 suites unchanged in behavior |
| Desktop type check / lint | `npm run typecheck`, `npm run lint` | **Passed** |
| Desktop tests | `npm test` | **Passed**: 167 tests in 12 files |
| Desktop build | `npm run build` | **Passed**; no token-format strings in the renderer bundle |
| Migration on Compose PostgreSQL 18 | Backend start with `auth` profile | **Passed**: V3 applied (history 1, 2, 3) |
| Partial Dropbox config | Start with only `DROPBOX_APP_KEY` | **Fails fast**: "Dropbox is partially configured…" |
| Real Dropbox authorize URL | Running backend, placeholder app key, temporary local user (deleted afterwards) | **Passed** (details below) |
| Logs | grep test and manual-run logs for tokens, tickets, emails, file names, the app secret | **None found** |

Real Dropbox authorize URL check:
- **Providers endpoint:** reported both providers as available.
- **Transaction:** create returned **201** with `no-store`.
- **Redirect:** the ticket URL redirected (302) to `https://www.dropbox.com/oauth2/authorize` with
  exactly `response_type=code`, `client_id`, `scope=account_info.read files.metadata.read`,
  `state`, the Dropbox callback as `redirect_uri`, and `token_access_type=offline`. There was no
  PKCE and no nonce.
- **Cookie:** path `/api/v1/storage-connections/dropbox`, `HttpOnly; SameSite=Lax`.
- **Rejected as intended:** reusing the ticket (400), a forged callback (400) and an
  unauthenticated create (401).

Mutation checks confirm the tests can fail; each mutation was reverted:
- Continuation not bound to the account.
- Dropbox `account_id` check removed.
- `Dropbox-API-Path-Root` header dropped.
- Callback not bound to its provider.
- Ownership filter removed.
- Renderer stale-response guard removed, caught after the test was strengthened.

### Requires manual verification (not executed)

- **Live Dropbox:** no Dropbox app credentials were available. This covers consent, real
  listings and pagination, Paper/cloud docs, refresh after expiry, revocation in Dropbox, a
  second account, and a team-space account. Follow the
  [Dropbox manual verification](dropbox.md#manual-verification-requires-a-dropbox-app).
- **Live Google:** still pending from Batches 2–3 (no OAuth client).
- **Electron window:** the new Files and Cloud Accounts pages were not viewed in a real Electron
  window; they're covered by jsdom tests only.

### Known limitations

- **Revocation:** Dropbox revocation (`auth/token/revoke`) is not called on disconnect;
  disconnect removes local credentials only.
- **Team spaces:** handled per the docs (path root header) but untested on a real Business
  account. Admin and team-token features are unsupported.
- **Second Dropbox account:** needs signing out of Dropbox in the browser first (no account
  chooser parameter is used).
- **Content scope deferred:** `files.content.read` isn't requested yet, so streaming will
  require a reconnect.
- **App status:** Dropbox development mode (your account only, or up to 500 enabled users),
  with no production approval.
- **Still not implemented:** downloads, streaming, caching, writes, mounting and OneDrive.

### Suggested prerequisites for the next batch

- **Live checks:** run both providers' manual checklists with real credentials first; the next
  batch builds on real provider behavior.
- **Credential location:** decide where provider credentials live once the local agent streams
  content. They're backend-only today.
- **Content scopes:** plan incremental consent — `files.content.read` for Dropbox (Drive's
  `drive.readonly` already covers content).

## Batch 3: Google Drive connection, encrypted credentials, read-only browsing (2026-11-22)

### Foundation check before starting

- **Batch 2 re-verified unchanged:** `mvnw verify` gave 111 tests, 0 skipped; desktop
  typecheck, lint and 110 tests passed.
- **Blockers:** none found, so nothing in the sign-in/onboarding foundation had to be fixed.

### Completed

- **Separate Google Drive consent:**
  - Its own Spring client registration (`google-drive`), callback, scopes
    (`openid email profile drive.readonly`), `access_type=offline` and
    `prompt=select_account consent`.
  - It can connect a Google account different from the sign-in account. It never creates or
    changes a HyperDrive login.
- **Connection transactions:**
  - Bound to the user, device session, provider and expiry. The browser leg uses a one-time
    hashed ticket.
  - Hashed OAuth state and a browser-binding cookie, PKCE with an encrypted verifier, and an
    atomic single-use callback.
  - Cancel, supersede and expire. Status and cancel are visible only to the owner.
- **Validation:**
  - Code exchange uses Spring's token client; the ID token is checked by Spring's
    `OidcIdTokenValidator` plus a nonce check.
  - The connection fails if the Drive scope wasn't granted.
  - My Drive's root ID is resolved through `files.get('root')`.
- **Credentials:** AES-256-GCM encryption (JDK) with a key from `HYPERDRIVE_CREDENTIAL_KEY`,
  a key version and previous keys for rotation. Startup fails when the key is missing or
  malformed.
- **Refresh:** server-side, serialized per account with a row lock, and keeps the old refresh
  token when Google omits a new one. `invalid_grant` gives `NEEDS_RECONNECT` without erasing
  credentials. One refresh-and-retry on a 401.
- **Provider abstraction:** `FileBrowser` plus application-owned `ProviderFile`
  (FILE / FOLDER / SHORTCUT / PROVIDER_DOCUMENT). The Google Drive v3 adapter has explicit
  `fields`, `trashed = false`, query escaping, bounded retries with backoff and Retry-After, and
  classified 403s.
- **API:** `/api/v1/storage-connections/...` and `/api/v1/accounts[/{id}[/files[/{fileId}]]]`,
  with ownership checked before any provider call, page size bounded to 200, and sanitized
  error codes. Rate limits for the new endpoints.
- **Disconnect:** deletes the link and credentials only. No Google revocation; the docs explain
  why.
- **Desktop:**
  - A `StorageManager` in the main process: validated authorize URL, polls only its own
    attempts, aborts that account's requests on disconnect, strict response parsers.
  - Validated storage IPC and a narrow `window.hyperdrive.storage` bridge.
  - **Cloud Accounts** page: connect, waiting with Cancel (server-side), and
    failed/cancelled/expired/"stopped waiting" states. It shows the real account identity,
    Needs-reconnect with Reconnect, and Disconnect with confirmation. Dropbox stays disabled.
  - **Files** page: an account selector, breadcrumbs, folder open, Load more, Refresh, and
    loading/empty/error/reconnect states. Type, size (only when known) and modified columns,
    an honest "nothing is cached" note, explanations for unsupported opens, and stale-response
    protection.
- **Database:** Flyway `V2__storage_connections.sql` with `connected_accounts`,
  `provider_credentials` and `storage_connection_transactions`.
- **Docs:**
  - New: [google-drive.md](google-drive.md).
  - Updated: README, architecture, development, security, authentication (pointer) and
    `.env.example`.

### Foundation changes (separate from new features)

- **Browser pages:** `BrowserPages` is now public and HTML-escapes its title and message. A
  sign-in test assertion was updated for the escaped apostrophe (`can&#39;t`).
- **Errors:** `ApiException` can carry `Retry-After`, which the handler sends as a header.
- **Desktop session helper:** `AuthManager.withAccessToken` is now public, so the main-process
  storage code reuses the session, refresh and 401 handling. `SessionExpiredError` is exported.
- **Updated tests:** Batch 1/2 renderer assertions that expected Connect Google Drive to be
  disabled, and the old Settings wording, were updated deliberately. The migration test now
  expects V1 + V2.

### Defects found during this batch and fixed

- **PostgreSQL regex limit:** the first migration draft used the regex `{1,256}`, but
  PostgreSQL caps repetition counts at 255. Every connection insert failed, which the
  integration tests caught. The CHECK now uses `+` plus the column length.
- **Flaky renderer test:** the connection-flow test was timing-dependent with 5 ms polling and
  failed once in a full run. It now completes the attempt only after asserting the waiting
  state; it then passed 3 isolated runs and the full suite.

### Checks executed (Windows 11, Docker 29.7.2, JDK 25.0.5, Node 24.19.0)

| Check | Command | Result |
| --- | --- | --- |
| Backend unit + integration tests | `.\mvnw.cmd verify` | **Passed**: 176 tests, 0 failures, **0 skipped** (PostgreSQL 18 via Testcontainers) |
| Desktop type check / lint | `npm run typecheck`, `npm run lint` | **Passed** |
| Desktop tests | `npm test` | **Passed**: 156 tests in 12 files |
| Desktop build | `npm run build` | **Passed**; no token-format strings in the renderer bundle |
| Migration on Compose PostgreSQL 18 | Backend start with `auth` profile | **Passed**: V2 applied; a later start was "up to date" |
| Missing credential key | Start with `auth` profile and no `HYPERDRIVE_CREDENTIAL_KEY` | **Fails fast**: "HYPERDRIVE_CREDENTIAL_KEY is not set…" |
| Real Google consent URL | Running backend, temporary local test user (deleted afterwards), placeholder client ID | **Passed** (details below) |
| Logs | grep test and manual-run logs for tokens, tickets, emails, file names | **None found** |

Real Google consent URL check:
- The create call returned **201** with `Cache-Control: no-store`.
- The ticket URL redirected (302) to `accounts.google.com/o/oauth2/v2/auth` with scope
  `openid email profile …/drive.readonly`, the storage callback as redirect URI,
  `access_type=offline`, `prompt=select_account consent`, S256 PKCE, state and nonce.
- The binding cookie was `HttpOnly; SameSite=Lax` and path-scoped.
- Rejected as intended: reusing the ticket (400), a forged callback (400) and an
  unauthenticated create (401).

Mutation checks confirm the tests can fail. Each mutation was reverted:
- Ownership filter removed → the cross-user test failed.
- Old refresh token not kept on reconnect → the reconnect test failed.
- Callback replay allowed → the replay test failed.
- Refresh not serialized → the concurrency test failed.
- Renderer stale-response guard removed → the stale-folder test failed.

### Requires manual verification (not executed)

- **Live Google Drive connection and real Drive browsing:** no real OAuth client was available.
  This covers consent with a different Google account, real listings and pagination, native
  documents and shortcuts, token refresh after an hour, and revocation at Google. Follow the
  [manual QA](google-drive.md#manual-qa-requires-real-google-credentials).
- **Electron with the new pages:** the Cloud Accounts and Files pages were not viewed in a real
  Electron window, because that needs a real signed-in session. They are covered by jsdom
  tests only.
- **Google's live behavior:** whether Google returns `scope` and a refresh token exactly as
  assumed on repeat consent, and the Testing-mode 7-day expiry.

### Known limitations

- **Restricted scope:** `drive.readonly` is restricted. The app is unverified with no CASA
  assessment, and Testing mode allows at most 100 test users with a 7-day authorization expiry.
  Not fit for public distribution.
- **Forwarded connection link:** a forwarded authorize link (2-minute ticket) could connect a
  victim's Drive to the sender's HyperDrive account if the victim approves consent. Mitigation
  (loopback confirmation) is not implemented; see [security.md](security.md).
- **Browsing scope:** My Drive only. No Shared drives, "Shared with me", search, opening,
  export, shortcut resolution, downloads, caching or Dropbox.
- **Disconnect:** does not revoke the Google grant; the user removes access at Google.
- **Single instance:** rate limits are in memory. Refresh serialization *is* safe across
  instances (row lock).
- **Key rotation:** lazy only (re-encrypts on refresh or reconnect); there is no bulk tool.
- **Refresh-token limit:** Google keeps 100 refresh tokens per account per client, and older
  connections can be invalidated silently.

### Next suggested batch

Dropbox through the same `FileBrowser` abstraction, or the local agent's first read path (4 MiB
range reads for one Drive file). Decide first where provider credentials should live once the
agent streams content.

## Batch 2: Google sign-in, accounts, onboarding, desktop sessions (2026-11-21)

### Completed

- **Backend** (`SPRING_PROFILES_ACTIVE=auth`; the default stays health-only):
  - Google sign-in via Spring Security OAuth2 Client (`openid profile email` only), with a
    desktop loopback + PKCE handoff.
  - Automatic account creation keyed on OIDC issuer + subject.
  - Username onboarding: rules, reserved names, case-insensitive uniqueness, a database
    constraint for races, and an onboarding gate.
  - Opaque access and refresh tokens, stored hashed, with atomic rotation, replay → family
    revocation, and logout.
  - Readiness endpoint, correlation IDs, sanitized problem responses, in-memory rate limits, an
    expired-data cleanup job, and fail-fast startup checks.
  - Flyway migration `V1__authentication.sql`.
- **Desktop:**
  - `AuthManager` in the main process: loopback listener, PKCE, sign-in orchestration with cancel
    and timeout, single-flight refresh, and sign-out that always clears local credentials.
  - `safeStorage` token store with no plaintext fallback.
  - A narrow preload `auth` API and validated IPC.
  - React screens for all 8 states: checking, signed out, browser sign-in in progress,
    cancelled/failed, onboarding, signed in, session expired, unavailable/not configured.
- **Docs:** `authentication.md` (flow, endpoints, schema, Google Console setup, manual QA),
  `security.md`, and updates to README, architecture, development and `.env.example`.

### Foundation fixes and changes (separate from new features)

- **Deny-by-default security:** with Spring Security on the classpath, unknown paths and wrong
  methods now return **403** instead of 404/405. The Batch 1 tests were updated deliberately.
- **Slice test:** `HealthControllerTest` (`@WebMvcTest`) now imports the health-only security
  configuration.
- **Desktop tooling:** ESLint now allows `_`-prefixed unused arguments, and allows non-null
  assertions in test files only.
- **No Batch 1 defects found:** all Batch 1 checks passed before any changes.

### Defect found during manual verification, then fixed

- **Placeholder taken literally:** with `SPRING_PROFILES_ACTIVE=auth` but **no**
  `GOOGLE_CLIENT_ID`, the backend started and reported READY. Spring kept the literal
  `${GOOGLE_CLIENT_ID}` text as the client ID.
- **Fix:** required variables now default to empty, and `AuthStartupCheck` rejects blank or
  unresolved values and a mismatched redirect URI.
- **Re-verified:** startup now fails with
  `Client id of registration 'google' must not be empty`. Unit tests were added.

### Checks executed (Windows 11, Docker 29.7.2, JDK 25.0.5, Node 24.19.0)

| Check | Command | Result |
| --- | --- | --- |
| Backend unit + integration tests | `.\mvnw.cmd verify` | **Passed**: 111 tests, 0 failures, **0 skipped** (Docker running; PostgreSQL 18 via Testcontainers) |
| Desktop type check / lint | `npm run typecheck`, `npm run lint` | **Passed** (0 errors / 0 problems) |
| Desktop tests | `npm test` | **Passed**: 110 tests in 9 files |
| Desktop build | `npm run build` | **Passed**; no token-format strings in the renderer bundle |
| Migration on Compose PostgreSQL 18 | Backend start with `auth` profile against `docker compose up -d postgres` | **Passed**: V1 applied, all 6 tables present, later start "up to date" |
| Compose PostgreSQL health check | `docker compose up -d postgres` | **Passed**: became `healthy` (first time executed) |
| Missing Google config | Start with `auth` profile and no `GOOGLE_*` | **Fails fast** as intended (after the fix above) |
| Real Google authorization request | `GET /authorize/{id}` with a placeholder client ID | **Passed**: 302 to `accounts.google.com/o/oauth2/v2/auth`, scopes exactly `openid profile email`, state + nonce + S256 PKCE, cookie `HttpOnly; SameSite=Lax` |
| Electron, auth-enabled backend | Built app read over CDP | **Passed** (details below) |
| Electron, health-only backend | Built app read over CDP | **Passed**: "Sign-in isn't enabled" |
| Electron, backend stopped | Built app read over CDP | **Passed**: "Can't reach HyperDrive" with Try again |

Electron with the auth-enabled backend showed:
- The welcome screen, with a single "Continue with Google" button and no inputs.
- `sessionPersistence: "encrypted"`, so DPAPI is available.
- The bridge exposes only the 7 auth functions plus the health check.
- A malformed onboarding payload was rejected by the main process.

Mutation checks confirm the tests can fail:
- PKCE always accepting → `wrongVerifierFailsAndBurnsTheCode` failed.
- No revocation on replay → `replayingAConsumedRefreshTokenRevokesTheWholeSession` failed.
- Refresh not single-flight → the concurrency test failed (5 refreshes instead of 1).

### Requires manual verification (not executed)

- **Live Google sign-in end to end:** no Google OAuth client credentials were available. This
  covers consent, the callback, the loopback handoff in a real browser, and the session
  remembered across a real restart. Follow the manual QA checklist in
  [authentication.md](authentication.md#manual-qa-checklist-requires-real-google-credentials).
- **"Continue with Google" in the real app:** not clicked, because it would have opened your
  browser with a placeholder client.
- **`127.0.0.1` redirect URI:** whether Google Cloud Console accepts it for a Web client has not
  been checked; a `localhost` fallback is documented.

### Known limitations

- **Session lifetime:** an absolute 30-day session (no sliding renewal), and no device list or
  "sign out everywhere".
- **Single instance:** rate limiting is in memory and single-instance, with no proxy support
  (`X-Forwarded-*` ignored).
- **Java version:** still compiled for Java 21 but only executed on JDK 25.
- **Mockito warning:** backend tests still print the harmless Mockito self-attach warning on
  JDK 25.
- **Electron binary after `npm ci`:** `npm ci` removes the downloaded Electron binary, and it's
  fetched again on the next run.
- **Leftovers from the earlier codebase:** they were removed on 2026-11-22 after a verified
  backup. These were the old `hyperdrive-redis-1` container, its volume, and the PostgreSQL 17
  volume `hyperdrive_pgdata`. The backups are in `C:\Users\chaud\Downloads\hyperdrive-old-docker-backup\`,
  outside the repo. Only the new `hyperdrive-postgres-1` and `hyperdrive_postgres-data` remain.

### Next suggested batch

Google Drive connection as a **separate** consent flow:
- Drive-specific scopes and an encrypted provider-token store.
- A connected-accounts model, where disconnecting never deletes the HyperDrive account.
- Read-only listing in the Files view.

## Batch 1: monorepo foundation (2026-11-21)

### Completed

- Monorepo layout: `backend/`, `desktop/`, `agent/` and `vfs/` (README only, planned), `docs/`,
  `compose.yaml`, `.env.example`, `.gitignore`.
- **Desktop shell:** Electron 45.4.1 + electron-vite 6.0.0 + Vite 8.3.8 + React 19.3.1 +
  TypeScript 6.0.3.
  - Sidebar navigation with distinct empty states for Overview, Files, Cloud Accounts,
    Performance and Settings.
  - "Development build" badge.
  - Backend status indicator: checking, connected or unavailable, with reason-specific text.
  - Disabled Google Drive and Dropbox connect buttons, each explained through
    `aria-describedby`.
  - Hardened Electron configuration: sandbox, context isolation, a one-function bridge, IPC
    sender and argument validation, navigation and window-open blocking, permission denial,
    and separate dev and production CSPs.
- **Backend:** Spring Boot 4.2.0, Java release 21, Maven Wrapper 3.3.4 (Maven 3.9.16).
  - `GET /api/v1/health` returns `{"status":"UP","service":"hyperdrive-backend"}` with
    `Cache-Control: no-store`.
  - Binds to `127.0.0.1:18080` by default. Error responses don't include internals.
- **Compose:** PostgreSQL 18 (volume and health check), plus Redis 8 behind the `redis` profile
  (password required, health check). Credentials come from the environment, and host ports bind
  to loopback only.

### Checks executed (Windows 11, Node 24.19.0, npm 11.17.0, JDK 25.0.5, Maven 3.9.16)

| Check | Command | Result |
| --- | --- | --- |
| Clean install from lockfile | `npm ci` | **Passed** |
| Desktop type check | `npm run typecheck` | **Passed** (0 errors) |
| Desktop lint | `npm run lint` | **Passed** (0 problems) |
| Desktop tests | `npm test` | **Passed**, 39 tests in 4 files |
| Desktop build | `npm run build` | **Passed** |
| Backend tests + package | `.\mvnw.cmd verify` | **Passed**, 7 tests, BUILD SUCCESS |
| Backend run (documented command) | `.\mvnw.cmd spring-boot:run` + `Invoke-RestMethod` | **Passed**, returned the expected JSON |
| Compose file syntax | `docker compose --env-file .env.example config -q` | **Passed**. Without `.env` it correctly fails on missing `POSTGRES_PASSWORD`. |
| Compose containers start / become healthy | `docker compose up -d` | **Not executed.** The Docker daemon was not running. |

Desktop test coverage:
- Empty states, branding and the development badge.
- Checking, connected and unavailable states, including a rejected bridge call, a missing
  bridge, and recheck-to-recovery.
- Sidebar navigation by mouse and keyboard, including `aria-current`.
- The disabled connect buttons and their accessible descriptions.
- Health-check behavior: success, unreachable, timeout, non-2xx, wrong service, `DOWN`, and a
  non-JSON body.
- Backend URL validation.
- Trusted-renderer URL checks.

Backend test coverage:
- Exact health JSON and the `no-store` header.
- 405 for POST, and 404 for other paths and `/actuator/*`.
- No stack traces or exception details in error responses.
- A full-context start on a real port with no PostgreSQL or Redis available.

**End-to-end manual probe (built app, real Electron window, read over the Chrome DevTools Protocol):**
- **Backend running:** showed "Backend: connected".
- **Backend JVM stopped, then "Check again":** showed "Backend: unavailable" ("could not be
  reached").
- **Backend restarted, then "Check again":** showed "connected".
- **Pointed at an unrelated Apache server on :8080:** showed "unavailable" with the "did not
  respond like the HyperDrive backend" message.
- **Renderer isolation:** `require` and `process` were undefined, and `window.hyperdrive`
  exposed only `checkBackendHealth`.
- **Blocked by policy:** a direct renderer `fetch` was blocked by the CSP, and `window.open`
  returned `null`.
- **`npm run dev`:** the window loaded from the Vite dev server with the dev CSP, and the bridge
  was present.

### Known limitations

- **Java 21:** the JDK on the development machine is 25. The code is compiled with
  `--release 21`, but it has **not been run on a JDK 21 runtime**.
- **Electron window checks:** a real window was opened and inspected programmatically on
  Windows 11. There are no automated Electron end-to-end tests (for example, Playwright) in the
  repository yet.
- **Not yet set up:** packaging, an installer, code signing, a `LICENSE` file and CI.
- **No authentication:** the backend has none and must stay bound to loopback.
- **Mockito warning:** the backend tests print Mockito's "self-attaching" warning on JDK 25.
  It's harmless today; configure Mockito as a Java agent before newer JDKs drop
  self-attachment.

### Next suggested batch

**Authentication and first-time username onboarding:**
- Google sign-in that creates a HyperDrive account on first sign-in.
- A username chosen manually during first-time onboarding, with validation and uniqueness
  checks.
- The backend gains PostgreSQL persistence and a real security configuration.
- The desktop gets a secure sign-in flow (system browser + loopback/PKCE, not an embedded
  login page).

Cloud-storage connections remain a separate, later batch.
