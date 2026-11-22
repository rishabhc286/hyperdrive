# ADR 0001: Local agent transport and the future content-transfer boundary

- **Status:** accepted for Batch 5. The content-transfer design is **open**.
- **Date:** 2026-11-22

## Context

- **What Batch 5 adds:** a local Java agent that the Electron desktop starts and supervises.
- **What already exists:**
  - The remote Spring Boot backend owns the **encrypted provider refresh tokens** (Google Drive,
    Dropbox; Batches 3–4).
  - Metadata browsing goes through the backend's account-scoped APIs.
- **What comes later:** reading file content in 4 MiB chunks and a native C++ WinFsp filesystem
  client.

## Decision 1: control/status transport is loopback HTTP/JSON

- **Transport:** HTTP/JSON on `127.0.0.1` with an OS-assigned port.
- **Authentication:** a per-launch bearer secret delivered over stdin.
- **Endpoints:** `/local/v1/health` and `/local/v1/status` only.

**Why:**
- The Electron main process already has `fetch`, so it needs no native gRPC or protobuf tooling.
- The security boundary is easy to reason about and to test: loopback only, a secret, `Host`
  and `Origin` checks.
- Status traffic is tiny.

**Alternatives not chosen now:**
- **Named pipes:** good Windows ACL story, but more Node and Java plumbing for a status API.
- **gRPC:** good for typed, high-volume RPC; introduces native tooling into Electron for no
  benefit yet.

There was no existing gRPC foundation in the repository to reuse.

## Decision 2: this API is not the filesystem I/O contract

- The status API must not grow file-content endpoints by accretion.
- The VFS ↔ agent data-plane transport is an **open decision** for the VFS batch. Candidates are
  a named pipe with a binary framing, gRPC over a named pipe or loopback, or shared memory.
- That decision must weigh throughput for 4 MiB chunks, cancellation, back-pressure, Windows
  ACLs and the authentication of the native client.

## Decision 3: no provider credentials in the agent (yet), and no accidental proxying

> **Update (Batch 6):** the open choice below was decided in
> [ADR 0002](0002-provider-access-token-delegation.md): option 1, short-lived access-token
> delegation. The no-proxying rule still holds.

- **No credentials in the agent:** refresh tokens stay on the backend. The agent receives none
  in this batch, and it has no endpoint or configuration for them.
- **No proxying through the backend:** file contents must **not** start flowing through the
  remote backend "temporarily" just because it already holds the tokens. That would turn the
  backend into a bandwidth-heavy content proxy and change the privacy model. Under Google's rules
  it would also expand server-side handling of restricted-scope data.
- **A later batch must choose explicitly** among:
  1. **Short-lived access delegation:** the backend mints and returns provider *access* tokens
     (not refresh tokens) scoped to one connected account. The agent reads directly from the
     provider and refreshes through the backend. Needs: an authenticated agent ↔ backend channel
     bound to the user's device session, token lifetime and caching rules, and revocation
     behavior.
  2. **Device-held credentials:** move or duplicate refresh tokens into the agent under OS
     protection (DPAPI). This needs a migration story and per-device consent.
  3. **Another explicit content-transfer design,** documented with its privacy, cost and
     verification implications.
- **Provider specifics:** Google `drive.readonly` already covers content. Dropbox needs an added
  `files.content.read` grant, which means a reconnect.

## Consequences

- **Agent status means process health only.** The UI and docs say "Ready" means only that the
  agent process is healthy, never that drives, caches or streaming work.
- **Capability flags are truthful.** They were all `false` in Batch 5. Since Batch 6,
  `providerReads` and `rangeReads` are `true`; `chunkCache` and `virtualDrive` stay `false`.
- **Protocol versioning.** `protocolVersion` is checked by both sides. Any incompatible change
  to bootstrap, handshake or status bumps it.
