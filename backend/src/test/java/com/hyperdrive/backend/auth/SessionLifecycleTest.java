package com.hyperdrive.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.support.AuthIntegrationTest;
import com.hyperdrive.backend.support.DesktopLoginDriver;
import com.hyperdrive.backend.support.DesktopLoginDriver.SignedIn;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class SessionLifecycleTest extends AuthIntegrationTest {

    private DesktopLoginDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DesktopLoginDriver(mvc, google);
    }

    private int me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken)).andReturn()
                .getResponse().getStatus();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn();
    }

    @Test
    void accessTokenAuthenticatesTheCurrentUser() throws Exception {
        SignedIn signedIn = driver.signIn("sub-me", "me@example.com");

        MvcResult result = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + signedIn.accessToken()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode me = DesktopLoginDriver.JSON.readTree(result.getResponse().getContentAsString());
        assertThat(me.get("id").asString()).isEqualTo(signedIn.user().get("id").asString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("hda_", "hdr_", "Token");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void refreshRotatesBothTokens() throws Exception {
        SignedIn signedIn = driver.signIn("sub-rotate", "r@example.com");

        MvcResult result = refresh(signedIn.refreshToken());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode tokens = DesktopLoginDriver.JSON.readTree(result.getResponse().getContentAsString());

        assertThat(tokens.get("refreshToken").asString()).startsWith("hdr_").isNotEqualTo(signedIn.refreshToken());
        assertThat(tokens.get("accessToken").asString()).startsWith("hda_").isNotEqualTo(signedIn.accessToken());
        assertThat(me(tokens.get("accessToken").asString())).isEqualTo(200);
        assertThat(me(signedIn.accessToken())).isEqualTo(401);
    }

    @Test
    void replayingAConsumedRefreshTokenRevokesTheWholeSession() throws Exception {
        SignedIn signedIn = driver.signIn("sub-replay", "replay@example.com");
        JsonNode rotated = DesktopLoginDriver.JSON.readTree(refresh(signedIn.refreshToken()).getResponse().getContentAsString());

        MvcResult replay = refresh(signedIn.refreshToken());

        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        // The legitimate (latest) tokens are now dead too: the family was revoked.
        assertThat(refresh(rotated.get("refreshToken").asString()).getResponse().getStatus()).isEqualTo(401);
        assertThat(me(rotated.get("accessToken").asString())).isEqualTo(401);
        assertThat(jdbc.sql("SELECT revoked_reason FROM device_sessions").query(String.class).single())
                .isEqualTo("refresh_reuse");
    }

    @Test
    void logoutRevokesTheSessionAndItsAccessTokens() throws Exception {
        SignedIn signedIn = driver.signIn("sub-logout", "logout@example.com");

        MvcResult logout = mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + signedIn.accessToken()))
                .andReturn();

        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(me(signedIn.accessToken())).isEqualTo(401);
        assertThat(refresh(signedIn.refreshToken()).getResponse().getStatus()).isEqualTo(401);
        assertThat(count("access_tokens")).isZero();
    }

    @Test
    void logoutAffectsOnlyThatDevice() throws Exception {
        SignedIn laptop = driver.signIn("sub-two-devices", "d@example.com");
        SignedIn desktop = driver.signIn("sub-two-devices", "d@example.com");

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + laptop.accessToken()));

        assertThat(me(laptop.accessToken())).isEqualTo(401);
        assertThat(me(desktop.accessToken())).isEqualTo(200);
    }

    @Test
    void accessTokenExpiresButSessionCanBeRefreshed() throws Exception {
        SignedIn signedIn = driver.signIn("sub-access-expiry", "ae@example.com");
        mutableClock().advance(Duration.ofMinutes(16));

        assertThat(me(signedIn.accessToken())).isEqualTo(401);
        assertThat(refresh(signedIn.refreshToken()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredSessionRequiresSigningInAgain() throws Exception {
        SignedIn signedIn = driver.signIn("sub-session-expiry", "se@example.com");
        mutableClock().advance(Duration.ofDays(31));

        assertThat(refresh(signedIn.refreshToken()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void missingInvalidOrQueryParameterTokensAreUnauthorized() throws Exception {
        SignedIn signedIn = driver.signIn("sub-unauth", "u@example.com");

        MvcResult none = mvc.perform(get("/api/v1/me")).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        assertThat(none.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(none.getResponse().getContentAsString()).contains("\"code\":\"unauthenticated\"");

        assertThat(me("hda_" + "A".repeat(43))).isEqualTo(401);
        assertThat(me("not-even-a-token")).isEqualTo(401);
        // Tokens in URLs are not accepted (they would leak into logs and history).
        assertThat(mvc.perform(get("/api/v1/me").queryParam("access_token", signedIn.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        // A refresh token is not an access token.
        assertThat(me(signedIn.refreshToken())).isEqualTo(401);
    }

    @Test
    void refreshWithGarbageIsUnauthorized() throws Exception {
        assertThat(refresh("hdr_" + "B".repeat(43)).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh("nonsense").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void tokensAreStoredOnlyAsHashes() throws Exception {
        SignedIn signedIn = driver.signIn("sub-hashes", "h@example.com");

        assertThat(jdbc.sql("SELECT count(*) FROM access_tokens WHERE token_hash = :h")
                .param("h", SecureTokens.hash(signedIn.accessToken())).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM device_sessions WHERE current_refresh_token_hash = :h")
                .param("h", SecureTokens.hash(signedIn.refreshToken())).query(Long.class).single()).isEqualTo(1);
    }
}
