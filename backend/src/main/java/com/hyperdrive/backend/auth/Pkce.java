package com.hyperdrive.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** PKCE (RFC 7636) verification. Only the S256 method is supported; "plain" is rejected. */
public final class Pkce {

    public static final String METHOD_S256 = "S256";
    /** RFC 7636 §4.1: 43–128 characters from the unreserved set. */
    public static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");
    /** base64url(SHA-256(verifier)) without padding is always 43 characters. */
    public static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private Pkce() {
    }

    public static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of the derived challenge with the stored one. */
    public static boolean matches(String verifier, String storedChallenge) {
        if (verifier == null || storedChallenge == null || !VERIFIER.matcher(verifier).matches()) {
            return false;
        }
        return MessageDigest.isEqual(
                challengeFor(verifier).getBytes(StandardCharsets.US_ASCII),
                storedChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
