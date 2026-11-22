package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth.IdentityValidationException;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth.ProviderIdentity;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.FileBrowser;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Turns a claimed Google callback into a stored connection: code exchange, scope check, ID-token
 * validation, root-folder lookup, then account + encrypted credentials + transaction completion in
 * one database transaction. The Google identity here is the <em>storage</em> account; it never
 * becomes, replaces or creates a HyperDrive login identity.
 */
@Service
@ConditionalOnAuthEnabled
public class GoogleDriveConnectionCompleter {

    /** Machine-readable failure codes reported to the desktop through the transaction status. */
    public enum Failure {
        ACCESS_DENIED("access_denied"),
        AUTHORIZATION_FAILED("authorization_failed"),
        TOKEN_EXCHANGE_FAILED("token_exchange_failed"),
        DRIVE_SCOPE_NOT_GRANTED("drive_scope_not_granted"),
        IDENTITY_INVALID("identity_invalid"),
        DRIVE_UNAVAILABLE("drive_unavailable"),
        BROWSER_MISMATCH("browser_mismatch"),
        INTERNAL("internal_error");

        private final String code;

        Failure(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public sealed interface Outcome {
        record Connected(boolean reconnected) implements Outcome {
        }

        record Failed(Failure failure) implements Outcome {
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveConnectionCompleter.class);

    private final GoogleStorageOAuth oauth;
    private final StorageConnectionService transactions;
    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final FileBrowser drive;
    private final TransactionTemplate tx;

    public GoogleDriveConnectionCompleter(GoogleStorageOAuth oauth, StorageConnectionService transactions,
            ConnectedAccountService accounts, ProviderCredentialStore credentials, List<FileBrowser> browsers,
            TransactionTemplate tx) {
        this.oauth = oauth;
        this.transactions = transactions;
        this.accounts = accounts;
        this.credentials = credentials;
        this.drive = browsers.stream().filter(b -> b.provider() == StorageProvider.GOOGLE_DRIVE).findFirst()
                .orElseThrow(() -> new IllegalStateException("Google Drive browser missing"));
        this.tx = tx;
    }

    public Outcome complete(StorageConnectionService.Claimed claimed, String code, String state) {
        OAuth2AccessTokenResponse tokens;
        try {
            tokens = oauth.exchange(code, state, transactions.codeVerifier(claimed));
        } catch (OAuth2AuthorizationException | RestClientException | CredentialCipher.DecryptionException e) {
            log.info("Drive code exchange failed for transaction {}: {}", claimed.id(), e.getClass().getSimpleName());
            return fail(claimed, Failure.TOKEN_EXCHANGE_FAILED);
        }
        if (!GoogleStorageOAuth.grantsDriveReadonly(tokens)) {
            // Google's consent screen lets users untick individual permissions.
            return fail(claimed, Failure.DRIVE_SCOPE_NOT_GRANTED);
        }
        ProviderIdentity identity;
        try {
            identity = oauth.identity(tokens, claimed.nonceHash());
        } catch (IdentityValidationException e) {
            log.warn("Drive grant identity rejected for transaction {}: {}", claimed.id(), e.getMessage());
            return fail(claimed, Failure.IDENTITY_INVALID);
        }
        String rootFolderId;
        try {
            rootFolderId = drive.rootFolderId(new ProviderAccessToken(tokens.getAccessToken().getTokenValue()));
        } catch (ProviderException e) {
            log.info("Drive root lookup failed for transaction {}: {}", claimed.id(), e.category());
            return fail(claimed, Failure.DRIVE_UNAVAILABLE);
        }
        try {
            boolean reconnected = tx.execute(status -> {
                ConnectedAccountService.Upserted account = accounts.upsert(claimed.userId(), StorageProvider.GOOGLE_DRIVE,
                        identity, rootFolderId, tokens.getAccessToken().getScopes());
                credentials.saveGrant(account.accountId(), tokens);
                transactions.complete(claimed.id(), account.accountId(), account.reconnected());
                return account.reconnected();
            });
            log.info("Storage connection {} completed (reconnect={})", claimed.id(), reconnected);
            return new Outcome.Connected(reconnected);
        } catch (RuntimeException e) {
            log.error("Storing the Drive connection for transaction {} failed", claimed.id(), e);
            return fail(claimed, Failure.INTERNAL);
        }
    }

    public Outcome fail(StorageConnectionService.Claimed claimed, Failure failure) {
        transactions.fail(claimed.id(), failure.code());
        return new Outcome.Failed(failure);
    }
}
