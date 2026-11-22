package com.hyperdrive.backend.storage.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption of provider credentials with the JDK's AES-256-GCM.
 *
 * <ul>
 *   <li>Every value gets a fresh random 96-bit nonce; output is {@code nonce || ciphertext || tag}.</li>
 *   <li>A caller-supplied context (for example {@code provider_credentials:<account id>:refresh}) is
 *       authenticated as associated data, so a value only decrypts in the row and field it was
 *       written for.</li>
 *   <li>Keys come from configuration (environment), never from the database. Each value is stored
 *       with its key version; older versions stay decryptable while their key is configured.</li>
 * </ul>
 *
 * Exceptions never include plaintext, ciphertext or key material.
 */
public final class CredentialCipher {

    public static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** An encrypted value plus the version of the key that produced it. */
    public record Sealed(byte[] value, int keyVersion) {
        @Override
        public String toString() {
            return "Sealed[keyVersion=" + keyVersion + "]";
        }
    }

    /** Configuration problem: missing, malformed or wrong-length key. */
    public static final class KeyConfigurationException extends IllegalStateException {
        KeyConfigurationException(String message) {
            super(message);
        }
    }

    /** A stored value could not be decrypted (unknown key version, wrong key, or tampering). */
    public static final class DecryptionException extends RuntimeException {
        DecryptionException(String message) {
            super(message, null, false, false);
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final int currentVersion;
    private final Map<Integer, SecretKey> keys;

    private CredentialCipher(int currentVersion, Map<Integer, SecretKey> keys) {
        this.currentVersion = currentVersion;
        this.keys = Collections.unmodifiableMap(keys);
    }

    /**
     * @param currentKey   base64 (standard or URL-safe) of exactly 32 random bytes
     * @param version      positive version number for {@code currentKey}
     * @param previousKeys optional {@code version:base64[,version:base64...]} for decryption only
     */
    public static CredentialCipher fromConfiguration(String currentKey, int version, String previousKeys) {
        if (version <= 0) {
            throw new KeyConfigurationException("HYPERDRIVE_CREDENTIAL_KEY_VERSION must be a positive integer");
        }
        Map<Integer, SecretKey> keys = new HashMap<>();
        keys.put(version, decodeKey(currentKey, "HYPERDRIVE_CREDENTIAL_KEY"));
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                int previousVersion;
                try {
                    previousVersion = parts.length == 2 ? Integer.parseInt(parts[0].trim()) : -1;
                } catch (NumberFormatException e) {
                    previousVersion = -1;
                }
                if (previousVersion <= 0 || keys.containsKey(previousVersion)) {
                    throw new KeyConfigurationException(
                            "HYPERDRIVE_CREDENTIAL_PREVIOUS_KEYS must be version:base64 pairs with distinct positive versions");
                }
                keys.put(previousVersion, decodeKey(parts[1].trim(), "HYPERDRIVE_CREDENTIAL_PREVIOUS_KEYS"));
            }
        }
        return new CredentialCipher(version, keys);
    }

    public int currentVersion() {
        return currentVersion;
    }

    public Sealed encrypt(String plaintext, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Sealed(ByteBuffer.allocate(NONCE_BYTES + ciphertext.length).put(nonce).put(ciphertext).array(),
                    currentVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(byte[] sealed, int keyVersion, String context) {
        SecretKey key = keys.get(keyVersion);
        if (key == null) {
            throw new DecryptionException("No credential key configured for key version " + keyVersion);
        }
        if (sealed == null || sealed.length <= NONCE_BYTES + TAG_BITS / 8) {
            throw new DecryptionException("Stored credential is malformed");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key, wrong context or modified data. Deliberately no cause: it adds nothing safe.
            throw new DecryptionException("Stored credential could not be decrypted (wrong key or modified data)");
        }
    }

    private static SecretKey decodeKey(String value, String name) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new KeyConfigurationException(name + " is not set. Generate 32 random bytes, base64-encode them "
                    + "and set the variable (see docs/google-drive.md).");
        }
        byte[] bytes;
        try {
            String trimmed = value.trim();
            bytes = trimmed.contains("-") || trimmed.contains("_")
                    ? Base64.getUrlDecoder().decode(trimmed)
                    : Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw new KeyConfigurationException(name + " is not valid base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new KeyConfigurationException(name + " must decode to exactly 32 bytes (AES-256); got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
