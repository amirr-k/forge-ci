package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildEventType;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.repository.BuildRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Valid {@link Build} transitions and their side effects. Every accepted transition emits exactly
 * one ordered {@code BuildEvent}; terminal states never transition; a caller working from a stale
 * copy of the build is rejected rather than allowed to silently overwrite a newer state.
 */
@Component
public class BuildStateMachine {

    private static final Map<BuildState, Set<BuildState>> ALLOWED =
            Map.of(
                    BuildState.CREATED, Set.of(BuildState.PLANNING, BuildState.CANCELED),
                    BuildState.PLANNING, Set.of(BuildState.RUNNING, BuildState.CANCELED, BuildState.FAILED),
                    BuildState.RUNNING,
                            Set.of(BuildState.SUCCEEDED, BuildState.FAILED, BuildState.CANCELED),
                    BuildState.SUCCEEDED, Set.of(),
                    BuildState.FAILED, Set.of(),
                    BuildState.CANCELED, Set.of());

    private static final Map<BuildState, BuildEventType> EVENT_FOR_TARGET =
            Map.of(
                    BuildState.PLANNING, BuildEventType.BUILD_PLANNING,
                    BuildState.RUNNING, BuildEventType.BUILD_RUNNING,
                    BuildState.SUCCEEDED, BuildEventType.BUILD_SUCCEEDED,
                    BuildState.FAILED, BuildEventType.BUILD_FAILED,
                    BuildState.CANCELED, BuildEventType.BUILD_CANCELED);

    private final BuildRepository buildRepository;
    private final BuildEventPublisher events;

    public BuildStateMachine(BuildRepository buildRepository, BuildEventPublisher events) {
        this.buildRepository = buildRepository;
        this.events = events;
    }

    /**
     * Transitions the build with id {@code buildId} to {@code target}, using a row lock to
     * serialize concurrent attempts. Rejects the transition (without changing state or emitting an
     * event) if it isn't legal from the current state, or if {@code expectedVersion} no longer
     * matches — the same protection an optimistic-locked update would give a caller that already
     * held a copy of the row.
     */
    @Transactional
    public Build transition(Long buildId, long expectedVersion, BuildState target) {
        Build build =
                buildRepository
                        .findByIdForUpdate(buildId)
                        .orElseThrow(() -> new NotFoundException("build " + buildId + " not found"));

        if (build.getVersion() != expectedVersion) {
            throw new StaleTransitionException(
                    "build " + buildId + " expected version " + expectedVersion + " but was " + build.getVersion());
        }

        BuildState current = build.getState();
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new InvalidTransitionException("build " + buildId + " cannot move from " + current + " to " + target);
        }

        build.setState(target);
        Instant now = Instant.now();
        if (target == BuildState.RUNNING) {
            build.setStartedAt(now);
        } else if (target.isTerminal()) {
            build.setCompletedAt(now);
        }
        // flush now so the returned entity's bumped version is visible to a caller chaining transitions
        Build saved = buildRepository.saveAndFlush(build);

        events.publish(
                saved,
                EVENT_FOR_TARGET.get(target),
                null,
                Map.of("buildId", saved.getId(), "from", current.name(), "to", target.name()));
        return saved;
    }
}
