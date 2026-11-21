# Development guide

See the [README](../README.md) for setup and run commands. This page covers details.

## Configuration: who reads what

| Variable | Read by | How it's loaded | Default |
| --- | --- | --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` | Docker Compose | `./.env`, read automatically by `docker compose` | `hyperdrive`, `hyperdrive`, **required**, `5432` |
| `REDIS_PASSWORD`, `REDIS_PORT` | Docker Compose (`redis` profile) | `./.env` | **required when used**, `6379` |
| `HYPERDRIVE_BACKEND_ADDRESS` | Backend | Process environment (Spring placeholders in `application.yaml`) | `127.0.0.1` |
| `HYPERDRIVE_BACKEND_PORT` | Backend | Process environment | `18080` |
| `HYPERDRIVE_BACKEND_URL` | Desktop **main process** only | Process environment (`process.env`) | `http://127.0.0.1:18080` |

Neither the backend nor the desktop loads `.env`. Set variables in the PowerShell session that
starts them (`$env:NAME = "value"`). The renderer never receives environment variables, and no
secrets exist in the desktop app.

Why port 18080 and not 8080: 8080 is commonly occupied by other local web servers (on the
development machine it was taken by an Apache `httpd`).

## Desktop (`desktop/`)

| Command | What it does |
| --- | --- |
| `npm ci` | Install exact dependency versions from `package-lock.json` |
| `npm run dev` | electron-vite dev server + Electron window with hot reload |
| `npm run build` | Production build into `desktop/out/` (main, preload, renderer) |
| `npm start` | Run the built app from `out/` (`electron-vite preview`) |
| `npm run typecheck` | `tsc` for the main/preload (`tsconfig.node.json`) and renderer (`tsconfig.web.json`) projects |
| `npm run lint` | ESLint (typescript-eslint strict + react-hooks) |
| `npm test` | Vitest: renderer tests in jsdom, main-process tests in node |
| `npm run check` | typecheck, lint, test, build |

Layout:

```
src/main/       Electron main process: window, security hardening, IPC, health check
src/preload/    contextBridge API (one function)
src/shared/     Types shared by all three contexts (no runtime dependencies)
src/renderer/   React UI (index.html, src/)
test/setup.ts   Vitest setup (jest-dom matchers, cleanup)
```

Version notes:
- TypeScript is pinned to **6.0.3**, not 7.x, because typescript-eslint 8.73 supports only
  `typescript < 6.1`.
- `electron` 45 downloads its binary the first time it runs, not during `npm install`.

Packaging (installer, code signing) is not set up yet.

## Backend (`backend/`)

| Command | What it does |
| --- | --- |
| `.\mvnw.cmd verify` | Compile (Java 21 bytecode) and run tests |
| `.\mvnw.cmd spring-boot:run` | Run on `127.0.0.1:18080` |
| `.\mvnw.cmd package` then `java -jar target\hyperdrive-backend-0.1.0-SNAPSHOT.jar` | Run the packaged jar |

- Spring Boot **4.2.0**, Java release **21** (`maven-enforcer-plugin` requires JDK 21+).
- Only `spring-boot-starter-webmvc` is on the runtime classpath. There is no Actuator, no JDBC and
  no Redis client, so startup cannot depend on optional services.
- Error responses never include stack traces, messages or exception names.
- No authentication exists yet. Keep the default loopback binding.

## Development services

See the README. `compose.yaml` refuses to start PostgreSQL without `POSTGRES_PASSWORD` and Redis
without `REDIS_PASSWORD`. Host ports bind to `127.0.0.1` only.
