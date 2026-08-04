package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The scheduling baseline: same eligibility rule, ordered only by the time each task became
     * ready. Selected by {@code forge.scheduler.policy=fifo}. Exists so the critical-path policy
     * can be measured against something rather than asserted — see benchmarks.md.
     */
    @Query(
            "select t from TaskRun t where t.state = dev.forgeci.controlplane.domain.TaskRunState.READY "
                    + "and t.build.state = dev.forgeci.controlplane.domain.BuildState.RUNNING "
                    + "order by t.readyAt asc, t.id asc")
    List<TaskRun> findClaimCandidatesFifo(Pageable pageable);

    /**
     * Duration-aware critical path: orders by estimated remaining milliseconds on the longest chain
     * out of this task rather than by hop count, so a long chain of cheap tasks stops outranking a
     * short chain of expensive ones. Selected by {@code
     * forge.scheduler.policy=critical-path-duration}.
     */
    @Query(
            "select t from TaskRun t where t.state = dev.forgeci.controlplane.domain.TaskRunState.READY "
                    + "and t.build.state = dev.forgeci.controlplane.domain.BuildState.RUNNING "
                    + "order by t.criticalPathMillis desc, t.readyAt asc, t.id asc")
    List<TaskRun> findClaimCandidatesByDuration(Pageable pageable);

    /**
     * Observed durations of previously completed runs of a task within one project — the history
     * the duration-aware policy estimates from. Ordered newest first so a caller can bound how far
     * back it looks.
     */
    @Query(
            "select t.taskName, t.startedAt, t.completedAt from TaskRun t "
                    + "where t.build.project.id = :projectId "
                    + "and t.state = dev.forgeci.controlplane.domain.TaskRunState.SUCCEEDED "
                    + "and t.startedAt is not null and t.completedAt is not null "
                    + "order by t.completedAt desc")
    List<Object[]> findRecentDurations(@Param("projectId") Long projectId, Pageable pageable);

    List<TaskRun> findByStateAndRetryAtBefore(TaskRunState state, Instant cutoff);

    List<TaskRun> findByStateInAndLeaseExpirationBefore(List<TaskRunState> states, Instant cutoff);

    List<TaskRun> findByStateIn(List<TaskRunState> states);
}
