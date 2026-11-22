package com.hyperdrive.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyperdrive.backend.auth.desktop.LoginTransactionService;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.user.UsernamePolicy;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** Pure unit tests: no Spring context, no database. */
class AuthUnitTest {

    @Test
    void pkceMatchesRfc7636AppendixBExample() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertThat(Pkce.challengeFor(verifier)).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assertThat(Pkce.matches(verifier, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "contains space and is long enough to pass the length check!!", "üüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüü"})
    void pkceRejectsMalformedVerifiers(String verifier) {
        assertThat(Pkce.matches(verifier, Pkce.challengeFor("a".repeat(43)))).isFalse();
    }

    @Test
    void pkceRejectsOverlongVerifier() {
        String verifier = "a".repeat(129);
        assertThat(Pkce.matches(verifier, Pkce.challengeFor(verifier))).isFalse();
    }

    @Test
    void secureTokensAreRandomPrefixedAndValidated() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = SecureTokens.ACCESS_TOKEN.generate();
            assertThat(SecureTokens.ACCESS_TOKEN.isWellFormed(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
        String refresh = SecureTokens.REFRESH_TOKEN.generate();
        assertThat(SecureTokens.ACCESS_TOKEN.isWellFormed(refresh)).isFalse();
        assertThat(SecureTokens.hash(refresh)).hasSize(32);
        assertThat(SecureTokens.HANDOFF_CODE.isWellFormed(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "Rishabh_01", "A_B_C", "abcdefghijklmnopqrstuvwx", "123"})
    void validUsernames(String username) {
        assertThat(UsernamePolicy.check(username)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxy", "a b", "a-b", "a.b", "ébc", "abc\n", ""})
    void invalidUsernames(String username) {
        assertThat(UsernamePolicy.check(username)).contains(UsernamePolicy.Violation.INVALID_FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Administrator", "support", "System", "hyperdrive", "root"})
    void reservedUsernames(String username) {
        assertThat(UsernamePolicy.check(username)).contains(UsernamePolicy.Violation.RESERVED);
    }

    @Test
    void loopbackCallbackIsAlwaysExact127001WithFixedPathAndEncodedValues() {
        URI uri = new LoginTransactionService.Callback(53682, "state_123-ABC").withParams("code", "hdc_x/y?z&w");
        assertThat(uri.getScheme()).isEqualTo("http");
        assertThat(uri.getHost()).isEqualTo("127.0.0.1");
        assertThat(uri.getPort()).isEqualTo(53682);
        assertThat(uri.getPath()).isEqualTo("/callback");
        assertThat(uri.getRawQuery()).isEqualTo("code=hdc_x/y?z%26w&state=state_123-ABC");
    }

    @Test
    void startupCheckRejectsMissingOrBadConfiguration() {
        var good = new InMemoryClientRegistrationRepository(google("id", "secret", "openid", "profile", "email"));
        AuthProperties ok = properties("http://127.0.0.1:18080");
        new AuthStartupCheck(ok, good).afterPropertiesSet();

        assertThatThrownBy(() -> new AuthStartupCheck(properties(null), good).afterPropertiesSet())
                .hasMessageContaining("HYPERDRIVE_PUBLIC_BASE_URL");
        assertThatThrownBy(() -> new AuthStartupCheck(properties("http://127.0.0.1:18080/path"), good).afterPropertiesSet())
                .hasMessageContaining("HYPERDRIVE_PUBLIC_BASE_URL");
        assertThatThrownBy(() -> new AuthStartupCheck(properties("ftp://example.com"), good).afterPropertiesSet())
                .hasMessageContaining("HYPERDRIVE_PUBLIC_BASE_URL");
        var unresolved = new InMemoryClientRegistrationRepository(google("${GOOGLE_CLIENT_ID}", "secret", "openid"));
        assertThatThrownBy(() -> new AuthStartupCheck(ok, unresolved).afterPropertiesSet())
                .hasMessageContaining("GOOGLE_CLIENT_ID");
        assertThatThrownBy(() -> new AuthStartupCheck(properties("http://localhost:9999"), good).afterPropertiesSet())
                .hasMessageContaining("redirect URI must be http://localhost:9999/login/oauth2/code/google");
        var driveScope = new InMemoryClientRegistrationRepository(
                google("id", "secret", "openid", "https://www.googleapis.com/auth/drive"));
        assertThatThrownBy(() -> new AuthStartupCheck(ok, driveScope).afterPropertiesSet())
                .hasMessageContaining("only openid, profile and email");
    }

    private static AuthProperties properties(String baseUrl) {
        return new AuthProperties(true, baseUrl == null ? null : URI.create(baseUrl), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofMinutes(15), Duration.ofDays(30));
    }

    private static ClientRegistration google(String id, String secret, String... scopes) {
        return ClientRegistration.withRegistrationId("google")
                .clientId(id).clientSecret(secret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:18080/login/oauth2/code/google")
                .scope(scopes)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                .build();
    }
}
