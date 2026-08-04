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

    @Column(name = "lease_token")
    private String leaseToken;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "retry_at")
    private Instant retryAt;

    @Column(name = "critical_path_weight", nullable = false)
    private int criticalPathWeight;

    // estimated milliseconds remaining on the longest chain out of this task; 0 when no duration
    // history exists yet, which makes the duration policy degrade to this build's readyAt order
    @Column(name = "critical_path_millis", nullable = false)
    private long criticalPathMillis;

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

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(Instant readyAt) {
        this.readyAt = readyAt;
    }

    public Instant getRetryAt() {
        return retryAt;
    }

    public void setRetryAt(Instant retryAt) {
        this.retryAt = retryAt;
    }

    public int getCriticalPathWeight() {
        return criticalPathWeight;
    }

    public void setCriticalPathWeight(int criticalPathWeight) {
        this.criticalPathWeight = criticalPathWeight;
    }

    public long getCriticalPathMillis() {
        return criticalPathMillis;
    }

    public void setCriticalPathMillis(long criticalPathMillis) {
        this.criticalPathMillis = criticalPathMillis;
    }
}
