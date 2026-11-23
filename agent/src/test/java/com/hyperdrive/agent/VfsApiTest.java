package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.agent.vfs.MetadataSource;
import com.hyperdrive.agent.vfs.MountRegistry;
import com.hyperdrive.agent.vfs.VfsMetadataService;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Batch 8: the agent's VFS routes through the real HTTP API, with a fake backend metadata source. */
class VfsApiTest {

    @TempDir
    Path temp;

    private static final String GRANT = "hdm_" + "G".repeat(43);
    private final UUID user = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private AgentHarness agent;
    private FakeSource source;
    private int port;

    /** Fake backend: pages of entries; records which grant each call carried. */
    static final class FakeSource implements MetadataSource {
        final List<String> grantsSeen = new CopyOnWriteArrayList<>();
        volatile int pages = 2;
        volatile int perPage = 3;
        volatile boolean duplicateAcrossPages;
        volatile MetadataException failWith;

        @Override
        public List<Account> accounts(MountRegistry.Mount mount) throws MetadataException {
            grantsSeen.add(grantOf(mount));
            if (failWith != null) {
                throw failWith;
            }
            return List.of(new Account(UUID.fromString("11111111-2222-4333-8444-555555555555"), "DROPBOX", "USER",
                    "CONNECTED"));
        }

        @Override
        public Page files(MountRegistry.Mount mount, UUID accountId, String directory, String continuation, int size)
                throws MetadataException {
            grantsSeen.add(grantOf(mount));
            if (failWith != null) {
                throw failWith;
            }
            int page = continuation == null ? 0 : Integer.parseInt(continuation);
            List<Entry> items = new ArrayList<>();
            for (int i = 0; i < perPage; i++) {
                int n = duplicateAcrossPages && page > 0 && i == 0 ? page * perPage - 1 : page * perPage + i;
                items.add(new Entry("id:" + n, "File " + n + ".txt", "FILE", (long) n, Instant.EPOCH, null));
            }
            return new Page(items, page + 1 < pages ? Integer.toString(page + 1) : null);
        }

        private static String grantOf(MountRegistry.Mount mount) {
            try {
                var m = MountRegistry.Mount.class.getDeclaredMethod("grant");
                m.setAccessible(true);
                return (String) m.invoke(mount);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @BeforeEach
    void start() throws Exception {
        agent = new AgentHarness(Duration.ofSeconds(5));
        source = new FakeSource();
        agent.agent.useMetadataSource(source);
        agent.send(AgentHarness.bootstrapJson(agent.secret, temp.resolve("agent")));
        port = agent.handshake().get("port").asInt();
    }

    @AfterEach
    void stop() throws Exception {
        agent.close();
        agent.exitCode();
    }

    private JsonNode mount() throws Exception {
        HttpResponse<byte[]> r = agent.post(port, "/local/v1/vfs/mount", Map.of("userId", user.toString(),
                "backendOrigin", "http://127.0.0.1:18080", "grant", GRANT,
                "grantExpiresAt", Instant.now().plusSeconds(600).toString()));
        assertThat(r.statusCode()).as(new String(r.body())).isEqualTo(201);
        return AgentHarness.JSON.readTree(r.body());
    }

    private HttpResponse<String> vfs(String path, Object body, String capability) throws Exception {
        return agent.postAs(port, path, body, capability == null ? null : "Bearer " + capability);
    }

    @Test
    void theCapabilityOpensOnlyTheVfsRoutesAndTheAdminSecretDoesNotOpenThem() throws Exception {
        JsonNode created = mount();
        String capability = created.get("capability").asString();
        assertThat(capability).matches("^hdv_[A-Za-z0-9_-]{43}$");

        HttpResponse<String> hello = vfs("/local/v1/vfs/session", Map.of(), capability);
        assertThat(hello.statusCode()).isEqualTo(200);
        assertThat(hello.body()).contains("\"generation\":" + created.get("generation").asLong());

        HttpResponse<String> accounts = vfs("/local/v1/vfs/accounts", Map.of(), capability);
        assertThat(accounts.statusCode()).isEqualTo(200);
        assertThat(accounts.body()).contains("DROPBOX").doesNotContain(GRANT, "hdm_");

        // The launch secret is not accepted on VFS routes...
        assertThat(vfs("/local/v1/vfs/accounts", Map.of(), agent.secret).statusCode()).isEqualTo(401);
        // ...and the capability is not accepted on any administration route.
        for (String admin : List.of("/local/v1/reads", "/local/v1/credentials", "/local/v1/credentials/clear",
                "/local/v1/cache/clear", "/local/v1/vfs/mount", "/local/v1/vfs/mount/revoke",
                "/local/v1/vfs/mount/grant")) {
            assertThat(vfs(admin, Map.of(), capability).statusCode()).as(admin).isEqualTo(401);
        }
        assertThat(agent.get(port, "/local/v1/status", "Bearer " + capability, Map.of()).statusCode()).isEqualTo(401);
        // No generic forwarding: unknown paths don't exist for either credential.
        assertThat(vfs("/local/v1/vfs/proxy", Map.of("url", "http://example.com"), capability).statusCode())
                .isEqualTo(401);
        assertThat(agent.allOutput()).doesNotContain(capability, GRANT);
    }

    @Test
    void revokingOrRemountingInvalidatesTheOldCapabilityAndRenewalNeedsTheCurrentGeneration() throws Exception {
        JsonNode first = mount();
        String old = first.get("capability").asString();
        JsonNode second = mount();
        assertThat(second.get("generation").asLong()).isGreaterThan(first.get("generation").asLong());
        assertThat(vfs("/local/v1/vfs/session", Map.of(), old).statusCode()).isEqualTo(401);
        String current = second.get("capability").asString();
        assertThat(vfs("/local/v1/vfs/session", Map.of(), current).statusCode()).isEqualTo(200);

        String renewed = "hdm_" + "R".repeat(43);
        HttpResponse<byte[]> stale = agent.post(port, "/local/v1/vfs/mount/grant", Map.of("generation",
                first.get("generation").asLong(), "grant", renewed, "grantExpiresAt", Instant.now().plusSeconds(600).toString()));
        assertThat(stale.statusCode()).isEqualTo(404);
        HttpResponse<byte[]> ok = agent.post(port, "/local/v1/vfs/mount/grant", Map.of("generation",
                second.get("generation").asLong(), "grant", renewed, "grantExpiresAt", Instant.now().plusSeconds(600).toString()));
        assertThat(ok.statusCode()).isEqualTo(200);
        vfs("/local/v1/vfs/accounts", Map.of(), current);
        assertThat(source.grantsSeen.getLast()).isEqualTo(renewed);

        assertThat(AgentHarness.JSON.readTree(agent.post(port, "/local/v1/vfs/mount/revoke", Map.of()).body())
                .get("revoked").asBoolean()).isTrue();
        assertThat(vfs("/local/v1/vfs/session", Map.of(), current).statusCode()).isEqualTo(401);
        assertThat(agent.get(port, "/local/v1/vfs/status", "Bearer " + agent.secret, Map.of()).body())
                .contains("\"active\":false");
    }

    @Test
    void listingsAssemblePagesDropDuplicatesAndFailLoudlyBeyondLimits() throws Exception {
        String capability = mount().get("capability").asString();
        source.pages = 3;
        source.perPage = 4;
        source.duplicateAcrossPages = true;
        HttpResponse<String> list = vfs("/local/v1/vfs/list", Map.of("accountId", account.toString()), capability);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode body = AgentHarness.JSON.readTree(list.body());
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("pages").asInt()).isEqualTo(3);
        assertThat(body.get("duplicatesDropped").asInt()).isEqualTo(2);
        assertThat(body.get("items")).hasSize(10);

        source.duplicateAcrossPages = false;
        source.pages = VfsMetadataService.MAX_PAGES + 1;
        source.perPage = 1;
        HttpResponse<String> tooMany = vfs("/local/v1/vfs/list", Map.of("accountId", account.toString()), capability);
        assertThat(tooMany.statusCode()).isEqualTo(413);
        assertThat(tooMany.body()).contains("directory_too_large");

        source.pages = 1;
        source.perPage = VfsMetadataService.MAX_ENTRIES + 1;
        assertThat(vfs("/local/v1/vfs/list", Map.of("accountId", account.toString()), capability).body())
                .contains("directory_too_large");
    }

    @Test
    void backendAuthorizationLossAndBadInputAreReportedWithStableCodes() throws Exception {
        String capability = mount().get("capability").asString();
        source.failWith = new MetadataSource.MetadataException("authorization_lost", 401);
        HttpResponse<String> lost = vfs("/local/v1/vfs/accounts", Map.of(), capability);
        assertThat(lost.statusCode()).isEqualTo(401);
        assertThat(lost.body()).contains("authorization_lost");
        source.failWith = null;

        assertThat(vfs("/local/v1/vfs/list", Map.of("accountId", "nope"), capability).statusCode()).isEqualTo(400);
        assertThat(vfs("/local/v1/vfs/list", Map.of("accountId", account.toString(), "url", "x"), capability)
                .statusCode()).isEqualTo(400);
        assertThat(vfs("/local/v1/vfs/list", Map.of("accountId", account.toString(), "directory", "a\u0000b"),
                capability).statusCode()).isEqualTo(400);
        assertThat(agent.postAs(port, "/local/v1/vfs/accounts?x=1", Map.of(), "Bearer " + capability).statusCode())
                .isEqualTo(400);
    }

    @Test
    void mountCreationValidatesTheBackendOriginAndGrant() throws Exception {
        for (String origin : List.of("http://example.com", "file:///c:/", "https://user@host", "http://127.0.0.1:18080/api",
                "ftp://127.0.0.1")) {
            HttpResponse<byte[]> r = agent.post(port, "/local/v1/vfs/mount", Map.of("userId", user.toString(),
                    "backendOrigin", origin, "grant", GRANT, "grantExpiresAt", Instant.now().plusSeconds(60).toString()));
            assertThat(r.statusCode()).as(origin).isEqualTo(400);
            assertThat(new String(r.body())).contains("invalid_backend_origin");
        }
        HttpResponse<byte[]> expired = agent.post(port, "/local/v1/vfs/mount", Map.of("userId", user.toString(),
                "backendOrigin", "https://hyperdrive.example", "grant", GRANT,
                "grantExpiresAt", Instant.now().minusSeconds(1).toString()));
        assertThat(new String(expired.body())).contains("grant_expired");
        HttpResponse<byte[]> badGrant = agent.post(port, "/local/v1/vfs/mount", Map.of("userId", user.toString(),
                "backendOrigin", "https://hyperdrive.example", "grant", "hda_" + "x".repeat(43),
                "grantExpiresAt", Instant.now().plusSeconds(60).toString()));
        assertThat(new String(badGrant.body())).contains("invalid_grant");
    }
}
