package com.hyperdrive.backend.user;

/**
 * A validated external identity. {@code issuer} + {@code subject} is the key; the profile fields
 * are optional and informational only (email never controls account matching).
 */
public record ExternalIdentity(String issuer, String subject, String displayName, String verifiedEmail) {

    public ExternalIdentity {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("issuer and subject are required");
        }
        displayName = truncate(blankToNull(displayName), 200);
        verifiedEmail = truncate(blankToNull(verifiedEmail), 320);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
