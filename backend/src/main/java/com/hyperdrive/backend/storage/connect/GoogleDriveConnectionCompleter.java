package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore;
import com.hyperdrive.backend.storage.connect.ConnectionOutcome.Failure;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth.IdentityValidationException;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.AccountRoot;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.ProviderIdentity;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.storage.provider.google.GoogleDriveBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Google Drive callback: code exchange (PKCE), scope check, ID-token validation, root-folder
 * lookup, then account + encrypted credentials + transaction completion in one database transaction.
 */
@Service
@ConditionalOnAuthEnabled
public class GoogleDriveConnectionCompleter implements ConnectionCompleter {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveConnectionCompleter.class);

    private final GoogleStorageOAuth oauth;
    private final StorageConnectionService transactions;
    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final GoogleDriveBrowser drive;
    private final TransactionTemplate tx;

    public GoogleDriveConnectionCompleter(GoogleStorageOAuth oauth, StorageConnectionService transactions,
            ConnectedAccountService accounts, ProviderCredentialStore credentials, GoogleDriveBrowser drive,
            TransactionTemplate tx) {
        this.oauth = oauth;
        this.transactions = transactions;
        this.accounts = accounts;
        this.credentials = credentials;
        this.drive = drive;
        this.tx = tx;
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.GOOGLE_DRIVE;
    }

    @Override
    public StorageOAuth oauth() {
        return oauth;
    }

    @Override
    public ConnectionOutcome complete(StorageConnectionService.Claimed claimed, String code, String state) {
        OAuth2AccessTokenResponse tokens;
        try {
            tokens = oauth.exchange(code, state, transactions.codeVerifier(claimed));
        } catch (OAuth2AuthorizationException | RestClientException | CredentialCipher.DecryptionException e) {
            log.info("Drive code exchange failed for transaction {}: {}", claimed.id(), e.getClass().getSimpleName());
            return transactions.failWith(claimed, Failure.TOKEN_EXCHANGE_FAILED);
        }
        if (!GoogleStorageOAuth.grantsDriveReadonly(tokens)) {
            // Google's consent screen lets users untick individual permissions.
            return transactions.failWith(claimed, Failure.DRIVE_SCOPE_NOT_GRANTED);
        }
        ProviderIdentity identity;
        try {
            identity = oauth.identity(tokens, claimed.nonceHash());
        } catch (IdentityValidationException e) {
            log.warn("Drive grant identity rejected for transaction {}: {}", claimed.id(), e.getMessage());
            return transactions.failWith(claimed, Failure.IDENTITY_INVALID);
        }
        String rootFolderId;
        try {
            rootFolderId = drive.rootFolderId(new ProviderAccessToken(tokens.getAccessToken().getTokenValue()));
        } catch (ProviderException e) {
            log.info("Drive root lookup failed for transaction {}: {}", claimed.id(), e.category());
            return transactions.failWith(claimed, Failure.DRIVE_UNAVAILABLE);
        }
        return transactions.store(claimed, () -> tx.execute(status -> {
            ConnectedAccountService.Upserted account = accounts.upsert(claimed.userId(), StorageProvider.GOOGLE_DRIVE,
                    identity, AccountRoot.driveFolder(rootFolderId), null, tokens.getAccessToken().getScopes());
            credentials.saveGrant(account.accountId(), tokens);
            transactions.complete(claimed.id(), account.accountId(), account.reconnected());
            return account.reconnected();
        }));
    }
}
