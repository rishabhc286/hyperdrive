package com.hyperdrive.backend.storage.provider;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Supported storage providers. Each provider has its own syntax for stable file ids and for the
 * directory locators used to navigate; the two are deliberately distinct (a Dropbox folder is
 * identified by {@code id:...} but listed by path).
 */
public enum StorageProvider {
    GOOGLE_DRIVE("google", Pattern.compile("^[A-Za-z0-9_-]{1,256}$"), Pattern.compile("^[A-Za-z0-9_-]{1,256}$")),
    /** File ids are {@code id:...}; directories are addressed by lower-cased path ("/a/b"), root = "". */
    DROPBOX("dropbox", Pattern.compile("^id:[A-Za-z0-9_-]{1,256}$"), Pattern.compile("^/[^\\p{Cntrl}]{1,4095}$"));

    private final String pathSegment;
    private final Pattern fileIdFormat;
    private final Pattern directoryFormat;

    StorageProvider(String pathSegment, Pattern fileIdFormat, Pattern directoryFormat) {
        this.pathSegment = pathSegment;
        this.fileIdFormat = fileIdFormat;
        this.directoryFormat = directoryFormat;
    }

    /** Segment used in {@code /api/v1/storage-connections/{segment}/...}. */
    public String pathSegment() {
        return pathSegment;
    }

    /**
     * The granted scope that allows reading file <em>content</em> (not just metadata). Google's
     * drive.readonly covers both; Dropbox needs files.content.read in addition to files.metadata.read.
     */
    public String contentReadScope() {
        return this == GOOGLE_DRIVE ? "https://www.googleapis.com/auth/drive.readonly" : "files.content.read";
    }

    /** Spring Security / internal client registration id for this provider's storage consent. */
    public String registrationId() {
        return this == GOOGLE_DRIVE ? "google-drive" : "dropbox";
    }

    public static Optional<StorageProvider> fromPathSegment(String segment) {
        return Arrays.stream(values()).filter(p -> p.pathSegment.equals(segment)).findFirst();
    }

    /** Syntactic check of a stable provider file id before it is sent anywhere. */
    public boolean isValidFileId(String id) {
        return id != null && fileIdFormat.matcher(id).matches();
    }

    /** Syntactic check of a directory locator (never the root, which is always "omitted"). */
    public boolean isValidDirectory(String locator) {
        return locator != null && directoryFormat.matcher(locator).matches() && !locator.contains("//");
    }
}
