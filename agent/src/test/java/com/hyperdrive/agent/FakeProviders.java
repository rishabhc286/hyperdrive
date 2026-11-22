package com.hyperdrive.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local stand-ins for Google Drive {@code files/{id}?alt=media} and Dropbox {@code files/download},
 * honoring {@code Range} like the documented APIs, with per-file misbehavior switches. A second
 * server ("elsewhere") records anything a redirect would send to another origin.
 */
final class FakeProviders implements AutoCloseable {

    enum Mode {
        NORMAL, IGNORE_RANGE, WRONG_RANGE, LONGER_BODY, TRUNCATED, REDIRECT, SLOW, STATUS, GZIP
    }

    /** A recorded provider request. */
    record Seen(String method, String path, String range, String authorization, String acceptEncoding, String dropboxArg,
            String pathRoot) {
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d+)-(\\d+)$");

    final HttpServer server;
    final HttpServer elsewhere;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, Mode> modes = new ConcurrentHashMap<>();
    final Map<String, String> dropboxRevs = new ConcurrentHashMap<>();
    /** For Mode.STATUS: status, then body, then optional Retry-After; consumed one per request. */
    final List<Object[]> statusQueue = new CopyOnWriteArrayList<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final List<String> elsewhereAuthorization = new CopyOnWriteArrayList<>();
    volatile long slowMillis = 3000;

    FakeProviders() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/drive/v3/files/", this::google);
        server.createContext("/2/files/download", this::dropbox);
        server.start();
        elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/", ex -> {
            elsewhereAuthorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            send(ex, 200, "stolen".getBytes(StandardCharsets.UTF_8), Map.of());
        });
        elsewhere.start();
    }

    URI googleBase() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/drive/v3");
    }

    URI dropboxBase() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
    }

    private void google(HttpExchange ex) throws IOException {
        String id = URLDecoder.decode(ex.getRequestURI().getRawPath().substring("/drive/v3/files/".length()), StandardCharsets.UTF_8);
        record(ex, null);
        if (!ex.getRequestHeaders().getFirst("Authorization").equals("Bearer " + GOOGLE_TOKEN)) {
            send(ex, 401, "{\"error\":{\"code\":401,\"errors\":[{\"reason\":\"authError\"}]}}".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        serve(ex, id, Map.of());
    }

    private void dropbox(HttpExchange ex) throws IOException {
        String arg = ex.getRequestHeaders().getFirst("Dropbox-API-Arg");
        record(ex, arg);
        if (!"POST".equals(ex.getRequestMethod()) || !("Bearer " + DROPBOX_TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "{\"error_summary\":\"invalid_access_token/\",\"error\":{\".tag\":\"invalid_access_token\"}}"
                    .getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        JsonNode root = JSON.readTree(String.valueOf(ex.getRequestHeaders().getFirst("Dropbox-API-Path-Root")));
        if (!"root".equals(root.path(".tag").asString("")) || !"777".equals(root.path("root").asString(""))) {
            send(ex, 422, "{\"error_summary\":\"invalid_root/\"}".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        String path = JSON.readTree(arg).path("path").asString("");
        String id = path;
        if (path.startsWith("rev:")) {
            String rev = path.substring(4);
            id = dropboxRevs.entrySet().stream().filter(e -> e.getValue().equals(rev)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (id == null) {
                send(ex, 409, "{\"error_summary\":\"path/not_found/\",\"error\":{\".tag\":\"path\",\"path\":{\".tag\":\"not_found\"}}}"
                        .getBytes(StandardCharsets.UTF_8), Map.of());
                return;
            }
        }
        byte[] content = files.get(id);
        Map<String, String> extra = content == null ? Map.of()
                : Map.of("Dropbox-API-Result", JSON.writeValueAsString(Map.of("rev", dropboxRevs.getOrDefault(id, "a1c10ce0dd78"),
                        "size", content.length, "id", id, "name", "f")));
        serve(ex, id, extra);
    }

    private void serve(HttpExchange ex, String id, Map<String, String> extra) throws IOException {
        Mode mode = modes.getOrDefault(id, Mode.NORMAL);
        if (mode == Mode.STATUS && !statusQueue.isEmpty()) {
            Object[] next = statusQueue.remove(0);
            Map<String, String> headers = next.length > 2 && next[2] != null ? Map.of("Retry-After", (String) next[2]) : Map.of();
            send(ex, (Integer) next[0], ((String) next[1]).getBytes(StandardCharsets.UTF_8), headers);
            return;
        }
        byte[] content = files.get(id);
        if (content == null) {
            boolean dropbox = ex.getRequestURI().getPath().startsWith("/2/");
            send(ex, dropbox ? 409 : 404, (dropbox
                    ? "{\"error_summary\":\"path/not_found/\",\"error\":{\".tag\":\"path\",\"path\":{\".tag\":\"not_found\"}}}"
                    : "{\"error\":{\"code\":404,\"errors\":[{\"reason\":\"notFound\"}]}}").getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        if (mode == Mode.REDIRECT) {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/steal");
            send(ex, 302, new byte[0], Map.of());
            return;
        }
        if (mode == Mode.SLOW) {
            try {
                Thread.sleep(slowMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mode == Mode.IGNORE_RANGE) {
            send(ex, 200, content, extra);
            return;
        }
        Matcher m = RANGE.matcher(String.valueOf(ex.getRequestHeaders().getFirst("Range")));
        if (!m.matches()) {
            send(ex, 200, content, extra);
            return;
        }
        long start = Long.parseLong(m.group(1));
        long end = Math.min(Long.parseLong(m.group(2)), content.length - 1L);
        if (start >= content.length) {
            ex.getResponseHeaders().add("Content-Range", "bytes */" + content.length);
            send(ex, 416, new byte[0], Map.of());
            return;
        }
        byte[] slice = java.util.Arrays.copyOfRange(content, (int) start, (int) end + 1);
        if (mode == Mode.GZIP) {
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        long shownStart = mode == Mode.WRONG_RANGE ? start + 1 : start;
        ex.getResponseHeaders().add("Content-Range", "bytes " + shownStart + "-" + end + "/" + content.length);
        if (mode == Mode.LONGER_BODY) {
            byte[] longer = java.util.Arrays.copyOf(slice, slice.length + 10);
            send(ex, 206, longer, extra);
            return;
        }
        if (mode == Mode.TRUNCATED) {
            extra.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(206, 0); // chunked
            try (OutputStream out = ex.getResponseBody()) {
                out.write(slice, 0, Math.max(0, slice.length / 2));
            }
            return;
        }
        send(ex, 206, slice, extra);
    }

    private void record(HttpExchange ex, String dropboxArg) {
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("Range"),
                ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Accept-Encoding"), dropboxArg,
                ex.getRequestHeaders().getFirst("Dropbox-API-Path-Root")));
    }

    private static void send(HttpExchange ex, int status, byte[] body, Map<String, String> headers) throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    static final String GOOGLE_TOKEN = "ya29.agent-test-google-token-0123456789";
    static final String DROPBOX_TOKEN = "sl.agent-test-dropbox-token-0123456789";

    @Override
    public void close() {
        server.stop(0);
        elsewhere.stop(0);
    }
}
