package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.api.dto.TaskDefinitionRequest;
import dev.forgeci.controlplane.domain.PlanSubmission;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.repository.PlanSubmissionRepository;
import dev.forgeci.controlplane.repository.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanSubmissionService {

    private final ProjectRepository projectRepository;
    private final PlanSubmissionRepository planSubmissionRepository;

    public PlanSubmissionService(
            ProjectRepository projectRepository,
            PlanSubmissionRepository planSubmissionRepository) {
        this.projectRepository = projectRepository;
        this.planSubmissionRepository = planSubmissionRepository;
    }

    /**
     * Resubmitting the same (revision, base revision) pair for a project is idempotent: the
     * existing plan submission is returned rather than a duplicate row created.
     */
    @Transactional
    public PlanSubmission submit(Long projectId, PlanSubmissionRequest request) {
        Project project =
                projectRepository
                        .findById(projectId)
                        .orElseThrow(
                                () -> new NotFoundException("project " + projectId + " not found"));

        var existing =
                planSubmissionRepository.findByProjectIdAndRevisionAndBaseRevision(
                        projectId, request.revision(), request.baseRevision());
        if (existing.isPresent()) {
            PlanSubmission found = existing.get();
            found.getTasks()
                    .size(); // force-load while the session is still open for the DTO mapper
            return found;
        }

        PlanSubmission submission =
                new PlanSubmission(
                        project,
                        request.revision(),
                        request.baseRevision(),
                        request.fullBuild(),
                        request.changedPaths(),
                        request.unaffectedTasks());
        for (TaskDefinitionRequest task : request.tasks()) {
            submission.addTask(
                    task.name(),
                    task.dependsOn(),
                    task.cacheKey(),
                    task.reason(),
                    task.command(),
                    task.outputs(),
                    task.environment(),
                    task.timeoutSeconds());
        }

        try {
            return planSubmissionRepository.saveAndFlush(submission);
        } catch (DataIntegrityViolationException raceLostToConcurrentSubmit) {
            return planSubmissionRepository
                    .findByProjectIdAndRevisionAndBaseRevision(
                            projectId, request.revision(), request.baseRevision())
                    .orElseThrow(() -> raceLostToConcurrentSubmit);
        }
    }
}
