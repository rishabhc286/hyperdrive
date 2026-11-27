# Live verification (checkpoint before Batch 8)

This page is the runbook for verifying HyperDrive against **real** Google and Dropbox accounts,
and the record of what has and hasn't been verified live. Nothing here is marked "passed live"
unless it was run against the real provider with real credentials.

Status values: **Passed live**, **Passed with mocks only**, **Failed**, **Not executed** (with the
blocker).

## 1. Configuration you perform

Never paste secrets into a chat, an issue or a commit. Keep them in your own PowerShell session or
in a script **outside the repository** (for example `%USERPROFILE%\hyperdrive-dev-env.ps1`, which
you dot-source with `. $HOME\hyperdrive-dev-env.ps1`). `.env` is git-ignored but is read only by
Docker Compose, not by the backend or the desktop.

Fixed local values (unchanged): backend `http://127.0.0.1:18080`, PostgreSQL host port `55432`.

### 1a. Google Cloud: sign-in and Drive

Use a **dedicated test Google Cloud project**. Revoking access is project-wide at Google (see
[google-drive.md](google-drive.md#disconnect-vs-revoking-access-at-google)), so don't reuse a
project that other apps depend on.

1. <https://console.cloud.google.com/> → create project (e.g. "HyperDrive dev").
2. **APIs & Services → Library → Google Drive API → Enable.**
3. **Google Auth Platform → Branding:** app name, support email, developer contact.
4. **Audience:** User type **External**, publishing status **Testing**. **Test users:** add every
   Google account you will use: the sign-in account and each account whose Drive you connect
   (use dedicated test accounts holding only non-sensitive files).
5. **Data access (scopes):** `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`,
   `.../auth/drive.readonly` (restricted scope; fine for test users in Testing mode).
6. **Clients → Create client → Web application.** Authorized redirect URIs, exactly:
   - `http://127.0.0.1:18080/login/oauth2/code/google` (sign-in)
   - `http://127.0.0.1:18080/api/v1/storage-connections/google/callback` (Drive connection)

   No JavaScript origins are needed. If the console rejects `127.0.0.1`, use `localhost` in both
   URIs **and** set `HYPERDRIVE_PUBLIC_BASE_URL` and `HYPERDRIVE_BACKEND_URL` to
   `http://localhost:18080`.
7. Copy the client ID and secret into your local env script (below), not anywhere else.

Sign-in and Drive use the **same client by default** (`GOOGLE_DRIVE_CLIENT_ID` falls back to
`GOOGLE_CLIENT_ID`). A second client in the **same** project doesn't isolate revocation; only a
separate project would.

Testing-mode limit: Google expires refresh tokens of Testing apps with restricted scopes after
7 days; a Drive connection then shows **Needs reconnect**.

### 1b. Dropbox app

1. <https://www.dropbox.com/developers/apps> → **Create app** → **Scoped access** → **Full
   Dropbox** → name it (e.g. "HyperDrive dev <you>").
2. **Permissions tab:** enable `account_info.read`, `files.metadata.read` and
   `files.content.read`, then **Submit**. (No write, sharing or team scopes.)
3. **Settings → OAuth 2 → Redirect URIs:** add exactly
   `http://127.0.0.1:18080/api/v1/storage-connections/dropbox/callback`.
4. The app is in **development** status: it can link your own account; to use a separate test
   account, add it under **Development users** (or link only the owner account).
5. Copy the **App key** and **App secret** into your local env script.

### 1c. Local environment

`.env` (Docker Compose only; already present on this machine with the PostgreSQL values):
`POSTGRES_PASSWORD`, `POSTGRES_PORT=55432`.

Backend PowerShell window (from your env script):

```powershell
$env:SPRING_PROFILES_ACTIVE      = "auth"
$env:HYPERDRIVE_DB_URL           = "jdbc:postgresql://127.0.0.1:55432/hyperdrive"
$env:HYPERDRIVE_DB_PASSWORD      = "<POSTGRES_PASSWORD from .env>"
$env:HYPERDRIVE_PUBLIC_BASE_URL  = "http://127.0.0.1:18080"
$env:GOOGLE_CLIENT_ID            = "<Google client id>"
$env:GOOGLE_CLIENT_SECRET        = "<Google client secret>"
$env:HYPERDRIVE_CREDENTIAL_KEY   = "<base64 of 32 random bytes; generate once, keep stable>"
$env:DROPBOX_APP_KEY             = "<Dropbox app key>"
$env:DROPBOX_APP_SECRET          = "<Dropbox app secret>"
```

Generate the credential key once (prints only to your own window; store it in the env script):

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Desktop window: nothing required (`HYPERDRIVE_BACKEND_URL` defaults to
`http://127.0.0.1:18080`). Build the agent once: `cd agent; .\mvnw.cmd -q package -DskipTests`.

Start: `docker compose up -d postgres` → backend `.\mvnw.cmd spring-boot:run` → desktop
`npm run dev`. To check presence without printing values:
`'GOOGLE_CLIENT_ID','DROPBOX_APP_KEY','HYPERDRIVE_CREDENTIAL_KEY' | % { "$_ set: $([bool](Get-Item env:$_ -ErrorAction SilentlyContinue))" }`.

## 2. Test files

Put the same small, non-sensitive files in the test Drive and the test Dropbox, in a root and a
nested folder (e.g. `/HD-Test/nested/`), and keep the originals locally:

| File | Size | Purpose |
| --- | --- | --- |
| `hd-small.bin` | 1,000 bytes | offset 0, nonzero offset, final partial read, EOF |
| `hd-5mib.bin` | 5,242,880 bytes | crosses the 4 MiB chunk boundary (Dropbox cache) |
| `hd-empty.bin` | 0 bytes | empty file |

Expected digests come from the local copies, e.g. for bytes `[100, 600)`:

```powershell
$f=[IO.File]::ReadAllBytes("hd-small.bin"); $s=[Security.Cryptography.SHA256]::Create()
[BitConverter]::ToString($s.ComputeHash($f,100,500)).Replace('-','').ToLower()
```

Compare with the SHA-256 shown by **Verify file read**. Record only sizes, offsets, digests,
status codes and cache outcomes: never tokens or file contents.

## 3. Checklist and current status

| # | Check | How | Status |
| --- | --- | --- | --- |
| 1 | First Google sign-in + username onboarding | Continue with Google → choose username | **Not executed**: no Google OAuth client configured on this machine |
| 2 | Returning sign-in reaches the same HyperDrive account | Sign out, sign in again with the same Google account; same username | **Not executed**: same blocker |
| 3 | Separate Google Drive connection | Cloud Accounts → Connect Google Drive with a *different* test account | **Not executed**: same blocker |
| 4 | Separate Dropbox connection (incl. `files.content.read`) | Connect Dropbox; account shows "Connected", no "can't read file contents" note | **Not executed**: no Dropbox app configured |
| 5 | Root and nested browsing | Files → open `/HD-Test/nested/` on each provider | **Not executed**: blockers 1–4 |
| 6 | Bounded read, offset 0 | Verify file read `hd-small.bin`, offset 0, length 100 → digest matches | **Not executed** (mock-tested) |
| 7 | Bounded read, nonzero offset | offset 100, length 500 → digest of `[100,600)` | **Not executed** (mock-tested) |
| 8 | Final partial read and EOF | offset 900, length 500 → 100 bytes, EOF; offset 1000 → 0 bytes, EOF | **Not executed** (mock-tested) |
| 9 | Clear permission / reconnect errors | Revoke at provider, or old Dropbox connection without content scope → "Needs reconnect" / "can't read file contents" | **Not executed** (mock-tested) |
| 10 | Logout and disconnect cleanup | Sign out: cache status shows 0 entries for the user; disconnect: account's chunks gone, other account unaffected | **Not executed** live (mock-tested; races covered by regression tests) |
| 11 | Dropbox revision-pinned partial download (cache eligibility) | Section 4 | **Not executed**: no Dropbox app |
| 12 | Google reads bypass the persistent cache | Verify file read on Drive → "Bypassed: provider not version-bound" | **Not executed** live (mock-tested) |
| 13 | Dropbox "Revoke access and disconnect" | Section 5 | **Not executed** live (mock-tested) |
| 14 | Real Windows `safeStorage` key protection | Section 6 | **Passed live** (2026-11-23, this machine) |
| 15 | Mounted drive: copy a Google Drive binary out, compare SHA-256 | Mount → Explorer → copy `hd-5mib.bin` to a local folder → `Get-FileHash` vs. original | **Not executed**: blockers 1–3 (fixture-tested) |
| 16 | Mounted drive: copy a Dropbox binary out, compare SHA-256 | Same with the Dropbox copy | **Not executed**: blocker 4 (fixture-tested) |
| 17 | Mounted drive: random-offset reads | PowerShell `[IO.File]::OpenRead` + `Seek`/`Read` at several offsets vs. the local file | **Not executed** (fixture-tested) |
| 18 | Mounted drive: Dropbox cache hits, Google bypass | Read a Dropbox file twice with non-cached I/O (`vfs/tests/unbuffered-read.ps1`); card's "From the local encrypted cache" grows for Dropbox only | **Not executed** (fixture-tested) |
| 19 | Mounted drive: PDF opens in an installed viewer | Open a test PDF from `H:\` | **Not executed**: no live account; no viewer tested |
| 20 | Mounted drive: video opens and seeks in an installed player | Open a test video, seek forward/back | **Not executed**: no live account; no player tested |
| 21 | Mounted drive: sign out / disconnect while a large copy is running | Start copying a large file, then sign out (or disconnect) | **Not executed** live (fixture: unmount with outstanding read passes) |

## 4. Dropbox cache-eligibility record

For `hd-5mib.bin`, run Verify file read at offset 4,194,000 length 1,000 (crosses the chunk
boundary) twice. The agent logs no tokens or contents; record from the diagnostic and agent status:

| Field | Value |
| --- | --- |
| Requested revision | `rev` from the listing (first 4 chars only) |
| Requested range | e.g. bytes 0–4,194,303 and 4,194,304–5,242,879 (chunk fetches) |
| Response status | 206 expected |
| Returned range | from `Content-Range` (validated by the agent) |
| Returned revision metadata | whether `Dropbox-API-Result` carried a `rev`, and whether it matched |
| Byte count | requested bytes vs. provider bytes fetched |
| Cache outcome | first: `miss` (admitted) or `bypass`/not admitted with reason; second: `hit` |

If the first read reports **not stored: `revision_unconfirmed`**, Dropbox didn't confirm the
revision on the ranged response. That is a limitation to report; the validation is **not** to be
weakened. Uncached reads keep working.

## 5. Revoke-and-disconnect (Dropbox)

1. Connect the test Dropbox account. Cloud Accounts → **Revoke access…** → confirm.
2. Expect "Dropbox confirmed that this connection's authorization was revoked" and the account
   gone from HyperDrive.
3. At <https://www.dropbox.com/account/connected_apps>, record whether HyperDrive is still listed
   (undocumented; other connections may hold their own grants).
4. Negative: disconnect Wi-Fi, repeat on a fresh connection → outcome "unknown", account still
   disconnected locally, manual instructions shown.

## 6. Windows key protection: live result (2026-11-23)

Run with the real Electron 45.4.1 `safeStorage` on Windows 11 (this development machine), the
real `CacheKeyStore`, `CacheCoordinator` and agent jar, in four separate Electron processes
(scratch harness, not committed). Evidence is sanitized (no keys, no wrapped blobs):

| Check | Result |
| --- | --- |
| OS encryption available, store enabled | `isEncryptionAvailable() = true`, store available |
| Key creation | 32-byte key, `fresh = true`; key file `{v:1, data}`, wrapped blob starts with Electron's `v10` marker (75 bytes) |
| No plaintext | key file contains neither the key's base64 nor its raw bytes, nor the user id |
| Recovery after restart | new process: `fresh = false`, same key fingerprint; agent session started, cache enabled |
| Corrupted ciphertext / truncated file / missing file | each: new key, `fresh = true` (agent purges the user's old, now unreadable entries); session started |
| Key file wrapped under another Electron profile | not decryptable here → new key, `fresh = true` |
| Logout | wrapped key file deleted |
| Key or key-file path in agent log | no |
| Encrypted cache **hit** with the real key | **Not executed**: needs an eligible Dropbox read (section 4) |
| Cache disabled when protection is unavailable | **Passed with mocks only**: DPAPI can't be made unavailable on this machine |

**Threat model (actual):** Electron's `safeStorage` on Windows encrypts with a key that is itself
protected by DPAPI **for the current Windows user** (stored in the app's `Local State`). It
protects the cache key, and so the cached chunks, against reading the disk offline, other Windows
users, and copying the files to another profile or machine. It does **not** protect against
software running as the same Windows user, which can ask DPAPI to unwrap the key or read the
agent's memory. It also doesn't survive a Windows password reset that discards DPAPI keys; the
cache then starts over with a new key.
