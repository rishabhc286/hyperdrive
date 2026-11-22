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

    public Agent(InputStream stdin, PrintStream stdout, PrintStream stderr, Clock clock, Duration bootstrapTimeout) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.clock = clock;
        this.bootstrapTimeout = bootstrapTimeout;
        this.log = new AgentLog(stderr, clock);
        this.version = loadVersion();
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
                server = LocalApiServer.start(bootstrap.secret(), this::health, this::status, log);
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
        capabilities.put("providerReads", false);
        capabilities.put("rangeReads", false);
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
