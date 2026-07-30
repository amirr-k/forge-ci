package dev.forgeci.cache;

/**
 * Identifies the toolchain a task runs under. Local mode has exactly one toolchain axis today —
 * the JVM running the CLI itself — so an incompatible Java upgrade invalidates every cache key.
 */
public final class ToolchainFingerprint {

    private ToolchainFingerprint() {}

    public static String current() {
        return "Java " + Runtime.version();
    }
}
