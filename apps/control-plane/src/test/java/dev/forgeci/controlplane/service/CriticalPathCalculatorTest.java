package dev.forgeci.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * The case the duration-aware policy exists for: three hops of trivial work outrank one hop of
     * expensive work under hop counting, which is backwards when the goal is to finish sooner.
     */
    @Test
    void hopCountAndDurationDisagreeWhenAChainOfCheapTasksFacesOneExpensiveTask() {
        List<TaskDefinitionEntity> tasks =
                List.of(
                        task("cheap-1"),
                        task("cheap-2", "cheap-1"),
                        task("cheap-3", "cheap-2"),
                        task("expensive"),
                        task("sink", "cheap-3", "expensive"));

        Map<String, Integer> hops = CriticalPathCalculator.weights(tasks);
        assertThat(hops.get("cheap-1")).isGreaterThan(hops.get("expensive"));

        Map<String, Long> millis =
                CriticalPathCalculator.millis(
                        tasks, name -> name.equals("expensive") ? 30_000L : 100L);
        assertThat(millis.get("expensive")).isGreaterThan(millis.get("cheap-1"));
    }

    @Test
    void aTasksDurationIsItsOwnEstimatePlusTheLongestChainBelowIt() {
        List<TaskDefinitionEntity> tasks =
                List.of(
                        task("root"),
                        task("short", "root"),
                        task("long", "root"),
                        task("leaf", "long"));

        Map<String, Long> millis =
                CriticalPathCalculator.millis(
                        tasks,
                        name ->
                                switch (name) {
                                    case "root" -> 1_000L;
                                    case "short" -> 500L;
                                    case "long" -> 2_000L;
                                    default -> 250L;
                                });

        assertThat(millis.get("leaf")).isEqualTo(250L);
        assertThat(millis.get("long")).isEqualTo(2_250L);
        assertThat(millis.get("short")).isEqualTo(500L);
        assertThat(millis.get("root")).isEqualTo(3_250L);
    }

    @Test
    void policyNamesParseInTheConfigurationSpelling() {
        assertThat(SchedulingPolicy.from("fifo")).isEqualTo(SchedulingPolicy.FIFO);
        assertThat(SchedulingPolicy.from("critical-path"))
                .isEqualTo(SchedulingPolicy.CRITICAL_PATH);
        assertThat(SchedulingPolicy.from(" Critical-Path-Duration "))
                .isEqualTo(SchedulingPolicy.CRITICAL_PATH_DURATION);
        assertThatThrownBy(() -> SchedulingPolicy.from("shortest-first"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("critical-path-duration");
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
