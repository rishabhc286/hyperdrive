package com.hyperdrive.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.ProviderException.Category;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import com.hyperdrive.backend.storage.provider.ProviderFile.FileKind;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.storage.provider.google.GoogleDriveBrowserTestAccess;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import tools.jackson.databind.json.JsonMapper;

/** Pure unit tests (no Spring context, no database). */
class StorageUnitTest {

    private static final String KEY_1 = Base64.getEncoder().encodeToString(filled(32, (byte) 1));
    private static final String KEY_2 = Base64.getEncoder().encodeToString(filled(32, (byte) 2));

    private static byte[] filled(int length, byte value) {
        byte[] b = new byte[length];
        Arrays.fill(b, value);
        return b;
    }

    // --- Credential encryption -------------------------------------------------------------

    @Test
    void encryptsAndDecryptsWithAuthenticatedContext() {
        CredentialCipher cipher = CredentialCipher.fromConfiguration(KEY_1, 1, "");
        CredentialCipher.Sealed sealed = cipher.encrypt("ya29.secret-token", "provider_credentials:a:access");

        assertThat(sealed.keyVersion()).isEqualTo(1);
        assertThat(new String(sealed.value(), StandardCharsets.ISO_8859_1)).doesNotContain("secret-token");
        assertThat(cipher.decrypt(sealed.value(), 1, "provider_credentials:a:access")).isEqualTo("ya29.secret-token");
        assertThat(sealed.toString()).doesNotContain("secret");
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        CredentialCipher cipher = CredentialCipher.fromConfiguration(KEY_1, 1, null);
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            byte[] value = cipher.encrypt("same", "ctx").value();
            assertThat(nonces.add(Base64.getEncoder().encodeToString(Arrays.copyOf(value, 12)))).isTrue();
        }
    }

    @Test
    void wrongContextTamperingOrWrongKeyFailClearlyWithoutDetails() {
        CredentialCipher cipher = CredentialCipher.fromConfiguration(KEY_1, 1, "");
        byte[] value = cipher.encrypt("refresh-secret", "provider_credentials:a:refresh").value();

        assertThatThrownBy(() -> cipher.decrypt(value, 1, "provider_credentials:b:refresh"))
                .isInstanceOf(CredentialCipher.DecryptionException.class)
                .hasMessageNotContaining("refresh-secret");
        byte[] tampered = value.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(tampered, 1, "provider_credentials:a:refresh"))
                .isInstanceOf(CredentialCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(value, 9, "provider_credentials:a:refresh"))
                .isInstanceOf(CredentialCipher.DecryptionException.class).hasMessageContaining("key version 9");
        CredentialCipher otherKey = CredentialCipher.fromConfiguration(KEY_2, 1, "");
        assertThatThrownBy(() -> otherKey.decrypt(value, 1, "provider_credentials:a:refresh"))
                .isInstanceOf(CredentialCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[5], 1, "x")).isInstanceOf(CredentialCipher.DecryptionException.class);
    }

    @Test
    void previousKeysStillDecryptAfterRotation() {
        CredentialCipher before = CredentialCipher.fromConfiguration(KEY_1, 1, "");
        byte[] old = before.encrypt("token", "ctx").value();

        CredentialCipher after = CredentialCipher.fromConfiguration(KEY_2, 2, "1:" + KEY_1);
        assertThat(after.decrypt(old, 1, "ctx")).isEqualTo("token");
        assertThat(after.encrypt("token", "ctx").keyVersion()).isEqualTo(2);
        assertThat(after.currentVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "${HYPERDRIVE_CREDENTIAL_KEY}", "not base64 !!", "AQID"})
    void missingOrMalformedKeyFailsStartupWithAnActionableMessage(String key) {
        assertThatThrownBy(() -> CredentialCipher.fromConfiguration(key, 1, ""))
                .isInstanceOf(CredentialCipher.KeyConfigurationException.class)
                .hasMessageContaining("HYPERDRIVE_CREDENTIAL_KEY");
    }

    @Test
    void invalidVersionsAndPreviousKeysAreRejected() {
        assertThatThrownBy(() -> CredentialCipher.fromConfiguration(KEY_1, 0, ""))
                .isInstanceOf(CredentialCipher.KeyConfigurationException.class);
        assertThatThrownBy(() -> CredentialCipher.fromConfiguration(KEY_1, 1, "1:" + KEY_2))
                .isInstanceOf(CredentialCipher.KeyConfigurationException.class);
        assertThatThrownBy(() -> CredentialCipher.fromConfiguration(KEY_1, 2, KEY_2))
                .isInstanceOf(CredentialCipher.KeyConfigurationException.class);
    }

    @Test
    void urlSafeBase64KeysAreAccepted() {
        byte[] bytes = filled(32, (byte) 0xfb);
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertThat(urlSafe).contains("-");
        CredentialCipher cipher = CredentialCipher.fromConfiguration(urlSafe, 1, "");
        assertThat(cipher.decrypt(cipher.encrypt("x", "c").value(), 1, "c")).isEqualTo("x");
    }

    // --- Startup validation ----------------------------------------------------------------

    private static AuthProperties auth() {
        return new AuthProperties(true, URI.create("http://127.0.0.1:18080"), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofMinutes(15), Duration.ofDays(30));
    }

    private static StorageProperties storage(int defaultPage, int maxPage) {
        return storage(defaultPage, maxPage, "", "");
    }

    static StorageProperties storage(int defaultPage, int maxPage, String dropboxKey, String dropboxSecret) {
        return new StorageProperties(KEY_1, 1, "", Duration.ofMinutes(10), Duration.ofMinutes(2),
                URI.create("https://www.googleapis.com/drive/v3"), Duration.ofSeconds(5), Duration.ofSeconds(15),
                defaultPage, maxPage, 2, Duration.ofMillis(250), Duration.ofSeconds(4),
                new StorageProperties.Dropbox(dropboxKey, dropboxSecret, URI.create("https://www.dropbox.com/oauth2/authorize"),
                        URI.create("https://api.dropboxapi.com/oauth2/token"), URI.create("https://api.dropboxapi.com/2")));
    }

    private static ClientRegistration drive(String clientId, String redirect, String... scopes) {
        return ClientRegistration.withRegistrationId("google-drive")
                .clientId(clientId).clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirect)
                .scope(scopes)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .build();
    }

    private static final String CALLBACK = "http://127.0.0.1:18080/api/v1/storage-connections/google/callback";
    private static final String[] SCOPES = {"openid", "email", "profile", "https://www.googleapis.com/auth/drive.readonly"};

    @Test
    void startupCheckAcceptsTheDocumentedConfiguration() {
        StorageStartupCheck.validate(auth(), storage(100, 200), drive("id", CALLBACK, SCOPES));
    }

    @Test
    void startupCheckRejectsMissingClientWrongCallbackAndWrongScopes() {
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(100, 200), null))
                .hasMessageContaining("GOOGLE_CLIENT_ID");
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(100, 200), drive("${GOOGLE_CLIENT_ID}", CALLBACK, SCOPES)))
                .hasMessageContaining("GOOGLE_CLIENT_ID");
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(100, 200),
                drive("id", "http://127.0.0.1:18080/login/oauth2/code/google", SCOPES)))
                .hasMessageContaining("redirect URI");
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(100, 200),
                drive("id", CALLBACK, "openid", "email", "profile", "https://www.googleapis.com/auth/drive")))
                .hasMessageContaining("exactly");
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(100, 200),
                drive("id", CALLBACK, "openid", "email", "profile")))
                .hasMessageContaining("exactly");
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(300, 200), drive("id", CALLBACK, SCOPES)))
                .hasMessageContaining("page sizes");
        assertThatThrownBy(() -> StorageStartupCheck.validate(auth(), storage(100, 200, "key-only", ""), drive("id", CALLBACK, SCOPES)))
                .hasMessageContaining("partially configured");
        StorageStartupCheck.validate(auth(), storage(100, 200, "key", "secret"), drive("id", CALLBACK, SCOPES));
    }

    // --- Drive adapter ----------------------------------------------------------------------

    @Test
    void driveQueryValuesAreEscaped() {
        assertThat(GoogleDriveBrowserTestAccess.escape("a'b\\c")).isEqualTo("a\\'b\\\\c");
        assertThat(GoogleDriveBrowserTestAccess.childrenQuery("abc_-123")).isEqualTo("'abc_-123' in parents and trashed = false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a'b", "a b", "../x", "a/b", "", "é"})
    void driveIdsOutsideTheIdAlphabetAreInvalid(String id) {
        assertThat(StorageProvider.GOOGLE_DRIVE.isValidFileId(id)).isFalse();
    }

    @Test
    void driveResourcesMapWithoutInventingValues() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        ProviderFile sheet = GoogleDriveBrowserTestAccess.map(json.readTree(
                "{\"id\":\"s1\",\"name\":\"Budget\",\"mimeType\":\"application/vnd.google-apps.spreadsheet\",\"size\":\"1024\","
                        + "\"capabilities\":{\"canDownload\":true}}"));
        assertThat(sheet.kind()).isEqualTo(FileKind.PROVIDER_DOCUMENT);
        assertThat(sheet.sizeBytes()).isNull();
        assertThat(sheet.canDownload()).isFalse();
        assertThat(sheet.modifiedAt()).isNull();

        ProviderFile blocked = GoogleDriveBrowserTestAccess.map(json.readTree(
                "{\"id\":\"f1\",\"name\":\"x.pdf\",\"mimeType\":\"application/pdf\",\"size\":\"10\","
                        + "\"modifiedTime\":\"bad\",\"capabilities\":{\"canDownload\":false}}"));
        assertThat(blocked.kind()).isEqualTo(FileKind.FILE);
        assertThat(blocked.sizeBytes()).isEqualTo(10);
        assertThat(blocked.canDownload()).isFalse();
        assertThat(blocked.modifiedAt()).isNull();
        assertThat(blocked.toString()).doesNotContain("x.pdf");

        assertThat(GoogleDriveBrowserTestAccess.map(json.readTree("{\"id\":\"bad id\"}"))).isNull();
    }

    @Test
    void providerErrorsAreClassifiedByStatusAndReason() {
        assertThat(GoogleDriveBrowserTestAccess.classify(401, null, "{}")).isEqualTo(Category.AUTHENTICATION);
        assertThat(GoogleDriveBrowserTestAccess.classify(403, null,
                "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}")).isEqualTo(Category.RATE_LIMITED);
        assertThat(GoogleDriveBrowserTestAccess.classify(403, null,
                "{\"error\":{\"errors\":[{\"reason\":\"dailyLimitExceeded\"}]}}")).isEqualTo(Category.RATE_LIMITED);
        assertThat(GoogleDriveBrowserTestAccess.classify(403, null,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}")).isEqualTo(Category.INSUFFICIENT_SCOPE);
        assertThat(GoogleDriveBrowserTestAccess.classify(403, null,
                "{\"error\":{\"errors\":[{\"reason\":\"forbidden\"}]}}")).isEqualTo(Category.PERMISSION_DENIED);
        assertThat(GoogleDriveBrowserTestAccess.classify(403, null, "not json")).isEqualTo(Category.PERMISSION_DENIED);
        assertThat(GoogleDriveBrowserTestAccess.classify(404, null, "{}")).isEqualTo(Category.NOT_FOUND);
        assertThat(GoogleDriveBrowserTestAccess.classify(429, "7", "{}")).isEqualTo(Category.RATE_LIMITED);
        assertThat(GoogleDriveBrowserTestAccess.classify(503, null, "{}")).isEqualTo(Category.UNAVAILABLE);
        assertThat(GoogleDriveBrowserTestAccess.retryAfter("7")).isEqualTo(Duration.ofSeconds(7));
        assertThat(GoogleDriveBrowserTestAccess.retryAfter("soon")).isNull();
        assertThat(GoogleDriveBrowserTestAccess.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO);
    }
}
