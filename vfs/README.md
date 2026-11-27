# hyperdrive-vfs: read-only WinFsp drive (Batch 8 metadata, Batch 9 content)

C++20, CMake, **Windows x64 only**. Full documentation: [docs/virtual-drive.md](../docs/virtual-drive.md);
design: [ADR 0004](../docs/adr/0004-vfs-transport-and-scoped-metadata-authorization.md) (metadata),
[ADR 0005](../docs/adr/0005-mounted-content-reads.md) (content).

## Build

```powershell
.\build.cmd      # vcvars64 (via vswhere) → CMake/Ninja → build\hyperdrive-vfs.exe → ctest
```

Prerequisites (not installed by the build): Visual Studio 2022 (Build Tools) with the C++ x64
tools and bundled CMake/Ninja; WinFsp with the **Developer** feature
(`C:\Program Files (x86)\WinFsp\{inc,lib}`; override with `-DWINFSP_DIR=...`). The WinFsp DLL is
delay-loaded and located at runtime with `FspLoad`, as in the WinFsp samples.

## Layout

| Path | Contents |
| --- | --- |
| `src/core/text.*` | UTF-8/16 conversion, stable FNV-1a suffixes, case-insensitive ordinal compare |
| `src/core/naming.*` | Windows name sanitization and collision suffixes |
| `src/core/snapshot.*` | Directory snapshots, metadata conversion, marker-based enumeration |
| `src/core/agent_client.*` | Strictly validated calls to the agent's VFS routes (metadata, open/read/close/cancel); fault mapping |
| `src/core/transport.hpp`, `winhttp_transport.cpp` | Narrow agent transport; WinHTTP to 127.0.0.1 only |
| `src/core/tree.*` | Path resolution, 30 s snapshot cache, bounded concurrent fetches |
| `src/core/bootstrap.*` | Strict stdin bootstrap parsing |
| `src/core/status.*` | NTSTATUS decisions (read-only, metadata and content fault mapping) |
| `src/core/content.*` | Content reads: lazy agent read handles, EOF/clamping, ≤ 4 MiB splitting, all-or-nothing, sticky failures, unmount |
| `src/core/fixture.*` | Explicit opt-in fixture data (`--fixture-mount`), labeled as test data |
| `src/fs/filesystem.*` | WinFsp callbacks and volume parameters |
| `src/main.cpp` | Process protocol, mount lifecycle, heartbeat |
| `tests/` | Native unit tests (`hdvfs_tests`), opt-in real-mount checks (`*.mjs`), `unbuffered-read.ps1` helper |

## Process protocol

- Normal mode: **no arguments**; one JSON bootstrap line on stdin (≤ 4 KiB, 10 s):
  `{"protocolVersion":1,"agentPort":<1024-65535>,"capability":"hdv_…","mount":{"driveLetter":"H"}}`.
- stdout: one JSON event per line (`started`, `agent-authenticated`, `volume-created`, `mounted`,
  `unmounted{reason}`, `failed{code}`), never secrets. stderr: diagnostics with status numbers and
  fixed codes only.
- stdin stays open: `unmount` or EOF unmounts and exits.
- Fixture mode (manual testing only): `hyperdrive-vfs.exe --fixture-mount <D-Z>`. Fixture files have
  no content: reads fail with "not supported" (never fabricated bytes).

## Opt-in real-mount checks

```powershell
node tests\fixture-mount-check.mjs build\hyperdrive-vfs.exe                                  # metadata, no agent
node tests\agent-mount-check.mjs ..\agent\target\hyperdrive-agent.jar build\hyperdrive-vfs.exe # agent + stand-in backend
# Content through the agent with the TEST-ONLY fixture provider (see docs/virtual-drive.md):
cd ..\agent; .\mvnw.cmd -q test-compile; .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/fixture-classpath.txt"; cd ..
node vfs\tests\content-mount-check.mjs agent vfs\build\hyperdrive-vfs.exe
```

## Third-party components

| Component | Use | License |
| --- | --- | --- |
| [WinFsp](https://winfsp.dev) 2.1 (2025 release; tested) | Headers/import library at build time; runtime DLL and driver installed separately by the user | GPLv3 with a FLOSS exception |
| [nlohmann/json](https://github.com/nlohmann/json) 3.11.3 | JSON parsing (header-only; fetched by CMake, pinned by SHA-256) | MIT |
| Windows SDK (WinHTTP, Win32) and MSVC runtime | System APIs | Microsoft system libraries |

HyperDrive itself stays GPL-3.0-only.
