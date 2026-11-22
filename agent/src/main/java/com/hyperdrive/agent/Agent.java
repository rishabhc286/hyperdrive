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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
                reads = new ReadService(credentials, new ProviderReader(endpoints, Duration.ofSeconds(5),
                        providerRequestTimeout, sleeper));
                server = LocalApiServer.start(bootstrap.secret(),
                        Map.of("/local/v1/health", () -> new LocalApiServer.Response.Json(200, health()),
                                "/local/v1/status", () -> new LocalApiServer.Response.Json(200, status())),
                        Map.of("/local/v1/credentials", this::installCredential,
                                "/local/v1/credentials/clear", this::clearCredentials,
                                "/local/v1/reads", this::read),
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
            int cleared = credentials.clear(null); // also cancels in-flight reads
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
        capabilities.put("chunkCache", false);
        capabilities.put("virtualDrive", false);
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
