package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildEvent;
import dev.forgeci.controlplane.domain.BuildEventType;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.repository.BuildEventRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Assigns each build's event sequence numbers. Callers must already hold the pessimistic lock on
 * the {@link Build} row (see {@code BuildRepository#findByIdForUpdate}) so that concurrent
 * transitions on the same build cannot allocate the same sequence number.
 */
@Component
public class BuildEventPublisher {

    private final BuildEventRepository buildEventRepository;

    public BuildEventPublisher(BuildEventRepository buildEventRepository) {
        this.buildEventRepository = buildEventRepository;
    }

    public BuildEvent publish(Build build, BuildEventType type, TaskRun taskRun, Map<String, Object> payload) {
        long nextSequence = buildEventRepository.countByBuildId(build.getId()) + 1;
        BuildEvent event = new BuildEvent(build, nextSequence, type, taskRun, payload);
        return buildEventRepository.save(event);
    }
}
