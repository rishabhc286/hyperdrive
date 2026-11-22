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
 * Read-only browsing of a connected account. Order of operations for every request:
 * ownership check (no provider call for accounts the user does not own) → account status →
 * credentials (refresh if needed) → provider call, with one refresh-and-retry on an authentication
 * failure. Provider failures become stable, sanitized API errors.
 */
@Service
@ConditionalOnAuthEnabled
public class StorageFilesService {

    private static final Logger log = LoggerFactory.getLogger(StorageFilesService.class);

    private final ConnectedAccountService accounts;
    private final ProviderCredentialStore credentials;
    private final Map<StorageProvider, FileBrowser> browsers;
    private final StorageProperties properties;

    public StorageFilesService(ConnectedAccountService accounts, ProviderCredentialStore credentials,
            List<FileBrowser> browsers, StorageProperties properties) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.browsers = browsers.stream().collect(Collectors.toUnmodifiableMap(FileBrowser::provider, b -> b));
        this.properties = properties;
    }

    public FilePage list(UUID userId, UUID accountId, String parentId, String pageToken, Integer pageSize) {
        OwnedAccount account = owned(userId, accountId);
        String folderId = parentId == null ? account.rootFolderId() : parentId;
        requireValidId(account.provider(), folderId, "parentId");
        if (pageToken != null && (pageToken.isEmpty() || pageToken.length() > 2048
                || !pageToken.chars().allMatch(c -> c > 0x20 && c < 0x7f))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid or missing fields: pageToken");
        }
        int size = pageSize == null ? properties.defaultPageSize() : Math.clamp(pageSize, 1, properties.maxPageSize());
        return call(account, (browser, token) -> browser.listChildren(token, folderId, pageToken, size));
    }

    public ProviderFile get(UUID userId, UUID accountId, String fileId) {
        OwnedAccount account = owned(userId, accountId);
        requireValidId(account.provider(), fileId, "fileId");
        return call(account, (browser, token) -> browser.getFile(token, fileId));
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
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "provider_not_supported", "This provider is not supported yet.");
        }
        try {
            T result;
            ProviderAccessToken token = credentials.accessToken(account.id());
            try {
                result = operation.apply(browser, token);
            } catch (ProviderException e) {
                if (e.category() != ProviderException.Category.AUTHENTICATION) {
                    throw e;
                }
                // Once: the token may have been revoked or expired early. A second rejection is final.
                result = operation.apply(browser, credentials.refresh(account.id(), token));
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
        log.info("Provider call for account {} failed: {} ({})", account.id(), e.category(), e.getMessage());
        return switch (e.category()) {
            case AUTHENTICATION -> {
                accounts.markNeedsReconnect(account.id(), "provider_auth_failed");
                yield needsReconnect();
            }
            case INSUFFICIENT_SCOPE -> {
                accounts.markNeedsReconnect(account.id(), "insufficient_scope");
                yield needsReconnect();
            }
            case PERMISSION_DENIED -> {
                accounts.recordError(account.id(), "provider_permission_denied");
                yield new ApiException(HttpStatus.FORBIDDEN, "provider_permission_denied",
                        "The storage provider denied access to this item.");
            }
            case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "file_not_found",
                    "This item no longer exists or is not accessible.");
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

    private static void requireValidId(StorageProvider provider, String id, String field) {
        if (!provider.isValidFileId(id)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid or missing fields: " + field);
        }
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
