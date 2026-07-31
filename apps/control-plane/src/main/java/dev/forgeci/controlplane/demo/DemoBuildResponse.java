package dev.forgeci.controlplane.demo;

import java.util.List;

/** Both builds one guest visit starts: the real full rebuild and the real affected-only run. */
public record DemoBuildResponse(
        Long baselineBuildId,
        Long incrementalBuildId,
        String scenario,
        int workerCount,
        List<String> baselineTasks,
        List<DemoTaskResponse> incrementalTasks,
        List<String> unaffectedTasks) {

    public record DemoTaskResponse(String name, List<String> dependsOn, String reason) {}
}
