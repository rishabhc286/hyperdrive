package com.hyperdrive.agent.read;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * In-memory store of delegated provider access tokens, keyed by opaque local handles.
 *
 * <ul>
 *   <li>Never written to disk or logs; {@link Credential#toString()} is redacted.</li>
 *   <li>At most one usable credential per connected account (a new delegation replaces the old one;
 *       reads already using the old one finish) and at most {@link #MAX_CREDENTIALS} overall.</li>
 *   <li>Two expiries: the provider's stated expiry, and a shorter local retention. Whichever comes
 *       first ends local use. Neither changes what the provider token can do elsewhere.</li>
 *   <li>Removing a credential cancels its in-flight reads.</li>
 * </ul>
 *
 * Java strings can't be reliably wiped from memory; the practical mitigation is a short lifetime and
 * dropping all references on removal (see docs/local-agent.md).
 */
public final class CredentialRegistry {

    public static final int MAX_CREDENTIALS = 32;
    /** Local retention cap, independent of (and never longer than) the provider expiry. */
    public static final Duration MAX_RETENTION = Duration.ofMinutes(20);
    /** A token this close to its provider expiry is no longer used. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(15);

    public enum Provider { GOOGLE_DRIVE, DROPBOX }

    /** One delegated credential. The token is only ever handed to the provider adapters. */
    public static final class Credential {
        final String handle;
        final Provider provider;
        final UUID accountId;
        final String accessToken;
        final Instant providerExpiresAt;
        final Instant retentionExpiresAt;
        final String rootNamespaceId;
        final Set<CancelToken> inFlight = new CopyOnWriteArraySet<>();

        Credential(String handle, Provider provider, UUID accountId, String accessToken, Instant providerExpiresAt,
                Instant retentionExpiresAt, String rootNamespaceId) {
            this.handle = handle;
            this.provider = provider;
            this.accountId = accountId;
            this.accessToken = accessToken;
            this.providerExpiresAt = providerExpiresAt;
            this.retentionExpiresAt = retentionExpiresAt;
            this.rootNamespaceId = rootNamespaceId;
        }

        public Provider provider() {
            return provider;
        }

        public UUID accountId() {
            return accountId;
        }

        public Instant retentionExpiresAt() {
            return retentionExpiresAt;
        }

        @Override
        public String toString() {
            return "Credential[provider=" + provider + ", accountId=" + accountId + ", token=redacted]";
        }
    }

    public record Installed(String handle, Instant retentionExpiresAt, Instant providerExpiresAt) {
    }

    /** The account's current usable credential (Batch 9 mount reads look credentials up by account). */
    public record Live(String handle, Provider provider, Instant retentionExpiresAt) {
    }

    private static final Pattern TOKEN = Pattern.compile("^[\\x21-\\x7e]{16,4096}$");
    private static final Pattern NAMESPACE = Pattern.compile("^[0-9]{1,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, Credential> byHandle = new HashMap<>();
    private final Map<UUID, String> handleByAccount = new HashMap<>();
    /**
     * Credentials replaced by a newer delegation (or retired after a provider rejection) whose reads
     * may still be running. They can't be looked up any more, but clearing the account still cancels
     * those reads. Pruned once their reads finish.
     */
    private final Map<UUID, List<Credential>> retired = new HashMap<>();

    public CredentialRegistry(Clock clock) {
        this.clock = clock;
    }

    public synchronized Installed install(Provider provider, UUID accountId, String accessToken, Instant providerExpiresAt,
            String rootNamespaceId) throws ReadException {
        if (provider == null || accountId == null || accessToken == null || !TOKEN.matcher(accessToken).matches()
                || providerExpiresAt == null) {
            throw ReadException.invalid();
        }
        if (provider == Provider.DROPBOX ? rootNamespaceId == null || !NAMESPACE.matcher(rootNamespaceId).matches()
                : rootNamespaceId != null) {
            throw ReadException.invalid();
        }
        Instant now = clock.instant();
        if (!providerExpiresAt.minus(EXPIRY_MARGIN).isAfter(now)) {
            throw new ReadException("credential_expired", 409);
        }
        purgeExpired(now);
        pruneRetired();
        String previous = handleByAccount.remove(accountId);
        if (previous != null) {
            // Replaced, not revoked: reads already using the previous token finish normally (the
            // provider token stays valid until it expires); new reads get the new credential.
            retireCredential(byHandle.remove(previous));
        }
        if (byHandle.size() >= MAX_CREDENTIALS) {
            throw new ReadException("too_many_credentials", 503);
        }
        Instant retention = now.plus(MAX_RETENTION);
        if (retention.isAfter(providerExpiresAt)) {
            retention = providerExpiresAt;
        }
        String handle = "hdh_" + randomId();
        byHandle.put(handle, new Credential(handle, provider, accountId, accessToken, providerExpiresAt, retention,
                rootNamespaceId));
        handleByAccount.put(accountId, handle);
        return new Installed(handle, retention, providerExpiresAt);
    }

    /** The live credential for a handle, or an error if unknown or expired (expired ones are removed). */
    public synchronized Credential get(String handle) throws ReadException {
        Credential credential = handle == null ? null : byHandle.get(handle);
        if (credential == null) {
            throw new ReadException("unknown_credential", 409);
        }
        Instant now = clock.instant();
        if (!now.isBefore(credential.retentionExpiresAt) || !credential.providerExpiresAt.minus(EXPIRY_MARGIN).isAfter(now)) {
            remove(credential);
            throw new ReadException("credential_expired", 409);
        }
        return credential;
    }

    /**
     * The account's live credential, or null when there is none or it has expired (an expired one is
     * removed, as by {@link #get}).
     */
    public synchronized Live live(UUID accountId) {
        String handle = accountId == null ? null : handleByAccount.get(accountId);
        Credential credential = handle == null ? null : byHandle.get(handle);
        if (credential == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!now.isBefore(credential.retentionExpiresAt) || !credential.providerExpiresAt.minus(EXPIRY_MARGIN).isAfter(now)) {
            remove(credential);
            return null;
        }
        return new Live(credential.handle, credential.provider, credential.retentionExpiresAt);
    }

    /**
     * Stops handing out one credential (the provider rejected it) without cancelling reads that are
     * using it. Returns whether it was still current.
     */
    public synchronized boolean retire(String handle) {
        Credential credential = handle == null ? null : byHandle.remove(handle);
        if (credential == null) {
            return false;
        }
        handleByAccount.remove(credential.accountId, handle);
        retireCredential(credential);
        return true;
    }

    /** Removes one account's credential (or all when {@code accountId} is null); returns how many. */
    public synchronized int clear(UUID accountId) {
        List<Credential> removed = new ArrayList<>();
        if (accountId == null) {
            removed.addAll(byHandle.values());
            byHandle.clear();
            handleByAccount.clear();
            retired.values().forEach(list -> list.forEach(CredentialRegistry::discard));
            retired.clear();
        } else {
            String handle = handleByAccount.remove(accountId);
            if (handle != null) {
                removed.add(byHandle.remove(handle));
            }
            List<Credential> old = retired.remove(accountId);
            if (old != null) {
                old.forEach(CredentialRegistry::discard);
            }
        }
        removed.forEach(CredentialRegistry::discard);
        return removed.size();
    }

    private void retireCredential(Credential credential) {
        if (credential != null && !credential.inFlight.isEmpty()) {
            retired.computeIfAbsent(credential.accountId, id -> new ArrayList<>()).add(credential);
        }
    }

    private void pruneRetired() {
        retired.values().forEach(list -> list.removeIf(c -> c.inFlight.isEmpty()));
        retired.values().removeIf(List::isEmpty);
    }

    public synchronized int size() {
        return byHandle.size();
    }

    private void purgeExpired(Instant now) {
        List<Credential> expired = byHandle.values().stream()
                .filter(c -> !now.isBefore(c.retentionExpiresAt) || !c.providerExpiresAt.minus(EXPIRY_MARGIN).isAfter(now))
                .toList();
        expired.forEach(this::remove);
    }

    private void remove(Credential credential) {
        byHandle.remove(credential.handle);
        handleByAccount.remove(credential.accountId, credential.handle);
        discard(credential);
    }

    private static void discard(Credential credential) {
        if (credential != null) {
            credential.inFlight.forEach(CancelToken::cancel);
        }
    }

    private static String randomId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
