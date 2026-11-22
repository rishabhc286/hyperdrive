package com.hyperdrive.backend.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** Builds the sanitized RFC 9457 problem responses used by every API error path. */
public final class ApiErrors {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ApiErrors() {
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    /** For filters and security handlers that run outside Spring MVC. */
    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        MAPPER.writeValue(response.getOutputStream(), problem(status, code, detail));
    }
}
