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
| Google access/ID tokens | Used transiently by Spring Security during the callback | Stored or sent to the desktop |

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

## Desktop

- **Electron hardening (Batch 1):** sandbox, context isolation, no Node in the renderer, strict
  CSP, navigation, pop-ups and permissions blocked.
- **IPC:** handlers answer only the app's own window, main frame, at the trusted renderer URL.
  Payloads are validated (`completeOnboarding` accepts exactly `{ username: string }`), and every
  other channel takes no arguments.
- **System browser:** `shell.openExternal` is reachable only from main-process sign-in code, and
  only for `<trusted backend origin>/api/v1/auth/desktop/authorize/<uuid>`. The check runs twice.
- **Loopback listener:** bound to `127.0.0.1` on an OS-assigned port. It accepts only
  `GET /callback` with `Host: 127.0.0.1:<port>` (DNS-rebinding guard) and the exact state. Stray
  requests are refused without ending the attempt. It closes on success, cancel, timeout (5 min)
  or a new attempt.
- **Refreshes:** single-flight. The access token is refreshed 30 s before expiry.

## Known limitations

- **Live Google sign-in not verified:** it has not been run against a real Google OAuth client
  (no credentials were available). Automated tests use a fake OIDC provider. See the manual QA
  checklist.
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
