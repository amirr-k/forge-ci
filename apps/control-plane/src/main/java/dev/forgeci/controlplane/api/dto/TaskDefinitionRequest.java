package dev.forgeci.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TaskDefinitionRequest(
        @NotBlank String name, @NotNull List<String> dependsOn, @NotBlank String cacheKey, @NotBlank String reason) {

    public TaskDefinitionRequest {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
