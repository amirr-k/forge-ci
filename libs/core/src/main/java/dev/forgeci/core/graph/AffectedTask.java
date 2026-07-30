package dev.forgeci.core.graph;

/**
 * A task included in an affected-task closure.
 *
 * @param name the task name
 * @param reason human-readable cause, e.g. {@code "source changed"} or
 *     {@code "pricing:build output may change"}
 */
public record AffectedTask(String name, String reason) {}
