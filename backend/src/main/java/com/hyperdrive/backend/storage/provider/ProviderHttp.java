package com.hyperdrive.backend.storage.provider;

import com.hyperdrive.backend.config.StorageProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.client.RestClientException;

/**
 * Shared transport policy for provider adapters: executes one HTTP exchange, lets the adapter
 * classify failures, and retries only what the adapter marks retryable, at most
 * {@code maxRetries} times with exponential backoff and jitter. A {@code Retry-After} the provider
 * sends is honored when it fits within {@code maxRetryDelay}; longer waits are returned to the
 * client instead of blocking the request. Authorization headers are never logged here.
 */
public final class ProviderHttp {

    /** A raw provider response; the body may contain user content and must not be logged. */
    public record Response(int status, String retryAfter, byte[] body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Provider-specific interpretation of a non-2xx response. */
    public interface Classifier {
        ProviderException classify(Response response);

        boolean retryable(Response response, ProviderException failure);
    }

    @FunctionalInterface
    public interface Exchange {
        Response execute();
    }

    /** Waits between retries; replaceable in tests. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final StorageProperties properties;
    private final Sleeper sleeper;
    private final String providerName;

    public ProviderHttp(StorageProperties properties, Sleeper sleeper, String providerName) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.providerName = providerName;
    }

    /** Returns the first 2xx response, or throws the classified failure. */
    public Response execute(Exchange exchange, Classifier classifier) {
        int attempt = 0;
        while (true) {
            ProviderException failure;
            boolean retryable;
            try {
                Response response = exchange.execute();
                if (response.ok()) {
                    return response;
                }
                failure = classifier.classify(response);
                retryable = classifier.retryable(response, failure);
            } catch (RestClientException e) {
                failure = new ProviderException(ProviderException.Category.UNAVAILABLE, providerName + " could not be reached");
                retryable = true;
            }
            if (!retryable || attempt >= properties.maxRetries()) {
                throw failure;
            }
            Duration wait = backoff(attempt, failure.retryAfter());
            if (wait == null) {
                throw failure; // Retry-After longer than we are willing to block a request for.
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            attempt++;
        }
    }

    private Duration backoff(int attempt, Duration retryAfter) {
        Duration max = properties.maxRetryDelay();
        if (retryAfter != null) {
            return retryAfter.compareTo(max) <= 0 ? retryAfter : null;
        }
        long base = properties.retryBaseDelay().toMillis() << attempt;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, properties.retryBaseDelay().toMillis()));
        return Duration.ofMillis(Math.min(base + jitter, max.toMillis()));
    }

    /** {@code Retry-After} as seconds or an HTTP date; null if absent or unparseable. */
    public static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            try {
                Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration d = Duration.between(Instant.now(), at);
                return d.isNegative() ? Duration.ZERO : d;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
