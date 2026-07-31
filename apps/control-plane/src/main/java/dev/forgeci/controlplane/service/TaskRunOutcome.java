package dev.forgeci.controlplane.service;

/** Optional detail attached to a {@link TaskRunStateMachine} transition landing on an attempt's end. */
public record TaskRunOutcome(Integer exitCode, String failureReason, String artifactDigest) {

    public static final TaskRunOutcome NONE = new TaskRunOutcome(null, null, null);
}
