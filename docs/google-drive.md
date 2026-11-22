# Google Drive connection (Batch 3)

HyperDrive can connect a Google Drive account and browse its **My Drive** folders and file
metadata, **read-only**, inside the desktop app. File contents are never downloaded, previewed,
streamed or cached in this batch, and nothing is written to Drive.

> **Verification status.** Automated tests cover the flow against an in-process fake Google
> (OIDC + token + Drive endpoints). A **live** Google Drive connection and real Drive browsing
> have **not** been tested yet, because no real OAuth client was available. See
> [Manual QA](#manual-qa-requires-real-google-credentials).

## Sign-in vs. storage permissions

| | HyperDrive sign-in (Batch 2) | Google Drive connection (Batch 3) |
| --- | --- | --- |
| Purpose | Who you are in HyperDrive | Which Drive HyperDrive may read |
| Scopes | `openid profile email` | `openid email profile` + `https://www.googleapis.com/auth/drive.readonly` |
| Started by | "Continue with Google" | Cloud Accounts → "Connect Google Drive" (signed in and onboarded) |
| Google callback | `/login/oauth2/code/google` | `/api/v1/storage-connections/google/callback` |
| Spring client registration | `google` | `google-drive` |
| Google tokens stored? | No | Yes: encrypted on the backend (AES-256-GCM) |
| Effect on identity | Creates or finds the HyperDrive user | **None**: never signs anyone in, never creates or changes a HyperDrive user or login identity |

- **Different accounts:** the Drive account can be a different Google account from the one you
  signed in with. Google's account chooser is always shown (`prompt=select_account consent`).
- **Why `openid email profile` too:** they identify *which* Google account's Drive was
  connected. The connection is keyed on the validated ID-token issuer + subject, never on email.
- **Sign-in is unchanged:** sign-in never requests Drive scopes.

### Scope choice: `drive.readonly`

- **What it grants:** Google describes it as "View and download all your Drive files"
  ([Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth),
  read 2026-11-22). It's the narrowest scope that lets HyperDrive list **existing** files
  across the user's Drive, and later read their content for the planned read-only drive.
- **Why not `drive.file`:** that only covers files the user creates with, or opens in, this app
  (for example through the Google Picker). It doesn't give access to the existing files in a
  Drive, so it can't support "browse my whole Drive". HyperDrive doesn't use it, and doesn't
  claim it would be enough.
- **Why not `drive.metadata.readonly`:** it would be enough for this batch's listing, but not
  for the planned content reads. It is also a **restricted** scope, so it would not make
  verification any easier.

### Google's classification and what it means for publishing

These are Google's rules as documented when this was written (2026-11-22). Check the linked
pages again before publishing; nothing here has been confirmed with Google.

- **Restricted scope:** `drive.readonly` is classified **restricted**
  ([scope list](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)).
- **Verification and security assessment:** apps using restricted scopes need OAuth
  verification. Because HyperDrive's backend stores Drive tokens and accesses Drive data from a
  server, Google's rules call for an annual third-party **security assessment** (CASA)
  ([restricted-scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)).
- **Exceptions:** the same page lists exceptions, such as personal use (only you or a few users
  you know personally), development/testing/staging, and internal use within a Google Workspace
  organization.
- **Status:** HyperDrive has **not** been verified, assessed or approved by Google, and is
  **not** ready for public OAuth distribution. Treat it as a self-hosted development build.

### Testing-mode limits

These limits come from Google's
[OAuth consent / audience help](https://support.google.com/cloud/answer/15549945):

- **Test users:** at most **100**, listed on the consent screen.
- **Warning screen:** test users see an "unverified app" warning before granting access.
- **7-day expiry:** authorizations for scopes beyond `openid`, email and profile **expire 7
  days after consent**, and so does the refresh token. After that HyperDrive marks the account
  **Needs reconnect**. This is expected in Testing mode, not a bug.
- **Refresh-token limit:** Google keeps at most **100 refresh tokens per Google Account per
  OAuth client**. Creating more silently invalidates the oldest
  ([OAuth 2.0 overview](https://developers.google.com/identity/protocols/oauth2)). Many
  reconnects, or many HyperDrive users connecting the same Google account through one client,
  can therefore invalidate older connections.

## Google Cloud setup

You can reuse the Web application client from [authentication.md](authentication.md#google-cloud-console-setup).

1. **Enable the Drive API:** APIs & Services → Library → **Google Drive API** → Enable.
2. **Add the storage redirect URI:** Credentials → your **Web application** OAuth client →
   Authorized redirect URIs → add exactly
   `http://127.0.0.1:18080/api/v1/storage-connections/google/callback`. This is
   `<HYPERDRIVE_PUBLIC_BASE_URL>/api/v1/storage-connections/google/callback`. Keep the sign-in
   URI as well.
3. **Add the scopes:** OAuth consent screen (Google Auth Platform → Data access) → add the
   scopes `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile` and
   `.../auth/drive.readonly`. Google lists `drive.readonly` as restricted.
4. **Add test users:** while the app is in **Testing**, add every Google account that will
   connect a Drive as a test user. That includes a different account from your sign-in account.
5. **Optional separate client:** you can use a separate OAuth client for storage by setting
   `GOOGLE_DRIVE_CLIENT_ID` and `GOOGLE_DRIVE_CLIENT_SECRET`. Otherwise the sign-in client is
   reused.

**Not verified:** whether Google accepts a `127.0.0.1` redirect for a Web client, as for sign-in.
If it doesn't, use `http://localhost:18080` consistently in the backend, desktop and Console.

## Backend configuration

In addition to the sign-in variables (see [README](../README.md#run-with-google-sign-in)):

| Variable | Required | Meaning |
| --- | --- | --- |
| `HYPERDRIVE_CREDENTIAL_KEY` | **Yes** (auth mode) | Base64 of **32 random bytes** (AES-256). Encrypts Google tokens at rest. There is no default: startup fails without it. |
| `HYPERDRIVE_CREDENTIAL_KEY_VERSION` | No (default `1`) | Positive integer stored with every encrypted value. |
| `HYPERDRIVE_CREDENTIAL_PREVIOUS_KEYS` | No | `version:base64[,version:base64]`: old keys, used for decryption only, during rotation. |
| `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` | No | A separate OAuth client for storage. Defaults to `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`. |

### Generating the encryption key

Run this in Windows PowerShell 5.1 or 7:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

- **Where it goes:** set the result as `$env:HYPERDRIVE_CREDENTIAL_KEY` in the backend window.
  It is never read from the database or the repo.
- **Keep it safe:** store it like a password. Anyone with the key **and** a copy of the
  database can decrypt the stored Google tokens.
- **Use separate keys:** don't reuse the development key anywhere else.

### What the key protects, and rotation

- **Encryption:** each token is encrypted with AES-256-GCM using a fresh 96-bit random nonce,
  through the JDK's standard `javax.crypto` API.
- **Binding:** the account ID and field name are authenticated as associated data. A value
  copied to another row or column fails to decrypt.
- **Rotation:** set the new key with a higher `HYPERDRIVE_CREDENTIAL_KEY_VERSION`, and move the
  old key to `HYPERDRIVE_CREDENTIAL_PREVIOUS_KEYS` (for example `1:<old base64>`).
  - Rows are re-encrypted with the new key on their next token refresh or reconnect. There is no
    bulk re-encryption command yet.
  - Keep the old key until this query shows no old versions:
    `SELECT encryption_key_version, count(*) FROM provider_credentials GROUP BY 1`.
- **Lost or wrong key:** stored credentials can't be decrypted. Browsing returns
  `credentials_unreadable`, and the log names the account ID but no secret. Reconnecting the
  account stores fresh credentials.

## Connection flow

```
Desktop main ──POST /api/v1/storage-connections/google/transactions (Bearer)──▶ Backend
             ◀─ { transactionId, authorizationUrl = <base>/…/google/authorize/hds_<ticket>, expiresAt }
Desktop main ── shell.openExternal(authorizationUrl)  (validated: backend origin, exact path)
Browser ──GET …/google/authorize/hds_<ticket>──▶ Backend
        ◀─ 302 to accounts.google.com (state, nonce, PKCE S256, access_type=offline,
           prompt=select_account consent)  + Set-Cookie HD_STORAGE_BINDING (HttpOnly, Lax)
Browser ── user picks an account and consents at Google ──▶ GET …/google/callback?code&state
Backend: claim transaction by state (once) → check binding cookie → exchange code (PKCE) →
         require drive.readonly in the granted scopes → validate ID token + nonce →
         resolve My Drive root id → upsert account + encrypted credentials → COMPLETED
Desktop main ──GET /api/v1/storage-connections/transactions/{id} (Bearer), every 2 s──▶ status
```

**Transaction binding.** Each attempt is bound to the HyperDrive user **and** the device
session that created it, to the provider, and to a 10-minute expiry.

- **Status and cancel:** both require the same user *and* session. Any other caller gets the
  same `404 transaction_not_found` as for an ID that doesn't exist.
- **Ticket:** the one-time ticket (`hds_…`, 256 bits) is stored as a SHA-256 hash, lasts 2
  minutes, and works once.
- **State and binding cookie:** the OAuth state and the browser-binding cookie are stored only
  as hashes.
- **PKCE verifier:** stored encrypted until the callback, then cleared.
- **No bearer tokens in URLs:** no HyperDrive bearer token ever appears in a browser URL.

**Callback rules.**

- **One callback per transaction:** the first matching callback atomically moves the attempt
  from `AUTHORIZING` to `COMPLETING`. Repeated, forged, expired and cancelled callbacks get a
  fixed "link can't be used" page.
- **Browser binding:** a callback finished in a different browser (missing or wrong binding
  cookie) fails with `browser_mismatch`.
- **Fixed pages:** browser pages are fixed text, HTML-escaped, and sent with `Cache-Control:
  no-store`, `Referrer-Policy: no-referrer` and a strict CSP.

**Error codes on a failed attempt** (`errorCode` in the status):

| Code | Meaning |
| --- | --- |
| `access_denied` | The user declined. |
| `drive_scope_not_granted` | The Drive permission was unticked on Google's consent screen. |
| `browser_mismatch` | The attempt was finished in another browser. |
| `token_exchange_failed` | Google's code exchange failed. |
| `identity_invalid` | The ID token failed validation or the nonce didn't match. |
| `drive_unavailable` | The root folder couldn't be read. |
| `internal_error` | Something failed on the backend. |
| `cancelled` / `superseded` | Cancelled by the user, or replaced by a newer attempt. |

**Polling and cancellation.**

- **Polling:** the desktop polls every 2 s, stops after 3 consecutive errors, and never polls
  past the expiry (hard cap 11 minutes). The backend rate-limits status calls (60 per minute
  per address).
- **Cancel:** **Cancel** asks the backend to cancel, so a later Google callback is rejected.
- **Unconfirmed cancel:** if the backend can't confirm, the UI says it *only stopped waiting*
  and that the account may still connect.
- **Own attempts only:** the main process only asks about attempts it started itself.

## Duplicate connections and policy

- **Reconnect updates the existing row:** connecting the same Google account again for the same
  HyperDrive user updates the existing connection (status `reconnected: true`). The unique key
  is `(user_id, provider, provider_account_id)`.
- **The old refresh token is kept if needed:** if Google doesn't return a new refresh token on
  repeat consent, the stored one is kept.
- **The same Google account under two HyperDrive users:** this is **allowed**. Each HyperDrive
  user gets their own connection and credentials; there is no global uniqueness. Nothing is
  linked by email.

## Token refresh

- **Where:** access tokens are refreshed **on the backend**, 60 s before expiry, using Spring
  Security's refresh-token client.
- **Serialized per account:** a PostgreSQL row lock (`SELECT … FOR UPDATE`) ensures concurrent
  requests cause one refresh, even across backend instances sharing the database.
- **One retry:** if Drive rejects a token that looked valid (401), the backend refreshes once
  and retries once.
- **Revoked grant:** `invalid_grant` (revoked or expired, for example the 7-day Testing limit)
  marks the account **`NEEDS_RECONNECT`**. Stored credentials are **not** erased, and no
  further calls are made to Google until the user reconnects.
- **Temporary failures:** token-endpoint outages leave the account connected and return
  `502 provider_unavailable`.

## Supported browsing scope

**Included:**
- **My Drive:** the user's My Drive root, and any folder below it reached by opening folders,
  one level per request.
- **Root ID:** the real root folder ID is resolved once at connect time with `files.get('root')`.
  Google documents the `root` alias "anywhere a file ID is provided", but not inside a search
  query, so HyperDrive doesn't put it in one.
- **Listing query:** each listing is `'<folder id>' in parents and trashed = false` with
  `corpora=user`, `includeItemsFromAllDrives=false`, ordered folders-first then by name. Only
  the needed `fields` are requested.

**Excluded (deferred):**
- Shared drives and "Shared with me".
- Computers (Backup and Sync).
- Trash and search.
- Any recursive or background enumeration. Listings are never stored in PostgreSQL.

**Paging:** `pageSize` defaults to 100 and is clamped to 1–200 on the server. Since Batch 4,
Drive's `nextPageToken` is wrapped into an encrypted, account- and folder-bound HyperDrive
`continuation` (see [dropbox.md](dropbox.md#browsing)), and the UI shows **Load more**.

### Metadata and its limits

| `kind` | Meaning | `sizeBytes` | `canDownload` |
| --- | --- | --- | --- |
| `FOLDER` | Drive folder | `null` | `false` |
| `FILE` | Ordinary (binary) file | Drive's size, or `null` if not reported | Drive's `capabilities.canDownload` |
| `PROVIDER_DOCUMENT` | Native Google Docs/Sheets/Slides/Forms/etc. | `null`. Drive may report a storage size, but it's not a downloadable file size. | `false`: these need export, which isn't implemented |
| `SHORTCUT` | Drive shortcut | `null` | `false` |

- **Shortcuts:** they show their target ID and target MIME type, but following them isn't
  implemented.
- **Unsupported actions:** opening a file, a native document or a shortcut shows an
  explanation in the app. Nothing is downloaded.
- **Missing modified time:** shown as "—".

## API (all under `/api/v1`, bearer token, completed onboarding)

| Method and path | Purpose |
| --- | --- |
| `POST /storage-connections/google/transactions` | Start a connection: returns `transactionId`, `authorizationUrl` and `expiresAt` |
| `GET /storage-connections/transactions/{id}` | Status of your own attempt: `PENDING`, `COMPLETED`, `FAILED`, `CANCELLED` or `EXPIRED` |
| `POST /storage-connections/transactions/{id}/cancel` | Server-side cancellation |
| `GET /storage-connections/google/authorize/{ticket}` | Browser only, no bearer token: consumes the ticket and redirects to Google |
| `GET /storage-connections/google/callback` | Browser only: Google's redirect back |
| `GET /accounts` · `GET /accounts/{id}` | Connected accounts (no credentials, no provider subject) |
| `DELETE /accounts/{id}` | Disconnect (see below) |
| `GET /accounts/{id}/files?directory=&continuation=&pageSize=` | Folder listing `{ items, continuation }`. `directory` is a folder's `directoryLocator` (for Drive, its id); `continuation` is the opaque value from the previous page (Batch 4 renamed these from `parentId`/`pageToken`/`nextPageToken`) |
| `GET /accounts/{id}/files/{fileId}` | Metadata of one item |

**Ownership:** it's checked in SQL (`WHERE id = ? AND user_id = <session user>`) **before**
any Google call. Another user's account ID gets the same `404 account_not_found` as an unknown
one.

**ID validation:** provider IDs must match Drive's ID alphabet (`[A-Za-z0-9_-]`) before they're
used. They're also escaped as Drive query values, and never joined into file-system paths.

**Errors:** sanitized RFC 9457 problems with a stable `code`:

| Code | Meaning |
| --- | --- |
| `account_needs_reconnect` (409) | The grant was revoked, expired, or lacks the scope |
| `provider_permission_denied` (403) | Drive refused this item; not treated as an expired token |
| `provider_rate_limited` (429) | Rate limited; sends `Retry-After` when Google gave one |
| `provider_unavailable` (502) | Drive or Google couldn't be reached |
| `file_not_found` (404) | The item doesn't exist or isn't accessible |
| `credentials_unreadable` (500) | Stored credentials can't be decrypted |
| `invalid_request` (400) | A malformed ID or page token |

**Retries:** rate limits (429, `rateLimitExceeded`/`userRateLimitExceeded`), 5xx and network
errors are retried **at most 2 times**, with exponential backoff and jitter.
- **Retry-After:** honored when it is at most 4 s. A longer `Retry-After` is passed to the
  client instead of blocking.
- **Not retried:** daily quota errors, permission errors and scope errors.
- **Timeouts:** provider requests time out after 5 s (connect) and 15 s (read).

## Disconnect vs. revoking access at Google

**Disconnect** (`DELETE /accounts/{id}`, with a confirmation in the app):
- **Deletes:** the connected-account row and, by cascade, its encrypted credentials.
- **Does not delete:** Drive files, or the HyperDrive user. It doesn't sign you out of
  HyperDrive either.
- **In-flight requests:** the desktop aborts that account's browsing requests that are still
  running.
- **Does NOT revoke the grant at Google.** HyperDrive deliberately doesn't call Google's
  revocation endpoint automatically, for three reasons:
  - Revocation applies to the user's whole grant for the OAuth client, not just one HyperDrive
    connection. Google's documentation (as read for this batch) couldn't settle whether that
    includes every client in the project. The sign-in client may be the same client.
  - The same Google account may be connected by another HyperDrive user through the same
    client, so revoking could break their connection.
  - Revocation is irreversible for the user's other sessions.

  To remove HyperDrive's access at Google, use **Google Account → Security → Your connections
  to third-party apps & services**, and remove the app there. After that, any remaining
  HyperDrive connection to that account shows **Needs reconnect**.

## Manual QA (requires real Google credentials)

Not executed yet. Run it once a real OAuth client exists:

1. **Setup:** complete [Google Cloud setup](#google-cloud-setup) and the sign-in setup. Start
   the backend with `HYPERDRIVE_CREDENTIAL_KEY` and the desktop. Sign in and finish onboarding.
2. **Connect:** Cloud Accounts → **Connect Google Drive**. The browser opens Google's account
   chooser.
   - Pick a **different** Google account from your sign-in account.
   - The consent screen should list read-only Drive access. In Testing mode an "unverified app"
     warning appears first.
3. **Approve:** the browser shows "Google Drive connected". The app lists the account with
   that account's email and the status Connected.
4. **Sign-in unchanged:** the HyperDrive username and the sign-in identity in Settings are
   unchanged. In the database, `auth_identities` still has one row for you.
5. **Browse:** Files lists My Drive.
   - Open a folder and use the breadcrumbs to go back.
   - In a folder with more than 100 items, use **Load more**.
   - Check that Google Docs show "Google Docs" with no size, shortcuts show as shortcuts, and
     trashed files are absent.
6. **Refresh:** click **Refresh** after adding a file in the Drive web UI. The new file appears.
7. **Decline:** connect again, untick the Drive permission on Google's screen, and expect
   "Google Drive access wasn't granted". Then press **Cancel** mid-flow and expect "Connection
   cancelled".
8. **Reconnect:** connect the same account again. There is still one row and the status says
   reconnected.
9. **Revoke at Google:** remove HyperDrive at Google (third-party connections), wait about an
   hour or until the access token expires, then browse. Expect **Needs reconnect**, then
   **Reconnect** to restore it.
10. **Disconnect:** HyperDrive stays signed in, the Drive files are untouched, and the app is
    still listed at Google until you remove it there.
11. **7-day expiry (Testing mode):** after 7 days the account shows Needs reconnect.
12. **Logs:** search the backend log for tokens, emails or file names. There should be none.

## Content reads (Batch 6)

How the agent reads ranges is described in
[local-agent.md](local-agent.md#bounded-read-contract-batch-6); the delegation trade-offs are in
[ADR 0002](adr/0002-provider-access-token-delegation.md).

- **Scope:** `drive.readonly` already covers content, so no new consent is needed.
- **Endpoint:** `GET https://www.googleapis.com/drive/v3/files/{id}?alt=media&supportsAllDrives=true`
  with `Range: bytes=a-b`.
- **Supported files:** ordinary binary files with `capabilities.canDownload` and a size.
  - Folders, shortcuts and native Google Docs/Sheets/Slides are refused before any token is
    delegated. Export isn't implemented.
  - `fileNotDownloadable` and download restrictions are reported as unsupported.
- **Revision:** the listing now carries `headRevisionId` and `md5Checksum` (binary files only).
  Ranged `alt=media` downloads serve the **current** content. `revisions.get?alt=media` only works
  for "Keep Forever" revisions, so Google reads are **not revision-pinned**; only the total size
  is checked against the listing.
- **Redirects:** Google doesn't document whether `alt=media` redirects. The agent refuses all
  redirects so the bearer token is never sent to another origin; if live testing shows one, the
  read fails with "provider didn't return a valid response".
- **Errors:** 401 → one re-delegation; rate-limit 403/429 → retried, then reported; other 403s →
  forbidden (not treated as expiry); 404 → not found.

### Chunk cache (Batch 7): Google reads are never cached

- **Why:** ranged `alt=media` downloads serve whatever is current. `headRevisionId`,
  `modifiedTime`, size or `md5Checksum` checks before or after a read can't make a range, or two
  chunks, version-atomic.
- **Result:** every Drive read bypasses the cache (`provider_not_version_bound`). It fetches
  exactly the requested range, as in Batch 6, and the diagnostic says so.
- **Not built:** a revisions-based design ("Keep Forever" revisions) was deliberately not built; it
  would change users' files. See
  [ADR 0003](adr/0003-cache-consistency-and-key-lifecycle.md).

### Manual QA: content reads (requires real Google credentials)

Not executed yet.

1. **Read:** in Files, open a connected Drive and click an ordinary file larger than 4 MiB. In
   **Verify file read**, run offset 0 and length 4,194,304.
   - Expect success, 4,194,304 bytes and "Not pinned".
   - Compare the SHA-256 with the first 4 MiB of a downloaded copy (`Get-FileHash`).
2. **Middle and final ranges:** an offset in the middle gives the exact length; an offset 100
   bytes before the end gives 100 bytes and "reached end of file".
3. **Native docs and shortcuts:** no Verify panel is offered (the click shows the "not
   implemented" note).
4. **Restricted download:** for a file whose owner disabled downloading for viewers, expect
   "can't be read as an ordinary file".
5. **Redirect check:** record whether any read fails with "provider didn't return a valid
   response". That would indicate an undocumented redirect.
6. **Sign out:** sign out and check that the agent log shows the delegated credentials cleared.
7. **Logs:** search the backend, desktop and agent logs for `ya29.`, `Bearer` and file names.
   There should be none.
