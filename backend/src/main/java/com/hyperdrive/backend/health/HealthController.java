package com.hyperdrive.backend.health;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint for the desktop app and local tooling.
 *
 * <p>Deliberately minimal: it reports only that this process is serving requests. It does not
 * expose configuration, dependency status, versions, or any other internal diagnostics.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    static final String SERVICE_NAME = "hyperdrive-backend";

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new HealthResponse("UP", SERVICE_NAME));
    }

    public record HealthResponse(String status, String service) {
    }
}
