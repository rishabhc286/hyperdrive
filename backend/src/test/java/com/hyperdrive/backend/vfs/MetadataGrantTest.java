package com.hyperdrive.backend.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.support.FakeDropbox;
import com.hyperdrive.backend.support.FakeDropbox.Account;
import com.hyperdrive.backend.support.StorageIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Batch 8: metadata grants are scoped, owner-bound, short-lived and die with the session. */
class MetadataGrantTest extends StorageIntegrationTest {

    private static final Account BOX = new Account("dbid:AAH4f99T0taONIb-OurWxbNQ6ywGRopQngc", "Box Owner",
            "box.owner@example.com", true, "user", "3235641", null, FakeDropbox.SCOPES_WITH_CONTENT, true);

    private MvcResult call(String method, String path, String bearer) throws Exception {
        var builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> delete(path);
        };
        return mvc.perform(builder.header("Authorization", "Bearer " + bearer)).andReturn();
    }

    private String issue(String sessionToken) throws Exception {
        MvcResult r = call("POST", "/api/v1/metadata-grants", sessionToken);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        String grant = json(r).get("grant").asString();
        assertThat(grant).matches("^hdm_[A-Za-z0-9_-]{43}$");
        return grant;
    }

    @Test
    void aGrantListsOnlyTheOwnersAccountsAndFolderMetadataWithoutPersonalOrSecretFields() throws Exception {
        String alice = onboardedUser("login-vfs-1", "alicevfs");
        UUID box = storage.connectDropbox(alice, dropbox, BOX);
        dropbox.folder("3235641", "", "Projects");
        dropbox.file("3235641", "", "Report.pdf", 1234);
        String bob = onboardedUser("login-vfs-2", "bobvfs");
        UUID bobsBox = storage.connectDropbox(bob, dropbox, BOX);
        String grant = issue(alice);

        MvcResult accounts = call("GET", "/api/v1/vfs/accounts", grant);
        assertThat(accounts.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = json(accounts).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id").asString()).isEqualTo(box.toString());
        assertThat(items.get(0).get("provider").asString()).isEqualTo("DROPBOX");
        String body = accounts.getResponse().getContentAsString();
        assertThat(body).doesNotContain("box.owner@example.com", "Box Owner", "email", "displayName");

        MvcResult root = call("GET", "/api/v1/vfs/accounts/" + box + "/files", grant);
        assertThat(root.getResponse().getStatus()).as(root.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode entries = json(root).get("items");
        assertThat(entries).hasSize(2);
        assertThat(root.getResponse().getContentAsString())
                .contains("\"name\":\"Projects\"", "\"kind\":\"FOLDER\"", "\"sizeBytes\":1234")
                .doesNotContain("contentHash", "sl.fake", "fake-dropbox-refresh", "hdm_", "hda_");

        // Another user's account behaves like an unknown one.
        MvcResult foreign = call("GET", "/api/v1/vfs/accounts/" + bobsBox + "/files", grant);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aGrantAuthorizesNothingElseAndASessionTokenCannotUseTheVfsApi() throws Exception {
        String token = onboardedUser("login-vfs-3", "carolvfs");
        UUID box = storage.connectDropbox(token, dropbox, BOX);
        String grant = issue(token);

        for (String[] forbidden : new String[][] {
                {"GET", "/api/v1/accounts"},
                {"GET", "/api/v1/accounts/" + box + "/files"},
                {"POST", "/api/v1/accounts/" + box + "/read-credentials"},
                {"DELETE", "/api/v1/accounts/" + box},
                {"POST", "/api/v1/accounts/" + box + "/revoke-and-disconnect"},
                {"GET", "/api/v1/me"},
                {"POST", "/api/v1/metadata-grants"},
                {"DELETE", "/api/v1/metadata-grants"},
                {"POST", "/api/v1/auth/logout"}}) {
            MvcResult r = call(forbidden[0], forbidden[1], grant);
            assertThat(r.getResponse().getStatus()).as(forbidden[0] + " " + forbidden[1]).isEqualTo(403);
        }
        assertThat(accountStatus(box)).isEqualTo("CONNECTED");
        assertThat(call("GET", "/api/v1/vfs/accounts", token).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/vfs/accounts", "hdm_" + "A".repeat(43)).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void aGrantIsReplacedRevokedExpiredAndEndedByLogout() throws Exception {
        String token = onboardedUser("login-vfs-4", "davevfs");
        String first = issue(token);
        String second = issue(token);
        assertThat(call("GET", "/api/v1/vfs/accounts", first).getResponse().getStatus()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/vfs/accounts", second).getResponse().getStatus()).isEqualTo(200);

        assertThat(call("DELETE", "/api/v1/metadata-grants", token).getResponse().getStatus()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/vfs/accounts", second).getResponse().getStatus()).isEqualTo(401);

        String third = issue(token);
        mutableClock().advance(MetadataGrantService.TTL.plus(Duration.ofSeconds(1)));
        assertThat(call("GET", "/api/v1/vfs/accounts", third).getResponse().getStatus()).isEqualTo(401);
        mutableClock().reset();

        String fourth = issue(token);
        assertThat(call("POST", "/api/v1/auth/logout", token).getResponse().getStatus()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/vfs/accounts", fourth).getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM metadata_grants").query(Long.class).single()).isZero();
    }

    @Test
    void onlyTheGrantHashIsStored() throws Exception {
        String token = onboardedUser("login-vfs-5", "evevfs");
        String grant = issue(token);
        byte[] stored = jdbc.sql("SELECT token_hash FROM metadata_grants").query(byte[].class).single();
        assertThat(stored).hasSize(32);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(grant.substring(4, 20));
    }
}
