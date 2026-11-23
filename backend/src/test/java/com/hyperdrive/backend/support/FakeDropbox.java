package com.hyperdrive.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-process stand-in for the Dropbox API v2 endpoints HyperDrive uses: {@code /oauth2/token}
 * (authorization_code and refresh_token grants), {@code /2/users/get_current_account},
 * {@code /2/files/list_folder}, {@code /2/files/list_folder/continue} and {@code /2/files/get_metadata}.
 * Responses follow the documented field names. Files calls are answered only for the root namespace
 * given in {@code Dropbox-API-Path-Root}; without the header a team member sees their member folder.
 */
public final class FakeDropbox {

    public static final String APP_KEY = "test-dropbox-app-key";
    public static final String APP_SECRET = "test-dropbox-app-secret";
    /** Pre-Batch-6 grant (no content read). */
    public static final String SCOPES = "account_info.read files.metadata.read";
    /** Batch 6 grant: includes files.content.read. */
    public static final String SCOPES_WITH_CONTENT = "account_info.read files.content.read files.metadata.read";

    /** What the user authorized for one code. */
    public record Account(String accountId, String displayName, String email, boolean emailVerified, String rootTag,
            String rootNamespaceId, String homeNamespaceId, String grantedScope, boolean issueRefresh) {

        public static Account personal(String accountId, String email) {
            return new Account(accountId, "Dropbox Owner", email, true, "user", "100" + Math.abs(accountId.hashCode() % 1000),
                    null, SCOPES, true);
        }
    }

    public record Injected(int status, Map<String, String> headers, String body) {
    }

    /** One recorded API request (endpoint, Dropbox-API-Path-Root header, JSON body). */
    public record Request(String endpoint, String pathRoot, String body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final Map<String, Account> codes = new ConcurrentHashMap<>();
    private final Map<String, Account> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Account> refreshTokens = new ConcurrentHashMap<>();
    /** access token -> the refresh token it belongs to (revoke disables exactly that grant). */
    private final Map<String, String> accessToRefresh = new ConcurrentHashMap<>();
    public final AtomicInteger revokeCalls = new AtomicInteger();
    /** namespace id -> (path_lower -> entries). */
    private final Map<String, Map<String, List<Map<String, Object>>>> namespaces = new ConcurrentHashMap<>();
    public final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    public final List<String> tokenAuthorizationHeaders = new CopyOnWriteArrayList<>();
    public final List<Request> apiRequests = new CopyOnWriteArrayList<>();
    public final List<String> issuedTokens = new CopyOnWriteArrayList<>();
    public final ConcurrentLinkedQueue<Injected> apiFailures = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<Injected> refreshFailures = new ConcurrentLinkedQueue<>();
    public final AtomicInteger refreshGrants = new AtomicInteger();
    /** If set, get_current_account answers with this account id instead (identity mismatch tests). */
    public volatile String overrideCurrentAccountId;
    public volatile int accessTokenSeconds = 14400;

    public FakeDropbox() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/oauth2/token", this::token);
        server.createContext("/2/", this::api);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        codes.clear();
        namespaces.clear();
        tokenRequests.clear();
        tokenAuthorizationHeaders.clear();
        apiRequests.clear();
        apiFailures.clear();
        refreshFailures.clear();
        refreshGrants.set(0);
        revokeCalls.set(0);
        overrideCurrentAccountId = null;
        accessTokenSeconds = 14400;
    }

    public void prepare(String code, Account account) {
        codes.put(code, account);
    }

    public void revoke(String accountId) {
        refreshTokens.values().removeIf(a -> a.accountId().equals(accountId));
        accessTokens.values().removeIf(a -> a.accountId().equals(accountId));
    }

    public void revokeAccessTokens(String accountId) {
        accessTokens.values().removeIf(a -> a.accountId().equals(accountId));
    }

    /** Adds an entry to a folder ("" = root) of a namespace. Returns the entry for further edits. */
    public Map<String, Object> folder(String namespace, String parentPathLower, String name) {
        String path = (parentPathLower.isEmpty() ? "" : parentPathLower) + "/" + name.toLowerCase();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put(".tag", "folder");
        e.put("name", name);
        e.put("id", "id:" + idFor(path));
        e.put("path_lower", path);
        e.put("path_display", path);
        children(namespace, parentPathLower).add(e);
        children(namespace, path); // exists, possibly empty
        return e;
    }

    public Map<String, Object> file(String namespace, String parentPathLower, String name, long size) {
        String path = parentPathLower + "/" + name.toLowerCase();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put(".tag", "file");
        e.put("name", name);
        e.put("id", "id:" + idFor(path));
        e.put("path_lower", path);
        e.put("path_display", path);
        e.put("client_modified", "2026-03-01T10:00:00Z");
        e.put("server_modified", "2026-03-02T11:00:00Z");
        e.put("rev", "a1c10ce0dd78");
        e.put("size", size);
        e.put("is_downloadable", true);
        e.put("content_hash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        children(namespace, parentPathLower).add(e);
        return e;
    }

    public void deleted(String namespace, String parentPathLower, String name) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put(".tag", "deleted");
        e.put("name", name);
        e.put("path_lower", parentPathLower + "/" + name.toLowerCase());
        children(namespace, parentPathLower).add(e);
    }

    private List<Map<String, Object>> children(String namespace, String pathLower) {
        return namespaces.computeIfAbsent(namespace, n -> new ConcurrentHashMap<>())
                .computeIfAbsent(pathLower, p -> new CopyOnWriteArrayList<>());
    }

    private final AtomicInteger ids = new AtomicInteger();

    private String idFor(String path) {
        return "dbxFake" + ids.incrementAndGet();
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(form);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        tokenAuthorizationHeaders.add(auth == null ? "" : auth);
        String expected = "Basic " + Base64.getEncoder().encodeToString((APP_KEY + ":" + APP_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(auth)) {
            respond(ex, 400, Map.of("error", "invalid_client"));
            return;
        }
        if ("refresh_token".equals(form.get("grant_type"))) {
            refreshGrants.incrementAndGet();
            Injected injected = refreshFailures.poll();
            if (injected != null) {
                respondRaw(ex, injected);
                return;
            }
            Account account = refreshTokens.get(form.getOrDefault("refresh_token", ""));
            if (account == null) {
                respond(ex, 400, Map.of("error", "invalid_grant", "error_description", "refresh token is invalid or revoked"));
                return;
            }
            // Documented: the refresh grant does not return a new refresh token.
            respond(ex, 200, Map.of("access_token", newAccess(account, form.get("refresh_token")), "token_type", "bearer",
                    "expires_in", accessTokenSeconds));
            return;
        }
        Account account = codes.remove(form.getOrDefault("code", ""));
        if (account == null || !"authorization_code".equals(form.get("grant_type")) || form.get("redirect_uri") == null) {
            respond(ex, 400, Map.of("error", "invalid_grant"));
            return;
        }
        String refresh = account.issueRefresh() ? "fake-dropbox-refresh-" + UUID.randomUUID() : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", newAccess(account, refresh));
        body.put("expires_in", accessTokenSeconds);
        body.put("token_type", "bearer");
        body.put("scope", account.grantedScope());
        body.put("account_id", account.accountId());
        body.put("uid", "12345");
        if (refresh != null) {
            refreshTokens.put(refresh, account);
            issuedTokens.add(refresh);
            body.put("refresh_token", refresh);
        }
        respond(ex, 200, body);
    }

    private String newAccess(Account account, String refresh) {
        String token = "sl.fake-dropbox-access-" + UUID.randomUUID();
        accessTokens.put(token, account);
        if (refresh != null) {
            accessToRefresh.put(token, refresh);
        }
        issuedTokens.add(token);
        return token;
    }

    private void api(HttpExchange ex) throws IOException {
        String endpoint = ex.getRequestURI().getPath().substring("/2/".length());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String pathRoot = ex.getRequestHeaders().getFirst("Dropbox-API-Path-Root");
        apiRequests.add(new Request(endpoint, pathRoot, body));
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, Map.of("error_summary", "method"));
            return;
        }
        Injected injected = apiFailures.poll();
        if (injected != null) {
            respondRaw(ex, injected);
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Account account = auth == null ? null : accessTokens.get(auth.replaceFirst("^Bearer ", ""));
        if (account == null) {
            respond(ex, 401, Map.of("error_summary", "invalid_access_token/", "error", Map.of(".tag", "invalid_access_token")));
            return;
        }
        if ("auth/token/revoke".equals(endpoint)) {
            // Documented: disables this access token, its refresh token and that refresh token's other
            // access tokens. Other grants of the same user and app are untouched.
            revokeCalls.incrementAndGet();
            String access = auth.replaceFirst("^Bearer ", "");
            accessTokens.remove(access);
            String refresh = accessToRefresh.remove(access);
            if (refresh != null) {
                refreshTokens.remove(refresh);
                accessToRefresh.entrySet().removeIf(e -> {
                    boolean sibling = e.getValue().equals(refresh);
                    if (sibling) {
                        accessTokens.remove(e.getKey());
                    }
                    return sibling;
                });
            }
            respondRaw(ex, new Injected(200, Map.of(), "null"));
            return;
        }
        switch (endpoint) {
            case "users/get_current_account" -> currentAccount(ex, account);
            case "files/list_folder" -> listFolder(ex, account, pathRoot, JSON.readTree(body));
            case "files/list_folder/continue" -> continueFolder(ex, account, pathRoot, JSON.readTree(body));
            case "files/get_metadata" -> metadata(ex, account, pathRoot, JSON.readTree(body));
            default -> respond(ex, 400, Map.of("error_summary", "unknown endpoint"));
        }
    }

    private void currentAccount(HttpExchange ex, Account a) throws IOException {
        Map<String, Object> rootInfo = new LinkedHashMap<>();
        rootInfo.put(".tag", a.rootTag());
        rootInfo.put("root_namespace_id", a.rootNamespaceId());
        rootInfo.put("home_namespace_id", a.homeNamespaceId() != null ? a.homeNamespaceId() : a.rootNamespaceId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_id", overrideCurrentAccountId != null ? overrideCurrentAccountId : a.accountId());
        body.put("name", Map.of("display_name", a.displayName(), "given_name", "D", "surname", "O",
                "familiar_name", "D", "abbreviated_name", "DO"));
        body.put("email", a.email());
        body.put("email_verified", a.emailVerified());
        body.put("disabled", false);
        body.put("account_type", Map.of(".tag", "user".equals(a.rootTag()) ? "basic" : "business"));
        body.put("root_info", rootInfo);
        respond(ex, 200, body);
    }

    /** The namespace a files call operates in: the path-root header's root, else the home namespace. */
    private String namespaceFor(Account a, String pathRoot) {
        if (pathRoot == null) {
            return a.homeNamespaceId() != null ? a.homeNamespaceId() : a.rootNamespaceId();
        }
        JsonNode header = JSON.readTree(pathRoot);
        if (!"root".equals(header.path(".tag").asString("")) || !a.rootNamespaceId().equals(header.path("root").asString(""))) {
            return null;
        }
        return a.rootNamespaceId();
    }

    private void listFolder(HttpExchange ex, Account a, String pathRoot, JsonNode args) throws IOException {
        String ns = namespaceFor(a, pathRoot);
        if (ns == null) {
            respond(ex, 422, Map.of("error_summary", "invalid_root/", "error", Map.of(".tag", "invalid_root")));
            return;
        }
        String path = args.path("path").asString("");
        int limit = args.path("limit").asInt(2000);
        page(ex, ns, path, 0, limit);
    }

    private void continueFolder(HttpExchange ex, Account a, String pathRoot, JsonNode args) throws IOException {
        String cursor = args.path("cursor").asString("");
        String[] parts = cursor.split("~", 4);
        String ns = namespaceFor(a, pathRoot);
        if (parts.length != 4 || !"cur".equals(parts[0]) || ns == null || !ns.equals(parts[1])) {
            respond(ex, 409, Map.of("error_summary", "reset/", "error", Map.of(".tag", "reset")));
            return;
        }
        String[] tail = parts[3].split("~", 2);
        page(ex, ns, parts[2], Integer.parseInt(tail[0]), Integer.parseInt(tail[1]));
    }

    private void page(HttpExchange ex, String ns, String path, int offset, int limit) throws IOException {
        Map<String, List<Map<String, Object>>> tree = namespaces.get(ns);
        List<Map<String, Object>> entries = tree == null ? null : tree.get(path);
        if (entries == null && path.isEmpty()) {
            entries = List.of(); // the root always exists
        }
        if (entries == null) {
            respond(ex, 409, Map.of("error_summary", "path/not_found/", "error",
                    Map.of(".tag", "path", "path", Map.of(".tag", "not_found"))));
            return;
        }
        List<Map<String, Object>> slice = new ArrayList<>(entries.subList(Math.min(offset, entries.size()),
                Math.min(offset + limit, entries.size())));
        boolean more = offset + limit < entries.size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", slice);
        body.put("cursor", "cur~" + ns + "~" + path + "~" + (offset + limit) + "~" + limit);
        body.put("has_more", more);
        respond(ex, 200, body);
    }

    private void metadata(HttpExchange ex, Account a, String pathRoot, JsonNode args) throws IOException {
        String ns = namespaceFor(a, pathRoot);
        String id = args.path("path").asString("");
        if (ns != null && namespaces.containsKey(ns)) {
            for (List<Map<String, Object>> entries : namespaces.get(ns).values()) {
                for (Map<String, Object> e : entries) {
                    if (id.equals(e.get("id"))) {
                        respond(ex, 200, e);
                        return;
                    }
                }
            }
        }
        respond(ex, 409, Map.of("error_summary", "path/not_found/", "error",
                Map.of(".tag", "path", "path", Map.of(".tag", "not_found"))));
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
