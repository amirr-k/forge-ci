package dev.forgeci.core.exec;

import java.util.List;

/**
 * Receives execution progress as it happens. Implementations are called from task threads and must
 * be thread-safe.
 */
public interface ExecutionListener {

    ExecutionListener NONE = new ExecutionListener() {};

    default void taskStarted(String task, List<String> command) {}

    /** One line of merged stdout/stderr from a running task. */
    default void taskOutput(String task, String line) {}

    default void taskFinished(TaskOutcome outcome) {}
}
