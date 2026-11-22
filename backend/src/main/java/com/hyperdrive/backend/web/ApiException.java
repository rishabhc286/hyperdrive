package com.hyperdrive.backend.web;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * An error that is safe to show to the client. {@code code} is a stable machine-readable value;
 * {@code detail} is a short human-readable message. Neither may contain secrets or tokens.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail, null, false, false);
        this.status = status;
        this.code = code;
        this.retryAfter = null;
    }

    private ApiException(ApiException base, Duration retryAfter) {
        super(base.getMessage(), null, false, false);
        this.status = base.status;
        this.code = base.code;
        this.retryAfter = retryAfter;
    }

    /** Same error, also telling the client when to retry (sent as a Retry-After header). */
    public ApiException withRetryAfter(Duration retryAfter) {
        return new ApiException(this, retryAfter);
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException invalidGrant() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_grant",
                "The sign-in code is invalid, expired or already used. Start sign-in again.");
    }
}
