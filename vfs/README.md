# HyperDrive virtual filesystem (planned, not implemented)

This directory has no code yet. It records what the virtual filesystem component will do.

The VFS will be a **C++** user-mode filesystem built on **[WinFsp](https://winfsp.dev)**
(GPLv3 with a FLOSS exception). Planned responsibilities:

- Mount a **read-only** virtual drive in Windows File Explorer for the signed-in user.
- Answer Windows filesystem requests (open, directory enumeration, get-info, read, close) by
  asking the local agent (`agent/`). It will not talk to cloud providers directly.
- Report errors accurately to Windows (for example, offline or permission-denied) instead of
  returning incorrect data.
- Unmount cleanly on exit and recover from agent restarts.

Write support is out of scope for the first release.

Build prerequisites (expected): Visual Studio 2022 C++ build tools, the WinFsp SDK
(installed with WinFsp's "Developer" feature) and CMake.
