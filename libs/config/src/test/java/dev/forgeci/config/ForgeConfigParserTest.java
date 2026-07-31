package dev.forgeci.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.graph.CycleDetectedException;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.graph.TopologicalSorter;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.model.TaskDefinition;
import dev.forgeci.core.validation.ConfigValidationException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ForgeConfigParserTest {

    @Test
    void parsesTheDemoFixtureIntoSixTasks() {
        ForgeConfig config = ForgeConfigParser.parse(fixture("demo-project/forgeci.yml"));

        assertEquals(1, config.version());
        assertEquals("forge-ci-demo", config.project().name());
        assertEquals(6, config.tasks().size());

        TaskDefinition pricingBuild = config.tasks().get("pricing:build");
        assertEquals(java.util.List.of("pricing:test"), pricingBuild.dependsOn());
        assertEquals(java.util.List.of("echo", "build pricing"), pricingBuild.command());
        assertEquals("10m", pricingBuild.timeout());
        assertTrue(pricingBuild.cacheable());
    }

    @Test
    void rejectsUnsupportedVersion() {
        String yaml =
                """
                version: 2
                project:
                  name: x
                tasks:
                  a:
                    command: ["echo", "a"]
                """;

        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(yaml, "forgeci.yml"));
        assertTrue(exception.getMessage().contains("unsupported schema version 2"));
    }

    @Test
    void rejectsUnknownTopLevelField() {
        String yaml =
                """
                version: 1
                project:
                  name: x
                tasks:
                  a:
                    command: ["echo", "a"]
                extra: true
                """;

        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(yaml, "forgeci.yml"));
        assertTrue(exception.getMessage().contains("unknown field 'extra'"));
    }

    @Test
    void rejectsUnknownTaskField() {
        String yaml =
                """
                version: 1
                project:
                  name: x
                tasks:
                  a:
                    command: ["echo", "a"]
                    bogus: 1
                """;

        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(yaml, "forgeci.yml"));
        assertTrue(exception.getMessage().contains("tasks.a"));
        assertTrue(exception.getMessage().contains("unknown field 'bogus'"));
    }

    @Test
    void rejectsCommandAsShellString() {
        String yaml =
                """
                version: 1
                project:
                  name: x
                tasks:
                  a:
                    command: "go test ./..."
                """;

        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(yaml, "forgeci.yml"));
        assertTrue(exception.getMessage().contains("not a shell string"));
    }

    @Test
    void rejectsMissingProjectName() {
        String yaml =
                """
                version: 1
                project:
                  name: ""
                tasks:
                  a:
                    command: ["echo", "a"]
                """;

        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(yaml, "forgeci.yml"));
        assertTrue(exception.getMessage().contains("project.name"));
    }

    @Test
    void rejectsInvalidTimeoutFormat() {
        String yaml =
                """
                version: 1
                project:
                  name: x
                tasks:
                  a:
                    command: ["echo", "a"]
                    timeout: "soon"
                """;

        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(yaml, "forgeci.yml"));
        assertTrue(exception.getMessage().contains("invalid duration"));
    }

    @Test
    void missingTaskFixtureIsRejectedWithActionableError() {
        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> ForgeConfigParser.parse(fixture("missing-task.yml")));

        assertTrue(
                exception.getMessage().contains("forgeci.yml")
                        || exception.getMessage().contains("missing-task.yml"));
        assertTrue(exception.getMessage().contains("checkout:integration.depends_on"));
        assertTrue(exception.getMessage().contains("undefined task 'pricing:build'"));
    }

    @Test
    void cyclicFixtureIsRejectedWithExactCyclePathFormatDuringGraphConstruction() {
        ForgeConfig config = ForgeConfigParser.parse(fixture("cyclic.yml"));
        TaskGraph graph = TaskGraph.build(config);

        CycleDetectedException exception =
                assertThrows(CycleDetectedException.class, () -> TopologicalSorter.sort(graph));

        assertEquals(
                "Cycle detected:\nfrontend:build -> api:generate -> frontend:build",
                exception.getMessage());
    }

    private static Path fixture(String relativePath) {
        try {
            return Paths.get(
                    ForgeConfigParserTest.class.getResource("/fixtures/" + relativePath).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
