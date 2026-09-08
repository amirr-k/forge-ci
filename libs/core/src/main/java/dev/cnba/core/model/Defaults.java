package dev.cnba.core.model;

/**
 * The {@code defaults} block of {@code cnba.yml}, applied to any task that omits these fields.
 */
public record Defaults(String timeout, boolean cacheable) {}
