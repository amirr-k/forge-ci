package dev.forgeci.controlplane.demo;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.PlanSubmission;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.repository.ProjectRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.service.BuildService;
import dev.forgeci.controlplane.service.PlanSubmissionService;
import dev.forgeci.controlplane.service.WorkerService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Orchestrates one guest demo build end to end: scenario mutation, real plan, real scheduled build. */
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
        return submit(scenario, requestedWorkerCount, false, "guest-demo");
    }

    /**
     * Runs once at control-plane startup, for real, so the very first guest's "no changes"
     * scenario has genuine prior output to describe as "reused" rather than an empty history.
     */
    public void warmUp() {
        submit(DemoScenario.NO_CHANGE, 2, true, "warm-up");
    }

    private DemoBuildResponse submit(DemoScenario scenario, int requestedWorkerCount, boolean full, String triggerType) {
        String token = UUID.randomUUID().toString();
        if (!guard.tryAcquireBuildSlot(token)) {
            throw new DemoBusyException();
        }
        try {
            Project project = ensureDemoProject();
            var mutatedWorkspace = workspace.applyScenario(scenario);
            DemoPlanFactory.DemoPlan plan = full ? planFactory.buildFull(mutatedWorkspace, scenario) : planFactory.build(mutatedWorkspace, scenario);

            String revision = "demo-" + scenario.scriptId() + "-" + token;
            PlanSubmissionRequest planRequest =
                    new PlanSubmissionRequest(revision, "baseline", full, plan.changedPaths(), plan.tasks(), plan.unaffectedTasks());
            PlanSubmission submission = planSubmissionService.submit(project.getId(), planRequest);

            int workerCount = guard.boundWorkerCount(requestedWorkerCount <= 0 ? 2 : requestedWorkerCount);
            Build build = buildService.createBuild(project.getId(), new BuildCreationRequest(submission.getId(), triggerType, workerCount));

            watcher.watch(build.getId(), token);
            return new DemoBuildResponse(build.getId(), scenario.scriptId(), workerCount);
        } catch (RuntimeException failedBeforeScheduling) {
            guard.releaseBuildSlot(token);
            throw failedBeforeScheduling;
        }
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
