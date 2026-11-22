package com.hyperdrive.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.support.AuthIntegrationTest;
import com.hyperdrive.backend.support.DesktopLoginDriver;
import com.hyperdrive.backend.support.DesktopLoginDriver.GoogleRedirect;
import com.hyperdrive.backend.support.DesktopLoginDriver.SignedIn;
import com.hyperdrive.backend.support.DesktopLoginDriver.Transaction;
import com.hyperdrive.backend.support.FakeOidcProvider;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class DesktopLoginFlowTest extends AuthIntegrationTest {

    private DesktopLoginDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DesktopLoginDriver(mvc, google);
    }

    private static FakeOidcProvider.Login login(String sub, String email) {
        return new FakeOidcProvider.Login(sub, email, true, "Test User", null, null, false);
    }

    @Test
    void firstSignInCreatesExactlyOneAccountThatNeedsOnboarding() throws Exception {
        SignedIn signedIn = driver.signIn("google-sub-1", "one@example.com");

        JsonNode user = signedIn.user();
        assertThat(user.get("username").isNull()).isTrue();
        assertThat(user.get("onboardingComplete").asBoolean()).isFalse();
        assertThat(user.get("displayName").asString()).isEqualTo("Test User");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("auth_identities")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT issuer FROM auth_identities").query(String.class).single()).isEqualTo(google.issuer());
    }

    @Test
    void returningUserGetsTheSameAccountAndSkipsOnboarding() throws Exception {
        SignedIn first = driver.signIn("google-sub-2", "two@example.com");
        mvc.perform(post("/api/v1/me/onboarding").header("Authorization", "Bearer " + first.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"Returning_User\"}"));

        SignedIn second = driver.signIn("google-sub-2", "two@example.com");

        assertThat(second.user().get("id").asString()).isEqualTo(first.user().get("id").asString());
        assertThat(second.user().get("onboardingComplete").asBoolean()).isTrue();
        assertThat(second.user().get("username").asString()).isEqualTo("Returning_User");
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void emailNeverControlsIdentityMatching() throws Exception {
        SignedIn a = driver.signIn("sub-a", "shared@example.com");
        SignedIn b = driver.signIn("sub-b", "shared@example.com");
        SignedIn aWithNewEmail = driver.signIn("sub-a", "different@example.com");

        assertThat(b.user().get("id").asString()).isNotEqualTo(a.user().get("id").asString());
        assertThat(aWithNewEmail.user().get("id").asString()).isEqualTo(a.user().get("id").asString());
        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    void unverifiedEmailIsNotStoredAndMissingProfileFieldsAreTolerated() throws Exception {
        Transaction tx = driver.createTransaction();
        GoogleRedirect redirect = driver.openInBrowser(tx);
        MvcResult callback = driver.completeAtGoogle(redirect,
                new FakeOidcProvider.Login("sub-minimal", "unverified@example.com", false, null, null, null, false));

        assertThat(callback.getResponse().getStatus()).isEqualTo(302);
        assertThat(jdbc.sql("SELECT contact_email FROM users").query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT display_name FROM users").query(String.class).optional()).isEmpty();
    }

    @Test
    void googleIsAskedOnlyForIdentityScopesWithStateNonceAndPkce() throws Exception {
        GoogleRedirect redirect = driver.openInBrowser(driver.createTransaction());

        assertThat(redirect.location().toString()).startsWith(google.issuer() + "/authorize");
        assertThat(Set.of(redirect.params().get("scope").split(" "))).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(redirect.location().toString()).doesNotContainIgnoringCase("drive");
        assertThat(redirect.params()).containsKeys("state", "nonce", "code_challenge");
        assertThat(redirect.params().get("code_challenge_method")).isEqualTo("S256");
        assertThat(redirect.params().get("redirect_uri")).isEqualTo(PUBLIC_BASE_URL + "/login/oauth2/code/google");
    }

    @Test
    void loopbackRedirectCarriesOnlyTheHandoffCodeAndDesktopState() throws Exception {
        Transaction tx = driver.createTransaction();
        GoogleRedirect redirect = driver.openInBrowser(tx);
        MvcResult callback = driver.completeAtGoogle(redirect, login("sub-loopback", "l@example.com"));

        URI loopback = URI.create(callback.getResponse().getHeader("Location"));
        assertThat(loopback.getScheme()).isEqualTo("http");
        assertThat(loopback.getHost()).isEqualTo("127.0.0.1");
        assertThat(loopback.getPort()).isEqualTo(tx.port());
        assertThat(loopback.getPath()).isEqualTo("/callback");
        Map<String, String> params = DesktopLoginDriver.queryParams(loopback);
        assertThat(params.keySet()).containsExactlyInAnyOrder("code", "state");
        assertThat(params.get("state")).isEqualTo(tx.desktopState());
        assertThat(params.get("code")).startsWith("hdc_");
        assertThat(callback.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(callback.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        // Spring's OAuth state is separate from the desktop state.
        assertThat(redirect.params().get("state")).isNotEqualTo(tx.desktopState());
        // Only a hash of the code is stored.
        assertThat(jdbc.sql("SELECT count(*) FROM login_transactions WHERE handoff_code_hash = :h")
                .param("h", SecureTokens.hash(params.get("code"))).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void browserSessionIsDiscardedAndNeverAuthenticatesTheApi() throws Exception {
        Transaction tx = driver.createTransaction();
        GoogleRedirect redirect = driver.openInBrowser(tx);

        // While the browser holds the OAuth session cookie, the bearer API still refuses it.
        MvcResult duringLogin = mvc.perform(get("/api/v1/me").session(redirect.session())).andReturn();
        assertThat(duringLogin.getResponse().getStatus()).isEqualTo(401);

        driver.completeAtGoogle(redirect, login("sub-session", "s@example.com"));
        assertThat(redirect.session().isInvalid()).isTrue();

        // A second "login" in the same browser can't reuse anything: the authorize link is single-use.
        MvcResult again = mvc.perform(get(URI.create(tx.authorizationUrl()).getPath())).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void noProviderTokensEverReachTheDesktop() throws Exception {
        SignedIn signedIn = driver.signIn("sub-tokens", "t@example.com");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + signedIn.accessToken()))
                .andDo(r -> driver.transcript.add(r.getResponse().getContentAsString()));

        assertThat(google.issuedProviderTokens).isNotEmpty();
        for (String providerToken : google.issuedProviderTokens) {
            assertThat(driver.transcript).noneMatch(s -> s.contains(providerToken));
        }
        assertThat(signedIn.tokens().propertyNames()).containsExactlyInAnyOrder(
                "tokenType", "accessToken", "accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt", "user");
        assertThat(signedIn.user().propertyNames()).containsExactlyInAnyOrder(
                "id", "username", "displayName", "onboardingComplete");
    }

    @Test
    void googleSidePkceVerifierIsSentOnTheCodeExchange() throws Exception {
        google.tokenRequests.clear();
        driver.signIn("sub-pkce", "p@example.com");
        assertThat(google.tokenRequests).anySatisfy(form -> assertThat(form).containsKey("code_verifier"));
    }

    @Test
    void handoffCodeIsSingleUse() throws Exception {
        Transaction tx = driver.createTransaction();
        String code = handoffCode(tx, "sub-single");

        assertThat(driver.exchange(tx.id(), code, tx.verifier()).getResponse().getStatus()).isEqualTo(200);
        MvcResult reuse = driver.exchange(tx.id(), code, tx.verifier());
        assertThat(reuse.getResponse().getStatus()).isEqualTo(400);
        assertThat(reuse.getResponse().getContentAsString()).contains("invalid_grant");
    }

    @Test
    void handoffCodeExpiresAfterSixtySeconds() throws Exception {
        Transaction tx = driver.createTransaction();
        String code = handoffCode(tx, "sub-expiry");
        mutableClock().advance(Duration.ofSeconds(61));

        assertThat(driver.exchange(tx.id(), code, tx.verifier()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void wrongVerifierFailsAndBurnsTheCode() throws Exception {
        Transaction tx = driver.createTransaction();
        String code = handoffCode(tx, "sub-verifier");

        assertThat(driver.exchange(tx.id(), code, DesktopLoginDriver.random(48)).getResponse().getStatus()).isEqualTo(400);
        assertThat(driver.exchange(tx.id(), code, tx.verifier()).getResponse().getStatus()).isEqualTo(400);
        assertThat(count("device_sessions")).isZero();
    }

    @Test
    void codeFromAnotherTransactionIsRejected() throws Exception {
        Transaction first = driver.createTransaction();
        String code = handoffCode(first, "sub-cross");
        Transaction second = driver.createTransaction();

        assertThat(driver.exchange(second.id(), code, first.verifier()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void expiredTransactionCannotStartSignIn() throws Exception {
        Transaction tx = driver.createTransaction();
        mutableClock().advance(Duration.ofMinutes(6));

        MvcResult result = mvc.perform(get(URI.create(tx.authorizationUrl()).getPath())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("can&#39;t be used");
    }

    @Test
    void unknownOrMalformedTransactionIdShowsInvalidLinkPage() throws Exception {
        assertThat(mvc.perform(get("/api/v1/auth/desktop/authorize/" + UUID.randomUUID())).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/api/v1/auth/desktop/authorize/not-a-uuid")).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void cancellingAtGoogleNotifiesTheDesktopAndFailsTheTransaction() throws Exception {
        Transaction tx = driver.createTransaction();
        GoogleRedirect redirect = driver.openInBrowser(tx);

        MvcResult result = driver.googleCallback(redirect,
                Map.of("error", "access_denied", "state", redirect.params().get("state")));

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        Map<String, String> params = DesktopLoginDriver.queryParams(URI.create(result.getResponse().getHeader("Location")));
        assertThat(params).containsEntry("error", "access_denied").containsEntry("state", tx.desktopState());
        assertThat(jdbc.sql("SELECT status FROM login_transactions").query(String.class).single()).isEqualTo("FAILED");
        assertThat(count("users")).isZero();
    }

    @Test
    void tamperedOauthStateIsRejectedBySpringSecurity() throws Exception {
        GoogleRedirect redirect = driver.openInBrowser(driver.createTransaction());
        String code = "google-code-tampered";
        google.prepare(code, new FakeOidcProvider.Login("sub-x", null, false, null, redirect.params().get("nonce"), null, false));

        MvcResult result = driver.googleCallback(redirect, Map.of("code", code, "state", "attacker-state"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getHeader("Location")).isNull();
        assertThat(count("users")).isZero();
    }

    @Test
    void idTokenWithWrongSignatureIsRejected() throws Exception {
        assertGoogleLoginFails(new FakeOidcProvider.Login("sub-sig", null, false, null, null, null, true));
    }

    @Test
    void idTokenForAnotherAudienceIsRejected() throws Exception {
        assertGoogleLoginFails(new FakeOidcProvider.Login("sub-aud", null, false, null, null, "someone-else", false));
    }

    @Test
    void idTokenWithWrongNonceIsRejected() throws Exception {
        assertGoogleLoginFails(new FakeOidcProvider.Login("sub-nonce", null, false, null, "wrong-nonce", null, false));
    }

    @Test
    void genericSpringAuthorizationEndpointCannotStartALogin() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/google")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getHeader("Location")).isNull();
    }

    static Stream<Arguments> invalidTransactions() {
        String challenge = "a".repeat(43);
        String state = "s".repeat(32);
        return Stream.of(
                Arguments.of("plain PKCE", Map.of("codeChallenge", challenge, "codeChallengeMethod", "plain", "callbackPort", 50000, "callbackState", state)),
                Arguments.of("short challenge", Map.of("codeChallenge", "abc", "codeChallengeMethod", "S256", "callbackPort", 50000, "callbackState", state)),
                Arguments.of("privileged port", Map.of("codeChallenge", challenge, "codeChallengeMethod", "S256", "callbackPort", 80, "callbackState", state)),
                Arguments.of("port out of range", Map.of("codeChallenge", challenge, "codeChallengeMethod", "S256", "callbackPort", 70000, "callbackState", state)),
                Arguments.of("short state", Map.of("codeChallenge", challenge, "codeChallengeMethod", "S256", "callbackPort", 50000, "callbackState", "abc")),
                Arguments.of("state with URL characters", Map.of("codeChallenge", challenge, "codeChallengeMethod", "S256", "callbackPort", 50000, "callbackState", state + "&x=1")),
                Arguments.of("client-supplied redirect URI", Map.of("codeChallenge", challenge, "codeChallengeMethod", "S256", "callbackPort", 50000, "callbackState", state, "redirectUri", "https://evil.example/callback")),
                Arguments.of("missing port", Map.of("codeChallenge", challenge, "codeChallengeMethod", "S256", "callbackState", state)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTransactions")
    void invalidTransactionRequestsAreRejected(String label, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/desktop/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(DesktopLoginDriver.JSON.writeValueAsString(new HashMap<>(body)))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(count("login_transactions")).isZero();
    }

    @Test
    void malformedExchangeRequestsAreRejectedWithoutEchoingSecrets() throws Exception {
        String secret = "hdc_" + "x".repeat(43);
        MvcResult result = mvc.perform(post("/api/v1/auth/desktop/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"" + UUID.randomUUID() + "\",\"code\":\"" + secret + "\",\"codeVerifier\":\"short\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secret).contains("correlationId");
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
    }

    private String handoffCode(Transaction tx, String subject) throws Exception {
        GoogleRedirect redirect = driver.openInBrowser(tx);
        MvcResult callback = driver.completeAtGoogle(redirect, login(subject, subject + "@example.com"));
        assertThat(callback.getResponse().getStatus()).isEqualTo(302);
        return DesktopLoginDriver.queryParams(URI.create(callback.getResponse().getHeader("Location"))).get("code");
    }

    private void assertGoogleLoginFails(FakeOidcProvider.Login login) throws Exception {
        Transaction tx = driver.createTransaction();
        GoogleRedirect redirect = driver.openInBrowser(tx);
        MvcResult result = driver.completeAtGoogle(redirect, login);

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        Map<String, String> params = DesktopLoginDriver.queryParams(URI.create(result.getResponse().getHeader("Location")));
        assertThat(params).containsEntry("error", "login_failed").doesNotContainKey("code");
        assertThat(count("users")).isZero();
    }
}
