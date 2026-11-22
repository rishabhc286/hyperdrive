package com.hyperdrive.backend.storage.files;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.account.ConnectedAccount.Status;
import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.account.ConnectedAccountService.OwnedAccount;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore.ReconnectRequiredException;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.FileBrowser;
import com.hyperdrive.backend.storage.provider.FilePage;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read-only browsing of a connected account, for every provider. Order of operations for every
 * request: ownership check (no provider call for accounts the user does not own) → account status →
 * input validation for that provider → credentials (refresh if needed) → provider call, with one
 * refresh-and-retry on an authentication failure. Provider failures become stable, sanitized API
 * errors, and provider cursors are wrapped into account-bound continuations.
 */
@Service
@ConditionalOnAuthEnabled
public class StorageFilesService {

    private static final Logger log = LoggerFactory.getLogger(StorageFilesService.class);

    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final Map<StorageProvider, FileBrowser> browsers;
    private final StorageProperties properties;
    private final ContinuationCodec continuations;

    public StorageFilesService(ConnectedAccountService accounts, ProviderCredentialStore credentials,
            List<FileBrowser> browsers, StorageProperties properties, CredentialCipher cipher) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.browsers = browsers.stream().collect(Collectors.toUnmodifiableMap(FileBrowser::provider, b -> b));
        this.properties = properties;
        this.continuations = new ContinuationCodec(cipher);
    }

    /**
     * @param directory    provider directory locator from a folder's {@code directoryLocator}; null = root
     * @param continuation opaque continuation from the previous page of the same account and directory
     */
    public FileListing list(UUID userId, UUID accountId, String directory, String continuation, Integer pageSize) {
        OwnedAccount account = owned(userId, accountId);
        StorageProvider provider = account.provider();
        if (directory != null && !provider.isValidDirectory(directory)) {
            throw invalid("directory");
        }
        String providerCursor = null;
        if (continuation != null) {
            providerCursor = continuations.unwrap(account.id(), provider, directory, continuation)
                    .orElseThrow(() -> invalid("continuation"));
        }
        int size = pageSize == null ? properties.defaultPageSize() : Math.clamp(pageSize, 1, properties.maxPageSize());
        String cursor = providerCursor;
        FilePage page = call(account, (browser, token) -> browser.listChildren(token, account.root(), directory, cursor, size));
        String next = page.providerCursor() == null ? null
                : continuations.wrap(account.id(), provider, directory, page.providerCursor());
        return new FileListing(page.items(), next);
    }

    public ProviderFile get(UUID userId, UUID accountId, String fileId) {
        OwnedAccount account = owned(userId, accountId);
        if (!account.provider().isValidFileId(fileId)) {
            throw invalid("fileId");
        }
        return call(account, (browser, token) -> browser.getFile(token, account.root(), fileId));
    }

    private OwnedAccount owned(UUID userId, UUID accountId) {
        OwnedAccount account = accounts.findOwned(userId, accountId).orElseThrow(StorageFilesService::accountNotFound);
        if (account.status() == Status.NEEDS_RECONNECT) {
            throw needsReconnect();
        }
        return account;
    }

    private interface ProviderCall<T> {
        T apply(FileBrowser browser, ProviderAccessToken token);
    }

    private <T> T call(OwnedAccount account, ProviderCall<T> operation) {
        FileBrowser browser = browsers.get(account.provider());
        if (browser == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider_not_configured",
                    "This storage provider is not configured on this HyperDrive server.");
        }
        try {
            T result;
            ProviderAccessToken token = credentials.accessToken(account.provider(), account.id());
            try {
                result = operation.apply(browser, token);
            } catch (ProviderException e) {
                if (e.category() != ProviderException.Category.AUTHENTICATION) {
                    throw e;
                }
                // Once: the token may have been revoked or expired early. A second rejection is final.
                result = operation.apply(browser, credentials.refresh(account.provider(), account.id(), token));
            }
            accounts.recordSuccess(account.id());
            return result;
        } catch (ReconnectRequiredException e) {
            accounts.markNeedsReconnect(account.id(), e.code());
            throw needsReconnect();
        } catch (CredentialCipher.DecryptionException e) {
            log.error("Stored credentials of account {} could not be decrypted: {}", account.id(), e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "credentials_unreadable",
                    "Stored credentials for this account can't be read. Reconnect the account.");
        } catch (ProviderException e) {
            throw translate(account, e);
        }
    }

    private ApiException translate(OwnedAccount account, ProviderException e) {
        log.info("Provider call for account {} ({}) failed: {} ({})", account.id(), account.provider(), e.category(),
                e.getMessage());
        return switch (e.category()) {
            case AUTHENTICATION -> {
                accounts.markNeedsReconnect(account.id(), "provider_auth_failed");
                yield needsReconnect();
            }
            case INSUFFICIENT_SCOPE -> {
                accounts.markNeedsReconnect(account.id(), "insufficient_scope");
                yield needsReconnect();
            }
            case ROOT_UNAVAILABLE -> {
                accounts.markNeedsReconnect(account.id(), "root_changed");
                yield needsReconnect();
            }
            case PERMISSION_DENIED -> {
                accounts.recordError(account.id(), "provider_permission_denied");
                yield new ApiException(HttpStatus.FORBIDDEN, "provider_permission_denied",
                        "The storage provider denied access to this item.");
            }
            case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "file_not_found",
                    "This item no longer exists or is not accessible.");
            case CURSOR_RESET -> new ApiException(HttpStatus.CONFLICT, "continuation_expired",
                    "This listing changed or expired. Refresh the folder.");
            case RATE_LIMITED -> {
                accounts.recordError(account.id(), "provider_rate_limited");
                ApiException limited = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "provider_rate_limited",
                        "The storage provider is limiting requests. Try again shortly.");
                yield e.retryAfter() == null ? limited : limited.withRetryAfter(e.retryAfter());
            }
            case UNAVAILABLE -> {
                accounts.recordError(account.id(), "provider_unavailable");
                yield new ApiException(HttpStatus.BAD_GATEWAY, "provider_unavailable",
                        "The storage provider could not be reached. Try again.");
            }
            case INVALID_RESPONSE -> {
                accounts.recordError(account.id(), "provider_error");
                yield new ApiException(HttpStatus.BAD_GATEWAY, "provider_error",
                        "The storage provider returned an unexpected response.");
            }
        };
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid or missing fields: " + field);
    }

    /** Same response whether the account does not exist or belongs to someone else. */
    static ApiException accountNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "account_not_found", "Connected account not found.");
    }

    static ApiException needsReconnect() {
        return new ApiException(HttpStatus.CONFLICT, "account_needs_reconnect",
                "This storage account needs to be connected again.");
    }
}
