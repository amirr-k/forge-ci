package dev.forgeci.cli;

import dev.forgeci.core.plan.PlanBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Writes a starting {@code forgeci.yml}. Never touches an existing one. */
@Command(name = "init", description = "Create a starting forgeci.yml in the current directory.")
final class InitCommand implements Callable<Integer> {

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        Path directory = ProjectWorkspace.currentDirectory();
        Path configFile = directory.resolve(PlanBuilder.CONFIG_FILE);

        try {
            // CREATE_NEW rather than an exists() check, so two concurrent inits cannot both write
            Files.writeString(configFile, template(projectName(directory)), StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new CliException(
                    configFile
                            + " already exists. forge init never overwrites a configuration — edit that"
                            + " file, or move it aside first.");
        } catch (IOException e) {
            throw new CliException("could not write " + configFile + ": " + e.getMessage());
        }

        PrintWriter out = spec.commandLine().getOut();
        out.println("Created " + configFile);
        out.println("Edit the example tasks, then run 'forge plan'.");
        out.flush();
        return ExitCode.SUCCESS;
    }

    private static String projectName(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return "my-project";
        }
        String sanitized = name.toString().toLowerCase().replaceAll("[^a-z0-9-]+", "-");
        return sanitized.isBlank() ? "my-project" : sanitized;
    }

    private static String template(String projectName) {
        return """
                version: 1

                project:
                  name: %s

                # applied to every task that does not set these itself
                defaults:
                  timeout: 10m
                  cacheable: true

                # each task declares the files it reads, the files it writes, and the tasks it needs
                # first. commands are argument lists, never shell strings.
                tasks:
                  example:test:
                    inputs:
                      - "src/**"
                    outputs:
                      - "build/test-results/**"
                    command: ["echo", "replace this with your test command"]

                  example:build:
                    depends_on:
                      - "example:test"
                    inputs:
                      - "src/**"
                    outputs:
                      - "build/dist/**"
                    command: ["echo", "replace this with your build command"]
                """
                .formatted(projectName);
    }
}
