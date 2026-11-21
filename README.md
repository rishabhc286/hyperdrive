# HyperDrive

HyperDrive is a Windows desktop application, in early development, that will bring
**Google Drive** and **Dropbox** together behind one read-only virtual drive in File Explorer.
Files will stream on demand in 4 MiB chunks and be cached on the local disk, so they don't need
to be downloaded in full first.

> **Status: foundation only.** This repository currently contains a desktop shell and a backend
> health endpoint. There is no sign-in, no cloud connection, no virtual drive, and no file access yet.
> See [docs/progress.md](docs/progress.md).

The launch website is a separate project and is not part of this repository.

## What this batch implements

- **Desktop shell** (`desktop/`): Electron + React + TypeScript + Vite (via electron-vite).
  - Dark-theme UI with sidebar navigation: Overview, Files, Cloud Accounts, Performance, Settings.
  - Each section shows an honest empty state ("No cloud accounts connected.", and so on).
  - "Development build" indicator.
  - Live backend status (checking / connected / unavailable), checked by the Electron main
    process every 15 s and on demand.
  - Disabled "Connect Google Drive" / "Connect Dropbox" buttons that explain why they are disabled.
  - Hardened Electron configuration (see [docs/architecture.md](docs/architecture.md#desktop-security)).
- **Backend** (`backend/`): Java 21 + Spring Boot 4.2 + Maven Wrapper, serving
  `GET /api/v1/health`. Starts without PostgreSQL or Redis.
- **Development services** (`compose.yaml`): PostgreSQL 18, plus optional Redis 8 behind a profile.
- Tests for the backend, the desktop UI and the desktop main-process logic.

## What is *not* implemented

Sign-in and accounts, username onboarding, Google Drive or Dropbox connections, file browsing,
the local agent (`agent/`), the WinFsp virtual drive (`vfs/`), streaming, caching, metrics,
installer/packaging, production authentication and deployment hardening. The backend has **no
authentication** and must not be exposed beyond your own machine.

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Windows 10/11 | x64 | Running the desktop app (target platform) |
| Node.js | 22.12+ (tested with 24.19.0) and npm | Desktop |
| JDK | 21 or newer (tested with JDK 25, compiled for Java 21) | Backend |
| Docker Desktop | Optional (Compose v2) | PostgreSQL / Redis only |

Maven is **not** required: use the bundled wrapper (`mvnw.cmd`), which downloads Maven 3.9.16
on first use. `npm ci` downloads Electron's binary into `desktop/node_modules` the first time
Electron runs.

## Setup (Windows PowerShell)

```powershell
cd C:\path\to\Hyperdrive

# Desktop dependencies (exact versions from package-lock.json)
cd desktop
npm ci
cd ..

# Backend: compile and run tests once (downloads Maven + dependencies on first run)
cd backend
.\mvnw.cmd verify
cd ..
```

## Run the backend and desktop together

Use two PowerShell windows.

**Window 1: backend**

```powershell
cd C:\path\to\Hyperdrive\backend
.\mvnw.cmd spring-boot:run
# Listens on http://127.0.0.1:18080
# Check it: Invoke-RestMethod http://127.0.0.1:18080/api/v1/health
```

**Window 2: desktop (development mode, hot reload)**

```powershell
cd C:\path\to\Hyperdrive\desktop
npm run dev
```

The HyperDrive window opens. The status in the top-right shows **Backend: connected** while the
backend runs, and **Backend: unavailable** if you stop it (it re-checks every 15 s, or press
**Check again** on the Overview).

To run the built (non-dev) desktop app instead: `npm run build` then `npm start`.

### Using a different backend port

```powershell
# Window 1
$env:HYPERDRIVE_BACKEND_PORT = "18081"; .\mvnw.cmd spring-boot:run
# Window 2
$env:HYPERDRIVE_BACKEND_URL = "http://127.0.0.1:18081"; npm run dev
```

## Optional development services (PostgreSQL, Redis)

Not needed for this batch: nothing connects to them yet.

```powershell
cd C:\path\to\Hyperdrive
Copy-Item .env.example .env      # then edit the passwords in .env
docker compose up -d             # PostgreSQL only
docker compose --profile redis up -d   # PostgreSQL + Redis
docker compose ps                # wait for "healthy"
docker compose down              # stop (data volumes are kept)
```

Ports are bound to `127.0.0.1` only. See [.env.example](.env.example) for which process reads
which variable. Only Docker Compose reads `.env`; the backend and desktop read shell
environment variables.

## Checks

```powershell
# Backend: compile + tests
cd backend;  .\mvnw.cmd verify;  cd ..

# Desktop
cd desktop
npm run typecheck
npm run lint
npm test
npm run build
npm run check      # all four of the above, in order
cd ..

# Compose file syntax (needs Docker CLI, not a running daemon)
docker compose --env-file .env.example config -q
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Backend log says `Port 18080 was already in use` | Another program uses the port. Pick another: `$env:HYPERDRIVE_BACKEND_PORT="18081"`, and set `$env:HYPERDRIVE_BACKEND_URL="http://127.0.0.1:18081"` for the desktop. |
| Desktop shows "did not respond like the HyperDrive backend" | Something else is listening at the configured address. Check `HYPERDRIVE_BACKEND_URL` and the port. |
| Desktop shows "backend address is not configured correctly" | `HYPERDRIVE_BACKEND_URL` must be an origin only, e.g. `http://127.0.0.1:18080` (no path, query or credentials). |
| `.\mvnw.cmd` fails with a Java version error | Install JDK 21+ and make sure `java -version` (or `JAVA_HOME`) points to it. |
| `npm run dev` says Electron failed to install | Delete `desktop\node_modules\electron` and run `npm ci` again; corporate proxies may block the Electron download. |
| `docker compose` says `POSTGRES_PASSWORD` is missing | Create `.env` from `.env.example`. |
| `docker compose` cannot connect to the Docker API | Start Docker Desktop. |
| Stopping the backend from a script leaves it running | On Windows, the `java` launcher in `Common Files\Oracle\Java\javapath` starts a child JVM. Stop the process that owns the port instead. Use Ctrl+C in the terminal normally. |

## Repository layout

```
backend/    Spring Boot service (health endpoint only)
desktop/    Electron + React desktop app
agent/      Planned local agent (README only)
vfs/        Planned WinFsp virtual drive (README only)
docs/       Architecture, development notes, progress log
compose.yaml, .env.example
```

More detail: [docs/development.md](docs/development.md) · [docs/architecture.md](docs/architecture.md)

## License

Intended license: GPL-3.0 (the planned WinFsp integration is GPLv3 with a FLOSS exception). A
`LICENSE` file has not been added yet; see [docs/progress.md](docs/progress.md).
