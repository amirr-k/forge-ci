package dev.forgeci.core.exec;

import java.time.Duration;
import java.util.List;

/** The result of one local run: every selected task's outcome, in plan order, plus wall-clock time. */
public record ExecutionReport(List<TaskOutcome> outcomes, Duration wallClock) {

    public ExecutionReport {
        outcomes = List.copyOf(outcomes);
    }

    public boolean succeeded() {
        return outcomes.stream().allMatch(outcome -> outcome.status() == TaskStatus.SUCCEEDED);
    }

    public long count(TaskStatus status) {
        return outcomes.stream().filter(outcome -> outcome.status() == status).count();
    }
}
