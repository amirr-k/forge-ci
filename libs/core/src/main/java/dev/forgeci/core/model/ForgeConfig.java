package dev.forgeci.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The fully parsed and validated contents of a {@code forgeci.yml} file. */
public record ForgeConfig(
        int version, ProjectInfo project, Defaults defaults, Map<String, TaskDefinition> tasks) {

    public ForgeConfig {
        // preserve declaration order from forgeci.yml rather than Map.copyOf's unspecified order
        tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
    }
}
