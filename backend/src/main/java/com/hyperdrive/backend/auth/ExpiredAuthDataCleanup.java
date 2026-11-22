package com.hyperdrive.backend.auth;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically removes auth rows that can no longer be used. Correctness never depends on it. */
@Component
@ConditionalOnAuthEnabled
public class ExpiredAuthDataCleanup {

    /** Keep finished sessions a while for diagnostics of replay/revocation before deleting. */
    private static final Duration RETAIN_ENDED_SESSIONS = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final Clock clock;

    public ExpiredAuthDataCleanup(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void purge() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.sql("DELETE FROM access_tokens WHERE expires_at <= :now").param("now", now).update();
        jdbc.sql("DELETE FROM login_transactions WHERE expires_at <= :cutoff")
                .param("cutoff", now.minusHours(1)).update();
        jdbc.sql("DELETE FROM storage_connection_transactions WHERE expires_at <= :cutoff")
                .param("cutoff", now.minusHours(1)).update();
        jdbc.sql("""
                DELETE FROM device_sessions
                WHERE (revoked_at IS NOT NULL AND revoked_at <= :cutoff) OR expires_at <= :cutoff
                """)
                .param("cutoff", now.minus(RETAIN_ENDED_SESSIONS)).update();
    }
}
