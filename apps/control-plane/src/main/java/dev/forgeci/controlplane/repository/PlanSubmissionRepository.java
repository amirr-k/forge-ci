package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.PlanSubmission;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanSubmissionRepository extends JpaRepository<PlanSubmission, Long> {

    Optional<PlanSubmission> findByProjectIdAndRevisionAndBaseRevision(
            Long projectId, String revision, String baseRevision);
}
