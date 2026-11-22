package com.hyperdrive.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.support.FakeOidcProvider.Login;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Plays the desktop main process (bearer API) and the system browser (ticket start, Google
 * consent, callback) for storage connections, step by step.
 */
public final class StorageConnectionDriver {

    private final MockMvc mvc;
    private final FakeOidcProvider google;
    /** Every response body/header seen by the "desktop", to assert no provider token leaks. */
    public final List<String> transcript = new java.util.ArrayList<>();

    public StorageConnectionDriver(MockMvc mvc, FakeOidcProvider google) {
        this.mvc = mvc;
        this.google = google;
    }

    public record Started(UUID transactionId, String authorizationUrl, JsonNode body) {
        public String ticketPath() {
            return URI.create(authorizationUrl).getPath();
        }
    }

    /** The browser after opening the ticket URL: Google's consent URL and the binding cookie. */
    public record AtGoogle(URI location, Map<String, String> params, Cookie binding) {
    }

    public static Login driveLogin(String subject, String email) {
        return new Login(subject, email, true, "Drive Owner", null, null, false, FakeOidcProvider.DRIVE_SCOPE, true);
    }

    public MvcResult createRaw(String accessToken) throws Exception {
        return createRaw(accessToken, "google");
    }

    public MvcResult createRaw(String accessToken, String provider) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/storage-connections/" + provider + "/transactions");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return record(mvc.perform(request).andReturn());
    }

    public Started create(String accessToken) throws Exception {
        return create(accessToken, "google");
    }

    public Started create(String accessToken, String provider) throws Exception {
        MvcResult result = createRaw(accessToken, provider);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode json = DesktopLoginDriver.JSON.readTree(result.getResponse().getContentAsString());
        return new Started(UUID.fromString(json.get("transactionId").asString()), json.get("authorizationUrl").asString(), json);
    }

    public MvcResult openRaw(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    public AtGoogle open(Started started) throws Exception {
        MvcResult result = openRaw(started.ticketPath());
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(302);
        URI location = URI.create(result.getResponse().getHeader("Location"));
        return new AtGoogle(location, DesktopLoginDriver.queryParams(location), bindingCookie(result));
    }

    /** Google redirecting the browser back to the storage callback. */
    public MvcResult callback(Map<String, String> params, Cookie binding) throws Exception {
        return callback("google", params, binding);
    }

    public MvcResult callback(String provider, Map<String, String> params, Cookie binding) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/storage-connections/" + provider + "/callback");
        params.forEach(request::queryParam);
        if (binding != null) {
            request.cookie(binding);
        }
        return mvc.perform(request).andReturn();
    }

    /** Completes consent at the fake Google as {@code login}; returns the callback result. */
    public MvcResult consent(AtGoogle atGoogle, Login login) throws Exception {
        String code = "google-drive-code-" + DesktopLoginDriver.random(12);
        google.prepare(code, login.nonce() != null ? login : login.withNonce(atGoogle.params().get("nonce")));
        return callback(Map.of("code", code, "state", atGoogle.params().get("state")), atGoogle.binding());
    }

    /** Full happy path; returns the connected account id. */
    public UUID connect(String accessToken, Login login) throws Exception {
        Started started = create(accessToken);
        MvcResult callback = consent(open(started), login);
        assertThat(callback.getResponse().getStatus()).as(callback.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode status = statusJson(accessToken, started.transactionId());
        assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
        return UUID.fromString(status.get("accountId").asString());
    }

    // --- Dropbox ---------------------------------------------------------------------------

    /** Completes Dropbox consent at the fake as {@code account}; returns the callback result. */
    public MvcResult dropboxConsent(FakeDropbox fake, AtGoogle atDropbox, FakeDropbox.Account account) throws Exception {
        String code = "dropbox-code-" + DesktopLoginDriver.random(12);
        fake.prepare(code, account);
        return callback("dropbox", Map.of("code", code, "state", atDropbox.params().get("state")), atDropbox.binding());
    }

    /** Full Dropbox happy path; returns the connected account id. */
    public UUID connectDropbox(String accessToken, FakeDropbox fake, FakeDropbox.Account account) throws Exception {
        Started started = create(accessToken, "dropbox");
        MvcResult callback = dropboxConsent(fake, open(started), account);
        assertThat(callback.getResponse().getStatus()).as(callback.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode status = statusJson(accessToken, started.transactionId());
        assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
        return UUID.fromString(status.get("accountId").asString());
    }

    public MvcResult status(String accessToken, UUID transactionId) throws Exception {
        return record(mvc.perform(get("/api/v1/storage-connections/transactions/" + transactionId)
                .header("Authorization", "Bearer " + accessToken)).andReturn());
    }

    public JsonNode statusJson(String accessToken, UUID transactionId) throws Exception {
        MvcResult result = status(accessToken, transactionId);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return DesktopLoginDriver.JSON.readTree(result.getResponse().getContentAsString());
    }

    public MvcResult cancel(String accessToken, UUID transactionId) throws Exception {
        return record(mvc.perform(post("/api/v1/storage-connections/transactions/" + transactionId + "/cancel")
                .header("Authorization", "Bearer " + accessToken)).andReturn());
    }

    public MvcResult api(String accessToken, String path) throws Exception {
        return record(mvc.perform(get(path).header("Authorization", "Bearer " + accessToken)).andReturn());
    }

    public JsonNode apiJson(String accessToken, String path) throws Exception {
        MvcResult result = api(accessToken, path);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return DesktopLoginDriver.JSON.readTree(result.getResponse().getContentAsString());
    }

    public MvcResult disconnect(String accessToken, UUID accountId) throws Exception {
        return record(mvc.perform(delete("/api/v1/accounts/" + accountId).header("Authorization", "Bearer " + accessToken))
                .andReturn());
    }

    private static Cookie bindingCookie(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("HD_STORAGE_BINDING=")) {
                String value = header.substring("HD_STORAGE_BINDING=".length(), header.indexOf(';'));
                return new Cookie("HD_STORAGE_BINDING", value);
            }
        }
        throw new AssertionError("No binding cookie set");
    }

    private MvcResult record(MvcResult result) throws Exception {
        transcript.add(result.getResponse().getContentAsString());
        for (String name : result.getResponse().getHeaderNames()) {
            transcript.add(name + ": " + String.join(",", result.getResponse().getHeaders(name)));
        }
        return result;
    }
}
