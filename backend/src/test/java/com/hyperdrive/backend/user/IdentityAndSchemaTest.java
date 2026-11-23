package com.hyperdrive.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.hyperdrive.backend.support.AuthIntegrationTest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class IdentityAndSchemaTest extends AuthIntegrationTest {

    @Autowired
    UserService users;
    @Autowired
    Flyway flyway;

    @Test
    void concurrentFirstSignInsForOneIdentityCreateExactlyOneUser() throws Exception {
        ExternalIdentity identity = new ExternalIdentity("https://accounts.google.com", "concurrent-sub", "C", null);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return users.findOrCreate(identity);
                }));
            }
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> f : results) {
                ids.add(f.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(count("users")).isEqualTo(1);
            assertThat(count("auth_identities")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameSubjectFromADifferentIssuerIsADifferentIdentity() {
        UUID google = users.findOrCreate(new ExternalIdentity("https://accounts.google.com", "123", null, null));
        UUID other = users.findOrCreate(new ExternalIdentity("https://other-issuer.example", "123", null, null));
        assertThat(google).isNotEqualTo(other);
    }

    @Test
    void returningSignInRefreshesButNeverClearsProfileFields() {
        UUID id = users.findOrCreate(new ExternalIdentity("https://i", "s", "Old Name", "old@example.com"));
        users.findOrCreate(new ExternalIdentity("https://i", "s", null, null));
        assertThat(users.find(id).orElseThrow().displayName()).isEqualTo("Old Name");
        users.findOrCreate(new ExternalIdentity("https://i", "s", "New Name", null));
        assertThat(users.find(id).orElseThrow().displayName()).isEqualTo("New Name");
    }

    @Test
    void databaseEnforcesUniqueIssuerAndSubject() {
        UUID a = users.findOrCreate(new ExternalIdentity("https://i", "dup", null, null));
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO auth_identities (id, user_id, issuer, subject, created_at)
                VALUES (:id, :u, 'https://i', 'dup', now())
                """).param("id", UUID.randomUUID()).param("u", a).update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void databaseEnforcesUsernameRules() {
        UUID id = users.findOrCreate(new ExternalIdentity("https://i", "rules", null, null));
        OffsetDateTime now = OffsetDateTime.now();

        assertThatThrownBy(() -> setUsername(id, "bad name", "bad name", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setUsername(id, "Mixed", "Mixed", now))   // normalized must be lower(username)
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setUsername(id, "Valid_Name", "valid_name", null)) // onboarding timestamp required
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesCaseInsensitiveUsernameUniqueness() {
        UUID a = users.findOrCreate(new ExternalIdentity("https://i", "u1", null, null));
        UUID b = users.findOrCreate(new ExternalIdentity("https://i", "u2", null, null));
        setUsername(a, "SameName", "samename", OffsetDateTime.now());
        assertThatThrownBy(() -> setUsername(b, "samename", "samename", OffsetDateTime.now()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void databaseRejectsInvalidLoginTransactions() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO login_transactions (id, code_challenge, callback_port, desktop_state, status, created_at, expires_at)
                VALUES (:id, :c, 80, :s, 'PENDING', now(), now())
                """).param("id", UUID.randomUUID()).param("c", "a".repeat(43)).param("s", "s".repeat(32)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO login_transactions (id, code_challenge, callback_port, desktop_state, status, created_at, expires_at)
                VALUES (:id, :c, 50000, :s, 'AUTHENTICATED', now(), now())
                """).param("id", UUID.randomUUID()).param("c", "a".repeat(43)).param("s", "s".repeat(32)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationsAreAppliedAndValid() {
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3", "4");
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
    }

    @Test
    void readinessReportsAuthEnabledAndDatabaseUp() throws Exception {
        String body = mvc.perform(get("/api/v1/ready")).andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"status\":\"READY\",\"auth\":\"ENABLED\"}");
    }

    private void setUsername(UUID id, String username, String normalized, OffsetDateTime onboardedAt) {
        jdbc.sql("""
                UPDATE users SET username = :u, username_normalized = :n, onboarding_completed_at = :t WHERE id = :id
                """).param("u", username).param("n", normalized).param("t", onboardedAt).param("id", id).update();
    }
}
