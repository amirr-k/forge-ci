package dev.forgeci.controlplane.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Fires the one real warm-up build after startup — best-effort, never blocks readiness on it.
 * Disabled in integration tests ({@code forge.demo.warmup.enabled=false}): every test in this
 * module shares the one Testcontainers Redis instance, and without a real worker present a
 * warm-up build never reaches a terminal state, so it would hold the single guest-build slot for
 * its full TTL and make every demo test in the suite flaky depending on startup timing.
 */
@Component
@ConditionalOnProperty(prefix = "forge.demo.warmup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoWarmupRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoWarmupRunner.class);

    private final DemoScenarioService demoScenarioService;

    public DemoWarmupRunner(DemoScenarioService demoScenarioService) {
        this.demoScenarioService = demoScenarioService;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            demoScenarioService.warmUp();
        } catch (RuntimeException warmupFailure) {
            log.warn("demo warm-up build failed to start, first guest visit will build cold: {}", warmupFailure.getMessage());
        }
    }
}
