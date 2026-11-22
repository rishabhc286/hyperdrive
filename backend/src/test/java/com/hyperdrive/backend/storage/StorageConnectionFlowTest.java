package com.hyperdrive.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.backend.support.DesktopLoginDriver;
import com.hyperdrive.backend.support.FakeOidcProvider;
import com.hyperdrive.backend.support.FakeOidcProvider.Login;
import com.hyperdrive.backend.support.StorageConnectionDriver;
import com.hyperdrive.backend.support.StorageConnectionDriver.AtGoogle;
import com.hyperdrive.backend.support.StorageConnectionDriver.Started;
import com.hyperdrive.backend.support.StorageIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Google Drive connection: separate consent, transaction binding, callback validation, ownership. */
class StorageConnectionFlowTest extends StorageIntegrationTest {

    private static final Login DRIVE = StorageConnectionDriver.driveLogin("drive-sub-1", "drive.owner@example.com");

    @Test
    void connectsADifferentGoogleAccountWithoutTouchingTheHyperDriveLogin() throws Exception {
        String token = onboardedUser("login-sub-1", "alice");
        long identitiesBefore = count("auth_identities");
        String userIdBefore = json(storage.api(token, "/api/v1/me")).get("id").asString();

        UUID accountId = storage.connect(token, DRIVE);

        JsonNode accounts = storage.apiJson(token, "/api/v1/accounts").get("items");
        assertThat(accounts).hasSize(1);
        JsonNode account = accounts.get(0);
        assertThat(account.get("id").asString()).isEqualTo(accountId.toString());
        assertThat(account.get("provider").asString()).isEqualTo("GOOGLE_DRIVE");
        assertThat(account.get("email").asString()).isEqualTo("drive.owner@example.com");
        assertThat(account.get("displayName").asString()).isEqualTo("Drive Owner");
        assertThat(account.get("status").asString()).isEqualTo("CONNECTED");
        assertThat(account.get("grantedScopes").toString()).contains("https://www.googleapis.com/auth/drive.readonly");

        // The storage identity never became a HyperDrive login identity, user or session change.
        assertThat(count("auth_identities")).isEqualTo(identitiesBefore);
        assertThat(count("users")).isEqualTo(1);
        assertThat(json(storage.api(token, "/api/v1/me")).get("id").asString()).isEqualTo(userIdBefore);
        assertThat(jdbc.sql("SELECT provider_account_id FROM connected_accounts").query(String.class).single())
                .isEqualTo("drive-sub-1");
    }

    @Test
    void googleConsentRequestHasDriveReadonlyOfflineAccessPkceAndTheStorageCallback() throws Exception {
        String token = onboardedUser("login-sub-2", "bob");
        AtGoogle atGoogle = storage.open(storage.create(token));

        Map<String, String> params = atGoogle.params();
        assertThat(atGoogle.location().toString()).startsWith(google.issuer() + "/authorize");
        assertThat(params.get("scope").split(" ")).containsExactlyInAnyOrder(
                "openid", "email", "profile", "https://www.googleapis.com/auth/drive.readonly");
        assertThat(params.get("access_type")).isEqualTo("offline");
        assertThat(params.get("prompt")).isEqualTo("select_account consent");
        assertThat(params.get("code_challenge_method")).isEqualTo("S256");
        assertThat(params.get("code_challenge")).isNotBlank();
        assertThat(params.get("state")).isNotBlank();
        assertThat(params.get("nonce")).isNotBlank();
        assertThat(params.get("redirect_uri")).isEqualTo("http://localhost/api/v1/storage-connections/google/callback");
        assertThat(params.get("client_id")).isEqualTo(CLIENT_ID);
        // No HyperDrive bearer token or session id in any browser URL.
        assertThat(atGoogle.location().toString()).doesNotContain(token);
        assertThat(atGoogle.binding().getValue()).startsWith("hdb_");
    }

    @Test
    void signInStillRequestsIdentityScopesOnly() throws Exception {
        DesktopLoginDriver.Transaction tx = login.createTransaction();
        DesktopLoginDriver.GoogleRedirect redirect = login.openInBrowser(tx);
        assertThat(redirect.params().get("scope").split(" ")).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void creatingATransactionRequiresAnOnboardedSession() throws Exception {
        assertThat(storage.createRaw(null).getResponse().getStatus()).isEqualTo(401);
        assertThat(storage.createRaw("hda_" + "x".repeat(43)).getResponse().getStatus()).isEqualTo(401);

        String notOnboarded = login.signIn("login-sub-new", "new@example.com").accessToken();
        MvcResult result = storage.createRaw(notOnboarded);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("storage_connection_transactions")).isZero();
    }

    @Test
    void transactionStoresOnlyHashesAndEncryptedVerifier() throws Exception {
        String token = onboardedUser("login-sub-3", "carol");
        Started started = storage.create(token);
        String ticket = started.ticketPath().substring(started.ticketPath().lastIndexOf('/') + 1);
        assertThat(ticket).startsWith("hds_");
        AtGoogle atGoogle = storage.open(started);

        Map<String, Object> row = jdbc.sql("SELECT * FROM storage_connection_transactions").query().singleRow();
        String dump = row.values().stream().map(v -> v instanceof byte[] b ? new String(b, StandardCharsets.ISO_8859_1)
                : String.valueOf(v)).reduce("", String::concat);
        assertThat(dump).doesNotContain(ticket, atGoogle.params().get("state"), atGoogle.binding().getValue());
        assertThat(row.get("encrypted_code_verifier")).isNotNull();
        assertThat(row.get("user_id")).isNotNull();
        assertThat(row.get("device_session_id")).isNotNull();
    }

    @Test
    void ticketIsSingleUseAndExpiresQuickly() throws Exception {
        String token = onboardedUser("login-sub-4", "dave");
        Started started = storage.create(token);
        storage.open(started);
        MvcResult second = storage.openRaw(started.ticketPath());
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(second.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");

        Started late = storage.create(token);
        mutableClock().advance(Duration.ofMinutes(3));
        assertThat(storage.openRaw(late.ticketPath()).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.openRaw("/api/v1/storage-connections/google/authorize/not-a-ticket").getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void callbackRejectsUnknownStateAndReplays() throws Exception {
        String token = onboardedUser("login-sub-5", "erin");
        Started started = storage.create(token);
        AtGoogle atGoogle = storage.open(started);

        MvcResult unknown = storage.callback(Map.of("code", "x", "state", "forged-state"), atGoogle.binding());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.callback(Map.of("code", "x"), atGoogle.binding()).getResponse().getStatus()).isEqualTo(400);

        String code = "google-drive-code-replay";
        google.prepare(code, DRIVE.withNonce(atGoogle.params().get("nonce")));
        Map<String, String> params = Map.of("code", code, "state", atGoogle.params().get("state"));
        assertThat(storage.callback(params, atGoogle.binding()).getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = storage.callback(params, atGoogle.binding());
        assertThat(replay.getResponse().getStatus()).isEqualTo(400);
        assertThat(replay.getResponse().getContentAsString()).contains("can&#39;t be used");
        assertThat(count("connected_accounts")).isEqualTo(1);
        assertThat(storage.statusJson(token, started.transactionId()).get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void callbackFromAnotherBrowserFails() throws Exception {
        String token = onboardedUser("login-sub-6", "frank");
        Started started = storage.create(token);
        AtGoogle atGoogle = storage.open(started);
        AtGoogle forgedCookie = new AtGoogle(atGoogle.location(), atGoogle.params(),
                new Cookie("HD_STORAGE_BINDING", "hdb_" + "A".repeat(43)));

        MvcResult result = storage.consent(forgedCookie, DRIVE);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode status = storage.statusJson(token, started.transactionId());
        assertThat(status.get("status").asString()).isEqualTo("FAILED");
        assertThat(status.get("errorCode").asString()).isEqualTo("browser_mismatch");
        assertThat(count("connected_accounts")).isZero();
    }

    @Test
    void expiredTransactionCannotComplete() throws Exception {
        String token = onboardedUser("login-sub-7", "gina");
        Started started = storage.create(token);
        AtGoogle atGoogle = storage.open(started);
        mutableClock().advance(Duration.ofMinutes(11));

        assertThat(storage.consent(atGoogle, DRIVE).getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.statusJson(token, started.transactionId()).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(count("connected_accounts")).isZero();
    }

    @Test
    void transactionStatusAndCancelAreVisibleOnlyToTheOwningUserAndDevice() throws Exception {
        String alice = onboardedUser("login-sub-8", "alice8");
        String mallory = onboardedUser("login-sub-9", "mallory");
        Started started = storage.create(alice);

        assertThat(storage.status(mallory, started.transactionId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(storage.cancel(mallory, started.transactionId()).getResponse().getStatus()).isEqualTo(404);

        // Same HyperDrive user on a second device: a different session cannot follow this attempt.
        String aliceOtherDevice = login.signIn("login-sub-8", "x@example.com").accessToken();
        assertThat(storage.status(aliceOtherDevice, started.transactionId()).getResponse().getStatus()).isEqualTo(404);

        assertThat(storage.statusJson(alice, started.transactionId()).get("status").asString()).isEqualTo("PENDING");
        assertThat(storage.statusJson(alice, started.transactionId()).get("browserOpened").asBoolean()).isFalse();
    }

    @Test
    void notFoundResponsesDoNotRevealWhetherATransactionExists() throws Exception {
        String alice = onboardedUser("login-sub-10", "alice10");
        String mallory = onboardedUser("login-sub-11", "mallory11");
        Started started = storage.create(alice);
        MvcResult othersTransaction = storage.status(mallory, started.transactionId());
        MvcResult missing = storage.status(mallory, UUID.randomUUID());
        assertThat(othersTransaction.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
        assertThat(json(othersTransaction).get("code").asString()).isEqualTo(json(missing).get("code").asString());
    }

    @Test
    void cancelledTransactionRejectsTheLaterCallback() throws Exception {
        String token = onboardedUser("login-sub-12", "hank");
        Started started = storage.create(token);
        AtGoogle atGoogle = storage.open(started);

        JsonNode cancelled = json(storage.cancel(token, started.transactionId()));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");

        assertThat(storage.consent(atGoogle, DRIVE).getResponse().getStatus()).isEqualTo(400);
        assertThat(count("connected_accounts")).isZero();
        assertThat(storage.statusJson(token, started.transactionId()).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void aNewAttemptSupersedesTheUnfinishedOneOfTheSameSession() throws Exception {
        String token = onboardedUser("login-sub-13", "ivan");
        Started first = storage.create(token);
        AtGoogle firstAtGoogle = storage.open(first);
        storage.create(token);

        assertThat(storage.statusJson(token, first.transactionId()).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(storage.consent(firstAtGoogle, DRIVE).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void userDecliningConsentIsReportedAsAccessDenied() throws Exception {
        String token = onboardedUser("login-sub-14", "judy");
        Started started = storage.create(token);
        AtGoogle atGoogle = storage.open(started);

        MvcResult result = storage.callback(Map.of("error", "access_denied", "state", atGoogle.params().get("state")),
                atGoogle.binding());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode status = storage.statusJson(token, started.transactionId());
        assertThat(status.get("status").asString()).isEqualTo("FAILED");
        assertThat(status.get("errorCode").asString()).isEqualTo("access_denied");
    }

    @Test
    void untickedDrivePermissionFailsWithoutStoringAnything() throws Exception {
        String token = onboardedUser("login-sub-15", "kim");
        Started started = storage.create(token);
        Login withoutDrive = new Login("drive-sub-15", "d@example.com", true, "D", null, null, false,
                "openid https://www.googleapis.com/auth/userinfo.email", true);

        storage.consent(storage.open(started), withoutDrive);

        JsonNode status = storage.statusJson(token, started.transactionId());
        assertThat(status.get("errorCode").asString()).isEqualTo("drive_scope_not_granted");
        assertThat(count("connected_accounts")).isZero();
        assertThat(count("provider_credentials")).isZero();
    }

    @Test
    void idTokenWithWrongNonceOrAudienceIsRejected() throws Exception {
        String token = onboardedUser("login-sub-16", "lena");
        Started first = storage.create(token);
        storage.consent(storage.open(first), DRIVE.withNonce("not-the-transaction-nonce"));
        assertThat(storage.statusJson(token, first.transactionId()).get("errorCode").asString()).isEqualTo("identity_invalid");

        Started second = storage.create(token);
        Login otherAudience = new Login("drive-sub-16", "d@example.com", true, "D", null, "another-client", false,
                FakeOidcProvider.DRIVE_SCOPE, true);
        storage.consent(storage.open(second), otherAudience);
        assertThat(storage.statusJson(token, second.transactionId()).get("errorCode").asString()).isEqualTo("identity_invalid");
        assertThat(count("connected_accounts")).isZero();
    }

    @Test
    void connectingTheSameDriveAccountAgainReconnectsTheExistingRow() throws Exception {
        String token = onboardedUser("login-sub-17", "mona");
        UUID first = storage.connect(token, DRIVE);
        String originalRefresh = storedRefreshToken(first);
        assertThat(originalRefresh).startsWith("1//fake-google-refresh-");

        // Repeat consent where Google does not return a new refresh token.
        Login noNewRefresh = new Login(DRIVE.subject(), DRIVE.email(), true, "Renamed Owner", null, null, false,
                FakeOidcProvider.DRIVE_SCOPE, false);
        Started again = storage.create(token);
        storage.consent(storage.open(again), noNewRefresh);
        JsonNode status = storage.statusJson(token, again.transactionId());

        assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(status.get("reconnected").asBoolean()).isTrue();
        assertThat(status.get("accountId").asString()).isEqualTo(first.toString());
        assertThat(count("connected_accounts")).isEqualTo(1);
        assertThat(storedRefreshToken(first)).isEqualTo(originalRefresh);
        assertThat(storage.apiJson(token, "/api/v1/accounts/" + first).get("displayName").asString())
                .isEqualTo("Renamed Owner");
    }

    @Test
    void sameDriveAccountConnectedByTwoHyperDriveUsersGivesEachTheirOwnConnection() throws Exception {
        String alice = onboardedUser("login-sub-18", "alice18");
        String bob = onboardedUser("login-sub-19", "bob19");

        UUID aliceAccount = storage.connect(alice, DRIVE);
        UUID bobAccount = storage.connect(bob, DRIVE);

        assertThat(aliceAccount).isNotEqualTo(bobAccount);
        assertThat(storage.apiJson(alice, "/api/v1/accounts").get("items")).hasSize(1);
        assertThat(storage.apiJson(bob, "/api/v1/accounts").get("items").get(0).get("id").asString())
                .isEqualTo(bobAccount.toString());
    }

    @Test
    void providerTokensNeverReachTheDesktopAndAreEncryptedAtRest() throws Exception {
        String token = onboardedUser("login-sub-20", "nina");
        UUID accountId = storage.connect(token, DRIVE);
        storage.apiJson(token, "/api/v1/accounts");
        storage.apiJson(token, "/api/v1/accounts/" + accountId);

        List<String> providerTokens = google.issuedProviderTokens;
        assertThat(providerTokens).isNotEmpty();
        for (String providerToken : providerTokens) {
            assertThat(storage.transcript).noneMatch(entry -> entry.contains(providerToken));
        }
        Map<String, Object> row = jdbc.sql("SELECT * FROM provider_credentials").query().singleRow();
        String dump = row.values().stream().map(v -> v instanceof byte[] b ? new String(b, StandardCharsets.ISO_8859_1)
                : String.valueOf(v)).reduce("", String::concat);
        for (String providerToken : providerTokens) {
            assertThat(dump).doesNotContain(providerToken);
        }
        assertThat(row.get("encryption_key_version")).isEqualTo(1);
    }

    @Test
    void accountDtoContainsExactlyTheDocumentedFields() throws Exception {
        String token = onboardedUser("login-sub-21", "olga");
        UUID accountId = storage.connect(token, DRIVE);
        JsonNode account = storage.apiJson(token, "/api/v1/accounts/" + accountId);
        assertThat(account.propertyNames()).containsExactlyInAnyOrder("id", "provider", "displayName", "email", "status",
                "grantedScopes", "connectedAt", "updatedAt", "lastSuccessAt", "lastErrorCode");
        assertThat(account.toString()).doesNotContain("drive-sub-1", "refresh", "access_token");
    }
}
