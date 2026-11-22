package com.hyperdrive.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.agent.read.ProviderEndpoints;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Delegated credentials and bounded provider reads, against local mock provider servers. */
class ReadTest {

    @TempDir
    Path temp;

    private FakeProviders providers;
    private AgentHarness agent;
    private int port;
    private final UUID driveAccount = UUID.randomUUID();
    private final UUID boxAccount = UUID.randomUUID();
    private static final int MIB4 = 4 * 1024 * 1024;

    @BeforeEach
    void start() throws Exception {
        providers = new FakeProviders();
        agent = new AgentHarness(Duration.ofSeconds(5), new ProviderEndpoints(providers.googleBase(), providers.dropboxBase()),
                Duration.ofMillis(800));
        agent.send(AgentHarness.bootstrapJson(agent.secret, temp.resolve("agent")));
        port = agent.handshake().get("port").asInt();
    }

    @AfterEach
    void stop() throws Exception {
        agent.close();
        agent.exitCode();
        providers.close();
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private String install(String provider, UUID account, String token, Instant expires, String ns) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", provider);
        body.put("accountId", account.toString());
        body.put("accessToken", token);
        body.put("providerExpiresAt", expires.toString());
        if (ns != null) {
            body.put("rootNamespaceId", ns);
        }
        HttpResponse<byte[]> r = agent.post(port, "/local/v1/credentials", body);
        assertThat(r.statusCode()).as(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(201);
        return AgentHarness.JSON.readTree(r.body()).get("handle").asString();
    }

    private String driveHandle() throws Exception {
        return install("GOOGLE_DRIVE", driveAccount, FakeProviders.GOOGLE_TOKEN, Instant.now().plusSeconds(3600), null);
    }

    private String boxHandle() throws Exception {
        return install("DROPBOX", boxAccount, FakeProviders.DROPBOX_TOKEN, Instant.now().plusSeconds(3600), "777");
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

    /** The error code of a failed read; "" for a successful (binary) one. */
    private static String code(HttpResponse<byte[]> r) {
        if (r.statusCode() == 200 && r.headers().firstValue("Content-Type").orElse("").startsWith("application/octet-stream")) {
            return "";
        }
        return AgentHarness.JSON.readTree(r.body()).path("code").asString("");
    }

    // --- Credentials -------------------------------------------------------------------------

    @Test
    void credentialInstallIsValidatedAndHandlesAreOpaque() throws Exception {
        String handle = driveHandle();
        assertThat(handle).startsWith("hdh_").doesNotContain(FakeProviders.GOOGLE_TOKEN);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("provider", "ONEDRIVE", "accountId", driveAccount.toString(), "accessToken", FakeProviders.GOOGLE_TOKEN,
                        "providerExpiresAt", Instant.now().plusSeconds(600).toString()),
                Map.of("provider", "GOOGLE_DRIVE", "accountId", "not-a-uuid", "accessToken", FakeProviders.GOOGLE_TOKEN,
                        "providerExpiresAt", Instant.now().plusSeconds(600).toString()),
                Map.of("provider", "GOOGLE_DRIVE", "accountId", driveAccount.toString(), "accessToken", "short",
                        "providerExpiresAt", Instant.now().plusSeconds(600).toString()),
                Map.of("provider", "DROPBOX", "accountId", boxAccount.toString(), "accessToken", FakeProviders.DROPBOX_TOKEN,
                        "providerExpiresAt", Instant.now().plusSeconds(600).toString()),
                Map.of("provider", "GOOGLE_DRIVE", "accountId", driveAccount.toString(), "accessToken", FakeProviders.GOOGLE_TOKEN,
                        "providerExpiresAt", Instant.now().plusSeconds(600).toString(), "refreshToken", "1//x"))) {
            assertThat(agent.post(port, "/local/v1/credentials", bad).statusCode()).isEqualTo(400);
        }
        HttpResponse<byte[]> expired = agent.post(port, "/local/v1/credentials", Map.of("provider", "GOOGLE_DRIVE",
                "accountId", driveAccount.toString(), "accessToken", FakeProviders.GOOGLE_TOKEN,
                "providerExpiresAt", Instant.now().plusSeconds(5).toString()));
        assertThat(code(expired)).isEqualTo("credential_expired");
    }

    @Test
    void handlesAreScopedPerAccountReplacedOnRedelegationAndClearedSelectively() throws Exception {
        providers.files.put("f1", bytes(100));
        providers.files.put("id:b1", bytes(100));
        String first = driveHandle();
        String second = driveHandle(); // re-delegation replaces the account's credential
        String box = boxHandle();

        assertThat(code(read(first, "f1", 0, 10, 100, null))).isEqualTo("unknown_credential");
        assertThat(read(second, "f1", 0, 10, 100, null).statusCode()).isEqualTo(200);
        // A Drive handle can't read Dropbox ids and vice versa.
        assertThat(code(read(second, "id:b1", 0, 10, 100, null))).isEqualTo("invalid_request");

        HttpResponse<byte[]> cleared = agent.post(port, "/local/v1/credentials/clear", Map.of("accountId", driveAccount.toString()));
        assertThat(AgentHarness.JSON.readTree(cleared.body()).get("cleared").asInt()).isEqualTo(1);
        assertThat(code(read(second, "f1", 0, 10, 100, null))).isEqualTo("unknown_credential");
        assertThat(read(box, "id:b1", 0, 10, 100, null).statusCode()).isEqualTo(200);

        agent.post(port, "/local/v1/credentials/clear", Map.of());
        assertThat(code(read(box, "id:b1", 0, 10, 100, null))).isEqualTo("unknown_credential");
    }

    // --- Ranges and EOF ----------------------------------------------------------------------

    @Test
    void exactRangeIsRequestedAndReturnedRaw() throws Exception {
        byte[] content = bytes(10_000);
        providers.files.put("f1", content);
        HttpResponse<byte[]> r = read(driveHandle(), "f1", 100, 500, 10_000, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).contains("application/octet-stream");
        assertThat(r.body()).isEqualTo(java.util.Arrays.copyOfRange(content, 100, 600));
        assertThat(r.headers().firstValue("X-HyperDrive-EOF")).contains("false");
        assertThat(r.headers().firstValue("X-HyperDrive-Revision-Pinned")).contains("false");
        FakeProviders.Seen seen = providers.seen.get(0);
        assertThat(seen.range()).isEqualTo("bytes=100-599");
        assertThat(seen.acceptEncoding()).isEqualTo("identity");
        assertThat(seen.path()).startsWith("/drive/v3/files/f1?alt=media");
        assertThat(agent.allOutput()).doesNotContain(FakeProviders.GOOGLE_TOKEN);
    }

    @Test
    void finalPartialRangeIsClampedAndMarksEof() throws Exception {
        byte[] content = bytes(10_000);
        providers.files.put("f1", content);
        HttpResponse<byte[]> r = read(driveHandle(), "f1", 9_900, 500, 10_000, null);
        assertThat(r.body()).isEqualTo(java.util.Arrays.copyOfRange(content, 9_900, 10_000));
        assertThat(r.headers().firstValue("X-HyperDrive-Read-Length")).contains("100");
        assertThat(r.headers().firstValue("X-HyperDrive-EOF")).contains("true");
        assertThat(providers.seen.get(0).range()).isEqualTo("bytes=9900-9999");
    }

    @Test
    void readsAtOrBeyondEofAndOfEmptyFilesReturnNothingWithoutAProviderCall() throws Exception {
        providers.files.put("f1", bytes(10));
        String h = driveHandle();
        for (long[] c : new long[][] {{10, 5, 10}, {11, 5, 10}, {0, 5, 0}}) {
            HttpResponse<byte[]> r = read(h, "f1", c[0], c[1], c[2], null);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEmpty();
            assertThat(r.headers().firstValue("X-HyperDrive-EOF")).contains("true");
        }
        assertThat(providers.seen).isEmpty();
    }

    @Test
    void boundsAndOverflowAreRejectedBeforeAnyProviderCall() throws Exception {
        providers.files.put("f1", bytes(10));
        String h = driveHandle();
        assertThat(code(read(h, "f1", 0, 0, 10, null))).isEqualTo("invalid_request");
        assertThat(code(read(h, "f1", 0, MIB4 + 1L, 10, null))).isEqualTo("invalid_request");
        assertThat(code(read(h, "f1", -1, 5, 10, null))).isEqualTo("invalid_request");
        assertThat(code(read(h, "f1", Long.MAX_VALUE - 1, 10, Long.MAX_VALUE, null))).isEqualTo("invalid_request");
        assertThat(code(read(h, "f1", 0, 5, -1, null))).isEqualTo("invalid_request");
        assertThat(code(read(h, "../etc", 0, 5, 10, null))).isEqualTo("invalid_request");
        assertThat(code(read(h, "f1?x=https://evil", 0, 5, 10, null))).isEqualTo("invalid_request");
        assertThat(providers.seen).isEmpty();
    }

    @Test
    void theFullFourMibIsAllowedAndExactlyThat() throws Exception {
        byte[] content = bytes(MIB4 + 1000);
        providers.files.put("big", content);
        HttpResponse<byte[]> r = read(driveHandle(), "big", 500, MIB4, content.length, null);
        assertThat(r.body()).hasSize(MIB4);
        assertThat(MessageDigest.getInstance("SHA-256").digest(r.body()))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(content, 500, 500 + MIB4)));
    }

    // --- Provider response validation --------------------------------------------------------

    @Test
    void wrongContentRangeLongerBodyAndTruncatedBodyAreRejected() throws Exception {
        String h = driveHandle();
        for (Map.Entry<FakeProviders.Mode, String> c : Map.of(
                FakeProviders.Mode.WRONG_RANGE, "invalid_provider_response",
                FakeProviders.Mode.LONGER_BODY, "invalid_provider_response",
                FakeProviders.Mode.TRUNCATED, "truncated_response",
                FakeProviders.Mode.GZIP, "invalid_provider_response").entrySet()) {
            String id = "m" + c.getKey().ordinal();
            providers.files.put(id, bytes(1000));
            providers.modes.put(id, c.getKey());
            assertThat(code(read(h, id, 10, 100, 1000, null))).as(c.getKey().name()).isEqualTo(c.getValue());
        }
    }

    @Test
    void anIgnoredRangeIsRefusedUnlessTheWholeSmallFileWasRequested() throws Exception {
        String h = driveHandle();
        providers.files.put("large", bytes(MIB4 * 2));
        providers.modes.put("large", FakeProviders.Mode.IGNORE_RANGE);
        long t0 = System.nanoTime();
        assertThat(code(read(h, "large", 100, 1000, MIB4 * 2L, null))).isEqualTo("range_not_supported");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("must not download the whole body").isLessThan(5000);

        byte[] small = bytes(50);
        providers.files.put("small", small);
        providers.modes.put("small", FakeProviders.Mode.IGNORE_RANGE);
        assertThat(read(h, "small", 0, 50, 50, null).body()).isEqualTo(small);
        assertThat(code(read(h, "small", 10, 20, 50, null))).isEqualTo("range_not_supported");
    }

    @Test
    void aChangedFileSizeIsReportedNotPatchedOver() throws Exception {
        providers.files.put("f1", bytes(1000));
        assertThat(code(read(driveHandle(), "f1", 0, 100, 900, null))).isEqualTo("file_changed");
    }

    @Test
    void providerErrorsAreClassifiedAndOnlyTransientOnesRetried() throws Exception {
        String h = driveHandle();
        providers.files.put("s", bytes(100));
        providers.modes.put("s", FakeProviders.Mode.STATUS);
        record Case(int status, String body, String retryAfter, String code) {
        }
        for (Case c : List.of(
                new Case(403, "{\"error\":{\"errors\":[{\"reason\":\"forbidden\"}]}}", null, "forbidden"),
                new Case(403, "{\"error\":{\"errors\":[{\"reason\":\"fileNotDownloadable\"}]}}", null, "unsupported_file"),
                new Case(404, "{}", null, "not_found"),
                new Case(429, "{}", "120", "rate_limited"))) {
            providers.seen.clear();
            providers.statusQueue.add(new Object[] {c.status(), c.body(), c.retryAfter()});
            HttpResponse<byte[]> r = read(h, "s", 0, 10, 100, null);
            assertThat(code(r)).as(c.code()).isEqualTo(c.code());
            assertThat(providers.seen).as("not retried: " + c.code()).hasSize(1);
        }
        assertThat(code(read(h, "s", 0, 10, 100, null))).isEmpty();

        providers.seen.clear();
        providers.statusQueue.add(new Object[] {429, "{}", "1"});
        providers.statusQueue.add(new Object[] {500, "{}", null});
        HttpResponse<byte[]> retried = read(h, "s", 0, 10, 100, null);
        assertThat(retried.statusCode()).as(new String(retried.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(providers.seen).hasSize(3);

        providers.seen.clear();
        for (int i = 0; i < 3; i++) {
            providers.statusQueue.add(new Object[] {503, "{}", null});
        }
        assertThat(code(read(h, "s", 0, 10, 100, null))).isEqualTo("unavailable");
        assertThat(providers.seen).hasSize(3);
    }

    @Test
    void aRejectedTokenIsReportedSoTheDesktopCanRedelegate() throws Exception {
        providers.files.put("f1", bytes(100));
        String h = install("GOOGLE_DRIVE", driveAccount, "ya29.some-other-token-that-google-rejects", Instant.now().plusSeconds(600), null);
        assertThat(code(read(h, "f1", 0, 10, 100, null))).isEqualTo("credential_rejected");
        assertThat(providers.seen).hasSize(1);
    }

    @Test
    void redirectsAreNeverFollowedSoTheTokenCantLeak() throws Exception {
        providers.files.put("r", bytes(100));
        providers.modes.put("r", FakeProviders.Mode.REDIRECT);
        assertThat(code(read(driveHandle(), "r", 0, 10, 100, null))).isEqualTo("redirect_refused");
        assertThat(providers.elsewhereAuthorization).isEmpty();
    }

    @Test
    void slowProvidersTimeOutAndClearingACredentialCancelsItsReads() throws Exception {
        providers.files.put("slow", bytes(100));
        providers.modes.put("slow", FakeProviders.Mode.SLOW);
        String h = driveHandle();
        assertThat(code(read(h, "slow", 0, 10, 100, null))).isEqualTo("timeout");

        providers.slowMillis = 10_000;
        CompletableFuture<HttpResponse<byte[]>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return read(h, "slow", 0, 10, 100, null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(300);
        long t0 = System.nanoTime();
        agent.post(port, "/local/v1/credentials/clear", Map.of("accountId", driveAccount.toString()));
        HttpResponse<byte[]> r = pending.get(5, TimeUnit.SECONDS);
        assertThat(code(r)).isIn("cancelled", "unknown_credential");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(3000);
    }

    @Test
    void perAccountConcurrencyIsBounded() throws Exception {
        providers.files.put("slow", bytes(100));
        providers.modes.put("slow", FakeProviders.Mode.SLOW);
        providers.slowMillis = 600;
        String h = driveHandle();
        List<CompletableFuture<String>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return code(read(h, "slow", 0, 10, 100, null));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        List<String> codes = calls.stream().map(CompletableFuture::join).toList();
        assertThat(codes).contains("too_busy");
    }

    // --- Dropbox -----------------------------------------------------------------------------

    @Test
    void dropboxReadsArePinnedToTheExpectedRevisionAndUseTheAccountRoot() throws Exception {
        byte[] content = bytes(5000);
        providers.files.put("id:b1", content);
        providers.dropboxRevs.put("id:b1", "0123456789abc");
        HttpResponse<byte[]> r = read(boxHandle(), "id:b1", 1000, 1000, 5000, "0123456789abc");

        assertThat(r.statusCode()).as(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(r.body()).isEqualTo(java.util.Arrays.copyOfRange(content, 1000, 2000));
        assertThat(r.headers().firstValue("X-HyperDrive-Revision-Pinned")).contains("true");
        assertThat(r.headers().firstValue("X-HyperDrive-Revision")).contains("0123456789abc");
        FakeProviders.Seen seen = providers.seen.get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.dropboxArg()).isEqualTo("{\"path\":\"rev:0123456789abc\"}");
        assertThat(seen.pathRoot()).isEqualTo("{\".tag\":\"root\",\"root\":\"777\"}");
        assertThat(seen.range()).isEqualTo("bytes=1000-1999");

        // A revision that no longer exists is not silently replaced by the current content.
        assertThat(code(read(boxHandle(), "id:b1", 0, 10, 5000, "fedcba9876543"))).isEqualTo("not_found");
        // Unpinned reads report the served revision.
        HttpResponse<byte[]> unpinned = read(boxHandle(), "id:b1", 0, 10, 5000, null);
        assertThat(unpinned.headers().firstValue("X-HyperDrive-Revision-Pinned")).contains("false");
        assertThat(unpinned.headers().firstValue("X-HyperDrive-Revision")).contains("0123456789abc");
    }

    @Test
    void dropboxMissingFilesAndRejectedTokensAreDistinct() throws Exception {
        assertThat(code(read(boxHandle(), "id:missing", 0, 10, 10, null))).isEqualTo("not_found");
        String bad = install("DROPBOX", UUID.randomUUID(), "sl.not-the-right-token-xx", Instant.now().plusSeconds(600), "777");
        providers.files.put("id:b2", bytes(10));
        assertThat(code(read(bad, "id:b2", 0, 10, 10, null))).isEqualTo("credential_rejected");
    }

    // --- Local API protections and lifecycle -------------------------------------------------

    @Test
    void readEndpointsKeepTheLocalApiProtections() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/local/v1/reads");
        String json = "{\"handle\":\"x\"}";
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + agent.secret)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + agent.secret)
                .header("Content-Type", "application/json").header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + agent.secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"handle\":\"" + "x".repeat(20_000) + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(agent.get(port, "/local/v1/reads", "Bearer " + agent.secret, Map.of()).statusCode()).isEqualTo(405);
    }

    @Test
    void statusReportsReadsButNoCacheOrDrive() throws Exception {
        JsonNode caps = AgentHarness.JSON.readTree(agent.get(port, "/local/v1/status", "Bearer " + agent.secret, Map.of()).body())
                .get("capabilities");
        assertThat(caps.get("providerReads").asBoolean()).isTrue();
        assertThat(caps.get("rangeReads").asBoolean()).isTrue();
        assertThat(caps.get("chunkCache").asBoolean()).isFalse();
        assertThat(caps.get("virtualDrive").asBoolean()).isFalse();
    }

    @Test
    void shutdownClearsCredentialsAndNothingIsPersisted() throws Exception {
        providers.files.put("f1", bytes(2000));
        read(driveHandle(), "f1", 0, 2000, 2000, null);
        agent.send("{\"type\":\"shutdown\"}");
        assertThat(agent.exitCode()).isZero();
        assertThat(agent.stderr.toString(StandardCharsets.UTF_8)).contains("Cleared 1 delegated credential(s) on shutdown");
        try (Stream<Path> files = Files.walk(temp)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
                    .containsExactly("agent.lock");
        }
        assertThat(agent.allOutput()).doesNotContain(FakeProviders.GOOGLE_TOKEN, FakeProviders.DROPBOX_TOKEN);
    }
}
