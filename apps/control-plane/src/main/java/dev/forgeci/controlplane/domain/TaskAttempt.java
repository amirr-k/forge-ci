package dev.forgeci.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "task_attempts")
public class TaskAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "task_run_id", nullable = false)
    private TaskRun taskRun;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskRunState state;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "failure_reason")
    private String failureReason;

    // the lease lives here rather than on task_runs: a straggler and its speculative duplicate are
    // both in flight against the same run, so each needs its own token to be told apart on report
    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "lease_expiration")
    private Instant leaseExpiration;

    @Column(nullable = false)
    private boolean speculative;

    protected TaskAttempt() {}

    public TaskAttempt(
            TaskRun taskRun, int attemptNumber, TaskRunState state, boolean speculative) {
        this.taskRun = taskRun;
        this.attemptNumber = attemptNumber;
        this.state = state;
        this.speculative = speculative;
        this.startedAt = Instant.now();
    }

    /**
     * Holds a lease that has not yet been resolved, expired, or superseded by a winning sibling.
     */
    public boolean isLive() {
        return state == TaskRunState.LEASED || state == TaskRunState.RUNNING;
    }

    public Long getId() {
        return id;
    }

    public TaskRun getTaskRun() {
        return taskRun;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public TaskRunState getState() {
        return state;
    }

    public void setState(TaskRunState state) {
        this.state = state;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Long getWorkerId() {
        return workerId;
    }

    public void setWorkerId(Long workerId) {
        this.workerId = workerId;
    }

    public Instant getLeaseExpiration() {
        return leaseExpiration;
    }

    public void setLeaseExpiration(Instant leaseExpiration) {
        this.leaseExpiration = leaseExpiration;
    }

    public boolean isSpeculative() {
        return speculative;
    }
}
