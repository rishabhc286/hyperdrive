package com.hyperdrive.backend.health;

import com.hyperdrive.backend.config.AuthProperties;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness, distinct from liveness ({@code /api/v1/health}). Tells the desktop whether sign-in is
 * enabled and, if so, whether its required database is reachable. Reports nothing else.
 */
@RestController
@RequestMapping("/api/v1")
public class ReadinessController {

    public enum Auth { ENABLED, DISABLED }

    public record ReadinessResponse(String status, Auth auth) {
    }

    private final AuthProperties properties;
    private final ObjectProvider<DataSource> dataSource;

    public ReadinessController(AuthProperties properties, ObjectProvider<DataSource> dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    @GetMapping(path = "/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReadinessResponse> ready() {
        if (!properties.enabled()) {
            return respond(HttpStatus.OK, new ReadinessResponse("READY", Auth.DISABLED));
        }
        boolean databaseUp = databaseReachable();
        return databaseUp
                ? respond(HttpStatus.OK, new ReadinessResponse("READY", Auth.ENABLED))
                : respond(HttpStatus.SERVICE_UNAVAILABLE, new ReadinessResponse("NOT_READY", Auth.ENABLED));
    }

    private boolean databaseReachable() {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return false;
        }
        try (var connection = ds.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static ResponseEntity<ReadinessResponse> respond(HttpStatus status, ReadinessResponse body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
