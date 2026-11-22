package com.hyperdrive.agent;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;

/**
 * Minimal structured logger writing single lines to stderr (stdout is reserved for the machine
 * protocol). Messages are fixed strings from this codebase plus sanitized values; the launch
 * secret is additionally redacted from every line as defense in depth. No log files are written.
 */
final class AgentLog {

    private final PrintStream err;
    private final Clock clock;
    private volatile Bootstrap.LogLevel threshold = Bootstrap.LogLevel.INFO;
    private volatile String secret;

    AgentLog(PrintStream err, Clock clock) {
        this.err = err;
        this.clock = clock;
    }

    void configure(Bootstrap.LogLevel level, String secretToRedact) {
        this.threshold = level;
        this.secret = secretToRedact;
    }

    void error(String message) {
        log(Bootstrap.LogLevel.ERROR, message);
    }

    void warn(String message) {
        log(Bootstrap.LogLevel.WARN, message);
    }

    void info(String message) {
        log(Bootstrap.LogLevel.INFO, message);
    }

    void debug(String message) {
        log(Bootstrap.LogLevel.DEBUG, message);
    }

    private void log(Bootstrap.LogLevel level, String message) {
        if (level.ordinal() > threshold.ordinal()) {
            return;
        }
        String text = message.replace('\n', ' ').replace('\r', ' ');
        String s = secret;
        if (s != null && !s.isEmpty()) {
            text = text.replace(s, "[redacted]");
        }
        synchronized (err) {
            err.println(Instant.now(clock) + " " + level + " [hyperdrive-agent] " + text);
            err.flush();
        }
    }
}
