# HyperDrive

HyperDrive is a Windows desktop application, in early development, that will bring
**Google Drive** and **Dropbox** together behind one read-only virtual drive in File Explorer.
Files will stream on demand in 4 MiB chunks and be cached on the local disk, so they don't need
to be downloaded in full first.

> **Status: accounts + read-only Google Drive browsing.** You can sign in with Google (which
> creates a HyperDrive account), choose a username, then **separately** connect a Google Drive
> account and browse its folders and file metadata. There is **no file download, streaming,
> caching, virtual drive or Dropbox yet**. Live Google Drive use has not been tested with real
> credentials yet. See [docs/progress.md](docs/progress.md).

The launch website is a separate project and is not part of this repository.

## What exists so far

- **Batch 1 (foundation):** Electron + React desktop shell, Spring Boot backend with
  liveness/readiness endpoints, Docker Compose for PostgreSQL (and optional Redis).
- **Batch 2 (accounts):**
  - Google sign-in through the system browser, with a PKCE-protected loopback handoff to the app.
  - A HyperDrive account is created automatically on first sign-in, followed by first-time
    username onboarding.
  - Opaque device sessions: 15-minute access tokens and rotating refresh tokens with replay
    detection.
  - The desktop stores sessions with OS encryption (Electron `safeStorage`).
  - Sign-out revokes the session on the server when it can be reached.

- **Batch 3 (Google Drive, read-only browsing):**
  - "Connect Google Drive" is a separate consent flow from sign-in. It requests
    `drive.readonly` (a restricted scope), and any Google account can be connected, including a
    different one from the sign-in account.
  - Google tokens stay on the backend, encrypted with AES-256-GCM. They never reach the desktop.
  - Browse My Drive folders with pagination, see real metadata (type, size, modified time), and
    disconnect without signing out.

Design and security details: [docs/authentication.md](docs/authentication.md) ·
[docs/google-drive.md](docs/google-drive.md) ·
[docs/security.md](docs/security.md) · [docs/architecture.md](docs/architecture.md)

## Not implemented yet

- Dropbox, Shared drives / "Shared with me", opening or downloading files, exporting Google Docs,
  following shortcuts.
- The local agent (`agent/`) and the WinFsp virtual drive (`vfs/`).
- Streaming, caching and metrics.
- Google OAuth verification: `drive.readonly` is a restricted scope. HyperDrive is unverified
  and runs in Google's Testing mode, with at most 100 test users and a 7-day authorization expiry.
  See [docs/google-drive.md](docs/google-drive.md).
- Username editing, and a device list or "sign out everywhere".
- Installer/packaging, and production deployment hardening (proxy/HTTPS support).

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Windows 10/11 | x64 | Running the desktop app (target platform) |
| Node.js | 22.12+ (tested with 24.19.0) and npm | Desktop |
| JDK | 21 or newer (tested with JDK 25, compiled for Java 21) | Backend |
| Docker Desktop | Compose v2 | PostgreSQL for sign-in, and the backend's database tests (Testcontainers) |
| Google Cloud OAuth client | "Web application" type | Real sign-in only; see [setup](docs/authentication.md#google-cloud-console-setup) |

Maven is not required: use the bundled wrapper (`mvnw.cmd`). Electron downloads its binary into
`desktop/node_modules` the first time it runs.

## Setup (Windows PowerShell)

```powershell
cd C:\path\to\Hyperdrive
cd desktop; npm ci; cd ..
cd backend; .\mvnw.cmd verify; cd ..     # needs Docker running for the database tests
```

## Run: health-only mode (no database, no sign-in)

```powershell
# Window 1
cd C:\path\to\Hyperdrive\backend
.\mvnw.cmd spring-boot:run

# Window 2
cd C:\path\to\Hyperdrive\desktop
npm run dev
```

The app shows **"Sign-in isn't enabled"**, because the backend is running without the `auth`
profile. Nothing simulates a login.

## Run: with Google sign-in

1. Create a Google OAuth client (**Web application**) with the redirect URI
   `http://127.0.0.1:18080/login/oauth2/code/google`. Step-by-step:
   [docs/authentication.md](docs/authentication.md#google-cloud-console-setup).
2. Start PostgreSQL:
   ```powershell
   cd C:\path\to\Hyperdrive
   Copy-Item .env.example .env   # edit POSTGRES_PASSWORD; change POSTGRES_PORT if 5432 is taken
   docker compose up -d postgres
   docker compose ps             # wait for "healthy"
   ```
3. Backend (window 1). The variables apply to this window only:
   ```powershell
   cd C:\path\to\Hyperdrive\backend
   $env:SPRING_PROFILES_ACTIVE = "auth"
   $env:HYPERDRIVE_DB_URL = "jdbc:postgresql://127.0.0.1:5432/hyperdrive"   # use your POSTGRES_PORT
   $env:HYPERDRIVE_DB_PASSWORD = "<POSTGRES_PASSWORD from .env>"
   $env:GOOGLE_CLIENT_ID = "<client id>"
   $env:GOOGLE_CLIENT_SECRET = "<client secret>"
   $env:HYPERDRIVE_PUBLIC_BASE_URL = "http://127.0.0.1:18080"
   # Encrypts stored Google Drive tokens. 32 random bytes, base64 (generate once, keep it safe):
   $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
   $env:HYPERDRIVE_CREDENTIAL_KEY = [Convert]::ToBase64String($b)   # store this value; reuse it next time
   .\mvnw.cmd spring-boot:run
   ```
4. Desktop (window 2):
   ```powershell
   cd C:\path\to\Hyperdrive\desktop
   npm run dev
   ```
5. Click **Continue with Google**, finish in the browser, then choose a username.
6. Optional: **Cloud Accounts → Connect Google Drive**. This first needs the Drive API enabled
   and the redirect URI `http://127.0.0.1:18080/api/v1/storage-connections/google/callback`
   added to the same OAuth client; see [docs/google-drive.md](docs/google-drive.md#google-cloud-setup).
   Then browse it under **Files**.

To run the built app instead of the dev server: `npm run build`, then `npm start`.

### Which process reads which setting

Only Docker Compose reads `.env`. The backend and the desktop read environment variables from
the PowerShell window that starts them. [.env.example](.env.example) documents every variable.
The Google client secret is used by the backend only; never put it in desktop configuration.

## Checks

```powershell
cd backend;  .\mvnw.cmd verify;  cd ..        # unit + PostgreSQL integration tests (Docker required)
cd desktop;  npm run check;      cd ..        # typecheck, lint, tests, production build
docker compose --env-file .env.example config -q
```

If Docker isn't running, the backend's database tests are **skipped** (reported as skipped,
not passed).

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| App says "Sign-in isn't enabled" | Start the backend with `$env:SPRING_PROFILES_ACTIVE = "auth"` (and the other variables above). |
| App says "HyperDrive isn't ready" | The backend can't reach PostgreSQL. Check `docker compose ps`, `HYPERDRIVE_DB_URL` (port!) and `HYPERDRIVE_DB_PASSWORD`. |
| Backend fails with `Client id of registration 'google' must not be empty` | Set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in the backend window. |
| Backend fails with `HYPERDRIVE_CREDENTIAL_KEY is not set` / `must decode to exactly 32 bytes` | Generate a key as shown in step 3, and use the **same** key every time. A different key makes stored Drive connections unreadable, so you would have to reconnect. |
| "Connect Google Drive" → Google shows `redirect_uri_mismatch` | Add `<HYPERDRIVE_PUBLIC_BASE_URL>/api/v1/storage-connections/google/callback` to the OAuth client's redirect URIs. |
| Google Drive account shows **Needs reconnect** | The grant was revoked or expired. In Google's Testing mode authorizations expire after 7 days. Click **Reconnect**. |
| Files says Google Drive "could not be reached" | Enable the **Google Drive API** in the Cloud project, and check network access to `www.googleapis.com`. |
| Backend fails with `Google redirect URI must be …` or `HYPERDRIVE_PUBLIC_BASE_URL must be …` | Set `HYPERDRIVE_PUBLIC_BASE_URL` to an origin such as `http://127.0.0.1:18080`. |
| Google shows `redirect_uri_mismatch` | The redirect URI in Google Cloud Console must equal `<HYPERDRIVE_PUBLIC_BASE_URL>/login/oauth2/code/google` exactly. |
| Google shows "Access blocked: app not verified / not a test user" | Add your Google account as a test user on the OAuth consent screen. |
| "Sign-in didn't complete" after choosing an account | Check the backend log (search for the correlation ID). `HYPERDRIVE_BACKEND_URL` (desktop) and `HYPERDRIVE_PUBLIC_BASE_URL` (backend) must be the same origin. |
| `docker compose up` says port 5432 is not available | Another PostgreSQL is running locally. Set `POSTGRES_PORT=55432` in `.env` and use that port in `HYPERDRIVE_DB_URL`. |
| Backend log says `Port 18080 was already in use` | Pick another port: `$env:HYPERDRIVE_BACKEND_PORT="18081"`, and update `HYPERDRIVE_PUBLIC_BASE_URL`, `HYPERDRIVE_BACKEND_URL` and the Google redirect URI to match. |
| You must sign in after every restart | Windows encryption (DPAPI) was unavailable to Electron, so the session is memory-only by design (no plaintext fallback). |
| `npm run dev` says Electron failed to install | Delete `desktop\node_modules\electron` and run `npm ci`; corporate proxies may block the Electron download. |
| `.\mvnw.cmd` fails with a Java version error | Install JDK 21+ and point `JAVA_HOME` to it. |

## Repository layout

```
backend/    Spring Boot service: health/readiness, Google sign-in, accounts, sessions,
            Google Drive connections and read-only browsing
desktop/    Electron + React desktop app
agent/      Planned local agent (README only)
vfs/        Planned WinFsp virtual drive (README only)
docs/       Architecture, authentication, security, development notes, progress log
compose.yaml, .env.example
```

## License

Intended license: GPL-3.0 (the planned WinFsp integration is GPLv3 with a FLOSS exception). A
`LICENSE` file has not been added yet; see [docs/progress.md](docs/progress.md).
