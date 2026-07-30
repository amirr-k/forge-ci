package dev.forgeci.cli;

/**
 * A problem the user can fix. The message must name what is wrong, where, and what to do about it;
 * it is printed on its own with no stack trace.
 */
class CliException extends RuntimeException {

    CliException(String message) {
        super(message);
    }
}
