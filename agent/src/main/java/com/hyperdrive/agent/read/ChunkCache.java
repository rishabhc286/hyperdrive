package com.hyperdrive.agent.read;

import com.hyperdrive.agent.read.CredentialRegistry.Credential;
import com.hyperdrive.agent.read.CredentialRegistry.Provider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The encrypted 4 MiB chunk cache in front of {@link RangeReader} (Batch 7; see docs/cache.md).
 *
 * <p>Only reads whose content request <b>enforces</b> a revision and whose response <b>confirms</b>
 * it are admitted: today that is Dropbox {@code rev:} reads with a matching {@code Dropbox-API-Result}.
 * Everything else uses the uncached path and gets a typed bypass reason.
 */
public final class ChunkCache {

    public static final int CHUNK_SIZE = 4 * 1024 * 1024;
    static final int MAX_CHUNK_FETCHES = 4;
    private static final long WAIT_LIMIT_MINUTES = 5;

    public enum Outcome { HIT, MISS, PARTIAL, BYPASS, NONE }

    /** The active user's cache context; the key lives only here, in memory. */
    private record Session(UUID userId, String userTag, CacheCrypto crypto) {
    }

    /** One served read: data plus what the cache actually did. */
    public record Served(byte[] data, int length, Outcome outcome, String bypassReason, String notAdmittedReason,
            long providerBytes, long cacheBytes, String revision) {
    }

    private record ChunkData(byte[] data, boolean hit, String notAdmitted) {
    }

    public record Status(boolean enabled, String disabledReason, ChunkStore.Snapshot store, Map<String, Long> counters,
            Map<String, Long> bypasses) {
    }

    private final ChunkStore store;
    private final RangeReader reader;
    private final ExecutorService pool;
    private final Map<String, SharedFetch> inflight = new ConcurrentHashMap<>();
    private volatile Session session;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong bytesRequested = new AtomicLong();
    private final AtomicLong providerBytes = new AtomicLong();
    private final AtomicLong cacheBytes = new AtomicLong();
    private final AtomicLong notAdmitted = new AtomicLong();
    private final Map<String, AtomicLong> bypasses = new ConcurrentHashMap<>();

    public ChunkCache(Path cacheRoot, RangeReader reader, Consumer<String> log) {
        this.store = new ChunkStore(cacheRoot, log);
        this.reader = reader;
        this.pool = Executors.newFixedThreadPool(MAX_CHUNK_FETCHES, r -> {
            Thread t = new Thread(r, "agent-chunk-fetch");
            t.setDaemon(true);
            return t;
        });
        store.scan();
    }

    // --- Session, budget, cleanup --------------------------------------------------------------

    /**
     * Activates a user's key. {@code fresh} means the desktop just created the key, so any older
     * entries of this user are unreadable and are removed first. Other users' leftovers are removed.
     */
    public synchronized ChunkStore.PurgeResult startSession(UUID userId, byte[] key, boolean fresh) {
        String tag = CacheCrypto.userTag(userId);
        CacheCrypto crypto = new CacheCrypto(key);
        ChunkStore.PurgeResult others = store.purgeOtherUsers(tag);
        if (fresh) {
            store.purgeUser(tag, "stale-key");
        }
        session = new Session(userId, tag, crypto);
        return others;
    }

    /** Logout: deletes the user's entries and forgets the key (if it's the active one). */
    public synchronized ChunkStore.PurgeResult endSession(UUID userId) {
        String tag = CacheCrypto.userTag(userId);
        Session s = session;
        if (s != null && s.userId.equals(userId)) {
            session = null;
        }
        return store.purgeUser(tag, "logout");
    }

    /** Clear cache (all of the active user's entries) or one account's entries (disconnect). */
    public ChunkStore.PurgeResult clear(UUID accountIdOrNull) throws ReadException {
        Session s = session;
        if (s == null) {
            throw new ReadException("cache_session_required", 409);
        }
        return accountIdOrNull == null ? store.purgeUser(s.userTag, "clear")
                : store.purgeAccount(s.userTag, s.crypto.accountTag(accountIdOrNull));
    }

    public void setBudget(long bytes) throws ReadException {
        if (!ChunkStore.validBudget(bytes)) {
            throw ReadException.invalid();
        }
        store.setBudget(bytes);
    }

    ChunkStore store() {
        return store;
    }

    public boolean hasSession() {
        return session != null;
    }

    public void shutdown() {
        session = null;
        pool.shutdownNow();
    }

    // --- Eligibility ---------------------------------------------------------------------------

    /** Null when the read may use the cache, else the bypass reason. */
    String bypassReason(Provider provider, String expectedRevision) {
        if (provider == Provider.GOOGLE_DRIVE) {
            return "provider_not_version_bound";
        }
        if (expectedRevision == null) {
            return "version_unknown";
        }
        if (session == null) {
            return "cache_key_unavailable";
        }
        if (store.budget() == 0) {
            return "cache_disabled";
        }
        return null;
    }

    void recordBypass(String reason, long requested, long fetched) {
        bypasses.computeIfAbsent(reason, r -> new AtomicLong()).incrementAndGet();
        bytesRequested.addAndGet(requested);
        providerBytes.addAndGet(fetched);
    }

    // --- Cached reads --------------------------------------------------------------------------

    /**
     * Serves {@code [offset, endExclusive)} (already clamped to {@code size}, at most 4 MiB, so at most
     * two chunks) of an eligible file through the cache. Returns null if the cache session ended after
     * the eligibility check (logout race); the caller then serves the read uncached.
     */
    Served read(Credential credential, String fileId, String revision, long size, long offset, long endExclusive,
            CancelToken waiterCancel) throws ReadException {
        Session s = session;
        if (s == null) {
            return null;
        }
        int length = Math.toIntExact(endExclusive - offset);
        long first = offset / CHUNK_SIZE;
        long last = (endExclusive - 1) / CHUNK_SIZE;
        String accountTag = s.crypto.accountTag(credential.accountId());

        // Phase 1: hits are served now; misses join (or start) one shared fetch per chunk.
        List<Object> parts = new ArrayList<>();
        try {
            for (long index = first; index <= last; index++) {
                long chunkStart = index * CHUNK_SIZE;
                int chunkLength = (int) Math.min(CHUNK_SIZE, size - chunkStart);
                byte[] tag = s.crypto.chunkTag(s.userId, credential.accountId(), credential.provider().name(), fileId,
                        revision, size, CHUNK_SIZE, index);
                String name = accountTag + "-" + CacheCrypto.hex(tag) + ".hdc";
                byte[] hit = tryHit(s, name, tag, chunkLength);
                if (hit != null) {
                    parts.add(new ChunkData(hit, true, null));
                    continue;
                }
                parts.add(join(s, accountTag, name, tag, credential, fileId, revision, size, chunkStart, chunkLength,
                        waiterCancel));
            }
            // Phase 2: wait for this read's fetches.
            List<ChunkData> chunks = new ArrayList<>();
            for (Object p : parts) {
                chunks.add(p instanceof Waiter w ? w.await() : (ChunkData) p);
            }
            return assemble(chunks, first, offset, length, revision);
        } finally {
            for (Object p : parts) {
                if (p instanceof Waiter w) {
                    w.leave();
                }
            }
        }
    }

    private byte[] tryHit(Session s, String name, byte[] tag, int chunkLength) {
        ChunkStore.Entry e = store.pin(s.userTag, name);
        if (e == null) {
            return null;
        }
        byte[] file;
        try {
            file = ChunkStore.read(e);
        } catch (IOException ex) {
            store.unpin(e); // vanished (purged or evicted meanwhile): treat as a miss
            return null;
        }
        try {
            byte[] plain = s.crypto.decrypt(tag, file, chunkLength);
            store.unpin(e);
            ChunkStore.touch(e);
            hits.incrementAndGet();
            return plain;
        } catch (CacheCrypto.CorruptChunkException ex) {
            store.removeCorrupt(e); // never served; one refetch follows as a miss
            return null;
        }
    }

    private Served assemble(List<ChunkData> chunks, long first, long offset, int length, String revision) {
        byte[] out = new byte[length];
        int written = 0;
        long fromCache = 0;
        long fetched = 0;
        int hitCount = 0;
        String notAdmittedReason = null;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData c = chunks.get(i);
            long chunkStart = (first + i) * CHUNK_SIZE;
            int from = (int) Math.max(0, offset - chunkStart);
            int n = Math.min(c.data.length - from, length - written);
            System.arraycopy(c.data, from, out, written, n);
            written += n;
            if (c.hit) {
                hitCount++;
                fromCache += n;
            } else {
                fetched += c.data.length;
                if (c.notAdmitted != null) {
                    notAdmittedReason = c.notAdmitted;
                }
            }
        }
        bytesRequested.addAndGet(length);
        cacheBytes.addAndGet(fromCache);
        Outcome outcome = hitCount == chunks.size() ? Outcome.HIT : hitCount == 0 ? Outcome.MISS : Outcome.PARTIAL;
        return new Served(out, length, outcome, null, notAdmittedReason, fetched, fromCache, revision);
    }

    // --- Shared fetches ------------------------------------------------------------------------

    /** One waiter's interest in a shared fetch; its own cancellation never cancels the others. */
    private final class Waiter {
        final SharedFetch fetch;
        final CompletableFuture<ChunkData> mine = new CompletableFuture<>();
        private boolean left;

        Waiter(SharedFetch fetch, CancelToken cancel) {
            this.fetch = fetch;
            fetch.result.whenComplete((v, e) -> {
                if (e != null) {
                    mine.completeExceptionally(e);
                } else {
                    mine.complete(v);
                }
            });
            cancel.onCancel(() -> mine.completeExceptionally(new ReadException("cancelled", 409)));
        }

        ChunkData await() throws ReadException {
            try {
                return mine.get(WAIT_LIMIT_MINUTES, TimeUnit.MINUTES);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof ReadException re ? re : new ReadException("unavailable", 502);
            } catch (TimeoutException e) {
                throw new ReadException("timeout", 504);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReadException("cancelled", 409);
            }
        }

        synchronized void leave() {
            if (!left) {
                left = true;
                fetch.leave();
            }
        }
    }

    private Waiter join(Session s, String accountTag, String name, byte[] tag, Credential credential, String fileId,
            String revision, long size, long chunkStart, int chunkLength, CancelToken waiterCancel) {
        String key = s.userTag + "/" + name;
        while (true) {
            SharedFetch created = new SharedFetch(key, s, accountTag, name, tag, credential, fileId, revision, size,
                    chunkStart, chunkLength);
            SharedFetch fetch = inflight.putIfAbsent(key, created);
            if (fetch == null) {
                fetch = created;
                fetch.enter();
                pool.execute(fetch);
                return new Waiter(fetch, waiterCancel);
            }
            if (fetch.enter()) {
                return new Waiter(fetch, waiterCancel);
            }
            inflight.remove(key, fetch); // abandoned by all of its waiters; start a fresh one
        }
    }

    private final class SharedFetch implements Runnable {
        final String key;
        final Session session;
        final String accountTag;
        final String name;
        final byte[] tag;
        final Credential credential;
        final String fileId;
        final String revision;
        final long size;
        final long chunkStart;
        final int chunkLength;
        final ChunkStore.Gen gen;
        final CompletableFuture<ChunkData> result = new CompletableFuture<>();
        final CancelToken cancel = new CancelToken();
        private int waiters;
        private boolean abandoned;

        SharedFetch(String key, Session session, String accountTag, String name, byte[] tag, Credential credential,
                String fileId, String revision, long size, long chunkStart, int chunkLength) {
            this.key = key;
            this.session = session;
            this.accountTag = accountTag;
            this.name = name;
            this.tag = tag;
            this.credential = credential;
            this.fileId = fileId;
            this.revision = revision;
            this.size = size;
            this.chunkStart = chunkStart;
            this.chunkLength = chunkLength;
            this.gen = store.generation(session.userTag, accountTag); // snapshot before any network I/O
        }

        synchronized boolean enter() {
            if (abandoned) {
                return false;
            }
            waiters++;
            return true;
        }

        synchronized void leave() {
            waiters--;
            if (waiters == 0 && !result.isDone()) {
                abandoned = true;
                cancel.cancel();
                inflight.remove(key, this);
            }
        }

        @Override
        public void run() {
            credential.inFlight.add(cancel); // disconnect/logout/re-delegation cancel the shared fetch
            try {
                if (cancel.isCancelled()) {
                    throw new ReadException("cancelled", 409);
                }
                ProviderReader.Result r = reader.read(credential, fileId, chunkStart, chunkStart + chunkLength - 1, size,
                        revision, cancel);
                misses.incrementAndGet();
                providerBytes.addAndGet(r.length());
                String reason = admit(r);
                if (reason != null) {
                    notAdmitted.incrementAndGet();
                }
                result.complete(new ChunkData(r.data(), false, reason));
            } catch (ReadException e) {
                result.completeExceptionally(e);
            } catch (RuntimeException e) {
                result.completeExceptionally(new ReadException("unavailable", 502));
            } finally {
                credential.inFlight.remove(cancel);
                inflight.remove(key, this);
            }
        }

        /** Null when published; otherwise why the (valid, served) bytes were not cached. */
        private String admit(ProviderReader.Result r) {
            if (!r.revisionPinned() || r.revision() == null || !r.revision().equals(revision)) {
                return "revision_unconfirmed"; // the response didn't prove the enforced revision
            }
            if (r.length() != chunkLength) {
                return "cache_error";
            }
            if (cancel.isCancelled()) {
                return "cache_invalidated";
            }
            ChunkStore.Reservation reservation = store.reserve(session.userTag, name, CacheCrypto.OVERHEAD + chunkLength);
            if (reservation == null) {
                return store.budget() == 0 ? "cache_disabled" : "cache_full";
            }
            byte[] ciphertext = session.crypto.encrypt(tag, r.data(), chunkLength);
            try {
                return store.publish(reservation, ciphertext, session.userTag, accountTag, name, gen) ? null
                        : "cache_invalidated";
            } catch (IOException e) {
                store.release(reservation);
                return "cache_error";
            }
        }
    }

    // --- Status --------------------------------------------------------------------------------

    public Status status() {
        ChunkStore.Snapshot snap = store.snapshot();
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("hits", hits.get());
        counters.put("misses", misses.get());
        counters.put("notAdmitted", notAdmitted.get());
        counters.put("evictions", snap.evictions());
        counters.put("corruptions", snap.corruptions());
        counters.put("bytesRequested", bytesRequested.get());
        counters.put("providerBytesFetched", providerBytes.get());
        counters.put("bytesServedFromCache", cacheBytes.get());
        Map<String, Long> byReason = new TreeMap<>();
        bypasses.forEach((k, v) -> byReason.put(k, v.get()));
        String disabled = session == null ? "cache_key_unavailable" : snap.budget() == 0 ? "cache_disabled" : null;
        return new Status(disabled == null, disabled, snap, counters, byReason);
    }
}
