package com.hyperdrive.agent.read;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chunk encryption and key-derived naming for the local cache (format v1). Only JDK primitives:
 * AES-256-GCM, HMAC-SHA-256 and HKDF-Expand (RFC 5869, one block) from a uniformly random 32-byte
 * master key, so the extract step is skipped (RFC 5869 §3.3).
 *
 * <pre>
 * file = MAGIC(4: "HDC" 0x01) || nonce(12, random) || AES-GCM ciphertext || tag(16)
 * AAD  = MAGIC || chunkTag(32 bytes) || plaintextLength(8, big-endian)
 * </pre>
 */
final class CacheCrypto {

    static final byte[] MAGIC = {'H', 'D', 'C', 0x01};
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    /** Bytes added to each chunk on disk. */
    static final int OVERHEAD = MAGIC.length + NONCE_BYTES + TAG_BITS / 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec encKey;
    private final SecretKeySpec tagKey;

    /** @param master 32 random bytes; copied into derived keys, the array itself isn't retained. */
    CacheCrypto(byte[] master) {
        if (master == null || master.length != 32) {
            throw new IllegalArgumentException("cache key must be 32 bytes");
        }
        this.encKey = new SecretKeySpec(hkdfExpand(master, "hyperdrive-cache-enc-v1"), "AES");
        this.tagKey = new SecretKeySpec(hkdfExpand(master, "hyperdrive-cache-tag-v1"), "HmacSHA256");
    }

    /** Directory name for a user: unkeyed, so cleanup works without the key; not the raw id. */
    static String userTag(UUID userId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("hyperdrive-cache-user-v1\0" + userId).getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(d, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String accountTag(UUID accountId) {
        return HEX.formatHex(mac("account", accountId.toString()), 0, 16);
    }

    /** Binds user, account, provider, file, enforced revision, size, chunk size and index. */
    byte[] chunkTag(UUID userId, UUID accountId, String provider, String fileId, String revision, long fileSize,
            int chunkSize, long chunkIndex) {
        return mac("chunk", "1", userId.toString(), accountId.toString(), provider, fileId, revision,
                Long.toString(fileSize), Integer.toString(chunkSize), Long.toString(chunkIndex));
    }

    static String hex(byte[] tag) {
        return HEX.formatHex(tag);
    }

    byte[] encrypt(byte[] chunkTag, byte[] plaintext, int length) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(chunkTag, length));
            byte[] out = new byte[OVERHEAD + length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(nonce, 0, out, MAGIC.length, NONCE_BYTES);
            cipher.doFinal(plaintext, 0, length, out, MAGIC.length + NONCE_BYTES);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("chunk encryption failed", e);
        }
    }

    /** Returns the plaintext, or throws {@link CorruptChunkException}; never unauthenticated bytes. */
    byte[] decrypt(byte[] chunkTag, byte[] file, int expectedLength) throws CorruptChunkException {
        if (file.length != OVERHEAD + expectedLength || !hasMagic(file)) {
            throw new CorruptChunkException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, file, MAGIC.length, NONCE_BYTES));
            cipher.updateAAD(aad(chunkTag, expectedLength));
            int start = MAGIC.length + NONCE_BYTES;
            return cipher.doFinal(file, start, file.length - start);
        } catch (AEADBadTagException e) {
            throw new CorruptChunkException();
        } catch (GeneralSecurityException e) {
            throw new CorruptChunkException();
        }
    }

    static boolean hasMagic(byte[] header) {
        if (header.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] aad(byte[] chunkTag, long length) {
        return ByteBuffer.allocate(MAGIC.length + chunkTag.length + 8).put(MAGIC).put(chunkTag).putLong(length).array();
    }

    private byte[] mac(String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(tagKey);
            for (String p : parts) {
                byte[] b = p.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(b.length).array()); // length-prefixed: unambiguous
                mac.update(b);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF-Expand with L = 32: T(1) = HMAC(PRK, info || 0x01). */
    private static byte[] hkdfExpand(byte[] prk, String info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0x01);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Authentication or format failure of a cached chunk. */
    static final class CorruptChunkException extends Exception {
        CorruptChunkException() {
            super("cached chunk failed authentication", null, false, false);
        }
    }
}
