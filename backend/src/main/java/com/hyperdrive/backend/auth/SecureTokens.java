package com.hyperdrive.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque secrets: 256 random bits, base64url, with a short type prefix so a leaked value is
 * recognizable (and easy for secret scanners). Only SHA-256 hashes are stored server-side; a slow
 * password hash is unnecessary because the values have full 256-bit entropy.
 */
public enum SecureTokens {
    HANDOFF_CODE("hdc_"),
    ACCESS_TOKEN("hda_"),
    REFRESH_TOKEN("hdr_"),
    /** One-time ticket in the browser-start URL of a storage connection. */
    STORAGE_TICKET("hds_"),
    /** Cookie value binding a storage connection's Google callback to the browser that started it. */
    BROWSER_BINDING("hdb_"),
    /** Short-lived, metadata-only grant handed to the local agent for the virtual drive (Batch 8). */
    METADATA_GRANT("hdm_");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String prefix;
    private final Pattern format;

    SecureTokens(String prefix) {
        this.prefix = prefix;
        this.format = Pattern.compile("^" + prefix + "[A-Za-z0-9_-]{43}$");
    }

    public String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + ENCODER.encodeToString(bytes);
    }

    /** Cheap syntactic check before touching the database. */
    public boolean isWellFormed(String value) {
        return value != null && format.matcher(value).matches();
    }

    public static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
