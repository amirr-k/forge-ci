package dev.cnba.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cnba.core.graph.CycleDetectedException;
import dev.cnba.core.graph.TaskGraph;
import dev.cnba.core.graph.TopologicalSorter;
import dev.cnba.core.model.CNBAConfig;
import dev.cnba.core.model.TaskDefinition;
import dev.cnba.core.validation.ConfigValidationException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class CNBAConfigParserTest {

    @Test
    void parsesTheDemoFixtureIntoSixTasks() {
        CNBAConfig config = CNBAConfigParser.parse(fixture("demo-project/cnba.yml"));

        assertEquals(1, config.version());
        assertEquals("cnba-ci-demo", config.project().name());
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
                        () -> CNBAConfigParser.parse(yaml, "cnba.yml"));
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
                        () -> CNBAConfigParser.parse(yaml, "cnba.yml"));
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
                        () -> CNBAConfigParser.parse(yaml, "cnba.yml"));
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
                        () -> CNBAConfigParser.parse(yaml, "cnba.yml"));
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
                        () -> CNBAConfigParser.parse(yaml, "cnba.yml"));
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
                        () -> CNBAConfigParser.parse(yaml, "cnba.yml"));
        assertTrue(exception.getMessage().contains("invalid duration"));
    }

    @Test
    void missingTaskFixtureIsRejectedWithActionableError() {
        ConfigValidationException exception =
                assertThrows(
                        ConfigValidationException.class,
                        () -> CNBAConfigParser.parse(fixture("missing-task.yml")));

        assertTrue(
                exception.getMessage().contains("cnba.yml")
                        || exception.getMessage().contains("missing-task.yml"));
        assertTrue(exception.getMessage().contains("checkout:integration.depends_on"));
        assertTrue(exception.getMessage().contains("undefined task 'pricing:build'"));
    }

    @Test
    void cyclicFixtureIsRejectedWithExactCyclePathFormatDuringGraphConstruction() {
        CNBAConfig config = CNBAConfigParser.parse(fixture("cyclic.yml"));
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
                    CNBAConfigParserTest.class.getResource("/fixtures/" + relativePath).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
