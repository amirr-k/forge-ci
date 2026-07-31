package dev.forgeci.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildEvent;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.BuildEventRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StateMachineTest extends ControlPlaneIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private PlanSubmissionService planSubmissionService;
    @Autowired private BuildService buildService;
    @Autowired private BuildStateMachine buildStateMachine;
    @Autowired private TaskRunStateMachine taskRunStateMachine;
    @Autowired private TaskRunRepository taskRunRepository;
    @Autowired private BuildEventRepository buildEventRepository;

    private Build createRunningBuild(String revision) {
        Project project = projectService.register(TestFixtures.project());
        var plan = planSubmissionService.submit(project.getId(), TestFixtures.twoTaskPlan(revision, "rev-0"));
        return buildService.createBuild(project.getId(), new BuildCreationRequest(plan.getId(), "manual", 0));
    }

    @Test
    void invalidBuildTransitionIsRejectedWithoutChangingState() {
        Build build = createRunningBuild("state-1");
        long eventsBefore = buildEventRepository.countByBuildId(build.getId());

        assertThatThrownBy(() -> buildStateMachine.transition(build.getId(), build.getVersion(), BuildState.PLANNING))
                .isInstanceOf(InvalidTransitionException.class);

        Build reloaded = buildService.get(build.getId());
        assertThat(reloaded.getState()).isEqualTo(BuildState.RUNNING);
        assertThat(buildEventRepository.countByBuildId(build.getId())).isEqualTo(eventsBefore);
    }

    @Test
    void staleBuildVersionIsRejectedWithoutCorruptingAcceptedState() {
        Build build = createRunningBuild("state-2");
        long staleVersion = build.getVersion();

        // an accepted transition moves the version forward from under a caller holding the old copy
        buildStateMachine.transition(build.getId(), build.getVersion(), BuildState.CANCELED);

        assertThatThrownBy(() -> buildStateMachine.transition(build.getId(), staleVersion, BuildState.CANCELED))
                .isInstanceOf(StaleTransitionException.class);

        Build reloaded = buildService.get(build.getId());
        assertThat(reloaded.getState()).isEqualTo(BuildState.CANCELED);
    }

    @Test
    void invalidTaskRunTransitionIsRejected() {
        Build build = createRunningBuild("state-3");
        TaskRun pricingBuild = taskRunRepository.findByBuildIdAndTaskName(build.getId(), "pricing:build").orElseThrow();
        assertThat(pricingBuild.getState()).isEqualTo(TaskRunState.READY);

        assertThatThrownBy(
                        () ->
                                taskRunStateMachine.transition(
                                        pricingBuild.getId(), pricingBuild.getVersion(), TaskRunState.SUCCEEDED, TaskRunOutcome.NONE))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void lateReportFromAStaleAttemptCannotOverwriteAnAlreadyAcceptedResult() {
        Build build = createRunningBuild("state-4");
        TaskRun pricingBuild = taskRunRepository.findByBuildIdAndTaskName(build.getId(), "pricing:build").orElseThrow();
        long staleVersion = pricingBuild.getVersion();

        TaskRun leased =
                taskRunStateMachine.transition(pricingBuild.getId(), pricingBuild.getVersion(), TaskRunState.LEASED, TaskRunOutcome.NONE);
        taskRunStateMachine.transition(leased.getId(), leased.getVersion(), TaskRunState.RUNNING, TaskRunOutcome.NONE);

        // a late worker report still carrying the pre-lease version must not resurrect this task run
        assertThatThrownBy(
                        () ->
                                taskRunStateMachine.transition(
                                        pricingBuild.getId(), staleVersion, TaskRunState.LEASED, TaskRunOutcome.NONE))
                .isInstanceOf(StaleTransitionException.class);
    }

    @Test
    void everyAcceptedTransitionEmitsExactlyOneOrderedBuildEvent() {
        Build build = createRunningBuild("state-5");
        List<BuildEvent> events = buildEventRepository.findByBuildIdOrderBySequenceNumberAsc(build.getId());

        // BUILD_PLANNING, BUILD_RUNNING, plus one TASK_RUN_READY for the dependency-free task
        assertThat(events).hasSize(3);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).getSequenceNumber()).isEqualTo(i + 1);
        }

        TaskRun pricingBuild = taskRunRepository.findByBuildIdAndTaskName(build.getId(), "pricing:build").orElseThrow();
        taskRunStateMachine.transition(pricingBuild.getId(), pricingBuild.getVersion(), TaskRunState.LEASED, TaskRunOutcome.NONE);

        List<BuildEvent> after = buildEventRepository.findByBuildIdOrderBySequenceNumberAsc(build.getId());
        assertThat(after).hasSize(4);
        assertThat(after.get(3).getSequenceNumber()).isEqualTo(4);
    }
}
