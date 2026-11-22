package com.hyperdrive.backend.storage.provider;

import java.util.regex.Pattern;

/** Supported storage providers. Dropbox joins in its own batch. */
public enum StorageProvider {
    GOOGLE_DRIVE("google", "google-drive", Pattern.compile("^[A-Za-z0-9_-]{1,256}$"));

    private final String pathSegment;
    private final String registrationId;
    private final Pattern fileIdFormat;

    StorageProvider(String pathSegment, String registrationId, Pattern fileIdFormat) {
        this.pathSegment = pathSegment;
        this.registrationId = registrationId;
        this.fileIdFormat = fileIdFormat;
    }

    /** Segment used in {@code /api/v1/storage-connections/{segment}/...}. */
    public String pathSegment() {
        return pathSegment;
    }

    /** Spring Security client registration used for this provider's storage consent. */
    public String registrationId() {
        return registrationId;
    }

    /** Syntactic check of a provider file id before it is sent anywhere. */
    public boolean isValidFileId(String id) {
        return id != null && fileIdFormat.matcher(id).matches();
    }
}
