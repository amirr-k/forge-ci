package dev.forgeci.controlplane.kafka;

import dev.forgeci.controlplane.domain.BuildEvent;
import java.time.Instant;
import java.util.Map;

/** {@code forge.build-events} message schema, version 1 — a durable mirror of one already-accepted {@code BuildEvent} row. */
public record BuildEventMessage(
        int schemaVersion,
        long buildId,
        long sequenceNumber,
        String eventType,
        Long taskRunId,
        Instant occurredAt,
        Map<String, Object> payload) {

    public static BuildEventMessage from(BuildEvent event) {
        return new BuildEventMessage(
                1,
                event.getBuild().getId(),
                event.getSequenceNumber(),
                event.getEventType().name(),
                event.getTaskRun() == null ? null : event.getTaskRun().getId(),
                event.getOccurredAt(),
                event.getPayload());
    }
}
