package dev.forgeci.controlplane.demo;

import java.util.List;

public record DemoBuildResponse(
        Long buildId, String scenario, int workerCount, List<DemoTaskResponse> tasks, List<String> unaffectedTasks) {

    public record DemoTaskResponse(String name, List<String> dependsOn, String reason) {}
}
