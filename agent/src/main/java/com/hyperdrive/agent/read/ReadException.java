package com.hyperdrive.agent.read;

import java.time.Duration;

/**
 * A read failed. {@code code} is a stable, non-sensitive identifier returned to the desktop;
 * {@code httpStatus} is the local API status. Messages never contain tokens, file names or
 * provider response bodies.
 */
public final class ReadException extends Exception {

    private final String code;
    private final int httpStatus;
    private final Duration retryAfter;

    public ReadException(String code, int httpStatus) {
        this(code, httpStatus, null);
    }

    public ReadException(String code, int httpStatus, Duration retryAfter) {
        super(code, null, false, false);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    static ReadException invalid() {
        return new ReadException("invalid_request", 400);
    }
}
