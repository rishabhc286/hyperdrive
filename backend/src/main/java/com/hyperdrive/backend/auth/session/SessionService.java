package com.hyperdrive.backend.auth.session;

import com.hyperdrive.backend.auth.SecureTokens;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device sessions and their opaque tokens.
 *
 * <ul>
 *   <li>Access tokens: random, 15 min, stored hashed, deleted on rotation and on logout.</li>
 *   <li>Refresh tokens: random, rotated on every use with one atomic conditional UPDATE. The previous
 *       hash is recorded; presenting it again is treated as theft and revokes the whole session.</li>
 * </ul>
 */
@Service
@ConditionalOnAuthEnabled
public class SessionService {

    public enum RefreshOutcome { ROTATED, INVALID, REPLAY_DETECTED }

    public record RefreshResult(RefreshOutcome outcome, TokenResponse tokens) {
    }

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AuthProperties properties;

    public SessionService(JdbcClient jdbc, TransactionTemplate tx, Clock clock, AuthProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
    }

    public TokenResponse createSession(UUID userId, String deviceName) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.sessionTtl());
            UUID sessionId = UUID.randomUUID();
            String refreshToken = SecureTokens.REFRESH_TOKEN.generate();
            jdbc.sql("""
                    INSERT INTO device_sessions (id, user_id, device_name, current_refresh_token_hash, token_family_id,
                                                 created_at, last_used_at, expires_at)
                    VALUES (:id, :userId, :deviceName, :refreshHash, :familyId, :now, :now, :expiresAt)
                    """)
                    .param("id", sessionId)
                    .param("userId", userId)
                    .param("deviceName", deviceName)
                    .param("refreshHash", SecureTokens.hash(refreshToken))
                    .param("familyId", UUID.randomUUID())
                    .param("now", utc(now))
                    .param("expiresAt", utc(expiresAt))
                    .update();
            return issueAccessToken(sessionId, now, refreshToken, expiresAt);
        });
    }

    public RefreshResult refresh(String refreshToken) {
        if (!SecureTokens.REFRESH_TOKEN.isWellFormed(refreshToken)) {
            return new RefreshResult(RefreshOutcome.INVALID, null);
        }
        byte[] presentedHash = SecureTokens.hash(refreshToken);
        return tx.execute(status -> {
            Instant now = clock.instant();
            String nextRefreshToken = SecureTokens.REFRESH_TOKEN.generate();
            // Atomic rotation: the row lock + re-checked WHERE clause means exactly one concurrent
            // caller can win for a given current hash.
            Optional<SessionRow> rotated = jdbc.sql("""
                    UPDATE device_sessions
                    SET current_refresh_token_hash = :nextHash, last_used_at = :now
                    WHERE current_refresh_token_hash = :presentedHash
                      AND revoked_at IS NULL AND expires_at > :now
                    RETURNING id, expires_at
                    """)
                    .param("nextHash", SecureTokens.hash(nextRefreshToken))
                    .param("now", utc(now))
                    .param("presentedHash", presentedHash)
                    .query((rs, n) -> new SessionRow(rs.getObject("id", UUID.class),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                    .optional();

            if (rotated.isPresent()) {
                UUID sessionId = rotated.get().id();
                jdbc.sql("INSERT INTO superseded_refresh_tokens (token_hash, session_id, superseded_at) VALUES (:h, :s, :now)")
                        .param("h", presentedHash).param("s", sessionId).param("now", utc(now)).update();
                jdbc.sql("DELETE FROM access_tokens WHERE session_id = :s").param("s", sessionId).update();
                return new RefreshResult(RefreshOutcome.ROTATED,
                        issueAccessToken(sessionId, now, nextRefreshToken, rotated.get().expiresAt()));
            }

            Optional<UUID> replayedSession = jdbc.sql("SELECT session_id FROM superseded_refresh_tokens WHERE token_hash = :h")
                    .param("h", presentedHash).query(UUID.class).optional();
            if (replayedSession.isPresent()) {
                revoke(replayedSession.get(), "refresh_reuse", now);
                log.warn("Refresh token reuse detected; session {} revoked", replayedSession.get());
                return new RefreshResult(RefreshOutcome.REPLAY_DETECTED, null);
            }
            return new RefreshResult(RefreshOutcome.INVALID, null);
        });
    }

    public void logout(UUID sessionId) {
        tx.executeWithoutResult(status -> revoke(sessionId, "logout", clock.instant()));
    }

    /** Resolves a bearer access token to its live session, or empty if invalid/expired/revoked. */
    public Optional<SessionPrincipal> authenticate(String accessToken) {
        if (!SecureTokens.ACCESS_TOKEN.isWellFormed(accessToken)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT s.id AS session_id, s.user_id, u.onboarding_completed_at IS NOT NULL AS onboarded
                FROM access_tokens a
                JOIN device_sessions s ON s.id = a.session_id
                JOIN users u ON u.id = s.user_id
                WHERE a.token_hash = :hash AND a.expires_at > :now
                  AND s.revoked_at IS NULL AND s.expires_at > :now
                """)
                .param("hash", SecureTokens.hash(accessToken))
                .param("now", utc(clock.instant()))
                .query((rs, n) -> new SessionPrincipal(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getBoolean("onboarded")))
                .optional();
    }

    private void revoke(UUID sessionId, String reason, Instant now) {
        jdbc.sql("""
                UPDATE device_sessions
                SET revoked_at = :now, revoked_reason = :reason, current_refresh_token_hash = NULL
                WHERE id = :id AND revoked_at IS NULL
                """)
                .param("now", utc(now)).param("reason", reason).param("id", sessionId).update();
        jdbc.sql("DELETE FROM access_tokens WHERE session_id = :id").param("id", sessionId).update();
    }

    private TokenResponse issueAccessToken(UUID sessionId, Instant now, String refreshToken, Instant refreshExpiresAt) {
        String accessToken = SecureTokens.ACCESS_TOKEN.generate();
        Instant accessExpiresAt = now.plus(properties.accessTokenTtl());
        if (accessExpiresAt.isAfter(refreshExpiresAt)) {
            accessExpiresAt = refreshExpiresAt;
        }
        jdbc.sql("INSERT INTO access_tokens (token_hash, session_id, created_at, expires_at) VALUES (:h, :s, :now, :exp)")
                .param("h", SecureTokens.hash(accessToken))
                .param("s", sessionId)
                .param("now", utc(now))
                .param("exp", utc(accessExpiresAt))
                .update();
        return new TokenResponse("Bearer", accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, null);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record SessionRow(UUID id, Instant expiresAt) {
    }
}
