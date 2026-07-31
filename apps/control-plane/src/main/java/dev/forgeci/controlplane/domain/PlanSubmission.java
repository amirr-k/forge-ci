package dev.forgeci.controlplane.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One accepted {@code forge plan} submission for a project — the input a build is created from. */
@Entity
@Table(name = "plan_submissions")
public class PlanSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String revision;

    @Column(name = "base_revision", nullable = false)
    private String baseRevision;

    @Column(name = "full_build", nullable = false)
    private boolean fullBuild;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "changed_paths", nullable = false, columnDefinition = "json")
    private List<String> changedPaths;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "unaffected_tasks", nullable = false, columnDefinition = "json")
    private List<String> unaffectedTasks;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @OneToMany(mappedBy = "planSubmission", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaskDefinitionEntity> tasks = new ArrayList<>();

    protected PlanSubmission() {}

    public PlanSubmission(
            Project project,
            String revision,
            String baseRevision,
            boolean fullBuild,
            List<String> changedPaths,
            List<String> unaffectedTasks) {
        this.project = project;
        this.revision = revision;
        this.baseRevision = baseRevision;
        this.fullBuild = fullBuild;
        this.changedPaths = changedPaths;
        this.unaffectedTasks = unaffectedTasks;
    }

    public void addTask(String taskName, List<String> dependsOn, String cacheKey, String reason) {
        tasks.add(new TaskDefinitionEntity(this, taskName, dependsOn, cacheKey, reason));
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getRevision() {
        return revision;
    }

    public String getBaseRevision() {
        return baseRevision;
    }

    public boolean isFullBuild() {
        return fullBuild;
    }

    public List<String> getChangedPaths() {
        return changedPaths;
    }

    public List<String> getUnaffectedTasks() {
        return unaffectedTasks;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public List<TaskDefinitionEntity> getTasks() {
        return tasks;
    }
}
