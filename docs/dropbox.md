# Dropbox connection (Batch 4)

HyperDrive can connect one or more Dropbox accounts and browse their folders and file metadata,
**read-only**, next to Google Drive accounts. File contents are never downloaded, previewed,
streamed or cached in this batch, and nothing is written to Dropbox.

> **Verification status.** Automated tests cover the flow against an in-process fake Dropbox that
> follows the documented request and response fields. A **live** Dropbox connection and real
> Dropbox browsing have **not** been tested yet, because no Dropbox app credentials were
> available. See [Manual verification](#manual-verification-requires-a-dropbox-app).

Sources: the Dropbox API docs at `docs.dropboxapi.com`, read 2026-11-22 (OAuth guide, API
reference for the endpoints below, team-files guide, developer guide). Where those docs were
ambiguous, HyperDrive avoids relying on the behavior, and this page says so.

## Sign-in vs. storage, and how Dropbox differs from Google Drive

| | HyperDrive sign-in | Google Drive | Dropbox |
| --- | --- | --- | --- |
| Purpose | Who you are in HyperDrive | Read a Drive | Read a Dropbox |
| Started from | "Continue with Google" | Cloud Accounts | Cloud Accounts |
| Callback | `/login/oauth2/code/google` | `/api/v1/storage-connections/google/callback` | `/api/v1/storage-connections/dropbox/callback` |
| Identity used | Google OIDC issuer + subject | Google OIDC subject (ID token) | Dropbox `account_id` (`dbid:…`) from `users/get_current_account` |
| PKCE | Yes | Yes | **No** (see below) |
| Tokens stored | None | Encrypted (AES-256-GCM) | Encrypted (AES-256-GCM) |

A Dropbox connection never signs anyone in, never creates or changes a HyperDrive user, and doesn't
need the Dropbox email to match your HyperDrive sign-in email. The connection is keyed on Dropbox's
stable `account_id`, never on email. The email is stored only if Dropbox marks it verified.

## Dropbox app setup

1. **Create the app:** at <https://www.dropbox.com/developers/apps> → **Create app**:
   - API: **Scoped access**.
   - Access type: **Full Dropbox**. HyperDrive browses a user's existing files. **App folder**
     access only gives an app its own `/Apps/<app name>` folder (Dropbox: "Your app gets read and
     write access to this folder only"), so it **can't** browse the rest of the account. Don't
     choose it expecting whole-account browsing.
2. **Permissions tab:** enable exactly these two scopes, then **Submit**:
   - `account_info.read`: required by `users/get_current_account` (account identity and root).
   - `files.metadata.read`: required by `files/list_folder`, `files/list_folder/continue` and
     `files/get_metadata`.

   HyperDrive's authorization request asks for this subset explicitly (`scope=...`). Dropbox
   only grants scopes that are enabled on the Permissions tab.
3. **Settings tab → OAuth 2 → Redirect URIs:** add exactly
   `http://127.0.0.1:18080/api/v1/storage-connections/dropbox/callback`. This is
   `<HYPERDRIVE_PUBLIC_BASE_URL>/api/v1/storage-connections/dropbox/callback`; Dropbox requires
   an exact match.
4. **Copy the credentials:** copy the **App key** and **App secret** into the backend
   environment (below). The secret stays on the backend and never goes into the desktop app.

### Permissions requested now vs. deferred

| Scope | Now | Why |
| --- | --- | --- |
| `account_info.read` | **Requested** | Identify the connected account (`account_id`, name, verified email) and its root namespace |
| `files.metadata.read` | **Requested** | List folders and read file/folder metadata |
| `files.content.read` | **Requested since Batch 6** | Bounded byte-range reads by the local agent. Connections made before Batch 6 don't have it: they keep browsing, show "can't read file contents" and need **Reconnect** before reads work. Enable it in the app's Permissions tab. |
| Any `*.write`, `sharing.*`, `team_*` | **Never requested** | HyperDrive is read-only |

### Offline access

The authorization request includes `token_access_type=offline`, so Dropbox returns a
**refresh token** with the first code exchange. Access tokens are short-lived and are refreshed
**on the backend**. Dropbox documents that a refresh "won't return a new refresh token", so the
stored refresh token is always kept.

### Development vs. production (Dropbox's rules, 2026-11-22)

- **Development status:** a new app "can only be connected to your own account". Under
  **Settings → Development users → Enable additional users** you can allow other accounts, up
  to **500** users in total.
- **50-user deadline:** once 50 users have linked the app, you have two weeks to apply for
  production. After that, new links are frozen until the app is approved.
- **Production:** apply with **Apply for production** on the Settings tab. Add an app icon on
  the Branding tab first. Production apps can link any number of users, and the app name can't
  be changed after approval.
- **HyperDrive's status:** HyperDrive has **not** applied for or received production approval.
  Treat it as a self-hosted development app.

## Backend configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `DROPBOX_APP_KEY` | Optional | Dropbox app key. If **both** this and the secret are empty, Dropbox connections are **disabled**: the app shows "Not configured on this HyperDrive server", and starting a connection returns `503 provider_not_configured`. |
| `DROPBOX_APP_SECRET` | With the key | Dropbox app secret (backend only). Setting only one of the two **fails startup**. |
| `HYPERDRIVE_CREDENTIAL_KEY` | Yes (already, Batch 3) | Encrypts Dropbox tokens exactly like Google's. |

Set them in the backend's PowerShell window:

```powershell
$env:DROPBOX_APP_KEY = "<app key>"
$env:DROPBOX_APP_SECRET = "<app secret>"
```

## Connection flow

The flow is the same as Google Drive's (see [google-drive.md](google-drive.md#connection-flow)),
with Dropbox-specific steps:

1. **Start:** the desktop main process calls `POST /api/v1/storage-connections/dropbox/transactions`
   (bearer). The attempt is bound to the user, the device session, the provider and a 10-minute
   expiry. It returns a backend URL carrying a one-time ticket.
2. **Open the browser:** the main process opens that URL only if it is this backend's
   `/api/v1/storage-connections/dropbox/authorize/hds_…`. A Google URL for a Dropbox request is
   refused, and vice versa.
3. **Redirect to Dropbox:** the backend consumes the ticket once, stores hashed OAuth state and a
   browser-binding cookie scoped to `/api/v1/storage-connections/dropbox`, and redirects to
   `https://www.dropbox.com/oauth2/authorize` with `response_type=code`, `client_id`,
   `redirect_uri`, `scope=account_info.read files.metadata.read`, `token_access_type=offline`
   and `state`.
4. **Callback:** Dropbox redirects to the Dropbox callback. The backend:
   - Claims the attempt by state, once, **for the Dropbox provider only**. A Google state can't
     complete a Dropbox attempt.
   - Checks the binding cookie.
   - Exchanges the code with Spring Security's token client, using HTTP Basic client
     authentication (documented by Dropbox).
5. **Validation:**
   - The granted `scope` must include both scopes; otherwise the attempt fails with
     `scope_not_granted`.
   - `users/get_current_account` must return the **same** `account_id` as the token response;
     otherwise it fails with `identity_invalid`.
   - `root_info` must be `user` or `team` with a numeric `root_namespace_id`; otherwise it fails
     with `unsupported_account`.
6. **Store:** the account and the encrypted credentials are upserted and the attempt is
   completed, all in one database transaction. Reconnecting the same `account_id` updates the
   existing row (`reconnected: true`).
7. **Desktop:** the desktop polls its own attempt (every 2 s, bounded) and refreshes the
   account list.

**Why no PKCE for Dropbox:** Dropbox's docs describe PKCE for apps that *can't* keep a secret,
and don't say whether a confidential app may send PKCE together with the client secret. Spring
Security 7 adds PKCE by default, so HyperDrive explicitly strips it from Dropbox requests (and
tests that it's absent). The exchange is protected by the client secret, the single-use hashed
state, the binding cookie and the expiring transaction. If Dropbox later documents PKCE for
confidential apps, adding it means setting `pkce=true` for the Dropbox `StorageOAuth`.

**Connecting a different Dropbox account:** Dropbox has no account chooser parameter. To connect a
second account, sign out of Dropbox in the browser first, or use a private window. HyperDrive
doesn't force re-authentication.

## Root and namespace behavior

- **Path root:** every files call sends `Dropbox-API-Path-Root: {".tag": "root", "root":
  "<root_namespace_id>"}`. Dropbox's team-files guide recommends this header because it "works
  for both configurations".
- **Personal and member-folder accounts:** the root namespace is the user's own Dropbox, so path
  `""` is their root.
- **Team-space accounts** (`root_info.tag = team`, root namespace ≠ home namespace): path `""` is
  the **team space** root. That's the folders the member can access, with their member folder
  inside it. Without the header Dropbox would show only the member folder ("you won't see team
  folders"). The UI labels these accounts "(team space)".
- **Lost root:** if Dropbox rejects the stored root namespace (HTTP 422, for example after
  leaving a team), the account becomes **Needs reconnect**. Reconnecting reads `root_info`
  again.
- **Not supported:** Dropbox Business admin features (`Dropbox-API-Select-User`/`Select-Admin`,
  team tokens), app-folder apps, and accounts whose `root_info` isn't `user`/`team`. The last
  fail with `unsupported_account` rather than showing a wrong root.
- **Untested:** the team-space behavior is implemented per the docs and covered by tests against
  the fake, but has **not** been verified against a real Dropbox Business account.

## Browsing

- **Identity vs. navigation:** a Dropbox item's stable identity is its `id` (`id:…`, used for
  `files/get_metadata`). A folder is listed by its `path_lower`, returned to the desktop as
  `directoryLocator`. The two are never derived from each other.
- **Listing:** `files/list_folder` with `path` ("" for root), `recursive=false`,
  `include_deleted=false`, `include_mounted_folders=true`, `include_non_downloadable_files=true`,
  and `limit` (Dropbox documents it as approximate; HyperDrive sends the bounded page size,
  default 100 and at most 200).
- **Pagination:** when `has_more` is true, Dropbox's `cursor` is wrapped into an encrypted
  HyperDrive **continuation** (`hdk_…`). It's bound to the connected account, the provider and
  the folder, and the next page uses `files/list_folder/continue`.
  - The raw cursor never reaches the desktop.
  - A continuation can't be replayed against another account (even another account of the same
    user), another provider, or another folder.
  - A Dropbox `reset` (cursor invalidated) becomes `409 continuation_expired`, and the UI offers
    **Refresh**.
- **No recursion:** HyperDrive never lists recursively or crawls an account.

### Metadata mapping (nothing invented)

| Dropbox entry | `kind` | `mimeType` | `sizeBytes` | `modifiedAt` | Notes |
| --- | --- | --- | --- | --- | --- |
| `folder` | `FOLDER` | `null` | `null` | `null` | `directoryLocator` = `path_lower` |
| `file` | `FILE` | `null` (Dropbox has no MIME types) | `size` | `server_modified` | `providerRevision` = `rev`; `contentHash` = `content_hash`, labeled `dropbox-content-hash` (not comparable with Google hashes) |
| `file` with `is_downloadable: false` | `PROVIDER_DOCUMENT` | `null` | `null` | `server_modified` | Cloud documents that need export; export isn't implemented |
| `file` with `symlink_info` | `SHORTCUT` | `null` | `null` | `server_modified` | Shown as "Link", **never followed** |
| `deleted` | (skipped) | | | | Not listed |
| unknown tag or no usable `id` | (skipped) | | | | Logged as a count only |

**Type labels:** the UI shows a type label from the file name's extension (for example "PDF
file") because Dropbox reports no MIME type. The API's `mimeType` stays `null`.

### Errors

| Dropbox response | HyperDrive behavior |
| --- | --- |
| 401 `expired_access_token` / `invalid_access_token` | One server-side refresh and retry. If still rejected → `account_needs_reconnect` |
| 401 `missing_scope` | `account_needs_reconnect` (no refresh attempt) |
| 403 | `403 provider_permission_denied` (not treated as an expired token) |
| 409 `path/not_found` | `404 file_not_found` |
| 409 `reset` | `409 continuation_expired` |
| 422 (path root) | `account_needs_reconnect` (`root_changed`) |
| 429 | Retried up to 2 times, honoring `Retry-After` (or `error.retry_after`) up to 4 s. Longer waits → `429 provider_rate_limited` with `Retry-After` |
| 5xx / network error | Retried up to 2 times with backoff → `502 provider_unavailable` |
| Refresh `invalid_grant` | `account_needs_reconnect` (`refresh_revoked`); stored credentials are kept, not erased |

Error messages never echo Dropbox response text, file names or paths.

## Credential handling

Dropbox tokens use Batch 3's credential store unchanged:
- **Encryption:** AES-256-GCM with a key outside the database. Ciphertext is bound to the
  account row and field, and each row records its key version.
- **Refresh:** server-side and serialized per account (row lock). The refresh token is kept when
  Dropbox omits a new one, which it always does.
- **Isolation:** connecting Dropbox never touches Google credentials. Each connection has its own
  row and its own ciphertext bound to that row; this is covered by regression tests.

## Disconnect vs. revoking access at Dropbox

**Disconnect** (`DELETE /api/v1/accounts/{id}`, with a confirmation in the app):
- **Deletes:** the connection row and, by cascade, its encrypted credentials.
- **Does not delete:** remote files, the HyperDrive user, or any other connected account. You
  stay signed in to HyperDrive.
- **In-flight requests:** the desktop aborts that account's browsing requests that are still
  running.

**Dropbox's revocation endpoint is not called.** `auth/token/revoke` "Disables the access token
used to authenticate the call. If there is a corresponding refresh token for the access token,
this disables that refresh token, as well as any other access tokens for that refresh token."
Its scope would be limited to this connection's tokens, unlike Google's. HyperDrive still
doesn't call it automatically, for three reasons:
- After a disconnect the local credentials are already gone, which is what unlinking requires.
- A revoke call that fails, or reaches an unreachable Dropbox, must not block or leave behind
  credentials.
- Calling it would be a new outbound request that this batch hasn't verified live.

To also remove HyperDrive's access at Dropbox, use **Dropbox → Settings → Connected apps**.
Whether to call `auth/token/revoke` on disconnect (best effort, after deleting local
credentials) is a decision for a later batch.

## API summary

All under `/api/v1`, bearer token, completed onboarding:

| Method and path | Purpose |
| --- | --- |
| `GET /storage-connections/providers` | `{ items: [{ provider, available }] }`: whether Google Drive and Dropbox are configured on this server |
| `POST /storage-connections/dropbox/transactions` | Start a Dropbox connection (`503 provider_not_configured` if disabled) |
| `GET /storage-connections/transactions/{id}` · `POST …/{id}/cancel` | Status and cancellation, shared with Google |
| `GET /storage-connections/dropbox/authorize/{ticket}` · `GET /storage-connections/dropbox/callback` | Browser only |
| `GET /accounts` · `GET /accounts/{id}` · `DELETE /accounts/{id}` | Provider-neutral account APIs (`provider`, `rootType`) |
| `GET /accounts/{id}/files?directory=&continuation=&pageSize=` | `{ items, continuation }` |
| `GET /accounts/{id}/files/{fileId}` | Metadata by stable ID; URL-encode the `id:…` segment |

## Manual verification (requires a Dropbox app)

Not executed yet. Run it once a real app exists:

1. **Setup:** create the app as described above, set `DROPBOX_APP_KEY` and `DROPBOX_APP_SECRET`,
   and start the backend and desktop. Cloud Accounts shows **Connect Dropbox** enabled.
2. **Connect:** click **Connect Dropbox**, sign in with a Dropbox account whose email differs
   from your HyperDrive sign-in, and allow access. The browser shows "Dropbox connected", and the
   app lists the account with Dropbox's verified email.
3. **Sign-in unchanged:** the HyperDrive username and the `auth_identities` row are unchanged.
4. **Browse:** Files → **All storage** lists both your Google Drive and Dropbox accounts. Open the
   Dropbox account.
   - Root folders and files appear, and nested folders open.
   - **Load more** appears for a folder with more than 100 items.
   - Paper/cloud docs show as "Cloud document"; deleted files are absent.
5. **Isolation:** switch between Google Drive and Dropbox. Each shows its own root, and
   breadcrumbs reset.
6. **Refresh:** add a file in Dropbox's web UI and click **Refresh**; it appears.
7. **Reconnect:** connect the same Dropbox account again. There's still one row, and the app
   says "reconnected".
8. **Second account:** in a private window, connect a second Dropbox account. Both are listed.
9. **Revoke at Dropbox:** remove the app under Dropbox → Settings → Connected apps. After the
   access token expires, browsing shows **Needs reconnect**.
10. **Disconnect:** disconnect one Dropbox account. Google Drive and the other accounts are
    unaffected, you stay signed in, and the Dropbox files are untouched.
11. **Team account (if available):** a Dropbox Business team-space member sees the team space
    root, labeled "(team space)".
12. **Logs:** search the backend log for tokens, emails, file names or the app secret. There
    should be none.

## Content reads (Batch 6)

How the agent reads ranges is described in
[local-agent.md](local-agent.md#bounded-read-contract-batch-6); the delegation trade-offs are in
[ADR 0002](adr/0002-provider-access-token-delegation.md).

- **Endpoint:** `POST https://content.dropboxapi.com/2/files/download`, with `Dropbox-API-Arg
  {"path": "id:…"}`, or `{"path": "rev:<rev>"}` when the listed revision is known (pinned).
- **Root:** `Dropbox-API-Path-Root {".tag":"root","root":"<root namespace>"}`, the same root as
  browsing, so team-space files resolve the same way.
- **Range:** `Range: bytes=a-b`, with `206` and `Content-Range` expected.
  - A whole-file `200` is accepted only when the whole (≤ 4 MiB) file was asked for.
  - The `Dropbox-API-Result` `rev` and `size`, when present, must match the listing.
- **Errors:** 409 `path/not_found` → not found; `restricted_content`, `unsupported_file` and
  `not_file` → unsupported; `missing_scope` → reconnect for content permission; 401 → one
  re-delegation; 429 → `Retry-After` honored up to 2 s, then reported.
- **Not verified live:** whether Dropbox sends `Dropbox-API-Result` on 206 responses, and its
  exact error bodies, are inferred from the API spec and tested only against a local mock.

### Chunk cache (Batch 7)

- **Admission:** Dropbox is the only provider whose reads are cached, and only when **both** hold:
  the request enforced the revision (`path = "rev:<rev>"`, from the listing's `rev`), and
  `Dropbox-API-Result` reports the **same** `rev` and size.
- **Unconfirmed reads:** if the header is missing, the bytes are served but not stored
  (`revision_unconfirmed`).
- **Spec basis:** `files.stone` calls `rev` "a unique identifier for the current revision of a
  file". Treating a revision's bytes as immutable is an inference from that, so the response check
  stays mandatory.
- **Not yet observed live:** whether Dropbox includes `Dropbox-API-Result` on 206 responses. If it
  doesn't, Dropbox reads will show "not stored: the provider didn't confirm the file version" and
  nothing will be cached.
- See [cache.md](cache.md) for chunking, encryption and cleanup.

### Manual verification: content reads (requires a Dropbox app)

Not executed yet.

1. **Permission:** enable `files.content.read` in the app's Permissions tab. A Dropbox account
   connected before Batch 6 shows "can't read file contents" in Cloud Accounts. **Reconnect** it;
   the consent screen now lists file-content read access, and the note disappears.
2. **Read:** in Files, open a Dropbox folder and click an ordinary file (> 4 MiB if possible).
   The **Verify file read** panel opens. Run it with offset 0 and length 4,194,304.
   - Expect success, 4,194,304 bytes, "Pinned to the listed revision" and a SHA-256.
   - Compare it with `Get-FileHash -Algorithm SHA256` over the first 4 MiB of a downloaded copy.
3. **Final range:** an offset 100 bytes before the end returns 100 bytes and "reached end of file".
4. **Changed file:** edit the file in Dropbox's web UI without refreshing the listing, then
   verify again. Expect "changed since it was listed" (the old `rev` is either still served
   pinned, or reported not found; record which).
5. **Paper/cloud docs:** no Verify panel is offered.
6. **Disconnect:** disconnect the account and check the agent log for "Cleared 1 delegated
   credential(s) for account …".
7. **Logs:** search the backend, desktop and agent logs for `sl.`, `Bearer` and file names.
   There should be none.
