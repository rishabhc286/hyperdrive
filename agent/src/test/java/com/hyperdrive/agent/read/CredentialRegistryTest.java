package com.hyperdrive.agent.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyperdrive.agent.read.CredentialRegistry.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Provider expiry vs. local retention, bounds and redaction of the in-memory credential store. */
class CredentialRegistryTest {

    private static final String TOKEN = "ya29.registry-test-token-0123456789";

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void retentionIsCappedAndNeverOutlivesTheProviderExpiry() throws Exception {
        MovableClock clock = new MovableClock();
        CredentialRegistry registry = new CredentialRegistry(clock);

        var longLived = registry.install(Provider.GOOGLE_DRIVE, UUID.randomUUID(), TOKEN, clock.now.plus(Duration.ofHours(1)), null);
        assertThat(longLived.retentionExpiresAt()).isEqualTo(clock.now.plus(CredentialRegistry.MAX_RETENTION));
        var shortLived = registry.install(Provider.GOOGLE_DRIVE, UUID.randomUUID(), TOKEN, clock.now.plus(Duration.ofMinutes(3)), null);
        assertThat(shortLived.retentionExpiresAt()).isEqualTo(clock.now.plus(Duration.ofMinutes(3)));

        clock.now = clock.now.plus(Duration.ofMinutes(3));
        assertThatThrownBy(() -> registry.get(shortLived.handle()))
                .isInstanceOfSatisfying(ReadException.class, e -> assertThat(e.code()).isEqualTo("credential_expired"));
        // Removed after expiry: a second lookup no longer knows it.
        assertThatThrownBy(() -> registry.get(shortLived.handle()))
                .isInstanceOfSatisfying(ReadException.class, e -> assertThat(e.code()).isEqualTo("unknown_credential"));
        assertThat(registry.get(longLived.handle())).isNotNull();

        clock.now = clock.now.plus(CredentialRegistry.MAX_RETENTION);
        assertThatThrownBy(() -> registry.get(longLived.handle())).isInstanceOf(ReadException.class);
        assertThat(registry.size()).isZero();
    }

    @Test
    void theNumberOfCredentialsIsBounded() throws Exception {
        MovableClock clock = new MovableClock();
        CredentialRegistry registry = new CredentialRegistry(clock);
        for (int i = 0; i < CredentialRegistry.MAX_CREDENTIALS; i++) {
            registry.install(Provider.GOOGLE_DRIVE, UUID.randomUUID(), TOKEN, clock.now.plusSeconds(600), null);
        }
        assertThatThrownBy(() -> registry.install(Provider.GOOGLE_DRIVE, UUID.randomUUID(), TOKEN, clock.now.plusSeconds(600), null))
                .isInstanceOfSatisfying(ReadException.class, e -> assertThat(e.code()).isEqualTo("too_many_credentials"));
        assertThat(registry.clear(null)).isEqualTo(CredentialRegistry.MAX_CREDENTIALS);
    }

    @Test
    void credentialsAreRedactedAndDropboxNeedsANumericNamespace() throws Exception {
        MovableClock clock = new MovableClock();
        CredentialRegistry registry = new CredentialRegistry(clock);
        var installed = registry.install(Provider.DROPBOX, UUID.randomUUID(), TOKEN, clock.now.plusSeconds(600), "42");
        assertThat(registry.get(installed.handle()).toString()).doesNotContain(TOKEN);
        assertThatThrownBy(() -> registry.install(Provider.DROPBOX, UUID.randomUUID(), TOKEN, clock.now.plusSeconds(600), "x\"}"))
                .isInstanceOf(ReadException.class);
        assertThatThrownBy(() -> registry.install(Provider.GOOGLE_DRIVE, UUID.randomUUID(), TOKEN, clock.now.plusSeconds(600), "42"))
                .isInstanceOf(ReadException.class);
    }
}
