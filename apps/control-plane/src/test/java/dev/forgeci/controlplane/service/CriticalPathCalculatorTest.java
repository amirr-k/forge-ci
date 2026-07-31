package dev.forgeci.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Weight is the length of the longest chain of selected tasks from a task to a sink, counting the
 * task itself — the number the scheduler orders ready task runs by. Exercised directly here rather
 * than only through {@code WorkerSchedulingIntegrationTest}'s two-task tie-break, so a wrong weight
 * on a deeper or wider graph fails without needing a database.
 */
class CriticalPathCalculatorTest {

    @Test
    void aSinkWeighsOne() {
        Map<String, Integer> weights = CriticalPathCalculator.weights(List.of(task("solo")));

        assertThat(weights).containsExactly(Map.entry("solo", 1));
    }

    @Test
    void aChainWeighsItsRemainingLength() {
        Map<String, Integer> weights =
                CriticalPathCalculator.weights(List.of(task("a"), task("b", "a"), task("c", "b")));

        assertThat(weights).containsExactlyInAnyOrderEntriesOf(Map.of("a", 3, "b", 2, "c", 1));
    }

    @Test
    void aForkTakesItsLongestBranchNotTheSumOfBoth() {
        // root feeds both a short branch (one dependent) and a long one (two chained dependents)
        List<TaskDefinitionEntity> tasks =
                List.of(
                        task("root"),
                        task("short", "root"),
                        task("long-1", "root"),
                        task("long-2", "long-1"));

        Map<String, Integer> weights = CriticalPathCalculator.weights(tasks);

        assertThat(weights.get("root")).isEqualTo(3);
        assertThat(weights.get("short")).isEqualTo(1);
        assertThat(weights.get("long-1")).isEqualTo(2);
    }

    @Test
    void aDependencyOutsideTheSelectedSetDoesNotAddWeightToTheTasksInIt() {
        // "unaffected:build" was never selected for this build, so nothing in the set waits on it
        Map<String, Integer> weights =
                CriticalPathCalculator.weights(List.of(task("selected", "unaffected:build")));

        assertThat(weights).containsOnlyKeys("selected");
        assertThat(weights.get("selected")).isEqualTo(1);
    }

    @Test
    void twoIndependentTasksBothWeighOne() {
        Map<String, Integer> weights =
                CriticalPathCalculator.weights(List.of(task("alpha"), task("beta")));

        assertThat(weights).containsExactlyInAnyOrderEntriesOf(Map.of("alpha", 1, "beta", 1));
    }

    private static TaskDefinitionEntity task(String name, String... dependsOn) {
        return new TaskDefinitionEntity(
                null,
                name,
                List.of(dependsOn),
                "sha256:" + name,
                "source changed",
                List.of("true"),
                List.of(),
                List.of(),
                60);
    }
}
