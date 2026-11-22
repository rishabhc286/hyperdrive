package com.hyperdrive.backend.web;

import org.springframework.http.HttpStatus;

/**
 * An error that is safe to show to the client. {@code code} is a stable machine-readable value;
 * {@code detail} is a short human-readable message. Neither may contain secrets or tokens.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail, null, false, false);
        this.status = status;
        this.code = code;
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
