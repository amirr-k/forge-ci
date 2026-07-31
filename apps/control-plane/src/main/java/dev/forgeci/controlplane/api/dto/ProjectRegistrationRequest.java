package dev.forgeci.controlplane.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ProjectRegistrationRequest(
        @NotBlank String name,
        @NotBlank String repositoryIdentity,
        @NotBlank String defaultBranch,
        @Min(1) int configVersion) {}
