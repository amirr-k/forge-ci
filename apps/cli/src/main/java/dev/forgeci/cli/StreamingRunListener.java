package dev.forgeci.cli;

import dev.forgeci.core.exec.Durations;
import dev.forgeci.core.exec.ExecutionListener;
import dev.forgeci.core.exec.TaskOutcome;
import java.io.PrintWriter;
import java.util.List;

/**
 * Prefixes every line with the task that produced it, so concurrent tasks stay readable. Writes are
 * serialized because tasks report from their own threads.
 */
final class StreamingRunListener implements ExecutionListener {

    private final PrintWriter out;

    StreamingRunListener(PrintWriter out) {
        this.out = out;
    }

    @Override
    public void taskStarted(String task, List<String> command) {
        write(task, "> " + String.join(" ", command));
    }

    @Override
    public void taskOutput(String task, String line) {
        write(task, line);
    }

    @Override
    public void taskFinished(TaskOutcome outcome) {
        String detail = outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")";
        String elapsed =
                outcome.duration().isZero() ? "" : " in " + Durations.format(outcome.duration());
        write(outcome.task(), outcome.status() + detail + elapsed);
    }

    private synchronized void write(String task, String line) {
        out.println("[" + task + "] " + line);
        out.flush();
    }
}
