package com.hyperdrive.backend.storage.account;

import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A connected storage account as returned by the API. Contains no credentials, no provider
 * subject and no internal ids other than HyperDrive's own account id.
 *
 * @param email    provider email, only if the provider marked it verified; may be null
 * @param rootType Dropbox only: {@code USER} (personal or member folder root) or {@code TEAM}
 *                 (team space root); null for Google Drive
 */
public record ConnectedAccount(
        UUID id,
        StorageProvider provider,
        String displayName,
        String email,
        Status status,
        String rootType,
        List<String> grantedScopes,
        Instant connectedAt,
        Instant updatedAt,
        Instant lastSuccessAt,
        String lastErrorCode) {

    public enum Status {
        CONNECTED,
        /** Credentials were revoked, expired or lack a scope; the user must connect again. */
        NEEDS_RECONNECT
    }
}
