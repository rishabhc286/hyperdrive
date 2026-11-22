package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore;
import com.hyperdrive.backend.storage.connect.ConnectionOutcome.Failure;
import com.hyperdrive.backend.storage.provider.AccountRoot;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.ProviderIdentity;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.storage.provider.dropbox.DropboxBrowser;
import com.hyperdrive.backend.storage.provider.dropbox.DropboxBrowser.DropboxAccount;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Dropbox callback: code exchange (Spring's token client, client secret, no PKCE), scope check,
 * identity from {@code users/get_current_account} (which must name the same {@code account_id} as the
 * token response), root namespace from {@code root_info}, then account + encrypted credentials +
 * transaction completion in one database transaction. Created only when Dropbox is configured.
 */
public class DropboxConnectionCompleter implements ConnectionCompleter {

    /** Issuer recorded for Dropbox identities (Dropbox account ids are unique within Dropbox). */
    public static final String ISSUER = "https://www.dropbox.com";
    public static final String SCOPE_ACCOUNT_INFO = "account_info.read";
    public static final String SCOPE_METADATA = "files.metadata.read";
    public static final Set<String> REQUIRED_SCOPES = Set.of(SCOPE_ACCOUNT_INFO, SCOPE_METADATA);

    private static final Logger log = LoggerFactory.getLogger(DropboxConnectionCompleter.class);

    private final StorageOAuth oauth;
    private final StorageConnectionService transactions;
    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final DropboxBrowser dropbox;
    private final TransactionTemplate tx;

    public DropboxConnectionCompleter(StorageOAuth oauth, StorageConnectionService transactions,
            ConnectedAccountService accounts, ProviderCredentialStore credentials, DropboxBrowser dropbox,
            TransactionTemplate tx) {
        this.oauth = oauth;
        this.transactions = transactions;
        this.accounts = accounts;
        this.credentials = credentials;
        this.dropbox = dropbox;
        this.tx = tx;
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.DROPBOX;
    }

    @Override
    public StorageOAuth oauth() {
        return oauth;
    }

    @Override
    public ConnectionOutcome complete(StorageConnectionService.Claimed claimed, String code, String state) {
        OAuth2AccessTokenResponse tokens;
        try {
            tokens = oauth.exchange(code, state, null);
        } catch (OAuth2AuthorizationException | RestClientException e) {
            log.info("Dropbox code exchange failed for transaction {}: {}", claimed.id(), e.getClass().getSimpleName());
            return transactions.failWith(claimed, Failure.TOKEN_EXCHANGE_FAILED);
        }
        if (!StorageOAuth.grants(tokens, REQUIRED_SCOPES)) {
            return transactions.failWith(claimed, Failure.SCOPE_NOT_GRANTED);
        }
        DropboxAccount account;
        try {
            account = dropbox.currentAccount(new ProviderAccessToken(tokens.getAccessToken().getTokenValue()));
        } catch (ProviderException e) {
            log.info("Dropbox account lookup failed for transaction {}: {}", claimed.id(), e.category());
            return transactions.failWith(claimed, e.category() == ProviderException.Category.INVALID_RESPONSE
                    || e.category() == ProviderException.Category.PERMISSION_DENIED
                    ? Failure.UNSUPPORTED_ACCOUNT : Failure.PROVIDER_UNAVAILABLE);
        }
        // The token response names the account it was issued for; it must be the same account.
        Object tokenAccount = tokens.getAdditionalParameters().get("account_id");
        if (!(tokenAccount instanceof String t) || !t.equals(account.accountId())) {
            log.warn("Dropbox account id mismatch for transaction {}", claimed.id());
            return transactions.failWith(claimed, Failure.IDENTITY_INVALID);
        }
        ProviderIdentity identity = new ProviderIdentity(ISSUER, account.accountId(), account.displayName(),
                account.verifiedEmail());
        return transactions.store(claimed, () -> tx.execute(status -> {
            ConnectedAccountService.Upserted upserted = accounts.upsert(claimed.userId(), StorageProvider.DROPBOX,
                    identity, AccountRoot.dropboxNamespace(account.rootNamespaceId()), account.rootType(),
                    tokens.getAccessToken().getScopes());
            credentials.saveGrant(upserted.accountId(), tokens);
            transactions.complete(claimed.id(), upserted.accountId(), upserted.reconnected());
            return upserted.reconnected();
        }));
    }
}
