package dev.forgeci.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The retry policy itself — backoff growth and the attempt cap — separate from the integration
 * tests that observe it end to end. Both call sites that retry a task run ({@code reportResult} on
 * a failure and {@code reclaimIfStillExpired} on an expired lease) share these two numbers, so a
 * change to either shows up here first.
 */
class RetryPolicyTest {

    @Test
    void backoffGrowsExponentiallyFromTheFirstFailure() {
        assertThat(SchedulerService.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(SchedulerService.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(SchedulerService.backoff(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void backoffIsMonotonicAndStopsDoublingAtItsCeiling() {
        Duration ceiling = SchedulerService.backoff(4);
        for (int attempt = 5; attempt < 50; attempt++) {
            Duration next = SchedulerService.backoff(attempt);
            assertThat(next).isGreaterThanOrEqualTo(SchedulerService.backoff(attempt - 1));
            assertThat(next).isEqualTo(ceiling);
        }
    }

    @Test
    void everyBackoffTheAttemptCapAllowsIsPositiveAndBounded() {
        // the scheduler only ever waits between attempts it will actually make
        for (int attempt = 1; attempt <= SchedulerService.MAX_ATTEMPTS; attempt++) {
            Duration backoff = SchedulerService.backoff(attempt);
            assertThat(backoff).isPositive();
            assertThat(backoff).isLessThanOrEqualTo(Duration.ofMinutes(2));
        }
    }

    @Test
    void theAttemptCapLeavesRoomForRetriesWithoutRetryingForever() {
        assertThat(SchedulerService.MAX_ATTEMPTS).isGreaterThan(1);
        assertThat(SchedulerService.MAX_ATTEMPTS).isLessThan(10);
    }
}
