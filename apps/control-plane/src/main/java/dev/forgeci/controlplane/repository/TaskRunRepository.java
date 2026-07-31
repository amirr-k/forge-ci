package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    List<TaskRun> findByBuildId(Long buildId);

    Optional<TaskRun> findByBuildIdAndTaskName(Long buildId, String taskName);

    long countByState(TaskRunState state);

    /**
     * READY task runs belonging to a still-RUNNING build, ordered by the scheduler's fixed
     * tie-break: highest remaining critical-path weight first, then FIFO by the time each task
     * actually became ready. Canceled/finished builds never surface a candidate here, which is how
     * the scheduler stops leasing work for them.
     */
    @Query(
            "select t from TaskRun t where t.state = dev.forgeci.controlplane.domain.TaskRunState.READY "
                    + "and t.build.state = dev.forgeci.controlplane.domain.BuildState.RUNNING "
                    + "order by t.criticalPathWeight desc, t.readyAt asc, t.id asc")
    List<TaskRun> findClaimCandidates(Pageable pageable);

    List<TaskRun> findByStateAndRetryAtBefore(TaskRunState state, Instant cutoff);

    List<TaskRun> findByStateInAndLeaseExpirationBefore(List<TaskRunState> states, Instant cutoff);

    List<TaskRun> findByStateIn(List<TaskRunState> states);
}
