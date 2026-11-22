package com.hyperdrive.backend.storage.provider;

/**
 * The validated provider account behind a storage grant. {@code subject} is the provider's stable
 * account id (Google OIDC {@code sub}, Dropbox {@code account_id}); email is informational only and
 * present only when the provider marked it verified. Never used to link HyperDrive users.
 */
public record ProviderIdentity(String issuer, String subject, String displayName, String email) {
    @Override
    public String toString() {
        return "ProviderIdentity[redacted]";
    }
}
