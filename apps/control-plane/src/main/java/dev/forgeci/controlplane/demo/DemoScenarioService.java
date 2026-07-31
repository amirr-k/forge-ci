package dev.forgeci.controlplane.demo;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.demo.DemoBuildResponse.DemoTaskResponse;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.PlanSubmission;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.repository.ProjectRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.service.BuildService;
import dev.forgeci.controlplane.service.PlanSubmissionService;
import dev.forgeci.controlplane.service.WorkerService;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one guest demo visit end to end: scenario mutation, then two real, concurrently
 * scheduled builds against the same mutated workspace — a full rebuild ("traditional baseline")
 * and the affected-only incremental build — so the comparison the UI shows is two genuine
 * measured runs, never one live run next to a precomputed number (product-and-demo.md#demo-repository).
 * Both builds compete for the same worker fleet exactly like any two unrelated builds would
 * (apps/control-plane's scheduler already claims across a single global queue, not per-build), and
 * both apply the identical, idempotent scenario mutation on every task, so running them at the same
 * time against the one shared demo workspace is safe.
 */
@Service
public class DemoScenarioService {

    private static final String DEMO_PROJECT_NAME = "sample-monorepo-demo";

    private final ProjectRepository projectRepository;
    private final PlanSubmissionService planSubmissionService;
    private final BuildService buildService;
    private final TaskRunRepository taskRunRepository;
    private final WorkerService workerService;
    private final DemoWorkspace workspace;
    private final DemoPlanFactory planFactory;
    private final DemoGuestGuard guard;
    private final DemoBuildWatcher watcher;

    public DemoScenarioService(
            ProjectRepository projectRepository,
            PlanSubmissionService planSubmissionService,
            BuildService buildService,
            TaskRunRepository taskRunRepository,
            WorkerService workerService,
            DemoWorkspace workspace,
            DemoPlanFactory planFactory,
            DemoGuestGuard guard,
            DemoBuildWatcher watcher) {
        this.projectRepository = projectRepository;
        this.planSubmissionService = planSubmissionService;
        this.buildService = buildService;
        this.taskRunRepository = taskRunRepository;
        this.workerService = workerService;
        this.workspace = workspace;
        this.planFactory = planFactory;
        this.guard = guard;
        this.watcher = watcher;
    }

    public DemoBuildResponse startBuild(DemoScenario scenario, int requestedWorkerCount) {
        String token = UUID.randomUUID().toString();
        if (!guard.tryAcquireBuildSlot(token)) {
            throw new DemoBusyException();
        }
        try {
            Project project = ensureDemoProject();
            Path mutatedWorkspace = workspace.applyScenario(scenario);
            int workerCount = guard.boundWorkerCount(requestedWorkerCount <= 0 ? 2 : requestedWorkerCount);

            DemoPlanFactory.DemoPlan baselinePlan = planFactory.buildBaselineForComparison(mutatedWorkspace, scenario, token);
            Build baselineBuild =
                    submitOne(project, baselinePlan, "demo-baseline-" + scenario.scriptId() + "-" + token, true, "guest-demo-baseline", workerCount);

            DemoPlanFactory.DemoPlan incrementalPlan = planFactory.build(mutatedWorkspace, scenario);
            Build incrementalBuild =
                    submitOne(
                            project,
                            incrementalPlan,
                            "demo-incremental-" + scenario.scriptId() + "-" + token,
                            false,
                            "guest-demo",
                            workerCount);

            watcher.watch(List.of(baselineBuild.getId(), incrementalBuild.getId()), token);

            List<DemoTaskResponse> incrementalTasks =
                    incrementalPlan.tasks().stream().map(t -> new DemoTaskResponse(t.name(), t.dependsOn(), t.reason())).toList();
            List<String> baselineTasks = baselinePlan.tasks().stream().map(t -> t.name()).toList();
            return new DemoBuildResponse(
                    baselineBuild.getId(),
                    incrementalBuild.getId(),
                    scenario.scriptId(),
                    workerCount,
                    baselineTasks,
                    incrementalTasks,
                    incrementalPlan.unaffectedTasks());
        } catch (RuntimeException failedBeforeScheduling) {
            guard.releaseBuildSlot(token);
            throw failedBeforeScheduling;
        }
    }

    /**
     * Runs once at control-plane startup, for real, so the very first guest's "no changes"
     * scenario has genuine prior output to describe as "reused" rather than an empty history.
     */
    public void warmUp() {
        String token = UUID.randomUUID().toString();
        if (!guard.tryAcquireBuildSlot(token)) {
            return;
        }
        try {
            Project project = ensureDemoProject();
            Path mutatedWorkspace = workspace.applyScenario(DemoScenario.NO_CHANGE);
            DemoPlanFactory.DemoPlan plan = planFactory.buildFull(mutatedWorkspace, DemoScenario.NO_CHANGE);
            Build build = submitOne(project, plan, "demo-warmup-" + token, true, "warm-up", 2);
            watcher.watch(List.of(build.getId()), token);
        } catch (RuntimeException warmupFailure) {
            guard.releaseBuildSlot(token);
            throw warmupFailure;
        }
    }

    private Build submitOne(
            Project project, DemoPlanFactory.DemoPlan plan, String revision, boolean full, String triggerType, int workerCount) {
        PlanSubmissionRequest planRequest =
                new PlanSubmissionRequest(revision, "baseline", full, plan.changedPaths(), plan.tasks(), plan.unaffectedTasks());
        PlanSubmission submission = planSubmissionService.submit(project.getId(), planRequest);
        return buildService.createBuild(project.getId(), new BuildCreationRequest(submission.getId(), triggerType, workerCount));
    }

    /** Crashes whichever worker currently holds a running task in this build, for the "Crash a Worker" demo. */
    public long crashWorker(Long buildId) {
        List<TaskRun> runs = taskRunRepository.findByBuildId(buildId);
        Long workerId =
                runs.stream()
                        .filter(run -> run.getWorkerId() != null && !run.getState().isTerminal())
                        .max(Comparator.comparing(TaskRun::getId))
                        .map(TaskRun::getWorkerId)
                        .orElseThrow(() -> new IllegalStateException("no running task to crash for build " + buildId));
        workerService.requestCrash(workerId);
        return workerId;
    }

    private Project ensureDemoProject() {
        return projectRepository
                .findByName(DEMO_PROJECT_NAME)
                .orElseGet(() -> projectRepository.save(new Project(DEMO_PROJECT_NAME, "bundled/sample-monorepo", "main", 1)));
    }
}
