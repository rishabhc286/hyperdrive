package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.auth.SecureTokens;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Storage authorization transactions. Every transition is one conditional UPDATE, so a ticket,
 * an OAuth state or a callback can each be used at most once, and nothing moves backwards.
 *
 * <pre>
 * PENDING --browser opens ticket (once)--> AUTHORIZING (state, nonce, PKCE, browser binding stored)
 * AUTHORIZING --Google callback (once)--> COMPLETING --> COMPLETED | FAILED
 * PENDING | AUTHORIZING --desktop cancels, or a newer attempt from the same session--> CANCELLED
 * </pre>
 *
 * Transactions belong to one HyperDrive user <em>and</em> the device session that created them;
 * status and cancel require both, so a transaction id alone reveals nothing.
 */
@Service
@ConditionalOnAuthEnabled
public class StorageConnectionService {

    public record Created(UUID id, String ticket, Instant expiresAt) {
        @Override
        public String toString() {
            return "Created[id=" + id + "]";
        }
    }

    /** A callback that won the race for its transaction. */
    public record Claimed(UUID id, UUID userId, StorageProvider provider, byte[] bindingHash, String nonceHash,
            byte[] encryptedVerifier, int keyVersion) {
        @Override
        public String toString() {
            return "Claimed[id=" + id + "]";
        }
    }

    public enum PublicStatus { PENDING, COMPLETED, FAILED, CANCELLED, EXPIRED }

    public record Status(UUID transactionId, StorageProvider provider, PublicStatus status, boolean browserOpened,
            Instant expiresAt, UUID accountId, Boolean reconnected, String errorCode) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final StorageProperties properties;
    private final CredentialCipher cipher;

    public StorageConnectionService(JdbcClient jdbc, Clock clock, StorageProperties properties, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.cipher = cipher;
    }

    /** New attempt for this session; any unfinished earlier attempt of the same session is cancelled. */
    public Created create(UUID userId, UUID sessionId, StorageProvider provider) {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE storage_connection_transactions SET status = 'CANCELLED', completed_at = :now,
                       error_code = 'superseded'
                WHERE device_session_id = :session AND provider = :provider AND status IN ('PENDING', 'AUTHORIZING')
                """)
                .param("now", utc(now)).param("session", sessionId).param("provider", provider.name()).update();
        UUID id = UUID.randomUUID();
        String ticket = SecureTokens.STORAGE_TICKET.generate();
        Instant expiresAt = now.plus(properties.transactionTtl());
        Instant ticketExpiresAt = now.plus(properties.ticketTtl());
        jdbc.sql("""
                INSERT INTO storage_connection_transactions (id, user_id, device_session_id, provider, status,
                    start_ticket_hash, ticket_expires_at, created_at, expires_at)
                VALUES (:id, :userId, :session, :provider, 'PENDING', :ticketHash, :ticketExpiresAt, :now, :expiresAt)
                """)
                .param("id", id).param("userId", userId).param("session", sessionId)
                .param("provider", provider.name())
                .param("ticketHash", SecureTokens.hash(ticket))
                .param("ticketExpiresAt", utc(ticketExpiresAt.isBefore(expiresAt) ? ticketExpiresAt : expiresAt))
                .param("now", utc(now)).param("expiresAt", utc(expiresAt))
                .update();
        return new Created(id, ticket, expiresAt);
    }

    /**
     * Consumes the one-time ticket and binds the OAuth state, nonce, PKCE verifier (encrypted) and
     * browser cookie to the transaction. Empty if the ticket is unknown, used, expired or cancelled.
     */
    public Optional<UUID> startBrowser(StorageProvider provider, String ticket, String state, String nonceHash,
            String codeVerifier, String browserBinding) {
        if (!SecureTokens.STORAGE_TICKET.isWellFormed(ticket)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<UUID> id = jdbc.sql("""
                SELECT id FROM storage_connection_transactions
                WHERE start_ticket_hash = :ticketHash AND provider = :provider
                """)
                .param("ticketHash", SecureTokens.hash(ticket)).param("provider", provider.name())
                .query(UUID.class).optional();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        CredentialCipher.Sealed verifier = cipher.encrypt(codeVerifier, verifierContext(id.get()));
        int updated = jdbc.sql("""
                UPDATE storage_connection_transactions
                SET status = 'AUTHORIZING', browser_started_at = :now, oauth_state_hash = :stateHash,
                    browser_binding_hash = :bindingHash, oidc_nonce = :nonce, encrypted_code_verifier = :verifier,
                    encryption_key_version = :keyVersion
                WHERE id = :id AND start_ticket_hash = :ticketHash AND status = 'PENDING'
                  AND ticket_expires_at > :now AND expires_at > :now
                """)
                .param("now", utc(now))
                .param("stateHash", SecureTokens.hash(state))
                .param("bindingHash", SecureTokens.hash(browserBinding))
                .param("nonce", nonceHash)
                .param("verifier", verifier.value())
                .param("keyVersion", verifier.keyVersion())
                .param("id", id.get())
                .param("ticketHash", SecureTokens.hash(ticket))
                .update();
        return updated == 1 ? id : Optional.empty();
    }

    /** The single winning callback for an OAuth state; empty for unknown, replayed or expired state. */
    public Optional<Claimed> claimCallback(StorageProvider provider, String state) {
        if (state == null || state.isEmpty() || state.length() > 512) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE storage_connection_transactions SET status = 'COMPLETING'
                WHERE oauth_state_hash = :stateHash AND provider = :provider AND status = 'AUTHORIZING'
                  AND expires_at > :now
                RETURNING id, user_id, provider, browser_binding_hash, oidc_nonce, encrypted_code_verifier,
                          encryption_key_version
                """)
                .param("stateHash", SecureTokens.hash(state)).param("provider", provider.name()).param("now", utc(now))
                .query((rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        StorageProvider.valueOf(rs.getString("provider")), rs.getBytes("browser_binding_hash"),
                        rs.getString("oidc_nonce"), rs.getBytes("encrypted_code_verifier"),
                        rs.getInt("encryption_key_version")))
                .optional();
    }

    public String codeVerifier(Claimed claimed) {
        return cipher.decrypt(claimed.encryptedVerifier(), claimed.keyVersion(), verifierContext(claimed.id()));
    }

    public void complete(UUID id, UUID accountId, boolean reconnected) {
        jdbc.sql("""
                UPDATE storage_connection_transactions
                SET status = 'COMPLETED', connected_account_id = :accountId, reconnected = :reconnected,
                    completed_at = :now, encrypted_code_verifier = NULL
                WHERE id = :id AND status = 'COMPLETING'
                """)
                .param("accountId", accountId).param("reconnected", reconnected).param("now", utc(clock.instant()))
                .param("id", id).update();
    }

    public void fail(UUID id, String errorCode) {
        jdbc.sql("""
                UPDATE storage_connection_transactions
                SET status = 'FAILED', error_code = :code, completed_at = :now, encrypted_code_verifier = NULL
                WHERE id = :id AND status = 'COMPLETING'
                """)
                .param("code", errorCode).param("now", utc(clock.instant())).param("id", id).update();
    }

    /** Status for the owning user and device session only. */
    public Optional<Status> status(UUID id, UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT id, provider, status, browser_started_at, expires_at, connected_account_id, reconnected, error_code
                FROM storage_connection_transactions
                WHERE id = :id AND user_id = :userId AND device_session_id = :session
                """)
                .param("id", id).param("userId", userId).param("session", sessionId)
                .query((rs, n) -> {
                    String raw = rs.getString("status");
                    Instant expiresAt = rs.getObject("expires_at", OffsetDateTime.class).toInstant();
                    PublicStatus status = switch (raw) {
                        case "COMPLETED" -> PublicStatus.COMPLETED;
                        case "FAILED" -> PublicStatus.FAILED;
                        case "CANCELLED" -> PublicStatus.CANCELLED;
                        default -> expiresAt.isAfter(now) ? PublicStatus.PENDING : PublicStatus.EXPIRED;
                    };
                    Object reconnected = rs.getObject("reconnected");
                    return new Status(rs.getObject("id", UUID.class), StorageProvider.valueOf(rs.getString("provider")),
                            status, rs.getObject("browser_started_at") != null, expiresAt,
                            rs.getObject("connected_account_id", UUID.class),
                            reconnected instanceof Boolean b ? b : null, rs.getString("error_code"));
                })
                .optional();
    }

    /**
     * Server-side cancellation: an unfinished attempt can no longer complete (a later Google
     * callback is rejected). A callback already being processed is not interrupted.
     */
    public Optional<Status> cancel(UUID id, UUID userId, UUID sessionId) {
        jdbc.sql("""
                UPDATE storage_connection_transactions
                SET status = 'CANCELLED', error_code = 'cancelled', completed_at = :now, encrypted_code_verifier = NULL
                WHERE id = :id AND user_id = :userId AND device_session_id = :session
                  AND status IN ('PENDING', 'AUTHORIZING')
                """)
                .param("now", utc(clock.instant())).param("id", id).param("userId", userId).param("session", sessionId)
                .update();
        return status(id, userId, sessionId);
    }

    static String verifierContext(UUID transactionId) {
        return "storage_connection_transactions:" + transactionId + ":code_verifier";
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
