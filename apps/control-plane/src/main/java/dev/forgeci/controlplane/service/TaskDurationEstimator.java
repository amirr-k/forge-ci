package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.repository.TaskRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estimates how long a task will take from how long it actually took before, within the same
 * project. Feeds duration-aware critical-path scheduling and straggler detection.
 *
 * <p>Median rather than mean: build task durations have a long right tail (a cold JVM, a noisy
 * neighbour, one unlucky GC pause), and a mean lets a single outlier distort the estimate for every
 * later build. Median of the most recent observations tracks a genuine shift in cost without
 * chasing one bad sample.
 *
 * <p>Estimates are advisory in both places they are used. A wrong estimate makes the scheduler
 * order tasks suboptimally or makes speculation fire at the wrong moment; it can never make a
 * result incorrect, because correctness rests on lease/attempt checks, not on timing.
 */
@Service
public class TaskDurationEstimator {

    private final TaskRunRepository taskRunRepository;
    private final int historyLimit;
    private final long defaultEstimateMillis;

    public TaskDurationEstimator(
            TaskRunRepository taskRunRepository,
            @Value("${forge.scheduler.duration-history-limit:2000}") int historyLimit,
            @Value("${forge.scheduler.default-task-estimate-ms:1000}") long defaultEstimateMillis) {
        this.taskRunRepository = taskRunRepository;
        this.historyLimit = historyLimit;
        this.defaultEstimateMillis = defaultEstimateMillis;
    }

    /** Median observed duration per task name for one project, newest {@code historyLimit} runs. */
    @Transactional(readOnly = true)
    public Map<String, Long> medianDurationsByTaskName(Long projectId) {
        Map<String, List<Long>> observed = new HashMap<>();
        for (Object[] row :
                taskRunRepository.findRecentDurations(projectId, PageRequest.of(0, historyLimit))) {
            String taskName = (String) row[0];
            Instant startedAt = (Instant) row[1];
            Instant completedAt = (Instant) row[2];
            if (startedAt == null || completedAt == null) {
                continue;
            }
            long millis = Duration.between(startedAt, completedAt).toMillis();
            if (millis >= 0) {
                observed.computeIfAbsent(taskName, name -> new ArrayList<>()).add(millis);
            }
        }

        Map<String, Long> medians = new HashMap<>();
        for (Map.Entry<String, List<Long>> entry : observed.entrySet()) {
            List<Long> samples = entry.getValue();
            samples.sort(null);
            medians.put(entry.getKey(), samples.get(samples.size() / 2));
        }
        return medians;
    }

    public long defaultEstimateMillis() {
        return defaultEstimateMillis;
    }
}
