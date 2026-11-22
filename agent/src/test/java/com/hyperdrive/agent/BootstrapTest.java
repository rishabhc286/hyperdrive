package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Bootstrap parsing and the agent's fail-closed behavior for bad launch input. */
class BootstrapTest {

    @TempDir
    Path temp;

    private String dir() {
        return AgentHarness.JSON.writeValueAsString(temp.resolve("agent").toString());
    }

    @Test
    void parsesAValidBootstrapAndRedactsTheSecret() throws Exception {
        String secret = AgentHarness.newSecret();
        Bootstrap b = Bootstrap.parse(AgentHarness.bootstrapJson(secret, temp.resolve("agent")));
        assertThat(b.secret()).isEqualTo(secret);
        assertThat(b.dataDir()).isEqualTo(temp.resolve("agent"));
        assertThat(b.logLevel()).isEqualTo(Bootstrap.LogLevel.DEBUG);
        assertThat(b.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(b.toString()).doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "[]",
        "{}",
        "{\"protocolVersion\":1,\"dataDir\":DIR}",
        "{\"protocolVersion\":1,\"secret\":\"short\",\"dataDir\":DIR}",
        "{\"protocolVersion\":1,\"secret\":\"SECRET\",\"dataDir\":\"relative/dir\"}",
        "{\"protocolVersion\":1,\"secret\":\"SECRET\",\"dataDir\":DIR,\"extra\":true}",
        "{\"protocolVersion\":1,\"secret\":\"SECRET\",\"dataDir\":DIR,\"logLevel\":\"TRACE\"}",
        "{\"protocolVersion\":1,\"secret\":\"SECRET\",\"dataDir\":DIR,\"requestTimeoutMs\":5}",
        "{\"protocolVersion\":\"1\",\"secret\":\"SECRET\",\"dataDir\":DIR}"
    })
    void malformedBootstrapIsRejected(String template) {
        String line = template.replace("DIR", dir()).replace("SECRET", AgentHarness.newSecret());
        assertThatThrownBy(() -> Bootstrap.parse(line))
                .isInstanceOfSatisfying(Bootstrap.BootstrapException.class,
                        e -> assertThat(e.code()).isEqualTo("bootstrap_invalid"));
    }

    @Test
    void unsupportedProtocolVersionIsRejectedDistinctly() {
        String line = "{\"protocolVersion\":2,\"secret\":\"" + AgentHarness.newSecret() + "\",\"dataDir\":" + dir() + "}";
        assertThatThrownBy(() -> Bootstrap.parse(line))
                .isInstanceOfSatisfying(Bootstrap.BootstrapException.class,
                        e -> assertThat(e.code()).isEqualTo("protocol_unsupported"));
    }

    @Test
    void agentFailsClosedOnMalformedInputWithoutEchoingIt() throws Exception {
        String secretLike = AgentHarness.newSecret();
        try (AgentHarness h = new AgentHarness(Duration.ofSeconds(5))) {
            h.send("{\"protocolVersion\":1,\"secret\":\"" + secretLike + "\",\"dataDir\":\"relative\"}");
            JsonNode error = h.handshake();
            assertThat(error.get("type").asString()).isEqualTo("error");
            assertThat(error.get("code").asString()).isEqualTo("bootstrap_invalid");
            assertThat(h.exitCode()).isEqualTo(Agent.EXIT_BOOTSTRAP);
            assertThat(h.allOutput()).doesNotContain(secretLike, "relative");
        }
    }

    @Test
    void oversizedBootstrapIsRejectedWithoutUnboundedBuffering() throws Exception {
        try (AgentHarness h = new AgentHarness(Duration.ofSeconds(5))) {
            h.stdinWriter.write("x".repeat(Bootstrap.MAX_BYTES + 100).getBytes(StandardCharsets.UTF_8));
            h.stdinWriter.flush();
            assertThat(h.handshake().get("code").asString()).isEqualTo("bootstrap_invalid");
            assertThat(h.exitCode()).isEqualTo(Agent.EXIT_BOOTSTRAP);
            assertThat(h.stderr.toString(StandardCharsets.UTF_8)).contains("too large");
        }
    }

    @Test
    void missingBootstrapTimesOut() throws Exception {
        try (AgentHarness h = new AgentHarness(Duration.ofMillis(300))) {
            assertThat(h.handshake().get("code").asString()).isEqualTo("bootstrap_timeout");
            assertThat(h.exitCode()).isEqualTo(Agent.EXIT_BOOTSTRAP);
        }
    }

    @Test
    void closedStdinBeforeBootstrapIsRejected() throws Exception {
        try (AgentHarness h = new AgentHarness(Duration.ofSeconds(5))) {
            h.close();
            assertThat(h.handshake().get("code").asString()).isEqualTo("bootstrap_missing");
            assertThat(h.exitCode()).isEqualTo(Agent.EXIT_BOOTSTRAP);
        }
    }

    @Test
    void boundedLineReaderStopsAtTheLimit() throws Exception {
        BoundedLineReader reader = new BoundedLineReader(new ByteArrayInputStream("abc\r\ndefghij\n".getBytes(StandardCharsets.UTF_8)), 5);
        assertThat(reader.readLine()).isEqualTo("abc");
        assertThatThrownBy(reader::readLine).isInstanceOf(BoundedLineReader.LineTooLongException.class);
        assertThat(new BoundedLineReader(new ByteArrayInputStream(new byte[0]), 5).readLine()).isNull();
    }
}
