package dev.forgeci.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;

/** A single selected-to-run task within one {@link PlanSubmission}. */
@Entity
@Table(name = "task_definitions")
public class TaskDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_submission_id", nullable = false)
    private PlanSubmission planSubmission;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "depends_on", nullable = false, columnDefinition = "json")
    private List<String> dependsOn;

    @Column(name = "cache_key", nullable = false)
    private String cacheKey;

    @Column(nullable = false)
    private String reason;

    protected TaskDefinitionEntity() {}

    public TaskDefinitionEntity(
            PlanSubmission planSubmission, String taskName, List<String> dependsOn, String cacheKey, String reason) {
        this.planSubmission = planSubmission;
        this.taskName = taskName;
        this.dependsOn = dependsOn;
        this.cacheKey = cacheKey;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getTaskName() {
        return taskName;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getReason() {
        return reason;
    }
}
