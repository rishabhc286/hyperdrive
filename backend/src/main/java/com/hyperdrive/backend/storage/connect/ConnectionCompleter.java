package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.storage.provider.StorageProvider;

/**
 * Turns a claimed provider callback into a stored connection (or a recorded failure). One per
 * provider. The provider identity here is the storage account; it never becomes, replaces or
 * creates a HyperDrive login identity.
 */
public interface ConnectionCompleter {

    StorageProvider provider();

    /** {@code oauth} for this provider, used by the browser leg to start authorization. */
    StorageOAuth oauth();

    ConnectionOutcome complete(StorageConnectionService.Claimed claimed, String code, String state);
}
