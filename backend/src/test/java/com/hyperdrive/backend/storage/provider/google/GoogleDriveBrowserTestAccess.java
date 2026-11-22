package com.hyperdrive.backend.storage.provider.google;

import com.hyperdrive.backend.storage.provider.ProviderException.Category;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Exposes package-private helpers of {@link GoogleDriveBrowser} to unit tests in other packages. */
public final class GoogleDriveBrowserTestAccess {

    private GoogleDriveBrowserTestAccess() {
    }

    public static String escape(String value) {
        return GoogleDriveBrowser.escapeQueryValue(value);
    }

    public static String childrenQuery(String id) {
        return GoogleDriveBrowser.childrenQuery(id);
    }

    public static ProviderFile map(JsonNode node) {
        return GoogleDriveBrowser.toProviderFile(node);
    }

    public static Category classify(int status, String retryAfter, String body) {
        return GoogleDriveBrowser.classify(new GoogleDriveBrowser.Response(status, retryAfter,
                body.getBytes(StandardCharsets.UTF_8))).category();
    }

    public static Duration retryAfter(String value) {
        return GoogleDriveBrowser.parseRetryAfter(value);
    }
}
