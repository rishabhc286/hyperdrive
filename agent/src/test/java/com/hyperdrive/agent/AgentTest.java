package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** The agent protocol end to end, in-process (bootstrap → handshake → authenticated API → shutdown). */
class AgentTest {

    @TempDir
    Path temp;

    private static final Duration BOOT = Duration.ofSeconds(5);

    private AgentHarness started() throws Exception {
        AgentHarness h = new AgentHarness(BOOT);
        h.send(AgentHarness.bootstrapJson(h.secret, temp.resolve("agent")));
        return h;
    }

    @Test
    void handshakeReportsTheRealPortAndNoSecret() throws Exception {
        try (AgentHarness h = started()) {
            JsonNode ready = h.handshake();
            assertThat(ready.get("type").asString()).isEqualTo("ready");
            assertThat(ready.get("protocolVersion").asInt()).isEqualTo(1);
            assertThat(ready.get("port").asInt()).isBetween(1024, 65535).isEqualTo(h.agent.port());
            assertThat(ready.get("instanceId").asString()).matches("[0-9a-f-]{36}");
            assertThat(ready.get("agentVersion").asString()).isEqualTo("0.1.0-SNAPSHOT");
            assertThat(ready.propertyNames()).containsExactlyInAnyOrder("type", "protocolVersion", "port", "instanceId",
                    "agentVersion");
            assertThat(h.allOutput()).doesNotContain(h.secret);
            h.close();
            assertThat(h.exitCode()).isZero();
            assertThat(h.allOutput()).doesNotContain(h.secret);
        }
    }

    @Test
    void healthAndStatusRequireTheSecretAndReportTruthfulCapabilities() throws Exception {
        try (AgentHarness h = started()) {
            int port = h.handshake().get("port").asInt();
            String auth = "Bearer " + h.secret;

            HttpResponse<String> health = h.get(port, "/local/v1/health", auth, Map.of());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
            assertThat(health.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(health.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

            JsonNode status = AgentHarness.JSON.readTree(h.get(port, "/local/v1/status", auth, Map.of()).body());
            assertThat(status.get("readiness").asString()).isEqualTo("READY");
            assertThat(status.get("protocolVersion").asInt()).isEqualTo(1);
            assertThat(status.get("uptimeMillis").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(status.get("instanceId").asString()).isEqualTo(h.handshake().get("instanceId").asString());
            JsonNode caps = status.get("capabilities");
            assertThat(caps.propertyNames()).containsExactlyInAnyOrder("providerReads", "rangeReads", "chunkCache", "virtualDrive");
            assertThat(caps.get("providerReads").asBoolean()).isTrue();
            assertThat(caps.get("rangeReads").asBoolean()).isTrue();
            assertThat(caps.get("chunkCache").asBoolean()).isTrue();
            assertThat(caps.get("virtualDrive").asBoolean()).isFalse();
            assertThat(status.get("lastStartupError").isNull()).isTrue();
            assertThat(status.toString()).doesNotContain(h.secret, temp.toString(), "dataDir", "env", "path");
        }
    }

    @Test
    void missingOrWrongSecretIsRejected() throws Exception {
        try (AgentHarness h = started()) {
            int port = h.handshake().get("port").asInt();
            assertThat(h.get(port, "/local/v1/health", null, Map.of()).statusCode()).isEqualTo(401);
            assertThat(h.get(port, "/local/v1/status", "Bearer " + AgentHarness.newSecret(), Map.of()).statusCode())
                    .isEqualTo(401);
            assertThat(h.get(port, "/local/v1/status", "Bearer " + h.secret.substring(1), Map.of()).statusCode())
                    .isEqualTo(401);
            assertThat(h.get(port, "/local/v1/status", h.secret, Map.of()).statusCode()).isEqualTo(401);
            assertThat(h.get(port, "/local/v1/status", null, Map.of()).body()).doesNotContain("READY");
        }
    }

    @Test
    void browserStyleRequestsAndWrongHostAreRejected() throws Exception {
        try (AgentHarness h = started()) {
            int port = h.handshake().get("port").asInt();
            String auth = "Bearer " + h.secret;
            assertThat(h.get(port, "/local/v1/status", auth, Map.of("Origin", "https://evil.example")).statusCode())
                    .isEqualTo(403);
            // DNS rebinding: a request addressed to another host name is refused even with the secret.
            assertThat(rawStatus(port, "localhost:" + port, auth)).isEqualTo(403);
            assertThat(rawStatus(port, "evil.example", auth)).isEqualTo(403);
            assertThat(rawStatus(port, "127.0.0.1:" + port, auth)).isEqualTo(200);
        }
    }

    @Test
    void onlyGetOnKnownPathsWithoutBodiesOrQueries() throws Exception {
        try (AgentHarness h = started()) {
            int port = h.handshake().get("port").asInt();
            String auth = "Bearer " + h.secret;
            assertThat(h.request(port, "POST", "/local/v1/status", auth).statusCode()).isEqualTo(405);
            assertThat(h.request(port, "DELETE", "/local/v1/status", auth).statusCode()).isEqualTo(405);
            assertThat(h.get(port, "/local/v1/shutdown", auth, Map.of()).statusCode()).isEqualTo(404);
            assertThat(h.get(port, "/local/v1/files", auth, Map.of()).statusCode()).isEqualTo(404);
            assertThat(h.get(port, "/local/v1/status?x=1", auth, Map.of()).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void shutdownMessageStopsGracefully() throws Exception {
        try (AgentHarness h = started()) {
            h.handshake();
            h.send("{\"type\":\"unknown\"}");
            h.send("{\"type\":\"shutdown\"}");
            assertThat(h.exitCode()).isZero();
            assertThat(h.agent.isStopped()).isTrue();
            assertThat(h.stderr.toString(StandardCharsets.UTF_8)).contains("shutdown_requested", "Ignored an unknown control message");
        }
    }

    @Test
    void closedParentChannelStopsTheAgent() throws Exception {
        try (AgentHarness h = started()) {
            h.handshake();
            h.close(); // EOF: the owning desktop process went away
            assertThat(h.exitCode()).isZero();
            assertThat(h.stderr.toString(StandardCharsets.UTF_8)).contains("parent_closed");
        }
    }

    @Test
    void secondAgentOnTheSameDataDirectoryFailsWithLockedAndFirstKeepsRunning() throws Exception {
        try (AgentHarness first = started(); AgentHarness second = new AgentHarness(BOOT)) {
            first.handshake();
            second.send(AgentHarness.bootstrapJson(second.secret, temp.resolve("agent")));
            JsonNode error = second.handshake();
            assertThat(error.get("type").asString()).isEqualTo("error");
            assertThat(error.get("code").asString()).isEqualTo("data_dir_locked");
            assertThat(second.exitCode()).isEqualTo(Agent.EXIT_DATA_DIR);

            first.close();
            assertThat(first.exitCode()).isZero();
            try (AgentHarness third = new AgentHarness(BOOT)) {
                third.send(AgentHarness.bootstrapJson(third.secret, temp.resolve("agent")));
                assertThat(third.handshake().get("type").asString()).isEqualTo("ready");
            }
        }
    }

    @Test
    void serverBindsToLoopbackOnlyOnAnAssignedPort() throws Exception {
        AgentLog log = new AgentLog(System.err, java.time.Clock.systemUTC());
        LocalApiServer a = LocalApiServer.start(AgentHarness.newSecret(), Map::of, Map::of, log);
        LocalApiServer b = LocalApiServer.start(AgentHarness.newSecret(), Map::of, Map::of, log);
        try {
            assertThat(a.address().getHostAddress()).isEqualTo("127.0.0.1");
            assertThat(a.address().isAnyLocalAddress()).isFalse();
            assertThat(a.port()).isPositive().isNotEqualTo(b.port());
            // Not reachable on the machine's other addresses.
            InetAddress lan = InetAddress.getLocalHost();
            if (!lan.isLoopbackAddress()) {
                try (Socket s = new Socket()) {
                    s.connect(new java.net.InetSocketAddress(lan, a.port()), 500);
                    throw new AssertionError("Agent API reachable on a non-loopback address");
                } catch (IOException expected) {
                    // refused or timed out: good
                }
            }
        } finally {
            a.stop();
            b.stop();
        }
    }

    /** A hand-written request so the Host header can be chosen freely. */
    private static int rawStatus(int port, String host, String authorization) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /local/v1/health HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: " + authorization
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
