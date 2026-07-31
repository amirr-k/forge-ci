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

    protected TaskAttempt() {}

    public TaskAttempt(TaskRun taskRun, int attemptNumber, TaskRunState state) {
        this.taskRun = taskRun;
        this.attemptNumber = attemptNumber;
        this.state = state;
        this.startedAt = Instant.now();
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
}
