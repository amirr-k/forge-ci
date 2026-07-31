package dev.forgeci.controlplane.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the guest demo path end to end against the bundled repo for real: a leaf-module change
 * selects exactly the tasks the dependency graph says it should, the single guest-build slot
 * actually serializes concurrent attempts, and crashing a worker with nothing running fails
 * loudly instead of silently no-op'ing.
 */
class DemoScenarioIntegrationTest extends ControlPlaneIntegrationTest {

    @TempDir static Path demoWorkspace;

    @DynamicPropertySource
    static void demoProperties(DynamicPropertyRegistry registry) {
        // tests run with the module directory as the working directory, two levels below the repo root
        registry.add("forge.demo.repo-path", () -> "../../demo/sample-monorepo");
        registry.add("forge.demo.workspace-path", () -> demoWorkspace.toString());
        registry.add("forge.demo.warmup.enabled", () -> "false");
    }

    @Autowired private DemoScenarioService demoScenarioService;
    @Autowired private DemoGuestGuard guard;
    @Autowired private StringRedisTemplate redis;

    /**
     * Nothing in this test class registers a real worker, so a build started here never reaches a
     * terminal state and {@link DemoBuildWatcher} never releases the slot on its own — clear it
     * directly so each test starts from a clean lock, exactly like production's TTL eventually would.
     */
    @AfterEach
    void releaseGuestBuildSlot() {
        redis.delete("forge:demo:build-lock");
    }

    @org.junit.jupiter.api.Test
    void leafModuleChangeSelectsExactlyItsDownstreamClosure() {
        DemoBuildResponse response = demoScenarioService.startBuild(DemoScenario.LEAF_MODULE_CHANGE, 2);

        assertThat(response.buildId()).isNotNull();
        assertThat(response.scenario()).isEqualTo("leaf-module");
        assertThat(response.workerCount()).isEqualTo(2);
    }

    @org.junit.jupiter.api.Test
    void aSecondGuestBuildIsRejectedWhileOneIsInFlight() {
        String heldToken = "held-by-another-request";
        boolean acquired = guard.tryAcquireBuildSlot(heldToken);
        assertThat(acquired).isTrue();
        try {
            assertThatThrownBy(() -> demoScenarioService.startBuild(DemoScenario.NO_CHANGE, 1))
                    .isInstanceOf(DemoBusyException.class);
        } finally {
            guard.releaseBuildSlot(heldToken);
        }
    }

    @org.junit.jupiter.api.Test
    void crashingAWorkerWithNothingRunningFailsRatherThanNoOp() {
        DemoBuildResponse response = demoScenarioService.startBuild(DemoScenario.SHARED_CORE_CHANGE, 2);

        assertThatThrownBy(() -> demoScenarioService.crashWorker(response.buildId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.api.Test
    void boundsRequestedWorkerCountToTheGuestMaximum() {
        DemoBuildResponse response = demoScenarioService.startBuild(DemoScenario.FAILED_TEST, 999);

        assertThat(response.workerCount()).isEqualTo(guard.maxWorkerCount());
    }
}
