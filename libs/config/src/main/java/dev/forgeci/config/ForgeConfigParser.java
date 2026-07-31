package dev.forgeci.config;

import dev.forgeci.core.exec.Durations;
import dev.forgeci.core.model.Defaults;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.model.ProjectInfo;
import dev.forgeci.core.model.TaskDefinition;
import dev.forgeci.core.validation.ConfigValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Parses and strictly validates a {@code forgeci.yml} document into a {@link ForgeConfig}. Rejects
 * unknown fields, wrong types, and {@code depends_on} references to undefined tasks with an
 * actionable message naming the source file and the offending field path.
 */
public final class ForgeConfigParser {

    private static final Set<String> ROOT_FIELDS =
            Set.of("version", "project", "defaults", "tasks");
    private static final Set<String> PROJECT_FIELDS = Set.of("name");
    private static final Set<String> DEFAULTS_FIELDS = Set.of("timeout", "cacheable");
    private static final Set<String> TASK_FIELDS =
            Set.of(
                    "depends_on",
                    "inputs",
                    "outputs",
                    "command",
                    "environment",
                    "timeout",
                    "cacheable");

    private ForgeConfigParser() {}

    public static ForgeConfig parse(Path file) {
        if (!Files.exists(file)) {
            throw new ConfigValidationException(
                    file + " not found. Run 'forge init' to create a starting forgeci.yml.");
        }
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new ConfigValidationException(
                    "cannot read "
                            + file
                            + ": "
                            + e
                            + ". Check that it is a readable UTF-8 text file.");
        }
        return parse(content, file.toString());
    }

    public static ForgeConfig parse(String yaml, String sourceName) {
        Yaml snakeYaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root;
        try {
            root = snakeYaml.load(yaml);
        } catch (YAMLException e) {
            throw fail(sourceName, "", "invalid YAML syntax: " + e.getMessage());
        }

        Map<String, Object> rootMap = asMap(root, sourceName, "");
        rejectUnknownKeys(rootMap, ROOT_FIELDS, sourceName, "");

        int version = requireInt(rootMap, "version", sourceName, "");
        if (version != 1) {
            throw fail(
                    sourceName,
                    "version",
                    "unsupported schema version " + version + " (expected 1)");
        }

        ProjectInfo project = parseProject(rootMap, sourceName);
        Defaults defaults = parseDefaults(rootMap, sourceName);
        Map<String, TaskDefinition> tasks = parseTasks(rootMap, defaults, sourceName);
        validateTaskReferences(tasks, sourceName);

        return new ForgeConfig(version, project, defaults, tasks);
    }

    private static ProjectInfo parseProject(Map<String, Object> rootMap, String sourceName) {
        Object raw = rootMap.get("project");
        if (raw == null) {
            throw fail(sourceName, "", "missing required field 'project'");
        }
        Map<String, Object> projectMap = asMap(raw, sourceName, "project");
        rejectUnknownKeys(projectMap, PROJECT_FIELDS, sourceName, "project");
        String name = requireNonBlankString(projectMap, "name", sourceName, "project");
        return new ProjectInfo(name);
    }

    private static Defaults parseDefaults(Map<String, Object> rootMap, String sourceName) {
        Object raw = rootMap.get("defaults");
        if (raw == null) {
            return new Defaults(null, true);
        }
        Map<String, Object> defaultsMap = asMap(raw, sourceName, "defaults");
        rejectUnknownKeys(defaultsMap, DEFAULTS_FIELDS, sourceName, "defaults");

        String timeout = optionalString(defaultsMap, "timeout", sourceName, "defaults");
        if (timeout != null) {
            requireValidTimeout(timeout, sourceName, "defaults.timeout");
        }
        boolean cacheable = optionalBoolean(defaultsMap, "cacheable", sourceName, "defaults", true);
        return new Defaults(timeout, cacheable);
    }

    private static Map<String, TaskDefinition> parseTasks(
            Map<String, Object> rootMap, Defaults defaults, String sourceName) {
        Object raw = rootMap.get("tasks");
        if (raw == null) {
            throw fail(sourceName, "", "missing required field 'tasks'");
        }
        Map<String, Object> tasksMap = asMap(raw, sourceName, "tasks");
        if (tasksMap.isEmpty()) {
            throw fail(sourceName, "tasks", "must declare at least one task");
        }

        Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : tasksMap.entrySet()) {
            String name = entry.getKey();
            String context = "tasks." + name;
            Map<String, Object> taskMap = asMap(entry.getValue(), sourceName, context);
            rejectUnknownKeys(taskMap, TASK_FIELDS, sourceName, context);

            List<String> dependsOn = optionalStringList(taskMap, "depends_on", sourceName, context);
            List<String> inputs = optionalStringList(taskMap, "inputs", sourceName, context);
            List<String> outputs = optionalStringList(taskMap, "outputs", sourceName, context);
            List<String> command = requireCommand(taskMap, sourceName, context);
            List<String> environment =
                    optionalStringList(taskMap, "environment", sourceName, context);

            String timeout = optionalString(taskMap, "timeout", sourceName, context);
            if (timeout != null) {
                requireValidTimeout(timeout, sourceName, context + ".timeout");
            } else {
                timeout = defaults.timeout();
            }
            boolean cacheable =
                    optionalBoolean(
                            taskMap, "cacheable", sourceName, context, defaults.cacheable());

            tasks.put(
                    name,
                    new TaskDefinition(
                            name,
                            dependsOn,
                            inputs,
                            outputs,
                            command,
                            environment,
                            timeout,
                            cacheable));
        }
        return tasks;
    }

    private static void validateTaskReferences(
            Map<String, TaskDefinition> tasks, String sourceName) {
        for (TaskDefinition task : tasks.values()) {
            for (String dependency : task.dependsOn()) {
                if (!tasks.containsKey(dependency)) {
                    throw fail(
                            sourceName,
                            "tasks." + task.name() + ".depends_on",
                            "references undefined task '" + dependency + "'");
                }
            }
        }
    }

    private static List<String> requireCommand(
            Map<String, Object> taskMap, String sourceName, String context) {
        Object raw = taskMap.get("command");
        if (raw == null) {
            throw fail(sourceName, context, "missing required field 'command'");
        }
        if (raw instanceof String) {
            throw fail(
                    sourceName,
                    context + ".command",
                    "must be a list of arguments, not a shell string (got: \"" + raw + "\")");
        }
        List<String> command = asStringList(raw, sourceName, context + ".command");
        if (command.isEmpty()) {
            throw fail(sourceName, context + ".command", "must declare at least one argument");
        }
        return command;
    }

    /**
     * Validated here, not at execution time, so a bad duration is reported with its file location.
     */
    private static void requireValidTimeout(String timeout, String sourceName, String context) {
        try {
            Durations.parse(timeout);
        } catch (IllegalArgumentException e) {
            throw fail(sourceName, context, e.getMessage());
        }
    }

    private static void rejectUnknownKeys(
            Map<String, Object> map, Set<String> allowed, String sourceName, String context) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw fail(
                        sourceName,
                        context,
                        "unknown field '"
                                + key
                                + "' (allowed: "
                                + String.join(", ", allowed)
                                + ")");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String sourceName, String context) {
        if (!(value instanceof Map)) {
            throw fail(sourceName, context, "must be a mapping (got: " + typeName(value) + ")");
        }
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (!(key instanceof String)) {
                throw fail(
                        sourceName, context, "keys must be strings (got: " + typeName(key) + ")");
            }
        }
        return (Map<String, Object>) value;
    }

    private static List<String> asStringList(Object value, String sourceName, String context) {
        if (!(value instanceof List<?> list)) {
            throw fail(
                    sourceName,
                    context,
                    "must be a list of strings (got: " + typeName(value) + ")");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String)) {
                throw fail(
                        sourceName,
                        context,
                        "must be a list of strings (found " + typeName(element) + ")");
            }
            result.add((String) element);
        }
        return result;
    }

    private static List<String> optionalStringList(
            Map<String, Object> map, String field, String sourceName, String context) {
        Object raw = map.get(field);
        if (raw == null) {
            return List.of();
        }
        return asStringList(raw, sourceName, context + "." + field);
    }

    private static String optionalString(
            Map<String, Object> map, String field, String sourceName, String context) {
        Object raw = map.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String)) {
            throw fail(
                    sourceName,
                    context + "." + field,
                    "must be a string (got: " + typeName(raw) + ")");
        }
        return (String) raw;
    }

    private static String requireNonBlankString(
            Map<String, Object> map, String field, String sourceName, String context) {
        String value = optionalString(map, field, sourceName, context);
        if (value == null) {
            throw fail(sourceName, context, "missing required field '" + field + "'");
        }
        if (value.isBlank()) {
            throw fail(sourceName, context + "." + field, "must not be blank");
        }
        return value;
    }

    private static boolean optionalBoolean(
            Map<String, Object> map,
            String field,
            String sourceName,
            String context,
            boolean fallback) {
        Object raw = map.get(field);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean)) {
            throw fail(
                    sourceName,
                    context + "." + field,
                    "must be true or false (got: " + typeName(raw) + ")");
        }
        return (Boolean) raw;
    }

    private static int requireInt(
            Map<String, Object> map, String field, String sourceName, String context) {
        Object raw = map.get(field);
        if (raw == null) {
            throw fail(sourceName, context, "missing required field '" + field + "'");
        }
        if (!(raw instanceof Integer)) {
            throw fail(
                    sourceName,
                    context + "." + field,
                    "must be an integer (got: " + typeName(raw) + ")");
        }
        return (Integer) raw;
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Map) {
            return "mapping";
        }
        if (value instanceof List) {
            return "list";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }

    private static ConfigValidationException fail(
            String sourceName, String context, String detail) {
        String location = context.isEmpty() ? sourceName : sourceName + ": " + context;
        return new ConfigValidationException(location + ": " + detail);
    }
}
