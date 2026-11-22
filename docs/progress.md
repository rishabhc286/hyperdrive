# Progress

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
