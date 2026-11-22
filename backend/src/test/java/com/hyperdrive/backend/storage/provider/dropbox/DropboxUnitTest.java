package com.hyperdrive.backend.storage.provider.dropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.storage.connect.StorageConnectionController;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.files.ContinuationCodec;
import com.hyperdrive.backend.storage.provider.ProviderException.Category;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import com.hyperdrive.backend.storage.provider.ProviderFile.FileKind;
import com.hyperdrive.backend.storage.provider.ProviderHttp.Response;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.web.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Pure unit tests for the Dropbox adapter, continuation envelopes and provider validation. */
class DropboxUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Category classify(int status, String retryAfter, String body) {
        return DropboxBrowser.classifyResponse(new Response(status, retryAfter, body.getBytes(StandardCharsets.UTF_8)))
                .category();
    }

    @Test
    void errorsAreClassifiedPerDropboxSemantics() {
        assertThat(classify(401, null, "{\"error\":{\".tag\":\"expired_access_token\"}}")).isEqualTo(Category.AUTHENTICATION);
        assertThat(classify(401, null, "{\"error\":{\".tag\":\"invalid_access_token\"}}")).isEqualTo(Category.AUTHENTICATION);
        assertThat(classify(401, null, "{\"error\":{\".tag\":\"missing_scope\"}}")).isEqualTo(Category.INSUFFICIENT_SCOPE);
        assertThat(classify(403, null, "{}")).isEqualTo(Category.PERMISSION_DENIED);
        assertThat(classify(409, null, "{\"error\":{\".tag\":\"reset\"}}")).isEqualTo(Category.CURSOR_RESET);
        assertThat(classify(409, null, "{\"error\":{\".tag\":\"path\",\"path\":{\".tag\":\"not_found\"}}}"))
                .isEqualTo(Category.NOT_FOUND);
        assertThat(classify(409, null, "{\"error\":{\".tag\":\"path\",\"path\":{\".tag\":\"restricted_content\"}}}"))
                .isEqualTo(Category.PERMISSION_DENIED);
        assertThat(classify(422, null, "{}")).isEqualTo(Category.ROOT_UNAVAILABLE);
        assertThat(classify(429, "3", "{}")).isEqualTo(Category.RATE_LIMITED);
        assertThat(classify(500, null, "not json")).isEqualTo(Category.UNAVAILABLE);
        assertThat(classify(400, null, "Error in call to API function")).isEqualTo(Category.INVALID_RESPONSE);
        assertThat(DropboxBrowser.classifyResponse(new Response(429, null,
                "{\"error\":{\"retry_after\":7}}".getBytes(StandardCharsets.UTF_8))).retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void errorMessagesNeverEchoProviderText() {
        String message = DropboxBrowser.classifyResponse(new Response(409, null,
                "{\"error\":{\".tag\":\"path\",\"path\":{\".tag\":\"<script>/secret/file\"}}}".getBytes(StandardCharsets.UTF_8)))
                .getMessage();
        assertThat(message).doesNotContain("script", "secret").endsWith("unknown");
    }

    @Test
    void metadataMappingNeverInventsValues() throws Exception {
        ProviderFile folder = DropboxBrowser.toProviderFile(JSON.readTree(
                "{\".tag\":\"folder\",\"name\":\"A\",\"id\":\"id:abc\",\"path_lower\":\"/a\"}"));
        assertThat(folder.kind()).isEqualTo(FileKind.FOLDER);
        assertThat(folder.directoryLocator()).isEqualTo("/a");
        assertThat(folder.mimeType()).isNull();
        assertThat(folder.sizeBytes()).isNull();

        ProviderFile file = DropboxBrowser.toProviderFile(JSON.readTree(
                "{\".tag\":\"file\",\"name\":\"x\",\"id\":\"id:f1\",\"size\":9,\"rev\":\"r1\",\"content_hash\":\"h\"}"));
        assertThat(file.kind()).isEqualTo(FileKind.FILE);
        assertThat(file.sizeBytes()).isEqualTo(9);
        assertThat(file.modifiedAt()).isNull();
        assertThat(file.canDownload()).isTrue(); // documented default of is_downloadable
        assertThat(file.contentHashAlgorithm()).isEqualTo("dropbox-content-hash");
        assertThat(file.toString()).doesNotContain("x");

        assertThat(DropboxBrowser.toProviderFile(JSON.readTree("{\".tag\":\"file\",\"id\":\"not-an-id\"}"))).isNull();
        assertThat(DropboxBrowser.toProviderFile(JSON.readTree("{\".tag\":\"mystery\",\"id\":\"id:z\"}"))).isNull();
    }

    @Test
    void pathRootHeaderAcceptsOnlyNumericNamespaces() {
        assertThat(DropboxBrowser.pathRootHeader("3235641")).isEqualTo("{\".tag\":\"root\",\"root\":\"3235641\"}");
        assertThatThrownBy(() -> DropboxBrowser.pathRootHeader("1\"}, {\"x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DropboxBrowser.pathRootHeader(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "relative", "/a//b", "/a\u0000b", "/a\nb"})
    void invalidDropboxDirectoriesAreRejected(String directory) {
        assertThat(StorageProvider.DROPBOX.isValidDirectory(directory)).isFalse();
    }

    @Test
    void validLocatorsAreAccepted() {
        assertThat(StorageProvider.DROPBOX.isValidDirectory("/photos/2024 trip/über")).isTrue();
        assertThat(StorageProvider.DROPBOX.isValidFileId("id:a4ayc_80_OEAAAAAAAAAYa")).isTrue();
        assertThat(StorageProvider.DROPBOX.isValidFileId("/photos")).isFalse();
        assertThat(StorageProvider.GOOGLE_DRIVE.isValidFileId("id:abc")).isFalse();
    }

    @Test
    void continuationsAreBoundToAccountProviderAndDirectory() {
        ContinuationCodec codec = new ContinuationCodec(CredentialCipher.fromConfiguration(
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", 1, ""));
        UUID account = UUID.randomUUID();
        String token = codec.wrap(account, StorageProvider.DROPBOX, "/a", "raw-provider-cursor");

        assertThat(token).startsWith("hdk_").doesNotContain("raw-provider-cursor");
        assertThat(codec.unwrap(account, StorageProvider.DROPBOX, "/a", token)).contains("raw-provider-cursor");
        assertThat(codec.unwrap(UUID.randomUUID(), StorageProvider.DROPBOX, "/a", token)).isEmpty();
        assertThat(codec.unwrap(account, StorageProvider.GOOGLE_DRIVE, "/a", token)).isEmpty();
        assertThat(codec.unwrap(account, StorageProvider.DROPBOX, "/b", token)).isEmpty();
        assertThat(codec.unwrap(account, StorageProvider.DROPBOX, null, token)).isEmpty();
        assertThat(codec.unwrap(account, StorageProvider.DROPBOX, "/a", "hdk_garbage")).isEmpty();
        assertThat(codec.unwrap(account, StorageProvider.DROPBOX, "/a", "raw-provider-cursor")).isEmpty();
        assertThat(codec.unwrap(account, StorageProvider.DROPBOX, "/a", token.substring(0, token.length() - 2) + "AA")).isEmpty();
    }

    @Test
    void unconfiguredDropboxRejectsConnectionAttemptsWithAClearCode() {
        AuthProperties auth = new AuthProperties(true, URI.create("http://127.0.0.1:18080"), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofMinutes(15), Duration.ofDays(30));
        StorageConnectionController controller = new StorageConnectionController(null, auth, List.of());
        SessionPrincipal principal = new SessionPrincipal(UUID.randomUUID(), UUID.randomUUID(), true);

        assertThatThrownBy(() -> controller.create(principal, "dropbox"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("provider_not_configured");
                    assertThat(e.status().value()).isEqualTo(503);
                });
        assertThat(controller.providers().getBody().items()).allMatch(p -> !p.available());
    }
}
