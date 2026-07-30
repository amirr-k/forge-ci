package dev.forgeci.cli;

import dev.forgeci.config.ForgeConfigParser;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.graph.TopologicalSorter;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.plan.PlanBuilder;
import java.nio.file.Files;
import java.nio.file.Path;

/** The project a command operates on: its directory, its validated configuration, and its graph. */
final class ProjectWorkspace {

    private final Path directory;
    private final ForgeConfig config;
    private final TaskGraph graph;

    private ProjectWorkspace(Path directory, ForgeConfig config, TaskGraph graph) {
        this.directory = directory;
        this.config = config;
        this.graph = graph;
    }

    /**
     * Loads the project rooted at the current working directory, validating the configuration and
     * rejecting a cyclic graph before any command can act on it.
     */
    static ProjectWorkspace load() {
        Path directory = currentDirectory();
        Path configFile = directory.resolve(PlanBuilder.CONFIG_FILE);
        if (!Files.exists(configFile)) {
            throw new CliException(
                    "no "
                            + PlanBuilder.CONFIG_FILE
                            + " in "
                            + directory
                            + ". Run 'forge init' to create one, or run forge from the directory that"
                            + " holds it.");
        }
        ForgeConfig config = ForgeConfigParser.parse(configFile);
        TaskGraph graph = TaskGraph.build(config);
        TopologicalSorter.sort(graph);
        return new ProjectWorkspace(directory, config, graph);
    }

    /** Read live rather than cached, so tests can point commands at a temporary project. */
    static Path currentDirectory() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    Path directory() {
        return directory;
    }

    ForgeConfig config() {
        return config;
    }

    TaskGraph graph() {
        return graph;
    }
}
