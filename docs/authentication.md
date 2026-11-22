# Authentication

> Connecting Google Drive or Dropbox is a **separate** flow with its own scopes and callback; it
> never changes the sign-in described here. See [google-drive.md](google-drive.md) and
> [dropbox.md](dropbox.md).

HyperDrive accounts are created by signing in with Google. There are no HyperDrive passwords.
Signing in identifies you and nothing more. **It does not connect Google Drive**: connecting
storage (Google Drive or Dropbox) will be a separate, explicit step in a later batch. A user can
sign in with Google and later connect only Dropbox.

This is an **application-specific desktop handoff** built on Spring Security's OAuth 2.0 / OpenID
Connect client support. The backend is **not** a general-purpose OAuth authorization server, and
the handoff endpoints are not a standards-compliant OAuth/OIDC provider.

## Flow

```
Electron main                 Backend (Spring Boot)                    System browser        Google
─────────────                 ─────────────────────                    ──────────────        ──────
verifier, S256 challenge,
desktop state; listen on
127.0.0.1:<random port>
POST /api/v1/auth/desktop/transactions ─▶ store PENDING tx (5 min)
   {codeChallenge, S256, callbackPort,    ◀─ {transactionId, authorizationUrl}
    callbackState, deviceName}
check URL is <backend>/api/v1/auth/desktop/authorize/<uuid>
shell.openExternal(url) ─────────────────────────────────────────────▶ GET authorize/<id>
                              mark tx started (once), build Google
                              auth request (state, nonce, PKCE),
                              bind tx id inside it ──────────────────▶ 302 ──────────────▶ consent
                                                                        GET /login/oauth2/code/google ◀─
                              Spring validates state, code exchange,
                              ID token (sig, iss, aud, exp, nonce)
                              find/create user by (issuer, subject)
                              tx → AUTHENTICATED + hashed handoff code (60 s)
                              discard browser session ───────────────▶ 302 http://127.0.0.1:<port>/callback?code=hdc_…&state=…
check state (constant time) ◀──────────────────────────────────────────
close listener
POST /api/v1/auth/desktop/exchange ──▶ consume code atomically, verify PKCE,
   {transactionId, code, codeVerifier}   create device session
                                     ◀─ {accessToken, refreshToken, expiries, user}
store refresh token (safeStorage), keep access token in memory
renderer ◀ status only: onboarding / signed-in
```

Details that matter:
- **Separate states:** the desktop `callbackState` and Spring's Google OAuth `state` are
  different values. The transaction is bound to Spring's authorization request (stored inside
  it, keyed by Spring's state), so concurrent logins in one browser can't be mixed up. There is
  no global "current transaction".
- **Generic entry point disabled:** Spring's `/oauth2/authorization/google` is turned off.
  Google sign-in can only start from a valid, unstarted transaction.
- **Browser session is throwaway:** the backend never keeps a HyperDrive login in the browser.
  The security context is request-scoped and the session is invalidated after the callback.
  Every desktop sign-in needs its own Google round trip.
- **Redirect contents:** the redirect to the loopback carries only `code` and `state`. No Google
  tokens, HyperDrive tokens or personal data.
- **Cancellation:** if you cancel at Google (`access_denied`), the backend marks the
  transaction `FAILED` and redirects the browser to the loopback with `error=access_denied`, so
  the app updates immediately.
- **App-side cancel and timeout:** "Cancel sign-in" in the app, a 5-minute timeout, or starting a
  new attempt all abort the current attempt and close its listener.

## Endpoints

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /api/v1/health` | none | Liveness (unchanged from Batch 1) |
| `GET /api/v1/ready` | none | Readiness: `{"status":"READY"\|"NOT_READY","auth":"ENABLED"\|"DISABLED"}`. 503 when auth is enabled and the database is unreachable |
| `POST /api/v1/auth/desktop/transactions` | none, rate-limited | Start a login transaction |
| `GET /api/v1/auth/desktop/authorize/{id}` | none (browser), rate-limited | Start Google sign-in for that transaction |
| `GET /login/oauth2/code/google` | browser (Spring Security) | Google callback |
| `POST /api/v1/auth/desktop/exchange` | none, rate-limited | Handoff code + PKCE verifier → tokens |
| `POST /api/v1/auth/refresh` | none, rate-limited | Rotate refresh token → new token pair |
| `POST /api/v1/auth/logout` | Bearer | Revoke this device session |
| `GET /api/v1/me` | Bearer | Safe user shape |
| `POST /api/v1/me/onboarding` | Bearer, rate-limited | Set username once |

Every other `/api/**` route requires a Bearer token **and** a completed username (the
`ONBOARDED` authority). All other paths are denied.

Errors are RFC 9457 problem documents with a stable `code` and a `correlationId`. For example:
`invalid_request`, `invalid_grant`, `invalid_refresh_token`, `unauthenticated`, `forbidden`,
`invalid_username`, `username_reserved`, `username_taken`, `onboarding_already_complete`,
`rate_limited`.

Safe user shape (the only user data the desktop receives):

```json
{ "id": "…", "username": null, "displayName": "…", "onboardingComplete": false }
```

## Tokens and storage

| Secret | Format | Lifetime | Backend stores | Desktop stores |
| --- | --- | --- | --- | --- |
| Handoff code | `hdc_` + 256 random bits | 60 s, single use | SHA-256 hash | Nowhere (used immediately) |
| Access token | `hda_` + 256 random bits | 15 min | SHA-256 hash | Main-process memory only |
| Refresh token | `hdr_` + 256 random bits | Session: 30 days, absolute | SHA-256 hash (current + superseded) | Encrypted with Electron `safeStorage` in `%APPDATA%\HyperDrive\session\refresh-token.json` (Electron `userData`; created on first successful sign-in) |
| Google tokens | — | — | **Not stored** | **Never received** |

- **Rotation:** every refresh issues a new refresh token through one atomic conditional
  `UPDATE`, records the old hash as superseded, and deletes the session's old access tokens.
- **Replay:** presenting a superseded refresh token revokes the whole session (token family).
  The desktop serializes refreshes (single-flight), so parallel requests never trigger a false
  replay.
- **Logout:** revokes the session and deletes its access tokens. The desktop clears local
  credentials **even if** the backend can't be reached, and then says revocation wasn't
  confirmed.
- **Expiry:** sessions expire 30 days after sign-in, and you must sign in again. This is an
  absolute limit, not extended by use.
- **No plaintext fallback:** if OS encryption is unavailable (or is Linux's `basic_text`), the
  session is memory-only and the welcome screen says you'll need to sign in after each restart.
- **Renderer:** React receives only `AuthStatus` (phase, safe user, persistence, notices). Never
  a token.

## Identity and accounts

- **Account key:** the validated OIDC `iss` + `sub`, unique in `auth_identities`. Email is never
  used to match or link accounts. Two Google accounts with the same email are two users.
- **First sign-in:** creates the user and identity in one transaction. Concurrent first
  sign-ins are resolved by the unique constraint; the loser discards its tentative user.
- **Profile fields:** display name and email are optional. Email is stored only if Google says
  it's verified. Returning sign-ins update these fields but never clear them.

## Username onboarding

- 3–24 ASCII letters, digits or underscores. The chosen casing is kept, and uniqueness is
  case-insensitive (`username_normalized = lower(username)`, unique).
- **Reserved:** `admin`, `administrator`, `support`, `system`, `hyperdrive`, `root`,
  `security`, `help`.
- **Validation:** enforced by the backend (and by database CHECK constraints). The UI checks too,
  for instant feedback.
- **Races:** handled by the unique constraint, not a pre-check (one winner, the others get
  `username_taken`).
- **Who can set it:** only once, and only for the user behind the bearer token. Unknown JSON
  fields such as `userId` are rejected.
- **Until a username is set:** only `GET /api/v1/me`, `POST /api/v1/me/onboarding` and
  `POST /api/v1/auth/logout` are allowed. Editing a username later is deferred.

## Database schema (Flyway `V1__authentication.sql`)

| Table | Purpose |
| --- | --- |
| `users` | `id`, `username` (nullable until onboarding), `username_normalized` (unique), `display_name`, `contact_email`, `onboarding_completed_at`, `created_at`, `updated_at`, `version` |
| `auth_identities` | `user_id` → users, `issuer`, `subject`, unique (`issuer`, `subject`) |
| `device_sessions` | One per signed-in device: `current_refresh_token_hash` (unique), `token_family_id`, `created_at`, `last_used_at`, `expires_at`, `revoked_at`, `revoked_reason` |
| `superseded_refresh_tokens` | Rotated-away refresh hashes → session (for replay detection) |
| `access_tokens` | Access-token hashes → session, `expires_at` |
| `login_transactions` | PKCE challenge, callback port, desktop state, status (`PENDING`/`AUTHENTICATED`/`CONSUMED`/`FAILED`), `browser_started_at`, `user_id`, `handoff_code_hash` (unique), `handoff_expires_at`, `consumed_at` |

CHECK constraints enforce username format, normalization, onboarding consistency, port range,
challenge and state syntax, and 32-byte hashes. A scheduled job purges expired rows every 15
minutes; correctness never depends on it.

## Auth disabled vs. enabled

| | Default (no profile) | `SPRING_PROFILES_ACTIVE=auth` |
| --- | --- | --- |
| Database | Not used; DB auto-configuration excluded | Required (PostgreSQL); Flyway migrates on start |
| Routes | `/api/v1/health`, `/api/v1/ready` only (everything else 403) | All routes above |
| `/api/v1/ready` | `READY`, `DISABLED` | `READY`/`NOT_READY`, `ENABLED` |
| Desktop | "Sign-in isn't enabled" (no fake login) | Welcome → Google sign-in |
| Missing config | n/a | Startup fails with a clear message (client ID/secret, public base URL, redirect URI, extra scopes) |

## Google Cloud Console setup

1. Open <https://console.cloud.google.com/>, then create or select a project.
2. Go to **APIs & Services → OAuth consent screen** (Google Auth Platform → Branding/Audience):
   - User type **External**. Fill in the app name and support email.
   - Scopes: only `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`. **Do not add
     any Drive scope.**
   - While the app is in **Testing**, add your Google account under **Test users**.
3. Go to **Credentials → Create credentials → OAuth client ID**:
   - Application type: **Web application** (the backend is a confidential client holding the
     secret; the desktop never sees it).
   - **Authorized redirect URI**, exactly: `http://127.0.0.1:18080/login/oauth2/code/google`.
     If you change the port or host, change `HYPERDRIVE_PUBLIC_BASE_URL` to match.
   - If the console refuses the `127.0.0.1` form, use `http://localhost:18080/login/oauth2/code/google`
     and set **both** `HYPERDRIVE_PUBLIC_BASE_URL` and `HYPERDRIVE_BACKEND_URL` to
     `http://localhost:18080`.
4. Copy the client ID and secret into `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` in the
   backend's PowerShell window. Never commit them.

**Why no Drive access:** sign-in proves identity only. Requesting Drive scopes here would grant
file access you didn't explicitly choose and would mix two consent decisions. Drive will get
its own consent flow, scopes and token storage in a later batch. The backend refuses to start if
the sign-in registration asks for any scope beyond `openid profile email`.

## Running with sign-in (Windows PowerShell)

```powershell
# 1. Database
cd C:\path\to\Hyperdrive
Copy-Item .env.example .env       # set POSTGRES_PASSWORD (and POSTGRES_PORT if 5432 is taken)
docker compose up -d postgres

# 2. Backend (window 1)
cd backend
$env:SPRING_PROFILES_ACTIVE = "auth"
$env:HYPERDRIVE_DB_URL = "jdbc:postgresql://127.0.0.1:5432/hyperdrive"   # your POSTGRES_PORT
$env:HYPERDRIVE_DB_PASSWORD = "<POSTGRES_PASSWORD from .env>"
$env:GOOGLE_CLIENT_ID = "<client id>"
$env:GOOGLE_CLIENT_SECRET = "<client secret>"
$env:HYPERDRIVE_PUBLIC_BASE_URL = "http://127.0.0.1:18080"
.\mvnw.cmd spring-boot:run

# 3. Desktop (window 2)
cd C:\path\to\Hyperdrive\desktop
npm run dev       # HYPERDRIVE_BACKEND_URL defaults to http://127.0.0.1:18080
```

## Manual QA checklist (requires real Google credentials)

Automated tests use a fake OIDC provider. **Live Google sign-in has not been verified yet.**

| # | Scenario | Steps | Expected |
| --- | --- | --- | --- |
| 1 | Fresh signup | Continue with Google → consent | Browser tab says "You're signed in"; app shows "Choose your username"; one row in `users` |
| 2 | Returning login | Sign out → Continue with Google | Goes straight to the dashboard (no username screen) |
| 3 | Username taken | Second Google account picks the first account's name in different case | "That username is already taken" |
| 4 | Login cancellation | Click Cancel on Google's consent | App shows "Sign-in was cancelled" |
| 5 | Browser closed early | Close the tab before consenting; wait 5 min, or press Cancel sign-in | App returns to welcome with timed-out/cancelled notice; listener port closed (`Get-NetTCPConnection -State Listen` shows no stray electron port) |
| 6 | Backend unavailable | Stop backend, start app | "Can't reach HyperDrive" with Try again; nothing crashes |
| 7 | Remembered session | Sign in, quit app, start again | Dashboard without signing in; `refresh-token.json` contains no `hdr_` text |
| 8 | Logout | Sign out | Welcome screen with "You've signed out"; `device_sessions.revoked_at` set; token file deleted |
| 9 | Logout while backend down | Sign in, stop backend, Sign out | Welcome with "couldn't be reached to confirm"; token file deleted |
| 10 | Expired/revoked session | `UPDATE device_sessions SET revoked_at=now(), revoked_reason='manual'`, restart app | "Your session has expired" |
| 11 | Two simultaneous attempts | Click Continue with Google, then Cancel and click again; complete only the second tab | Second succeeds; the first tab's link shows "can't be used" |
| 12 | No Drive permission | Read Google's consent screen | Only name, email and profile picture are requested; no Drive access |
