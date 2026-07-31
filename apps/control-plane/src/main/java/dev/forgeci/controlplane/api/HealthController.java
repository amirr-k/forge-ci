package dev.forgeci.controlplane.api;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;

    /**
     * The commit this running process was built from — set by the release pipeline, never derived
     * from the running repo (a deployed jar has no {@code .git} directory). Defaults to
     * {@code "unknown"} for local/dev runs, where no release pipeline set it.
     */
    private final String gitCommit;

    public HealthController(DataSource dataSource, @Value("${forge.git-commit:unknown}") String gitCommit) {
        this.dataSource = dataSource;
        this.gitCommit = gitCommit;
    }

    /** Liveness: the process is up and serving requests, independent of any dependency's state. */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * What the release pipeline's last step verifies: that the commit the public deployment
     * actually answers requests with matches the commit it just deployed, not a stale rollout or a
     * cached response from a previous version.
     */
    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of("commit", gitCommit);
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
