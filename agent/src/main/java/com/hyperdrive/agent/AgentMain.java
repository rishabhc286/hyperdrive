package com.hyperdrive.agent;

import java.time.Clock;
import java.time.Duration;

/**
 * Entry point: {@code java -jar hyperdrive-agent.jar}. Takes no arguments and reads no
 * environment variables; all configuration arrives in the stdin bootstrap message, so secrets
 * never appear on the command line.
 */
public final class AgentMain {

    /** How long to wait for the bootstrap message before failing closed. */
    static final Duration BOOTSTRAP_TIMEOUT = Duration.ofSeconds(10);

    private AgentMain() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("hyperdrive-agent takes no arguments; it is started by the HyperDrive desktop app.");
            System.exit(Agent.EXIT_BOOTSTRAP);
        }
        int code = new Agent(System.in, System.out, System.err, Clock.systemUTC(), BOOTSTRAP_TIMEOUT).run();
        System.exit(code);
    }
}
