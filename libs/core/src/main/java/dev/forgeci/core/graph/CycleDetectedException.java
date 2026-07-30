package dev.forgeci.core.graph;

import java.util.List;

/**
 * The task graph contains a dependency cycle. {@link #getMessage()} matches the exact,
 * spec-mandated two-line format:
 *
 * <pre>
 * Cycle detected:
 * frontend:build -&gt; api:generate -&gt; frontend:build
 * </pre>
 */
public class CycleDetectedException extends RuntimeException {

    private final List<String> cycle;

    public CycleDetectedException(List<String> cycle) {
        super("Cycle detected:\n" + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    /** The cycle path, closed (first and last element are the same task). */
    public List<String> cycle() {
        return cycle;
    }
}
