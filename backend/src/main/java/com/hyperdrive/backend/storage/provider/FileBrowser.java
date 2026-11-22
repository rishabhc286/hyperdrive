package com.hyperdrive.backend.storage.provider;

/**
 * Read-only metadata browsing for one storage provider. Only what HyperDrive uses today; content
 * transfer and any write operations are intentionally absent until they are implemented.
 *
 * <p>Callers (not implementations) own credentials, refresh, account ownership and continuation
 * wrapping. Every method throws {@link ProviderException} on failure.
 */
public interface FileBrowser {

    StorageProvider provider();

    /**
     * Direct children of a directory; never recursive. {@code directory} null = the account root.
     * {@code providerCursor} null = first page; otherwise the cursor from the previous page of the
     * same directory (initial listing and continuation stay separate provider calls).
     */
    FilePage listChildren(ProviderAccessToken token, AccountRoot root, String directory, String providerCursor,
            int pageSize);

    ProviderFile getFile(ProviderAccessToken token, AccountRoot root, String fileId);
}
