package com.hyperdrive.backend.storage.provider;

import java.time.Instant;

/**
 * Application-owned file metadata, normalized from any provider. Provider SDK or wire models never
 * leave the provider adapter.
 *
 * @param providerFileId         opaque provider identifier, passed back unchanged
 * @param kind                   what the entry is
 * @param mimeType               provider-reported MIME type, may be null
 * @param sizeBytes              byte size of downloadable content; null when unknown or not
 *                               meaningful (folders, shortcuts, native provider documents)
 * @param modifiedAt             last modification time, null when the provider did not report one
 * @param canDownload            whether the content can be downloaded as-is (false for folders,
 *                               shortcuts and native documents, which need export)
 * @param shortcutTargetId       for shortcuts only: the target's provider id
 * @param shortcutTargetMimeType for shortcuts only: the target's MIME type as recorded at creation
 */
public record ProviderFile(
        String providerFileId,
        String name,
        FileKind kind,
        String mimeType,
        Long sizeBytes,
        Instant modifiedAt,
        boolean canDownload,
        String shortcutTargetId,
        String shortcutTargetMimeType) {

    public enum FileKind { FILE, FOLDER, SHORTCUT, PROVIDER_DOCUMENT }

    /** Names are user content: keep them out of logs. */
    @Override
    public String toString() {
        return "ProviderFile[kind=" + kind + "]";
    }
}
