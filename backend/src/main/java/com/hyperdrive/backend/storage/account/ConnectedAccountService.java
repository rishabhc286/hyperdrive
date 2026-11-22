package com.hyperdrive.backend.storage.account;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.account.ConnectedAccount.Status;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth.ProviderIdentity;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Connected storage accounts. Every read and write is scoped by the owning HyperDrive user id from
 * the authenticated session; a bare account id is never enough.
 */
@Service
@ConditionalOnAuthEnabled
public class ConnectedAccountService {

    /** Internal view used by browsing: includes the provider's root folder id. */
    public record OwnedAccount(UUID id, UUID userId, StorageProvider provider, Status status, String rootFolderId) {
    }

    public record Upserted(UUID accountId, boolean reconnected) {
    }

    private static final String COLUMNS = """
            id, provider, display_name, provider_email, status, granted_scopes, connected_at, updated_at,
            last_success_at, last_error_code
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ConnectedAccountService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<ConnectedAccount> list(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connected_accounts WHERE user_id = :userId ORDER BY connected_at, id")
                .param("userId", userId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<ConnectedAccount> find(UUID userId, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connected_accounts WHERE id = :id AND user_id = :userId")
                .param("id", accountId).param("userId", userId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<OwnedAccount> findOwned(UUID userId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, user_id, provider, status, root_folder_id FROM connected_accounts
                WHERE id = :id AND user_id = :userId
                """)
                .param("id", accountId).param("userId", userId)
                .query((rs, n) -> new OwnedAccount(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        StorageProvider.valueOf(rs.getString("provider")), Status.valueOf(rs.getString("status")),
                        rs.getString("root_folder_id")))
                .optional();
    }

    /**
     * Creates the connection, or updates the user's existing connection to the same provider
     * account (reconnect). Must run inside the caller's transaction together with the credentials.
     */
    public Upserted upsert(UUID userId, StorageProvider provider, ProviderIdentity identity, String rootFolderId,
            Set<String> grantedScopes) {
        OffsetDateTime now = utc(clock.instant());
        return jdbc.sql("""
                INSERT INTO connected_accounts (id, user_id, provider, provider_issuer, provider_account_id, display_name,
                                                provider_email, root_folder_id, status, granted_scopes, connected_at,
                                                updated_at, last_success_at)
                VALUES (:id, :userId, :provider, :issuer, :subject, :name, :email, :root, 'CONNECTED', :scopes, :now,
                        :now, :now)
                ON CONFLICT (user_id, provider, provider_account_id) DO UPDATE
                SET display_name = EXCLUDED.display_name, provider_email = EXCLUDED.provider_email,
                    provider_issuer = EXCLUDED.provider_issuer, root_folder_id = EXCLUDED.root_folder_id,
                    status = 'CONNECTED', granted_scopes = EXCLUDED.granted_scopes, updated_at = EXCLUDED.updated_at,
                    last_success_at = EXCLUDED.last_success_at, last_error_code = NULL
                RETURNING id, (xmax <> 0) AS reconnected
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("provider", provider.name())
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .param("name", identity.displayName())
                .param("email", identity.email())
                .param("root", rootFolderId)
                .param("scopes", String.join(" ", new TreeSet<>(grantedScopes)))
                .param("now", now)
                .query((rs, n) -> new Upserted(rs.getObject("id", UUID.class), rs.getBoolean("reconnected")))
                .single();
    }

    /**
     * Removes HyperDrive's link to the storage account and, by cascade, its stored credentials.
     * Does not touch the HyperDrive user, the remote files, or the grant at the provider.
     */
    public boolean disconnect(UUID userId, UUID accountId) {
        return jdbc.sql("DELETE FROM connected_accounts WHERE id = :id AND user_id = :userId")
                .param("id", accountId).param("userId", userId).update() == 1;
    }

    public void markNeedsReconnect(UUID accountId, String errorCode) {
        jdbc.sql("""
                UPDATE connected_accounts SET status = 'NEEDS_RECONNECT', last_error_code = :code, updated_at = :now
                WHERE id = :id
                """)
                .param("code", errorCode).param("now", utc(clock.instant())).param("id", accountId).update();
    }

    public void recordError(UUID accountId, String errorCode) {
        jdbc.sql("UPDATE connected_accounts SET last_error_code = :code WHERE id = :id")
                .param("code", errorCode).param("id", accountId).update();
    }

    /** Throttled to one write per minute per account. */
    public void recordSuccess(UUID accountId) {
        OffsetDateTime now = utc(clock.instant());
        jdbc.sql("""
                UPDATE connected_accounts SET last_success_at = :now, last_error_code = NULL
                WHERE id = :id AND status = 'CONNECTED'
                  AND (last_success_at IS NULL OR last_success_at < :threshold OR last_error_code IS NOT NULL)
                """)
                .param("now", now).param("threshold", now.minusMinutes(1)).param("id", accountId).update();
    }

    private static ConnectedAccount map(ResultSet rs) throws SQLException {
        String scopes = rs.getString("granted_scopes");
        return new ConnectedAccount(
                rs.getObject("id", UUID.class),
                StorageProvider.valueOf(rs.getString("provider")),
                rs.getString("display_name"),
                rs.getString("provider_email"),
                Status.valueOf(rs.getString("status")),
                scopes.isBlank() ? List.of() : Arrays.asList(scopes.split(" ")),
                instant(rs, "connected_at"),
                instant(rs, "updated_at"),
                instant(rs, "last_success_at"),
                rs.getString("last_error_code"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
