package com.hyperdrive.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.backend.support.FakeOidcProvider.DriveFile;
import com.hyperdrive.backend.support.FakeOidcProvider.Injected;
import com.hyperdrive.backend.support.StorageConnectionDriver;
import com.hyperdrive.backend.support.StorageIntegrationTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Read-only browsing through the backend: listing, metadata, refresh, provider errors, disconnect. */
class DriveBrowsingTest extends StorageIntegrationTest {

    private static final String SUB = "drive-sub-browse";
    private static final String ROOT = "root-" + SUB;

    private String token;
    private UUID accountId;

    @BeforeEach
    void connect() throws Exception {
        token = onboardedUser("login-browse", "browser");
        accountId = storage.connect(token, StorageConnectionDriver.driveLogin(SUB, "owner@example.com"));
        google.driveRequests.clear();
        google.tokenRequests.clear();
        google.refreshGrants.set(0);
    }

    /** Makes the stored Drive access token look expired (the HyperDrive session stays valid). */
    private void expireStoredAccessToken() {
        jdbc.sql("UPDATE provider_credentials SET access_token_expires_at = :t")
                .param("t", java.time.OffsetDateTime.ofInstant(clock.instant().minusSeconds(60), java.time.ZoneOffset.UTC))
                .update();
    }

    private String files(String query) {
        return "/api/v1/accounts/" + accountId + "/files" + (query.isEmpty() ? "" : "?" + query);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new java.util.HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    @Test
    void rootListingNormalizesEveryKindWithoutInventingSizes() throws Exception {
        google.add(SUB, DriveFile.folder("folderA", "Projects", ROOT));
        google.add(SUB, DriveFile.file("file1", "report.pdf", ROOT, 1234));
        google.add(SUB, new DriveFile("doc1", "Notes", "application/vnd.google-apps.document", ROOT, "999",
                "2026-03-01T00:00:00.000Z", false, false, null, null));
        google.add(SUB, new DriveFile("short1", "Link to Projects", "application/vnd.google-apps.shortcut", ROOT, null,
                null, false, false, "folderA", "application/vnd.google-apps.folder"));
        google.add(SUB, new DriveFile("nometa", "mystery.bin", "application/octet-stream", ROOT, null, null, false, true,
                null, null));
        google.add(SUB, new DriveFile("gone", "deleted.txt", "text/plain", ROOT, "5", null, true, true, null, null));

        JsonNode page = storage.apiJson(token, files(""));

        JsonNode items = page.get("items");
        assertThat(items).hasSize(5);
        assertThat(page.get("continuation").isNull()).isTrue();
        Map<String, JsonNode> byId = new java.util.HashMap<>();
        items.forEach(i -> byId.put(i.get("providerFileId").asString(), i));
        assertThat(byId).doesNotContainKey("gone");

        JsonNode folder = byId.get("folderA");
        assertThat(folder.get("kind").asString()).isEqualTo("FOLDER");
        assertThat(folder.get("sizeBytes").isNull()).isTrue();
        assertThat(folder.get("canDownload").asBoolean()).isFalse();

        JsonNode file = byId.get("file1");
        assertThat(file.get("kind").asString()).isEqualTo("FILE");
        assertThat(file.get("sizeBytes").asLong()).isEqualTo(1234);
        assertThat(file.get("modifiedAt").asString()).isEqualTo("2026-02-03T04:05:06Z");
        assertThat(file.get("canDownload").asBoolean()).isTrue();
        assertThat(file.get("mimeType").asString()).isEqualTo("application/pdf");

        JsonNode doc = byId.get("doc1");
        assertThat(doc.get("kind").asString()).isEqualTo("PROVIDER_DOCUMENT");
        assertThat(doc.get("sizeBytes").isNull()).as("native document size is not a download size").isTrue();
        assertThat(doc.get("canDownload").asBoolean()).isFalse();

        JsonNode shortcut = byId.get("short1");
        assertThat(shortcut.get("kind").asString()).isEqualTo("SHORTCUT");
        assertThat(shortcut.get("shortcutTargetId").asString()).isEqualTo("folderA");
        assertThat(shortcut.get("shortcutTargetMimeType").asString()).isEqualTo("application/vnd.google-apps.folder");
        assertThat(shortcut.get("sizeBytes").isNull()).isTrue();

        JsonNode noMeta = byId.get("nometa");
        assertThat(noMeta.get("sizeBytes").isNull()).isTrue();
        assertThat(noMeta.get("modifiedAt").isNull()).isTrue();

        assertThat(items.get(0).get("kind").asString()).isEqualTo("FOLDER");
    }

    @Test
    void listingRequestsOnlyNeededFieldsExcludesTrashAndUsesTheResolvedRootId() throws Exception {
        storage.apiJson(token, files(""));

        assertThat(google.driveRequests).hasSize(1);
        Map<String, String> params = query(google.driveRequests.get(0));
        assertThat(params.get("q")).isEqualTo("'" + ROOT + "' in parents and trashed = false");
        assertThat(params.get("fields")).isEqualTo("nextPageToken,files(id,name,mimeType,size,modifiedTime,trashed,"
                + "capabilities/canDownload,headRevisionId,md5Checksum,shortcutDetails/targetId,shortcutDetails/targetMimeType)");
        assertThat(params.get("pageSize")).isEqualTo("100");
        assertThat(params.get("corpora")).isEqualTo("user");
        assertThat(params.get("includeItemsFromAllDrives")).isEqualTo("false");
    }

    @Test
    void emptyFolderReturnsNoItemsAndNoCursor() throws Exception {
        google.add(SUB, DriveFile.folder("empty", "Empty", ROOT));
        JsonNode page = storage.apiJson(token, files("directory=empty"));
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("continuation").isNull()).isTrue();
    }

    @Test
    void nestedFoldersArePaginatedWithTheProviderCursor() throws Exception {
        google.add(SUB, DriveFile.folder("sub", "Sub", ROOT));
        for (int i = 0; i < 5; i++) {
            google.add(SUB, DriveFile.file("f" + i, "file" + i + ".pdf", "sub", i));
        }
        List<String> names = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = storage.apiJson(token, files("directory=sub&pageSize=2"
                    + (cursor == null ? "" : "&continuation=" + cursor)));
            page.get("items").forEach(i -> names.add(i.get("name").asString()));
            cursor = page.get("continuation").isNull() ? null : page.get("continuation").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(names).containsExactly("file0.pdf", "file1.pdf", "file2.pdf", "file3.pdf", "file4.pdf");
    }

    @Test
    void pageSizeIsBoundedServerSide() throws Exception {
        storage.apiJson(token, files("pageSize=5000"));
        storage.apiJson(token, files("pageSize=0"));
        assertThat(query(google.driveRequests.get(0)).get("pageSize")).isEqualTo("200");
        assertThat(query(google.driveRequests.get(1)).get("pageSize")).isEqualTo("1");
    }

    @Test
    void malformedIdentifiersAreRejectedBeforeAnyProviderCall() throws Exception {
        assertThat(storage.api(token, files("directory=a'b")).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.api(token, files("directory=../x")).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.api(token, files("continuation=" + "x".repeat(2049))).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.api(token, "/api/v1/accounts/" + accountId + "/files/a%27b").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(storage.api(token, "/api/v1/accounts/not-a-uuid/files").getResponse().getStatus()).isEqualTo(400);
        assertThat(google.driveRequests).isEmpty();
    }

    @Test
    void fileMetadataIsReturnedAndMissingFilesAreNotFound() throws Exception {
        google.add(SUB, DriveFile.file("meta1", "photo.jpg", ROOT, 42));
        JsonNode file = storage.apiJson(token, "/api/v1/accounts/" + accountId + "/files/meta1");
        assertThat(file.get("name").asString()).isEqualTo("photo.jpg");
        assertThat(file.get("sizeBytes").asLong()).isEqualTo(42);

        MvcResult missing = storage.api(token, "/api/v1/accounts/" + accountId + "/files/nope");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(missing).get("code").asString()).isEqualTo("file_not_found");
        assertThat(accountStatus(accountId)).isEqualTo("CONNECTED");
    }

    @Test
    void anotherUsersAccountIsIndistinguishableFromAMissingOneAndNeverReachesTheProvider() throws Exception {
        String mallory = onboardedUser("login-mallory", "mallory");
        google.driveRequests.clear();

        MvcResult list = storage.api(mallory, files(""));
        MvcResult get = storage.api(mallory, "/api/v1/accounts/" + accountId);
        MvcResult file = storage.api(mallory, "/api/v1/accounts/" + accountId + "/files/" + ROOT);
        MvcResult missing = storage.api(mallory, "/api/v1/accounts/" + UUID.randomUUID() + "/files");
        MvcResult delete = storage.disconnect(mallory, accountId);

        for (MvcResult result : List.of(list, get, file, missing, delete)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(json(result).get("code").asString()).isEqualTo("account_not_found");
        }
        assertThat(google.driveRequests).isEmpty();
        assertThat(count("connected_accounts")).isEqualTo(1);
        assertThat(storage.apiJson(mallory, "/api/v1/accounts").get("items")).isEmpty();
    }

    @Test
    void expiredAccessTokenIsRefreshedServerSideKeepingTheRefreshToken() throws Exception {
        String refreshBefore = storedRefreshToken(accountId);
        expireStoredAccessToken();

        storage.apiJson(token, files(""));

        assertThat(google.refreshGrants.get()).isEqualTo(1);
        assertThat(storedRefreshToken(accountId)).isEqualTo(refreshBefore);
        // The new access token is cached: no second refresh.
        storage.apiJson(token, files(""));
        assertThat(google.refreshGrants.get()).isEqualTo(1);
    }

    @Test
    void rotatedRefreshTokenReplacesTheStoredOne() throws Exception {
        String refreshBefore = storedRefreshToken(accountId);
        google.rotateRefreshTokens = true;
        expireStoredAccessToken();

        storage.apiJson(token, files(""));

        assertThat(storedRefreshToken(accountId)).isNotEqualTo(refreshBefore).startsWith("1//fake-google-refresh-");
    }

    @Test
    void concurrentRequestsWithAnExpiredTokenRefreshOnce() throws Exception {
        expireStoredAccessToken();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                calls.add(() -> storage.api(token, files("")).getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(google.refreshGrants.get()).isEqualTo(1);
    }

    @Test
    void revokedGrantMarksTheAccountForReconnectWithoutErasingCredentials() throws Exception {
        google.revokeRefreshTokens(SUB);
        google.revokeAccessTokens(SUB);
        expireStoredAccessToken();
        long credentialRows = count("provider_credentials");

        MvcResult result = storage.api(token, files(""));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asString()).isEqualTo("account_needs_reconnect");
        assertThat(accountStatus(accountId)).isEqualTo("NEEDS_RECONNECT");
        assertThat(count("provider_credentials")).isEqualTo(credentialRows);
        assertThat(storedRefreshToken(accountId)).isNotNull();
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + accountId).get("lastErrorCode").asString())
                .isEqualTo("refresh_revoked");

        // No further provider calls until the user reconnects.
        int grants = google.refreshGrants.get();
        google.driveRequests.clear();
        assertThat(storage.api(token, files("")).getResponse().getStatus()).isEqualTo(409);
        assertThat(google.refreshGrants.get()).isEqualTo(grants);
        assertThat(google.driveRequests).isEmpty();

        // Reconnecting the same Drive account restores it.
        storage.connect(token, StorageConnectionDriver.driveLogin(SUB, "owner@example.com"));
        assertThat(accountStatus(accountId)).isEqualTo("CONNECTED");
        assertThat(storage.api(token, files("")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void transientRefreshFailureKeepsTheAccountConnected() throws Exception {
        google.refreshFailures.add(new Injected(503, Map.of(), "{\"error\":\"temporarily_unavailable\"}"));
        expireStoredAccessToken();

        MvcResult result = storage.api(token, files(""));

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(json(result).get("code").asString()).isEqualTo("provider_unavailable");
        assertThat(accountStatus(accountId)).isEqualTo("CONNECTED");
        assertThat(storage.api(token, files("")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void driveRejectingTheTokenTriggersOneRefreshAndRetry() throws Exception {
        google.revokeAccessTokens(SUB); // token still looks unexpired locally

        assertThat(storage.api(token, files("")).getResponse().getStatus()).isEqualTo(200);
        assertThat(google.refreshGrants.get()).isEqualTo(1);
    }

    @Test
    void rateLimitsAreRetriedWithBackoff() throws Exception {
        google.driveFailures.add(new Injected(403, Map.of(),
                "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"));
        google.driveFailures.add(new Injected(429, Map.of(), "{\"error\":{\"code\":429}}"));

        assertThat(storage.api(token, files("")).getResponse().getStatus()).isEqualTo(200);
        assertThat(google.driveRequests).hasSize(3);
    }

    @Test
    void longRetryAfterIsPassedToTheClientInsteadOfBlocking() throws Exception {
        google.driveFailures.add(new Injected(429, Map.of("Retry-After", "120"), "{\"error\":{\"code\":429}}"));

        MvcResult result = storage.api(token, files(""));

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("120");
        assertThat(json(result).get("code").asString()).isEqualTo("provider_rate_limited");
        assertThat(google.driveRequests).hasSize(1);
        assertThat(accountStatus(accountId)).isEqualTo("CONNECTED");
    }

    @Test
    void permissionDeniedIsNotTreatedAsAnExpiredToken() throws Exception {
        google.driveFailures.add(new Injected(403, Map.of(),
                "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"forbidden\"}]}}"));

        MvcResult result = storage.api(token, files(""));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(result).get("code").asString()).isEqualTo("provider_permission_denied");
        assertThat(google.refreshGrants.get()).isZero();
        assertThat(google.driveRequests).hasSize(1);
        assertThat(accountStatus(accountId)).isEqualTo("CONNECTED");
    }

    @Test
    void insufficientScopeRequiresReconnect() throws Exception {
        google.driveFailures.add(new Injected(403, Map.of(), "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\","
                + "\"details\":[{\"reason\":\"ACCESS_TOKEN_SCOPE_INSUFFICIENT\"}]}}"));

        MvcResult result = storage.api(token, files(""));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(accountStatus(accountId)).isEqualTo("NEEDS_RECONNECT");
        assertThat(google.refreshGrants.get()).isZero();
    }

    @Test
    void serverErrorsAreRetriedABoundedNumberOfTimes() throws Exception {
        for (int i = 0; i < 3; i++) {
            google.driveFailures.add(new Injected(500, Map.of(), "{}"));
        }
        MvcResult result = storage.api(token, files(""));
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(json(result).get("code").asString()).isEqualTo("provider_unavailable");
        assertThat(google.driveRequests).hasSize(3); // 1 + maxRetries(2)
    }

    @Test
    void disconnectRemovesOnlyTheLinkAndCredentials() throws Exception {
        google.add(SUB, DriveFile.file("keep", "keep.pdf", ROOT, 7));
        int driveFiles = google.drive(SUB).size();

        MvcResult result = storage.disconnect(token, accountId);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(count("connected_accounts")).isZero();
        assertThat(count("provider_credentials")).isZero();
        assertThat(count("users")).isEqualTo(1);
        assertThat(google.drive(SUB)).hasSize(driveFiles);
        assertThat(google.driveRequests).isEmpty();
        // Still signed in to HyperDrive.
        assertThat(storage.api(token, "/api/v1/me").getResponse().getStatus()).isEqualTo(200);
        assertThat(storage.apiJson(token, "/api/v1/accounts").get("items")).isEmpty();
        assertThat(storage.api(token, files("")).getResponse().getStatus()).isEqualTo(404);
        assertThat(storage.disconnect(token, accountId).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void browsingRequiresAnOnboardedSession() throws Exception {
        String notOnboarded = login.signIn("login-new-browse", "n@example.com").accessToken();
        assertThat(storage.api(notOnboarded, "/api/v1/accounts").getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(files("")))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
