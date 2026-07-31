package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.Build;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuildRepository extends JpaRepository<Build, Long> {

    Optional<Build> findByProjectIdAndPlanSubmissionId(Long projectId, Long planSubmissionId);

    Page<Build> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    /**
     * Locks the build row for the duration of the transaction so a state transition and its
     * {@code BuildEvent} sequence-number allocation happen atomically under concurrent submissions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Build b where b.id = :id")
    Optional<Build> findByIdForUpdate(@Param("id") Long id);
}
