package dev.forgeci.worker;

/**
 * The worker side of crash injection: an admin request marks a pending crash on the control plane,
 * delivered back on this worker's next heartbeat response. Firing calls {@code haltAction} — in
 * production that's {@code Runtime.halt}, an abrupt, no-shutdown-hook JVM stop that leaves any
 * in-flight task's lease to simply expire, faithfully simulating a real crash rather than a
 * graceful shutdown. Injectable so tests can observe the trigger firing without killing the test
 * JVM.
 */
final class CrashTrigger {

    private final Runnable haltAction;

    CrashTrigger(Runnable haltAction) {
        this.haltAction = haltAction;
    }

    void maybeCrash(boolean shouldCrash) {
        if (shouldCrash) {
            haltAction.run();
        }
    }
}
