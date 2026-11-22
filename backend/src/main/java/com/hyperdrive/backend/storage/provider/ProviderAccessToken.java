package com.hyperdrive.backend.storage.provider;

/** A provider access token in memory. {@link #toString()} is redacted so it cannot leak into logs. */
public record ProviderAccessToken(String value) {
    @Override
    public String toString() {
        return "ProviderAccessToken[redacted]";
    }
}
