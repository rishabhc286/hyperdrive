package com.hyperdrive.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.backend.support.FakeDropbox;
import com.hyperdrive.backend.support.FakeDropbox.Account;
import com.hyperdrive.backend.support.FakeDropbox.Injected;
import com.hyperdrive.backend.support.StorageConnectionDriver;
import com.hyperdrive.backend.support.StorageConnectionDriver.AtGoogle;
import com.hyperdrive.backend.support.StorageConnectionDriver.Started;
import com.hyperdrive.backend.support.StorageIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Dropbox: separate consent, identity, root namespace, browsing, continuation, isolation from Drive. */
class DropboxTest extends StorageIntegrationTest {

    private static final Account PERSONAL = new Account("dbid:AAH4f99T0taONIb-OurWxbNQ6ywGRopQngc", "Dropbox Owner",
            "someone.else@dropbox.example", true, "user", "3235641", null, FakeDropbox.SCOPES, true);

    /** MockMvc URI templates encode query and path values themselves; pass values through unchanged. */
    private static String q(String value) {
        return value;
    }

    private String files(UUID accountId, String query) {
        return "/api/v1/accounts/" + accountId + "/files" + (query.isEmpty() ? "" : "?" + query);
    }

    // --- Connection -----------------------------------------------------------------------------

    @Test
    void connectsADropboxAccountWithItsOwnIdentityWithoutTouchingTheLogin() throws Exception {
        String token = onboardedUser("login-dbx-1", "alice");
        long identities = count("auth_identities");

        UUID accountId = storage.connectDropbox(token, dropbox, PERSONAL);

        JsonNode account = storage.apiJson(token, "/api/v1/accounts/" + accountId);
        assertThat(account.get("provider").asString()).isEqualTo("DROPBOX");
        assertThat(account.get("email").asString()).isEqualTo("someone.else@dropbox.example");
        assertThat(account.get("displayName").asString()).isEqualTo("Dropbox Owner");
        assertThat(account.get("rootType").asString()).isEqualTo("USER");
        assertThat(account.get("status").asString()).isEqualTo("CONNECTED");
        assertThat(count("auth_identities")).isEqualTo(identities);
        assertThat(count("users")).isEqualTo(1);
        Map<String, Object> row = jdbc.sql("SELECT provider_account_id, provider_issuer, root_namespace_id, root_folder_id "
                + "FROM connected_accounts").query().singleRow();
        assertThat(row.get("provider_account_id")).isEqualTo(PERSONAL.accountId());
        assertThat(row.get("provider_issuer")).isEqualTo("https://www.dropbox.com");
        assertThat(row.get("root_namespace_id")).isEqualTo("3235641");
        assertThat(row.get("root_folder_id")).isNull();
    }

    @Test
    void authorizationRequestUsesTheDocumentedParametersAndOnlyReadScopes() throws Exception {
        String token = onboardedUser("login-dbx-2", "bob");
        AtGoogle atDropbox = storage.open(storage.create(token, "dropbox"));

        Map<String, String> p = atDropbox.params();
        assertThat(atDropbox.location().toString()).startsWith(dropbox.baseUrl() + "/oauth2/authorize");
        assertThat(p.get("response_type")).isEqualTo("code");
        assertThat(p.get("client_id")).isEqualTo(FakeDropbox.APP_KEY);
        assertThat(p.get("redirect_uri")).isEqualTo("http://localhost/api/v1/storage-connections/dropbox/callback");
        assertThat(p.get("scope").split(" ")).containsExactlyInAnyOrder("account_info.read", "files.metadata.read", "files.content.read");
        assertThat(p.get("token_access_type")).isEqualTo("offline");
        assertThat(p.get("state")).isNotBlank();
        assertThat(p).doesNotContainKeys("code_challenge", "nonce");
        assertThat(p.get("scope")).doesNotContain("write", "sharing", "team");
    }

    @Test
    void tokenExchangeUsesBasicClientAuthenticationAndStoresEncryptedTokens() throws Exception {
        String token = onboardedUser("login-dbx-3", "carol");
        UUID accountId = storage.connectDropbox(token, dropbox, PERSONAL);

        Map<String, String> exchange = dropbox.tokenRequests.get(0);
        assertThat(exchange.get("grant_type")).isEqualTo("authorization_code");
        assertThat(exchange).doesNotContainKeys("code_verifier", "client_secret");
        assertThat(dropbox.tokenAuthorizationHeaders.get(0)).startsWith("Basic ");

        Map<String, Object> creds = jdbc.sql("SELECT * FROM provider_credentials WHERE connected_account_id = :id")
                .param("id", accountId).query().singleRow();
        String dump = creds.values().stream().map(v -> v instanceof byte[] b ? new String(b, StandardCharsets.ISO_8859_1)
                : String.valueOf(v)).reduce("", String::concat);
        for (String issued : dropbox.issuedTokens) {
            assertThat(dump).doesNotContain(issued);
            assertThat(storage.transcript).noneMatch(entry -> entry.contains(issued));
        }
        assertThat(storedRefreshToken(accountId)).startsWith("fake-dropbox-refresh-");
    }

    @Test
    void creatingADropboxTransactionRequiresAnOnboardedSession() throws Exception {
        assertThat(storage.createRaw(null, "dropbox").getResponse().getStatus()).isEqualTo(401);
        String notOnboarded = login.signIn("login-dbx-new", "n@example.com").accessToken();
        assertThat(storage.createRaw(notOnboarded, "dropbox").getResponse().getStatus()).isEqualTo(403);
        assertThat(storage.createRaw(notOnboarded, "onedrive").getResponse().getStatus()).isIn(403, 404);
        assertThat(count("storage_connection_transactions")).isZero();
    }

    @Test
    void stateIsBoundToProviderTransactionAndSingleUse() throws Exception {
        String token = onboardedUser("login-dbx-4", "dave");
        Started googleTx = storage.create(token, "google");
        AtGoogle atGoogle = storage.open(googleTx);

        // A Google state presented at the Dropbox callback is unknown there.
        MvcResult crossed = storage.callback("dropbox", Map.of("code", "x", "state", atGoogle.params().get("state")),
                atGoogle.binding());
        assertThat(crossed.getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.statusJson(token, googleTx.transactionId()).get("status").asString()).isEqualTo("PENDING");

        Started dbxTx = storage.create(token, "dropbox");
        AtGoogle atDropbox = storage.open(dbxTx);
        String code = "dropbox-code-replay";
        dropbox.prepare(code, PERSONAL);
        Map<String, String> params = Map.of("code", code, "state", atDropbox.params().get("state"));
        assertThat(storage.callback("dropbox", params, atDropbox.binding()).getResponse().getStatus()).isEqualTo(200);
        assertThat(storage.callback("dropbox", params, atDropbox.binding()).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.callback("dropbox", Map.of("code", "x", "state", "forged"), atDropbox.binding())
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(count("connected_accounts")).isEqualTo(1);
    }

    @Test
    void concurrentGoogleAndDropboxAttemptsEachCompleteToTheirOwnProvider() throws Exception {
        String token = onboardedUser("login-dbx-5", "erin");
        Started g = storage.create(token, "google");
        Started d = storage.create(token, "dropbox");
        AtGoogle atGoogle = storage.open(g);
        AtGoogle atDropbox = storage.open(d);

        storage.dropboxConsent(dropbox, atDropbox, PERSONAL);
        storage.consent(atGoogle, StorageConnectionDriver.driveLogin("drive-sub-dbx5", "drive@example.com"));

        JsonNode gs = storage.statusJson(token, g.transactionId());
        JsonNode ds = storage.statusJson(token, d.transactionId());
        assertThat(gs.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ds.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + gs.get("accountId").asString()).get("provider").asString())
                .isEqualTo("GOOGLE_DRIVE");
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + ds.get("accountId").asString()).get("provider").asString())
                .isEqualTo("DROPBOX");
    }

    @Test
    void transactionStatusIsVisibleOnlyToTheOwner() throws Exception {
        String alice = onboardedUser("login-dbx-6", "alice6");
        String mallory = onboardedUser("login-dbx-7", "mallory7");
        Started d = storage.create(alice, "dropbox");
        assertThat(storage.status(mallory, d.transactionId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(storage.cancel(mallory, d.transactionId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(storage.statusJson(alice, d.transactionId()).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void missingScopesAndAccountMismatchFailWithoutStoringAnything() throws Exception {
        String token = onboardedUser("login-dbx-8", "frank");
        Started first = storage.create(token, "dropbox");
        Account metadataOnly = new Account(PERSONAL.accountId(), "D", "d@example.com", true, "user", "3235641", null,
                "account_info.read", true);
        storage.dropboxConsent(dropbox, storage.open(first), metadataOnly);
        assertThat(storage.statusJson(token, first.transactionId()).get("errorCode").asString()).isEqualTo("scope_not_granted");

        Started second = storage.create(token, "dropbox");
        dropbox.overrideCurrentAccountId = "dbid:SomebodyElse";
        storage.dropboxConsent(dropbox, storage.open(second), PERSONAL);
        assertThat(storage.statusJson(token, second.transactionId()).get("errorCode").asString()).isEqualTo("identity_invalid");
        assertThat(count("connected_accounts")).isZero();
        assertThat(count("provider_credentials")).isZero();
    }

    @Test
    void unverifiedEmailIsNotStored() throws Exception {
        String token = onboardedUser("login-dbx-9", "gina");
        Account unverified = new Account("dbid:Unverified1", "U", "unverified@example.com", false, "user", "4242", null,
                FakeDropbox.SCOPES, true);
        UUID id = storage.connectDropbox(token, dropbox, unverified);
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + id).get("email").isNull()).isTrue();
    }

    @Test
    void reconnectingTheSameDropboxAccountUpdatesTheRowAndKeepsTheRefreshToken() throws Exception {
        String token = onboardedUser("login-dbx-10", "hank");
        UUID first = storage.connectDropbox(token, dropbox, PERSONAL);
        String refresh = storedRefreshToken(first);

        Account again = new Account(PERSONAL.accountId(), "Renamed", PERSONAL.email(), true, "user", "3235641", null,
                FakeDropbox.SCOPES, false);
        Started tx = storage.create(token, "dropbox");
        storage.dropboxConsent(dropbox, storage.open(tx), again);
        JsonNode status = storage.statusJson(token, tx.transactionId());

        assertThat(status.get("reconnected").asBoolean()).isTrue();
        assertThat(status.get("accountId").asString()).isEqualTo(first.toString());
        assertThat(count("connected_accounts")).isEqualTo(1);
        assertThat(storedRefreshToken(first)).isEqualTo(refresh);
    }

    @Test
    void twoDifferentDropboxAccountsOfOneUserCoexist() throws Exception {
        String token = onboardedUser("login-dbx-11", "ivan");
        UUID a = storage.connectDropbox(token, dropbox, PERSONAL);
        UUID b = storage.connectDropbox(token, dropbox, Account.personal("dbid:SecondAccount", "second@example.com"));
        assertThat(a).isNotEqualTo(b);
        assertThat(storage.apiJson(token, "/api/v1/accounts").get("items")).hasSize(2);
    }

    // --- Browsing -------------------------------------------------------------------------------

    @Test
    void rootListingUsesThePathRootHeaderAndNormalizesMetadata() throws Exception {
        String token = onboardedUser("login-dbx-12", "judy");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.folder("3235641", "", "Projects");
        dropbox.file("3235641", "", "Report.pdf", 1234);
        dropbox.deleted("3235641", "", "Old.txt");
        Map<String, Object> bare = dropbox.file("3235641", "", "Bare.bin", 5);
        bare.remove("server_modified");
        bare.remove("size");
        bare.remove("content_hash");
        bare.remove("rev");
        Map<String, Object> paper = dropbox.file("3235641", "", "Notes.paper", 0);
        paper.put("is_downloadable", false);
        paper.put("export_info", Map.of("export_as", "md"));
        Map<String, Object> link = dropbox.file("3235641", "", "Link", 0);
        link.put("symlink_info", Map.of("target", "/elsewhere"));
        dropbox.apiRequests.clear();

        JsonNode page = storage.apiJson(token, files(id, ""));

        FakeDropbox.Request request = dropbox.apiRequests.get(0);
        assertThat(request.endpoint()).isEqualTo("files/list_folder");
        assertThat(request.pathRoot()).isEqualTo("{\".tag\":\"root\",\"root\":\"3235641\"}");
        JsonNode args = com.hyperdrive.backend.support.DesktopLoginDriver.JSON.readTree(request.body());
        assertThat(args.get("path").asString()).isEmpty();
        assertThat(args.get("recursive").asBoolean()).isFalse();
        assertThat(args.get("include_deleted").asBoolean()).isFalse();

        Map<String, JsonNode> byName = new HashMap<>();
        page.get("items").forEach(i -> byName.put(i.get("name").asString(), i));
        assertThat(byName).containsOnlyKeys("Projects", "Report.pdf", "Bare.bin", "Notes.paper", "Link");
        JsonNode folder = byName.get("Projects");
        assertThat(folder.get("kind").asString()).isEqualTo("FOLDER");
        assertThat(folder.get("providerFileId").asString()).startsWith("id:");
        assertThat(folder.get("directoryLocator").asString()).isEqualTo("/projects");
        assertThat(folder.get("sizeBytes").isNull()).isTrue();
        assertThat(folder.get("modifiedAt").isNull()).isTrue();
        JsonNode file = byName.get("Report.pdf");
        assertThat(file.get("kind").asString()).isEqualTo("FILE");
        assertThat(file.get("mimeType").isNull()).as("Dropbox has no MIME types; none invented").isTrue();
        assertThat(file.get("sizeBytes").asLong()).isEqualTo(1234);
        assertThat(file.get("modifiedAt").asString()).isEqualTo("2026-03-02T11:00:00Z");
        assertThat(file.get("providerRevision").asString()).isEqualTo("a1c10ce0dd78");
        assertThat(file.get("contentHashAlgorithm").asString()).isEqualTo("dropbox-content-hash");
        assertThat(file.get("canDownload").asBoolean()).isTrue();
        assertThat(file.get("directoryLocator").isNull()).isTrue();
        JsonNode bareFile = byName.get("Bare.bin");
        assertThat(bareFile.get("sizeBytes").isNull()).isTrue();
        assertThat(bareFile.get("modifiedAt").isNull()).isTrue();
        assertThat(bareFile.get("contentHash").isNull()).isTrue();
        assertThat(bareFile.get("providerRevision").isNull()).isTrue();
        assertThat(byName.get("Notes.paper").get("kind").asString()).isEqualTo("PROVIDER_DOCUMENT");
        assertThat(byName.get("Notes.paper").get("canDownload").asBoolean()).isFalse();
        assertThat(byName.get("Link").get("kind").asString()).isEqualTo("SHORTCUT");
        assertThat(byName.get("Link").get("shortcutTargetId").isNull()).as("links are never followed").isTrue();
        assertThat(page.get("continuation").isNull()).isTrue();
    }

    @Test
    void teamAccountsListTheTeamSpaceRootNotTheMemberFolder() throws Exception {
        String token = onboardedUser("login-dbx-13", "kim");
        Account team = new Account("dbid:TeamMember1", "Team Member", "member@team.example", true, "team", "777",
                "888", FakeDropbox.SCOPES, true);
        UUID id = storage.connectDropbox(token, dropbox, team);
        dropbox.folder("777", "", "Team Shared");
        dropbox.folder("888", "", "Member Only");

        JsonNode items = storage.apiJson(token, files(id, "")).get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("name").asString()).isEqualTo("Team Shared");
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + id).get("rootType").asString()).isEqualTo("TEAM");
    }

    @Test
    void nestedFoldersPaginateWithAccountBoundContinuations() throws Exception {
        String token = onboardedUser("login-dbx-14", "lena");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.folder("3235641", "", "Photos");
        for (int i = 0; i < 5; i++) {
            dropbox.file("3235641", "/photos", "p" + i + ".jpg", i);
        }
        List<String> names = new ArrayList<>();
        String continuation = null;
        int pages = 0;
        do {
            JsonNode page = storage.apiJson(token, files(id, "directory=" + q("/photos") + "&pageSize=2"
                    + (continuation == null ? "" : "&continuation=" + q(continuation))));
            page.get("items").forEach(i -> names.add(i.get("name").asString()));
            continuation = page.get("continuation").isNull() ? null : page.get("continuation").asString();
            if (continuation != null) {
                assertThat(continuation).startsWith("hdk_").doesNotContain("cur~");
            }
            pages++;
        } while (continuation != null);

        assertThat(pages).isEqualTo(3);
        assertThat(names).containsExactly("p0.jpg", "p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg");
        assertThat(dropbox.apiRequests.stream().map(FakeDropbox.Request::endpoint))
                .contains("files/list_folder", "files/list_folder/continue");
    }

    @Test
    void continuationsCannotCrossAccountsProvidersOrFolders() throws Exception {
        String token = onboardedUser("login-dbx-15", "mona");
        UUID a = storage.connectDropbox(token, dropbox, PERSONAL);
        UUID b = storage.connectDropbox(token, dropbox, Account.personal("dbid:OtherAccount", "o@example.com"));
        UUID g = storage.connect(token, StorageConnectionDriver.driveLogin("drive-sub-dbx15", "g@example.com"));
        dropbox.folder("3235641", "", "Big");
        for (int i = 0; i < 3; i++) {
            dropbox.file("3235641", "/big", "f" + i, i);
        }
        String continuation = storage.apiJson(token, files(a, "directory=" + q("/big") + "&pageSize=1"))
                .get("continuation").asString();
        dropbox.apiRequests.clear();
        google.driveRequests.clear();

        for (MvcResult r : List.of(
                storage.api(token, files(b, "directory=" + q("/big") + "&continuation=" + q(continuation))),
                storage.api(token, files(a, "directory=" + q("/other") + "&continuation=" + q(continuation))),
                storage.api(token, files(a, "continuation=" + q(continuation))),
                storage.api(token, files(g, "continuation=" + q(continuation))),
                storage.api(token, files(a, "directory=" + q("/big") + "&continuation=" + q("cur~3235641~/big~1~1"))))) {
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(json(r).get("code").asString()).isEqualTo("invalid_request");
        }
        assertThat(dropbox.apiRequests).isEmpty();
        assertThat(google.driveRequests).isEmpty();
    }

    @Test
    void emptyFoldersMissingFoldersAndResetCursors() throws Exception {
        String token = onboardedUser("login-dbx-16", "nina");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.folder("3235641", "", "Empty");
        dropbox.folder("3235641", "", "Many");
        for (int i = 0; i < 3; i++) {
            dropbox.file("3235641", "/many", "f" + i, i);
        }

        JsonNode empty = storage.apiJson(token, files(id, "directory=" + q("/empty")));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("continuation").isNull()).isTrue();

        MvcResult missing = storage.api(token, files(id, "directory=" + q("/nope")));
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(missing).get("code").asString()).isEqualTo("file_not_found");

        String continuation = storage.apiJson(token, files(id, "directory=" + q("/many") + "&pageSize=1"))
                .get("continuation").asString();
        dropbox.apiFailures.add(new Injected(409, Map.of(), "{\"error_summary\":\"reset/\",\"error\":{\".tag\":\"reset\"}}"));
        MvcResult reset = storage.api(token, files(id, "directory=" + q("/many") + "&continuation=" + q(continuation)));
        assertThat(reset.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(reset).get("code").asString()).isEqualTo("continuation_expired");
        assertThat(accountStatus(id)).isEqualTo("CONNECTED");
    }

    @Test
    void metadataByStableIdAndInvalidIdsRejectedBeforeProviderCalls() throws Exception {
        String token = onboardedUser("login-dbx-17", "olga");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        Map<String, Object> f = dropbox.file("3235641", "", "One.txt", 3);
        String fileId = (String) f.get("id");

        JsonNode meta = storage.apiJson(token, "/api/v1/accounts/" + id + "/files/" + q(fileId));
        assertThat(meta.get("name").asString()).isEqualTo("One.txt");
        assertThat(dropbox.apiRequests.getLast().endpoint()).isEqualTo("files/get_metadata");

        dropbox.apiRequests.clear();
        assertThat(storage.api(token, "/api/v1/accounts/" + id + "/files/" + q("/one.txt")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(storage.api(token, files(id, "directory=" + q("relative/path"))).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.api(token, files(id, "directory=" + q("/a//b"))).getResponse().getStatus()).isEqualTo(400);
        assertThat(dropbox.apiRequests).isEmpty();
    }

    @Test
    void anotherUsersDropboxAccountNeverReachesDropbox() throws Exception {
        String alice = onboardedUser("login-dbx-18", "alice18");
        UUID id = storage.connectDropbox(alice, dropbox, PERSONAL);
        String mallory = onboardedUser("login-dbx-19", "mallory19");
        dropbox.apiRequests.clear();

        for (MvcResult r : List.of(storage.api(mallory, files(id, "")), storage.api(mallory, "/api/v1/accounts/" + id),
                storage.disconnect(mallory, id))) {
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(json(r).get("code").asString()).isEqualTo("account_not_found");
        }
        assertThat(dropbox.apiRequests).isEmpty();
        assertThat(count("connected_accounts")).isEqualTo(1);
    }

    @Test
    void expiredTokensAreRefreshedKeepingTheRefreshTokenAndRevocationNeedsReconnect() throws Exception {
        String token = onboardedUser("login-dbx-20", "pia");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        String refresh = storedRefreshToken(id);
        expire(id);

        assertThat(storage.api(token, files(id, "")).getResponse().getStatus()).isEqualTo(200);
        assertThat(dropbox.refreshGrants.get()).isEqualTo(1);
        assertThat(storedRefreshToken(id)).isEqualTo(refresh);

        dropbox.revoke(PERSONAL.accountId());
        expire(id);
        MvcResult revoked = storage.api(token, files(id, ""));
        assertThat(revoked.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(revoked).get("code").asString()).isEqualTo("account_needs_reconnect");
        assertThat(accountStatus(id)).isEqualTo("NEEDS_RECONNECT");
        assertThat(storedRefreshToken(id)).isEqualTo(refresh);
    }

    @Test
    void providerErrorsAreClassifiedAndSanitized() throws Exception {
        String token = onboardedUser("login-dbx-21", "quinn");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);

        dropbox.apiFailures.add(new Injected(429, Map.of("Retry-After", "0"),
                "{\"error_summary\":\"too_many_requests/\",\"error\":{\"reason\":{\".tag\":\"too_many_requests\"},\"retry_after\":0}}"));
        assertThat(storage.api(token, files(id, "")).getResponse().getStatus()).isEqualTo(200);

        dropbox.apiFailures.add(new Injected(429, Map.of("Retry-After", "300"), "{\"error_summary\":\"too_many_requests/\"}"));
        MvcResult limited = storage.api(token, files(id, ""));
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("300");

        for (int i = 0; i < 3; i++) {
            dropbox.apiFailures.add(new Injected(503, Map.of(), "<html>secret internal page</html>"));
        }
        MvcResult outage = storage.api(token, files(id, ""));
        assertThat(outage.getResponse().getStatus()).isEqualTo(502);
        assertThat(outage.getResponse().getContentAsString()).doesNotContain("secret internal page");

        dropbox.apiFailures.add(new Injected(403, Map.of(), "{\"error_summary\":\"no_permission/\"}"));
        assertThat(json(storage.api(token, files(id, ""))).get("code").asString()).isEqualTo("provider_permission_denied");
        assertThat(accountStatus(id)).isEqualTo("CONNECTED");

        dropbox.apiFailures.add(new Injected(401, Map.of(),
                "{\"error_summary\":\"missing_scope/\",\"error\":{\".tag\":\"missing_scope\",\"required_scope\":\"files.metadata.read\"}}"));
        int refreshes = dropbox.refreshGrants.get();
        assertThat(json(storage.api(token, files(id, ""))).get("code").asString()).isEqualTo("account_needs_reconnect");
        assertThat(dropbox.refreshGrants.get()).as("missing scope is not an expired token").isEqualTo(refreshes);
        assertThat(accountStatus(id)).isEqualTo("NEEDS_RECONNECT");
    }

    @Test
    void lostTeamRootRequiresReconnect() throws Exception {
        String token = onboardedUser("login-dbx-22", "rita");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.apiFailures.add(new Injected(422, Map.of(), "{\"error_summary\":\"no_permission/\"}"));
        assertThat(json(storage.api(token, files(id, ""))).get("code").asString()).isEqualTo("account_needs_reconnect");
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + id).get("lastErrorCode").asString()).isEqualTo("root_changed");
    }

    // --- Isolation between providers --------------------------------------------------------------

    @Test
    void googleAndDropboxCredentialsAreIsolatedAndDisconnectAffectsOnlyTheChosenAccount() throws Exception {
        String token = onboardedUser("login-dbx-23", "sam");
        UUID drive = storage.connect(token, StorageConnectionDriver.driveLogin("drive-sub-iso", "drive@example.com"));
        String driveRefresh = storedRefreshToken(drive);
        UUID box = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.file("3235641", "", "box.txt", 1);

        assertThat(storedRefreshToken(drive)).isEqualTo(driveRefresh);
        assertThat(storedRefreshToken(box)).startsWith("fake-dropbox-refresh-").isNotEqualTo(driveRefresh);
        // Ciphertexts are bound to their row: swapping them makes both unreadable rather than mixed up.
        assertThat(storage.api(token, files(drive, "")).getResponse().getStatus()).isEqualTo(200);
        assertThat(storage.api(token, files(box, "")).getResponse().getStatus()).isEqualTo(200);

        assertThat(storage.disconnect(token, box).getResponse().getStatus()).isEqualTo(204);

        assertThat(count("connected_accounts")).isEqualTo(1);
        assertThat(count("provider_credentials")).isEqualTo(1);
        assertThat(storedRefreshToken(drive)).isEqualTo(driveRefresh);
        assertThat(storage.api(token, files(drive, "")).getResponse().getStatus()).isEqualTo(200);
        assertThat(storage.api(token, "/api/v1/me").getResponse().getStatus()).isEqualTo(200);
        assertThat(dropbox.apiRequests.stream().map(FakeDropbox.Request::endpoint)).noneMatch(e -> e.contains("revoke"));
    }

    @Test
    void providerListReportsDropboxAsConfigured() throws Exception {
        String token = onboardedUser("login-dbx-24", "tina");
        JsonNode items = storage.apiJson(token, "/api/v1/storage-connections/providers").get("items");
        assertThat(items.toString()).contains("\"provider\":\"GOOGLE_DRIVE\",\"available\":true",
                "\"provider\":\"DROPBOX\",\"available\":true");
    }

    // --- Disconnect vs. revoke-and-disconnect (verification checkpoint) -------------------------

    @Test
    void ordinaryDisconnectIsLocalOnlyAndNeverCallsTheRevokeEndpoint() throws Exception {
        String token = onboardedUser("login-dbx-30", "una");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        String refresh = storedRefreshToken(id);

        assertThat(storage.disconnect(token, id).getResponse().getStatus()).isEqualTo(204);

        assertThat(dropbox.revokeCalls.get()).isZero();
        assertThat(dropbox.apiRequests.stream().map(FakeDropbox.Request::endpoint)).doesNotContain("auth/token/revoke");
        assertThat(count("connected_accounts")).isZero();
        assertThat(count("provider_credentials")).isZero();
        // The provider grant itself is untouched by a local unlink.
        assertThat(dropbox.issuedTokens).contains(refresh);
        assertThat(storage.api(token, "/api/v1/me").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void revokeAndDisconnectRevokesOnlyThisConnectionsGrantThenUnlinks() throws Exception {
        String alice = onboardedUser("login-dbx-31", "vera");
        UUID aliceId = storage.connectDropbox(alice, dropbox, PERSONAL);
        String bob = onboardedUser("login-dbx-32", "walt");
        UUID bobId = storage.connectDropbox(bob, dropbox, PERSONAL); // same Dropbox account, own grant
        dropbox.apiRequests.clear();

        MvcResult result = storage.revokeAndDisconnect(alice, aliceId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = json(result);
        assertThat(body.get("revocation").asString()).isEqualTo("confirmed");
        assertThat(body.get("disconnected").asBoolean()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sl.fake", "fake-dropbox-refresh");
        assertThat(dropbox.revokeCalls.get()).isEqualTo(1);
        assertThat(storage.api(alice, "/api/v1/accounts/" + aliceId).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM provider_credentials WHERE connected_account_id = :id")
                .param("id", aliceId).query(Long.class).single()).isZero();
        // The other HyperDrive user's connection to the same Dropbox account keeps working.
        assertThat(storage.api(bob, files(bobId, "")).getResponse().getStatus()).isEqualTo(200);
        expire(bobId);
        assertThat(storage.api(bob, files(bobId, "")).getResponse().getStatus()).isEqualTo(200);
        assertThat(storage.api(alice, "/api/v1/me").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failedOrUnansweredRevocationIsReportedAndStillDisconnects() throws Exception {
        String token = onboardedUser("login-dbx-33", "xena");
        UUID first = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.apiFailures.add(new Injected(503, Map.of(), "{}"));
        JsonNode unknown = json(storage.revokeAndDisconnect(token, first));
        assertThat(unknown.get("revocation").asString()).isEqualTo("unknown");
        assertThat(unknown.get("disconnected").asBoolean()).isTrue();
        assertThat(count("provider_credentials")).isZero();

        UUID second = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.apiFailures.add(new Injected(400, Map.of(), "{\"error_summary\":\"bad/\"}"));
        JsonNode failed = json(storage.revokeAndDisconnect(token, second));
        assertThat(failed.get("revocation").asString()).isEqualTo("failed");
        assertThat(failed.get("reason").asString()).isEqualTo("provider_rejected");
        assertThat(count("connected_accounts")).isZero();
    }

    @Test
    void anUnusableCredentialIsReportedAsFailedWithoutAProviderCall() throws Exception {
        String token = onboardedUser("login-dbx-34", "yuri");
        UUID id = storage.connectDropbox(token, dropbox, PERSONAL);
        dropbox.revoke(PERSONAL.accountId());
        expire(id);

        JsonNode body = json(storage.revokeAndDisconnect(token, id));

        assertThat(body.get("revocation").asString()).isEqualTo("failed");
        assertThat(body.get("reason").asString()).isEqualTo("no_usable_credential");
        assertThat(body.get("disconnected").asBoolean()).isTrue();
        assertThat(dropbox.revokeCalls.get()).isZero();
        assertThat(count("connected_accounts")).isZero();
    }

    @Test
    void revocationIsRefusedForGoogleDriveAndForeignAccounts() throws Exception {
        String token = onboardedUser("login-dbx-35", "zara");
        UUID drive = storage.connect(token, StorageConnectionDriver.driveLogin("drive-sub-35", "drive35@example.com"));
        MvcResult google = storage.revokeAndDisconnect(token, drive);
        assertThat(google.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(google).get("code").asString()).isEqualTo("revocation_unsupported");
        assertThat(accountStatus(drive)).isEqualTo("CONNECTED");

        UUID box = storage.connectDropbox(token, dropbox, PERSONAL);
        String mallory = onboardedUser("login-dbx-36", "mallory36");
        MvcResult foreign = storage.revokeAndDisconnect(mallory, box);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(dropbox.revokeCalls.get()).isZero();
        assertThat(accountStatus(box)).isEqualTo("CONNECTED");
    }

    private void expire(UUID accountId) {
        jdbc.sql("UPDATE provider_credentials SET access_token_expires_at = :t WHERE connected_account_id = :id")
                .param("t", OffsetDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC))
                .param("id", accountId).update();
    }
}
