package com.hyperdrive.backend.auth.desktop;

import com.hyperdrive.backend.auth.Pkce;
import com.hyperdrive.backend.auth.SecureTokens;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Short-lived desktop login transactions. Every state change is a single conditional UPDATE, so
 * concurrent or repeated requests cannot move a transaction backwards or use it twice. No database
 * transaction is held open across the browser's round trip to Google.
 *
 * <pre>
 * PENDING --authorize (once)--> PENDING+browser_started_at
 *         --Google success-----> AUTHENTICATED (user + hashed handoff code, short expiry)
 *         --Google failure-----> FAILED
 * AUTHENTICATED --exchange(code)--> CONSUMED   (then PKCE verified; a wrong verifier burns the code)
 * </pre>
 */
@Service
@ConditionalOnAuthEnabled
public class LoginTransactionService {

    /** Fixed loopback callback path; clients choose only the port. */
    public static final String CALLBACK_PATH = "/callback";

    public record Created(UUID id, Instant expiresAt) {
    }

    /** Where to send the browser after Google: the validated loopback URL for this transaction. */
    public record Callback(int port, String desktopState) {
        public URI withParams(String name1, String value1) {
            return UriComponentsBuilder.newInstance()
                    .scheme("http").host("127.0.0.1").port(port).path(CALLBACK_PATH)
                    .queryParam(name1, value1)
                    .queryParam("state", desktopState)
                    .encode().build().toUri();
        }
    }

    public record Consumed(UUID userId, String deviceName) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuthProperties properties;

    public LoginTransactionService(JdbcClient jdbc, Clock clock, AuthProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    public Created create(String codeChallenge, int callbackPort, String desktopState, String deviceName) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.transactionTtl());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO login_transactions (id, code_challenge, callback_port, desktop_state, device_name,
                                                status, created_at, expires_at)
                VALUES (:id, :challenge, :port, :state, :deviceName, 'PENDING', :now, :expiresAt)
                """)
                .param("id", id)
                .param("challenge", codeChallenge)
                .param("port", callbackPort)
                .param("state", desktopState)
                .param("deviceName", deviceName)
                .param("now", utc(now))
                .param("expiresAt", utc(expiresAt))
                .update();
        return new Created(id, expiresAt);
    }

    /** Marks the browser leg as started. Succeeds once per transaction, only while pending and fresh. */
    public boolean startBrowserLogin(UUID id) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE login_transactions SET browser_started_at = :now
                WHERE id = :id AND status = 'PENDING' AND browser_started_at IS NULL AND expires_at > :now
                """)
                .param("now", utc(now)).param("id", id).update() == 1;
    }

    /**
     * Records a successful Google sign-in and returns the one-time handoff code plus the loopback
     * callback, or empty if the transaction is unknown, expired, not started, or already finished.
     */
    public Optional<HandoffIssued> completeBrowserLogin(UUID id, UUID userId) {
        Instant now = clock.instant();
        String code = SecureTokens.HANDOFF_CODE.generate();
        Instant handoffExpiresAt = now.plus(properties.handoffTtl());
        return jdbc.sql("""
                UPDATE login_transactions
                SET status = 'AUTHENTICATED', user_id = :userId, handoff_code_hash = :hash,
                    handoff_expires_at = LEAST(:handoffExpiresAt, expires_at)
                WHERE id = :id AND status = 'PENDING' AND browser_started_at IS NOT NULL AND expires_at > :now
                RETURNING callback_port, desktop_state
                """)
                .param("userId", userId)
                .param("hash", SecureTokens.hash(code))
                .param("handoffExpiresAt", utc(handoffExpiresAt))
                .param("id", id)
                .param("now", utc(now))
                .query((rs, n) -> new HandoffIssued(code,
                        new Callback(rs.getInt("callback_port"), rs.getString("desktop_state"))))
                .optional();
    }

    /** Marks a pending transaction failed; returns its callback so the desktop can be told. */
    public Optional<Callback> fail(UUID id) {
        return jdbc.sql("""
                UPDATE login_transactions SET status = 'FAILED'
                WHERE id = :id AND status = 'PENDING' AND expires_at > :now
                RETURNING callback_port, desktop_state
                """)
                .param("id", id).param("now", utc(clock.instant()))
                .query((rs, n) -> new Callback(rs.getInt("callback_port"), rs.getString("desktop_state")))
                .optional();
    }

    /**
     * Atomically consumes the handoff code, then verifies PKCE. The code is burned even if the
     * verifier is wrong, so it can never be retried or brute-forced.
     */
    public Optional<Consumed> consume(UUID id, String code, String codeVerifier) {
        if (!SecureTokens.HANDOFF_CODE.isWellFormed(code)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        record Row(UUID userId, String challenge, String deviceName) {
        }
        Optional<Row> row = jdbc.sql("""
                UPDATE login_transactions SET status = 'CONSUMED', consumed_at = :now
                WHERE id = :id AND status = 'AUTHENTICATED' AND handoff_code_hash = :hash
                  AND handoff_expires_at > :now
                RETURNING user_id, code_challenge, device_name
                """)
                .param("now", utc(now))
                .param("id", id)
                .param("hash", SecureTokens.hash(code))
                .query((rs, n) -> new Row(rs.getObject("user_id", UUID.class), rs.getString("code_challenge"),
                        rs.getString("device_name")))
                .optional();
        return row.filter(r -> Pkce.matches(codeVerifier, r.challenge()))
                .map(r -> new Consumed(r.userId(), r.deviceName()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record HandoffIssued(String code, Callback callback) {
        @Override
        public String toString() {
            return "HandoffIssued[callbackPort=" + callback.port() + "]";
        }
    }
}
