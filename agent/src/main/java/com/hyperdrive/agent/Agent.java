package com.hyperdrive.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.HashMap;
import java.util.Set;
import java.time.format.DateTimeParseException;
import com.hyperdrive.agent.vfs.BackendMetadataClient;
import com.hyperdrive.agent.vfs.CredentialBroker;
import com.hyperdrive.agent.vfs.VfsReadService;
import com.hyperdrive.agent.vfs.MetadataSource;
import com.hyperdrive.agent.vfs.MountRegistry;
import com.hyperdrive.agent.vfs.VfsMetadataService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.hyperdrive.agent.read.ChunkCache;
import com.hyperdrive.agent.read.CredentialRegistry;
import com.hyperdrive.agent.read.ProviderEndpoints;
import com.hyperdrive.agent.read.ProviderReader;
import com.hyperdrive.agent.read.ReadException;
import com.hyperdrive.agent.read.ReadService;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent process lifecycle. Streams are injected so the whole protocol is testable in-process.
 *
 * <ol>
 *   <li>Read exactly one bounded bootstrap line from stdin (with a timeout). Fail closed.</li>
 *   <li>Lock the data directory, start the loopback API.</li>
 *   <li>Write one {@code {"type":"ready",...}} line to stdout (or {@code {"type":"error","code":...}}).
 *       stdout carries nothing else; logs go to stderr.</li>
 *   <li>Keep reading stdin as the parent-lifetime control channel: {@code {"type":"shutdown"}} or
 *       end-of-stream (the owning desktop process went away) triggers a graceful shutdown.</li>
 * </ol>
 */
public final class Agent {

    public static final int EXIT_OK = 0;
    public static final int EXIT_BOOTSTRAP = 2;
    public static final int EXIT_DATA_DIR = 3;
    public static final int EXIT_SERVER = 4;

    private static final int MAX_CONTROL_LINE = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InputStream stdin;
    private final PrintStream stdout;
    private final Clock clock;
    private final Duration bootstrapTimeout;
    private final AgentLog log;
    private final String version;
    private final String instanceId = UUID.randomUUID().toString();
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile String shutdownReason = "unknown";
    private volatile Instant startedAt;
    private volatile int port = -1;
    private final ProviderEndpoints endpoints;
    private final ProviderReader.Sleeper sleeper;
    private final Duration providerRequestTimeout;
    private final CredentialRegistry credentials;
    private ReadService reads;
    private ChunkCache cache;
    /** Batch 8: virtual-drive mount capability and the backend metadata grant (memory only). */
    private final MountRegistry mounts;
    private volatile MetadataSource metadataSource;
    private VfsMetadataService vfs;
    /** Batch 9: mounted content reads (agent-owned read contexts) and their credential renewal. */
    private CredentialBroker broker;
    private VfsReadService vfsReads;

    public Agent(InputStream stdin, PrintStream stdout, PrintStream stderr, Clock clock, Duration bootstrapTimeout) {
        this(stdin, stdout, stderr, clock, bootstrapTimeout, ProviderEndpoints.PRODUCTION, d -> Thread.sleep(d),
                Duration.ofSeconds(30));
    }

    /** Endpoint injection exists only for in-process tests; the production entry point can't set it. */
    Agent(InputStream stdin, PrintStream stdout, PrintStream stderr, Clock clock, Duration bootstrapTimeout,
            ProviderEndpoints endpoints, ProviderReader.Sleeper sleeper, Duration providerRequestTimeout) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.clock = clock;
        this.bootstrapTimeout = bootstrapTimeout;
        this.log = new AgentLog(stderr, clock);
        this.version = loadVersion();
        this.endpoints = endpoints;
        this.sleeper = sleeper;
        this.providerRequestTimeout = providerRequestTimeout;
        this.credentials = new CredentialRegistry(clock);
        this.mounts = new MountRegistry(clock);
        this.metadataSource = new BackendMetadataClient(Duration.ofSeconds(5), Duration.ofSeconds(20), clock);
    }

    /** In-process tests only: a fake backend metadata source. */
    void useMetadataSource(MetadataSource source) {
        this.metadataSource = source;
    }

    /** Runs until shutdown; returns the process exit code. */
    public int run() {
        BoundedLineReader reader = new BoundedLineReader(stdin, Bootstrap.MAX_BYTES);
        Bootstrap bootstrap;
        try {
            bootstrap = Bootstrap.parse(readBootstrapLine(reader));
        } catch (Bootstrap.BootstrapException e) {
            // Never include the received input; only the stable code and a fixed message.
            log.error("Bootstrap rejected (" + e.code() + "): " + e.getMessage());
            emit(Map.of("type", "error", "code", e.code()));
            return EXIT_BOOTSTRAP;
        }
        log.configure(bootstrap.logLevel(), bootstrap.secret());

        // Server-side limits on the JDK HTTP server (seconds; read once when the server class initializes).
        long seconds = Math.max(1, bootstrap.requestTimeout().toSeconds());
        System.setProperty("sun.net.httpserver.maxReqTime", Long.toString(seconds));
        System.setProperty("sun.net.httpserver.maxRspTime", Long.toString(seconds));
        System.setProperty("sun.net.httpserver.maxReqHeaders", "64");

        DataDirectoryLock lock;
        try {
            lock = DataDirectoryLock.acquire(bootstrap.dataDir());
        } catch (DataDirectoryLock.LockedException e) {
            log.error("Agent data directory is already in use by another agent");
            emit(Map.of("type", "error", "code", "data_dir_locked"));
            return EXIT_DATA_DIR;
        } catch (IOException | SecurityException e) {
            log.error("Agent data directory could not be prepared: " + e.getClass().getSimpleName());
            emit(Map.of("type", "error", "code", "data_dir_unusable"));
            return EXIT_DATA_DIR;
        }

        LocalApiServer server;
        try (lock) {
            startedAt = clock.instant();
            try {
                ProviderReader provider = new ProviderReader(endpoints, Duration.ofSeconds(5), providerRequestTimeout,
                        sleeper);
                // Encrypted chunk cache under the agent's own data directory (inert until a key arrives).
                cache = new ChunkCache(bootstrap.dataDir().resolve("cache"), provider, log::info);
                reads = new ReadService(credentials, provider, cache);
                vfs = new VfsMetadataService(metadataSource, clock);
                broker = new CredentialBroker(credentials, clock);
                vfsReads = new VfsReadService(reads, broker, metadataSource, clock);
                Map<String, LocalApiServer.PostRoute> posts = new HashMap<>();
                posts.put("/local/v1/credentials", this::installCredential);
                posts.put("/local/v1/credentials/clear", this::clearCredentials);
                posts.put("/local/v1/reads", this::read);
                posts.put("/local/v1/cache/session", this::cacheSession);
                posts.put("/local/v1/cache/session/end", this::cacheSessionEnd);
                posts.put("/local/v1/cache/budget", this::cacheBudget);
                posts.put("/local/v1/cache/clear", this::cacheClear);
                // Batch 8 administration (launch secret): create/renew/revoke the virtual-drive mount.
                posts.put("/local/v1/vfs/mount", this::vfsMount);
                posts.put("/local/v1/vfs/mount/grant", this::vfsGrant);
                posts.put("/local/v1/vfs/mount/revoke", this::vfsRevoke);
                // Batch 9 administration: credential renewal for mounted reads (Electron main only).
                posts.put("/local/v1/vfs/credential-requests", this::vfsCredentialRequests);
                posts.put("/local/v1/vfs/mount/credential", this::vfsInstallCredential);
                posts.put("/local/v1/vfs/mount/credential-failed", this::vfsCredentialFailed);
                server = LocalApiServer.start(bootstrap.secret(),
                        Map.of("/local/v1/health", () -> new LocalApiServer.Response.Json(200, health()),
                                "/local/v1/status", () -> new LocalApiServer.Response.Json(200, status()),
                                "/local/v1/cache/status", () -> new LocalApiServer.Response.Json(200, cacheStatus()),
                                "/local/v1/vfs/status", () -> new LocalApiServer.Response.Json(200, vfsStatus())),
                        posts,
                        // Batch 8 VFS routes: mount capability only, read-only metadata.
                        Map.of("/local/v1/vfs/session", this::vfsSession,
                                "/local/v1/vfs/accounts", this::vfsAccounts,
                                "/local/v1/vfs/list", this::vfsList,
                                // Batch 9: content through opaque, agent-owned read handles.
                                "/local/v1/vfs/open", this::vfsOpen,
                                "/local/v1/vfs/read", this::vfsRead,
                                "/local/v1/vfs/close", this::vfsClose,
                                "/local/v1/vfs/cancel", this::vfsCancel),
                        mounts::authenticate,
                        log);
            } catch (IOException e) {
                log.error("Local API could not bind to 127.0.0.1: " + e.getClass().getSimpleName());
                emit(Map.of("type", "error", "code", "bind_failed"));
                return EXIT_SERVER;
            }
            port = server.port();
            startControlChannel();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> requestShutdown("jvm_shutdown"), "agent-shutdown-hook"));

            Map<String, Object> ready = new LinkedHashMap<>();
            ready.put("type", "ready");
            ready.put("protocolVersion", Bootstrap.PROTOCOL_VERSION);
            ready.put("port", port);
            ready.put("instanceId", instanceId);
            ready.put("agentVersion", version);
            emit(ready);
            log.info("Agent ready on 127.0.0.1 (protocol " + Bootstrap.PROTOCOL_VERSION + ", version " + version + ")");

            awaitShutdown();
            log.info("Agent shutting down (" + shutdownReason + ")");
            mounts.revoke(); // the VFS capability dies with the agent
            broker.reset(-1);
            vfsReads.shutdown();
            int cleared = credentials.clear(null); // also cancels in-flight reads
            if (cache != null) {
                cache.shutdown(); // drops the in-memory key; encrypted files stay for the next session
            }
            if (cleared > 0) {
                log.info("Cleared " + cleared + " delegated credential(s) on shutdown");
            }
            server.stop();
        }
        stopped.set(true);
        log.info("Agent stopped");
        return EXIT_OK;
    }

    /** Asks the agent to stop; idempotent. */
    public void requestShutdown(String reason) {
        if (shutdown.getCount() > 0) {
            shutdownReason = reason;
            shutdown.countDown();
        }
    }

    public boolean isStopped() {
        return stopped.get();
    }

    int port() {
        return port;
    }

    private String readBootstrapLine(BoundedLineReader reader) throws Bootstrap.BootstrapException {
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (BoundedLineReader.LineTooLongException e) {
                throw new IllegalStateException("too_long");
            } catch (IOException e) {
                throw new IllegalStateException("io");
            }
        }, runnable -> {
            Thread t = new Thread(runnable, "agent-bootstrap");
            t.setDaemon(true);
            t.start();
        });
        try {
            return line.get(bootstrapTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Bootstrap.BootstrapException("bootstrap_timeout", "No bootstrap message within the timeout");
        } catch (ExecutionException e) {
            throw new Bootstrap.BootstrapException("bootstrap_invalid",
                    "too_long".equals(e.getCause().getMessage()) ? "Bootstrap message is too large"
                            : "Bootstrap message could not be read");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Bootstrap.BootstrapException("bootstrap_invalid", "Interrupted while reading bootstrap");
        }
    }

    /** After bootstrap, stdin stays open as the parent-lifetime channel. */
    private void startControlChannel() {
        BoundedLineReader control = new BoundedLineReader(stdin, MAX_CONTROL_LINE);
        Thread thread = new Thread(() -> {
            while (true) {
                String line;
                try {
                    line = control.readLine();
                } catch (BoundedLineReader.LineTooLongException e) {
                    log.warn("Ignored an oversized control message");
                    continue;
                } catch (IOException e) {
                    requestShutdown("control_channel_error");
                    return;
                }
                if (line == null) {
                    requestShutdown("parent_closed");
                    return;
                }
                if (isShutdownMessage(line)) {
                    requestShutdown("shutdown_requested");
                    return;
                }
                log.warn("Ignored an unknown control message");
            }
        }, "agent-control");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isShutdownMessage(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            return node != null && node.isObject() && node.size() == 1 && "shutdown".equals(node.path("type").asString(""));
        } catch (JacksonException e) {
            return false;
        }
    }

    private void awaitShutdown() {
        try {
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownReason = "interrupted";
        }
    }

    private Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    /**
     * Truthful status. Capabilities report what this build implements, which today is nothing in
     * the data plane; there are deliberately no throughput, cache or mount figures.
     */
    private Map<String, Object> status() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        // Batch 6: bounded provider range reads with delegated access tokens (no cache, no drive).
        capabilities.put("providerReads", true);
        capabilities.put("rangeReads", true);
        capabilities.put("chunkCache", true);
        capabilities.put("virtualDrive", true); // Batch 8: mount support
        capabilities.put("virtualDriveContent", true); // Batch 9: read-only content through the mount
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", Bootstrap.PROTOCOL_VERSION);
        body.put("agentVersion", version);
        body.put("instanceId", instanceId);
        body.put("readiness", "READY");
        body.put("startedAt", startedAt.toString());
        body.put("uptimeMillis", Math.max(0, Duration.between(startedAt, clock.instant()).toMillis()));
        body.put("capabilities", capabilities);
        body.put("lastStartupError", null);
        return body;
    }

    // --- Delegated credentials and reads (Batch 6) ---------------------------------------------

    private static final java.util.Set<String> INSTALL_FIELDS =
            java.util.Set.of("provider", "accountId", "accessToken", "providerExpiresAt", "rootNamespaceId");
    private static final java.util.Set<String> READ_FIELDS =
            java.util.Set.of("handle", "fileId", "offset", "length", "expectedSize", "expectedRevision");

    private LocalApiServer.Response installCredential(JsonNode body) {
        try {
            if (!onlyFields(body, INSTALL_FIELDS)) {
                throw new ReadException("invalid_request", 400);
            }
            CredentialRegistry.Provider provider = CredentialRegistry.Provider.valueOf(body.path("provider").asString(""));
            UUID accountId = UUID.fromString(body.path("accountId").asString(""));
            Instant expires = Instant.parse(body.path("providerExpiresAt").asString(""));
            JsonNode ns = body.get("rootNamespaceId");
            CredentialRegistry.Installed installed = credentials.install(provider, accountId,
                    body.path("accessToken").asString(null), expires, ns == null || ns.isNull() ? null : ns.asString(null));
            log.info("Installed a delegated " + provider + " credential for account " + accountId
                    + " (retained until " + installed.retentionExpiresAt() + ")");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("handle", installed.handle());
            out.put("retentionExpiresAt", installed.retentionExpiresAt().toString());
            out.put("providerExpiresAt", installed.providerExpiresAt().toString());
            return new LocalApiServer.Response.Json(201, out);
        } catch (ReadException e) {
            return new LocalApiServer.Response.Json(e.httpStatus(), LocalApiServer.error(e.code()));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
    }

    private LocalApiServer.Response clearCredentials(JsonNode body) {
        if (!onlyFields(body, java.util.Set.of("accountId"))) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
        UUID accountId = null;
        JsonNode id = body.get("accountId");
        if (id != null && !id.isNull()) {
            try {
                accountId = UUID.fromString(id.asString(""));
            } catch (IllegalArgumentException e) {
                return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
            }
        }
        int cleared = credentials.clear(accountId);
        if (accountId != null) {
            // Disconnect: that account's mounted read handles end too (the drive is also unmounted).
            int dropped = vfsReads.dropAccount(accountId);
            broker.forget(accountId);
            if (dropped > 0) {
                log.info("Closed " + dropped + " mounted read handle(s) for account " + accountId);
            }
        }
        log.info("Cleared " + cleared + " delegated credential(s)" + (accountId == null ? "" : " for account " + accountId));
        return new LocalApiServer.Response.Json(200, Map.of("cleared", cleared));
    }

    private LocalApiServer.Response read(JsonNode body) {
        try {
            if (!onlyFields(body, READ_FIELDS) || !body.path("offset").isIntegralNumber() || !body.path("length").isIntegralNumber()
                    || !body.path("expectedSize").isIntegralNumber() || !body.path("offset").canConvertToLong()
                    || !body.path("expectedSize").canConvertToLong()) {
                throw new ReadException("invalid_request", 400);
            }
            JsonNode rev = body.get("expectedRevision");
            ReadService.ReadResult result = reads.read(new ReadService.ReadRequest(body.path("handle").asString(null),
                    body.path("fileId").asString(null), body.path("offset").asLong(), body.path("length").asLong(),
                    body.path("expectedSize").asLong(), rev == null || rev.isNull() ? null : rev.asString(null)));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-HyperDrive-Read-Offset", Long.toString(result.offset()));
            headers.put("X-HyperDrive-Read-Length", Integer.toString(result.length()));
            headers.put("X-HyperDrive-EOF", Boolean.toString(result.eof()));
            headers.put("X-HyperDrive-Revision-Pinned", Boolean.toString(result.revisionPinned()));
            if (result.revision() != null) {
                headers.put("X-HyperDrive-Revision", result.revision());
            }
            headers.put("X-HyperDrive-Cache", result.cache().name().toLowerCase(java.util.Locale.ROOT));
            if (result.bypassReason() != null) {
                headers.put("X-HyperDrive-Cache-Bypass", result.bypassReason());
            }
            if (result.notAdmittedReason() != null) {
                headers.put("X-HyperDrive-Cache-Not-Admitted", result.notAdmittedReason());
            }
            headers.put("X-HyperDrive-Provider-Bytes", Long.toString(result.providerBytes()));
            headers.put("X-HyperDrive-Cache-Bytes", Long.toString(result.cacheBytes()));
            return new LocalApiServer.Response.Binary(200, result.data(), result.length(), headers);
        } catch (ReadException e) {
            log.info("Read failed: " + e.code());
            Map<String, Object> out = new LinkedHashMap<>(LocalApiServer.error(e.code()));
            if (e.retryAfter() != null) {
                out.put("retryAfterSeconds", e.retryAfter().toSeconds());
            }
            return new LocalApiServer.Response.Json(e.httpStatus(), out);
        }
    }

    // --- Chunk cache (Batch 7) -----------------------------------------------------------------

    private Map<String, Object> cacheStatus() {
        ChunkCache.Status st = cache.status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", st.enabled());
        out.put("disabledReason", st.disabledReason());
        out.put("budgetBytes", st.store().budget());
        out.put("encryptedBytesOnDisk", st.store().encryptedBytes());
        out.put("tempBytes", st.store().tempBytes());
        out.put("entryCount", st.store().entries());
        out.put("overBudgetBytes", st.store().overBudget());
        out.put("startupRemovedFiles", st.store().startupRemoved());
        out.put("counters", st.counters());
        out.put("bypasses", st.bypasses());
        var last = st.store().lastCleanup();
        out.put("lastCleanup", last == null ? null
                : Map.of("scope", last.scope(), "removed", last.removed(), "failed", last.failed()));
        return out;
    }

    private static Map<String, Object> purgeJson(com.hyperdrive.agent.read.ChunkStore.PurgeResult p) {
        return Map.of("removed", p.removed(), "failed", p.failed());
    }

    private LocalApiServer.Response cacheSession(JsonNode body) {
        if (!onlyFields(body, java.util.Set.of("userId", "key", "fresh", "budgetBytes")) || !body.path("fresh").isBoolean()
                || !body.path("budgetBytes").isIntegralNumber() || !body.path("budgetBytes").canConvertToLong()) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
        byte[] key = null;
        try {
            UUID userId = UUID.fromString(body.path("userId").asString(""));
            String encoded = body.path("key").asString("");
            if (!encoded.matches("^[A-Za-z0-9+/]{43}=$")) {
                throw new IllegalArgumentException("key");
            }
            key = java.util.Base64.getDecoder().decode(encoded);
            cache.setBudget(body.path("budgetBytes").asLong());
            var others = cache.startSession(userId, key, body.path("fresh").asBoolean());
            log.info("Cache session started" + (others.removed() + others.failed() > 0
                    ? " (removed " + others.removed() + " leftover file(s), " + others.failed() + " failed)" : ""));
            return new LocalApiServer.Response.Json(200, cacheStatus());
        } catch (ReadException e) {
            return new LocalApiServer.Response.Json(e.httpStatus(), LocalApiServer.error(e.code()));
        } catch (IllegalArgumentException e) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        } finally {
            if (key != null) {
                java.util.Arrays.fill(key, (byte) 0); // best effort; derived keys live in the session
            }
        }
    }

    private LocalApiServer.Response cacheSessionEnd(JsonNode body) {
        if (!onlyFields(body, java.util.Set.of("userId"))) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
        try {
            var result = cache.endSession(UUID.fromString(body.path("userId").asString("")));
            log.info("Cache session ended: removed " + result.removed() + " file(s), " + result.failed() + " failed");
            return new LocalApiServer.Response.Json(200, purgeJson(result));
        } catch (IllegalArgumentException e) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
    }

    private LocalApiServer.Response cacheBudget(JsonNode body) {
        if (!onlyFields(body, java.util.Set.of("budgetBytes")) || !body.path("budgetBytes").isIntegralNumber()
                || !body.path("budgetBytes").canConvertToLong()) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
        try {
            cache.setBudget(body.path("budgetBytes").asLong());
            return new LocalApiServer.Response.Json(200, cacheStatus());
        } catch (ReadException e) {
            return new LocalApiServer.Response.Json(e.httpStatus(), LocalApiServer.error(e.code()));
        }
    }

    private LocalApiServer.Response cacheClear(JsonNode body) {
        if (!onlyFields(body, java.util.Set.of("accountId"))) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
        try {
            JsonNode id = body.get("accountId");
            UUID accountId = id == null || id.isNull() ? null : UUID.fromString(id.asString(""));
            var result = cache.clear(accountId);
            log.info("Cache cleared" + (accountId == null ? "" : " for account " + accountId) + ": removed "
                    + result.removed() + " file(s), " + result.failed() + " failed");
            return new LocalApiServer.Response.Json(200, purgeJson(result));
        } catch (ReadException e) {
            return new LocalApiServer.Response.Json(e.httpStatus(), LocalApiServer.error(e.code()));
        } catch (IllegalArgumentException e) {
            return new LocalApiServer.Response.Json(400, LocalApiServer.error("invalid_request"));
        }
    }

    // --- Batch 8: virtual drive ------------------------------------------------------------------

    private LocalApiServer.Response vfsMount(JsonNode body) {
        if (!onlyFields(body, Set.of("userId", "backendOrigin", "grant", "grantExpiresAt"))) {
            return badRequest("invalid_request");
        }
        try {
            UUID userId = UUID.fromString(body.path("userId").asString(""));
            Instant expires = Instant.parse(body.path("grantExpiresAt").asString(""));
            MountRegistry.Created created = mounts.create(userId, body.path("backendOrigin").asString(null),
                    body.path("grant").asString(null), expires);
            // A new mount ends every context and renewal of the previous one.
            vfsReads.dropAll();
            broker.reset(created.generation());
            log.info("Virtual drive mount capability issued (generation " + created.generation() + ")");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generation", created.generation());
            out.put("capability", created.capability());
            return new LocalApiServer.Response.Json(201, out);
        } catch (MountRegistry.InvalidMountException e) {
            return badRequest(e.code());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return badRequest("invalid_request");
        }
    }

    private LocalApiServer.Response vfsGrant(JsonNode body) {
        if (!onlyFields(body, Set.of("generation", "grant", "grantExpiresAt"))
                || !body.path("generation").isIntegralNumber()) {
            return badRequest("invalid_request");
        }
        try {
            mounts.renewGrant(body.get("generation").asLong(), body.path("grant").asString(null),
                    Instant.parse(body.path("grantExpiresAt").asString("")));
            return new LocalApiServer.Response.Json(200, Map.of("renewed", true));
        } catch (MountRegistry.InvalidMountException e) {
            return new LocalApiServer.Response.Json("mount_not_found".equals(e.code()) ? 404 : 400,
                    LocalApiServer.error(e.code()));
        } catch (DateTimeParseException e) {
            return badRequest("invalid_request");
        }
    }

    private LocalApiServer.Response vfsRevoke(JsonNode body) {
        if (!onlyFields(body, Set.of())) {
            return badRequest("invalid_request");
        }
        boolean revoked = mounts.revoke();
        int dropped = vfsReads.dropAll(); // cancels in-flight mounted reads
        broker.reset(-1);
        if (dropped > 0) {
            log.info("Closed " + dropped + " mounted read handle(s)");
        }
        if (revoked) {
            log.info("Virtual drive mount capability revoked");
        }
        return new LocalApiServer.Response.Json(200, Map.of("revoked", revoked));
    }

    private Map<String, Object> vfsStatus() {
        MountRegistry.Mount mount = mounts.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", mount != null);
        out.put("generation", mount == null ? null : mount.generation());
        out.put("reads", vfsReads.stats());
        return out;
    }

    /** VFS hello/heartbeat: proves the capability is (still) valid. */
    private LocalApiServer.Response vfsSession(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of())) {
            return badRequest("invalid_request");
        }
        return new LocalApiServer.Response.Json(200, Map.of("protocolVersion", 1, "generation", mount.generation()));
    }

    private LocalApiServer.Response vfsAccounts(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of())) {
            return badRequest("invalid_request");
        }
        try {
            return new LocalApiServer.Response.Json(200, vfs.accounts(mount));
        } catch (MetadataSource.MetadataException e) {
            return new LocalApiServer.Response.Json(e.status(), LocalApiServer.error(e.code()));
        }
    }

    private LocalApiServer.Response vfsList(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of("accountId", "directory"))) {
            return badRequest("invalid_request");
        }
        try {
            UUID accountId = UUID.fromString(body.path("accountId").asString(""));
            JsonNode dir = body.get("directory");
            if (dir != null && !dir.isNull() && !dir.isString()) {
                return badRequest("invalid_request");
            }
            String directory = dir == null || dir.isNull() ? null : dir.asString();
            return new LocalApiServer.Response.Json(200, vfs.list(mount, accountId, directory));
        } catch (MetadataSource.MetadataException e) {
            return new LocalApiServer.Response.Json(e.status(), LocalApiServer.error(e.code()));
        } catch (IllegalArgumentException e) {
            return badRequest("invalid_request");
        }
    }

    // --- Batch 9: mounted content reads (capability routes) --------------------------------------

    private static LocalApiServer.Response readError(ReadException e) {
        Map<String, Object> out = new LinkedHashMap<>(LocalApiServer.error(e.code()));
        if (e.retryAfter() != null) {
            out.put("retryAfterSeconds", e.retryAfter().toSeconds());
        }
        return new LocalApiServer.Response.Json(e.httpStatus(), out);
    }

    private LocalApiServer.Response vfsOpen(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of("accountId", "fileId", "expectedSize")) || !body.path("fileId").isString()
                || !body.path("expectedSize").isIntegralNumber() || !body.path("expectedSize").canConvertToLong()) {
            return badRequest("invalid_request");
        }
        try {
            VfsReadService.Opened opened = vfsReads.open(mount, UUID.fromString(body.path("accountId").asString("")),
                    body.path("fileId").asString(), body.path("expectedSize").asLong());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("handle", opened.handle());
            out.put("sizeBytes", opened.sizeBytes());
            out.put("consistency", opened.consistency());
            out.put("revisionPinned", opened.revisionPinned());
            return new LocalApiServer.Response.Json(201, out);
        } catch (ReadException e) {
            log.info("Mounted open failed: " + e.code());
            return readError(e);
        } catch (IllegalArgumentException e) {
            return badRequest("invalid_request");
        }
    }

    private LocalApiServer.Response vfsRead(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of("handle", "offset", "length")) || !body.path("handle").isString()
                || !body.path("offset").isIntegralNumber() || !body.path("offset").canConvertToLong()
                || !body.path("length").isIntegralNumber() || !body.path("length").canConvertToLong()) {
            return badRequest("invalid_request");
        }
        try {
            ReadService.ReadResult result = vfsReads.read(mount, body.path("handle").asString(),
                    body.path("offset").asLong(), body.path("length").asLong());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-HyperDrive-Read-Offset", Long.toString(result.offset()));
            headers.put("X-HyperDrive-Read-Length", Integer.toString(result.length()));
            headers.put("X-HyperDrive-EOF", Boolean.toString(result.eof()));
            headers.put("X-HyperDrive-Cache", result.cache().name().toLowerCase(java.util.Locale.ROOT));
            return new LocalApiServer.Response.Binary(200, result.data(), result.length(), headers);
        } catch (ReadException e) {
            log.info("Mounted read failed: " + e.code());
            return readError(e);
        }
    }

    private LocalApiServer.Response vfsClose(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of("handle")) || !body.path("handle").isString()) {
            return badRequest("invalid_request");
        }
        return new LocalApiServer.Response.Json(200, Map.of("closed", vfsReads.close(mount, body.path("handle").asString())));
    }

    private LocalApiServer.Response vfsCancel(MountRegistry.Mount mount, JsonNode body) {
        if (!onlyFields(body, Set.of())) {
            return badRequest("invalid_request");
        }
        int cancelled = vfsReads.cancelInFlight(mount);
        if (cancelled > 0) {
            log.info("Cancelled " + cancelled + " in-flight mounted read(s) (unmounting)");
        }
        return new LocalApiServer.Response.Json(200, Map.of("cancelled", cancelled));
    }

    // --- Batch 9: credential renewal for mounted reads (administration, launch secret) -----------

    private LocalApiServer.Response vfsCredentialRequests(JsonNode body) {
        if (!onlyFields(body, Set.of("generation")) || !body.path("generation").isIntegralNumber()) {
            return badRequest("invalid_request");
        }
        try {
            java.util.List<UUID> due = broker.poll(body.get("generation").asLong(), CredentialBroker.MAX_POLL);
            return new LocalApiServer.Response.Json(200,
                    Map.of("requests", due.stream().map(id -> Map.of("accountId", id.toString())).toList()));
        } catch (ReadException e) {
            return readError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LocalApiServer.Response.Json(503, LocalApiServer.error("unavailable"));
        }
    }

    private LocalApiServer.Response vfsInstallCredential(JsonNode body) {
        if (!onlyFields(body, Set.of("generation", "provider", "accountId", "accessToken", "providerExpiresAt",
                "rootNamespaceId")) || !body.path("generation").isIntegralNumber()) {
            return badRequest("invalid_request");
        }
        try {
            CredentialRegistry.Provider provider = CredentialRegistry.Provider.valueOf(body.path("provider").asString(""));
            UUID accountId = UUID.fromString(body.path("accountId").asString(""));
            JsonNode ns = body.get("rootNamespaceId");
            CredentialRegistry.Installed installed = broker.install(body.get("generation").asLong(), provider, accountId,
                    body.path("accessToken").asString(null), Instant.parse(body.path("providerExpiresAt").asString("")),
                    ns == null || ns.isNull() ? null : ns.asString(null));
            log.info("Installed a renewed " + provider + " credential for mounted reads of account " + accountId);
            // The credential handle stays inside the agent; Electron only learns that it worked.
            return new LocalApiServer.Response.Json(201,
                    Map.of("installed", true, "retentionExpiresAt", installed.retentionExpiresAt().toString()));
        } catch (ReadException e) {
            return readError(e);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return badRequest("invalid_request");
        }
    }

    private LocalApiServer.Response vfsCredentialFailed(JsonNode body) {
        if (!onlyFields(body, Set.of("generation", "accountId", "code")) || !body.path("generation").isIntegralNumber()
                || !body.path("code").isString()) {
            return badRequest("invalid_request");
        }
        try {
            UUID accountId = UUID.fromString(body.path("accountId").asString(""));
            String code = body.path("code").asString();
            broker.fail(body.get("generation").asLong(), accountId, code);
            log.info("Credential renewal for account " + accountId + " failed: " + code);
            return new LocalApiServer.Response.Json(200, Map.of("recorded", true));
        } catch (ReadException e) {
            return readError(e);
        } catch (IllegalArgumentException e) {
            return badRequest("invalid_request");
        }
    }

    private static LocalApiServer.Response badRequest(String code) {
        return new LocalApiServer.Response.Json(400, LocalApiServer.error(code));
    }

    private static boolean onlyFields(JsonNode body, java.util.Set<String> allowed) {
        for (String name : body.propertyNames()) {
            if (!allowed.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private void emit(Map<String, Object> message) {
        synchronized (stdout) {
            stdout.println(JSON.writeValueAsString(message));
            stdout.flush();
        }
    }

    private static String loadVersion() {
        Properties properties = new Properties();
        try (InputStream in = Agent.class.getResourceAsStream("/hyperdrive-agent.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // Fall through to "unknown".
        }
        String v = properties.getProperty("agent.version", "unknown");
        return v.matches("[0-9A-Za-z.+-]{1,64}") ? v : "unknown";
    }
}
