package dev.forgeci.controlplane.demo;

import dev.forgeci.controlplane.service.BuildService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Releases the single guest-build slot once every build in one guest visit (baseline + incremental
 * run concurrently) reaches a terminal state, rather than at submission time — workers keep
 * mutating the shared demo workspace for as long as either is still running, so the next guest's
 * scenario mutation must wait for both, not just for this request to return. The lock's own TTL
 * (DemoGuestGuard) is the backstop if this watcher never observes a terminal state (process
 * restart, a build stuck forever).
 */
@Component
public class DemoBuildWatcher {

    private final BuildService buildService;
    private final DemoGuestGuard guard;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public DemoBuildWatcher(BuildService buildService, DemoGuestGuard guard) {
        this.buildService = buildService;
        this.guard = guard;
    }

    public void watch(List<Long> buildIds, String buildToken) {
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        ScheduledFuture<?> future =
                scheduler.scheduleAtFixedRate(
                        () -> poll(buildIds, buildToken, self), 1, 1, TimeUnit.SECONDS);
        self.set(future);
    }

    private void poll(
            List<Long> buildIds, String buildToken, AtomicReference<ScheduledFuture<?>> self) {
        boolean allTerminal;
        try {
            allTerminal =
                    buildIds.stream().allMatch(id -> buildService.get(id).getState().isTerminal());
        } catch (RuntimeException transientLookupFailure) {
            return; // keep polling; a lookup hiccup isn't proof the builds are gone
        }
        if (allTerminal) {
            guard.releaseBuildSlot(buildToken);
            ScheduledFuture<?> future = self.get();
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
