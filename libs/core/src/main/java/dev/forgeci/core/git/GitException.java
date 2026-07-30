package dev.forgeci.core.git;

/**
 * Local Git metadata could not be read: no repository, no Git executable, or a failing Git
 * command. The message is written for a developer to act on and is printed as-is by the CLI.
 */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }
}
