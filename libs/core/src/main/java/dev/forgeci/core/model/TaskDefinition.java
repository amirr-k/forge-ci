package dev.forgeci.core.model;

import java.util.List;

/** A single task declared in {@code forgeci.yml}. */
public record TaskDefinition(
        String name,
        List<String> dependsOn,
        List<String> inputs,
        List<String> outputs,
        List<String> command,
        List<String> environment,
        String timeout,
        boolean cacheable) {

    public TaskDefinition {
        dependsOn = List.copyOf(dependsOn);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        command = List.copyOf(command);
        environment = List.copyOf(environment);
    }
}
