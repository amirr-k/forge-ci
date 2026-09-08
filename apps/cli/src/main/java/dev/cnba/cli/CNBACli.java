package dev.cnba.cli;

import dev.cnba.core.git.GitException;
import dev.cnba.core.graph.CycleDetectedException;
import dev.cnba.core.validation.ConfigValidationException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "cnba",
        description = "Build only what changed.",
        mixinStandardHelpOptions = true,
        version = "cnba 0.1.0-SNAPSHOT",
        subcommands = {
            InitCommand.class,
            PlanCommand.class,
            RunCommand.class,
            ExplainCommand.class,
            DoctorCommand.class
        })
public final class CNBACli implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /**
     * The configured entry point used by both {@code main} and the tests. Expected failures — bad
     * configuration, a cyclic graph, no repository — print their own message and nothing else; a
     * stack trace would only bury the remediation.
     */
    public static CommandLine commandLine() {
        return new CommandLine(new CNBACli())
                .setExecutionExceptionHandler(
                        (exception, command, parseResult) -> {
                            if (exception instanceof CliException
                                    || exception instanceof ConfigValidationException
                                    || exception instanceof CycleDetectedException
                                    || exception instanceof GitException) {
                                command.getErr().println("cnba: " + exception.getMessage());
                                command.getErr().flush();
                                return ExitCode.USER_ERROR;
                            }
                            throw exception;
                        });
    }
}
