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
  Google grant, and the UI and docs say so.

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
- **Read-only scopes:** `account_info.read files.metadata.read`; no write or content scopes.
- **Root:** every files call is pinned to the stored root namespace (`Dropbox-API-Path-Root`).
  The namespace is validated as digits before it goes into the header.
- **Continuations:** provider cursors never reach the desktop. Wrapped continuations fail for any
  other account, provider or folder, before any provider call.
- **Disconnect:** local only. `auth/token/revoke` isn't called; see [dropbox.md](dropbox.md).

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
