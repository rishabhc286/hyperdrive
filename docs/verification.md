# Verification record (Batch 10)

What has been verified, how, and with what evidence. Nothing is "Passed live" unless it ran against
the real thing named: real Google/Dropbox for provider items, the real Windows component for OS
items. The OAuth setup runbook is [live-verification.md](live-verification.md); this page is the
record. Evidence is sanitized: sizes, hashes, timings, statuses; never tokens or file contents.

**Status values:** **Passed live** · **Passed with fixture only** (real WinFsp mount, real VFS, real
agent, real Windows apps, but the TEST-ONLY fixture provider and a local stand-in backend instead of
Google/Dropbox) · **Passed with mocks only** (unit/integration tests with fakes) · **Failed** ·
**Not executed** (with the blocker).

**Current blocker for every live provider item:** no Google OAuth client or Dropbox app is
configured on this machine (checked by variable presence only, 2026-11-27). The setup steps are in
[live-verification.md §1](live-verification.md#1-configuration-you-perform).

## How earlier evidence was obtained

| Batch | Evidence kind |
| --- | --- |
| 1–5 | Unit/integration tests (fake Google OIDC, fake Dropbox, Testcontainers PostgreSQL); agent smoke test with the real jar |
| 6–7 | Agent tests against local mock provider servers; ChunkCache tests with a fake reader |
| Checkpoint | **Live:** Windows `safeStorage` key wrapping/recovery (no cache hit); everything else mocks |
| 8 | Real WinFsp mount with fixture data; real agent + VFS against a stand-in backend |
| 9 | Real WinFsp mount, real agent + VFS, **fixture provider** (`content-mount-check.mjs`) |
| 10 | Adds: real-mount reliability check, real applications on the mount (synthetic files via the fixture provider), real Electron safeStorage + encrypted cache hit, dependency scans |

## Verification matrix

### Accounts and sessions

| Check | Status |
| --- | --- |
| First Google sign-in and username onboarding | Not executed (OAuth not configured); Passed with mocks only |
| Returning sign-in reaches the same account | Not executed (OAuth); Passed with mocks only |
| Session refresh and logout | Not executed live (OAuth); Passed with mocks only |
| Google Drive connection separate from sign-in | Not executed (OAuth); Passed with mocks only |
| Dropbox connection separate from sign-in | Not executed (no Dropbox app); Passed with mocks only |
| Reconnect after the Testing-mode 7-day refresh-token expiry | Not executed (OAuth; needs 7 days) |

### Browsing

| Check | Status |
| --- | --- |
| Real root and nested folders, both providers | Not executed (OAuth/Dropbox app) |
| Pagination (folder larger than one provider page) | Not executed live; Passed with fixture only (300-entry folder, Batch 8) and mocks |
| Unicode, trailing dots/spaces, reserved names, case-only and exact duplicates | Not executed live; Passed with fixture only (Batch 8 `fixture-mount-check.mjs`) |
| Native Google documents and shortcuts shown honestly as unsupported | Not executed live; Passed with mocks/fixture (hidden on the drive, labeled in the app) |

### Reads

| Check | Status |
| --- | --- |
| Verify file read: offset 0, nonzero, final partial, EOF (both providers) | Not executed live; Passed with mocks only |
| Zero-byte file | Not executed live; Passed with fixture only |
| File > 4 MiB through the drive | Not executed live; Passed with fixture only (9 MiB, 512 MiB, 5 GiB) |
| File > 4 GiB through the drive | Not executed live; Passed with fixture only (5 GiB + 777 B) |
| SHA-256 of a full copy out of the drive equals the original | Not executed live; **Passed with fixture only** (CopyFile and Explorer's shell copy engine) |

### Cache

| Check | Status |
| --- | --- |
| Dropbox revision proof on partial downloads (`Dropbox-API-Result` on 206) | **Not executed** (no Dropbox app). The decision stays: admit only when the response proves the revision; otherwise bypass (`revision_unconfirmed`). Record template: [live-verification.md §4](live-verification.md#4-dropbox-cache-eligibility-record) |
| Google: no persistent content written | Passed with fixture only (cache bytes 0 for every Google read; chunk count unchanged) and mocks |
| No plaintext content files in the cache directory | **Passed live** for the local mechanism (real encrypted cache; a 4 KiB run of the cached file's bytes searched in every cache file: not found) |

### Windows key protection

| Check | Status |
| --- | --- |
| Real Electron `safeStorage`/DPAPI: create key, restart, recover the same key | **Passed live** (Electron 45.4.1, separate processes; fingerprint equal; wrapped blob starts `v10`; no plaintext key or user id in the file) |
| Read an eligible cached chunk after restart | **Passed live** for key + cache (encrypted cache **hit**, exact SHA-256); provider was the fixture |
| Corrupted / truncated / missing key file | **Passed live**: a new key, the user's old entries purged (never decrypted with a wrong key), reads keep working (miss → admitted again) |
| OS protection unavailable → disk cache disabled, uncached reads work | Passed with mocks only (DPAPI can't be made unavailable on this machine) |

Note: an *unreadable* key is replaced rather than disabling the cache; caching is disabled only when
OS protection is unavailable. Both fail safe: no cached data is ever decrypted with a wrong key.
**DPAPI protects the key against other Windows users and offline disk access, not against software
running as the same Windows user.**

### Lifecycle (all on a real mount; provider = fixture)

| Check | Status | Evidence |
| --- | --- | --- |
| Unmount while a read is in progress | Passed with fixture only | read failed `ECANCELED`, VFS exited in 80 ms |
| Logout / account disconnect during a large read | Not executed live (needs sign-in); the same agent paths (revoke, `credentials/clear`) Passed with mocks |
| Agent killed mid-read | Passed with fixture only | read failed after 3 ms; VFS self-unmounted (`agent-unreachable`) after ~37 s |
| VFS killed mid-read | Passed with fixture only | drive letter removed by WinFsp/Windows |
| Desktop ("Electron") process killed | Passed with fixture only | a test process standing in for Electron (owns agent + VFS by pipes) killed: VFS and agent exited by themselves in 848 ms; no orphan. Real Electron crash: not executed |
| Backend stopped mid-read | Passed with fixture only | reads on an open file continued (512 MiB completed); a new open failed in 6 ms; after restart the same file opened |
| Network disconnected mid-read, then restored | Passed with fixture only (simulated by the fixture provider cutting connections) | read failed after 618 ms (no hang, no zero-fill); new open after restore exact. Real adapter disconnect: not executed |
| Provider token expiry during a long read → one renewal | Passed with fixture only | a 100 s credential was renewed exactly once during a 5 GiB read |
| No orphaned agent/VFS, no stuck mount, only owned processes terminated | Passed with fixture only; one defect fixed (javapath launcher, below) | |

### Electron-driven live flows

| Check | Status |
| --- | --- |
| Mount/unmount from the real desktop UI with a signed-in user | Not executed (needs live sign-in) |

## Tested applications (synthetic, non-sensitive files via the fixture provider)

`vfs/tests/app-compat-check.mjs`. All passed. "Agent reads" counts only reads that reached the
agent; repeated reads answered by **Windows' own cache never reach the VFS** and aren't counted.
"Cache" is the agent's encrypted cache (Dropbox only).

| Application (how driven) | What | Agent reads | Provider B | Agent cache B | Errors |
| --- | --- | --- | --- | --- | --- |
| Explorer shell (COM property system) | Properties: real size | 0 | 0 | 0 | – |
| Explorer shell (`CopyHere`, the Explorer copy engine) | Copy 7.06 MB out, SHA-256 equal | 7 | 7,056,044 | 1,048,576 | – |
| Explorer zip folder (`zipfldr`) | List a ZIP on the drive | 79 | 60,113 | 218 | – |
| Explorer shell (`IShellItemImageFactory`) | Thumbnail request returns (no hang), 575 ms | 2 | 107,387 | 41,851 | – |
| Windows Imaging Component | Decode PNG / JPEG (Dropbox), JPEG (Google) | 76 / 35 / 37 | 191,298 / 0 / 227,120 | 255,817 / 119,733 / 0 | – |
| Microsoft Edge (headless) | Open and print `doc.pdf` (30 pages), Dropbox and Google | 2 / 2 | 95,868 / 95,868 | 94,844 / 0 | – |
| VLC 3 (dummy interface, no output devices) | Play MP4 from start, seek to 30 s, back to 5 s (Dropbox and Google) | 65–69 each | see log | see log | – |
| VLC 3 | Play/seek a 7 MB WAV | 66–95 | 0 (cached) | ~650 KB each | – |
| VLC + CopyFile concurrently | Two apps read the same file; copy SHA-256 equal | 95 | 0 | 7,700,672 | – |
| .NET `File.ReadAllText` | Read a UTF-8 text file exactly | 327 | 1,328,888 | 1,392,112 | – |

**Not tested (no compatibility claimed):** interactive use of Notepad, Paint, Photos, Acrobat,
Windows Media Player, Explorer drag-out, the Explorer preview pane, Office, memory-mapped workloads,
byte-range locks. Manual steps are in the checklist below.

**Observed system behavior:** Windows Defender real-time protection is on and scans files when they
are opened (~128 KiB extra agent reads per open). Windows Search is running; HyperDrive volumes are
not in its indexed locations by default, and no indexer reads were observed in the counters.

## Measurements (this PC, fixture provider over loopback; not cloud figures)

These isolate HyperDrive's own overhead. Real provider latency and bandwidth will dominate live and
are **not measured** (no credentials).

| Metric | Dropbox path (encrypted cache) | Google path (uncached) |
| --- | --- | --- |
| First byte (open + first 4 KiB, cold) | 159 ms | 18 ms |
| Cold seek, 4 KiB (median / max of 8) | 68 / 73 ms | 3.7 / 4.2 ms |
| Sequential read (buffered, 1 MiB app reads) | 88 MiB/s over 5 GiB | 189 MiB/s over 512 MiB |
| Read amplification, random 4 KiB | **1024×** (each cold read fetches its whole 4 MiB chunk) | 1.0× |
| Read amplification, sequential | 1.00× (each chunk fetched once) | 1.00× |
| Memory during a 5 GiB read (working set) | agent JVM 362–407 MiB, VFS 12–14 MiB, both flat | – |

The Dropbox random-access cost is by design (Batch 7: whole-chunk caching) and matters for video
seeking: each cold seek on Dropbox downloads up to 4 MiB (8 MiB across a boundary).

## Defects found in Batch 10

| # | Defect | Severity | Status |
| --- | --- | --- | --- |
| 1 | **Orphaned JVM:** with `java` resolved from PATH to Oracle's `javapath` launcher, killing the owned child killed only the launcher; a hung agent JVM survived (reproduced: 1 JVM left) | Medium (stuck agent, data-dir lock) | **Fixed:** on Windows a forced stop ends the owned child's process tree by PID (`System32\taskkill.exe /PID n /T /F`, absolute path, never by name); reproduced fix: 0 JVMs left. Unit tests for the argv |
| 2 | **Overstated provider-byte counters:** reads sharing one in-flight chunk fetch each reported the whole chunk (7 MB copy showed 18–21 MB "downloaded"; real traffic was 7 MB) | Low (UI/metrics accuracy) | **Fixed:** a shared fetch's bytes are attributed to exactly one waiting read. Regression test: 4 waiters report 4 MiB, not 16 MiB |
| 3 | **Possible renewal loop:** a credential with < 2 min left requested a background renewal on every read; a short-lived answer would repeat it | Low (no current trigger: the backend guarantees ≥ 5 min) | **Fixed:** at most one renew-ahead per account per minute. Regression test |
| 4 | Test-harness issues (not product): quiet Maven output hid a test compile error; `java` stub measured instead of the JVM; top-level `await app.whenReady()` deadlocks an Electron ESM main | – | Fixed in the harnesses |

**Open (known, not fixed in this batch):** Google same-size modification not detected (documented
design limit); Dropbox random-read amplification (design); blocking callbacks up to 45 s; per-read
application cancellation not propagated; Disconnect unmounts the whole drive; files shown offline.

## Security review (Batch 10)

| Area | Finding |
| --- | --- |
| Secrets in logs | Reviewed backend/agent/desktop log statements: ids, codes and class names only. Identity-validation messages are fixed strings. Checks assert no capability, grant, token, `hdf_`/`hdh_` handle or key path in agent/VFS output |
| Command lines | Agent: `java -jar <path>` / test classpath, secret on stdin; VFS: no arguments, capability on stdin. Nothing secret in argv or environment |
| IPC | Renderer receives states, counters and codes only (checked preload and every `webContents.send`) |
| URLs | Authorization URLs carry one-time state (by design, opened in the system browser); no tokens in any URL |
| Scopes | Mount capability: only `/local/v1/vfs/*` capability routes. Read handle: one file, one generation, plus capability and unexpired grant. Agent launch secret: admin routes only, never accepted on capability routes and vice versa (tests) |
| Isolation | Foreign account/file ids → 404 at the backend; cache keys and file names per user/account/revision; tests cover cross-account handles |
| Cache paths | File names are validated hex patterns (`<32 hex>-<64 hex>.hdc`) under fixed directories; no path ever comes from a request |
| Loopback | Agent binds 127.0.0.1 with Host/Origin checks; backend defaults to 127.0.0.1:18080; desktop sign-in listener 127.0.0.1; VFS has no listener |
| Electron | `nodeIntegration:false`, `contextIsolation:true`, `sandbox:true`, `webSecurity:true`, `webviewTag:false`, navigation/IPC restricted to the app's renderer origin (unchanged) |
| Process termination | Only owned children (now including the owned child's own process tree, by PID); never by name |
| Wording | UI/docs say Disconnect deletes stored access locally and does not revoke at the provider; "Revoke access" reports only what Dropbox confirmed; cache deletion doesn't claim secure erase; encryption claims match DPAPI's actual scope |

**Dependency scans (2026-11-27):**

| Component | Tool | Result |
| --- | --- | --- |
| Desktop (npm, incl. Electron) | `npm audit` | 0 known vulnerabilities |
| Backend + agent (84 resolved Maven runtime artifacts) | OSV database (`api.osv.dev` batch query; names and versions only) | 0 with known advisories |
| Native: nlohmann/json 3.11.3, WinFsp 2.1 | – | **Not scanned automatically** (no OSV package ecosystem); unresolved: check their GitHub security advisories before release |
| Maven test-scope dependencies (Testcontainers, JUnit) | – | Not scanned (not shipped) |

## Manual QA checklist (repeat for each release candidate)

Use dedicated test accounts and the non-sensitive files from [live-verification.md §2](live-verification.md#2-test-files).
Record only sizes, hashes, timings and statuses.

**Automated, on this PC (no credentials needed):**

```powershell
cd agent; .\mvnw.cmd verify; .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/fixture-classpath.txt"; cd ..
node vfs\tests\fixture-mount-check.mjs vfs\build\hyperdrive-vfs.exe
node vfs\tests\agent-mount-check.mjs agent\target\hyperdrive-agent.jar vfs\build\hyperdrive-vfs.exe
node vfs\tests\content-mount-check.mjs agent vfs\build\hyperdrive-vfs.exe
node vfs\tests\reliability-check.mjs agent vfs\build\hyperdrive-vfs.exe     # ~4 min
node vfs\tests\app-compat-check.mjs agent vfs\build\hyperdrive-vfs.exe      # needs Edge and VLC for those rows
node vfs\tests\key-protection-check.mjs agent desktop                       # real Electron safeStorage
```

**Live, with the app (after the OAuth setup):**

1. Sign in (new HyperDrive account) → username → sign out → sign in again: same username.
2. Connect Google Drive (a different test Google account) and Dropbox; both "Connected".
3. Browse root and `/HD-Test/nested/` on both; open a folder with > 200 entries; check the
   special-name folder (Unicode, `CON`, trailing dot, `a.txt`/`A.TXT`, duplicates).
4. Verify file read on `hd-small.bin` at offsets 0, 100, 900 and 1000 (EOF) on both providers;
   compare SHA-256 with the local file.
5. Dropbox: Verify file read across the chunk boundary twice; fill in the §4 cache record.
6. Mount → Explorer: copy `hd-5mib.bin` out of each account; `Get-FileHash` vs the original.
7. Open `hd-test.pdf` in Edge and Acrobat and scroll; open `hd-test.mp4` in VLC and in Media Player,
   play, seek forward and back; open an image in Photos/Paint; a text file in Notepad; open a ZIP
   in Explorer; select files with the preview pane on (Explorer must stay responsive).
8. During a large copy: sign out (copy fails, drive unmounts); repeat with Disconnect; repeat with
   Unmount; repeat with Wi-Fi off, then on (copy fails; a new copy works).
9. Task Manager: end the HyperDrive desktop process during a copy → drive disappears, no
   `hyperdrive-vfs.exe` or HyperDrive `java.exe` remains.
10. Note the card's counters after each step; compare "Downloaded from the cloud" with file sizes.

## Remaining unverified items

Everything in the matrix marked Not executed, chiefly: live Google/Dropbox sign-in, browsing,
reads, Dropbox revision proof on ranged downloads (cache decision evidence), live application use,
real network loss, real Electron crash, logout/disconnect during a live read, and the 7-day
Testing-mode reconnect.
