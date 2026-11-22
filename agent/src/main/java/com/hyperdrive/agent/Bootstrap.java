package com.hyperdrive.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one-line launch message Electron writes to the agent's stdin. It carries the per-launch
 * secret, so it is never logged, echoed or written anywhere; {@link #toString()} is redacted.
 *
 * <pre>{"protocolVersion":1,"secret":"&lt;base64url, 256+ bits&gt;","dataDir":"C:\\...\\agent",
 *  "logLevel":"INFO","requestTimeoutMs":5000}</pre>
 *
 * Parsing fails closed: unknown fields, missing fields, a wrong protocol version, a short secret
 * or a relative data directory are all rejected.
 */
public record Bootstrap(int protocolVersion, String secret, Path dataDir, LogLevel logLevel, Duration requestTimeout) {

    public static final int PROTOCOL_VERSION = 1;
    /** Bootstrap line limit. A valid message is a few hundred bytes. */
    public static final int MAX_BYTES = 8 * 1024;
    /** 256 bits as base64url without padding is 43 characters. */
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");
    private static final Set<String> FIELDS = Set.of("protocolVersion", "secret", "dataDir", "logLevel", "requestTimeoutMs");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum LogLevel { ERROR, WARN, INFO, DEBUG }

    /** Why a bootstrap was rejected; the code is safe to report, the input is never included. */
    public static final class BootstrapException extends Exception {
        private final String code;

        public BootstrapException(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static Bootstrap parse(String line) throws BootstrapException {
        if (line == null) {
            throw new BootstrapException("bootstrap_missing", "No bootstrap message received");
        }
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (JacksonException e) {
            throw invalid("Bootstrap message is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid("Bootstrap message is not a JSON object");
        }
        for (String name : root.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw invalid("Bootstrap message has an unexpected field");
            }
        }
        JsonNode version = root.get("protocolVersion");
        if (version == null || !version.isIntegralNumber()) {
            throw invalid("Bootstrap message lacks protocolVersion");
        }
        if (version.asInt() != PROTOCOL_VERSION) {
            throw new BootstrapException("protocol_unsupported", "Unsupported protocol version");
        }
        JsonNode secret = root.get("secret");
        if (secret == null || !secret.isString() || !SECRET.matcher(secret.asString()).matches()) {
            throw invalid("Bootstrap secret is missing or too short");
        }
        JsonNode dir = root.get("dataDir");
        if (dir == null || !dir.isString() || dir.asString().isEmpty() || dir.asString().length() > 1024
                || dir.asString().indexOf('\0') >= 0) {
            throw invalid("Bootstrap dataDir is missing or invalid");
        }
        Path dataDir;
        try {
            dataDir = Path.of(dir.asString());
        } catch (InvalidPathException e) {
            throw invalid("Bootstrap dataDir is not a valid path");
        }
        if (!dataDir.isAbsolute()) {
            throw invalid("Bootstrap dataDir must be absolute");
        }
        LogLevel level = LogLevel.INFO;
        JsonNode levelNode = root.get("logLevel");
        if (levelNode != null) {
            try {
                level = LogLevel.valueOf(levelNode.asString(""));
            } catch (IllegalArgumentException e) {
                throw invalid("Bootstrap logLevel is invalid");
            }
        }
        Duration timeout = Duration.ofSeconds(5);
        JsonNode timeoutNode = root.get("requestTimeoutMs");
        if (timeoutNode != null) {
            if (!timeoutNode.isIntegralNumber() || timeoutNode.asLong() < 1000 || timeoutNode.asLong() > 60000) {
                throw invalid("Bootstrap requestTimeoutMs must be 1000-60000");
            }
            timeout = Duration.ofMillis(timeoutNode.asLong());
        }
        return new Bootstrap(PROTOCOL_VERSION, secret.asString(), dataDir.normalize(), level, timeout);
    }

    private static BootstrapException invalid(String message) {
        return new BootstrapException("bootstrap_invalid", message);
    }

    @Override
    public String toString() {
        return "Bootstrap[protocolVersion=" + protocolVersion + ", logLevel=" + logLevel + ", secret=redacted]";
    }
}
