package com.hyperdrive.agent.read;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encrypted chunk files on disk, with the file system as the index (see docs/cache.md).
 *
 * <ul>
 *   <li>Layout: {@code <root>/v1/<userTag>/<accountTag>-<chunkTag>.hdc}; in-progress files are
 *       {@code <name>.<random>.tmp} and are always ciphertext.</li>
 *   <li>Publication is a temp write + flush + atomic rename, so a crash never leaves a partial
 *       {@code .hdc}.</li>
 *   <li>Budget = sum of {@code .hdc} sizes + reserved temp bytes. Admission reserves first and evicts
 *       least-recently-used, unpinned entries; if that isn't enough the chunk isn't admitted.</li>
 *   <li>Generations (global, per user, per account) are bumped by every purge; a fetch that started
 *       under an older generation can't publish (no late resurrection).</li>
 *   <li>The monitor lock covers only index updates, reservations, renames and deletes — never
 *       network I/O or reading/writing chunk contents.</li>
 * </ul>
 */
public final class ChunkStore {

    static final long DEFAULT_BUDGET = 5L * 1024 * 1024 * 1024;
    static final long MIN_BUDGET = 256L * 1024 * 1024;
    static final long MAX_BUDGET = 1L << 40;
    static final int MAX_SCAN_FILES = 200_000;
    static final String FORMAT_DIR = "v1";

    private static final Pattern USER_DIR = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern ENTRY = Pattern.compile("^([0-9a-f]{32})-[0-9a-f]{64}\\.hdc$");
    private static final Pattern TEMP = Pattern.compile("^[0-9a-f]{32}-[0-9a-f]{64}\\.hdc\\.[0-9a-f]{16}\\.tmp$");
    private static final SecureRandom RANDOM = new SecureRandom();

    static boolean validBudget(long bytes) {
        return bytes == 0 || (bytes >= MIN_BUDGET && bytes <= MAX_BUDGET);
    }

    /** One {@code .hdc} file. */
    static final class Entry {
        final String userTag;
        final String accountTag;
        final String name;
        final Path path;
        final long size;
        int pins;
        boolean doomed;

        Entry(String userTag, String accountTag, String name, Path path, long size) {
            this.userTag = userTag;
            this.accountTag = accountTag;
            this.name = name;
            this.path = path;
            this.size = size;
        }
    }

    record Gen(long global, long user, long account) {
    }

    /** Space reserved for one temp file until it's published or released. */
    static final class Reservation {
        final long bytes;
        final Path temp;
        boolean open = true;

        Reservation(long bytes, Path temp) {
            this.bytes = bytes;
            this.temp = temp;
        }
    }

    public record PurgeResult(String scope, int removed, int failed) {
    }

    public record Snapshot(long budget, long encryptedBytes, long tempBytes, int entries, long overBudget, long evictions,
            long corruptions, long startupRemoved, PurgeResult lastCleanup) {
    }

    private final Path formatRoot;
    private final Consumer<String> log;
    private final LinkedHashMap<String, Entry> lru = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<Path, Long> pendingDelete = new HashMap<>();
    private final Set<Path> activeTemps = new HashSet<>();
    private final Map<String, Long> userGen = new HashMap<>();
    private final Map<String, Long> accountGen = new HashMap<>();
    private long globalGen;
    private long budget = DEFAULT_BUDGET;
    private long entryBytes;
    private long reservedBytes;
    private long evictions;
    private long corruptions;
    private long startupRemoved;
    private PurgeResult lastCleanup;

    ChunkStore(Path cacheRoot, Consumer<String> log) {
        this.formatRoot = cacheRoot.resolve(FORMAT_DIR);
        this.log = log;
    }

    // --- Startup reconciliation ----------------------------------------------------------------

    /** Bounded scan: drops temp files and malformed entries, registers the rest oldest-first. */
    synchronized void scan() {
        if (!Files.isDirectory(formatRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        record Found(Entry entry, FileTime modified) {
        }
        List<Found> found = new ArrayList<>();
        int seen = 0;
        try (DirectoryStream<Path> users = Files.newDirectoryStream(formatRoot)) {
            outer:
            for (Path userDir : users) {
                String userTag = userDir.getFileName().toString();
                if (!USER_DIR.matcher(userTag).matches() || !Files.isDirectory(userDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(userDir)) {
                    for (Path file : files) {
                        if (++seen > MAX_SCAN_FILES) {
                            log.accept("Cache scan stopped at " + MAX_SCAN_FILES + " files");
                            break outer;
                        }
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        String name = file.getFileName().toString();
                        if (TEMP.matcher(name).matches()) {
                            startupRemoved += deleteQuietly(file) ? 1 : 0;
                            continue;
                        }
                        Matcher m = ENTRY.matcher(name);
                        if (!m.matches()) {
                            continue; // not ours: ignored, never deleted
                        }
                        long size = Files.size(file);
                        if (size <= CacheCrypto.OVERHEAD || size > CacheCrypto.OVERHEAD + ChunkCache.CHUNK_SIZE
                                || !headerOk(file)) {
                            corruptions++;
                            startupRemoved += deleteQuietly(file) ? 1 : 0;
                            continue;
                        }
                        found.add(new Found(new Entry(userTag, m.group(1), name, file, size),
                                Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)));
                    }
                }
            }
        } catch (IOException e) {
            log.accept("Cache scan incomplete: " + e.getClass().getSimpleName());
        }
        found.sort(Comparator.comparing(Found::modified));
        for (Found f : found) {
            lru.put(key(f.entry.userTag, f.entry.name), f.entry);
            entryBytes += f.entry.size;
        }
        enforceBudget();
    }

    private static boolean headerOk(Path file) {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return CacheCrypto.hasMagic(in.readNBytes(CacheCrypto.MAGIC.length));
        } catch (IOException e) {
            return false;
        }
    }

    // --- Generations ---------------------------------------------------------------------------

    synchronized Gen generation(String userTag, String accountTag) {
        return new Gen(globalGen, userGen.getOrDefault(userTag, 0L), accountGen.getOrDefault(userTag + "/" + accountTag, 0L));
    }

    private boolean current(Gen g, String userTag, String accountTag) {
        return g.equals(generation(userTag, accountTag));
    }

    // --- Hits ----------------------------------------------------------------------------------

    /** Pins a live entry (protecting it from eviction) and marks it most recently used. */
    synchronized Entry pin(String userTag, String name) {
        Entry e = lru.get(key(userTag, name));
        if (e == null || e.doomed) {
            return null;
        }
        e.pins++;
        return e;
    }

    /** Reads a pinned entry; size-bounded by construction (checked at registration). */
    static byte[] read(Entry e) throws IOException {
        return Files.readAllBytes(e.path);
    }

    synchronized void unpin(Entry e) {
        e.pins--;
        if (e.pins == 0 && e.doomed) {
            finishDelete(e);
        }
        enforceBudget();
    }

    /** Best-effort recency for the next start (outside the lock; failure only affects LRU order). */
    static void touch(Entry e) {
        try {
            Files.setLastModifiedTime(e.path, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // recency is advisory
        }
    }

    /** A pinned entry failed authentication: remove it (after the last reader). */
    synchronized void removeCorrupt(Entry e) {
        corruptions++;
        doom(e);
        e.pins--;
        if (e.pins == 0) {
            finishDelete(e);
        }
    }

    // --- Admission -----------------------------------------------------------------------------

    /** Reserves space for one temp file, evicting LRU unpinned entries if needed; null if impossible. */
    synchronized Reservation reserve(String userTag, String name, long bytes) {
        if (budget == 0) {
            return null;
        }
        Iterator<Entry> it = lru.values().iterator();
        while (usage() + bytes > budget && it.hasNext()) {
            Entry e = it.next();
            if (e.pins == 0) {
                it.remove();
                entryBytes -= e.size;
                evictions++;
                deleteOrDefer(e.path, e.size);
            }
        }
        if (usage() + bytes > budget) {
            return null;
        }
        byte[] suffix = new byte[8];
        RANDOM.nextBytes(suffix);
        Path temp = formatRoot.resolve(userTag).resolve(name + "." + HexFormat.of().formatHex(suffix) + ".tmp");
        reservedBytes += bytes;
        activeTemps.add(temp);
        return new Reservation(bytes, temp);
    }

    /**
     * Writes ciphertext to the reservation's temp file and publishes it atomically if the generation
     * is still current. Returns false (and removes the temp file) when it was invalidated meanwhile.
     */
    boolean publish(Reservation r, byte[] ciphertext, String userTag, String accountTag, String name, Gen gen)
            throws IOException {
        if (r.bytes != ciphertext.length) {
            throw new IllegalArgumentException("reservation size mismatch");
        }
        Files.createDirectories(r.temp.getParent());
        try (FileChannel ch = FileChannel.open(r.temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(ciphertext);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        } catch (IOException e) {
            release(r);
            throw e;
        }
        synchronized (this) {
            if (!r.open) {
                deleteQuietly(r.temp);
                return false;
            }
            if (!current(gen, userTag, accountTag)) {
                closeReservation(r);
                deleteQuietly(r.temp);
                return false;
            }
            Path target = r.temp.resolveSibling(name);
            try {
                move(r.temp, target);
            } catch (IOException e) {
                closeReservation(r);
                deleteQuietly(r.temp);
                throw e;
            }
            closeReservation(r);
            Entry previous = lru.remove(key(userTag, name));
            if (previous != null) {
                entryBytes -= previous.size; // replaced in place by the same tag (after a corrupt removal race)
            }
            Entry e = new Entry(userTag, accountTag, name, target, ciphertext.length);
            lru.put(key(userTag, name), e);
            entryBytes += e.size;
            enforceBudget();
            return true;
        }
    }

    /** Gives up a reservation (and removes its temp file if one was written). */
    synchronized void release(Reservation r) {
        if (r.open) {
            closeReservation(r);
        }
        deleteQuietly(r.temp);
    }

    private void closeReservation(Reservation r) {
        r.open = false;
        reservedBytes -= r.bytes;
        activeTemps.remove(r.temp);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("atomic rename unavailable", e); // never fall back to a non-atomic copy
        }
    }

    // --- Purge, budget, status -----------------------------------------------------------------

    /** Deletes every entry of a user (and stray files of ours in their directory); bumps generations. */
    synchronized PurgeResult purgeUser(String userTag, String scope) {
        userGen.merge(userTag, 1L, Long::sum);
        int[] counts = new int[2];
        for (Iterator<Entry> it = lru.values().iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.userTag.equals(userTag)) {
                it.remove();
                purgeOne(e, counts);
            }
        }
        sweepDirectory(formatRoot.resolve(userTag), counts);
        lastCleanup = new PurgeResult(scope, counts[0], counts[1]);
        return lastCleanup;
    }

    synchronized PurgeResult purgeAccount(String userTag, String accountTag) {
        accountGen.merge(userTag + "/" + accountTag, 1L, Long::sum);
        int[] counts = new int[2];
        for (Iterator<Entry> it = lru.values().iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.userTag.equals(userTag) && e.accountTag.equals(accountTag)) {
                it.remove();
                purgeOne(e, counts);
            }
        }
        lastCleanup = new PurgeResult("account", counts[0], counts[1]);
        return lastCleanup;
    }

    /** Removes directories of any other user (only possible after a failed logout cleanup). */
    synchronized PurgeResult purgeOtherUsers(String keepUserTag) {
        globalGen++;
        Set<String> others = new HashSet<>();
        for (Entry e : lru.values()) {
            if (!e.userTag.equals(keepUserTag)) {
                others.add(e.userTag);
            }
        }
        if (Files.isDirectory(formatRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> users = Files.newDirectoryStream(formatRoot)) {
                for (Path dir : users) {
                    String tag = dir.getFileName().toString();
                    if (USER_DIR.matcher(tag).matches() && !tag.equals(keepUserTag)) {
                        others.add(tag);
                    }
                }
            } catch (IOException ignored) {
                // best effort; reported below only for what was attempted
            }
        }
        int removed = 0;
        int failed = 0;
        for (String tag : others) {
            PurgeResult r = purgeUser(tag, "other-user");
            removed += r.removed();
            failed += r.failed();
        }
        if (others.isEmpty()) {
            return new PurgeResult("other-user", 0, 0);
        }
        lastCleanup = new PurgeResult("other-user", removed, failed);
        return lastCleanup;
    }

    private void purgeOne(Entry e, int[] counts) {
        entryBytes -= e.size;
        if (e.pins > 0) {
            e.doomed = true; // the reader finishes from memory; deleted on unpin
            pendingDelete.put(e.path, e.size);
            counts[0]++;
            return;
        }
        if (deleteQuietly(e.path)) {
            counts[0]++;
        } else {
            pendingDelete.put(e.path, e.size);
            counts[1]++;
        }
    }

    /** Our own leftover files in a user directory (never links, never outside the cache root). */
    private void sweepDirectory(Path dir, int[] counts) {
        if (!dir.normalize().startsWith(formatRoot.normalize()) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean ours = ENTRY.matcher(name).matches() || TEMP.matcher(name).matches();
                if (!ours || activeTemps.contains(file) || pendingDelete.containsKey(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (deleteQuietly(file)) {
                    counts[0]++;
                } else {
                    counts[1]++;
                }
            }
        } catch (IOException e) {
            counts[1]++;
        }
        try {
            Files.deleteIfExists(dir); // only succeeds when empty
        } catch (IOException ignored) {
            // still has in-flight temps or undeletable files
        }
    }

    private void doom(Entry e) {
        if (!e.doomed && lru.remove(key(e.userTag, e.name)) != null) {
            entryBytes -= e.size;
            pendingDelete.put(e.path, e.size);
        }
        e.doomed = true;
    }

    private void finishDelete(Entry e) {
        if (deleteQuietly(e.path)) {
            pendingDelete.remove(e.path);
        }
    }

    synchronized void setBudget(long bytes) {
        if (!validBudget(bytes)) {
            throw new IllegalArgumentException("invalid budget");
        }
        budget = bytes;
        enforceBudget();
    }

    /** Tests only: budgets below the 256 MiB product minimum, so eviction can be exercised with small chunks. */
    synchronized void setBudgetForTest(long bytes) {
        budget = bytes;
        enforceBudget();
    }

    synchronized long budget() {
        return budget;
    }

    private void enforceBudget() {
        pendingDelete.entrySet().removeIf(p -> deleteQuietly(p.getKey()));
        Iterator<Entry> it = lru.values().iterator();
        while (usage() > budget && it.hasNext()) {
            Entry e = it.next();
            if (e.pins == 0) {
                it.remove();
                entryBytes -= e.size;
                evictions++;
                deleteOrDefer(e.path, e.size);
            }
        }
    }

    private void deleteOrDefer(Path path, long size) {
        if (!deleteQuietly(path)) {
            pendingDelete.put(path, size);
        }
    }

    private long usage() {
        long pending = 0;
        for (long s : pendingDelete.values()) {
            pending += s;
        }
        return entryBytes + reservedBytes + pending;
    }

    synchronized Snapshot snapshot() {
        long pending = 0;
        for (long s : pendingDelete.values()) {
            pending += s;
        }
        return new Snapshot(budget, entryBytes + pending, reservedBytes, lru.size(), Math.max(0, usage() - budget),
                evictions, corruptions, startupRemoved, lastCleanup);
    }

    private static String key(String userTag, String name) {
        return userTag + "/" + name;
    }

    private static boolean deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
