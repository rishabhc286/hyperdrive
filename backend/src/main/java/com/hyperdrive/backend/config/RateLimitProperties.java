package com.hyperdrive.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-client-address request limits for sensitive auth endpoints ({@code hyperdrive.rate-limit.*}).
 * Each limit is "requests per window". Single-instance, in-memory; see docs/security.md.
 */
@ConfigurationProperties("hyperdrive.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1m") Duration window,
        @DefaultValue("10") int transactions,
        @DefaultValue("20") int authorize,
        @DefaultValue("20") int exchange,
        @DefaultValue("30") int refresh,
        @DefaultValue("10") int onboarding,
        @DefaultValue("10000") int maxTrackedClients) {
}
