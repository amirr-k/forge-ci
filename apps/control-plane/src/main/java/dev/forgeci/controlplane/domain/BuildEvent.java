package dev.forgeci.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Map;

/** One ordered, immutable record of an accepted state transition. */
@Entity
@Table(name = "build_events")
public class BuildEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "build_id", nullable = false)
    private Build build;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private BuildEventType eventType;

    @ManyToOne
    @JoinColumn(name = "task_run_id")
    private TaskRun taskRun;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "json")
    private Map<String, Object> payload;

    protected BuildEvent() {}

    public BuildEvent(
            Build build, long sequenceNumber, BuildEventType eventType, TaskRun taskRun, Map<String, Object> payload) {
        this.build = build;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.taskRun = taskRun;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public Build getBuild() {
        return build;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public BuildEventType getEventType() {
        return eventType;
    }

    public TaskRun getTaskRun() {
        return taskRun;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
