package com.hyperdrive.backend.storage.account;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.account.ConnectedAccountService.OwnedAccount;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.storage.provider.dropbox.DropboxBrowser;
import com.hyperdrive.backend.storage.provider.dropbox.DropboxBrowser.RevokeOutcome;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * "Revoke provider access and disconnect" (verification checkpoint before Batch 8). Separate from
 * ordinary Disconnect, which only unlinks locally.
 *
 * <p><b>Dropbox only.</b> {@code auth/token/revoke} is documented to disable the calling access
 * token, its refresh token and that refresh token's other access tokens: the grant behind this one
 * connection. Other connections hold their own refresh tokens.
 *
 * <p><b>Google Drive is refused</b> ({@code revocation_unsupported}, nothing changed): Google
 * documents that revocation removes all scopes the user granted to the Cloud project, for every
 * client in it. With HyperDrive's default configuration (one client for sign-in and Drive) that is
 * not limited to one connection. See docs/google-drive.md.
 *
 * <p>Order: revoke first (it needs the stored credential), then always disconnect locally, so a
 * failed revocation never leaves credentials behind for a later retry.
 */
@Service
@ConditionalOnAuthEnabled
public class ProviderRevocationService {

    public enum Outcome { CONFIRMED, FAILED, UNKNOWN }

    /** {@code reason} is a fixed machine code, never provider text. */
    public record Result(Outcome outcome, String reason, boolean disconnected) {
    }

    /** Thrown for providers whose revocation can't be limited to one connection. */
    public static final class UnsupportedProviderException extends RuntimeException {
        UnsupportedProviderException() {
            super("revocation_unsupported", null, false, false);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ProviderRevocationService.class);

    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final ObjectProvider<DropboxBrowser> dropbox;

    public ProviderRevocationService(ConnectedAccountService accounts, ProviderCredentialStore credentials,
            ObjectProvider<DropboxBrowser> dropbox) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.dropbox = dropbox;
    }

    /** Empty if the account doesn't exist for this user. */
    public Optional<Result> revokeAndDisconnect(UUID userId, UUID accountId) {
        Optional<OwnedAccount> owned = accounts.findOwned(userId, accountId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        if (owned.get().provider() != StorageProvider.DROPBOX) {
            throw new UnsupportedProviderException();
        }
        Result attempt = attemptDropbox(accountId);
        boolean removed = accounts.disconnect(userId, accountId);
        log.info("Revoke-and-disconnect for account {}: revocation {} ({}), disconnected={}", accountId,
                attempt.outcome(), attempt.reason(), removed);
        return Optional.of(new Result(attempt.outcome(), attempt.reason(), removed));
    }

    private Result attemptDropbox(UUID accountId) {
        DropboxBrowser client = dropbox.getIfAvailable();
        if (client == null) {
            return new Result(Outcome.FAILED, "provider_not_configured", false);
        }
        ProviderAccessToken token;
        try {
            token = credentials.accessToken(StorageProvider.DROPBOX, accountId);
        } catch (ProviderCredentialStore.ReconnectRequiredException e) {
            // e.g. the refresh token was already rejected: nothing usable to revoke with.
            return new Result(Outcome.FAILED, "no_usable_credential", false);
        } catch (ProviderException e) {
            return new Result(Outcome.FAILED, "token_unavailable", false);
        } catch (RuntimeException e) {
            return new Result(Outcome.FAILED, "credential_unreadable", false);
        }
        RevokeOutcome outcome = client.revokeToken(token);
        return switch (outcome) {
            case CONFIRMED -> new Result(Outcome.CONFIRMED, "provider_confirmed", false);
            case FAILED -> new Result(Outcome.FAILED, "provider_rejected", false);
            case UNKNOWN -> new Result(Outcome.UNKNOWN, "no_definite_answer", false);
        };
    }
}
