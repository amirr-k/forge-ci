package dev.forgeci.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Remembers the last {@link CacheKey} computed for each task, independent of whether it was a hit —
 * this is what lets {@code forge explain} say which specific contributor changed on a miss, rather
 * than just that one did.
 */
final class CacheKeyRecordStore {

    private static final String INPUT_PREFIX = "input.";
    private static final String DEPENDENCY_PREFIX = "dependency.";

    private final Path directory;

    CacheKeyRecordStore(Path directory) {
        this.directory = directory;
    }

    void save(String taskName, CacheKey key) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(key.schemaVersion()));
        properties.setProperty("value", key.value());
        properties.setProperty("taskDefinitionDigest", key.taskDefinitionDigest());
        properties.setProperty("sourceInputsDigest", key.sourceInputsDigest());
        properties.setProperty("dependencyArtifactsDigest", key.dependencyArtifactsDigest());
        properties.setProperty("toolchain", key.toolchain());
        key.sourceInputDigests()
                .forEach((path, digest) -> properties.setProperty(INPUT_PREFIX + path, digest));
        key.dependencyDigests()
                .forEach(
                        (name, digest) -> properties.setProperty(DEPENDENCY_PREFIX + name, digest));

        Path file = pathFor(taskName);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<CacheKey> load(String taskName) {
        Path file = pathFor(taskName);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }

        String schemaVersion = properties.getProperty("schemaVersion");
        String value = properties.getProperty("value");
        String taskDefinitionDigest = properties.getProperty("taskDefinitionDigest");
        String sourceInputsDigest = properties.getProperty("sourceInputsDigest");
        String dependencyArtifactsDigest = properties.getProperty("dependencyArtifactsDigest");
        String toolchain = properties.getProperty("toolchain");
        if (schemaVersion == null
                || value == null
                || taskDefinitionDigest == null
                || sourceInputsDigest == null
                || dependencyArtifactsDigest == null
                || toolchain == null) {
            return Optional.empty();
        }

        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> dependencies = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(INPUT_PREFIX)) {
                inputs.put(name.substring(INPUT_PREFIX.length()), properties.getProperty(name));
            } else if (name.startsWith(DEPENDENCY_PREFIX)) {
                dependencies.put(
                        name.substring(DEPENDENCY_PREFIX.length()), properties.getProperty(name));
            }
        }

        try {
            return Optional.of(
                    new CacheKey(
                            Integer.parseInt(schemaVersion),
                            value,
                            taskDefinitionDigest,
                            sourceInputsDigest,
                            inputs,
                            dependencyArtifactsDigest,
                            dependencies,
                            toolchain));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Path pathFor(String taskName) {
        return directory.resolve(sanitize(taskName) + ".record");
    }

    private static String sanitize(String taskName) {
        return taskName.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
