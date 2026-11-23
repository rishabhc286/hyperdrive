# ADR 0003: Chunk-cache consistency model and key lifecycle

- **Status:** accepted (Batch 7, 2026-11-22)
- **Context docs:** [cache.md](../cache.md), [ADR 0002](0002-provider-access-token-delegation.md)

## Context

Batch 7 adds a persistent local cache of 4 MiB chunks. A cache that mixes bytes from two versions
of a file returns silently corrupt data, which is worse than no cache. The two providers offer
different guarantees:

- **Dropbox** can download a specific revision (`path = "rev:<rev>"`, per `files.stone`), and
  reports the served file's metadata, including `rev`, in `Dropbox-API-Result`.
- **Google Drive** ranged `files.get?alt=media` serves only the current content.
  `revisions.get?alt=media` works only for revisions marked "Keep Forever".

## Decision 1: cache only enforced and confirmed versions

The read contract separates an *observed* revision (seen in metadata), an *enforced* revision
(selected by the content request) and an unpinned read.

- **Admission rule:** a chunk is admitted only when the request **enforced** the revision and the
  response **confirmed** it. Today that's Dropbox `rev:` reads whose `Dropbox-API-Result` `rev`
  matches.
- **Google Drive:** always bypasses the cache (`provider_not_version_bound`). Metadata checks
  before or after a read (`modifiedTime`, size, `md5Checksum`, ETag) are **not** treated as proof:
  they can't make a ranged read, or two chunks, atomic.
- **Missing version identity** → `version_unknown`. A missing or mismatched response `rev` →
  `revision_unconfirmed`: the bytes are served but not stored.
- **Keying:** every chunk is keyed by the enforced revision, so a new revision is a new identity.
  Old chunks are never merged with new ones and are left to LRU or cleanup.

**Rejected for now:**
- **A Google revisions subsystem:** "Keep Forever" pinning would modify user files.
- **Caching Google content under `headRevisionId` + `md5Checksum` with before/after checks:**
  that's not atomic, and an `md5Checksum` covers the whole file only.

## Decision 2: per-user key owned by the desktop, delivered to the agent per session

- **Generation:** the Electron main process generates a random 32-byte key per HyperDrive user and
  wraps it with `safeStorage` (DPAPI). It is the only component that persists the key.
- **Delivery:** the key is sent to the agent over the existing authenticated loopback channel and
  held there in memory only. It is never visible to preload, React or logs.
- **Use:** the agent derives an AES-256-GCM key and an HMAC key with HKDF-Expand. The HMAC key
  makes file names unlinkable to provider IDs.
- **No OS protection → no disk cache.** There's no plaintext fallback.
- **Logout:** deletes the user's chunks *and* the wrapped key. If file deletion fails or the agent
  is unreachable, the leftover ciphertext can't be decrypted with any key the app still holds, and
  it's removed at the next cache session. That backstop applies to logout only: Clear cache and
  Disconnect keep the key (other accounts use it), so they are file deletion only. None of this is
  a claim of physical erasure, and copies of the wrapped key in profile backups are out of scope.

**Alternatives considered:**
- **Agent-generated key under DPAPI:** that would need native access from Java, and it splits key
  ownership away from the process that knows the session.
- **One machine-wide key:** that would make per-user purge rely on deletion alone.
- **Backend-held key:** that would make the cache depend on the network and widen the backend's
  role.

## Decision 3: the file system is the index

- **No SQLite:** there's no extra native dependency.
- **Self-describing entries:** each encrypted `.hdc` file is a complete entry, published by an
  atomic rename. A crash leaves at most `.tmp` files, which the startup scan removes, together
  with malformed entries.
- **Recency:** LRU order is in memory and approximated across restarts by file modification
  times.
- **Trade-offs:** a startup directory scan (bounded to 200,000 files), and recency that's only
  approximate after a restart.

## Decision 4: the cache is not an access-control layer

- **Every read needs live context:** a signed-in session, a connected account and a valid
  delegated credential, whether it hits or misses.
- **No permission check on hits:** a hit doesn't contact the provider, so access revoked at the
  provider in the last few minutes isn't detected on a hit. Disconnect and logout purge the
  account's or user's chunks.
- **Not offline access.**

## Consequences

- **Dropbox:** repeat reads can be served without provider traffic. The cache's benefit
  depends on Dropbox actually returning `Dropbox-API-Result` with `rev` on ranged downloads; that
  is unverified live.
- **Google Drive:** reads see no cache benefit in this release. The UI says so.
- **Read amplification:** an eligible miss fetches whole chunks, up to 8 MiB for one 4 MiB
  unaligned read. Requested and fetched bytes are reported separately.
- **WinFsp:** a WinFsp read path can reuse the same read service, but must still design its own
  transport (ADR 0001).
