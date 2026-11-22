package com.hyperdrive.backend.storage.provider;

import java.time.Duration;

/**
 * A provider call failed. The category drives what happens next (refresh, reconnect, retry later,
 * report); the message is safe to log and never contains tokens, file names or emails.
 */
public final class ProviderException extends RuntimeException {

    public enum Category {
        /** The access token was rejected (expired or revoked). Refreshing may help once. */
        AUTHENTICATION,
        /** The grant lacks a required scope; only a new consent helps. */
        INSUFFICIENT_SCOPE,
        /** The account may not access this item. Not a token problem. */
        PERMISSION_DENIED,
        NOT_FOUND,
        /** Rate or quota limit; {@link #retryAfter()} may say when to try again. */
        RATE_LIMITED,
        /** Network failure, timeout or provider 5xx. */
        UNAVAILABLE,
        /** The provider answered with something this adapter cannot interpret. */
        INVALID_RESPONSE
    }

    private final Category category;
    private final Duration retryAfter;

    public ProviderException(Category category, String message) {
        this(category, message, null);
    }

    public ProviderException(Category category, String message, Duration retryAfter) {
        super(message, null, false, false);
        this.category = category;
        this.retryAfter = retryAfter;
    }

    public Category category() {
        return category;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
