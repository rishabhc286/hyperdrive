package com.hyperdrive.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.auth.Pkce;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the parts of the Electron main process and the system browser against the real backend,
 * step by step, so tests can stop or tamper at any point.
 */
public final class DesktopLoginDriver {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MockMvc mvc;
    private final FakeOidcProvider google;
    /** Every response body/header seen, to assert no provider token ever reaches the desktop. */
    public final List<String> transcript = new ArrayList<>();

    public DesktopLoginDriver(MockMvc mvc, FakeOidcProvider google) {
        this.mvc = mvc;
        this.google = google;
    }

    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Desktop-side secrets and the created transaction. */
    public record Transaction(String verifier, String desktopState, int port, UUID id, String authorizationUrl) {
    }

    public record GoogleRedirect(MockHttpSession session, URI location, Map<String, String> params) {
    }

    public Transaction createTransaction() throws Exception {
        String verifier = random(48);
        String state = random(24);
        int port = 49152 + RANDOM.nextInt(1000);
        String body = JSON.writeValueAsString(Map.of(
                "codeChallenge", Pkce.challengeFor(verifier),
                "codeChallengeMethod", "S256",
                "callbackPort", port,
                "callbackState", state,
                "deviceName", "Test PC"));
        MvcResult result = mvc.perform(post("/api/v1/auth/desktop/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        record(result);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode json = JSON.readTree(result.getResponse().getContentAsString());
        return new Transaction(verifier, state, port, UUID.fromString(json.get("transactionId").asString()),
                json.get("authorizationUrl").asString());
    }

    /** The system browser opening the authorization URL; returns Spring's redirect to "Google". */
    public GoogleRedirect openInBrowser(Transaction tx) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mvc.perform(get(URI.create(tx.authorizationUrl()).getPath()).session(session)).andReturn();
        record(result);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(302);
        URI location = URI.create(result.getResponse().getHeader("Location"));
        return new GoogleRedirect(session, location, queryParams(location));
    }

    /** Google redirecting the browser back to the backend callback. */
    public MvcResult googleCallback(GoogleRedirect redirect, Map<String, String> params) throws Exception {
        var request = get("/login/oauth2/code/google").session(redirect.session());
        params.forEach(request::queryParam);
        MvcResult result = mvc.perform(request).andReturn();
        record(result);
        return result;
    }

    /** Completes the Google leg as {@code login} (nonce filled in from the real authorization request). */
    public MvcResult completeAtGoogle(GoogleRedirect redirect, FakeOidcProvider.Login login) throws Exception {
        String code = "google-code-" + random(12);
        google.prepare(code, new FakeOidcProvider.Login(login.subject(), login.email(), login.emailVerified(),
                login.name(), login.nonce() != null ? login.nonce() : redirect.params().get("nonce"),
                login.audienceOverride(), login.signWithWrongKey()));
        return googleCallback(redirect, Map.of("code", code, "state", redirect.params().get("state")));
    }

    public MvcResult exchange(UUID transactionId, String code, String verifier) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/desktop/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "transactionId", transactionId.toString(), "code", code, "codeVerifier", verifier))))
                .andReturn();
        record(result);
        return result;
    }

    public record SignedIn(Transaction tx, JsonNode tokens) {
        public String accessToken() {
            return tokens.get("accessToken").asString();
        }

        public String refreshToken() {
            return tokens.get("refreshToken").asString();
        }

        public JsonNode user() {
            return tokens.get("user");
        }
    }

    /** Full happy path for a Google subject. */
    public SignedIn signIn(String subject, String email) throws Exception {
        Transaction tx = createTransaction();
        GoogleRedirect redirect = openInBrowser(tx);
        MvcResult callback = completeAtGoogle(redirect,
                new FakeOidcProvider.Login(subject, email, true, "Test User", null, null, false));
        assertThat(callback.getResponse().getStatus()).as(callback.getResponse().getContentAsString()).isEqualTo(302);
        Map<String, String> loopback = queryParams(URI.create(callback.getResponse().getHeader("Location")));
        MvcResult exchange = exchange(tx.id(), loopback.get("code"), tx.verifier());
        assertThat(exchange.getResponse().getStatus()).as(exchange.getResponse().getContentAsString()).isEqualTo(200);
        return new SignedIn(tx, JSON.readTree(exchange.getResponse().getContentAsString()));
    }

    public static Map<String, String> queryParams(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> java.net.URLDecoder.decode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void record(MvcResult result) throws Exception {
        transcript.add(result.getResponse().getContentAsString());
        for (String name : result.getResponse().getHeaderNames()) {
            transcript.add(name + ": " + String.join(",", result.getResponse().getHeaders(name)));
        }
    }
}
