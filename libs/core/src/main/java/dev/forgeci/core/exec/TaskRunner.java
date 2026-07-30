package dev.forgeci.core.exec;

import dev.forgeci.core.model.TaskDefinition;
import java.time.Duration;

/** Executes a single task. Implementations never throw for task failure — they report it. */
public interface TaskRunner {

    TaskOutcome run(TaskDefinition task, Duration timeout, ExecutionListener listener);
}
