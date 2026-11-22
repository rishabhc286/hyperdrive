package com.hyperdrive.backend.storage.provider;

/**
 * Read-only metadata browsing for one storage provider. Only what HyperDrive uses today; content
 * transfer and any write operations are intentionally absent until they are implemented.
 *
 * <p>Callers (not implementations) own credentials, refresh and account ownership checks. Every
 * method throws {@link ProviderException} on failure.
 */
public interface FileBrowser {

    StorageProvider provider();

    /** The provider's stable id of the account's root folder. */
    String rootFolderId(ProviderAccessToken token);

    /** Direct, non-trashed children of {@code folderId}; never recursive. */
    FilePage listChildren(ProviderAccessToken token, String folderId, String pageToken, int pageSize);

    ProviderFile getFile(ProviderAccessToken token, String fileId);
}
