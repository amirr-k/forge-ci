package dev.forgeci.core.model;

/** The {@code defaults} block of {@code forgeci.yml}, applied to any task that omits these fields. */
public record Defaults(String timeout, boolean cacheable) {}
