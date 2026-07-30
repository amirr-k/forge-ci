package dev.forgeci.core.graph;

import java.util.List;

/** The outcome of {@link AffectedTaskAnalyzer#analyze}, both lists sorted alphabetically by task name. */
public record AffectedResult(List<AffectedTask> affected, List<String> unaffected) {

    public AffectedResult {
        affected = List.copyOf(affected);
        unaffected = List.copyOf(unaffected);
    }
}
