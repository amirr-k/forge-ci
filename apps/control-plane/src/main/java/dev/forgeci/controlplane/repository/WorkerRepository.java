package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.domain.WorkerState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkerRepository extends JpaRepository<Worker, Long> {

    Optional<Worker> findByExternalId(String externalId);

    /** Locked so a claim's read-then-increment of {@code activeLeaseCount} is race-free. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Worker w where w.id = :id")
    Optional<Worker> findByIdForUpdate(@Param("id") Long id);

    long countByState(WorkerState state);

    List<Worker> findByStateAndLastHeartbeatAtBefore(WorkerState state, Instant cutoff);
}
