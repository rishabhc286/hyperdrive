package com.hyperdrive.agent.vfs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Assembles one directory listing for the VFS from backend pages (Batch 8).
 *
 * <p>Provider pagination is consumed here, completely, into one bounded listing; the VFS then
 * enumerates that snapshot with its own WinFsp markers. Limits are explicit and never truncate
 * silently: exceeding any of them fails the listing with a stable code.
 *
 * <ul>
 *   <li>{@link #MAX_ENTRIES} entries, {@link #MAX_PAGES} backend pages, {@link #DEADLINE} overall.</li>
 *   <li>{@link #MAX_CONCURRENT} listings at once (others get {@code too_busy}).</li>
 *   <li>Entries repeated by the provider while paginating (content changed meanwhile) are kept once,
 *       by provider id.</li>
 * </ul>
 * The agent keeps no snapshot after answering: nothing to release.
 */
public final class VfsMetadataService {

    public static final int MAX_ENTRIES = 10_000;
    public static final int MAX_PAGES = 100;
    public static final int PAGE_SIZE = 200;
    public static final Duration DEADLINE = Duration.ofSeconds(30);
    public static final int MAX_CONCURRENT = 4;
    private static final int MAX_DIRECTORY = 4096;

    private final MetadataSource source;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);

    public VfsMetadataService(MetadataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public Map<String, Object> accounts(MountRegistry.Mount mount) throws MetadataSource.MetadataException {
        acquire();
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (MetadataSource.Account a : source.accounts(mount)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", a.id().toString());
                item.put("provider", a.provider());
                item.put("rootType", a.rootType());
                item.put("status", a.status());
                items.add(item);
            }
            return Map.of("generation", mount.generation(), "items", items);
        } finally {
            permits.release();
        }
    }

    public Map<String, Object> list(MountRegistry.Mount mount, UUID accountId, String directory)
            throws MetadataSource.MetadataException {
        if (directory != null && (directory.isEmpty() || directory.length() > MAX_DIRECTORY
                || directory.chars().anyMatch(c -> c < 0x20 || c == 0x7f))) {
            throw new MetadataSource.MetadataException("invalid_request", 400);
        }
        acquire();
        try {
            Instant deadline = clock.instant().plus(DEADLINE);
            List<Map<String, Object>> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String continuation = null;
            int pages = 0;
            int duplicates = 0;
            do {
                if (++pages > MAX_PAGES) {
                    throw new MetadataSource.MetadataException("directory_too_large", 413);
                }
                if (clock.instant().isAfter(deadline)) {
                    throw new MetadataSource.MetadataException("timeout", 504);
                }
                MetadataSource.Page page = source.files(mount, accountId, directory, continuation, PAGE_SIZE);
                for (MetadataSource.Entry e : page.items()) {
                    if (!seen.add(e.id())) {
                        duplicates++;
                        continue;
                    }
                    if (items.size() >= MAX_ENTRIES) {
                        throw new MetadataSource.MetadataException("directory_too_large", 413);
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", e.id());
                    item.put("name", e.name());
                    item.put("kind", e.kind());
                    item.put("sizeBytes", e.sizeBytes());
                    item.put("modifiedMillis", e.modifiedAt() == null ? null : e.modifiedAt().toEpochMilli());
                    item.put("directoryLocator", e.directoryLocator());
                    items.add(item);
                }
                continuation = page.continuation();
            } while (continuation != null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accountId", accountId.toString());
            result.put("complete", true);
            result.put("pages", pages);
            result.put("duplicatesDropped", duplicates);
            result.put("items", items);
            return result;
        } finally {
            permits.release();
        }
    }

    private void acquire() throws MetadataSource.MetadataException {
        if (!permits.tryAcquire()) {
            throw new MetadataSource.MetadataException("too_busy", 503);
        }
    }
}
