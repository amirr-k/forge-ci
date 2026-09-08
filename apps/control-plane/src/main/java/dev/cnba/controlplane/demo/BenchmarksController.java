package dev.cnba.controlplane.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the committed benchmark report bundled onto the classpath at build time. Reading the
 * repository at runtime is not an option — a deployed jar has no working tree — and returning
 * anything not generated from {@code benchmarks/results/latest.json} would be a fabricated number.
 */
@RestController
public class BenchmarksController {

    private static final String RESOURCE = "benchmarks/latest.json";

    private final ObjectMapper mapper;

    public BenchmarksController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/api/benchmarks/latest")
    public Map<String, Object> latest() throws IOException {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            return Map.of("available", false, "message", "no benchmark report is bundled");
        }
        try (InputStream bytes = resource.getInputStream()) {
            Map<String, Object> report =
                    mapper.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("available", true);
            body.put("message", "");
            body.putAll(report);
            return body;
        }
    }
}
