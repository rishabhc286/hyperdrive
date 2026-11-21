# Progress

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
