package dev.forgeci.controlplane.demo;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 11 produces real benchmark data (a versioned {@code latest.json}); until then this route
 * exists and returns an honest "not yet available" rather than fabricated numbers.
 */
@RestController
public class BenchmarksController {

    @GetMapping("/api/benchmarks/latest")
    public Map<String, Object> latest() {
        return Map.of("available", false, "message", "benchmark results are not published yet");
    }
}
