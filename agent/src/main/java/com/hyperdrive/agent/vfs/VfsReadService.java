package com.hyperdrive.agent.vfs;

import com.hyperdrive.agent.read.CancelToken;
import com.hyperdrive.agent.read.CredentialRegistry;
import com.hyperdrive.agent.read.ReadException;
import com.hyperdrive.agent.read.ReadService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Agent-owned read contexts for the mounted drive (Batch 9, ADR 0005).
 *
 * <p>{@link #open} validates the file against the backend (owner-checked, by provider id) and pins
 * what the backend said: provider, size and, for Dropbox, the revision. The VFS gets an opaque,
 * unguessable {@code hdf_} handle; only its SHA-256 is stored. {@link #read} serves at most
 * {@link ReadService#MAX_READ_BYTES} through the existing {@link ReadService} (and so the Batch 7
 * cache under its unchanged rules), obtaining credentials through the {@link CredentialBroker}.
 *
 * <ul>
 *   <li>At most {@link #MAX_OPEN} contexts per mount. No idle expiry; a context ends with
 *       {@link #close}, {@link #dropAll} (unmount, new mount, agent exit) or {@link #dropAccount}
 *       (disconnect). Ending a context cancels its in-flight reads.</li>
 *   <li>Every read re-checks the mount generation and the backend grant's expiry, and is bounded by
 *       {@link #READ_DEADLINE} including any credential wait ({@link #RENEWAL_WAIT}).</li>
 *   <li>Nothing returned contains a token, credential handle, cache path or file name.</li>
 * </ul>
 */
public final class VfsReadService {

    public static final int MAX_OPEN = 256;
    public static final Duration READ_DEADLINE = Duration.ofSeconds(45);
    public static final Duration RENEWAL_WAIT = Duration.ofSeconds(20);
    static final Pattern HANDLE = Pattern.compile("^hdf_[A-Za-z0-9_-]{43}$");
    private static final Pattern DRIVE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");
    private static final Pattern DROPBOX_ID = Pattern.compile("^id:[A-Za-z0-9_-]{1,256}$");
    private static final Pattern DROPBOX_REVISION = Pattern.compile("^[0-9a-f]{9,64}$");
    private static final Pattern DRIVE_REVISION = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> COUNTED_FAILURES = Set.of("authorization_expired", "needs_reconnect",
            "content_permission_missing", "permission_missing", "credential_rejected", "forbidden", "not_found",
            "file_changed", "unsupported_file", "unavailable", "backend_unavailable", "timeout", "rate_limited",
            "too_busy", "cancelled", "stale_handle", "too_many_open_files", "truncated_response",
            "invalid_provider_response", "range_not_supported", "provider_error", "credential_expired");

    public record Opened(String handle, long sizeBytes, String consistency, boolean revisionPinned) {
        @Override
        public String toString() {
            return "Opened[sizeBytes=" + sizeBytes + ", consistency=" + consistency + "]";
        }
    }

    private static final class Context {
        final long generation;
        final UUID accountId;
        final CredentialRegistry.Provider provider;
        final String fileId;
        final long size;
        final String revision;
        final Set<CancelToken> inFlight = new CopyOnWriteArraySet<>();
        volatile boolean closed;

        Context(long generation, UUID accountId, CredentialRegistry.Provider provider, String fileId, long size,
                String revision) {
            this.generation = generation;
            this.accountId = accountId;
            this.provider = provider;
            this.fileId = fileId;
            this.size = size;
            this.revision = revision;
        }

        void end() {
            closed = true;
            inFlight.forEach(CancelToken::cancel);
        }
    }

    private final ReadService reads;
    private final CredentialBroker broker;
    private final MetadataSource metadata;
    private final Clock clock;
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-vfs-read-deadline");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong opens = new AtomicLong();
    private final AtomicLong readsServed = new AtomicLong();
    private final AtomicLong bytesReturned = new AtomicLong();
    private final AtomicLong providerBytes = new AtomicLong();
    private final AtomicLong cacheBytes = new AtomicLong();
    private final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    private volatile String lastError;
    private final Duration readDeadline;
    private final Duration renewalWait;

    public VfsReadService(ReadService reads, CredentialBroker broker, MetadataSource metadata, Clock clock) {
        this(reads, broker, metadata, clock, READ_DEADLINE, RENEWAL_WAIT);
    }

    /** Tests only: shorter bounds. */
    VfsReadService(ReadService reads, CredentialBroker broker, MetadataSource metadata, Clock clock, Duration readDeadline,
            Duration renewalWait) {
        this.readDeadline = readDeadline;
        this.renewalWait = renewalWait;
        this.reads = reads;
        this.broker = broker;
        this.metadata = metadata;
        this.clock = clock;
    }

    /** OpenRead: validates against the backend and pins size, provider and (Dropbox) revision. */
    public Opened open(MountRegistry.Mount mount, UUID accountId, String fileId, long expectedSize) throws ReadException {
        try {
            requireActive(mount);
            if (accountId == null || fileId == null || fileId.length() > 300 || expectedSize < 0
                    || !(DRIVE_ID.matcher(fileId).matches() || DROPBOX_ID.matcher(fileId).matches())) {
                throw new ReadException("invalid_request", 400);
            }
            MetadataSource.FileMeta meta;
            try {
                meta = metadata.file(mount, accountId, fileId);
            } catch (MetadataSource.MetadataException e) {
                throw fromMetadata(e);
            }
            CredentialRegistry.Provider provider = CredentialRegistry.Provider.valueOf(meta.provider());
            boolean dropbox = provider == CredentialRegistry.Provider.DROPBOX;
            if (!meta.id().equals(fileId) || !(dropbox ? DROPBOX_ID : DRIVE_ID).matcher(fileId).matches()) {
                throw new ReadException("invalid_request", 400);
            }
            // Only ordinary, downloadable files with a known size; Dropbox additionally a known revision,
            // so that the handle can be pinned to it. Never invent a size or switch revisions later.
            String rev = meta.providerRevision();
            if (!"FILE".equals(meta.kind()) || !meta.canDownload() || meta.sizeBytes() == null
                    || (dropbox && (rev == null || !DROPBOX_REVISION.matcher(rev).matches()))) {
                throw new ReadException("unsupported_file", 415);
            }
            if (rev != null && !dropbox && !DRIVE_REVISION.matcher(rev).matches()) {
                rev = null; // informational only for Drive (reads aren't revision-pinned)
            }
            if (meta.sizeBytes() != expectedSize) {
                // Windows already shows the listing's size; serving another one would mix versions.
                throw new ReadException("file_changed", 409);
            }
            String handle;
            String key;
            Context context = new Context(mount.generation(), accountId, provider, fileId, meta.sizeBytes(), rev);
            synchronized (this) {
                requireActive(mount);
                long live = contexts.values().stream().filter(c -> c.generation == mount.generation()).count();
                if (live >= MAX_OPEN) {
                    throw new ReadException("too_many_open_files", 503);
                }
                byte[] raw = new byte[32];
                RANDOM.nextBytes(raw);
                handle = "hdf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
                key = hash(handle);
                contexts.put(key, context);
            }
            opens.incrementAndGet();
            return new Opened(handle, meta.sizeBytes(), dropbox ? "dropbox-revision-pinned" : "google-unpinned", dropbox);
        } catch (ReadException e) {
            count(e.code());
            throw e;
        } catch (IllegalArgumentException e) {
            count("invalid_request");
            throw new ReadException("invalid_request", 400);
        }
    }

    /** Read through an open context; bounded by {@link #READ_DEADLINE}. */
    public ReadService.ReadResult read(MountRegistry.Mount mount, String handle, long offset, long length)
            throws ReadException {
        try {
            requireActive(mount);
            Context context = lookup(mount, handle);
            // Validate before any credential wait (ReadService re-validates the same contract).
            if (offset < 0 || length < 1 || length > ReadService.MAX_READ_BYTES || offset > Long.MAX_VALUE - length) {
                throw new ReadException("invalid_request", 400);
            }
            CancelToken cancel = new CancelToken();
            AtomicBoolean timedOut = new AtomicBoolean();
            context.inFlight.add(cancel);
            ScheduledFuture<?> deadline = deadlines.schedule(() -> {
                timedOut.set(true);
                cancel.cancel();
            }, readDeadline.toMillis(), TimeUnit.MILLISECONDS);
            try {
                if (context.closed) {
                    throw new ReadException("cancelled", 409);
                }
                ReadService.ReadResult result = readWithRenewal(mount, context, offset, length, cancel, timedOut);
                if (context.closed || mount.generation() != context.generation) {
                    throw new ReadException("cancelled", 409); // closed while reading: no bytes after close
                }
                readsServed.incrementAndGet();
                bytesReturned.addAndGet(result.length());
                providerBytes.addAndGet(result.providerBytes());
                cacheBytes.addAndGet(result.cacheBytes());
                return result;
            } finally {
                deadline.cancel(false);
                context.inFlight.remove(cancel);
            }
        } catch (ReadException e) {
            count(e.code());
            throw e;
        }
    }

    private ReadService.ReadResult readWithRenewal(MountRegistry.Mount mount, Context context, long offset, long length,
            CancelToken cancel, AtomicBoolean timedOut) throws ReadException {
        Instant started = clock.instant();
        for (int attempt = 0; ; attempt++) {
            String credential = null;
            try {
                Duration left = readDeadline.minus(Duration.between(started, clock.instant()));
                credential = broker.credentialFor(context.generation, context.accountId, context.provider,
                        left.compareTo(renewalWait) < 0 ? left : renewalWait, cancel);
                return reads.read(new ReadService.ReadRequest(credential, context.fileId, offset, length, context.size,
                        context.revision), cancel);
            } catch (ReadException e) {
                if (cancel.isCancelled()) {
                    throw timedOut.get() ? new ReadException("timeout", 504) : new ReadException("cancelled", 409);
                }
                String code = e.code();
                if (attempt == 0 && (code.equals("unknown_credential") || code.equals("credential_expired"))) {
                    continue; // expired or cleared between lookup and use: the broker requests a new one
                }
                if (attempt == 0 && code.equals("credential_rejected")) {
                    // One renewal for a token the provider refused (it may have been revoked early).
                    // Permission/scope errors are final and never trigger renewal.
                    if (credential != null && broker.rejected(context.accountId, credential)) {
                        continue;
                    }
                }
                throw e;
            }
        }
    }

    /** CloseRead: idempotent for the caller; unknown handles answer false. */
    public boolean close(MountRegistry.Mount mount, String handle) {
        if (handle == null || !HANDLE.matcher(handle).matches()) {
            return false;
        }
        Context context = contexts.get(hash(handle));
        if (context == null || context.generation != mount.generation()) {
            return false;
        }
        contexts.remove(hash(handle), context);
        context.end();
        return true;
    }

    /** Unmount in progress: cancel every in-flight read of this mount (contexts stay until close). */
    public int cancelInFlight(MountRegistry.Mount mount) {
        int n = 0;
        for (Context c : contexts.values()) {
            if (c.generation == mount.generation()) {
                n += c.inFlight.size();
                c.inFlight.forEach(CancelToken::cancel);
            }
        }
        return n;
    }

    /** Unmount, new mount, logout or agent exit: every context ends. */
    public synchronized int dropAll() {
        int n = contexts.size();
        contexts.values().forEach(Context::end);
        contexts.clear();
        return n;
    }

    /** Disconnect: that account's contexts end. */
    public synchronized int dropAccount(UUID accountId) {
        int n = 0;
        for (Map.Entry<String, Context> e : contexts.entrySet()) {
            if (e.getValue().accountId.equals(accountId)) {
                contexts.remove(e.getKey(), e.getValue());
                e.getValue().end();
                n++;
            }
        }
        return n;
    }

    public void shutdown() {
        dropAll();
        deadlines.shutdownNow();
    }

    /** Counters for the desktop (no names, ids, tokens or paths). */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openHandles", contexts.size());
        out.put("opens", opens.get());
        out.put("reads", readsServed.get());
        out.put("bytesReturned", bytesReturned.get());
        out.put("providerBytes", providerBytes.get());
        out.put("cacheBytes", cacheBytes.get());
        Map<String, Long> f = new TreeMap<>();
        failures.forEach((k, v) -> f.put(k, v.get()));
        out.put("failures", f);
        out.put("lastError", lastError);
        Map<String, Object> renewals = new LinkedHashMap<>();
        renewals.put("requested", broker.requested.get());
        renewals.put("installed", broker.installed.get());
        renewals.put("failed", broker.failed.get());
        renewals.put("pending", broker.pendingCount());
        renewals.put("lastFailure", broker.lastFailure());
        out.put("renewals", renewals);
        return out;
    }

    private Context lookup(MountRegistry.Mount mount, String handle) throws ReadException {
        if (handle == null || !HANDLE.matcher(handle).matches()) {
            throw new ReadException("stale_handle", 410);
        }
        Context context = contexts.get(hash(handle));
        if (context == null || context.closed || context.generation != mount.generation()) {
            throw new ReadException("stale_handle", 410);
        }
        return context;
    }

    private void requireActive(MountRegistry.Mount mount) throws ReadException {
        if (!mount.grantExpiresAt().isAfter(clock.instant())) {
            throw new ReadException("authorization_expired", 401);
        }
    }

    private void count(String code) {
        String c = COUNTED_FAILURES.contains(code) ? code : "other";
        failures.computeIfAbsent(c, k -> new AtomicLong()).incrementAndGet();
        lastError = c;
    }

    private static ReadException fromMetadata(MetadataSource.MetadataException e) {
        return switch (e.code()) {
            case "authorization_lost", "authorization_expired" -> new ReadException("authorization_expired", 401);
            case "needs_reconnect" -> new ReadException("needs_reconnect", 409);
            case "not_found" -> new ReadException("not_found", 404);
            case "forbidden" -> new ReadException("forbidden", 403);
            case "rate_limited" -> new ReadException("rate_limited", 429);
            default -> new ReadException("backend_unavailable", 503);
        };
    }

    static String hash(String handle) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(handle.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
