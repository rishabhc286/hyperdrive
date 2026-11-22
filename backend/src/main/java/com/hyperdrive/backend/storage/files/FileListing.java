package com.hyperdrive.backend.storage.files;

import com.hyperdrive.backend.storage.provider.ProviderFile;
import java.util.List;

/**
 * API response for one listing page. {@code continuation} is an opaque HyperDrive token (see
 * {@link ContinuationCodec}), never a raw provider cursor; null when there are no more items.
 */
public record FileListing(List<ProviderFile> items, String continuation) {
}
