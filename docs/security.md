# Security notes

What the code does today, and what it deliberately doesn't yet. See
[authentication.md](authentication.md) for the sign-in design.

## Secrets

| Secret | Where it lives | Never |
| --- | --- | --- |
| Google client secret | Backend process environment (`GOOGLE_CLIENT_SECRET`) | In desktop code, renderer config, the repo, or logs |
| Database password | `.env` (Compose, git-ignored) and backend environment | Committed |
| HyperDrive access token | Backend: SHA-256 hash. Desktop: main-process memory | Renderer, disk, URLs, logs |
| HyperDrive refresh token | Backend: SHA-256 hash. Desktop: `safeStorage`-encrypted file, or memory-only | Plaintext on disk, renderer, URLs |
| Handoff code | Backend: SHA-256 hash, 60 s, single use. Appears once in the loopback redirect URL | Reusable, logged |
| Google sign-in access/ID tokens | Used transiently by Spring Security during the sign-in callback | Stored or sent to the desktop |
| Google **Drive** access/refresh tokens | Backend database, AES-256-GCM encrypted (`provider_credentials`) | Plaintext at rest, the desktop, the renderer, URLs, logs |
| **Dropbox** access/refresh tokens | Same table and encryption as Drive, separate row per connection | Same as above |
| Dropbox app secret | Backend environment (`DROPBOX_APP_SECRET`) | In desktop code, renderer config, the repo, or logs |
| Local agent secret | Generated per launch in the Electron main process (256 bits); sent once over the agent's stdin; kept in memory by both processes | Command line, environment, files, logs, handshake, renderer |
| Provider listing cursors | Wrapped into AES-GCM continuations bound to account + provider + folder | Returned raw, or accepted for another account |
| Credential encryption key | Backend environment (`HYPERDRIVE_CREDENTIAL_KEY`) | In the database, the repo, or a hardcoded fallback |
| Storage ticket / OAuth state / binding cookie | Backend: SHA-256 hashes. Ticket: 2 min, single use | Reusable, logged |
| Storage PKCE verifier | Backend: encrypted until the callback, then cleared | Plaintext at rest |

**Hashing:** tokens are 256-bit random values, so plain SHA-256 is sufficient (no slow password
hash needed). PKCE verification compares hashes in constant time. The desktop compares the
callback state in constant time.

## Backend

- **Separate filter chains:** browser sign-in (session used only for Spring's OAuth request,
  with the authentication never persisted), bearer-only `/api/**` (stateless, no cookies read,
  CSRF disabled for that reason only), and deny-all for everything else.
- **Validation:** Spring Security's OIDC validation is used unmodified (state, nonce, ID-token
  signature, issuer, audience, expiry). Tests run it against a fake provider that signs real
  RS256 tokens. Wrong signature, audience or nonce are rejected.
- **Request bodies:** request DTOs are validated, and unknown JSON fields are rejected globally.
- **Errors:** responses are sanitized problem documents, never echoing request values; stack
  traces and messages are off.
- **Correlation IDs:** every request gets `X-Correlation-Id`. It's also put on log lines and in
  error bodies. Client-supplied IDs are accepted only if they're well-formed.
- **Rate limiting:** transaction creation, authorize, exchange, refresh and onboarding are
  limited by TCP peer address. The limiter is in memory, single-instance, and capped at 10,000
  tracked clients (it fails closed when full).
- **CORS:** none configured. The renderer can't call the backend at all (`connect-src 'none'`);
  the Electron main process isn't subject to CORS.
- **Cookie:** the session cookie is `HttpOnly; SameSite=Lax`, plus `Secure` when
  `HYPERDRIVE_SECURE_COOKIES=true`. It lasts 10 minutes and carries no authentication.
- **Logging:** `org.springframework.security` is kept at INFO, because DEBUG would log redirect
  URLs containing handoff codes.
- **Startup:** fails when auth is enabled but the Google client ID/secret, public base URL or
  redirect URI are missing or wrong, or when extra scopes are configured.

## Cloud storage (Batch 3)

Details: [google-drive.md](google-drive.md).

- **Separate from sign-in:** a separate client registration, scopes and callback. The storage
  callback runs in the stateless API chain, uses no HTTP session, and can't create, change or
  replace a HyperDrive login.
- **Binding:** each connection attempt is bound to the user, device session, provider and
  expiry. Status and cancel answer only that user and session, with an identical 404 for
  everyone else.
- **Browser leg:** a one-time ticket (hashed at rest, 2 min). The callback is claimed once
  atomically, checked against a hashed `HttpOnly; SameSite=Lax` binding cookie, PKCE-protected,
  and requires a validated ID token and nonce plus the granted `drive.readonly` scope.
- **Ownership before provider calls:** every account and file operation is scoped in SQL to the
  session's user. File IDs are never trusted for authorization.
- **Encryption at rest:** AES-256-GCM via the JDK, with a unique nonce per value, associated
  data bound to account and field, and a key version per row. A failed refresh never overwrites
  stored credentials.
- **Error handling:** 403s are classified (rate limit, quota, scope, permission). Only 401s
  trigger a single refresh-and-retry, and retries are bounded.
- **Logs:** no tokens, file names or emails. Account IDs and error categories only.
- **Rate limits:** connection start 10/min, browser leg 20/min, status polling 60/min, account
  and file APIs 300/min (per address, in memory).
- **Disconnect is local:** it deletes HyperDrive's credentials. It does **not** revoke the
  Google grant, and the UI and docs say so. Google revocation is project-wide (all clients of the
  Cloud project, including sign-in by default), so no automatic Google revocation is offered; see
  [google-drive.md](google-drive.md#why-there-is-no-automatic-google-revocation-checked-2026-11-23).
  This is a public-release compliance item (Google's OAuth policies ask apps to revoke tokens they
  no longer need).

### Dropbox (Batch 4)

Details: [dropbox.md](dropbox.md).

- **Shared protections:** Dropbox uses the same transaction protections as Drive: ticket,
  hashed state, binding cookie, single-use callback, owner-only status, expiry.
- **Bound to its provider:** callbacks are claimed **per provider**, so a Google state can't
  complete a Dropbox attempt. The binding cookie is path-scoped per provider, so parallel
  attempts don't collide.
- **Identity:** `account_id` from `users/get_current_account` must equal the token response's
  `account_id`. Email is never used for identity.
- **No PKCE:** Spring's default PKCE is explicitly removed for Dropbox, and a test checks it's
  absent. Protection comes from the client secret, state, binding cookie and expiry.
- **Read-only scopes:** `account_info.read files.metadata.read files.content.read` (content read
  since Batch 6); no write, sharing or team scopes.
- **Root:** every files call is pinned to the stored root namespace (`Dropbox-API-Path-Root`).
  The namespace is validated as digits before it goes into the header.
- **Continuations:** provider cursors never reach the desktop. Wrapped continuations fail for any
  other account, provider or folder, before any provider call.
- **Disconnect:** local only. `auth/token/revoke` isn't called and nothing claims it was.
- **Revoke access and disconnect (separate, confirmed):** one `auth/token/revoke` call with this
  connection's token (documented to disable that token and its refresh token), then an
  unconditional local disconnect. The outcome is reported as `confirmed`, `failed` or `unknown`;
  credentials are never kept to retry. See
  [dropbox.md](dropbox.md#revoke-access-and-disconnect-separate-action-checkpoint-2026-11-23).

## Local agent (Batch 5)

Details: [local-agent.md](local-agent.md#loopback-threat-model-honest-scope) and
[ADR 0001](adr/0001-local-agent-transport-and-content-boundary.md).

- **Loopback only:** binds to `127.0.0.1` on an OS-assigned port (never `0.0.0.0`, no fixed
  fallback), confirmed on Windows 11.
- **Every request authenticated:** the per-launch bearer secret is compared in constant time.
- **Not reachable from browsers:**
  - `Host` must be exactly `127.0.0.1:<port>`, which blocks DNS rebinding.
  - Any `Origin` header is rejected, and no CORS headers are sent.
  - GET for status; POST only on the three fixed Batch 6 paths, with bounded `application/json`
    bodies of known fields. No query strings, and a bounded worker pool and request time.
- **No dangerous endpoints:** no shutdown, file, environment, path, configuration-write, proxy or
  shell endpoints. Shutdown exists only on the owned stdin channel.
- **Fail-closed bootstrap:** at most 8 KiB, exactly one protocol version, required fields only,
  and a 10 s timeout. The received input is never echoed.
- **The desktop trusts nothing from the child:** it validates the handshake schema, protocol and
  port, builds `http://127.0.0.1:<port>` itself, requires an authenticated health check before
  showing Ready, and buffers child output with a bound and secret redaction.
- **Ownership:** `spawn` with `shell:false` and an argv array. The desktop kills only the
  `ChildProcess` it spawned, never by name, and never attaches to an existing service on a port.
- **Renderer:** receives status only (state, version, uptime, capabilities, an error code),
  never the port, secret, paths or spawn parameters.
- **Not a sandbox:** malware already running as the same Windows user can still reach the agent
  (for example by reading process memory). That's inherent to desktop software.

## Provider-read delegation (Batch 6)

Details: [ADR 0002](adr/0002-provider-access-token-delegation.md) and
[local-agent.md](local-agent.md#bounded-read-contract-batch-6).

**Backend** (`POST /api/v1/accounts/{id}/read-credentials`):
- **Checks run first:** the HyperDrive session and completed onboarding, account ownership (404
  for others), connection status (409 `account_needs_reconnect`) and the stored content scope
  (409 `content_permission_missing`). All run **before** any refresh or provider call.
- **What's returned:** only the access token, its provider expiry and, for Dropbox, the root
  namespace ID. The refresh token never leaves the backend. Scopes are never silently added.
- **Delivery:** `Cache-Control: no-store` with `Pragma: no-cache`, and a per-user rate limit
  (default 30 per minute).
- **Audit and debugging:** an audit log line records user, session, account, provider and expiry,
  never the token. There is no token-debug endpoint.

**Desktop main process:**
- **Handling the token:** it never stores the token. It passes it to the agent and keeps only
  the opaque handle, bound to the agent `instanceId` (a restart invalidates it).
- **Renderer bridge:** preload and the renderer receive only diagnostic facts (sizes, time,
  SHA-256, an error code). Never tokens, handles or bytes.
- **Re-delegation:** after `credential_rejected`, `credential_expired` or `unknown_credential`,
  it re-delegates **once** per read, single-flight per account.
- **Cleanup:**
  - Disconnect clears that account's credential in the agent. Sign-out and session expiry clear
    all of them.
  - If a clear can't be confirmed, the main process restarts its agent, so no unmanaged
    authenticated agent keeps the token.
- **Backend URL:** `HYPERDRIVE_BACKEND_URL` must be `https`, unless it is loopback
  (`127.0.0.1`, `localhost` or `[::1]`) for development. TLS validation is never disabled.

**Agent:**
- **Storage:** credentials are kept in memory only (no disk), at most 32, one per account. Each is
  retained ≤ 20 min and never past provider expiry, and expired ones are refused.
- **Provider calls:** fixed provider endpoints, redirects refused, response-size and
  `Content-Range` validation, and no decompression.
- **No leakage:** tokens never appear in URLs, arguments, logs or error bodies.

**Honest limits:**
- **Not file-scoped:** a delegated token is **account-wide** at the provider, not limited to one
  file. HyperDrive can't narrow Google or Dropbox tokens.
- **No provider revocation:** clearing the agent doesn't revoke the token at the provider. It
  stays valid until the provider's own expiry.
- **No zeroization:** Java strings and Node buffers can't be reliably zeroized, and HyperDrive
  doesn't claim zeroization.

## Local chunk cache (Batch 7)

Details: [cache.md](cache.md) and [ADR 0003](adr/0003-cache-consistency-and-key-lifecycle.md).

- **Encryption:** every cached chunk is AES-256-GCM encrypted with a fresh random nonce, using JDK
  primitives only.
  - The authenticated data binds the chunk's identity (user, account, provider, file, enforced
    revision, size, chunk index, through an HMAC tag) and its length.
  - Tampered, renamed, truncated or wrong-key chunks are rejected and refetched. Plaintext is never
    written; temp files are ciphertext.
- **Key:** a random 32-byte key per HyperDrive user, created and persisted only by the Electron main
  process, wrapped by `safeStorage`. It's sent to the agent over the authenticated loopback
  channel and kept in memory there. It never reaches preload, React or logs.
  - **No OS protection → no disk cache.** There's no plaintext fallback.
  - **Verified live on Windows (2026-11-23):** creation, recovery after restart, and replacement of
    a corrupt, truncated, missing or foreign-profile key file with the real `safeStorage`; see
    [live-verification.md](live-verification.md#6-windows-key-protection-live-result-2026-11-23).
  - **Threat model:** `safeStorage` on Windows uses a key protected by DPAPI for the current
    Windows user. It protects against offline disk access, other Windows users and copying the
    files elsewhere. It does **not** protect against software running as the same Windows user.
- **Names:** file and directory names are HMAC/SHA-256 tags. No file names, emails, provider IDs,
  tokens or handles appear on disk.
- **Isolation:** cache keys include user, account and enforced revision, so entries are never
  shared across users, account connections or versions.
- **Cleanup** (what is deleted, precisely):
  - **Clear cache:** deletes the user's encrypted chunk files. The key is **kept** (it's the
    user's key for every account), so this is file deletion only, not cryptographic erasure.
  - **Disconnect one account:** deletes that account's encrypted chunk files. The key is **kept**
    for the user's other accounts. If the agent can't confirm, the purge is persisted and retried
    at the next cache session, also after an app restart. Until then those files remain
    decryptable by the retained key, though they can't be served (the account's id is gone).
  - **Logout / session expiry:** deletes the user's chunk files **and** the wrapped key file, and
    the agent forgets the key. Files that couldn't be deleted can't be decrypted with any key the
    app still holds, and are deleted at the next session. Copies of the wrapped key in backups of
    the profile aren't controlled by HyperDrive. Transient phases ("checking", backend
    unavailable) purge nothing.
  - **App restart:** nothing is deleted; the wrapped key and encrypted chunks persist, and the key
    is handed to the new agent process.
  - Generation counters and the desktop's per-account epochs stop in-flight fetches, delegations
    and reads from publishing or restoring access after any of these.
- **Not secure erasure:** deleting files doesn't overwrite disk blocks (SSDs, backups, journals).
- **Not an access check:** a cache hit doesn't ask the provider whether access is still allowed. It
  still needs a signed-in session, a connected account and a valid delegated credential (retained
  at most 20 minutes, re-issued only by the backend for a connected, owned account). Each
  diagnostic read also fetches fresh metadata through the backend first.
- **Renderer bridge:** IPC exposes only status counters, the budget setter (validated) and clear.
  It never exposes paths, keys or arbitrary deletion.

## Desktop

- **Electron hardening (Batch 1):** sandbox, context isolation, no Node in the renderer, strict
  CSP, navigation, pop-ups and permissions blocked.
- **IPC:** handlers answer only the app's own window, main frame, at the trusted renderer URL.
  Payloads are validated (`completeOnboarding` accepts exactly `{ username: string }`), and every
  other channel takes no arguments.
- **System browser:** `shell.openExternal` is reachable only from main-process sign-in and
  storage code, and only for `<trusted backend origin>/api/v1/auth/desktop/authorize/<uuid>`
  or `<trusted backend origin>/api/v1/storage-connections/{google|dropbox}/authorize/hds_<ticket>`
  for the provider requested. Each check runs twice.
- **Storage IPC:** account and transaction IDs must be UUIDs, and `provider` must be one of the
  enum values. File IDs must match the Drive or Dropbox ID alphabet. Directory locators must be
  bounded and free of control characters (the backend validates them per provider), and
  continuations must be bounded printable ASCII. Unknown keys are
  rejected. The main process only polls attempts it started, and every response is re-parsed
  field by field, so no token-shaped value can reach the renderer.
- **Loopback listener:** bound to `127.0.0.1` on an OS-assigned port. It accepts only
  `GET /callback` with `Host: 127.0.0.1:<port>` (DNS-rebinding guard) and the exact state. Stray
  requests are refused without ending the attempt. It closes on success, cancel, timeout (5 min)
  or a new attempt.
- **Refreshes:** single-flight. The access token is refreshed 30 s before expiry.

## Known limitations

- **Cached content and revoked access:** a chunk cached before access was revoked at Dropbox can
  still be served until the account is disconnected, the user logs out or the entry is evicted.
  Hits don't contact the provider.

- **Delegated tokens outlive local cleanup:** see
  [Provider-read delegation](#provider-read-delegation-batch-6). Malware running as the same
  Windows user can copy a delegated token from process memory and use it account-wide until it
  expires at the provider.
- **Provider reads not verified live:** they are tested only against local mock provider
  servers. Real Google and Dropbox range behavior, error bodies and redirects are unverified.

- **Agent orphans:** an agent that hangs without reading stdin, after Electron has died, isn't
  cleaned up automatically. There's no Windows Job Object yet; the data-directory lock then makes
  a new agent report `data-dir-locked`.

- **Live Google sign-in, Drive and Dropbox not verified:** none has been run against a real
  Google OAuth client or Dropbox app (no credentials were available). Automated tests use a fake Google. See the
  manual QA checklists.
- **Forwarded connection links:** an attacker who sends a victim their own storage authorize
  link (within its 2-minute ticket lifetime) could get the victim to grant Drive access to the
  **attacker's** HyperDrive account, if the victim approves Google's consent screen. The binding
  cookie doesn't prevent this, because the victim's browser starts the flow itself. A
  loopback-confirmation step (like sign-in's) would close this gap; it is not implemented.
- **Restricted scope:** `drive.readonly` is a Google restricted scope. The app is unverified and
  not security-assessed, so it isn't fit for public distribution.
- **Same-user local processes:** anything running as the same Windows user can read the
  `safeStorage` file's ciphertext. It can't decrypt it outside that user's DPAPI context, but
  malware running as you can still impersonate the app. This is inherent to desktop apps.
- **Loopback interception:** a local process could race to read the loopback redirect. It still
  can't redeem the handoff code without the PKCE verifier, which never leaves the main process.
- **Not production-hardened:** there is no proxy support (`X-Forwarded-*` is ignored, so
  public deployments behind a proxy would rate-limit by proxy IP and build wrong URLs), no
  HTTPS termination, no multi-instance rate limiting, and no audit log. Deployment hardening is
  future work.
- **Session lifetime:** sessions are an absolute 30 days (no sliding renewal). There is no
  "sign out all devices" UI yet.
- **Development builds only:** builds are unsigned (no code-signing certificate), and there's no
  installer yet.
