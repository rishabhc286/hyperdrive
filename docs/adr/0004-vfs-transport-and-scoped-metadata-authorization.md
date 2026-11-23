# ADR 0004: VFS transport and scoped metadata authorization

- **Status:** accepted (Batch 8, 2026-11-23)
- **Context docs:** [virtual-drive.md](../virtual-drive.md), [ADR 0001](0001-local-agent-transport-and-content-boundary.md),
  [ADR 0002](0002-provider-access-token-delegation.md)

## Context

Batch 8 adds a native WinFsp process (`hyperdrive-vfs.exe`) that shows accounts, folders and file
metadata in Explorer. ADR 0001 left the VFS ↔ agent transport open. The VFS must not hold provider
tokens, HyperDrive session tokens, cache keys or the agent's administration secret, and the agent
must get metadata from an authenticated backend context rather than from the renderer.

## Decision 1: loopback HTTP via WinHTTP, behind a narrow interface (for now)

- The VFS calls the agent's existing authenticated loopback server (`127.0.0.1:<port>`) with
  WinHTTP: fixed host, no proxy, redirects/cookies/auth disabled, bounded response size, explicit
  timeouts. The C++ code depends on an `AgentTransport` interface only.
- No gRPC or named pipes just for metadata: the agent already has a hardened loopback server, and
  WinHTTP is a maintained Windows API (no handwritten HTTP).
- **Not a permanent decision for content.** Batch 9's high-throughput reads may need a different
  transport; this ADR doesn't decide that.

## Decision 2: a separate, ephemeral mount capability

- Electron (launch secret) asks the agent to create a **mount**: a new generation and a 256-bit
  capability (`hdv_…`), returned once and stored as a SHA-256 hash in memory.
- The capability is accepted **only** on the VFS routes (`/local/v1/vfs/session|accounts|list`),
  and the launch secret is **not** accepted there; neither credential opens the other's routes.
- It's delivered to the VFS on **stdin** in a bounded bootstrap line (never arguments,
  environment or files), never logged, never shown to React.
- Revocation: unmount, logout, app quit, a new mount (new generation) or an agent restart (memory
  only). The VFS heartbeat turns a revoked capability into a self-unmount within ~10 s.

## Decision 3: a short-lived, metadata-only backend grant

- New table `metadata_grants` (Flyway V4): hash of an opaque `hdm_…` value, owning device session,
  expiry (15 min). At most one live grant per session; issuing replaces the previous one.
- `POST /api/v1/metadata-grants` (session token, onboarded) issues; `DELETE` revokes. The desktop
  renews every 10 minutes while mounted and hands each grant to the agent.
- A grant authenticates as a `MetadataPrincipal` whose only authority is `METADATA_READ`, which the
  security chain requires for `/api/v1/vfs/**` and which no other route accepts. Session tokens are
  in turn refused on `/api/v1/vfs/**`.
- `/api/v1/vfs/accounts` and `/api/v1/vfs/accounts/{id}/files` reuse the app's ownership-checked
  services and return a reduced projection (no emails, display names, hashes, tokens or provider
  cursors).
- A grant dies with its session (logout, revocation, expiry), and is deleted with it.

**Rejected alternatives:**
- Giving the agent the desktop's access or refresh token: unrestricted and long-lived.
- Letting the renderer or Electron push directory contents to the agent: metadata would not come
  from an authenticated backend context, and Electron would become a metadata proxy.
- Signed (stateless) grants: no immediate revocation without extra state; the database lookup is
  cheap and matches how session tokens already work.

## Decision 4: name mapping and enumeration live in the VFS

- Windows-name mapping (invalid characters, reserved names, trailing dots/spaces, length,
  case-insensitive collisions with stable id suffixes) and WinFsp marker enumeration are pure C++,
  unit-tested without WinFsp.
- The agent assembles provider pages into one bounded listing (10,000 entries, 100 pages, 30 s);
  the VFS keeps per-handle snapshots. Provider pagination never leaks into WinFsp continuation.

## Consequences

- The agent gains an outbound HTTP client to the configured backend origin (validated: https, or
  http to loopback only; origin only) used only for the two metadata routes.
- Metadata can be up to ~30 s stale in Explorer; listings over the limits fail visibly.
- Disconnecting any account unmounts the whole drive in this version.
- Content reads need a new decision on transport and on authorization (whether the mount
  capability may trigger reads), in Batch 9.
