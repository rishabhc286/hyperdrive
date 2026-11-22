package com.hyperdrive.backend.storage.provider;

/**
 * Where an account's browsing starts, as resolved at connection time.
 *
 * @param rootFolderId    Google Drive: the My Drive root folder id; null for Dropbox
 * @param rootNamespaceId Dropbox: root_info.root_namespace_id; null for Google Drive
 */
public record AccountRoot(String rootFolderId, String rootNamespaceId) {

    public static AccountRoot driveFolder(String folderId) {
        return new AccountRoot(folderId, null);
    }

    public static AccountRoot dropboxNamespace(String namespaceId) {
        return new AccountRoot(null, namespaceId);
    }
}
