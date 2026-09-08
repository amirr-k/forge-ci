package dev.cnba.cli;

/** Process exit codes. Stable across phases — CI pipelines branch on these. */
final class ExitCode {

    static final int SUCCESS = 0;

    /** The build ran but a task failed, timed out, or was skipped because a dependency failed. */
    static final int BUILD_FAILED = 1;

    /** CNBA could not run: bad configuration, bad usage, or a missing prerequisite. */
    static final int USER_ERROR = 2;

    private ExitCode() {}
}
