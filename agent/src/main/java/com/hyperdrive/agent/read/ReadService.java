package com.hyperdrive.agent.read;

import com.hyperdrive.agent.read.CredentialRegistry.Credential;
import com.hyperdrive.agent.read.CredentialRegistry.Provider;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * The local read contract. This is an <b>application read contract</b> (filesystem-style), not HTTP
 * Range semantics:
 *
 * <ul>
 *   <li>{@code offset >= 0}, {@code 1 <= length <= 4 MiB}; {@code offset + length} must not overflow.</li>
 *   <li>{@code expectedSize} (from the file's metadata) is required. A read at or beyond EOF, or of a
 *       zero-byte file, returns 0 bytes with {@code eof=true} and makes no provider request.</li>
 *   <li>A read that extends past EOF is clamped: it returns the remaining bytes with {@code eof=true}.</li>
 *   <li>If the provider reports a different total size (or, for pinned Dropbox reads, a different
 *       revision), the read fails with {@code file_changed}; nothing partial is returned.</li>
 *   <li>A cancelled read (credential cleared, shutdown) fails with {@code cancelled}.</li>
 * </ul>
 */
public final class ReadService {

    public static final int MAX_READ_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CONCURRENT = 8;
    public static final int MAX_CONCURRENT_PER_ACCOUNT = 4;

    private static final Pattern DRIVE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");
    private static final Pattern DROPBOX_ID = Pattern.compile("^id:[A-Za-z0-9_-]{1,256}$");
    private static final Pattern DRIVE_REVISION = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");
    private static final Pattern DROPBOX_REVISION = Pattern.compile("^[0-9a-f]{9,64}$");

    public record ReadRequest(String handle, String fileId, long offset, long length, long expectedSize,
            String expectedRevision) {
    }

    /**
     * One answered read. {@code cache} says what the chunk cache did; {@code bypassReason} is set when the
     * cache wasn't used at all, {@code notAdmittedReason} when fetched bytes weren't stored.
     * {@code providerBytes} counts bytes actually received from the provider (whole chunks on eligible
     * misses), {@code cacheBytes} the requested bytes answered from cached chunks.
     */
    public record ReadResult(byte[] data, int length, long offset, boolean eof, boolean revisionPinned, String revision,
            ChunkCache.Outcome cache, String bypassReason, String notAdmittedReason, long providerBytes, long cacheBytes) {
    }

    private final CredentialRegistry credentials;
    private final RangeReader reader;
    private final ChunkCache cache;
    private final Semaphore global = new Semaphore(MAX_CONCURRENT);
    private final Map<UUID, Semaphore> perAccount = new ConcurrentHashMap<>();

    public ReadService(CredentialRegistry credentials, RangeReader reader, ChunkCache cache) {
        this.credentials = credentials;
        this.reader = reader;
        this.cache = cache;
    }

    public ReadResult read(ReadRequest request) throws ReadException {
        if (request.offset() < 0 || request.length() < 1 || request.length() > MAX_READ_BYTES || request.expectedSize() < 0) {
            throw ReadException.invalid();
        }
        long requestEnd;
        try {
            requestEnd = Math.addExact(request.offset(), request.length());
        } catch (ArithmeticException e) {
            throw ReadException.invalid();
        }
        Credential credential = credentials.get(request.handle());
        validateIds(credential.provider(), request);

        if (request.expectedSize() == 0 || request.offset() >= request.expectedSize()) {
            return new ReadResult(new byte[0], 0, request.offset(), true, false, null, ChunkCache.Outcome.NONE, null, null, 0,
                    0);
        }
        long end = Math.min(requestEnd, request.expectedSize()) - 1;
        boolean eof = end == request.expectedSize() - 1;

        Semaphore account = perAccount.computeIfAbsent(credential.accountId(), id -> new Semaphore(MAX_CONCURRENT_PER_ACCOUNT));
        if (!global.tryAcquire()) {
            throw new ReadException("too_busy", 503);
        }
        try {
            if (!account.tryAcquire()) {
                throw new ReadException("too_busy", 503);
            }
            CancelToken cancel = new CancelToken();
            credential.inFlight.add(cancel);
            try {
                String bypass = cache == null ? "cache_disabled"
                        : cache.bypassReason(credential.provider(), request.expectedRevision());
                ChunkCache.Served served = bypass == null ? cache.read(credential, request.fileId(),
                        request.expectedRevision(), request.expectedSize(), request.offset(), end + 1, cancel) : null;
                if (bypass == null && served == null) {
                    bypass = "cache_key_unavailable"; // the session ended between the check and the read
                }
                if (served != null) {
                    return new ReadResult(served.data(), served.length(), request.offset(), eof, true, served.revision(),
                            served.outcome(), null, served.notAdmittedReason(), served.providerBytes(), served.cacheBytes());
                }
                // Uncached Batch 6 path: exactly the requested range.
                ProviderReader.Result r = reader.read(credential, request.fileId(), request.offset(), end,
                        request.expectedSize(), request.expectedRevision(), cancel);
                if (cache != null) {
                    cache.recordBypass(bypass, r.length(), r.length());
                }
                return new ReadResult(r.data(), r.length(), request.offset(), eof, r.revisionPinned(), r.revision(),
                        ChunkCache.Outcome.BYPASS, bypass, null, r.length(), 0);
            } finally {
                credential.inFlight.remove(cancel);
                account.release();
            }
        } finally {
            global.release();
        }
    }

    private static void validateIds(Provider provider, ReadRequest request) throws ReadException {
        boolean drive = provider == Provider.GOOGLE_DRIVE;
        String id = request.fileId();
        if (id == null || !(drive ? DRIVE_ID : DROPBOX_ID).matcher(id).matches()) {
            throw ReadException.invalid();
        }
        String rev = request.expectedRevision();
        if (rev != null && !(drive ? DRIVE_REVISION : DROPBOX_REVISION).matcher(rev).matches()) {
            throw ReadException.invalid();
        }
    }
}
