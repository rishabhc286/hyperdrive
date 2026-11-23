package com.hyperdrive.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loopback-only, authenticated local API on the JDK's built-in HTTP server.
 *
 * <ul>
 *   <li>Bound to 127.0.0.1 on an OS-assigned port; never 0.0.0.0, never a fixed fallback.</li>
 *   <li>Every endpoint requires {@code Authorization: Bearer <launch secret>} (constant-time check).</li>
 *   <li>{@code Host} must be exactly {@code 127.0.0.1:<port>} (DNS-rebinding guard); any {@code Origin}
 *       header is rejected and no CORS headers are ever sent, so browsers can't use the API.</li>
 *   <li>Only the registered routes exist. GET routes take no body or query; POST routes take a JSON
 *       body of at most {@link #MAX_JSON_BODY} bytes with {@code Content-Type: application/json}.</li>
 * </ul>
 *
 * It exposes no files, environment, paths, configuration writes, proxying, shell execution or
 * shutdown. File bytes are returned raw (application/octet-stream), never base64 in JSON.
 */
final class LocalApiServer {

    static final int MAX_JSON_BODY = 16 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A route's answer: JSON, or raw bytes with extra headers. */
    sealed interface Response {
        int status();

        record Json(int status, Object body) implements Response {
        }

        record Binary(int status, byte[] data, int length, Map<String, String> headers) implements Response {
        }
    }

    @FunctionalInterface
    interface GetRoute {
        Response handle();
    }

    @FunctionalInterface
    interface PostRoute {
        Response handle(JsonNode body);
    }

    /**
     * Batch 8: a route authorized ONLY by a virtual-drive mount capability (never by the launch
     * secret, which in turn never authorizes these routes).
     */
    @FunctionalInterface
    interface CapabilityRoute {
        Response handle(com.hyperdrive.agent.vfs.MountRegistry.Mount mount, JsonNode body);
    }

    /** Resolves an Authorization header to a live mount, or null. */
    @FunctionalInterface
    interface CapabilityAuthenticator {
        com.hyperdrive.agent.vfs.MountRegistry.Mount authenticate(String authorizationHeader);
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] expectedAuthorization;
    private final String expectedHost;
    private final AgentLog log;
    private final Map<String, GetRoute> gets;
    private final Map<String, PostRoute> posts;
    private final Map<String, CapabilityRoute> capabilityPosts;
    private final CapabilityAuthenticator capabilities;

    private LocalApiServer(HttpServer server, ExecutorService executor, String secret, AgentLog log,
            Map<String, GetRoute> gets, Map<String, PostRoute> posts, Map<String, CapabilityRoute> capabilityPosts,
            CapabilityAuthenticator capabilities) {
        this.server = server;
        this.executor = executor;
        this.expectedAuthorization = ("Bearer " + secret).getBytes(StandardCharsets.US_ASCII);
        this.expectedHost = "127.0.0.1:" + server.getAddress().getPort();
        this.log = log;
        this.gets = Map.copyOf(gets);
        this.posts = Map.copyOf(posts);
        this.capabilityPosts = Map.copyOf(capabilityPosts);
        this.capabilities = capabilities;
    }

    static LocalApiServer start(String secret, Map<String, GetRoute> gets, Map<String, PostRoute> posts, AgentLog log)
            throws IOException {
        return start(secret, gets, posts, Map.of(), header -> null, log);
    }

    static LocalApiServer start(String secret, Map<String, GetRoute> gets, Map<String, PostRoute> posts,
            Map<String, CapabilityRoute> capabilityPosts, CapabilityAuthenticator capabilities, AgentLog log)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 32);
        // Reads block a worker while the provider answers. A ThreadPoolExecutor only grows past its core
        // size when the queue is full, so the core size IS the concurrency: 12 workers (8 reads max plus
        // headroom for status and credential calls), idle ones time out.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "agent-http");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        server.setExecutor(executor);
        LocalApiServer api = new LocalApiServer(server, executor, secret, log, gets, posts, capabilityPosts,
                capabilities);
        server.createContext("/", api::handle);
        server.start();
        return api;
    }

    /** Status-only convenience (tests and the minimal API). */
    static LocalApiServer start(String secret, java.util.function.Supplier<Map<String, Object>> health,
            java.util.function.Supplier<Map<String, Object>> status, AgentLog log) throws IOException {
        return start(secret, Map.of(
                "/local/v1/health", () -> new Response.Json(200, health.get()),
                "/local/v1/status", () -> new Response.Json(200, status.get())), Map.of(), log);
    }

    int port() {
        return server.getAddress().getPort();
    }

    InetAddress address() {
        return server.getAddress().getAddress();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!expectedHost.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                respond(exchange, new Response.Json(403, error("forbidden")));
                return;
            }
            if (exchange.getRequestHeaders().containsKey("Origin")) {
                // Only the Electron main process talks to the agent; browsers always send Origin on fetch.
                respond(exchange, new Response.Json(403, error("forbidden")));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            CapabilityRoute capabilityRoute = capabilityPosts.get(path);
            if (capabilityRoute != null) {
                handleCapability(exchange, capabilityRoute);
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, new Response.Json(401, error("unauthorized")));
                return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                respond(exchange, new Response.Json(400, error("query_not_allowed")));
                return;
            }
            String method = exchange.getRequestMethod();
            GetRoute get = gets.get(path);
            PostRoute post = posts.get(path);
            if (get == null && post == null) {
                respond(exchange, new Response.Json(404, error("not_found")));
                return;
            }
            if ("GET".equals(method) && get != null) {
                if (hasBody(exchange)) {
                    respond(exchange, new Response.Json(400, error("body_not_allowed")));
                    return;
                }
                respond(exchange, get.handle());
                return;
            }
            if ("POST".equals(method) && post != null) {
                JsonNode body = readJson(exchange);
                if (body == null) {
                    respond(exchange, new Response.Json(400, error("invalid_request")));
                    return;
                }
                respond(exchange, post.handle(body));
                return;
            }
            respond(exchange, new Response.Json(405, error("method_not_allowed")));
        } catch (RuntimeException e) {
            log.warn("Local API request failed: " + e.getClass().getSimpleName());
        }
    }

    /** Mount-capability routes: POST with a bounded JSON body, no query; the launch secret is not accepted. */
    private void handleCapability(HttpExchange exchange, CapabilityRoute route) throws IOException {
        com.hyperdrive.agent.vfs.MountRegistry.Mount mount =
                capabilities.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
        if (mount == null) {
            respond(exchange, new Response.Json(401, error("unauthorized")));
            return;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            respond(exchange, new Response.Json(400, error("query_not_allowed")));
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, new Response.Json(405, error("method_not_allowed")));
            return;
        }
        JsonNode body = readJson(exchange);
        if (body == null) {
            respond(exchange, new Response.Json(400, error("invalid_request")));
            return;
        }
        respond(exchange, route.handle(mount, body));
    }

    /** Bounded JSON body: correct content type, declared length within the limit, no chunking. */
    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")
                || exchange.getRequestHeaders().containsKey("Transfer-Encoding") || length == null
                || !length.trim().matches("\\d{1,6}") || Integer.parseInt(length.trim()) > MAX_JSON_BODY) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_JSON_BODY + 1);
        }
        if (bytes.length > MAX_JSON_BODY) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private boolean authorized(String header) {
        if (header == null) {
            return false;
        }
        // Constant time for equal lengths; MessageDigest.isEqual also handles differing lengths safely.
        return MessageDigest.isEqual(expectedAuthorization, header.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean hasBody(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")) {
            return true;
        }
        if (length != null && !"0".equals(length.trim())) {
            return true;
        }
        try (InputStream body = exchange.getRequestBody()) {
            return body.read() != -1;
        }
    }

    static Map<String, Object> error(String code) {
        return Map.of("code", code);
    }

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        switch (response) {
            case Response.Json json -> {
                byte[] bytes = JSON.writeValueAsBytes(json.body());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(json.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            case Response.Binary binary -> {
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                binary.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
                exchange.sendResponseHeaders(binary.status(), binary.length() == 0 ? -1 : binary.length());
                if (binary.length() > 0) {
                    exchange.getResponseBody().write(binary.data(), 0, binary.length());
                }
            }
        }
    }
}
