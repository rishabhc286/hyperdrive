package com.hyperdrive.backend.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-process stand-in for Google's OpenID Connect endpoints (discovery, token, JWKS, userinfo).
 * It signs real RS256 ID tokens so Spring Security's genuine OIDC validation runs unmodified.
 * Tests register what the "user" did at Google via {@link #prepare}.
 */
public final class FakeOidcProvider {

    /** What Google would return for one authorization code. */
    public record Login(String subject, String email, boolean emailVerified, String name, String nonce,
            String audienceOverride, boolean signWithWrongKey) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;
    private final String clientId;
    private final Map<String, Login> codes = new ConcurrentHashMap<>();
    private final Map<String, Login> accessTokens = new ConcurrentHashMap<>();
    /** Every Google access/ID token issued, so tests can assert none leak to the desktop. */
    public final List<String> issuedProviderTokens = new CopyOnWriteArrayList<>();
    /** Form parameters of every token request (to check Google-side PKCE). */
    public final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

    public FakeOidcProvider(String clientId) {
        this.clientId = clientId;
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            this.wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/jwks", ex -> respond(ex, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
        server.createContext("/token", this::token);
        server.createContext("/userinfo", this::userinfo);
        server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void prepare(String code, Login login) {
        codes.put(code, login);
    }

    public void stop() {
        server.stop(0);
    }

    private void discovery(HttpExchange ex) throws IOException {
        String issuer = issuer();
        Map<String, Object> doc = new HashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer + "/authorize");
        doc.put("token_endpoint", issuer + "/token");
        doc.put("jwks_uri", issuer + "/jwks");
        doc.put("userinfo_endpoint", issuer + "/userinfo");
        doc.put("response_types_supported", List.of("code"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("scopes_supported", List.of("openid", "profile", "email"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        respond(ex, 200, doc);
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(form);
        Login login = codes.remove(form.getOrDefault("code", ""));
        if (login == null) {
            respond(ex, 400, Map.of("error", "invalid_grant"));
            return;
        }
        String accessToken = "ya29.fake-google-access-" + UUID.randomUUID();
        accessTokens.put(accessToken, login);
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer())
                    .subject(login.subject())
                    .audience(login.audienceOverride() != null ? login.audienceOverride() : clientId)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("nonce", login.nonce())
                    .claim("email_verified", login.emailVerified());
            if (login.email() != null) {
                claims.claim("email", login.email());
            }
            if (login.name() != null) {
                claims.claim("name", login.name());
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(login.signWithWrongKey() ? wrongKey : signingKey));
            String idToken = jwt.serialize();
            issuedProviderTokens.add(accessToken);
            issuedProviderTokens.add(idToken);
            respond(ex, 200, Map.of(
                    "access_token", accessToken,
                    "token_type", "Bearer",
                    "expires_in", 3600,
                    "scope", "openid profile email",
                    "id_token", idToken));
        } catch (Exception e) {
            respond(ex, 500, Map.of("error", "server_error"));
        }
    }

    private void userinfo(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Login login = auth == null ? null : accessTokens.get(auth.replaceFirst("^Bearer ", ""));
        if (login == null) {
            respond(ex, 401, Map.of("error", "invalid_token"));
            return;
        }
        Map<String, Object> info = new HashMap<>();
        info.put("sub", login.subject());
        info.put("email_verified", login.emailVerified());
        if (login.email() != null) {
            info.put("email", login.email());
        }
        if (login.name() != null) {
            info.put("name", login.name());
        }
        respond(ex, 200, info);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
