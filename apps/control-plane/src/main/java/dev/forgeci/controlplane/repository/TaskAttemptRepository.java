package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.TaskAttempt;
import dev.forgeci.controlplane.domain.TaskRunState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    List<TaskAttempt> findByTaskRunIdOrderByAttemptNumber(Long taskRunId);

    Optional<TaskAttempt> findByTaskRunIdAndAttemptNumber(Long taskRunId, int attemptNumber);

    /** Attempts still holding a lease on one task run, newest first. */
    @Query(
            "select a from TaskAttempt a where a.taskRun.id = :taskRunId "
                    + "and a.state in (dev.forgeci.controlplane.domain.TaskRunState.LEASED, "
                    + "dev.forgeci.controlplane.domain.TaskRunState.RUNNING) "
                    + "order by a.attemptNumber desc")
    List<TaskAttempt> findLiveByTaskRunId(@Param("taskRunId") Long taskRunId);

    /** The unconditional expiry safety net, run against each attempt's own lease deadline. */
    @Query(
            "select a from TaskAttempt a where a.state in "
                    + "(dev.forgeci.controlplane.domain.TaskRunState.LEASED, "
                    + "dev.forgeci.controlplane.domain.TaskRunState.RUNNING) "
                    + "and a.leaseExpiration is not null and a.leaseExpiration < :cutoff")
    List<TaskAttempt> findExpired(@Param("cutoff") Instant cutoff);

    /**
     * Speculation candidates: the sole live attempt of a task run whose build is still RUNNING,
     * started before {@code startedBefore} and not itself speculative. The "sole live attempt" and
     * per-build budget checks are applied by the caller, which holds the row locks that make them
     * meaningful; this only narrows the scan.
     */
    @Query(
            "select a from TaskAttempt a where a.state = "
                    + "dev.forgeci.controlplane.domain.TaskRunState.RUNNING "
                    + "and a.speculative = false and a.startedAt < :startedBefore "
                    + "and a.taskRun.state = :runState "
                    + "and a.taskRun.winningAttemptNumber is null "
                    + "and a.taskRun.build.state = dev.forgeci.controlplane.domain.BuildState.RUNNING "
                    + "order by a.startedAt asc")
    List<TaskAttempt> findStragglerCandidates(
            @Param("startedBefore") Instant startedBefore,
            @Param("runState") TaskRunState runState,
            org.springframework.data.domain.Pageable pageable);

    long countByTaskRunBuildIdAndSpeculativeTrue(Long buildId);
}
