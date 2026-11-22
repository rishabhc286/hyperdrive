package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.agent.read.ProviderEndpoints;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** The cache through the real agent HTTP API, against local mock providers (not live Dropbox/Google). */
class CacheApiTest {

    @TempDir
    Path temp;

    private FakeProviders providers;
    private AgentHarness agent;
    private int port;
    private final UUID user = UUID.randomUUID();
    private final UUID boxAccount = UUID.randomUUID();
    private final String key = randomKey();
    private static final int C = 4 * 1024 * 1024;

    static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @BeforeEach
    void start() throws Exception {
        providers = new FakeProviders();
        agent = new AgentHarness(Duration.ofSeconds(5), new ProviderEndpoints(providers.googleBase(), providers.dropboxBase()),
                Duration.ofSeconds(5));
        agent.send(AgentHarness.bootstrapJson(agent.secret, temp.resolve("agent")));
        port = agent.handshake().get("port").asInt();
    }

    @AfterEach
    void stop() throws Exception {
        agent.close();
        agent.exitCode();
        providers.close();
    }

    private JsonNode json(HttpResponse<byte[]> r) {
        return AgentHarness.JSON.readTree(r.body());
    }

    private static JsonNode jsonText(HttpResponse<String> r) {
        return AgentHarness.JSON.readTree(r.body());
    }

    private HttpResponse<byte[]> session(String withKey, boolean fresh) throws Exception {
        return agent.post(port, "/local/v1/cache/session",
                Map.of("userId", user.toString(), "key", withKey, "fresh", fresh, "budgetBytes", 5L * 1024 * 1024 * 1024));
    }

    private String boxHandle() throws Exception {
        HttpResponse<byte[]> r = agent.post(port, "/local/v1/credentials", Map.of("provider", "DROPBOX", "accountId",
                boxAccount.toString(), "accessToken", FakeProviders.DROPBOX_TOKEN, "providerExpiresAt",
                Instant.now().plusSeconds(3600).toString(), "rootNamespaceId", "777"));
        return json(r).get("handle").asString();
    }

    private String driveHandle() throws Exception {
        HttpResponse<byte[]> r = agent.post(port, "/local/v1/credentials", Map.of("provider", "GOOGLE_DRIVE", "accountId",
                UUID.randomUUID().toString(), "accessToken", FakeProviders.GOOGLE_TOKEN, "providerExpiresAt",
                Instant.now().plusSeconds(3600).toString()));
        return json(r).get("handle").asString();
    }

    private HttpResponse<byte[]> read(String handle, String fileId, long offset, long length, long size, String rev)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("handle", handle);
        body.put("fileId", fileId);
        body.put("offset", offset);
        body.put("length", length);
        body.put("expectedSize", size);
        if (rev != null) {
            body.put("expectedRevision", rev);
        }
        return agent.post(port, "/local/v1/reads", body);
    }

    private static String h(HttpResponse<byte[]> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    @Test
    void aPinnedDropboxReadIsCachedAndTheSecondReadMakesNoProviderRequest() throws Exception {
        byte[] content = bytes(C + 1000);
        providers.files.put("id:b1", content);
        providers.dropboxRevs.put("id:b1", "0123456789abc");
        assertThat(session(key, true).statusCode()).isEqualTo(200);
        String handle = boxHandle();

        HttpResponse<byte[]> miss = read(handle, "id:b1", 100, 1000, content.length, "0123456789abc");
        assertThat(miss.statusCode()).as(new String(miss.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(miss.body()).isEqualTo(java.util.Arrays.copyOfRange(content, 100, 1100));
        assertThat(h(miss, "X-HyperDrive-Cache")).isEqualTo("miss");
        assertThat(h(miss, "X-HyperDrive-Provider-Bytes")).isEqualTo(Integer.toString(C)); // the whole aligned chunk
        assertThat(providers.seen.get(0).range()).isEqualTo("bytes=0-" + (C - 1));
        assertThat(providers.seen.get(0).dropboxArg()).isEqualTo("{\"path\":\"rev:0123456789abc\"}");

        int requests = providers.seen.size();
        HttpResponse<byte[]> hit = read(handle, "id:b1", 100, 1000, content.length, "0123456789abc");
        assertThat(hit.body()).isEqualTo(miss.body());
        assertThat(h(hit, "X-HyperDrive-Cache")).isEqualTo("hit");
        assertThat(h(hit, "X-HyperDrive-Provider-Bytes")).isEqualTo("0");
        assertThat(h(hit, "X-HyperDrive-Cache-Bytes")).isEqualTo("1000");
        assertThat(providers.seen).hasSize(requests);

        JsonNode status = jsonText(agent.get(port, "/local/v1/cache/status", "Bearer " + agent.secret, Map.of()));
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("entryCount").asInt()).isEqualTo(1);
        assertThat(status.get("encryptedBytesOnDisk").asLong()).isEqualTo(C + 32L);
        assertThat(status.get("counters").get("hits").asLong()).isEqualTo(1);
        assertThat(status.get("counters").get("providerBytesFetched").asLong()).isEqualTo(C);
        String text = status.toString();
        assertThat(text).doesNotContain(key).doesNotContain(temp.toString().replace("\\", "\\\\"))
                .doesNotContain("id:b1").doesNotContain(user.toString());
        assertThat(agent.allOutput()).doesNotContain(key).doesNotContain(FakeProviders.DROPBOX_TOKEN);
    }

    @Test
    void googleReadsBypassTheCacheWithAnExplicitReason() throws Exception {
        session(key, true);
        byte[] content = bytes(C + 10);
        providers.files.put("g1", content);
        HttpResponse<byte[]> r = read(driveHandle(), "g1", 0, 100, content.length, "headRev1");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(h(r, "X-HyperDrive-Cache")).isEqualTo("bypass");
        assertThat(h(r, "X-HyperDrive-Cache-Bypass")).isEqualTo("provider_not_version_bound");
        assertThat(h(r, "X-HyperDrive-Provider-Bytes")).isEqualTo("100"); // exactly the request, no amplification
        assertThat(providers.seen.get(0).range()).isEqualTo("bytes=0-99");
        JsonNode status = jsonText(agent.get(port, "/local/v1/cache/status", "Bearer " + agent.secret, Map.of()));
        assertThat(status.get("entryCount").asInt()).isZero();
        assertThat(status.get("bypasses").get("provider_not_version_bound").asLong()).isEqualTo(1);
    }

    @Test
    void withoutACacheKeyReadsStillWorkUncached() throws Exception {
        providers.files.put("id:b2", bytes(500));
        providers.dropboxRevs.put("id:b2", "0123456789abc");
        HttpResponse<byte[]> r = read(boxHandle(), "id:b2", 0, 500, 500, "0123456789abc");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(h(r, "X-HyperDrive-Cache-Bypass")).isEqualTo("cache_key_unavailable");
        JsonNode status = jsonText(agent.get(port, "/local/v1/cache/status", "Bearer " + agent.secret, Map.of()));
        assertThat(status.get("enabled").asBoolean()).isFalse();
        assertThat(status.get("disabledReason").asString()).isEqualTo("cache_key_unavailable");
        assertThat(Files.exists(temp.resolve("agent").resolve("cache"))).isFalse();
    }

    @Test
    void sessionEndPurgesTheUsersFilesAndClearReportsCounts() throws Exception {
        providers.files.put("id:b3", bytes(5000));
        providers.dropboxRevs.put("id:b3", "0123456789abc");
        session(key, true);
        String handle = boxHandle();
        read(handle, "id:b3", 0, 5000, 5000, "0123456789abc");
        assertThat(cacheFiles()).hasSize(1);

        JsonNode cleared = json(agent.post(port, "/local/v1/cache/clear", Map.of()));
        assertThat(cleared.get("removed").asInt()).isEqualTo(1);
        assertThat(cleared.get("failed").asInt()).isZero();
        assertThat(cacheFiles()).isEmpty();

        read(handle, "id:b3", 0, 5000, 5000, "0123456789abc");
        JsonNode ended = json(agent.post(port, "/local/v1/cache/session/end", Map.of("userId", user.toString())));
        assertThat(ended.get("removed").asInt()).isEqualTo(1);
        assertThat(cacheFiles()).isEmpty();
        HttpResponse<byte[]> after = read(handle, "id:b3", 0, 5000, 5000, "0123456789abc");
        assertThat(h(after, "X-HyperDrive-Cache-Bypass")).isEqualTo("cache_key_unavailable");
    }

    @Test
    void cacheEndpointsValidateEverything() throws Exception {
        assertThat(agent.post(port, "/local/v1/cache/session", Map.of("userId", user.toString(), "key", "c2hvcnQ=",
                "fresh", true, "budgetBytes", 5L << 30)).statusCode()).isEqualTo(400);
        assertThat(agent.post(port, "/local/v1/cache/session", Map.of("userId", "nope", "key", key, "fresh", true,
                "budgetBytes", 5L << 30)).statusCode()).isEqualTo(400);
        assertThat(agent.post(port, "/local/v1/cache/session", Map.of("userId", user.toString(), "key", key, "fresh", true,
                "budgetBytes", 5L << 30, "path", "C:\\Windows")).statusCode()).isEqualTo(400);
        assertThat(agent.post(port, "/local/v1/cache/session", Map.of("userId", user.toString(), "key", key, "fresh", true,
                "budgetBytes", 1000)).statusCode()).isEqualTo(400);
        assertThat(agent.post(port, "/local/v1/cache/budget", Map.of("budgetBytes", -1)).statusCode()).isEqualTo(400);
        assertThat(agent.post(port, "/local/v1/cache/budget", Map.of("budgetBytes", 2L << 40)).statusCode()).isEqualTo(400);
        assertThat(agent.post(port, "/local/v1/cache/budget", Map.of("budgetBytes", 0)).statusCode()).isEqualTo(200);
        assertThat(agent.post(port, "/local/v1/cache/clear", Map.of()).statusCode()).isEqualTo(409); // no session
        assertThat(agent.post(port, "/local/v1/cache/clear", Map.of("path", "..")).statusCode()).isEqualTo(400);
        assertThat(agent.get(port, "/local/v1/cache/status", "Bearer wrong", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void shutdownKeepsOnlyCiphertextOnDisk() throws Exception {
        byte[] content = bytes(3000);
        providers.files.put("id:b4", content);
        providers.dropboxRevs.put("id:b4", "0123456789abc");
        session(key, true);
        read(boxHandle(), "id:b4", 0, 3000, 3000, "0123456789abc");
        agent.send("{\"type\":\"shutdown\"}");
        assertThat(agent.exitCode()).isZero();
        List<Path> files = cacheFiles();
        assertThat(files).hasSize(1);
        byte[] disk = Files.readAllBytes(files.get(0));
        assertThat(disk).hasSize(3000 + 32);
        assertThat(java.util.Arrays.copyOfRange(disk, 32, disk.length)).isNotEqualTo(java.util.Arrays.copyOfRange(content, 16, 3000));
    }

    private List<Path> cacheFiles() throws Exception {
        Path root = temp.resolve("agent").resolve("cache");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".hdc")).toList();
        }
    }
}
