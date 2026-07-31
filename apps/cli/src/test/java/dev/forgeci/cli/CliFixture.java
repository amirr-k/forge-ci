package dev.forgeci.cli;

import dev.forgeci.testsupport.GitTestRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import picocli.CommandLine;

/**
 * Runs the CLI against a temporary project. Commands read the working directory from {@code
 * user.dir} rather than the JVM's cached process directory, which is what lets a test point them at
 * a temporary project.
 */
final class CliFixture implements AutoCloseable {

    static final String DEMO_CONFIG =
            """
            version: 1

            project:
              name: cli-fixture

            defaults:
              timeout: 1m

            tasks:
              shared:build:
                inputs:
                  - "services/shared/**"
                command: ["sh", "-c", "echo building shared"]

              accounts:test:
                inputs:
                  - "services/accounts/**"
                command: ["sh", "-c", "echo testing accounts"]

              pricing:test:
                depends_on:
                  - "shared:build"
                inputs:
                  - "services/pricing/**"
                command: ["sh", "-c", "echo testing pricing"]

              pricing:build:
                depends_on:
                  - "pricing:test"
                inputs:
                  - "services/pricing/**"
                command: ["sh", "-c", "echo building pricing"]
            """;

    private final Path directory;
    private final String originalWorkingDirectory;
    private final GitTestRepository repository;

    CliFixture(Path directory) {
        this(directory, null);
    }

    private CliFixture(Path directory, GitTestRepository repository) {
        this.directory = directory;
        this.repository = repository;
        this.originalWorkingDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", directory.toString());
    }

    /** A committed project so only later edits show up as changed paths. */
    static CliFixture withCommittedProject(Path directory) {
        GitTestRepository repository =
                GitTestRepository.initialize(directory)
                        .write("forgeci.yml", DEMO_CONFIG)
                        .write("services/shared/Money.java", "shared\n")
                        .write("services/accounts/AccountService.java", "accounts\n")
                        .write("services/pricing/PriceCalculator.java", "pricing\n")
                        .commitAll("bundle the fixture project");
        return new CliFixture(directory, repository);
    }

    Path directory() {
        return directory;
    }

    GitTestRepository repository() {
        return repository;
    }

    Result run(String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(out, true, StandardCharsets.UTF_8);
        PrintWriter errWriter = new PrintWriter(err, true, StandardCharsets.UTF_8);

        CommandLine cli = ForgeCli.commandLine().setOut(outWriter).setErr(errWriter);
        int exitCode = cli.execute(arguments);

        outWriter.flush();
        errWriter.flush();
        return new Result(
                exitCode,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        System.setProperty("user.dir", originalWorkingDirectory);
    }

    record Result(int exitCode, String out, String err) {}
}
