package com.hyperdrive.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.support.AuthIntegrationTest;
import com.hyperdrive.backend.support.DesktopLoginDriver;
import com.hyperdrive.backend.support.DesktopLoginDriver.SignedIn;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import com.hyperdrive.backend.web.ApiException;

class OnboardingTest extends AuthIntegrationTest {

    @Autowired
    UserService users;

    private DesktopLoginDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DesktopLoginDriver(mvc, google);
    }

    private MvcResult onboard(String accessToken, String jsonBody) throws Exception {
        return mvc.perform(post("/api/v1/me/onboarding").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody)).andReturn();
    }

    private MvcResult onboardAs(String accessToken, String username) throws Exception {
        return onboard(accessToken, "{\"username\":\"" + username + "\"}");
    }

    @Test
    void choosingAUsernameCompletesOnboardingAndKeepsCasing() throws Exception {
        SignedIn signedIn = driver.signIn("sub-onboard", "o@example.com");

        MvcResult result = onboardAs(signedIn.accessToken(), "Rishabh_01");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"username\":\"Rishabh_01\"", "\"onboardingComplete\":true");
        assertThat(jdbc.sql("SELECT username_normalized FROM users").query(String.class).single()).isEqualTo("rishabh_01");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxy", "has space", "dash-name", "dot.name", "émile", "", "名前です"})
    void invalidUsernamesAreRejectedByTheBackend(String username) throws Exception {
        SignedIn signedIn = driver.signIn("sub-invalid-" + UUID.randomUUID(), null);

        MvcResult result = onboardAs(signedIn.accessToken(), username);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("invalid_username");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Administrator", "SUPPORT", "system", "HyperDrive"})
    void reservedUsernamesAreRejected(String username) throws Exception {
        SignedIn signedIn = driver.signIn("sub-reserved-" + UUID.randomUUID(), null);

        MvcResult result = onboardAs(signedIn.accessToken(), username);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("username_reserved");
    }

    @Test
    void usernamesAreUniqueCaseInsensitively() throws Exception {
        SignedIn first = driver.signIn("sub-unique-1", null);
        SignedIn second = driver.signIn("sub-unique-2", null);
        onboardAs(first.accessToken(), "Taken_Name");

        MvcResult result = onboardAs(second.accessToken(), "taken_name");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("username_taken");
    }

    @Test
    void onboardingCannotBeRepeated() throws Exception {
        SignedIn signedIn = driver.signIn("sub-repeat", null);
        onboardAs(signedIn.accessToken(), "First_Name");

        MvcResult again = onboardAs(signedIn.accessToken(), "Second_Name");

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("onboarding_already_complete");
    }

    @Test
    void clientCannotSupplyAUserId() throws Exception {
        SignedIn victim = driver.signIn("sub-victim", null);
        SignedIn attacker = driver.signIn("sub-attacker", null);

        MvcResult result = onboard(attacker.accessToken(),
                "{\"username\":\"Hijack\",\"userId\":\"" + victim.user().get("id").asString() + "\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username IS NOT NULL").query(Long.class).single()).isZero();
    }

    @Test
    void onboardingRequiresAuthentication() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/me/onboarding").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"Nobody\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void usersWithoutUsernameCanReachOnlyProfileOnboardingAndLogout() throws Exception {
        SignedIn signedIn = driver.signIn("sub-gate", null);
        String bearer = "Bearer " + signedIn.accessToken();

        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", bearer)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        // Any other API (present or future) requires a completed username.
        assertThat(mvc.perform(get("/api/v1/anything-else").header("Authorization", bearer)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);

        onboardAs(signedIn.accessToken(), "Now_Onboarded");
        // Same token, now onboarded: passes authorization (and the path simply doesn't exist).
        assertThat(mvc.perform(get("/api/v1/anything-else").header("Authorization", bearer)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void concurrentClaimsOfTheSameUsernameHaveExactlyOneWinner() throws Exception {
        List<UUID> userIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            userIds.add(users.findOrCreate(new ExternalIdentity("https://issuer.test", "race-" + i, null, null)));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(userIds.size());
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (UUID id : userIds) {
                Callable<String> claim = () -> {
                    start.await();
                    try {
                        users.completeOnboarding(id, "Contested_Name");
                        return "ok";
                    } catch (ApiException e) {
                        return e.code();
                    }
                };
                outcomes.add(pool.submit(claim));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : outcomes) {
                results.add(f.get());
            }
            assertThat(results).filteredOn("ok"::equals).hasSize(1);
            assertThat(results).filteredOn("username_taken"::equals).hasSize(userIds.size() - 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
