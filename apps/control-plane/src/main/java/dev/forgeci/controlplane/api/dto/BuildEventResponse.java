package dev.forgeci.controlplane.api.dto;

import dev.forgeci.controlplane.domain.BuildEvent;
import java.time.Instant;
import java.util.Map;

public record BuildEventResponse(
        long sequenceNumber, String eventType, Long taskRunId, String taskName, Instant occurredAt, Map<String, Object> payload) {

    public static BuildEventResponse from(BuildEvent event) {
        return new BuildEventResponse(
                event.getSequenceNumber(),
                event.getEventType().name(),
                event.getTaskRun() != null ? event.getTaskRun().getId() : null,
                event.getTaskRun() != null ? event.getTaskRun().getTaskName() : null,
                event.getOccurredAt(),
                event.getPayload());
    }
}
