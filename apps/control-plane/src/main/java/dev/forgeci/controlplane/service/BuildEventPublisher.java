package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildEvent;
import dev.forgeci.controlplane.domain.BuildEventType;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.kafka.BuildEventMessage;
import dev.forgeci.controlplane.kafka.KafkaTopics;
import dev.forgeci.controlplane.repository.BuildEventRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Assigns each build's event sequence numbers. Callers must already hold the pessimistic lock on
 * the {@link Build} row (see {@code BuildRepository#findByIdForUpdate}) so that concurrent
 * transitions on the same build cannot allocate the same sequence number.
 *
 * <p>Also mirrors the just-accepted (and already MySQL-committed-by-the-time-flush-returns) event
 * to {@code forge.build-events} — best-effort: a Kafka outage never fails the transition itself,
 * since Kafka is delivery only, not the state of truth.
 */
@Component
public class BuildEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BuildEventPublisher.class);

    private final BuildEventRepository buildEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public BuildEventPublisher(BuildEventRepository buildEventRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.buildEventRepository = buildEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public BuildEvent publish(Build build, BuildEventType type, TaskRun taskRun, Map<String, Object> payload) {
        long nextSequence = buildEventRepository.countByBuildId(build.getId()) + 1;
        BuildEvent event = new BuildEvent(build, nextSequence, type, taskRun, payload);
        BuildEvent saved = buildEventRepository.save(event);

        try {
            kafkaTemplate.send(KafkaTopics.BUILD_EVENTS, String.valueOf(build.getId()), BuildEventMessage.from(saved));
        } catch (RuntimeException kafkaUnavailable) {
            log.warn("failed to mirror build event {} to Kafka: {}", saved.getId(), kafkaUnavailable.getMessage());
        }
        return saved;
    }
}
