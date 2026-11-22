package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Starts the agent as a real, separate JVM process (same classpath as the tests), the way the
 * desktop does: secret over stdin only, handshake on stdout, logs on stderr, exit when stdin closes.
 */
class AgentProcessTest {

    @TempDir
    Path temp;

    private Process launch() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                AgentMain.class.getName()));
        pb.redirectError(temp.resolve("stderr.log").toFile());
        return pb.start();
    }

    @Test
    void realProcessStartsServesAndExitsWhenTheParentChannelCloses() throws Exception {
        Process process = launch();
        String secret = AgentHarness.newSecret();
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((AgentHarness.bootstrapJson(secret, temp.resolve("agent")) + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            JsonNode ready = AgentHarness.JSON.readTree(stdout.readLine());
            assertThat(ready.get("type").asString()).isEqualTo("ready");
            int port = ready.get("port").asInt();

            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> status = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/local/v1/status"))
                    .header("Authorization", "Bearer " + secret).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("\"readiness\":\"READY\"");

            // The command line never contains the secret.
            assertThat(process.info().arguments().map(List::of).orElse(List.of())).noneMatch(a -> a.contains(secret));

            stdin.close(); // parent-lifetime channel closes
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(stdout.readLine()).as("stdout carries only the handshake").isNull();
            String log = java.nio.file.Files.readString(temp.resolve("stderr.log"));
            assertThat(log).contains("Agent ready", "parent_closed").doesNotContain(secret);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void argumentsAreRefused() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                AgentMain.class.getName(), "--secret=abc")).redirectErrorStream(true).start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(Agent.EXIT_BOOTSTRAP);
    }
}
