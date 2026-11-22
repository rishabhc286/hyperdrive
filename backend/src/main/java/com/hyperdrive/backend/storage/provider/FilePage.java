package com.hyperdrive.backend.storage.provider;

import java.util.List;

/** One page of a folder listing. {@code nextPageToken} is the provider's opaque cursor, or null. */
public record FilePage(List<ProviderFile> items, String nextPageToken) {
    public FilePage {
        items = List.copyOf(items);
    }
}
