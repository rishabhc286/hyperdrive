package com.hyperdrive.backend.vfs;

import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.account.ConnectedAccount;
import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.files.FileListing;
import com.hyperdrive.backend.storage.files.StorageFilesService;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch 8 virtual-drive metadata.
 *
 * <ul>
 *   <li>{@code POST/DELETE /api/v1/metadata-grants}: the signed-in, onboarded desktop session
 *       issues or revokes its grant (session token required).</li>
 *   <li>{@code GET /api/v1/vfs/accounts} and {@code GET /api/v1/vfs/accounts/{id}/files}: the
 *       grant holder (the local agent) lists the session user's accounts and one folder page at a
 *       time. Ownership is enforced by the same services the app uses. Responses carry metadata
 *       only: no emails, display names, tokens, hashes or provider cursors.</li>
 * </ul>
 */
@RestController
@ConditionalOnAuthEnabled
public class VfsMetadataController {

    public record GrantResponse(String grant, Instant expiresAt) {
        @Override
        public String toString() {
            return "GrantResponse[expiresAt=" + expiresAt + "]";
        }
    }

    public record VfsAccount(UUID id, StorageProvider provider, String rootType, ConnectedAccount.Status status) {
    }

    public record VfsAccounts(List<VfsAccount> items) {
    }

    public record VfsEntry(String id, String name, ProviderFile.FileKind kind, Long sizeBytes, Instant modifiedAt,
            String directoryLocator) {
    }

    public record VfsPage(List<VfsEntry> items, String continuation) {
    }

    private final MetadataGrantService grants;
    private final ConnectedAccountService accounts;
    private final StorageFilesService files;

    public VfsMetadataController(MetadataGrantService grants, ConnectedAccountService accounts,
            StorageFilesService files) {
        this.grants = grants;
        this.accounts = accounts;
        this.files = files;
    }

    @PostMapping("/api/v1/metadata-grants")
    public ResponseEntity<GrantResponse> issue(@AuthenticationPrincipal SessionPrincipal principal) {
        MetadataGrantService.Issued issued = grants.issue(principal.sessionId());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new GrantResponse(issued.grant(), issued.expiresAt()));
    }

    @DeleteMapping("/api/v1/metadata-grants")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal SessionPrincipal principal) {
        grants.revokeAll(principal.sessionId());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/v1/vfs/accounts")
    public ResponseEntity<VfsAccounts> accounts(@AuthenticationPrincipal MetadataPrincipal principal) {
        List<VfsAccount> items = accounts.list(principal.userId()).stream()
                .map(a -> new VfsAccount(a.id(), a.provider(), a.rootType(), a.status()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VfsAccounts(items));
    }

    @GetMapping("/api/v1/vfs/accounts/{accountId}/files")
    public ResponseEntity<VfsPage> files(@AuthenticationPrincipal MetadataPrincipal principal,
            @PathVariable UUID accountId, @RequestParam(required = false) String directory,
            @RequestParam(required = false) String continuation, @RequestParam(required = false) Integer pageSize) {
        FileListing listing = files.list(principal.userId(), accountId, directory, continuation, pageSize);
        List<VfsEntry> items = listing.items().stream()
                .map(f -> new VfsEntry(f.providerFileId(), f.name(), f.kind(), f.sizeBytes(), f.modifiedAt(),
                        f.directoryLocator()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VfsPage(items, listing.continuation()));
    }
}
