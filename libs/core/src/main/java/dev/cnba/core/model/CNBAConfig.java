package dev.cnba.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The fully parsed and validated contents of a {@code cnba.yml} file. */
public record CNBAConfig(
        int version, ProjectInfo project, Defaults defaults, Map<String, TaskDefinition> tasks) {

    public CNBAConfig {
        // preserve declaration order from cnba.yml rather than Map.copyOf's unspecified order
        tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
    }
}
