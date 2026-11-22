package com.hyperdrive.backend.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings ({@code hyperdrive.auth.*}).
 *
 * @param enabled         true only when the {@code auth} profile is active
 * @param publicBaseUrl   origin the user's browser uses to reach this backend; the Google redirect
 *                        URI and desktop authorization URLs are built from it
 * @param transactionTtl  how long a desktop login transaction may take end to end
 * @param handoffTtl      how long a handoff code may wait before being exchanged
 * @param accessTokenTtl  lifetime of opaque access tokens
 * @param sessionTtl      absolute lifetime of a device session (and its refresh tokens)
 */
@ConfigurationProperties("hyperdrive.auth")
public record AuthProperties(
        @DefaultValue("false") boolean enabled,
        URI publicBaseUrl,
        @DefaultValue("5m") Duration transactionTtl,
        @DefaultValue("60s") Duration handoffTtl,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration sessionTtl) {
}
