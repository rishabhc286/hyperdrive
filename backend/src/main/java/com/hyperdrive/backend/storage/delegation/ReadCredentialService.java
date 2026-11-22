package com.hyperdrive.backend.storage.delegation;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.account.ConnectedAccount.Status;
import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.account.ConnectedAccountService.OwnedAccount;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore.ReconnectRequiredException;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Delegates a provider <em>access</em> token for one owned connected account to the caller's
 * desktop, which hands it to its local agent for direct provider reads (ADR 0002).
 *
 * <p>Order: ownership (a foreign or unknown account id gets the same 404 and causes no provider
 * call) → connection status → content-read scope → a token valid for at least
 * {@link #MIN_VALIDITY}, refreshing through the existing serialized refresh if needed. Refresh
 * tokens never leave this service. Issuance is audited without the token value.
 *
 * <p>Honest limits: the provider token is not file-scoped (it authorizes whatever the grant allows,
 * e.g. all of a Drive for drive.readonly), and it can't be recalled from the provider before it
 * expires even if the user logs out or disconnects.
 */
@Service
@ConditionalOnAuthEnabled
public class ReadCredentialService {

    /** A delegated token must remain usable at least this long; otherwise it is refreshed first. */
    static final Duration MIN_VALIDITY = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ReadCredentialService.class);

    /**
     * What the desktop receives. Only fields the agent needs: no refresh token, no scopes, no
     * provider account id. {@link #toString()} is redacted.
     *
     * @param rootNamespaceId Dropbox only (sent as Dropbox-API-Path-Root by the agent); null for Drive
     */
    public record ReadCredential(UUID accountId, StorageProvider provider, String accessToken, Instant accessTokenExpiresAt,
            String rootNamespaceId, Instant issuedAt) {
        @Override
        public String toString() {
            return "ReadCredential[accountId=" + accountId + ", provider=" + provider + ", accessToken=redacted]";
        }
    }

    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final Clock clock;

    public ReadCredentialService(ConnectedAccountService accounts, ProviderCredentialStore credentials, Clock clock) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.clock = clock;
    }

    public ReadCredential issue(UUID userId, UUID sessionId, UUID accountId) {
        OwnedAccount account = accounts.findOwned(userId, accountId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "account_not_found", "Connected account not found."));
        if (account.status() == Status.NEEDS_RECONNECT) {
            throw needsReconnect();
        }
        if (!account.canReadContent()) {
            // Listing permission does not imply content permission; don't pretend it does.
            throw new ApiException(HttpStatus.CONFLICT, "content_permission_missing",
                    "This account was connected without permission to read file contents. Reconnect it to allow reads.");
        }
        ProviderCredentialStore.ValidToken token;
        try {
            token = credentials.accessToken(account.provider(), account.id(), MIN_VALIDITY);
        } catch (ReconnectRequiredException e) {
            accounts.markNeedsReconnect(account.id(), e.code());
            throw needsReconnect();
        } catch (CredentialCipher.DecryptionException e) {
            log.error("Stored credentials of account {} could not be decrypted: {}", account.id(), e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "credentials_unreadable",
                    "Stored credentials for this account can't be read. Reconnect the account.");
        } catch (ProviderException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "provider_unavailable",
                    "The storage provider could not be reached. Try again.");
        }
        Instant now = clock.instant();
        // Audit: who, which account, which device session, until when. Never the token.
        log.info("Issued read credential: user={} session={} account={} provider={} providerExpiresAt={}",
                userId, sessionId, account.id(), account.provider(), token.expiresAt());
        return new ReadCredential(account.id(), account.provider(), token.token().value(), token.expiresAt(),
                account.provider() == StorageProvider.DROPBOX ? account.root().rootNamespaceId() : null, now);
    }

    private static ApiException needsReconnect() {
        return new ApiException(HttpStatus.CONFLICT, "account_needs_reconnect",
                "This storage account needs to be connected again.");
    }
}
