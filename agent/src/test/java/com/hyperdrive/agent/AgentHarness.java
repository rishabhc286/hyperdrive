package com.hyperdrive.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs an {@link Agent} in-process with piped stdin and captured stdout/stderr. */
final class AgentHarness implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    final String secret = newSecret();
    final PipedOutputStream stdinWriter = new PipedOutputStream();
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    final Agent agent;
    final CompletableFuture<Integer> exit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    AgentHarness(Duration bootstrapTimeout) throws IOException {
        PipedInputStream stdin = new PipedInputStream(stdinWriter, 64 * 1024);
        agent = new Agent(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), Clock.systemUTC(), bootstrapTimeout);
        exit = CompletableFuture.supplyAsync(agent::run);
    }

    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String bootstrapJson(String secret, Path dataDir) {
        return JSON.writeValueAsString(Map.of("protocolVersion", 1, "secret", secret, "dataDir", dataDir.toString(),
                "logLevel", "DEBUG", "requestTimeoutMs", 5000));
    }

    void send(String line) throws IOException {
        stdinWriter.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdinWriter.flush();
    }

    /** Waits for the first stdout line (the handshake). */
    JsonNode handshake() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String out = stdout.toString(StandardCharsets.UTF_8);
            int nl = out.indexOf('\n');
            if (nl >= 0) {
                return JSON.readTree(out.substring(0, nl));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No handshake. stderr: " + stderr.toString(StandardCharsets.UTF_8));
    }

    int exitCode() throws Exception {
        return exit.get(10, TimeUnit.SECONDS);
    }

    HttpResponse<String> get(int port, String path, String authorization, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> request(int port, String method, String path, String authorization) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", authorization)
                .method(method, HttpRequest.BodyPublishers.ofString(method.equals("GET") ? "" : "x")).build();
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    String allOutput() {
        return stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        try {
            stdinWriter.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
