package dev.forgeci.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record TaskDefinitionRequest(
        @NotBlank String name,
        @NotNull List<String> dependsOn,
        @NotBlank String cacheKey,
        @NotBlank String reason,
        @NotEmpty List<String> command,
        List<String> outputs,
        List<String> environment,
        @Positive int timeoutSeconds) {

    public TaskDefinitionRequest {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        command = command == null ? List.of() : List.copyOf(command);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        environment = environment == null ? List.of() : List.copyOf(environment);
    }
}
