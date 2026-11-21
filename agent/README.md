# HyperDrive local agent (planned, not implemented)

This directory has no code yet. It records what the local agent will be responsible for.

The agent will be a **Java** process running on the user's Windows machine, in the signed-in
user's context. Planned responsibilities:

- Act as the bridge between the Windows virtual filesystem (`vfs/`) and cloud providers.
- Serve directory listings and file metadata to the VFS.
- Turn byte-range reads from the VFS into provider HTTP Range requests, in fixed
  **4 MiB chunks** (4,194,304 bytes).
- Own the **local on-disk chunk cache**: storage, size limits, eviction, and integrity checks.
  File contents are cached on the local disk only. They are never stored in Redis or PostgreSQL.
- Hold the device's cloud access credentials securely and refresh them.
- Record the performance and cache metrics shown in the desktop app.
- Expose an authenticated local interface to the desktop app and the VFS.

The first release is planned to be **read-only**.
