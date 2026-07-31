package dev.forgeci.controlplane.demo;

public record DemoBuildResponse(Long buildId, String scenario, int workerCount) {}
