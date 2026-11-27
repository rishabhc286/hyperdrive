package com.hyperdrive.agent.vfs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Where virtual-drive metadata comes from: the backend's grant-scoped VFS API (a fake in tests). */
public interface MetadataSource {

    record Account(UUID id, String provider, String rootType, String status) {
    }

    record Entry(String id, String name, String kind, Long sizeBytes, Instant modifiedAt, String directoryLocator) {
        /** Names are user content: never logged. */
        @Override
        public String toString() {
            return "Entry[kind=" + kind + "]";
        }
    }

    record Page(List<Entry> items, String continuation) {
    }

    /** Batch 9: one file's current metadata by provider id (no name). */
    record FileMeta(String id, String provider, String kind, Long sizeBytes, String providerRevision, boolean canDownload) {
    }

    /** A failure with a stable code for the VFS; never provider or backend body text. */
    final class MetadataException extends Exception {
        private final String code;
        private final int status;

        public MetadataException(String code, int status) {
            super(code, null, false, false);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    List<Account> accounts(MountRegistry.Mount mount) throws MetadataException;

    Page files(MountRegistry.Mount mount, UUID accountId, String directory, String continuation, int pageSize)
            throws MetadataException;

    /** Owner-checked metadata of one file of one of the mount user's accounts. */
    default FileMeta file(MountRegistry.Mount mount, UUID accountId, String fileId) throws MetadataException {
        throw new MetadataException("not_found", 404);
    }
}
