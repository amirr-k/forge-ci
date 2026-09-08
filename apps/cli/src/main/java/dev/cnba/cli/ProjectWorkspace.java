package dev.cnba.cli;

import dev.cnba.config.CNBAConfigParser;
import dev.cnba.core.graph.TaskGraph;
import dev.cnba.core.graph.TopologicalSorter;
import dev.cnba.core.model.CNBAConfig;
import dev.cnba.core.plan.PlanBuilder;
import java.nio.file.Files;
import java.nio.file.Path;

/** The project a command operates on: its directory, its validated configuration, and its graph. */
final class ProjectWorkspace {

    private final Path directory;
    private final CNBAConfig config;
    private final TaskGraph graph;

    private ProjectWorkspace(Path directory, CNBAConfig config, TaskGraph graph) {
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
                            + ". Run 'cnba init' to create one, or run cnba from the directory that"
                            + " holds it.");
        }
        CNBAConfig config = CNBAConfigParser.parse(configFile);
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

    CNBAConfig config() {
        return config;
    }

    TaskGraph graph() {
        return graph;
    }
}
