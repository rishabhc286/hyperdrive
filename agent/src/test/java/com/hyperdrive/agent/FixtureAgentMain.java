package com.hyperdrive.agent;

import com.hyperdrive.agent.read.ProviderEndpoints;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * <b>TEST-ONLY</b> entry point (test sources; never packaged in the agent jar): the real {@link Agent}
 * with its provider endpoints pointed at a local, deterministic <b>fixture provider</b>. Used by the
 * opt-in Windows end-to-end check {@code vfs/tests/content-mount-check.mjs}. Nothing here talks to
 * Google or Dropbox; results from it are fixture evidence, never live-provider evidence.
 *
 * <pre>java -cp hyperdrive-agent.jar;test-classes com.hyperdrive.agent.FixtureAgentMain spec.json</pre>
 *
 * The spec lists files {@code {id, provider, size, seed, rev, delayMs}}, the two accepted tokens and
 * the Dropbox root namespace. Content is generated per byte ({@link #gen}); no file is ever held in
 * memory, so sizes beyond 4 GiB cost nothing. Ranges follow the documented provider behavior the agent
 * relies on: 206 + {@code Content-Range: bytes a-b/total}; Dropbox {@code rev:} paths and
 * {@code Dropbox-API-Result}.
 */
public final class FixtureAgentMain {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d+)-(\\d+)$");

    /**
     * Test-only network switch, set through {@code GET /fixture/network?state=up|down} on the fixture
     * provider (loopback): while down, every content response is cut off after its headers and a few
     * bytes, like a dropped connection.
     */
    private static volatile boolean networkDown;

    /** {@code source}: optional local file whose bytes are served instead of generated ones (app tests). */
    record FixtureFile(String id, String provider, long size, long seed, String rev, long delayMs, String source) {
    }

    private FixtureAgentMain() {
    }

    /** The fixture byte at position {@code p} (mirrored exactly by the node check). */
    static int gen(long seed, long p) {
        long lo = p & 0xffffffffL;
        long hi = p >>> 32;
        return (int) ((lo * 7 + hi * 13 + (lo >>> 11) + seed) & 0xff);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: FixtureAgentMain <spec.json>");
            System.exit(2);
        }
        JsonNode spec = JSON.readTree(Files.readString(Path.of(args[0])));
        Map<String, FixtureFile> files = new HashMap<>();
        for (JsonNode f : spec.get("files")) {
            files.put(f.get("id").asString(), new FixtureFile(f.get("id").asString(), f.get("provider").asString(),
                    f.get("size").asLong(), f.get("seed").asLong(), f.path("rev").asString(null), f.path("delayMs").asLong(0),
                    f.path("source").asString(null)));
        }
        String googleToken = spec.get("googleToken").asString();
        String dropboxToken = spec.get("dropboxToken").asString();
        String namespace = spec.get("dropboxRootNamespace").asString();
        System.err.println("[fixture] TEST-ONLY fixture provider: generated content, no Google/Dropbox access");

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 64);
        server.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "fixture-provider");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/drive/v3/files/", ex -> {
            String id = URLDecoder.decode(ex.getRequestURI().getRawPath().substring("/drive/v3/files/".length()),
                    StandardCharsets.UTF_8);
            FixtureFile f = files.get(id);
            if (!("Bearer " + googleToken).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                error(ex, 401, "{\"error\":{\"code\":401,\"errors\":[{\"reason\":\"authError\"}]}}");
            } else if (f == null || !"GOOGLE_DRIVE".equals(f.provider())) {
                error(ex, 404, "{\"error\":{\"code\":404,\"errors\":[{\"reason\":\"notFound\"}]}}");
            } else {
                serve(ex, f, Map.of());
            }
        });
        server.createContext("/2/files/download", ex -> {
            if (!("Bearer " + dropboxToken).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                error(ex, 401, "{\"error_summary\":\"invalid_access_token/\",\"error\":{\".tag\":\"invalid_access_token\"}}");
                return;
            }
            JsonNode root = JSON.readTree(String.valueOf(ex.getRequestHeaders().getFirst("Dropbox-API-Path-Root")));
            if (!namespace.equals(root.path("root").asString(""))) {
                error(ex, 422, "{\"error_summary\":\"invalid_root/\"}");
                return;
            }
            String path = JSON.readTree(String.valueOf(ex.getRequestHeaders().getFirst("Dropbox-API-Arg"))).path("path")
                    .asString("");
            FixtureFile f = path.startsWith("rev:") ? files.values().stream()
                    .filter(x -> "DROPBOX".equals(x.provider()) && path.substring(4).equals(x.rev())).findFirst().orElse(null)
                    : null; // the agent always pins rev: for mounted Dropbox reads
            if (f == null) {
                error(ex, 409, "{\"error_summary\":\"path/not_found/\",\"error\":{\".tag\":\"path\",\"path\":{\".tag\":\"not_found\"}}}");
                return;
            }
            serve(ex, f, Map.of("Dropbox-API-Result", JSON.writeValueAsString(Map.of("id", f.id(), "rev", f.rev(),
                    "size", f.size(), "name", "fixture"))));
        });
        server.createContext("/fixture/network", ex -> {
            String q = String.valueOf(ex.getRequestURI().getQuery());
            networkDown = q.equals("state=down");
            byte[] ok = ("{\"networkDown\":" + networkDown + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(ok);
            }
        });
        server.start();
        System.err.println("[fixture] provider control port " + server.getAddress().getPort());
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Agent agent = new Agent(System.in, System.out, System.err, Clock.systemUTC(), Duration.ofSeconds(10),
                new ProviderEndpoints(base.resolve("/drive/v3"), base.resolve("/2")), d -> Thread.sleep(d.toMillis()),
                Duration.ofSeconds(30));
        int code = agent.run();
        server.stop(0);
        System.exit(code);
    }

    private static void serve(HttpExchange ex, FixtureFile f, Map<String, String> extra) throws IOException {
        try (ex) {
            if (f.delayMs() > 0) {
                try {
                    Thread.sleep(f.delayMs()); // the "slow file" for outstanding-read tests
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Matcher m = RANGE.matcher(String.valueOf(ex.getRequestHeaders().getFirst("Range")));
            if (!m.matches()) {
                error(ex, 400, "{\"error\":\"fixture requires a Range\"}");
                return;
            }
            long start = Long.parseLong(m.group(1));
            long end = Math.min(Long.parseLong(m.group(2)), f.size() - 1);
            if (start >= f.size() || end < start) {
                ex.getResponseHeaders().add("Content-Range", "bytes */" + f.size());
                ex.sendResponseHeaders(416, -1);
                return;
            }
            extra.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + f.size());
            long length = end - start + 1;
            ex.sendResponseHeaders(206, length);
            byte[] buffer = new byte[64 * 1024];
            java.io.RandomAccessFile source = f.source() == null ? null : new java.io.RandomAccessFile(f.source(), "r");
            try (source; OutputStream out = ex.getResponseBody()) {
                long p = start;
                while (p <= end) {
                    int n = (int) Math.min(buffer.length, end - p + 1);
                    if (source != null) {
                        source.seek(p);
                        source.readFully(buffer, 0, n);
                    } else {
                        for (int i = 0; i < n; i++) {
                            buffer[i] = (byte) gen(f.seed(), p + i);
                        }
                    }
                    if (networkDown) {
                        out.write(buffer, 0, Math.min(n, 100));
                        out.flush();
                        throw new IOException("fixture: simulated network drop"); // closes the connection
                    }
                    out.write(buffer, 0, n);
                    p += n;
                }
            }
        }
    }

    private static void error(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        ex.close();
    }
}
