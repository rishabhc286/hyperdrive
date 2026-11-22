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
        /** Starting a storage connection. */
        @DefaultValue("10") int storageTransactions,
        /** Storage browser leg: ticket start and Google callback. */
        @DefaultValue("20") int storageBrowser,
        /** Connection status polling (the desktop polls every 2 s) and cancel. */
        @DefaultValue("60") int storageStatus,
        /** Account and file-browsing API calls. */
        @DefaultValue("300") int browse,
        /** Provider read-credential delegation (Batch 6). */
        @DefaultValue("30") int readCredentials,
        @DefaultValue("10000") int maxTrackedClients) {
}
