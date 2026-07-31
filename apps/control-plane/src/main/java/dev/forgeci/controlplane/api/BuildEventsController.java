package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.api.dto.BuildEventResponse;
import dev.forgeci.controlplane.domain.BuildEvent;
import dev.forgeci.controlplane.repository.BuildEventRepository;
import dev.forgeci.controlplane.service.BuildService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live build progress for the public demo — Server-Sent Events over a short poll of MySQL's
 * ordered event log (BuildEventRepository), the one-way "push task/build state to the browser"
 * case contracts.md leaves open between SSE and WebSocket. Polling MySQL directly rather than
 * Kafka keeps this endpoint correct even when the Kafka mirror lags or drops a message — MySQL is
 * still the source of truth for accepted state (contracts.md#redis-responsibilities table).
 */
@RestController
public class BuildEventsController {

    private static final long POLL_INTERVAL_MS = 500;
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000;

    private final BuildEventRepository buildEventRepository;
    private final BuildService buildService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public BuildEventsController(BuildEventRepository buildEventRepository, BuildService buildService) {
        this.buildEventRepository = buildEventRepository;
        this.buildService = buildService;
    }

    @GetMapping("/api/builds/{id}/events")
    public SseEmitter events(@PathVariable("id") Long buildId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        long[] lastSequence = {0};
        var task =
                scheduler.scheduleAtFixedRate(
                        () -> poll(buildId, emitter, lastSequence), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> task.cancel(true));
        emitter.onTimeout(() -> task.cancel(true));
        emitter.onError(t -> task.cancel(true));
        return emitter;
    }

    private void poll(Long buildId, SseEmitter emitter, long[] lastSequence) {
        try {
            List<BuildEvent> events = buildEventRepository.findByBuildIdOrderBySequenceNumberAsc(buildId);
            for (BuildEvent event : events) {
                if (event.getSequenceNumber() > lastSequence[0]) {
                    emitter.send(SseEmitter.event().name("build-event").data(BuildEventResponse.from(event)));
                    lastSequence[0] = event.getSequenceNumber();
                }
            }
            if (buildService.get(buildId).getState().isTerminal()) {
                emitter.complete();
            }
        } catch (Exception failure) {
            emitter.completeWithError(failure);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
