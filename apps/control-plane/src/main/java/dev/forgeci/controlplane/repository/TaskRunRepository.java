package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * The atomicity point for accepting one result out of several concurrent attempts. Conditional
     * on the winner still being unset, so of N simultaneous reporters exactly one sees a return
     * value of 1 and may apply its result; the rest see 0 and are rejected as duplicates.
     * Deliberately a single statement rather than a read-then-write: the database's own row lock
     * decides the winner, which is what makes duplicate execution safe without ever claiming
     * exactly-once execution.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update TaskRun t set t.winningAttemptNumber = :attemptNumber "
                    + "where t.id = :id and t.winningAttemptNumber is null")
    int claimWinningAttempt(@Param("id") Long id, @Param("attemptNumber") int attemptNumber);

    /**
     * How many of a build's task runs are not yet finished, as a scalar straight from the database.
     *
     * <p>Deliberately a count and not {@code findByBuildId(...).allMatch(...)}: loading entities
     * returns whatever instance is already in the persistence context, and Hibernate does not
     * refresh a managed entity's fields for a repeat query. A completion check that had already
     * touched a sibling task run earlier in the same transaction would therefore keep seeing that
     * sibling's stale state even after another transaction committed it as SUCCEEDED — leaving
     * every task finished and the build stuck RUNNING. An aggregate has no managed instance to be
     * stale, so it always reflects the latest committed rows.
     */
    @Query(
            "select count(t) from TaskRun t where t.build.id = :buildId "
                    + "and t.state not in (dev.forgeci.controlplane.domain.TaskRunState.SUCCEEDED, "
                    + "dev.forgeci.controlplane.domain.TaskRunState.CACHED)")
    long countUnfinished(@Param("buildId") Long buildId);

    List<TaskRun> findByStateAndRetryAtBefore(TaskRunState state, Instant cutoff);

    List<TaskRun> findByStateInAndLeaseExpirationBefore(List<TaskRunState> states, Instant cutoff);

    List<TaskRun> findByStateIn(List<TaskRunState> states);
}
