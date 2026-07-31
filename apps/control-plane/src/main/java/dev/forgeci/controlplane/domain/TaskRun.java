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
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "task_runs")
public class TaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "build_id", nullable = false)
    private Build build;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Column(name = "cache_key", nullable = false)
    private String cacheKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskRunState state = TaskRunState.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "lease_expiration")
    private Instant leaseExpiration;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "artifact_digest")
    private String artifactDigest;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    @Column(nullable = false)
    private long version;

    protected TaskRun() {}

    public TaskRun(Build build, String taskName, String cacheKey) {
        this.build = build;
        this.taskName = taskName;
        this.cacheKey = cacheKey;
    }

    public Long getId() {
        return id;
    }

    public Build getBuild() {
        return build;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public TaskRunState getState() {
        return state;
    }

    public void setState(TaskRunState state) {
        this.state = state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
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

    public String getArtifactDigest() {
        return artifactDigest;
    }

    public void setArtifactDigest(String artifactDigest) {
        this.artifactDigest = artifactDigest;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public long getVersion() {
        return version;
    }
}
