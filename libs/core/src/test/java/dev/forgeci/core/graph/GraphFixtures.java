package dev.forgeci.core.graph;

import dev.forgeci.core.model.Defaults;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.model.ProjectInfo;
import dev.forgeci.core.model.TaskDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hand-built {@link ForgeConfig} fixtures shared by core's graph tests. */
final class GraphFixtures {

    private GraphFixtures() {}

    static TaskDefinition task(String name, List<String> dependsOn, List<String> inputs) {
        return new TaskDefinition(
                name, dependsOn, inputs, List.of(), List.of("echo", name), List.of(), "10m", true);
    }

    /**
     * catalog:build                (no deps, inputs under services/catalog/**)
     * accounts:test                (no deps, inputs under services/accounts/**)
     * pricing:test                 (no deps, inputs under services/pricing/**)
     * pricing:build                depends on pricing:test, inputs under services/pricing/**
     * checkout:integration         depends on pricing:build, inputs under services/checkout/**
     * storefront:build             (no deps, inputs under services/storefront/**)
     */
    static ForgeConfig demoConfig() {
        Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
        tasks.put("catalog:build", task("catalog:build", List.of(), List.of("services/catalog/**")));
        tasks.put("accounts:test", task("accounts:test", List.of(), List.of("services/accounts/**")));
        tasks.put("pricing:test", task("pricing:test", List.of(), List.of("services/pricing/**")));
        tasks.put(
                "pricing:build",
                task("pricing:build", List.of("pricing:test"), List.of("services/pricing/**")));
        tasks.put(
                "checkout:integration",
                task(
                        "checkout:integration",
                        List.of("pricing:build"),
                        List.of("services/checkout/**")));
        tasks.put(
                "storefront:build", task("storefront:build", List.of(), List.of("services/storefront/**")));
        return new ForgeConfig(1, new ProjectInfo("demo"), new Defaults("10m", true), tasks);
    }

    static ForgeConfig cyclicConfig() {
        Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
        tasks.put("frontend:build", task("frontend:build", List.of("api:generate"), List.of()));
        tasks.put("api:generate", task("api:generate", List.of("frontend:build"), List.of()));
        return new ForgeConfig(1, new ProjectInfo("demo"), new Defaults("10m", true), tasks);
    }
}
