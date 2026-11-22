package com.hyperdrive.backend.storage.account;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.connect.StorageOAuth;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.ProviderException.Category;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Encrypted provider credentials and server-side access-token refresh.
 *
 * <p>Refresh is serialized per connected account with a PostgreSQL row lock ({@code SELECT ... FOR
 * UPDATE}), so concurrent requests (also across backend instances sharing the database) cause one
 * refresh; the others wait and reuse the new token. A refresh that fails never erases stored
 * credentials. When Google omits a new refresh token, the existing one is kept.
 */
@Service
@ConditionalOnAuthEnabled
public class ProviderCredentialStore {

    /** Refresh this long before Google's stated expiry. */
    static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);
    /** Google access tokens without expires_in are assumed to last this long (Google sends 3599 s). */
    private static final Duration DEFAULT_ACCESS_TTL = Duration.ofMinutes(55);

    /** Only a new consent can fix this; the account is marked NEEDS_RECONNECT by the caller. */
    public static final class ReconnectRequiredException extends RuntimeException {
        private final String code;

        ReconnectRequiredException(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record Row(byte[] access, byte[] refresh, Instant accessExpiresAt, int keyVersion) {
    }

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialStore.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final CredentialCipher cipher;
    private final Map<StorageProvider, StorageOAuth> oauth;

    public ProviderCredentialStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock, CredentialCipher cipher,
            List<StorageOAuth> oauthClients) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.cipher = cipher;
        this.oauth = oauthClients.stream().collect(Collectors.toUnmodifiableMap(StorageOAuth::provider, o -> o));
    }

    /**
     * Stores credentials from a new grant. On reconnect, keeps the previously stored refresh token
     * if Google did not issue a new one. Must run inside the caller's transaction.
     */
    public void saveGrant(UUID accountId, OAuth2AccessTokenResponse tokens) {
        String refresh = Optional.ofNullable(tokens.getRefreshToken()).map(OAuth2RefreshToken::getTokenValue)
                .orElseGet(() -> existingRefreshToken(accountId));
        write(accountId, tokens.getAccessToken().getTokenValue(), expiry(tokens), refresh, true);
    }

    /** A usable access token, refreshing first if it is missing or about to expire. */
    public ProviderAccessToken accessToken(StorageProvider provider, UUID accountId) {
        return accessToken(provider, accountId, EXPIRY_SKEW).token();
    }

    /** An access token together with the provider-stated expiry recorded for it. */
    public record ValidToken(ProviderAccessToken token, Instant expiresAt) {
        @Override
        public String toString() {
            return "ValidToken[expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * A token that stays valid for at least {@code minValidity} (refreshing first otherwise), e.g. for
     * delegation to the local agent, which can't refresh by itself.
     */
    public ValidToken accessToken(StorageProvider provider, UUID accountId, Duration minValidity) {
        Row row = load(accountId, false).orElseThrow(() -> new ReconnectRequiredException("credentials_missing"));
        if (row.accessExpiresAt().minus(minValidity).isAfter(clock.instant())) {
            return new ValidToken(new ProviderAccessToken(decrypt(accountId, row.access(), row.keyVersion(), "access")),
                    row.accessExpiresAt());
        }
        ProviderAccessToken token = refresh(provider, accountId, null, minValidity);
        Row after = load(accountId, false).orElseThrow(() -> new ReconnectRequiredException("credentials_missing"));
        return new ValidToken(token, after.accessExpiresAt());
    }

    /**
     * Refreshes under a row lock. If another request already replaced {@code rejected} (or the stored
     * token is still fresh and was not rejected), returns that token without calling Google.
     */
    public ProviderAccessToken refresh(StorageProvider provider, UUID accountId, ProviderAccessToken rejected) {
        return refresh(provider, accountId, rejected, EXPIRY_SKEW);
    }

    private ProviderAccessToken refresh(StorageProvider provider, UUID accountId, ProviderAccessToken rejected,
            Duration minValidity) {
        StorageOAuth client = oauth.get(provider);
        return tx.execute(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '20s'").update();
            Row row = load(accountId, true).orElseThrow(() -> new ReconnectRequiredException("credentials_missing"));
            String current = decrypt(accountId, row.access(), row.keyVersion(), "access");
            boolean fresh = row.accessExpiresAt().minus(minValidity).isAfter(clock.instant());
            if (fresh && (rejected == null || !rejected.value().equals(current))) {
                return new ProviderAccessToken(current);
            }
            if (row.refresh() == null) {
                throw new ReconnectRequiredException("no_refresh_token");
            }
            if (client == null) {
                // e.g. Dropbox app credentials were removed from the server configuration.
                throw new ProviderException(Category.UNAVAILABLE, "Provider " + provider + " is not configured");
            }
            String refreshToken = decrypt(accountId, row.refresh(), row.keyVersion(), "refresh");
            OAuth2AccessTokenResponse response;
            try {
                response = client.refresh(current, row.accessExpiresAt(), refreshToken);
            } catch (OAuth2AuthorizationException e) {
                String error = e.getError().getErrorCode();
                log.info("Provider refresh rejected for account {}: {}", accountId, error);
                if ("invalid_grant".equals(error) || "unauthorized_client".equals(error)) {
                    throw new ReconnectRequiredException("refresh_revoked");
                }
                throw new ProviderException(Category.UNAVAILABLE, "Token refresh failed: " + error);
            } catch (RestClientException e) {
                throw new ProviderException(Category.UNAVAILABLE, "Token endpoint could not be reached");
            }
            String nextRefresh = response.getRefreshToken() != null
                    ? response.getRefreshToken().getTokenValue() : refreshToken;
            write(accountId, response.getAccessToken().getTokenValue(), expiry(response), nextRefresh, false);
            return new ProviderAccessToken(response.getAccessToken().getTokenValue());
        });
    }

    private String existingRefreshToken(UUID accountId) {
        return load(accountId, false)
                .filter(r -> r.refresh() != null)
                .map(r -> decryptOrNull(accountId, r))
                .orElse(null);
    }

    private String decryptOrNull(UUID accountId, Row row) {
        try {
            return decrypt(accountId, row.refresh(), row.keyVersion(), "refresh");
        } catch (CredentialCipher.DecryptionException e) {
            return null; // Unreadable old value: the new grant replaces it.
        }
    }

    /** Both values are (re-)encrypted with the current key so a row never mixes key versions. */
    private void write(UUID accountId, String accessToken, Instant expiresAt, String refreshToken, boolean upsert) {
        CredentialCipher.Sealed access = cipher.encrypt(accessToken, context(accountId, "access"));
        CredentialCipher.Sealed refresh = refreshToken == null ? null : cipher.encrypt(refreshToken, context(accountId, "refresh"));
        OffsetDateTime now = utc(clock.instant());
        String sql = upsert
                ? """
                  INSERT INTO provider_credentials (connected_account_id, encrypted_access_token, encrypted_refresh_token,
                                                    access_token_expires_at, encryption_key_version, version, updated_at)
                  VALUES (:id, :access, :refresh, :expiresAt, :keyVersion, 0, :now)
                  ON CONFLICT (connected_account_id) DO UPDATE
                  SET encrypted_access_token = EXCLUDED.encrypted_access_token,
                      encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                      access_token_expires_at = EXCLUDED.access_token_expires_at,
                      encryption_key_version = EXCLUDED.encryption_key_version,
                      version = provider_credentials.version + 1, updated_at = EXCLUDED.updated_at
                  """
                : """
                  UPDATE provider_credentials
                  SET encrypted_access_token = :access, encrypted_refresh_token = :refresh,
                      access_token_expires_at = :expiresAt, encryption_key_version = :keyVersion,
                      version = version + 1, updated_at = :now
                  WHERE connected_account_id = :id
                  """;
        jdbc.sql(sql)
                .param("id", accountId)
                .param("access", access.value())
                .param("refresh", refresh == null ? null : refresh.value())
                .param("expiresAt", utc(expiresAt))
                .param("keyVersion", access.keyVersion())
                .param("now", now)
                .update();
    }

    private Optional<Row> load(UUID accountId, boolean forUpdate) {
        return jdbc.sql("""
                SELECT encrypted_access_token, encrypted_refresh_token, access_token_expires_at, encryption_key_version
                FROM provider_credentials WHERE connected_account_id = :id
                """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", accountId)
                .query((rs, n) -> new Row(rs.getBytes("encrypted_access_token"), rs.getBytes("encrypted_refresh_token"),
                        rs.getObject("access_token_expires_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("encryption_key_version")))
                .optional();
    }

    private String decrypt(UUID accountId, byte[] value, int keyVersion, String field) {
        return cipher.decrypt(value, keyVersion, context(accountId, field));
    }

    static String context(UUID accountId, String field) {
        return "provider_credentials:" + accountId + ":" + field;
    }

    private Instant expiry(OAuth2AccessTokenResponse tokens) {
        Instant expiresAt = tokens.getAccessToken().getExpiresAt();
        return expiresAt != null ? expiresAt : clock.instant().plus(DEFAULT_ACCESS_TTL);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
