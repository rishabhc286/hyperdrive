package com.hyperdrive.agent.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyperdrive.agent.read.CredentialRegistry.Credential;
import com.hyperdrive.agent.read.CredentialRegistry.Provider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The encrypted chunk cache against a deterministic fake provider (no network). */
class ChunkCacheTest {

    static final int C = ChunkCache.CHUNK_SIZE;
    static final String REV1 = "0123456789a";
    static final String REV2 = "0123456789b";
    static final byte[] MARKER = "HYPERDRIVE-PLAINTEXT-MARKER-".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path temp;

    /** Fake provider: content per (fileId, rev); optional gate; records every range fetched. */
    static final class FakeSource implements RangeReader {
        final Map<String, byte[]> content = new ConcurrentHashMap<>();
        final List<long[]> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger cancelledFetches = new AtomicInteger();
        volatile boolean confirmRevision = true;
        volatile CountDownLatch gate;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile ReadException failWith;

        @Override
        public ProviderReader.Result read(Credential credential, String fileId, long start, long endInclusive,
                long expectedSize, String expectedRevision, CancelToken cancel) throws ReadException {
            calls.add(new long[] {start, endInclusive});
            entered.countDown();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    while (!g.await(5, TimeUnit.MILLISECONDS)) {
                        if (cancel.isCancelled()) {
                            cancelledFetches.incrementAndGet();
                            throw new ReadException("cancelled", 409);
                        }
                    }
                } catch (InterruptedException e) {
                    throw new ReadException("cancelled", 409);
                }
            }
            if (cancel.isCancelled()) {
                cancelledFetches.incrementAndGet();
                throw new ReadException("cancelled", 409);
            }
            if (failWith != null) {
                throw failWith;
            }
            byte[] file = content.get(fileId + "@" + expectedRevision);
            if (file == null) {
                throw new ReadException("not_found", 404);
            }
            byte[] slice = Arrays.copyOfRange(file, (int) start, (int) endInclusive + 1);
            boolean pinned = credential.provider() == Provider.DROPBOX && expectedRevision != null;
            return new ProviderReader.Result(slice, slice.length, pinned, confirmRevision ? expectedRevision : null);
        }
    }

    private FakeSource source;
    private ChunkCache cache;
    private final UUID user = UUID.randomUUID();
    private final byte[] key = randomKey();
    private Credential box;

    static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    static Credential credential(Provider provider, UUID account) {
        return new Credential("hdh_test", provider, account, "sl.test-token-0123456789", Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(1200), provider == Provider.DROPBOX ? "777" : null);
    }

    static byte[] file(int size, long seed) {
        byte[] b = new byte[size];
        new java.util.Random(seed).nextBytes(b);
        for (int i = 0; i + MARKER.length <= size; i += 997) {
            System.arraycopy(MARKER, 0, b, i, MARKER.length);
        }
        return b;
    }

    @BeforeEach
    void setUp() {
        source = new FakeSource();
        cache = new ChunkCache(temp.resolve("cache"), source, m -> { });
        cache.startSession(user, key, false);
        box = credential(Provider.DROPBOX, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    private ChunkCache.Served read(Credential cred, String fileId, String rev, long size, long offset, long end)
            throws ReadException {
        return cache.read(cred, fileId, rev, size, offset, end, new CancelToken());
    }

    private List<Path> cacheFiles() throws Exception {
        Path root = temp.resolve("cache");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    // --- Chunk model -------------------------------------------------------------------------

    @Test
    void crossBoundaryReadFetchesBothAlignedChunksOnceThenHits() throws Exception {
        byte[] f = file(2 * C + 5, 1);
        source.content.put("id:a@" + REV1, f);
        ChunkCache.Served miss = read(box, "id:a", REV1, f.length, C - 100, C + 100);

        assertThat(miss.outcome()).isEqualTo(ChunkCache.Outcome.MISS);
        assertThat(miss.data()).isEqualTo(Arrays.copyOfRange(f, C - 100, C + 100));
        assertThat(source.calls).extracting(c -> c[0] + "-" + c[1])
                .containsExactlyInAnyOrder("0-" + (C - 1), C + "-" + (2L * C - 1));
        assertThat(miss.providerBytes()).isEqualTo(2L * C); // amplification is reported, not hidden

        ChunkCache.Served hit = read(box, "id:a", REV1, f.length, C - 100, C + 100);
        assertThat(hit.outcome()).isEqualTo(ChunkCache.Outcome.HIT);
        assertThat(hit.data()).isEqualTo(miss.data());
        assertThat(hit.providerBytes()).isZero();
        assertThat(hit.cacheBytes()).isEqualTo(200);
        assertThat(source.calls).hasSize(2); // no provider content request on a hit

        ChunkCache.Status st = cache.status();
        assertThat(st.counters()).containsEntry("hits", 2L).containsEntry("misses", 2L)
                .containsEntry("bytesRequested", 400L).containsEntry("providerBytesFetched", 2L * C)
                .containsEntry("bytesServedFromCache", 200L);
        assertThat(st.store().entries()).isEqualTo(2);
        assertThat(st.store().encryptedBytes()).isEqualTo(2L * (C + CacheCrypto.OVERHEAD));
    }

    @Test
    void finalPartialChunkIsStoredAtItsRealLengthAndPartialHitsAreReported() throws Exception {
        byte[] f = file(C + 10, 2);
        source.content.put("id:b@" + REV1, f);
        ChunkCache.Served tail = read(box, "id:b", REV1, f.length, C, C + 10);
        assertThat(tail.data()).isEqualTo(Arrays.copyOfRange(f, C, C + 10));
        assertThat(source.calls.get(0)).containsExactly(C, C + 9L);
        assertThat(cache.status().store().encryptedBytes()).isEqualTo(10 + CacheCrypto.OVERHEAD);

        ChunkCache.Served across = read(box, "id:b", REV1, f.length, C - 5, C + 10);
        assertThat(across.outcome()).isEqualTo(ChunkCache.Outcome.PARTIAL);
        assertThat(across.data()).isEqualTo(Arrays.copyOfRange(f, C - 5, C + 10));
        assertThat(across.cacheBytes()).isEqualTo(10);
    }

    // --- Eligibility and isolation -----------------------------------------------------------

    @Test
    void onlyEnforcedDropboxRevisionsAreEligible() {
        assertThat(cache.bypassReason(Provider.GOOGLE_DRIVE, "headRev1")).isEqualTo("provider_not_version_bound");
        assertThat(cache.bypassReason(Provider.DROPBOX, null)).isEqualTo("version_unknown");
        assertThat(cache.bypassReason(Provider.DROPBOX, REV1)).isNull();
        cache.store().setBudgetForTest(0);
        assertThat(cache.bypassReason(Provider.DROPBOX, REV1)).isEqualTo("cache_disabled");
        ChunkCache noKey = new ChunkCache(temp.resolve("other"), source, m -> { });
        assertThat(noKey.bypassReason(Provider.DROPBOX, REV1)).isEqualTo("cache_key_unavailable");
        noKey.shutdown();
    }

    @Test
    void anUnconfirmedRevisionIsServedButNeverAdmitted() throws Exception {
        byte[] f = file(1000, 3);
        source.content.put("id:c@" + REV1, f);
        source.confirmRevision = false;
        ChunkCache.Served s = read(box, "id:c", REV1, f.length, 0, 1000);
        assertThat(s.data()).isEqualTo(f);
        assertThat(s.notAdmittedReason()).isEqualTo("revision_unconfirmed");
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void fetchEvidenceIsLoggedWithoutTokensIdsOrContent() throws Exception {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        ChunkCache logged = new ChunkCache(temp.resolve("logged"), source, lines::add);
        logged.startSession(user, key, false);
        try {
            byte[] f = file(1000, 30);
            source.content.put("id:secretName@" + REV1, f);
            logged.read(box, "id:secretName", REV1, f.length, 0, 1000, new CancelToken());
            source.confirmRevision = false;
            source.content.put("id:other@" + REV1, f);
            logged.read(box, "id:other", REV1, f.length, 0, 1000, new CancelToken());
        } finally {
            logged.shutdown();
        }
        List<String> evidence = lines.stream().filter(l -> l.startsWith("Cache chunk fetch:")).toList();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0)).contains("provider=DROPBOX", "range=0-999", "validatedBytes=1000", "responseRev=match",
                "admission=stored");
        assertThat(evidence.get(1)).contains("responseRev=absent", "admission=revision_unconfirmed");
        assertThat(String.join(" | ", evidence)).doesNotContain(box.accessToken, box.handle, "secretName", REV1,
                box.accountId().toString());
    }

    @Test
    void revisionsAccountsAndUsersNeverShareEntries() throws Exception {
        byte[] v1 = file(1000, 4);
        byte[] v2 = file(1000, 5);
        source.content.put("id:d@" + REV1, v1);
        source.content.put("id:d@" + REV2, v2);
        assertThat(read(box, "id:d", REV1, 1000, 0, 1000).data()).isEqualTo(v1);
        assertThat(read(box, "id:d", REV2, 1000, 0, 1000).data()).isEqualTo(v2);
        assertThat(read(box, "id:d", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT);
        assertThat(read(box, "id:d", REV2, 1000, 0, 1000).data()).isEqualTo(v2);

        Credential otherAccount = credential(Provider.DROPBOX, UUID.randomUUID());
        assertThat(read(otherAccount, "id:d", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.MISS);

        UUID otherUser = UUID.randomUUID();
        cache.startSession(otherUser, randomKey(), true); // a different user: the first user's files are removed
        assertThat(cache.status().store().entries()).isZero();
        assertThat(read(box, "id:d", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.MISS);
    }

    // --- Concurrency ---------------------------------------------------------------------------

    @Test
    void concurrentMissesForOneChunkShareASingleFetch() throws Exception {
        byte[] f = file(1000, 6);
        source.content.put("id:e@" + REV1, f);
        source.gate = new CountDownLatch(1);
        List<CompletableFuture<byte[]>> reads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reads.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return read(box, "id:e", REV1, 1000, 0, 1000).data();
                } catch (ReadException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        source.gate.countDown();
        for (CompletableFuture<byte[]> r : reads) {
            assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo(f);
        }
        assertThat(source.calls).hasSize(1);
    }

    @Test
    void oneWaiterCancellingDoesNotCancelTheOthers() throws Exception {
        byte[] f = file(1000, 7);
        source.content.put("id:f@" + REV1, f);
        source.gate = new CountDownLatch(1);
        CancelToken first = new CancelToken();
        CancelToken second = new CancelToken();
        CompletableFuture<Object> a = CompletableFuture.supplyAsync(() -> attempt(first, "id:f"));
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Object> b = CompletableFuture.supplyAsync(() -> attempt(second, "id:f"));
        Thread.sleep(100);
        first.cancel();
        assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo("cancelled");
        source.gate.countDown();
        assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo(f);
        assertThat(source.cancelledFetches.get()).isZero();
        assertThat(source.calls).hasSize(1);
    }

    @Test
    void theSharedFetchIsCancelledWhenEveryWaiterLeaves() throws Exception {
        source.content.put("id:g@" + REV1, file(1000, 8));
        source.gate = new CountDownLatch(1);
        CancelToken only = new CancelToken();
        CompletableFuture<Object> a = CompletableFuture.supplyAsync(() -> attempt(only, "id:g"));
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        only.cancel();
        assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo("cancelled");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (source.cancelledFetches.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(source.cancelledFetches.get()).isEqualTo(1);
        assertThat(cacheFiles()).isEmpty();
    }

    private Object attempt(CancelToken token, String id) {
        try {
            return cache.read(box, id, REV1, 1000, 0, 1000, token).data();
        } catch (ReadException e) {
            return e.code();
        }
    }

    // --- Budget and LRU ------------------------------------------------------------------------

    @Test
    void leastRecentlyUsedEntriesAreEvictedFirst() throws Exception {
        int entry = 1000 + CacheCrypto.OVERHEAD;
        cache.store().setBudgetForTest(3L * entry);
        for (String id : List.of("id:h1", "id:h2", "id:h3", "id:h4")) {
            source.content.put(id + "@" + REV1, file(1000, id.hashCode()));
        }
        read(box, "id:h1", REV1, 1000, 0, 1000);
        read(box, "id:h2", REV1, 1000, 0, 1000);
        read(box, "id:h3", REV1, 1000, 0, 1000);
        assertThat(read(box, "id:h1", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT); // h1 now MRU
        read(box, "id:h4", REV1, 1000, 0, 1000); // evicts h2, the LRU

        assertThat(cache.status().counters()).containsEntry("evictions", 1L);
        assertThat(read(box, "id:h1", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT);
        assertThat(read(box, "id:h3", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT);
        assertThat(read(box, "id:h2", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.MISS);
        assertThat(cache.status().store().encryptedBytes()).isLessThanOrEqualTo(3L * entry);
    }

    @Test
    void pinnedEntriesSurviveABudgetCutAndAreEvictedOnceReleased() throws Exception {
        source.content.put("id:i@" + REV1, file(1000, 9));
        read(box, "id:i", REV1, 1000, 0, 1000);
        ChunkStore store = cache.store();
        ChunkStore.Entry e = store.pin(CacheCrypto.userTag(user), cacheFiles().get(0).getFileName().toString());
        assertThat(e).isNotNull();
        store.setBudgetForTest(10);
        assertThat(store.snapshot().entries()).isEqualTo(1);
        assertThat(store.snapshot().overBudget()).isPositive();
        store.unpin(e);
        assertThat(store.snapshot().entries()).isZero();
        assertThat(store.snapshot().overBudget()).isZero();
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void aFullCacheServesTheReadWithoutAdmittingIt() throws Exception {
        cache.store().setBudgetForTest(500); // smaller than one entry
        byte[] f = file(1000, 10);
        source.content.put("id:j@" + REV1, f);
        ChunkCache.Served s = read(box, "id:j", REV1, 1000, 0, 1000);
        assertThat(s.data()).isEqualTo(f);
        assertThat(s.notAdmittedReason()).isEqualTo("cache_full");
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void reservationsCountAgainstTheBudgetSoTempSpaceIsBounded() {
        ChunkStore store = cache.store();
        store.setBudgetForTest(2500);
        ChunkStore.Reservation one = store.reserve(CacheCrypto.userTag(user), "x", 1200);
        ChunkStore.Reservation two = store.reserve(CacheCrypto.userTag(user), "y", 1200);
        assertThat(one).isNotNull();
        assertThat(two).isNotNull();
        assertThat(store.reserve(CacheCrypto.userTag(user), "z", 1200)).isNull();
        assertThat(store.snapshot().tempBytes()).isEqualTo(2400);
        store.release(one);
        store.release(two);
        assertThat(store.snapshot().tempBytes()).isZero();
    }

    // --- Encryption and corruption -------------------------------------------------------------

    @Test
    void onlyCiphertextReachesTheDisk() throws Exception {
        byte[] f = file(C, 11);
        source.content.put("id:k@" + REV1, f);
        read(box, "id:k", REV1, f.length, 0, f.length);
        List<Path> files = cacheFiles();
        assertThat(files).hasSize(1);
        byte[] onDisk = Files.readAllBytes(files.get(0));
        assertThat(Arrays.copyOf(onDisk, 4)).isEqualTo(CacheCrypto.MAGIC);
        assertThat(onDisk).hasSize(C + CacheCrypto.OVERHEAD);
        assertThat(indexOf(onDisk, MARKER)).isEqualTo(-1);
        String name = files.get(0).getFileName().toString();
        assertThat(name).matches("[0-9a-f]{32}-[0-9a-f]{64}\\.hdc").doesNotContain("id:k").doesNotContain(REV1);
        assertThat(files.get(0).getParent().getFileName().toString()).isEqualTo(CacheCrypto.userTag(user))
                .doesNotContain(user.toString());
    }

    @Test
    void noKeyMeansNoDiskCacheAtAll() throws Exception {
        ChunkCache noKey = new ChunkCache(temp.resolve("nokey"), source, m -> { });
        assertThat(noKey.bypassReason(Provider.DROPBOX, REV1)).isEqualTo("cache_key_unavailable");
        assertThatThrownBy(() -> noKey.clear(null)).isInstanceOf(ReadException.class);
        assertThat(Files.exists(temp.resolve("nokey"))).isFalse();
        noKey.shutdown();
    }

    @Test
    void everyEncryptionUsesAFreshNonceAndIdentityIsAuthenticated() throws Exception {
        CacheCrypto crypto = new CacheCrypto(key);
        byte[] tag = crypto.chunkTag(user, box.accountId(), "DROPBOX", "id:x", REV1, 10, C, 0);
        byte[] otherTag = crypto.chunkTag(user, box.accountId(), "DROPBOX", "id:x", REV1, 10, C, 1);
        byte[] plain = "0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] a = crypto.encrypt(tag, plain, plain.length);
        byte[] b = crypto.encrypt(tag, plain, plain.length);
        assertThat(Arrays.copyOfRange(a, 4, 16)).isNotEqualTo(Arrays.copyOfRange(b, 4, 16));
        assertThat(a).isNotEqualTo(b);
        assertThat(crypto.decrypt(tag, a, plain.length)).isEqualTo(plain);
        // Moved to another chunk's name, or the wrong key: authentication fails.
        assertThatThrownBy(() -> crypto.decrypt(otherTag, a, plain.length)).isInstanceOf(CacheCrypto.CorruptChunkException.class);
        assertThatThrownBy(() -> new CacheCrypto(randomKey()).decrypt(tag, a, plain.length))
                .isInstanceOf(CacheCrypto.CorruptChunkException.class);
        assertThatThrownBy(() -> crypto.decrypt(tag, a, plain.length + 1)).isInstanceOf(CacheCrypto.CorruptChunkException.class);
        assertThat(otherTag).isNotEqualTo(tag);
    }

    @Test
    void tamperedChunksAreRejectedRemovedAndRefetchedOnce() throws Exception {
        byte[] f = file(1000, 12);
        source.content.put("id:l@" + REV1, f);
        read(box, "id:l", REV1, 1000, 0, 1000);
        Path chunk = cacheFiles().get(0);
        byte[] bytes = Files.readAllBytes(chunk);
        bytes[100] ^= 0x01;
        Files.write(chunk, bytes);

        ChunkCache.Served again = read(box, "id:l", REV1, 1000, 0, 1000);
        assertThat(again.data()).isEqualTo(f); // the provider's bytes, never the tampered ones
        assertThat(again.outcome()).isEqualTo(ChunkCache.Outcome.MISS);
        assertThat(cache.status().counters()).containsEntry("corruptions", 1L);
        assertThat(source.calls).hasSize(2);
        assertThat(read(box, "id:l", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT);
    }

    @Test
    void aWrongKeyTurnsEntriesIntoMissesNeverIntoGarbage() throws Exception {
        byte[] f = file(1000, 13);
        source.content.put("id:m@" + REV1, f);
        read(box, "id:m", REV1, 1000, 0, 1000);
        cache.startSession(user, randomKey(), false); // e.g. a restored, different key
        ChunkCache.Served s = read(box, "id:m", REV1, 1000, 0, 1000);
        assertThat(s.data()).isEqualTo(f);
        assertThat(s.outcome()).isEqualTo(ChunkCache.Outcome.MISS);
    }

    // --- Crash recovery ------------------------------------------------------------------------

    @Test
    void restartReconcilesTempFilesMalformedEntriesAndKeepsGoodOnes() throws Exception {
        byte[] f = file(1000, 14);
        source.content.put("id:n@" + REV1, f);
        read(box, "id:n", REV1, 1000, 0, 1000);
        Path good = cacheFiles().get(0);
        Path dir = good.getParent();
        Path tmp = dir.resolve(good.getFileName() + ".0123456789abcdef.tmp");
        Files.write(tmp, new byte[] {1, 2, 3});
        Path bad = dir.resolve("0".repeat(32) + "-" + "1".repeat(64) + ".hdc");
        Files.write(bad, new byte[100]);
        Path unknown = dir.resolve("notes.txt");
        Files.writeString(unknown, "not ours");
        Path outside = temp.resolve("outside.hdc");
        Files.write(outside, new byte[100]);
        cache.shutdown();

        cache = new ChunkCache(temp.resolve("cache"), source, m -> { });
        assertThat(Files.exists(tmp)).isFalse();
        assertThat(Files.exists(bad)).isFalse();
        assertThat(Files.exists(unknown)).isTrue();
        assertThat(Files.exists(outside)).isTrue();
        assertThat(cache.status().store().entries()).isEqualTo(1);
        assertThat(cache.status().store().startupRemoved()).isEqualTo(2);

        cache.startSession(user, key, false);
        assertThat(read(box, "id:n", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT);
    }

    @Test
    void aFailedFetchNeverLeavesAnEntry() throws Exception {
        source.content.put("id:o@" + REV1, file(1000, 15));
        source.failWith = new ReadException("truncated_response", 502);
        assertThatThrownBy(() -> read(box, "id:o", REV1, 1000, 0, 1000)).isInstanceOf(ReadException.class);
        assertThat(cacheFiles()).isEmpty();
        assertThat(cache.status().store().tempBytes()).isZero();
    }

    // --- Cleanup races -------------------------------------------------------------------------

    private CompletableFuture<Object> gatedRead(String id) throws Exception {
        source.content.put(id + "@" + REV1, file(1000, id.hashCode()));
        source.entered = new CountDownLatch(1);
        source.gate = new CountDownLatch(1);
        CompletableFuture<Object> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return read(box, id, REV1, 1000, 0, 1000);
            } catch (ReadException e) {
                return e.code();
            }
        });
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        return pending;
    }

    @Test
    void clearingWhileAFetchIsInFlightPreventsLatePublication() throws Exception {
        CompletableFuture<Object> pending = gatedRead("id:p");
        cache.clear(null);
        source.gate.countDown();
        Object result = pending.get(5, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(ChunkCache.Served.class);
        assertThat(((ChunkCache.Served) result).notAdmittedReason()).isEqualTo("cache_invalidated");
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void disconnectCancelsTheFetchAndNothingIsResurrected() throws Exception {
        CompletableFuture<Object> pending = gatedRead("id:q");
        cache.clear(box.accountId());
        box.inFlight.forEach(CancelToken::cancel); // what CredentialRegistry.clear(account) does
        assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo("cancelled");
        source.gate.countDown();
        Thread.sleep(100);
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void logoutWhileAFetchIsInFlightPublishesNothingAndDropsTheKey() throws Exception {
        CompletableFuture<Object> pending = gatedRead("id:r");
        ChunkStore.PurgeResult purge = cache.endSession(user);
        source.gate.countDown();
        pending.get(5, TimeUnit.SECONDS);
        assertThat(purge.failed()).isZero();
        assertThat(cacheFiles()).isEmpty();
        assertThat(cache.bypassReason(Provider.DROPBOX, REV1)).isEqualTo("cache_key_unavailable");
    }

    @Test
    void aReadAfterTheSessionEndedReturnsNullSoTheCallerServesItUncached() throws Exception {
        source.content.put("id:u@" + REV1, file(100, 18));
        cache.endSession(user);
        assertThat(read(box, "id:u", REV1, 100, 0, 100)).isNull();
        assertThat(source.calls).isEmpty();
    }

    @Test
    void disconnectingOneAccountKeepsTheOthers() throws Exception {
        Credential other = credential(Provider.DROPBOX, UUID.randomUUID());
        source.content.put("id:s@" + REV1, file(1000, 16));
        read(box, "id:s", REV1, 1000, 0, 1000);
        read(other, "id:s", REV1, 1000, 0, 1000);
        ChunkStore.PurgeResult purge = cache.clear(box.accountId());
        assertThat(purge.removed()).isEqualTo(1);
        assertThat(read(other, "id:s", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.HIT);
        assertThat(read(box, "id:s", REV1, 1000, 0, 1000).outcome()).isEqualTo(ChunkCache.Outcome.MISS);
    }

    @Test
    void deletionFailuresAreReportedNotHidden() throws Exception {
        source.content.put("id:t@" + REV1, file(1000, 17));
        read(box, "id:t", REV1, 1000, 0, 1000);
        Path chunk = cacheFiles().get(0);
        Files.delete(chunk);
        Files.createDirectory(chunk); // something that can't be removed as a file
        Files.writeString(chunk.resolve("blocker"), "x");
        ChunkStore.PurgeResult purge = cache.clear(null);
        assertThat(purge.failed()).isEqualTo(1);
        assertThat(cache.status().store().lastCleanup().failed()).isEqualTo(1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
