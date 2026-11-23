package com.hyperdrive.backend.vfs;

import com.hyperdrive.backend.auth.SecureTokens;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Metadata grants (Batch 8): opaque {@code hdm_} values the desktop obtains with its signed-in
 * session and hands to the local agent, so the agent can list accounts and folder metadata for the
 * virtual drive without holding a HyperDrive session token.
 *
 * <ul>
 *   <li>At most one live grant per device session: issuing a new one replaces the old one.</li>
 *   <li>Short-lived ({@link #TTL}); the desktop renews it while the drive is mounted.</li>
 *   <li>Valid only while its device session is live and the user is onboarded, so logout or
 *       session revocation ends it immediately.</li>
 *   <li>Only the SHA-256 hash is stored.</li>
 * </ul>
 */
@Service
@ConditionalOnAuthEnabled
public class MetadataGrantService {

    public static final Duration TTL = Duration.ofMinutes(15);

    public record Issued(String grant, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[expiresAt=" + expiresAt + "]";
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MetadataGrantService(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public Issued issue(UUID sessionId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TTL);
        String grant = SecureTokens.METADATA_GRANT.generate();
        tx.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM metadata_grants WHERE session_id = :s").param("s", sessionId).update();
            jdbc.sql("""
                    INSERT INTO metadata_grants (token_hash, session_id, created_at, expires_at)
                    VALUES (:h, :s, :now, :exp)
                    """)
                    .param("h", SecureTokens.hash(grant)).param("s", sessionId)
                    .param("now", utc(now)).param("exp", utc(expiresAt)).update();
        });
        return new Issued(grant, expiresAt);
    }

    /** Unmount, sign-out or agent restart: no grant of this session works afterwards. */
    public int revokeAll(UUID sessionId) {
        return jdbc.sql("DELETE FROM metadata_grants WHERE session_id = :s").param("s", sessionId).update();
    }

    public Optional<MetadataPrincipal> authenticate(String grant) {
        if (!SecureTokens.METADATA_GRANT.isWellFormed(grant)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT s.id AS session_id, s.user_id
                FROM metadata_grants g
                JOIN device_sessions s ON s.id = g.session_id
                JOIN users u ON u.id = s.user_id
                WHERE g.token_hash = :hash AND g.expires_at > :now
                  AND s.revoked_at IS NULL AND s.expires_at > :now
                  AND u.onboarding_completed_at IS NOT NULL
                """)
                .param("hash", SecureTokens.hash(grant))
                .param("now", utc(clock.instant()))
                .query((rs, n) -> new MetadataPrincipal(rs.getObject("user_id", UUID.class),
                        rs.getObject("session_id", UUID.class)))
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
