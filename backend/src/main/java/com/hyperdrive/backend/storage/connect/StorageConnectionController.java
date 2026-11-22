package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.web.ApiException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Bearer API used by the desktop main process to start and follow a storage connection. The owner
 * is always the authenticated session; requests carry no user or account ids.
 */
@RestController
@RequestMapping("/api/v1/storage-connections")
@ConditionalOnAuthEnabled
public class StorageConnectionController {

    public record TransactionCreated(UUID transactionId, String authorizationUrl, Instant expiresAt) {
    }

    /** Which providers this backend can connect (Dropbox is optional configuration). */
    public record ProviderAvailability(StorageProvider provider, boolean available) {
    }

    public record ProviderList(List<ProviderAvailability> items) {
    }

    private final StorageConnectionService transactions;
    private final AuthProperties authProperties;
    private final Set<StorageProvider> configured;

    public StorageConnectionController(StorageConnectionService transactions, AuthProperties authProperties,
            List<ConnectionCompleter> completers) {
        this.transactions = transactions;
        this.authProperties = authProperties;
        this.configured = completers.stream().map(ConnectionCompleter::provider).collect(Collectors.toUnmodifiableSet());
    }

    @GetMapping("/providers")
    public ResponseEntity<ProviderList> providers() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ProviderList(Arrays.stream(StorageProvider.values())
                .map(p -> new ProviderAvailability(p, configured.contains(p))).toList()));
    }

    /** No request body: provider comes from the path, owner from the session. */
    @PostMapping("/{provider:google|dropbox}/transactions")
    public ResponseEntity<TransactionCreated> create(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String provider) {
        StorageProvider p = StorageProvider.fromPathSegment(provider).orElseThrow();
        if (!configured.contains(p)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider_not_configured",
                    "This storage provider is not configured on this HyperDrive server.");
        }
        StorageConnectionService.Created created = transactions.create(principal.userId(), principal.sessionId(), p);
        String url = UriComponentsBuilder.fromUri(authProperties.publicBaseUrl())
                .path(StorageBrowserController.authorizePath(p)).path("/{ticket}").buildAndExpand(created.ticket())
                .toUriString();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new TransactionCreated(created.id(), url, created.expiresAt()));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<StorageConnectionService.Status> status(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID transactionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transactions.status(transactionId, principal.userId(), principal.sessionId())
                        .orElseThrow(StorageConnectionController::notFound));
    }

    @PostMapping("/transactions/{transactionId}/cancel")
    public ResponseEntity<StorageConnectionService.Status> cancel(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID transactionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transactions.cancel(transactionId, principal.userId(), principal.sessionId())
                        .orElseThrow(StorageConnectionController::notFound));
    }

    /** Identical for "does not exist" and "belongs to another user or device". */
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "transaction_not_found", "Connection attempt not found.");
    }
}
