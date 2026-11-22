# HyperDrive local agent

A **Java 21** process that the HyperDrive desktop app starts and supervises on the user's PC.

**Status (Batch 5):**
- **Built:** the process lifecycle and an authenticated loopback **status API** (`/local/v1/health`,
  `/local/v1/status`).
- **Not built:** reading files, talking to Google Drive or Dropbox, holding credentials, caching,
  and mounting a drive. All capability flags are `false`.

Full documentation: [docs/local-agent.md](../docs/local-agent.md) · design boundary:
[ADR 0001](../docs/adr/0001-local-agent-transport-and-content-boundary.md).

## Build (Windows PowerShell)

```powershell
.\mvnw.cmd verify       # tests (including a real separate-process test), then the jar
# -> target\hyperdrive-agent.jar
```

- **Dependencies:** plain Java with the JDK's built-in HTTP server and Jackson; no Spring runtime,
  database or Redis. The Spring Boot parent POM is used only for dependency versions (the same as
  `backend/`) and for packaging the runnable jar.
- **Toolchain:** requires JDK 21 or newer, and Maven via the bundled wrapper.

## How it's started

The desktop app runs `java -jar hyperdrive-agent.jar` with no arguments and then:
1. Writes one bootstrap line to the agent's stdin: protocol version, a per-launch secret, the
   data directory, log level and request timeout.
2. Reads one handshake line from stdout (`ready`, with the port, or `error`, with a code).
3. Keeps stdin open. `{"type":"shutdown"}`, or stdin closing, stops the agent.

Logs go to stderr. Running the jar by hand without the desktop exits after 10 s
(`bootstrap_timeout`).

## Layout

```
src/main/java/com/hyperdrive/agent/
  AgentMain            entry point (no args, no environment reads)
  Agent                lifecycle: bootstrap → lock → server → handshake → control channel → shutdown
  Bootstrap            strict parse of the stdin launch message (fail closed, secret redacted)
  LocalApiServer       127.0.0.1-only authenticated HTTP API (Host/Origin checks, GET only)
  DataDirectoryLock    exclusive agent.lock in the data directory
  BoundedLineReader    size-limited line reading from stdin
  AgentLog             stderr logging with secret redaction
```

## Still planned (not implemented)

Translating VFS reads into provider range requests in 4 MiB chunks (4,194,304 bytes), the
on-disk chunk cache, metrics, and the VFS-facing interface. Each needs the design decisions
recorded in ADR 0001 first.
