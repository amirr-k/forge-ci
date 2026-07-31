package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A freshly created task run is dependency-complete — and so can move straight to {@code READY} —
 * only if none of its declared dependencies are themselves part of this build's selected task set.
 * A dependency outside that set was already satisfied before the build was planned (cached or
 * otherwise unaffected).
 */
final class ReadinessEvaluator {

    private ReadinessEvaluator() {}

    static boolean isImmediatelyReady(
            TaskDefinitionEntity task, List<TaskDefinitionEntity> allSelected) {
        Set<String> selectedNames = new HashSet<>();
        for (TaskDefinitionEntity t : allSelected) {
            selectedNames.add(t.getTaskName());
        }
        for (String dependency : task.getDependsOn()) {
            if (selectedNames.contains(dependency)) {
                return false;
            }
        }
        return true;
    }
}
