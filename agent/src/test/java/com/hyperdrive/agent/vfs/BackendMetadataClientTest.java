package com.hyperdrive.agent.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The agent's backend metadata client against a local stand-in backend (not the real backend). */
class BackendMetadataClientTest {

    private static final String GRANT = "hdm_" + "B".repeat(43);
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile String location;
    private BackendMetadataClient client;
    private MountRegistry registry;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders()
                    .getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (location != null) {
                ex.getResponseHeaders().add("Location", location);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new BackendMetadataClient(Duration.ofSeconds(2), Duration.ofSeconds(2), Clock.systemUTC());
        registry = new MountRegistry(Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MountRegistry.Mount mount() throws Exception {
        registry.create(UUID.randomUUID(), "http://127.0.0.1:" + server.getAddress().getPort(), GRANT,
                Instant.now().plusSeconds(60));
        return registry.current();
    }

    @Test
    void callsOnlyTheFixedRoutesWithTheGrantAndEncodesParameters() throws Exception {
        UUID account = UUID.randomUUID();
        body = "{\"items\":[{\"id\":\"id:1\",\"name\":\"a.txt\",\"kind\":\"FILE\",\"sizeBytes\":5,"
                + "\"modifiedAt\":\"2026-01-02T03:04:05Z\",\"directoryLocator\":null}],\"continuation\":\"c2\"}";
        MetadataSource.Page page = client.files(mount(), account, "/A B&c", "c1", 200);
        assertThat(page.items()).hasSize(1);
        assertThat(page.continuation()).isEqualTo("c2");
        assertThat(requests.getFirst()).isEqualTo("GET /api/v1/vfs/accounts/" + account
                + "/files?pageSize=200&directory=%2FA+B%26c&continuation=c1 auth=Bearer " + GRANT);
    }

    @Test
    void mapsStatusesRefusesRedirectsAndRejectsMalformedOrOversizedBodies() throws Exception {
        MountRegistry.Mount mount = mount();
        status = 401;
        assertThatThrownBy(() -> client.accounts(mount)).hasMessage("authorization_lost");
        status = 409;
        body = "{\"code\":\"account_needs_reconnect\"}";
        assertThatThrownBy(() -> client.files(mount, UUID.randomUUID(), null, null, 10)).hasMessage("needs_reconnect");
        status = 302;
        location = "http://example.com/";
        assertThatThrownBy(() -> client.accounts(mount)).hasMessage("backend_error");
        assertThat(requests).allMatch(r -> !r.contains("example.com"));
        location = null;
        status = 200;
        body = "{\"items\":[{\"id\":\"id:1\",\"name\":\"a\",\"kind\":\"EXECUTABLE\"}]}";
        assertThatThrownBy(() -> client.files(mount, UUID.randomUUID(), null, null, 10))
                .hasMessage("invalid_backend_response");
        body = "{\"items\":[{\"id\":\"id:1\",\"name\":\"a\",\"kind\":\"FILE\",\"sizeBytes\":-1}]}";
        assertThatThrownBy(() -> client.files(mount, UUID.randomUUID(), null, null, 10))
                .hasMessage("invalid_backend_response");
        body = "{\"items\":[],\"pad\":\"" + "x".repeat(BackendMetadataClient.MAX_BODY) + "\"}";
        assertThatThrownBy(() -> client.files(mount, UUID.randomUUID(), null, null, 10))
                .hasMessage("response_too_large");
    }

    @Test
    void fileMetadataByIdIsEncodedStrictlyParsedAndMustMatchTheRequestedId() throws Exception {
        UUID account = UUID.randomUUID();
        MountRegistry.Mount mount = mount();
        body = "{\"id\":\"id:Ab-1\",\"provider\":\"DROPBOX\",\"kind\":\"FILE\",\"sizeBytes\":5368709897,"
                + "\"modifiedAt\":null,\"providerRevision\":\"0123456789a\",\"canDownload\":true}";
        MetadataSource.FileMeta f = client.file(mount, account, "id:Ab-1");
        assertThat(f.sizeBytes()).isEqualTo(5_368_709_897L);
        assertThat(f.providerRevision()).isEqualTo("0123456789a");
        assertThat(requests.getFirst()).isEqualTo("GET /api/v1/vfs/accounts/" + account + "/files/id%3AAb-1 auth=Bearer " + GRANT);
        // Another file's metadata, unknown provider or a negative size is never accepted.
        body = "{\"id\":\"id:Other\",\"provider\":\"DROPBOX\",\"kind\":\"FILE\",\"sizeBytes\":5,\"canDownload\":true}";
        assertThatThrownBy(() -> client.file(mount, account, "id:Ab-1")).hasMessage("invalid_backend_response");
        body = "{\"id\":\"id:Ab-1\",\"provider\":\"ONEDRIVE\",\"kind\":\"FILE\",\"sizeBytes\":5,\"canDownload\":true}";
        assertThatThrownBy(() -> client.file(mount, account, "id:Ab-1")).hasMessage("invalid_backend_response");
        body = "{\"id\":\"id:Ab-1\",\"provider\":\"DROPBOX\",\"kind\":\"FILE\",\"sizeBytes\":-5,\"canDownload\":true}";
        assertThatThrownBy(() -> client.file(mount, account, "id:Ab-1")).hasMessage("invalid_backend_response");
        status = 404;
        body = "{\"code\":\"file_not_found\"}";
        assertThatThrownBy(() -> client.file(mount, account, "id:Ab-1")).hasMessage("not_found");
    }

    @Test
    void anExpiredGrantIsNotSent() throws Exception {
        MountRegistry r = new MountRegistry(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-30)));
        r.create(UUID.randomUUID(), "http://127.0.0.1:" + server.getAddress().getPort(), GRANT,
                Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> client.accounts(r.current())).hasMessage("authorization_expired");
        assertThat(requests).isEmpty();
    }
}
