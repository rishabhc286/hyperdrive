# Local chunk cache (Batch 7)

The local agent keeps an **encrypted, bounded** cache of 4 MiB file chunks on this PC. It is a
performance cache for reads that the agent can prove are bound to one exact file version. It is
**not** an offline-access feature: every read, hit or miss, needs a signed-in HyperDrive session,
a connected account and a valid delegated credential.

Related: [local-agent.md](local-agent.md#bounded-read-contract-batch-6) (the read contract),
[ADR 0002](adr/0002-provider-access-token-delegation.md) (credential delegation) and
[ADR 0003](adr/0003-cache-consistency-and-key-lifecycle.md) (why the cache works this way).

## Eligibility: what may be cached

The read contract distinguishes three things that are easy to confuse:

| Term | Meaning |
| --- | --- |
| **Observed revision** | A revision ID seen in listing metadata (Drive `headRevisionId`, Dropbox `rev`). Knowing it doesn't mean the content request returned that version. |
| **Enforced revision** | A revision the **content request itself** selects, so the provider can't return a different version for it. |
| **Unpinned read** | An ordinary "current content" read. The provider may return whatever version is current. |

Content is cached **only when the read is enforced and confirmed**:

| Provider | Request | Cached? | Bypass reason when not |
| --- | --- | --- | --- |
| Dropbox, expected `rev` known | `files/download` with `path = "rev:<rev>"`. The `Dropbox-API-Result` response header must report the **same** `rev` and size. | **Yes** | `revision_unconfirmed` if the header or its `rev` is missing (the bytes are still served, but not admitted) |
| Dropbox, no expected `rev` | Unpinned `id:` read | No | `version_unknown` |
| Google Drive | `files.get?alt=media`: current content only | **No, never in this release** | `provider_not_version_bound` |

**Dropbox facts (from `files.stone` in the dropbox-api-spec repository, read 2026-11-22):**
- `rev` is "a unique identifier for the current revision of a file".
- `ReadPath` accepts `rev:[0-9a-f]{9,}`, and the download argument says "Please specify revision
  in path instead".
- Treating the bytes of one `rev` as immutable is an **inference** from that wording. It isn't a
  sentence the spec states, which is why the response's own `rev` must also match before
  admission.

**Why Google is never cached:**
- Drive can't download a specific revision of a file in general: `revisions.get?alt=media` works
  only for revisions marked "Keep Forever".
- `modifiedTime`, size, ETags and before/after metadata checks don't make a ranged read atomic. A
  file can change between the metadata check and the content request, or between two chunks.
- Google reads therefore always use the uncached Batch 6 path and say so in the diagnostic.
- A Google revisions subsystem is deliberately not built in this batch.

**Reads that never touch the cache:** reads at or beyond EOF, and reads of zero-byte files,
return 0 bytes without a provider call (cache outcome `none`).

## Chunks and read amplification

- **Chunk geometry:** chunk size is **4 MiB = 4,194,304 bytes**, aligned to the file start.
  - `chunkIndex = floor(offset / 4194304)`
  - `chunkStart = chunkIndex × 4194304`
  - `chunkEnd = min(chunkStart + 4194304, fileSize)`
  - The last chunk can be short; a zero-byte file has no chunks.
- **Request bound:** the public request bound stays **1 ≤ length ≤ 4 MiB**. A request is clamped
  at EOF and so intersects **at most two** chunks. Both are fetched concurrently and only the
  requested slice is returned.
- **Amplification:** on an eligible miss, the agent fetches **whole chunks**, not just the
  requested bytes. A 100-byte read fetches up to 4 MiB; a 4 MiB unaligned read fetches up to
  8 MiB. Amplification is therefore bounded at 2 chunks per request.
- **Metrics:** `bytesRequested`, `providerBytesFetched` and `bytesServedFromCache` are tracked
  separately so the amplification is visible.
- **Bypassed reads** keep the exact Batch 6 behavior: only the requested range is fetched.

## Cache key and file names

Every value is hashed with **HMAC-SHA-256** under a key derived from the user's cache master key
(see [Encryption](#encryption)).

- **chunk tag** = HMAC over `"chunk"`, format version `1`, the HyperDrive user ID, the connected
  account ID, the provider, the provider file ID, the **enforced** revision, the expected file
  size, the chunk size (4194304) and the chunk index.
- **account tag** = HMAC over `"account"` and the account ID, truncated to 128 bits. It's used to
  purge one account's entries.
- **user tag** = SHA-256 over `"hyperdrive-cache-user-v1"` and the user ID, truncated to 128 bits.
  It names the per-user directory and isn't keyed, so cleanup works even when the key is gone.

**What the key never contains:** access tokens, credential handles, file names, emails or
provider paths. Different users, different account connections and different revisions always
produce different tags, so entries are never shared or merged across them.

**Layout** (under the agent's data directory, `%APPDATA%\HyperDrive\agent` in development):

```
cache\v1\<user tag: 32 hex>\<account tag: 32 hex>-<chunk tag: 64 hex>.hdc
cache\v1\<user tag>\<same name>.<16 random hex>.tmp        (in-progress, encrypted)
```

There is no separate index: each `.hdc` file is self-describing and the file system is the index
(see [Crash recovery](#crash-recovery)). Nothing in the cache directory contains a plaintext file
name, email or provider ID.

## Encryption

- **File format v1:** magic `HDC\x01` (4 bytes), then a 12-byte random nonce, then AES-256-GCM
  ciphertext with a 16-byte tag. That's 32 bytes of overhead per chunk.
- **Associated data:** magic, chunk tag and plaintext length. A file renamed to another chunk's
  name, truncated or edited fails authentication.
- **Nonces:** fresh from `SecureRandom` for every encryption; chunks are never re-encrypted in
  place.
- **Keys:** the per-user master key is 32 random bytes. Two sub-keys come from it with HKDF-Expand
  (RFC 5869, one block: `HMAC-SHA-256(master, info ‖ 0x01)`), one for encryption
  (`hyperdrive-cache-enc-v1`) and one for tags (`hyperdrive-cache-tag-v1`). The extract step is
  skipped because the master key is already uniformly random (RFC 5869 §3.3).
- **Primitives:** JDK `javax.crypto` (`AES/GCM/NoPadding`, `HmacSHA256`). There is no custom
  cryptography.
- **No plaintext on disk:** plaintext is encrypted in memory before the first byte is written, and
  temp files contain ciphertext only. There's no plaintext fallback: if no key is available, the
  disk cache is off and reads go uncached.
- **Authentication failure** (tampering, wrong or old key, corruption): the entry is deleted, the
  corruption counter increases and the chunk is fetched again once. Unauthenticated plaintext is
  never returned.

### Key ownership and lifecycle

- **Owner:** the **desktop main process**. On first need it generates a random 32-byte key per
  HyperDrive user, wraps it with Electron `safeStorage` (Windows DPAPI, the same mechanism as the
  session token) and stores it at `%APPDATA%\HyperDrive\cache-keys\<hash of user id>.json`.
- **Loading:** when the user is signed in, the main process unwraps the key and sends it to the
  agent over the authenticated loopback channel (`POST /local/v1/cache/session`). The agent keeps
  it in memory only. The key never reaches preload, React or logs.
- **If `safeStorage` is unavailable** (or Linux falls back to `basic_text`), no key is created and
  the disk cache is **disabled**. Reads keep working uncached, and Settings says why.
- **If the key file is corrupt or can't be unwrapped**, it's replaced by a new key and the agent is
  told the key is fresh, so it deletes that user's old (now unreadable) entries before use.
- **Logout:** the agent deletes that user's cache files and drops the key from memory; the desktop
  then deletes the wrapped key file.
- **Relogin:** a new key is generated. Nothing from a previous session can be decrypted.
- **Agent restart:** the key is lost from agent memory and resent on the next read or status
  request.
- **Memory hygiene:** Java and JavaScript can't guarantee key zeroization, and HyperDrive doesn't
  claim it.

## Disk accounting and budget

- **Default budget:** **5 GiB = 5,368,709,120 bytes**. Allowed values are `0` (disk cache off) or
  256 MiB–1 TiB. It's set in Settings, validated in the main process and the agent, persisted by
  the desktop (`cache-settings.json`) and sent to the agent at each cache session.
- **What counts against the budget:** encrypted `.hdc` file sizes (ciphertext plus 32 bytes each)
  and the reservations for in-progress temporary files.
- **What doesn't count:**
  - The directory entries themselves and file-system allocation overhead (cluster rounding).
  - Unknown files the agent didn't create. They're ignored and never deleted.
- **Status reports actual encrypted bytes**, never logical plaintext bytes. It isn't labeled
  "space saved".
- **Admission:** before writing a temp file, the agent **reserves** its exact size, evicting
  least-recently-used unpinned entries if needed. If the space still isn't available, the chunk
  is **not admitted** (bypass `cache_full`), and the read is still served from the bytes it already
  fetched. A valid read never fails because the cache is full.
- **Temporary space** is bounded by the fetch pool (4 concurrent chunk fetches, so at most 4 temp
  files of ≤ 4 MiB + 32 B each).

## LRU eviction and concurrency

- **Recency:** an in-memory access-ordered list. A hit moves the entry to the front and updates the
  file's modified time, so recency survives restarts approximately.
- **Eviction:** removes the least-recently-used **unpinned** entries.
  - **Pinned entries** (being read or published) are skipped.
  - **Lowering the budget** evicts immediately. Any overage from pinned entries is reported and
    removed as soon as they're unpinned, so the budget isn't exceeded indefinitely.
- **Fetch deduplication:** concurrent misses for the same chunk share **one** provider fetch.
  - Each waiter has its own cancellation. One waiter giving up doesn't cancel the others; the
    shared fetch is cancelled only when every waiter has gone.
  - Clearing the account's credential (disconnect, logout, re-delegation) cancels the fetch
    itself.
- **Bounds:** reads stay limited to 8 concurrent (4 per account). Chunk fetches are limited to 4
  concurrent, so in-flight chunk memory is bounded (about 16 chunk buffers in the worst case).
- **Locking:** the cache lock is **never held during network or disk-content I/O**. It covers only
  index updates, reservation, and the final rename of a written temp file.
- **Generations:** clear-cache, disconnect, logout and a new session each bump a generation
  counter. A fetch that started under an older generation **can't publish**: its temp file is
  deleted. This prevents late resurrection.
- **No revision mixing:** every chunk of a read is fetched with the same enforced `rev:` and keyed
  by it.

## Crash recovery

- **Publication:** encrypted bytes are written to a `.tmp` file, flushed, then atomically renamed
  to `.hdc`. A crash can leave only a `.tmp` file, never a partial `.hdc`.
- **On start** the agent scans `cache\v1\` (bounded to 200,000 files):
  - It deletes `*.tmp` files.
  - It deletes `.hdc` files with a wrong header, an impossible size or a malformed name.
  - It ignores unknown files and directories.
  - It never follows links or deletes anything outside `cache\v1\`.
- **What the scan can't check:** without the key it can't authenticate content. Corruption found
  later at read time is handled as described under [Encryption](#encryption).
- **Other formats:** a directory for another format version (for example `cache\v2`) is left alone
  and not counted.

## Cleanup policy

| Event | What happens |
| --- | --- |
| **Logout / session expiry** | Reads are cancelled and delegated credentials cleared (Batch 6). The agent deletes **that user's** cache directory and drops the key, and the desktop deletes the wrapped key file. If the agent can't be reached, it's restarted; the files stay on disk, unreadable without the deleted key, and are deleted at the next cache session start. |
| **Disconnect account** | That account's credential is cleared and its reads cancelled. Its generation is bumped and its entries deleted; other accounts are untouched. If the agent can't confirm, the purge is retried at the next cache session. |
| **Clear cache** (Settings, after confirmation) | All of the current user's entries are deleted and the generation is bumped. Reads already being served finish from memory, but nothing they fetched is published. Credentials and remote files are untouched. |
| **New session for another user** | Leftover directories of other users (only possible after a failed logout cleanup) are deleted. |

**Deletion isn't physical erasure.** The agent deletes encrypted files, but SSD wear levelling,
backups and file-system journals may keep old blocks. Data protection rests on the encryption and
the deletion of the key, not on overwriting.

**The cache isn't an access check.** A hit doesn't contact the provider. The desktop fetches fresh
metadata through the backend before every diagnostic read, which checks the connection, and a hit
still needs a valid delegated credential. But a cache hit does not prove the user still has
permission at Google or Dropbox right now. A file whose sharing was revoked a moment ago can still
be served from a cached chunk until the account is disconnected or the user logs out.

## Metrics

| Metric | Scope | Meaning |
| --- | --- | --- |
| `budgetBytes`, `enabled`, `disabledReason` | Current setting | Configured budget, and why caching is off |
| `encryptedBytesOnDisk`, `entryCount` | Persistent (recomputed by the scan) | Sum of `.hdc` file sizes, number of `.hdc` files |
| `tempBytes`, `overBudgetBytes` | Current | Reserved temp-file bytes; usage above budget held by pinned entries |
| `hits`, `misses` | Agent process lifetime | Chunks served from cache, chunks fetched from the provider for an eligible read |
| `bypasses` (per reason) | Agent process lifetime | Reads not using the cache, by reason |
| `evictions`, `corruptions` | Agent process lifetime | Entries removed for space; entries rejected by authentication or format checks |
| `bytesRequested`, `providerBytesFetched`, `bytesServedFromCache` | Agent process lifetime | Requested slice bytes; bytes received from providers (whole chunks on eligible misses); requested bytes answered from cached chunks |
| `lastCleanup` | Agent process lifetime | Last purge: scope, files removed, files that couldn't be deleted |

Counters reset when the agent restarts. There is no "savings" metric, because there's no measured
baseline to compare against.

## Manual verification (requires a real Dropbox app and Google client)

Not executed yet; every test so far uses local fake providers.

1. **Dropbox miss then hit:** reconnect Dropbox with `files.content.read`. In Files, run **Verify
   file read** on a file larger than 8 MiB at offset 0, length 4,194,304.
   - Expect cache **miss** and provider bytes 4,194,304.
   - Run again: expect **hit**, provider bytes 0, the same SHA-256.
2. **Cross-boundary read:** offset 4,194,000, length 1,000. Expect up to two chunks (a partial hit
   or miss) and the correct digest.
3. **Changed file:** edit the file in Dropbox and refresh the folder. The next read is a miss under
   the new `rev`; the old entries stay until evicted or cleared.
4. **Google bypass:** verify a Drive file. Expect "bypass: provider not version-bound" and provider
   bytes equal to the requested length.
5. **Budget:** set the budget to 256 MiB in Settings and read enough distinct chunks to exceed it.
   Encrypted bytes on disk stay ≤ 256 MiB and evictions increase.
6. **Clear cache:** confirm the dialog. Entries go to 0 and the next read is a miss.
7. **Disconnect and logout:** disconnect the Dropbox account and confirm its entries are gone.
   Sign out and confirm `%APPDATA%\HyperDrive\agent\cache\v1\` has no directory for the user, and
   `cache-keys\` no file.
8. **On-disk check:** open any `.hdc` file in a hex viewer. It starts with `HDC\x01` followed by
   random-looking bytes, and no plaintext from the file appears.
