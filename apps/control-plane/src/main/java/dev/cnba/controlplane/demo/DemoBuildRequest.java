package dev.cnba.controlplane.demo;

import jakarta.validation.constraints.NotBlank;

public record DemoBuildRequest(@NotBlank String scenario, int workerCount) {}
