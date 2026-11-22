package com.hyperdrive.backend.storage.account;

import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.files.StorageFilesService;
import com.hyperdrive.backend.storage.files.FileListing;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import com.hyperdrive.backend.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connected storage accounts and read-only browsing. Ownership is checked against the session's
 * user before any provider call; another user's account id behaves exactly like an unknown one.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@ConditionalOnAuthEnabled
public class ConnectedAccountController {

    public record AccountList(List<ConnectedAccount> items) {
    }

    private final ConnectedAccountService accounts;
    private final StorageFilesService files;

    public ConnectedAccountController(ConnectedAccountService accounts, StorageFilesService files) {
        this.accounts = accounts;
        this.files = files;
    }

    @GetMapping
    public ResponseEntity<AccountList> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AccountList(accounts.list(principal.userId())));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<ConnectedAccount> get(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(accounts.find(principal.userId(), accountId).orElseThrow(ConnectedAccountController::notFound));
    }

    /**
     * Unlinks the storage account from HyperDrive and deletes the stored credentials. Remote files,
     * the HyperDrive user and the provider-side grant are untouched (see docs/google-drive.md).
     */
    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID accountId) {
        if (!accounts.disconnect(principal.userId(), accountId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Direct children of {@code directory} (a folder's {@code directoryLocator}; the account root when
     * omitted). {@code continuation} is the opaque value from the previous page of the same listing.
     */
    @GetMapping("/{accountId}/files")
    public ResponseEntity<FileListing> listFiles(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID accountId, @RequestParam(required = false) String directory,
            @RequestParam(required = false) String continuation, @RequestParam(required = false) Integer pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(files.list(principal.userId(), accountId, directory, continuation, pageSize));
    }

    /** Metadata by stable provider id (Dropbox ids look like {@code id:...}; URL-encode the segment). */
    @GetMapping("/{accountId}/files/{fileId}")
    public ResponseEntity<ProviderFile> getFile(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID accountId, @PathVariable String fileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(files.get(principal.userId(), accountId, fileId));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "account_not_found", "Connected account not found.");
    }
}
