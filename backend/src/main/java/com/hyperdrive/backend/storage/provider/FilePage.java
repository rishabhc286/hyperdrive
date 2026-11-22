package com.hyperdrive.backend.storage.provider;

import java.util.List;

/**
 * One page of a folder listing as returned by an adapter. {@code providerCursor} is the provider's
 * own opaque continuation (Drive {@code nextPageToken}, Dropbox {@code cursor} when {@code has_more});
 * it never leaves the backend unwrapped (see {@code ContinuationCodec}).
 */
public record FilePage(List<ProviderFile> items, String providerCursor) {
    public FilePage {
        items = List.copyOf(items);
    }
}
