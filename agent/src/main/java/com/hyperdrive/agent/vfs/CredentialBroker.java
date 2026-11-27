package com.hyperdrive.agent.vfs;

import com.hyperdrive.agent.read.CancelToken;
import com.hyperdrive.agent.read.CredentialRegistry;
import com.hyperdrive.agent.read.ReadException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Credential renewal for mounted reads (Batch 9, ADR 0005).
 *
 * <p>The agent never refreshes provider tokens itself and holds no HyperDrive or provider refresh
 * token. When a mounted read needs a credential for an account that has none (or whose token the
 * provider just rejected), the broker records <b>one</b> request per account for the current mount
 * generation. Electron main long-polls {@link #poll}, obtains the delegation with its signed-in
 * session (Batch 6 route) and answers with {@link #install} or {@link #fail}. Readers wait a bounded
 * time.
 *
 * <ul>
 *   <li>Everything is bound to a mount generation; {@link #reset} (unmount, new mount, agent exit)
 *       fails every waiter and forgets every request and failure.</li>
 *   <li>No storms: permanent failures (reconnect needed, no content permission, account gone) stick
 *       until the next generation; transient ones back off {@link #TRANSIENT_BACKOFF}; a token that is
 *       rejected again soon after a renewal is not renewed again for {@link #REJECTION_BACKOFF}.</li>
 * </ul>
 */
public final class CredentialBroker {

    /** A live credential with less than this left triggers a background renewal. */
    static final Duration RENEW_AHEAD = Duration.ofMinutes(2);
    static final Duration TRANSIENT_BACKOFF = Duration.ofSeconds(15);
    static final Duration REJECTION_BACKOFF = Duration.ofSeconds(60);
    /** An undelivered/unanswered request is offered to the poller again after this long. */
    static final Duration REDELIVER_AFTER = Duration.ofSeconds(30);
    public static final Duration MAX_POLL = Duration.ofSeconds(25);
    private static final Set<String> PERMANENT = Set.of("needs_reconnect", "content_permission_missing", "account_not_found");
    private static final Set<String> TRANSIENT = Set.of("backend_unavailable", "rate_limited", "session_expired",
            "agent_rejected", "cancelled");

    /** Codes Electron may report; anything else is rejected as invalid. */
    public static final Set<String> FAILURE_CODES;

    static {
        java.util.HashSet<String> all = new java.util.HashSet<>(PERMANENT);
        all.addAll(TRANSIENT);
        FAILURE_CODES = Set.copyOf(all);
    }

    private static final class Pending {
        final long generation;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Instant offeredAt;

        Pending(long generation) {
            this.generation = generation;
        }
    }

    private record Failure(String code, Instant until) {
    }

    private final CredentialRegistry credentials;
    private final Clock clock;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Failure> failures = new HashMap<>();
    private final Map<UUID, Instant> lastRejectionRenewal = new HashMap<>();
    private long generation = -1;
    final AtomicLong requested = new AtomicLong();
    final AtomicLong installed = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    private volatile String lastFailure;

    public CredentialBroker(CredentialRegistry credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    /** A new mount (or none): fail every waiter, forget every request, failure and backoff. */
    public synchronized void reset(long newGeneration) {
        generation = newGeneration;
        for (Pending p : pending.values()) {
            p.done.completeExceptionally(new ReadException("cancelled", 409));
        }
        pending.clear();
        failures.clear();
        lastRejectionRenewal.clear();
        lastFailure = null;
        notifyAll();
    }

    /** Disconnect of one account: its pending request fails and its state is forgotten. */
    public synchronized void forget(UUID accountId) {
        Pending p = pending.remove(accountId);
        if (p != null) {
            p.done.completeExceptionally(new ReadException("cancelled", 409));
        }
        failures.remove(accountId);
        lastRejectionRenewal.remove(accountId);
    }

    /**
     * The handle of a usable credential for {@code accountId}, waiting up to {@code wait} for a
     * renewal. Fails with the renewal's failure code, {@code timeout}, or {@code cancelled}.
     */
    public String credentialFor(long mountGeneration, UUID accountId, CredentialRegistry.Provider provider, Duration wait,
            CancelToken cancel) throws ReadException {
        Instant deadline = clock.instant().plus(wait);
        for (int round = 0; round < 3; round++) {
            CompletableFuture<Void> waitFor;
            synchronized (this) {
                if (mountGeneration != generation) {
                    throw new ReadException("stale_handle", 410);
                }
                CredentialRegistry.Live live = credentials.live(accountId);
                if (live != null) {
                    if (live.provider() != provider) {
                        throw new ReadException("invalid_request", 400);
                    }
                    if (Duration.between(clock.instant(), live.retentionExpiresAt()).compareTo(RENEW_AHEAD) < 0) {
                        request(accountId); // background: the current credential keeps working meanwhile
                    }
                    return live.handle();
                }
                Failure failure = failures.get(accountId);
                if (failure != null) {
                    if (failure.until() == null || clock.instant().isBefore(failure.until())) {
                        throw failureException(failure.code());
                    }
                    failures.remove(accountId);
                }
                waitFor = request(accountId).done;
            }
            CompletableFuture<Void> mine = new CompletableFuture<>();
            waitFor.whenComplete((v, e) -> {
                if (e == null) {
                    mine.complete(null);
                } else {
                    mine.completeExceptionally(e);
                }
            });
            cancel.onCancel(() -> mine.completeExceptionally(new ReadException("cancelled", 409)));
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                throw new ReadException("timeout", 504);
            }
            try {
                mine.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new ReadException("timeout", 504);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReadException("cancelled", 409);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof ReadException r ? r : new ReadException("unavailable", 502);
            }
        }
        throw new ReadException("credential_expired", 409); // installed but already unusable: give up
    }

    /**
     * The provider rejected {@code handle}. Returns true when one renewal may be attempted (the
     * credential is retired and a new one requested); false when a renewal was already tried recently.
     */
    public synchronized boolean rejected(UUID accountId, String handle) {
        Instant now = clock.instant();
        Instant last = lastRejectionRenewal.get(accountId);
        if (last != null && now.isBefore(last.plus(REJECTION_BACKOFF))) {
            return false;
        }
        lastRejectionRenewal.put(accountId, now);
        credentials.retire(handle);
        return true;
    }

    private Pending request(UUID accountId) {
        Pending p = pending.get(accountId);
        if (p == null || p.generation != generation) {
            p = new Pending(generation);
            pending.put(accountId, p);
            requested.incrementAndGet();
            notifyAll();
        }
        return p;
    }

    /**
     * Electron's long poll: accounts that need a credential for {@code mountGeneration}, waiting up to
     * {@code maxWait} (capped at {@link #MAX_POLL}) for one to appear. Each request is offered once and
     * again after {@link #REDELIVER_AFTER} if still unanswered.
     */
    public synchronized List<UUID> poll(long mountGeneration, Duration maxWait) throws ReadException, InterruptedException {
        long until = System.nanoTime() + Math.min(maxWait.toNanos(), MAX_POLL.toNanos());
        while (true) {
            if (mountGeneration != generation) {
                throw new ReadException("mount_not_found", 404);
            }
            List<UUID> due = new ArrayList<>();
            Instant now = clock.instant();
            for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
                Pending p = e.getValue();
                if (p.offeredAt == null || !now.isBefore(p.offeredAt.plus(REDELIVER_AFTER))) {
                    p.offeredAt = now;
                    due.add(e.getKey());
                }
            }
            long left = until - System.nanoTime();
            if (!due.isEmpty() || left <= 0) {
                return due;
            }
            TimeUnit.NANOSECONDS.timedWait(this, Math.min(left, TimeUnit.SECONDS.toNanos(1)));
        }
    }

    /** Electron answers a request with a fresh delegation (installed into the registry). */
    public CredentialRegistry.Installed install(long mountGeneration, CredentialRegistry.Provider provider, UUID accountId,
            String accessToken, Instant providerExpiresAt, String rootNamespaceId) throws ReadException {
        synchronized (this) {
            if (mountGeneration != generation) {
                throw new ReadException("mount_not_found", 404);
            }
            if (!pending.containsKey(accountId)) {
                throw new ReadException("no_pending_request", 409);
            }
            CredentialRegistry.Installed result = credentials.install(provider, accountId, accessToken, providerExpiresAt,
                    rootNamespaceId);
            Pending p = pending.remove(accountId);
            failures.remove(accountId);
            installed.incrementAndGet();
            p.done.complete(null);
            return result;
        }
    }

    /** Electron couldn't obtain a delegation; waiting reads fail now with that code. */
    public synchronized void fail(long mountGeneration, UUID accountId, String code) throws ReadException {
        if (mountGeneration != generation) {
            throw new ReadException("mount_not_found", 404);
        }
        if (!FAILURE_CODES.contains(code)) {
            throw new ReadException("invalid_request", 400);
        }
        Pending p = pending.remove(accountId);
        if (p == null) {
            throw new ReadException("no_pending_request", 409);
        }
        failures.put(accountId, new Failure(code, PERMANENT.contains(code) ? null : clock.instant().plus(TRANSIENT_BACKOFF)));
        failed.incrementAndGet();
        lastFailure = code;
        p.done.completeExceptionally(failureException(code));
    }

    String lastFailure() {
        return lastFailure;
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    /** The read error a renewal failure becomes (what the VFS maps to an NTSTATUS). */
    static ReadException failureException(String code) {
        return switch (code) {
            case "needs_reconnect" -> new ReadException("needs_reconnect", 409);
            case "content_permission_missing" -> new ReadException("content_permission_missing", 409);
            case "account_not_found" -> new ReadException("not_found", 404);
            case "session_expired" -> new ReadException("authorization_expired", 401);
            case "rate_limited" -> new ReadException("rate_limited", 429);
            case "cancelled" -> new ReadException("cancelled", 409);
            default -> new ReadException("backend_unavailable", 503);
        };
    }
}
