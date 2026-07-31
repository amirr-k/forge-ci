package dev.forgeci.controlplane.api;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Liveness: the process is up and serving requests, independent of any dependency's state. */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Readiness: the process can actually serve a request that needs MySQL. */
    @GetMapping("/api/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(Map.of("status", "UP"));
            }
        } catch (Exception ignoredConnectionFailure) {
            // fall through to unready below
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }
}
