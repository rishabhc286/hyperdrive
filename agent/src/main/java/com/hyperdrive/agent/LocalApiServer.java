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
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loopback-only, authenticated status API on the JDK's built-in HTTP server.
 *
 * <ul>
 *   <li>Bound to 127.0.0.1 on an OS-assigned port; never 0.0.0.0, never a fixed fallback.</li>
 *   <li>Every endpoint requires {@code Authorization: Bearer <launch secret>} (constant-time check).</li>
 *   <li>{@code Host} must be exactly {@code 127.0.0.1:<port>} (DNS-rebinding guard); any {@code Origin}
 *       header is rejected and no CORS headers are ever sent, so browsers can't use the API.</li>
 *   <li>GET only, no request bodies, a bounded worker pool, and server-side request time limits.</li>
 * </ul>
 *
 * The API is a control/status plane. It exposes no files, environment, paths, configuration
 * writes, proxying, shell execution or shutdown.
 */
final class LocalApiServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] expectedAuthorization;
    private final String expectedHost;
    private final AgentLog log;

    private LocalApiServer(HttpServer server, ExecutorService executor, String secret, AgentLog log) {
        this.server = server;
        this.executor = executor;
        this.expectedAuthorization = ("Bearer " + secret).getBytes(StandardCharsets.US_ASCII);
        this.expectedHost = "127.0.0.1:" + server.getAddress().getPort();
        this.log = log;
    }

    static LocalApiServer start(String secret, Supplier<Map<String, Object>> health,
            Supplier<Map<String, Object>> status, AgentLog log) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 16);
        ExecutorService executor = new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "agent-http");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        LocalApiServer api = new LocalApiServer(server, executor, secret, log);
        server.createContext("/", exchange -> api.handle(exchange, health, status));
        server.start();
        return api;
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

    private void handle(HttpExchange exchange, Supplier<Map<String, Object>> health, Supplier<Map<String, Object>> status)
            throws IOException {
        try (exchange) {
            if (!expectedHost.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                respond(exchange, 403, error("forbidden"));
                return;
            }
            if (exchange.getRequestHeaders().containsKey("Origin")) {
                // Only the Electron main process talks to the agent; browsers always send Origin on fetch.
                respond(exchange, 403, error("forbidden"));
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, error("unauthorized"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, error("method_not_allowed"));
                return;
            }
            if (hasBody(exchange)) {
                respond(exchange, 400, error("body_not_allowed"));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                respond(exchange, 400, error("query_not_allowed"));
                return;
            }
            switch (path) {
                case "/local/v1/health" -> respond(exchange, 200, health.get());
                case "/local/v1/status" -> respond(exchange, 200, status.get());
                default -> respond(exchange, 404, error("not_found"));
            }
        } catch (RuntimeException e) {
            log.warn("Local API request failed: " + e.getClass().getSimpleName());
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

    private static Map<String, Object> error(String code) {
        return Map.of("code", code);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
