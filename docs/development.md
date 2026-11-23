# Development guide

See the [README](../README.md) for setup and run commands. This page covers details.

## Configuration: who reads what

| Variable | Read by | How it's loaded | Default |
| --- | --- | --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` | Docker Compose | `./.env`, read automatically by `docker compose` | `hyperdrive`, `hyperdrive`, **required**, `5432` |
| `REDIS_PASSWORD`, `REDIS_PORT` | Docker Compose (`redis` profile) | `./.env` | **required when used**, `6379` |
| `HYPERDRIVE_BACKEND_ADDRESS`, `HYPERDRIVE_BACKEND_PORT` | Backend | Process environment | `127.0.0.1`, `18080` |
| `SPRING_PROFILES_ACTIVE` | Backend | Process environment | unset (health-only mode); `auth` enables sign-in |
| `HYPERDRIVE_DB_URL`, `HYPERDRIVE_DB_USERNAME`, `HYPERDRIVE_DB_PASSWORD` | Backend (auth) | Process environment | `jdbc:postgresql://127.0.0.1:5432/hyperdrive`, `hyperdrive`, **required** |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Backend (auth) | Process environment | **required** (startup fails without them) |
| `HYPERDRIVE_PUBLIC_BASE_URL` | Backend (auth) | Process environment | **required**, e.g. `http://127.0.0.1:18080` |
| `HYPERDRIVE_SECURE_COOKIES` | Backend (auth) | Process environment | `false` (set `true` behind https) |
| `HYPERDRIVE_CREDENTIAL_KEY` | Backend (auth) | Process environment | **required**: base64 of 32 random bytes (encrypts Google Drive tokens) |
| `HYPERDRIVE_CREDENTIAL_KEY_VERSION`, `HYPERDRIVE_CREDENTIAL_PREVIOUS_KEYS` | Backend (auth) | Process environment | `1`, empty (key rotation) |
| `GOOGLE_DRIVE_CLIENT_ID`, `GOOGLE_DRIVE_CLIENT_SECRET` | Backend (auth) | Process environment | Fall back to `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` |
| `DROPBOX_APP_KEY`, `DROPBOX_APP_SECRET` | Backend (auth) | Process environment | empty = Dropbox disabled; only one set = startup fails |
| `HYPERDRIVE_BACKEND_URL` | Desktop **main process** only | Process environment (`process.env`) | `http://127.0.0.1:18080` |
| `HYPERDRIVE_AGENT_JAVA` | Desktop main process | Process environment | unset → `JAVA_HOME\bin\java.exe` → `java` on PATH |
| `HYPERDRIVE_AGENT_JAR` | Desktop main process | Process environment | `<repo>\agent\target\hyperdrive-agent.jar` (development only) |
| `HYPERDRIVE_AGENT_LOG_LEVEL` | Desktop main process (passed to the agent) | Process environment | `INFO` |

Neither the backend nor the desktop loads `.env`. Set variables in the PowerShell session that
starts them (`$env:NAME = "value"`). The renderer never receives environment variables, and the
desktop holds no backend secrets.

**Ports:**
- **18080 instead of 8080:** 8080 is commonly occupied by other local web servers. On the
  development machine an Apache `httpd` was using it.
- **5432:** a native PostgreSQL install may already use it. Set `POSTGRES_PORT` (e.g. `55432`)
  in `.env` and the same port in `HYPERDRIVE_DB_URL`.

## Desktop (`desktop/`)

| Command | What it does |
| --- | --- |
| `npm ci` | Install exact dependency versions from `package-lock.json` |
| `npm run dev` | electron-vite dev server + Electron window with hot reload |
| `npm run build` | Production build into `desktop/out/` (main, preload, renderer) |
| `npm start` | Run the built app from `out/` (`electron-vite preview`) |
| `npm run typecheck` | `tsc` for the main/preload (`tsconfig.node.json`) and renderer (`tsconfig.web.json`) projects |
| `npm run lint` | ESLint (typescript-eslint strict + react-hooks) |
| `npm test` | Vitest: renderer tests in jsdom, main-process tests in node (including a real loopback HTTP listener) |
| `npm run check` | typecheck, lint, test, build |

Layout:

```
src/main/               Electron main process: window, hardening, IPC, health check
src/main/auth/          Sign-in: PKCE, loopback listener, backend client, token store, AuthManager
src/main/ipc-validation.ts   Payload validation for renderer → main calls
src/main/storage/       Cloud storage: StorageManager (bridge to backend APIs), strict response parsers
src/main/agent/         Local agent: AgentManager (lifecycle), Java discovery, handshake/status parsing, real spawn/fetch
src/preload/            contextBridge API (health + auth status/actions, no tokens)
src/shared/             Types and rules shared by all contexts (auth status, username rules)
src/renderer/src/       React UI: App (routing by auth phase), auth screens, onboarding, Shell,
                        CloudAccounts (connect/disconnect), FilesBrowser (read-only listing)
test/                   Vitest setup and the mock preload bridge used by renderer tests
```

Version notes:
- TypeScript is pinned to **6.0.3**, not 7.x, because typescript-eslint 8.73 supports only
  `typescript < 6.1`.
- `electron` 45 downloads its binary the first time it runs, not during `npm install`. After
  `npm ci`, the first `npm run dev` or `npm start` downloads it again.

Packaging (installer, code signing) is not set up yet.

## Local agent (`agent/`)

| Command | What it does |
| --- | --- |
| `.\mvnw.cmd verify` | Compile (Java 21), run unit and real-process tests, build `target\hyperdrive-agent.jar` |
| `.\mvnw.cmd package -DskipTests` | Just rebuild the jar |

- **Dependencies:** plain Java on the JDK HTTP server plus Jackson. The Spring Boot parent POM
  only supplies dependency versions and the runnable-jar packaging; no Spring runtime, no
  database. Docker isn't needed.
- **How it runs:** the desktop starts it automatically (see [local-agent.md](local-agent.md)).
  Running the jar by hand is only useful together with a bootstrap message on stdin.
- **Desktop smoke test:** `desktop/src/main/agent/agent-smoke.test.ts` drives the **real** jar
  through the production `AgentManager` with real `spawn` and `fetch`. It's skipped (shown as
  skipped by Vitest) when the jar hasn't been built.

## Virtual drive (`vfs/`)

Windows x64 only. Prerequisites (install yourself; nothing is installed by the build):
- Visual Studio 2022 or its Build Tools with **Desktop development with C++** (MSVC x64; the
  bundled CMake and Ninja are used).
- **WinFsp** with the **Developer** feature (headers and `winfsp-x64.lib` under
  `C:\Program Files (x86)\WinFsp`). Tested with WinFsp 2.1 (2025 release).

```powershell
cd vfs
.\build.cmd                                   # configure, build, run native unit tests
node tests\fixture-mount-check.mjs build\hyperdrive-vfs.exe          # opt-in: real mount, fixture data
node tests\agent-mount-check.mjs ..\agent\target\hyperdrive-agent.jar build\hyperdrive-vfs.exe  # opt-in: real agent chain
```

The first configure downloads nlohmann/json 3.11.3 source (pinned by SHA-256). The desktop uses
`vfs\build\hyperdrive-vfs.exe` in development, or an absolute `HYPERDRIVE_VFS_EXE`. See
[virtual-drive.md](virtual-drive.md).

## Backend (`backend/`)

| Command | What it does |
| --- | --- |
| `.\mvnw.cmd verify` | Compile (Java 21 bytecode) and run all tests; database tests need Docker |
| `.\mvnw.cmd spring-boot:run` | Run on `127.0.0.1:18080` (health-only unless `SPRING_PROFILES_ACTIVE=auth`) |
| `.\mvnw.cmd package` then `java -jar target\hyperdrive-backend-0.1.0-SNAPSHOT.jar` | Run the packaged jar |

- **Versions:** Spring Boot **4.2.0** (Spring Security 7.2), Java release **21**
  (`maven-enforcer-plugin` requires JDK 21+).
- **Packages:**

  ```
  com.hyperdrive.backend
    auth/            SecureTokens, Pkce, startup check, cleanup job
    auth/desktop/    login transactions, desktop + browser controllers, OAuth glue and handlers
    auth/session/    device sessions, rotation/replay, bearer principal
    user/            accounts, identities, onboarding, /api/v1/me
    security/        filter chains (auth-enabled and health-only)
    web/             correlation IDs, rate limiting, sanitized errors
    health/          liveness and readiness
    storage/         StorageConfig (clients, timeouts), StorageStartupCheck
    storage/connect/ Drive consent: transactions, browser leg + callback, Spring OAuth glue
    storage/account/ connected accounts, encrypted credentials + serialized refresh, accounts/files API
    storage/files/   browsing orchestration (ownership → credentials → provider, error mapping),
                     ContinuationCodec (encrypted, account-bound continuations)
    storage/provider/  FileBrowser abstraction, ProviderFile DTO, ProviderHttp (shared retries);
                     google/ = Drive v3 adapter, dropbox/ = Dropbox API v2 adapter
    storage/crypto/  AES-256-GCM CredentialCipher
  ```
- **Database access:** plain `JdbcClient` with explicit SQL. Every single-use or rotating state
  change is one conditional `UPDATE … RETURNING`. Migrations live in
  `src/main/resources/db/migration` (Flyway).
- **Auth-disabled mode:** database auto-configuration is excluded in `application.yaml` and
  re-enabled by `application-auth.yaml`.

### Backend tests

- **Unit tests** (no Docker): PKCE (RFC 7636 test vector), token format, username rules, loopback
  URL construction, startup validation, rate limiter, correlation IDs, readiness with a broken
  database.
- **Integration tests** (`AuthIntegrationTest` base): run against **PostgreSQL 18 via
  Testcontainers** with the real Flyway migrations, and against `FakeOidcProvider`, an in-process
  OIDC provider (discovery, token, JWKS, userinfo) that signs real RS256 ID tokens. That means
  Spring Security's OIDC validation runs unmodified. No live Google credentials are needed.
  For Batch 3 the same fake also serves the refresh-token grant and a small Drive v3 (`files.list`
  children queries, `files.get`), with error injection (401/403 reasons/429 + Retry-After/5xx).
  Storage tests use a fixed test-only credential key that exists only in test code.
  For Batch 4, `FakeDropbox` (JDK HttpServer) serves `/oauth2/token` (with Basic client auth
  checked), `users/get_current_account`, `files/list_folder`, `list_folder/continue` and
  `get_metadata`. It answers files calls only for the namespace in `Dropbox-API-Path-Root`, and
  supports error injection.
- **Without Docker:** the integration classes are **skipped** (`disabledWithoutDocker`). Maven
  reports them as skipped, never as passed.

## Development services

See the README. `compose.yaml` refuses to start PostgreSQL without `POSTGRES_PASSWORD` and Redis
without `REDIS_PASSWORD`. Host ports bind to `127.0.0.1` only. Nothing uses Redis yet.

**Compose project name:** `compose.yaml` uses the project name `hyperdrive`. If older containers
or volumes with that project name exist on your machine (for example from an earlier codebase),
Compose treats them as part of this project. List them with
`docker ps -a --filter label=com.docker.compose.project=hyperdrive`.
