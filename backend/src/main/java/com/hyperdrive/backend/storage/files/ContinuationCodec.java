package com.hyperdrive.backend.storage.files;

import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps a provider's continuation cursor (Drive {@code nextPageToken}, Dropbox {@code cursor}) into
 * an opaque HyperDrive continuation: AES-GCM encrypted with the credential key and authenticated
 * against the connected account id. A continuation therefore only works for the account, provider
 * and directory it was issued for; it can't be replayed against another account (even one of the
 * same user), another provider, or another folder, and the raw provider cursor never leaves the
 * backend.
 */
public final class ContinuationCodec {

    private static final String PREFIX = "hdk_";
    public static final int MAX_LENGTH = 8192;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final CredentialCipher cipher;

    public ContinuationCodec(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    public String wrap(UUID accountId, StorageProvider provider, String directory, String providerCursor) {
        byte[] plaintext = JSON.writeValueAsBytes(Map.of("p", provider.name(), "d", directory == null ? "" : directory,
                "c", providerCursor));
        CredentialCipher.Sealed sealed = cipher.encrypt(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8),
                context(accountId));
        byte[] out = ByteBuffer.allocate(4 + sealed.value().length).putInt(sealed.keyVersion()).put(sealed.value()).array();
        return PREFIX + ENCODER.encodeToString(out);
    }

    /** The provider cursor, or empty if the continuation is malformed or was issued for something else. */
    public Optional<String> unwrap(UUID accountId, StorageProvider provider, String directory, String continuation) {
        if (continuation == null || continuation.length() > MAX_LENGTH || !continuation.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            byte[] raw = DECODER.decode(continuation.substring(PREFIX.length()));
            if (raw.length < 4 + 29) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            int keyVersion = buffer.getInt();
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            JsonNode node = JSON.readTree(cipher.decrypt(sealed, keyVersion, context(accountId)));
            String p = node.path("p").asString("");
            String d = node.path("d").asString("");
            String c = node.path("c").asString("");
            if (!provider.name().equals(p) || !(directory == null ? "" : directory).equals(d) || c.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(c);
        } catch (IllegalArgumentException | CredentialCipher.DecryptionException | JacksonException e) {
            return Optional.empty();
        }
    }

    private static String context(UUID accountId) {
        return "listing_continuation:" + accountId;
    }
}
