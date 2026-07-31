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
@Table(name = "builds")
public class Build {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_submission_id", nullable = false)
    private PlanSubmission planSubmission;

    @Column(nullable = false)
    private String revision;

    @Column(name = "base_revision", nullable = false)
    private String baseRevision;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BuildState state = BuildState.CREATED;

    @Column(name = "requested_worker_count", nullable = false)
    private int requestedWorkerCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Build() {}

    public Build(
            Project project,
            PlanSubmission planSubmission,
            String revision,
            String baseRevision,
            String triggerType,
            int requestedWorkerCount) {
        this.project = project;
        this.planSubmission = planSubmission;
        this.revision = revision;
        this.baseRevision = baseRevision;
        this.triggerType = triggerType;
        this.requestedWorkerCount = requestedWorkerCount;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public PlanSubmission getPlanSubmission() {
        return planSubmission;
    }

    public String getRevision() {
        return revision;
    }

    public String getBaseRevision() {
        return baseRevision;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public BuildState getState() {
        return state;
    }

    public void setState(BuildState state) {
        this.state = state;
    }

    public int getRequestedWorkerCount() {
        return requestedWorkerCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
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

    public long getVersion() {
        return version;
    }
}
