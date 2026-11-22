package com.hyperdrive.backend.security;

import com.hyperdrive.backend.web.ApiErrors;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401/403 responses with the standard WWW-Authenticate header plus a sanitized problem body. */
final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() {
    }

    static AuthenticationEntryPoint bearerEntryPoint() {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception);
            ApiErrors.write(response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Sign in to continue. The access token is missing, invalid or expired.");
        };
    }

    static AccessDeniedHandler bearerAccessDenied() {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            bearer.handle(request, response, exception);
            ApiErrors.write(response, HttpStatus.FORBIDDEN, "forbidden",
                    "This action isn't available. If you're new, choose a username first.");
        };
    }

    static AuthenticationEntryPoint forbiddenEntryPoint() {
        return (request, response, exception) ->
                ApiErrors.write(response, HttpStatus.FORBIDDEN, "forbidden", "Not available.");
    }

    static AccessDeniedHandler forbidden() {
        return (request, response, exception) ->
                ApiErrors.write(response, HttpStatus.FORBIDDEN, "forbidden", "Not available.");
    }
}
