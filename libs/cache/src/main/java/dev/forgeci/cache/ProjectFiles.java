package dev.forgeci.cache;

import dev.forgeci.core.glob.GlobMatcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Resolves {@code forgeci.yml} glob patterns against the files actually present in a project
 * directory. Never walks {@code .git} or {@code .forge} — Git metadata and ForgeCI's own cache are
 * never legitimate task inputs or outputs.
 */
final class ProjectFiles {

    private ProjectFiles() {}

    /** Project-relative paths (forward-slash separated, sorted) matching any of {@code globs}. */
    static List<String> matching(Path projectDirectory, List<String> globs) {
        if (globs.isEmpty()) {
            return List.of();
        }
        TreeSet<String> matches = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(projectDirectory)) {
            walk.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                String relative =
                                        projectDirectory
                                                .relativize(file)
                                                .toString()
                                                .replace(File.separatorChar, '/');
                                if (isExcluded(relative)) {
                                    return;
                                }
                                for (String glob : globs) {
                                    if (GlobMatcher.matches(glob, relative)) {
                                        matches.add(relative);
                                        return;
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ArrayList<>(matches);
    }

    private static boolean isExcluded(String relativePath) {
        return relativePath.startsWith(".git/")
                || relativePath.equals(".git")
                || relativePath.startsWith(".forge/")
                || relativePath.equals(".forge");
    }
}
