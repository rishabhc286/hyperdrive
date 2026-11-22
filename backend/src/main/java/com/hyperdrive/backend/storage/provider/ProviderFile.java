package com.hyperdrive.backend.storage.provider;

import java.time.Instant;

/**
 * Application-owned file metadata, normalized from any provider. Provider SDK or wire models never
 * leave the provider adapter. Nothing here is invented: unknown values are null.
 *
 * @param providerFileId         stable, opaque provider identifier (Drive file id, Dropbox {@code id:...})
 * @param kind                   what the entry is
 * @param mimeType               provider-reported MIME type; null when the provider has none (Dropbox)
 * @param sizeBytes              byte size of downloadable content; null when unknown or not meaningful
 *                               (folders, shortcuts/links, native provider documents)
 * @param modifiedAt             last modification time reported by the provider, or null
 * @param canDownload            whether the content could be downloaded as-is (false for folders,
 *                               links and native documents)
 * @param directoryLocator       folders only: what to pass back to list this folder. Drive: the folder
 *                               id. Dropbox: its lower-cased path. Distinct from the stable id.
 * @param providerRevision       provider revision of the content, when the provider exposes one
 *                               (Dropbox {@code rev}); null otherwise
 * @param contentHash            provider-specific content hash, or null
 * @param contentHashAlgorithm   names the hash scheme (e.g. {@code dropbox-content-hash}); hashes of
 *                               different providers are not comparable
 * @param shortcutTargetId       Drive shortcuts only: the target's provider id
 * @param shortcutTargetMimeType Drive shortcuts only: the target's MIME type as recorded at creation
 */
public record ProviderFile(
        String providerFileId,
        String name,
        FileKind kind,
        String mimeType,
        Long sizeBytes,
        Instant modifiedAt,
        boolean canDownload,
        String directoryLocator,
        String providerRevision,
        String contentHash,
        String contentHashAlgorithm,
        String shortcutTargetId,
        String shortcutTargetMimeType) {

    public enum FileKind { FILE, FOLDER, SHORTCUT, PROVIDER_DOCUMENT }

    /** Names are user content: keep them out of logs. */
    @Override
    public String toString() {
        return "ProviderFile[kind=" + kind + "]";
    }
}
