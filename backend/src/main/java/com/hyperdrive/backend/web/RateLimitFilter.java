package com.hyperdrive.backend.web;

import com.hyperdrive.backend.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits sensitive auth endpoints per client address. Keys use the TCP peer address only;
 * X-Forwarded-For is deliberately not trusted (no proxy support yet).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String DESKTOP = "/api/v1/auth/desktop/";
    private static final String STORAGE = "/api/v1/storage-connections/";
    private static final String ACCOUNTS = "/api/v1/accounts";

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final Map<String, ToIntFunction<RateLimitProperties>> postLimits = Map.of(
            DESKTOP + "transactions", RateLimitProperties::transactions,
            DESKTOP + "exchange", RateLimitProperties::exchange,
            "/api/v1/auth/refresh", RateLimitProperties::refresh,
            "/api/v1/me/onboarding", RateLimitProperties::onboarding,
            STORAGE + "google/transactions", RateLimitProperties::storageTransactions,
            STORAGE + "dropbox/transactions", RateLimitProperties::storageTransactions);

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String bucket = null;
        int limit = 0;
        if ("POST".equals(request.getMethod()) && postLimits.containsKey(path)) {
            bucket = path;
            limit = postLimits.get(path).applyAsInt(properties);
        } else if ("GET".equals(request.getMethod()) && path.startsWith(DESKTOP + "authorize/")) {
            bucket = DESKTOP + "authorize";
            limit = properties.authorize();
        } else if ("GET".equals(request.getMethod()) && (path.startsWith(STORAGE + "google/authorize/")
                || path.equals(STORAGE + "google/callback") || path.startsWith(STORAGE + "dropbox/authorize/")
                || path.equals(STORAGE + "dropbox/callback"))) {
            bucket = STORAGE + "browser";
            limit = properties.storageBrowser();
        } else if (path.startsWith(STORAGE + "transactions/")) {
            // Status polling and cancellation.
            bucket = STORAGE + "transactions";
            limit = properties.storageStatus();
        } else if ("POST".equals(request.getMethod()) && path.startsWith(ACCOUNTS + "/") && path.endsWith("/read-credentials")) {
            // Provider-token delegation: its own, tighter bucket.
            bucket = ACCOUNTS + "/read-credentials";
            limit = properties.readCredentials();
        } else if (path.equals(ACCOUNTS) || path.startsWith(ACCOUNTS + "/")) {
            bucket = ACCOUNTS;
            limit = properties.browse();
        }

        if (bucket != null && !limiter.tryAcquire(bucket + "|" + request.getRemoteAddr(), limit)) {
            response.setHeader("Retry-After", Long.toString(limiter.retryAfterSeconds()));
            ApiErrors.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Too many requests. Wait a moment and try again.");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled();
    }
}
