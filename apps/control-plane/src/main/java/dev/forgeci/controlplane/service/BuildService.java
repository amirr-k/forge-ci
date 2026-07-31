package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.domain.Artifact;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.PlanSubmission;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.repository.ArtifactRepository;
import dev.forgeci.controlplane.repository.BuildRepository;
import dev.forgeci.controlplane.repository.PlanSubmissionRepository;
import dev.forgeci.controlplane.repository.ProjectRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildService {

    private final ProjectRepository projectRepository;
    private final PlanSubmissionRepository planSubmissionRepository;
    private final BuildRepository buildRepository;
    private final TaskRunRepository taskRunRepository;
    private final ArtifactRepository artifactRepository;
    private final BuildStateMachine buildStateMachine;
    private final SchedulerService schedulerService;
    private final BuildMetrics metrics;

    public BuildService(
            ProjectRepository projectRepository,
            PlanSubmissionRepository planSubmissionRepository,
            BuildRepository buildRepository,
            TaskRunRepository taskRunRepository,
            ArtifactRepository artifactRepository,
            BuildStateMachine buildStateMachine,
            SchedulerService schedulerService,
            BuildMetrics metrics) {
        this.projectRepository = projectRepository;
        this.planSubmissionRepository = planSubmissionRepository;
        this.buildRepository = buildRepository;
        this.taskRunRepository = taskRunRepository;
        this.artifactRepository = artifactRepository;
        this.buildStateMachine = buildStateMachine;
        this.schedulerService = schedulerService;
        this.metrics = metrics;
    }

    /**
     * Resubmitting a build for the same accepted plan submission is idempotent: returns the
     * existing build rather than creating a duplicate logical build.
     */
    @Transactional
    public Build createBuild(Long projectId, BuildCreationRequest request) {
        Project project =
                projectRepository
                        .findById(projectId)
                        .orElseThrow(
                                () -> new NotFoundException("project " + projectId + " not found"));
        PlanSubmission planSubmission =
                planSubmissionRepository
                        .findById(request.planSubmissionId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "plan submission "
                                                        + request.planSubmissionId()
                                                        + " not found"));

        var existing =
                buildRepository.findByProjectIdAndPlanSubmissionId(
                        projectId, planSubmission.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Build build;
        try {
            build =
                    buildRepository.saveAndFlush(
                            new Build(
                                    project,
                                    planSubmission,
                                    planSubmission.getRevision(),
                                    planSubmission.getBaseRevision(),
                                    request.triggerType(),
                                    request.requestedWorkerCount()));
        } catch (DataIntegrityViolationException raceLostToConcurrentCreate) {
            return buildRepository
                    .findByProjectIdAndPlanSubmissionId(projectId, planSubmission.getId())
                    .orElseThrow(() -> raceLostToConcurrentCreate);
        }

        metrics.buildStarted();
        materializeAndAdvance(build.getId(), planSubmission);
        return buildRepository.findById(build.getId()).orElseThrow();
    }

    private void materializeAndAdvance(Long buildId, PlanSubmission planSubmission) {
        Build build = buildRepository.findById(buildId).orElseThrow();
        List<TaskDefinitionEntity> tasks = planSubmission.getTasks();
        Map<String, Integer> criticalPathWeights = CriticalPathCalculator.weights(tasks);

        List<TaskRun> created = new ArrayList<>(tasks.size());
        for (TaskDefinitionEntity task : tasks) {
            TaskRun taskRun = new TaskRun(build, task.getTaskName(), task.getCacheKey());
            taskRun.setCriticalPathWeight(criticalPathWeights.getOrDefault(task.getTaskName(), 0));
            created.add(taskRunRepository.save(taskRun));
        }

        build = buildStateMachine.transition(buildId, build.getVersion(), BuildState.PLANNING);
        build = buildStateMachine.transition(buildId, build.getVersion(), BuildState.RUNNING);

        Long projectId = build.getProject().getId();
        for (int i = 0; i < tasks.size(); i++) {
            TaskDefinitionEntity task = tasks.get(i);
            TaskRun taskRun = created.get(i);
            if (ReadinessEvaluator.isImmediatelyReady(task, tasks)) {
                schedulerService.promoteToReadyOrCached(taskRun, projectId);
            }
        }
        // covers the all-cache-hit case: nothing will ever call claim/reportResult to notice
        // completion
        schedulerService.maybeCompleteBuild(buildId);
    }

    @Transactional
    public Build cancel(Long buildId) {
        Build build =
                buildRepository
                        .findById(buildId)
                        .orElseThrow(
                                () -> new NotFoundException("build " + buildId + " not found"));
        Build canceled =
                buildStateMachine.transition(buildId, build.getVersion(), BuildState.CANCELED);
        metrics.buildCanceled(Duration.between(canceled.getCreatedAt(), Instant.now()));
        return canceled;
    }

    @Transactional(readOnly = true)
    public Build get(Long buildId) {
        return buildRepository
                .findById(buildId)
                .orElseThrow(() -> new NotFoundException("build " + buildId + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<Build> history(Long projectId, Pageable pageable) {
        return buildRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable);
    }

    /**
     * Only artifacts that a real, verified upload has produced — this build's task runs' recorded
     * digests. Empty until phase 4/5 wire actual artifact upload.
     */
    @Transactional(readOnly = true)
    public List<Artifact> artifacts(Long buildId) {
        get(buildId);
        List<String> digests =
                taskRunRepository.findByBuildId(buildId).stream()
                        .map(TaskRun::getArtifactDigest)
                        .filter(d -> d != null)
                        .toList();
        return digests.isEmpty() ? List.of() : artifactRepository.findByDigestIn(digests);
    }
}
