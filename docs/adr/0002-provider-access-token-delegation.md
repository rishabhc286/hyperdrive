# ADR 0002: Provider access-token delegation to the local agent

- **Status:** accepted (Batch 6, 2026-11-22)
- **Supersedes:** Decision 3 of [ADR 0001](0001-local-agent-transport-and-content-boundary.md)
  (the "a later batch must choose" part). The rest of ADR 0001 stands.

## Context

The local agent must read byte ranges of the user's files directly from Google Drive and Dropbox.
The backend holds each connected account's encrypted refresh token and can mint provider access
tokens. ADR 0001 ruled out proxying file contents through the backend and left three options
open: short-lived access delegation, device-held refresh tokens, or another transfer design.

## Decision

**Option 1: the backend delegates provider *access* tokens, never refresh tokens.**

```
Desktop main ──POST /api/v1/accounts/{id}/read-credentials (HyperDrive bearer, HTTPS)──▶ Backend
                ◀── { provider, accessToken, accessTokenExpiresAt, rootNamespaceId } ──
Desktop main ──POST /local/v1/credentials (loopback, per-launch agent secret)──────────▶ Agent
                ◀── { handle (opaque), retentionExpiresAt, providerExpiresAt } ──
Desktop main ──POST /local/v1/reads { handle, fileId, offset, length, expectedSize, … }─▶ Agent
Agent ──GET/POST fixed provider endpoint, Range: bytes=a-b ───────────────────────────▶ Provider
Agent ◀── 206 + exactly the requested bytes ─────────────────────────────────────────── Provider
```

- File bytes travel **provider → agent** only. The backend never sees content.
- **Backend checks before any provider activity:**
  - The HyperDrive access token, and completed onboarding.
  - Account ownership (a 404 hides another user's account).
  - Connection status, and that the stored grant includes the content scope: Google
    `drive.readonly`, or Dropbox `files.content.read`.
- **What the backend returns:** an access token valid for at least 5 more minutes, refreshing it
  first if needed under the existing row lock. The refresh token never leaves the backend.
- **Response and audit:** the response is `Cache-Control: no-store` and is rate-limited (30 per
  minute per user by default). An audit line records user, session, account, provider and expiry,
  never the token.
- **Desktop main process:** passes the token to the agent at once and keeps only the opaque
  handle. Tokens never reach preload or React and are never written to disk.
- **Agent:** keeps credentials in memory only.
  - At most 32 credentials, one per account. Re-delegation replaces the previous one.
  - Each one is retained until the earlier of the provider expiry (minus a 15 s margin) or
    20 minutes.

## What this does and does not protect (honest boundaries)

- **The token is not file-scoped.** A delegated access token authorizes everything the account's
  grant allows: all files the user can read (`drive.readonly`, `files.content.read`). HyperDrive
  can't narrow an existing Google or Dropbox token to one file or one range. "Bounded reads" is a
  property of the agent's API, not a cryptographic restriction on the token.
- **Local expiry is not provider expiry.** Dropping a credential from the agent's memory does not
  revoke it at the provider. The provider honors the token until its own expiry (typically about
  1 hour for Google, and about 4 hours for Dropbox short-lived tokens, whatever the provider
  states).
- **Sign-out and disconnect can't guarantee immediate revocation.**
  - On sign-out or session expiry, the desktop clears every credential in the agent. On
    disconnect, it clears that account's.
  - If the clear can't be confirmed, the desktop restarts the agent, so the process holding the
    token ends.
  - A token already copied out of memory stays valid at the provider until it expires. Disconnect
    also deletes the backend's stored refresh token, but it doesn't call a provider revoke
    endpoint.
- **Memory hygiene is best effort.** Java strings and Node buffers can't be reliably zeroized.
  HyperDrive doesn't claim memory zeroization. The diagnostic read zeroes its byte buffer after
  hashing, and that's all it claims.
- **Same-user local processes.** As ADR 0001 states, a process running as the same Windows user
  can read the agent's memory or the Electron process. Delegated tokens add value to such an
  attacker: they live for up to the provider lifetime and are account-wide.

## Alternatives considered

- **Device-held refresh tokens (DPAPI):** rejected for now. It would put long-lived,
  non-expiring credentials on every device, need migration and per-device consent, and make
  backend disconnect ineffective until the device next checks in.
- **Backend content proxy:** rejected (ADR 0001). It means bandwidth cost, a changed privacy
  model, and expanded server-side handling of restricted-scope data under Google's rules.
- **Provider pre-signed links:** for example Dropbox `files/get_temporary_link`, valid about
  4 hours. They're file-scoped, which is better, but they are bearer URLs. Putting them in URLs
  conflicts with "no tokens in URLs/logs", and Google has no equivalent for `alt=media`. This
  stays a candidate for a future Dropbox-specific narrowing.

## Consequences

- **Content scope for Dropbox:** new Dropbox connections request `files.content.read`. Connections
  made before Batch 6 report `contentReadGranted=false` and need a reconnect before reads work.
  Browsing is unaffected.
- **Re-delegation:** when the provider rejects the token, the agent reports `credential_rejected`.
  The desktop re-delegates **once** per read, never in a loop, and renewals are single-flight per
  account.
- **Data-plane contract:** the application read contract (`/local/v1/reads`) is HTTP on loopback
  for now. It is not the final VFS transport; that decision stays open for the WinFsp batch.
- **Cache safety:** no read is labeled safe for caching yet.
  - Google ranged downloads aren't revision-pinned; only "Keep Forever" revisions can be fetched by
    revision.
  - Dropbox reads with an expected revision use `rev:` paths and are pinned.
  - The chunk-cache batch must define its key and invalidation rules on top of this.
