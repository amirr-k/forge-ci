package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.api.dto.ProjectRegistrationRequest;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.repository.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /** Re-registering an existing project name returns the existing project rather than a duplicate. */
    @Transactional
    public Project register(ProjectRegistrationRequest request) {
        var existing = projectRepository.findByName(request.name());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return projectRepository.saveAndFlush(
                    new Project(request.name(), request.repositoryIdentity(), request.defaultBranch(), request.configVersion()));
        } catch (DataIntegrityViolationException raceLostToConcurrentRegister) {
            return projectRepository.findByName(request.name()).orElseThrow(() -> raceLostToConcurrentRegister);
        }
    }

    @Transactional(readOnly = true)
    public Project get(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> new NotFoundException("project " + projectId + " not found"));
    }
}
