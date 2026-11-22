package com.hyperdrive.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.support.FakeDropbox;
import com.hyperdrive.backend.support.StorageConnectionDriver;
import com.hyperdrive.backend.support.StorageIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** POST /api/v1/accounts/{id}/read-credentials: provider access-token delegation (ADR 0002). */
@ExtendWith(OutputCaptureExtension.class)
class ReadCredentialTest extends StorageIntegrationTest {

    private MvcResult issue(String token, UUID accountId) throws Exception {
        var request = post("/api/v1/accounts/" + accountId + "/read-credentials");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn();
    }

    private String storedAccessToken(UUID accountId) {
        return jdbc.sql("SELECT encrypted_access_token, encryption_key_version FROM provider_credentials "
                        + "WHERE connected_account_id = :id")
                .param("id", accountId)
                .query((rs, n) -> cipher.decrypt(rs.getBytes(1), rs.getInt(2), "provider_credentials:" + accountId + ":access"))
                .single();
    }

    private void expiresIn(UUID accountId, long seconds) {
        jdbc.sql("UPDATE provider_credentials SET access_token_expires_at = :t WHERE connected_account_id = :id")
                .param("t", OffsetDateTime.ofInstant(clock.instant().plusSeconds(seconds), ZoneOffset.UTC))
                .param("id", accountId).update();
    }

    @Test
    void issuesOnlyTheAccessTokenForAnOwnedDriveAccount(CapturedOutput output) throws Exception {
        String token = onboardedUser("login-rc-1", "alice");
        UUID account = storage.connect(token, StorageConnectionDriver.driveLogin("drive-rc-1", "d@example.com"));
        String refresh = storedRefreshToken(account);

        MvcResult result = issue(token, account);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = json(result);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accountId", "provider", "accessToken",
                "accessTokenExpiresAt", "rootNamespaceId", "issuedAt");
        assertThat(body.get("accountId").asString()).isEqualTo(account.toString());
        assertThat(body.get("provider").asString()).isEqualTo("GOOGLE_DRIVE");
        assertThat(body.get("accessToken").asString()).isEqualTo(storedAccessToken(account));
        assertThat(body.get("rootNamespaceId").isNull()).isTrue();
        assertThat(Instant.parse(body.get("accessTokenExpiresAt").asString())).isAfter(clock.instant().plusSeconds(299));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(refresh, "refresh");
        // Audited without the token.
        assertThat(output.getAll()).contains("Issued read credential").doesNotContain(body.get("accessToken").asString(), refresh);
    }

    @Test
    void aTokenCloseToExpiryIsRefreshedFirstAndTheRefreshTokenStaysOnTheBackend() throws Exception {
        String token = onboardedUser("login-rc-2", "bob");
        UUID account = storage.connect(token, StorageConnectionDriver.driveLogin("drive-rc-2", "d@example.com"));
        String old = storedAccessToken(account);
        String refresh = storedRefreshToken(account);
        expiresIn(account, 120); // valid, but shorter than the 5-minute delegation minimum
        google.refreshGrants.set(0);

        JsonNode body = json(issue(token, account));

        assertThat(google.refreshGrants.get()).isEqualTo(1);
        assertThat(body.get("accessToken").asString()).isNotEqualTo(old).isEqualTo(storedAccessToken(account));
        assertThat(storedRefreshToken(account)).isEqualTo(refresh);

        // Still fresh: no second refresh.
        issue(token, account);
        assertThat(google.refreshGrants.get()).isEqualTo(1);
    }

    @Test
    void anotherUsersAccountIsRejectedBeforeAnyProviderActivity() throws Exception {
        String alice = onboardedUser("login-rc-3", "alice3");
        UUID account = storage.connect(alice, StorageConnectionDriver.driveLogin("drive-rc-3", "d@example.com"));
        expiresIn(account, 10); // would force a refresh if anyone got that far
        String mallory = onboardedUser("login-rc-4", "mallory4");
        google.refreshGrants.set(0);
        google.tokenRequests.clear();

        for (UUID target : new UUID[] {account, UUID.randomUUID()}) {
            MvcResult result = issue(mallory, target);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(json(result).get("code").asString()).isEqualTo("account_not_found");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("ya29");
        }
        assertThat(google.refreshGrants.get()).isZero();
        assertThat(google.tokenRequests).isEmpty();
    }

    @Test
    void requiresAnAuthenticatedOnboardedLiveSession() throws Exception {
        String alice = onboardedUser("login-rc-5", "alice5");
        UUID account = storage.connect(alice, StorageConnectionDriver.driveLogin("drive-rc-5", "d@example.com"));

        assertThat(issue(null, account).getResponse().getStatus()).isEqualTo(401);
        String notOnboarded = login.signIn("login-rc-6", "n@example.com").accessToken();
        assertThat(issue(notOnboarded, account).getResponse().getStatus()).isEqualTo(403);

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + alice)).andReturn();
        assertThat(issue(alice, account).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void dropboxRequiresTheContentScopeAndReportsItWithoutBreakingBrowsing() throws Exception {
        String token = onboardedUser("login-rc-7", "carol");
        UUID old = storage.connectDropbox(token, dropbox, FakeDropbox.Account.personal("dbid:OldGrant", "o@example.com"));

        MvcResult missing = issue(token, old);
        assertThat(missing.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(missing).get("code").asString()).isEqualTo("content_permission_missing");
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + old).get("contentReadGranted").asBoolean()).isFalse();
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + old).get("status").asString()).isEqualTo("CONNECTED");
        assertThat(storage.api(token, "/api/v1/accounts/" + old + "/files").getResponse().getStatus()).isEqualTo(200);

        UUID granted = storage.connectDropbox(token, dropbox, new FakeDropbox.Account("dbid:NewGrant", "N", "n@example.com",
                true, "user", "4242", null, FakeDropbox.SCOPES_WITH_CONTENT, true));
        JsonNode body = json(issue(token, granted));
        assertThat(body.get("provider").asString()).isEqualTo("DROPBOX");
        assertThat(body.get("rootNamespaceId").asString()).isEqualTo("4242");
        assertThat(body.get("accessToken").asString()).startsWith("sl.fake-dropbox-access-");
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + granted).get("contentReadGranted").asBoolean()).isTrue();
    }

    @Test
    void revokedOrReconnectRequiredAccountsGetNoCredential() throws Exception {
        String token = onboardedUser("login-rc-8", "dave");
        UUID account = storage.connect(token, StorageConnectionDriver.driveLogin("drive-rc-8", "d@example.com"));
        google.revokeRefreshTokens("drive-rc-8");
        expiresIn(account, 10);

        MvcResult revoked = issue(token, account);
        assertThat(revoked.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(revoked).get("code").asString()).isEqualTo("account_needs_reconnect");
        assertThat(accountStatus(account)).isEqualTo("NEEDS_RECONNECT");

        int grants = google.refreshGrants.get();
        assertThat(json(issue(token, account)).get("code").asString()).isEqualTo("account_needs_reconnect");
        assertThat(google.refreshGrants.get()).isEqualTo(grants);
    }

    @Test
    void driveReadonlyGrantsIncludeContentRead() throws Exception {
        String token = onboardedUser("login-rc-9", "erin");
        UUID account = storage.connect(token, StorageConnectionDriver.driveLogin("drive-rc-9", "d@example.com"));
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + account).get("contentReadGranted").asBoolean()).isTrue();
    }
}
