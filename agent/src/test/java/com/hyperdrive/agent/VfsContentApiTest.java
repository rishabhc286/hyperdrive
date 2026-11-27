package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.agent.read.ProviderEndpoints;
import com.hyperdrive.agent.vfs.MetadataSource;
import com.hyperdrive.agent.vfs.MountRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Batch 9: mounted content reads through the real agent HTTP API: capability-only content routes,
 * binary answers with validated headers, the admin-only renewal routes, Dropbox cache use and Google
 * bypass against local provider stand-ins (FakeProviders), and no secrets in answers or logs.
 */
class VfsContentApiTest {

    @TempDir
    Path temp;

    private static final String GRANT = "hdm_" + "G".repeat(43);
    private final UUID user = UUID.randomUUID();
    private final UUID box = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private final UUID drive = UUID.fromString("66666666-7777-4888-9999-aaaaaaaaaaaa");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private FakeProviders providers;
    private AgentHarness agent;
    private int port;
    private byte[] boxFile;
    private byte[] driveFile;

    /** Fake backend: two files, one per provider. */
    final class Source implements MetadataSource {
        @Override
        public List<Account> accounts(MountRegistry.Mount mount) {
            return List.of();
        }

        @Override
        public Page files(MountRegistry.Mount mount, UUID accountId, String directory, String continuation, int size) {
            return new Page(List.of(), null);
        }

        @Override
        public FileMeta file(MountRegistry.Mount mount, UUID accountId, String fileId) throws MetadataException {
            if (accountId.equals(box) && fileId.equals("id:b1")) {
                return new FileMeta("id:b1", "DROPBOX", "FILE", (long) boxFile.length, "a1c10ce0dd78", true);
            }
            if (accountId.equals(drive) && fileId.equals("g1")) {
                return new FileMeta("g1", "GOOGLE_DRIVE", "FILE", (long) driveFile.length, null, true);
            }
            throw new MetadataException("not_found", 404);
        }
    }

    @BeforeEach
    void start() throws Exception {
        providers = new FakeProviders();
        boxFile = random(9 * 1024 * 1024 + 17, 1);
        driveFile = random(300_000, 2);
        providers.files.put("id:b1", boxFile);
        providers.files.put("g1", driveFile);
        providers.dropboxRevs.put("id:b1", "a1c10ce0dd78");
        agent = new AgentHarness(Duration.ofSeconds(5), new ProviderEndpoints(providers.googleBase(), providers.dropboxBase()),
                Duration.ofSeconds(5));
        agent.agent.useMetadataSource(new Source());
        agent.send(AgentHarness.bootstrapJson(agent.secret, temp.resolve("agent")));
        port = agent.handshake().get("port").asInt();
    }

    @AfterEach
    void stop() throws Exception {
        agent.close();
        agent.exitCode();
        providers.close();
    }

    static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private JsonNode mount() throws Exception {
        HttpResponse<byte[]> r = agent.post(port, "/local/v1/vfs/mount", Map.of("userId", user.toString(), "backendOrigin",
                "http://127.0.0.1:18080", "grant", GRANT, "grantExpiresAt", Instant.now().plusSeconds(600).toString()));
        assertThat(r.statusCode()).isEqualTo(201);
        return AgentHarness.JSON.readTree(r.body());
    }

    private HttpResponse<byte[]> vfs(String path, Object body, String capability) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + capability).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(AgentHarness.JSON.writeValueAsString(body))).build();
        return http.send(r, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonNode json(HttpResponse<byte[]> r) {
        return AgentHarness.JSON.readTree(r.body());
    }

    /** Plays Electron main's renewal pump once: poll, then install the matching provider token. */
    private CompletableFuture<List<String>> pumpOnce(long generation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode polled = json(agent.post(port, "/local/v1/vfs/credential-requests", Map.of("generation", generation)));
                List<String> ids = new java.util.ArrayList<>();
                for (JsonNode req : polled.get("requests")) {
                    String id = req.get("accountId").asString();
                    ids.add(id);
                    boolean dropbox = id.equals(box.toString());
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("generation", generation);
                    body.put("provider", dropbox ? "DROPBOX" : "GOOGLE_DRIVE");
                    body.put("accountId", id);
                    body.put("accessToken", dropbox ? FakeProviders.DROPBOX_TOKEN : FakeProviders.GOOGLE_TOKEN);
                    body.put("providerExpiresAt", Instant.now().plusSeconds(3600).toString());
                    if (dropbox) {
                        body.put("rootNamespaceId", "777");
                    }
                    HttpResponse<byte[]> installed = agent.post(port, "/local/v1/vfs/mount/credential", body);
                    assertThat(installed.statusCode()).as(new String(installed.body())).isEqualTo(201);
                    assertThat(new String(installed.body())).doesNotContain("hdh_", FakeProviders.DROPBOX_TOKEN);
                }
                return ids;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private HttpResponse<byte[]> read(String cap, String handle, long offset, long length) throws Exception {
        return vfs("/local/v1/vfs/read", Map.of("handle", handle, "offset", offset, "length", length), cap);
    }

    @Test
    void openReadCloseOverHttpWithRenewalAndExactBinaryAnswers() throws Exception {
        JsonNode m = mount();
        String cap = m.get("capability").asString();
        long gen = m.get("generation").asLong();
        HttpResponse<byte[]> opened = vfs("/local/v1/vfs/open", Map.of("accountId", box.toString(), "fileId", "id:b1",
                "expectedSize", boxFile.length), cap);
        assertThat(opened.statusCode()).as(new String(opened.body())).isEqualTo(201);
        JsonNode o = json(opened);
        String handle = o.get("handle").asString();
        assertThat(o.get("sizeBytes").asLong()).isEqualTo(boxFile.length);
        assertThat(o.get("consistency").asString()).isEqualTo("dropbox-revision-pinned");
        assertThat(new String(opened.body())).doesNotContain("a1c10ce0dd78", "hdh_", "token");

        CompletableFuture<List<String>> pump = pumpOnce(gen);
        long at = 4L * 1024 * 1024 - 1000; // crosses a chunk boundary
        HttpResponse<byte[]> r = read(cap, handle, at, 3000);
        assertThat(r.statusCode()).as(new String(r.body())).isEqualTo(200);
        assertThat(pump.get(10, TimeUnit.SECONDS)).containsExactly(box.toString());
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/octet-stream");
        assertThat(r.headers().firstValue("Content-Length")).hasValue("3000");
        assertThat(r.headers().firstValue("X-HyperDrive-Read-Offset")).hasValue(Long.toString(at));
        assertThat(r.headers().firstValue("X-HyperDrive-Read-Length")).hasValue("3000");
        assertThat(r.headers().firstValue("X-HyperDrive-EOF")).hasValue("false");
        assertThat(r.body()).isEqualTo(Arrays.copyOfRange(boxFile, (int) at, (int) at + 3000));
        assertThat(r.headers().map().keySet().toString()).doesNotContainIgnoringCase("revision");

        HttpResponse<byte[]> tail = read(cap, handle, boxFile.length - 7, 100);
        assertThat(tail.body()).isEqualTo(Arrays.copyOfRange(boxFile, boxFile.length - 7, boxFile.length));
        assertThat(tail.headers().firstValue("X-HyperDrive-EOF")).hasValue("true");
        HttpResponse<byte[]> eof = read(cap, handle, boxFile.length, 100);
        assertThat(eof.statusCode()).isEqualTo(200);
        assertThat(eof.body()).isEmpty();
        assertThat(eof.headers().firstValue("X-HyperDrive-Read-Length")).hasValue("0");

        assertThat(json(vfs("/local/v1/vfs/close", Map.of("handle", handle), cap)).get("closed").asBoolean()).isTrue();
        HttpResponse<byte[]> stale = read(cap, handle, 0, 10);
        assertThat(stale.statusCode()).isEqualTo(410);
        assertThat(json(stale).get("code").asString()).isEqualTo("stale_handle");

        JsonNode status = AgentHarness.JSON.readTree(agent.get(port, "/local/v1/vfs/status", "Bearer " + agent.secret,
                Map.of()).body());
        assertThat(status.path("reads").path("reads").asLong()).isEqualTo(3);
        assertThat(status.path("reads").path("renewals").path("installed").asLong()).isEqualTo(1);
        String all = agent.allOutput();
        assertThat(all).doesNotContain(cap, handle, FakeProviders.DROPBOX_TOKEN, GRANT, "hdh_");
    }

    @Test
    void contentRoutesNeedTheCapabilityAndRenewalRoutesNeedTheAdminSecret() throws Exception {
        JsonNode m = mount();
        String cap = m.get("capability").asString();
        long gen = m.get("generation").asLong();
        for (String route : List.of("/local/v1/vfs/open", "/local/v1/vfs/read", "/local/v1/vfs/close", "/local/v1/vfs/cancel")) {
            assertThat(vfs(route, Map.of(), agent.secret).statusCode()).as(route).isEqualTo(401);
        }
        for (String route : List.of("/local/v1/vfs/credential-requests", "/local/v1/vfs/mount/credential",
                "/local/v1/vfs/mount/credential-failed")) {
            assertThat(vfs(route, Map.of("generation", gen), cap).statusCode()).as(route).isEqualTo(401);
        }
        // Old generations are refused for polling, installing and failing.
        assertThat(agent.post(port, "/local/v1/vfs/credential-requests", Map.of("generation", gen + 7)).statusCode())
                .isEqualTo(404);
        assertThat(agent.post(port, "/local/v1/vfs/mount/credential", Map.of("generation", gen + 7, "provider", "DROPBOX",
                "accountId", box.toString(), "accessToken", FakeProviders.DROPBOX_TOKEN, "providerExpiresAt",
                Instant.now().plusSeconds(3600).toString(), "rootNamespaceId", "777")).statusCode()).isEqualTo(404);
        // Nothing was requested for this account: an unsolicited install is refused.
        assertThat(agent.post(port, "/local/v1/vfs/mount/credential", Map.of("generation", gen, "provider", "DROPBOX",
                "accountId", box.toString(), "accessToken", FakeProviders.DROPBOX_TOKEN, "providerExpiresAt",
                Instant.now().plusSeconds(3600).toString(), "rootNamespaceId", "777")).statusCode()).isEqualTo(409);
        // Strict bodies: unknown fields, oversized reads, foreign files.
        assertThat(vfs("/local/v1/vfs/open", Map.of("accountId", box.toString(), "fileId", "id:b1", "expectedSize", 1,
                "url", "http://x"), cap).statusCode()).isEqualTo(400);
        assertThat(json(vfs("/local/v1/vfs/open", Map.of("accountId", drive.toString(), "fileId", "id:b1", "expectedSize",
                boxFile.length), cap)).get("code").asString()).isEqualTo("not_found");
        String handle = json(vfs("/local/v1/vfs/open", Map.of("accountId", box.toString(), "fileId", "id:b1",
                "expectedSize", boxFile.length), cap)).get("handle").asString();
        assertThat(read(cap, handle, 0, 4 * 1024 * 1024 + 1).statusCode()).isEqualTo(400);
        // A new mount: the old capability is dead, and so are its handles under the new one.
        String newCap = mount().get("capability").asString();
        assertThat(read(cap, handle, 0, 10).statusCode()).isEqualTo(401);
        assertThat(read(newCap, handle, 0, 10).statusCode()).isEqualTo(410);
    }

    @Test
    void dropboxRangesUseTheEncryptedCacheAndGoogleRangesBypassIt() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        assertThat(agent.post(port, "/local/v1/cache/session", Map.of("userId", user.toString(), "key",
                java.util.Base64.getEncoder().encodeToString(key), "fresh", true, "budgetBytes", 1L << 30)).statusCode())
                .isEqualTo(200);
        JsonNode m = mount();
        String cap = m.get("capability").asString();
        long gen = m.get("generation").asLong();
        String boxHandle = json(vfs("/local/v1/vfs/open", Map.of("accountId", box.toString(), "fileId", "id:b1",
                "expectedSize", boxFile.length), cap)).get("handle").asString();
        String driveHandle = json(vfs("/local/v1/vfs/open", Map.of("accountId", drive.toString(), "fileId", "g1",
                "expectedSize", driveFile.length), cap)).get("handle").asString();
        CompletableFuture<List<String>> pump = pumpOnce(gen);
        assertThat(read(cap, boxHandle, 100, 1000).headers().firstValue("X-HyperDrive-Cache")).hasValue("miss");
        pump.get(10, TimeUnit.SECONDS);
        HttpResponse<byte[]> hit = read(cap, boxHandle, 200, 1000);
        assertThat(hit.headers().firstValue("X-HyperDrive-Cache")).hasValue("hit");
        assertThat(hit.body()).isEqualTo(Arrays.copyOfRange(boxFile, 200, 1200));

        CompletableFuture<List<String>> pump2 = pumpOnce(gen);
        HttpResponse<byte[]> g = read(cap, driveHandle, 5, 1000);
        pump2.get(10, TimeUnit.SECONDS);
        assertThat(g.headers().firstValue("X-HyperDrive-Cache")).hasValue("bypass");
        assertThat(g.body()).isEqualTo(Arrays.copyOfRange(driveFile, 5, 1005));
        assertThat(read(cap, driveHandle, 5, 1000).headers().firstValue("X-HyperDrive-Cache")).hasValue("bypass");
        // Dropbox provider requests were always rev:-pinned.
        assertThat(providers.seen.stream().filter(s -> s.dropboxArg() != null).map(FakeProviders.Seen::dropboxArg))
                .allMatch(a -> a.contains("rev:a1c10ce0dd78"));
        JsonNode stats = AgentHarness.JSON.readTree(agent.get(port, "/local/v1/vfs/status", "Bearer " + agent.secret,
                Map.of()).body()).path("reads");
        assertThat(stats.path("bytesReturned").asLong()).isEqualTo(4000);
        assertThat(stats.path("cacheBytes").asLong()).isEqualTo(1000);
        assertThat(stats.path("providerBytes").asLong()).isEqualTo(4L * 1024 * 1024 + 2000); // one whole chunk + 2 Google ranges
    }

    @Test
    void aBackendRefusalReachesWaitingReadsAndDisconnectEndsTheAccountsHandles() throws Exception {
        JsonNode m = mount();
        String cap = m.get("capability").asString();
        long gen = m.get("generation").asLong();
        String handle = json(vfs("/local/v1/vfs/open", Map.of("accountId", box.toString(), "fileId", "id:b1",
                "expectedSize", boxFile.length), cap)).get("handle").asString();
        CompletableFuture<HttpResponse<byte[]>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return read(cap, handle, 0, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        JsonNode polled = json(agent.post(port, "/local/v1/vfs/credential-requests", Map.of("generation", gen)));
        assertThat(polled.get("requests").get(0).get("accountId").asString()).isEqualTo(box.toString());
        assertThat(agent.post(port, "/local/v1/vfs/mount/credential-failed", Map.of("generation", gen, "accountId",
                box.toString(), "code", "content_permission_missing")).statusCode()).isEqualTo(200);
        HttpResponse<byte[]> failed = waiting.get(10, TimeUnit.SECONDS);
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(json(failed).get("code").asString()).isEqualTo("content_permission_missing");

        assertThat(agent.post(port, "/local/v1/credentials/clear", Map.of("accountId", box.toString())).statusCode())
                .isEqualTo(200);
        assertThat(read(cap, handle, 0, 10).statusCode()).isEqualTo(410);
    }
}
