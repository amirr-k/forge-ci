package dev.forgeci.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PlanCommandTest {

    private static final String DEMO_FORGECI_YML =
            """
            version: 1

            project:
              name: forge-ci-demo

            tasks:
              catalog:build:
                inputs:
                  - "services/catalog/**"
                command: ["echo", "build catalog"]

              pricing:test:
                inputs:
                  - "services/pricing/**"
                command: ["echo", "test pricing"]

              pricing:build:
                depends_on:
                  - "pricing:test"
                inputs:
                  - "services/pricing/**"
                command: ["echo", "build pricing"]

              checkout:integration:
                depends_on:
                  - "pricing:build"
                inputs:
                  - "services/checkout/**"
                command: ["echo", "integration checkout"]
            """;

    private Path originalCwd;

    @BeforeEach
    void rememberCwd() {
        originalCwd = Path.of("").toAbsolutePath();
    }

    @AfterEach
    void restoreCwd() {
        System.setProperty("user.dir", originalCwd.toString());
    }

    @Test
    void printsAffectedClosureForDemoFixture(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("forgeci.yml"), DEMO_FORGECI_YML);
        System.setProperty("user.dir", projectDir.toString());

        String output = run("plan", "--changed", "services/pricing/src/main/java/PriceCalculator.java");

        assertTrue(output.contains("Changed files"));
        assertTrue(output.contains("services/pricing/src/main/java/PriceCalculator.java"));
        assertTrue(output.contains("pricing:test"));
        assertTrue(output.contains("pricing:build"));
        assertTrue(output.contains("checkout:integration"));
        assertTrue(output.contains("Unaffected tasks"));
        assertTrue(output.contains("catalog:build"));
        assertTrue(output.contains("Plan: 3 affected, 1 unaffected"));
    }

    @Test
    void exitsNonZeroForMissingForgeciYml(@TempDir Path projectDir) {
        System.setProperty("user.dir", projectDir.toString());

        CommandLine cli = new CommandLine(new ForgeCli());
        int exitCode = cli.execute("plan", "--changed", "some/file.txt");

        assertEquals(1, exitCode);
    }

    private String run(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            int exitCode = new CommandLine(new ForgeCli()).execute(args);
            assertEquals(0, exitCode);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
