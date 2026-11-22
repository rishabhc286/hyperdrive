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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-process stand-in for Google: OpenID Connect (discovery, token incl. refresh grant, JWKS,
 * userinfo) and a small Drive API v3 ({@code files.list} children queries and {@code files.get}).
 * It signs real RS256 ID tokens so Spring Security's genuine OIDC validation runs unmodified.
 * Tests register what the "user" did at Google via {@link #prepare} and the Drive contents via
 * {@link #drive(String)}.
 */
public final class FakeOidcProvider {

    public static final String SIGN_IN_SCOPE = "openid profile email";
    public static final String DRIVE_SCOPE = "openid https://www.googleapis.com/auth/userinfo.email "
            + "https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/drive.readonly";

    /**
     * What Google would return for one authorization code.
     *
     * @param scope         space-separated scopes Google reports as granted
     * @param issueRefresh  whether the code exchange returns a refresh token
     */
    public record Login(String subject, String email, boolean emailVerified, String name, String nonce,
            String audienceOverride, boolean signWithWrongKey, String scope, boolean issueRefresh) {

        public Login(String subject, String email, boolean emailVerified, String name, String nonce,
                String audienceOverride, boolean signWithWrongKey) {
            this(subject, email, emailVerified, name, nonce, audienceOverride, signWithWrongKey, SIGN_IN_SCOPE, false);
        }

        public Login withNonce(String value) {
            return new Login(subject, email, emailVerified, name, value, audienceOverride, signWithWrongKey, scope,
                    issueRefresh);
        }
    }

    /** One Drive file resource (as Google would store it). */
    public record DriveFile(String id, String name, String mimeType, String parent, String size, String modifiedTime,
            boolean trashed, boolean canDownload, String shortcutTargetId, String shortcutTargetMimeType) {

        public static DriveFile folder(String id, String name, String parent) {
            return new DriveFile(id, name, "application/vnd.google-apps.folder", parent, null,
                    "2026-01-02T03:04:05.000Z", false, false, null, null);
        }

        public static DriveFile file(String id, String name, String parent, long size) {
            return new DriveFile(id, name, "application/pdf", parent, Long.toString(size), "2026-02-03T04:05:06.000Z",
                    false, true, null, null);
        }
    }

    /** A canned HTTP response to return instead of the normal handling. */
    public record Injected(int status, Map<String, String> headers, String body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CHILDREN_QUERY = Pattern.compile("^'((?:[^'\\\\]|\\\\.)+)' in parents and trashed = false$");

    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;
    private final String clientId;
    private final Map<String, Login> codes = new ConcurrentHashMap<>();
    private final Map<String, Login> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Login> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DriveFile>> drives = new ConcurrentHashMap<>();
    /** Every Google access/ID/refresh token issued, so tests can assert none leak to the desktop. */
    public final List<String> issuedProviderTokens = new CopyOnWriteArrayList<>();
    /** Form parameters of every token request (to check Google-side PKCE and refresh grants). */
    public final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    /** Every Drive API request URI (path + raw query). */
    public final List<URI> driveRequests = new CopyOnWriteArrayList<>();
    /** Responses returned (in order) for the next Drive API requests instead of real handling. */
    public final ConcurrentLinkedQueue<Injected> driveFailures = new ConcurrentLinkedQueue<>();
    /** Responses returned (in order) for the next refresh-token grants. */
    public final ConcurrentLinkedQueue<Injected> refreshFailures = new ConcurrentLinkedQueue<>();
    /** Lifetime of issued access tokens, in seconds. */
    public volatile int accessTokenSeconds = 3600;
    /** Whether a refresh grant also returns a new refresh token (Google usually does not). */
    public volatile boolean rotateRefreshTokens = false;
    public final AtomicInteger refreshGrants = new AtomicInteger();

    public FakeOidcProvider(String clientId) {
        this.clientId = clientId;
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            this.wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/jwks", ex -> respond(ex, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
        server.createContext("/token", this::token);
        server.createContext("/userinfo", this::userinfo);
        server.createContext("/drive/v3/files", this::drive);
        server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String driveApiBaseUrl() {
        return issuer() + "/drive/v3";
    }

    public void prepare(String code, Login login) {
        codes.put(code, login);
    }

    /** Drive contents of a Google subject, keyed by file id. Created empty with a root folder. */
    public Map<String, DriveFile> drive(String subject) {
        return drives.computeIfAbsent(subject, s -> {
            Map<String, DriveFile> files = new ConcurrentHashMap<>();
            files.put("root-" + s, DriveFile.folder("root-" + s, "My Drive", null));
            return files;
        });
    }

    public void add(String subject, DriveFile file) {
        drive(subject).put(file.id(), file);
    }

    /** Revokes every refresh token of a subject (as if the user removed access at Google). */
    public void revokeRefreshTokens(String subject) {
        refreshTokens.values().removeIf(l -> l.subject().equals(subject));
    }

    /** Invalidates every access token of a subject. */
    public void revokeAccessTokens(String subject) {
        accessTokens.values().removeIf(l -> l.subject().equals(subject));
    }

    public void resetDrive() {
        drives.clear();
        driveRequests.clear();
        driveFailures.clear();
        refreshFailures.clear();
        tokenRequests.clear();
        accessTokenSeconds = 3600;
        rotateRefreshTokens = false;
        refreshGrants.set(0);
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
        if ("refresh_token".equals(form.get("grant_type"))) {
            refresh(ex, form);
            return;
        }
        Login login = codes.remove(form.getOrDefault("code", ""));
        if (login == null) {
            respond(ex, 400, Map.of("error", "invalid_grant"));
            return;
        }
        String accessToken = newAccessToken(login);
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
            issuedProviderTokens.add(idToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", accessToken);
            body.put("token_type", "Bearer");
            body.put("expires_in", accessTokenSeconds);
            body.put("scope", login.scope());
            body.put("id_token", idToken);
            if (login.issueRefresh()) {
                body.put("refresh_token", newRefreshToken(login));
            }
            respond(ex, 200, body);
        } catch (Exception e) {
            respond(ex, 500, Map.of("error", "server_error"));
        }
    }

    private void refresh(HttpExchange ex, Map<String, String> form) throws IOException {
        refreshGrants.incrementAndGet();
        Injected injected = refreshFailures.poll();
        if (injected != null) {
            respondRaw(ex, injected);
            return;
        }
        Login login = refreshTokens.get(form.getOrDefault("refresh_token", ""));
        if (login == null) {
            respond(ex, 400, Map.of("error", "invalid_grant", "error_description", "Token has been expired or revoked."));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", newAccessToken(login));
        body.put("token_type", "Bearer");
        body.put("expires_in", accessTokenSeconds);
        body.put("scope", login.scope());
        if (rotateRefreshTokens) {
            refreshTokens.remove(form.get("refresh_token"));
            body.put("refresh_token", newRefreshToken(login));
        }
        respond(ex, 200, body);
    }

    private String newAccessToken(Login login) {
        String token = "ya29.fake-google-access-" + UUID.randomUUID();
        accessTokens.put(token, login);
        issuedProviderTokens.add(token);
        return token;
    }

    private String newRefreshToken(Login login) {
        String token = "1//fake-google-refresh-" + UUID.randomUUID();
        refreshTokens.put(token, login);
        issuedProviderTokens.add(token);
        return token;
    }

    private void userinfo(HttpExchange ex) throws IOException {
        Login login = bearer(ex);
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

    /** Drive API: GET /drive/v3/files (children query) and GET /drive/v3/files/{id}. */
    private void drive(HttpExchange ex) throws IOException {
        driveRequests.add(ex.getRequestURI());
        Injected injected = driveFailures.poll();
        if (injected != null) {
            respondRaw(ex, injected);
            return;
        }
        Login login = bearer(ex);
        if (login == null) {
            respond(ex, 401, Map.of("error", Map.of("code", 401, "message", "Invalid Credentials",
                    "errors", List.of(Map.of("reason", "authError")))));
            return;
        }
        if (!login.scope().contains("drive.readonly")) {
            respond(ex, 403, Map.of("error", Map.of("code", 403, "status", "PERMISSION_DENIED",
                    "errors", List.of(Map.of("reason", "insufficientPermissions")),
                    "details", List.of(Map.of("reason", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")))));
            return;
        }
        Map<String, DriveFile> files = drive(login.subject());
        Map<String, String> query = parseForm(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
        String path = ex.getRequestURI().getPath();
        String rest = path.substring("/drive/v3/files".length());
        if (rest.startsWith("/")) {
            String id = URLDecoder.decode(rest.substring(1), StandardCharsets.UTF_8);
            DriveFile file = files.get("root".equals(id) ? "root-" + login.subject() : id);
            if (file == null) {
                respond(ex, 404, Map.of("error", Map.of("code", 404, "errors", List.of(Map.of("reason", "notFound")))));
                return;
            }
            respond(ex, 200, resource(file));
            return;
        }
        Matcher m = CHILDREN_QUERY.matcher(query.getOrDefault("q", ""));
        // Mimic Google: without "trashed = false" trashed items would be listed, so the fake only
        // supports the exact query the adapter must send.
        if (!m.matches()) {
            respond(ex, 400, Map.of("error", Map.of("code", 400, "errors", List.of(Map.of("reason", "invalid")))));
            return;
        }
        String parent = m.group(1).replace("\\'", "'").replace("\\\\", "\\");
        List<DriveFile> children = new ArrayList<>(files.values().stream()
                .filter(f -> parent.equals(f.parent()) && !f.trashed()).toList());
        children.sort((a, b) -> {
            boolean af = a.mimeType().endsWith(".folder");
            boolean bf = b.mimeType().endsWith(".folder");
            return af != bf ? (af ? -1 : 1) : a.name().compareTo(b.name());
        });
        int size = Integer.parseInt(query.getOrDefault("pageSize", "100"));
        int offset = query.containsKey("pageToken") ? Integer.parseInt(query.get("pageToken").replace("page~", "")) : 0;
        List<DriveFile> page = children.subList(Math.min(offset, children.size()), Math.min(offset + size, children.size()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", page.stream().map(FakeOidcProvider::resource).toList());
        if (offset + size < children.size()) {
            body.put("nextPageToken", "page~" + (offset + size));
        }
        respond(ex, 200, body);
    }

    private static Map<String, Object> resource(DriveFile f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", f.id());
        r.put("name", f.name());
        r.put("mimeType", f.mimeType());
        if (f.size() != null) {
            r.put("size", f.size());
        }
        if (f.modifiedTime() != null) {
            r.put("modifiedTime", f.modifiedTime());
        }
        r.put("trashed", f.trashed());
        r.put("capabilities", Map.of("canDownload", f.canDownload()));
        if (f.shortcutTargetId() != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("targetId", f.shortcutTargetId());
            if (f.shortcutTargetMimeType() != null) {
                s.put("targetMimeType", f.shortcutTargetMimeType());
            }
            r.put("shortcutDetails", s);
        }
        return r;
    }

    private Login bearer(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        return auth == null ? null : accessTokens.get(auth.replaceFirst("^Bearer ", ""));
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

    private static void respondRaw(HttpExchange ex, Injected injected) throws IOException {
        byte[] bytes = injected.body().getBytes(StandardCharsets.UTF_8);
        injected.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(injected.status(), bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
