package com.hyperdrive.backend.user;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.web.ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnAuthEnabled
public class UserService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public UserService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Returns the HyperDrive user for a validated identity, creating the account on first sign-in.
     * Concurrency-safe: the unique (issuer, subject) constraint decides the winner; a losing
     * transaction discards its tentative user and adopts the winner's.
     */
    @Transactional
    public UUID findOrCreate(ExternalIdentity identity) {
        OffsetDateTime now = now();
        Optional<UUID> existing = findUserId(identity);
        if (existing.isPresent()) {
            refreshProfile(existing.get(), identity, now);
            return existing.get();
        }

        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO users (id, display_name, contact_email, created_at, updated_at)
                VALUES (:id, :displayName, :email, :now, :now)
                """)
                .param("id", userId)
                .param("displayName", identity.displayName())
                .param("email", identity.verifiedEmail())
                .param("now", now)
                .update();
        int inserted = jdbc.sql("""
                INSERT INTO auth_identities (id, user_id, issuer, subject, created_at)
                VALUES (:id, :userId, :issuer, :subject, :now)
                ON CONFLICT (issuer, subject) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .param("now", now)
                .update();
        if (inserted == 1) {
            return userId;
        }
        // Lost a race with a concurrent first sign-in for the same identity.
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
        return findUserId(identity).orElseThrow(() -> new IllegalStateException("identity vanished during sign-in"));
    }

    public Optional<UserAccount> find(UUID userId) {
        return jdbc.sql("""
                SELECT id, username, display_name, onboarding_completed_at IS NOT NULL AS onboarded
                FROM users WHERE id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new UserAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getBoolean("onboarded")))
                .optional();
    }

    /**
     * Sets the username once. Uniqueness is enforced by the database constraint (not only a
     * pre-check), so concurrent attempts for the same name cannot both succeed.
     */
    @Transactional
    public UserAccount completeOnboarding(UUID userId, String username) {
        Optional<UsernamePolicy.Violation> violation = UsernamePolicy.check(username);
        if (violation.isPresent()) {
            throw switch (violation.get()) {
                case INVALID_FORMAT -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_username",
                        "Usernames are 3–24 characters: letters, numbers and underscores.");
                case RESERVED -> new ApiException(HttpStatus.CONFLICT, "username_reserved",
                        "That username is reserved. Choose another.");
            };
        }
        OffsetDateTime now = now();
        int updated;
        try {
            updated = jdbc.sql("""
                    UPDATE users
                    SET username = :username, username_normalized = :normalized,
                        onboarding_completed_at = :now, updated_at = :now, version = version + 1
                    WHERE id = :id AND username IS NULL
                    """)
                    .param("username", username)
                    .param("normalized", UsernamePolicy.normalize(username))
                    .param("now", now)
                    .param("id", userId)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken", "That username is already taken.");
        }
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "onboarding_already_complete",
                    "Your username has already been set.");
        }
        return find(userId).orElseThrow();
    }

    private Optional<UUID> findUserId(ExternalIdentity identity) {
        return jdbc.sql("SELECT user_id FROM auth_identities WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query(UUID.class)
                .optional();
    }

    /** Keeps display name / email current when the provider sends them; never clears them. */
    private void refreshProfile(UUID userId, ExternalIdentity identity, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE users
                SET display_name = COALESCE(:displayName, display_name),
                    contact_email = COALESCE(:email, contact_email),
                    updated_at = :now
                WHERE id = :id
                """)
                .param("displayName", identity.displayName())
                .param("email", identity.verifiedEmail())
                .param("now", now)
                .param("id", userId)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

}
