package dev.forgeci.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The producing half of artifact path safety: {@link TaskArchiveTest} proves nothing can be
 * extracted outside the output root, and these prove nothing outside the project directory can get
 * into an archive in the first place — a declared glob only ever selects files under the project,
 * whatever it claims.
 */
class ProjectFilesSecurityTest {

    @Test
    void aParentDirectoryGlobSelectsNothing(@TempDir Path parent) throws IOException {
        Path project = Files.createDirectory(parent.resolve("project"));
        Files.writeString(parent.resolve("outside-secret.txt"), "credentials\n");
        Files.writeString(project.resolve("inside.txt"), "fine\n");

        List<String> matched = ProjectFiles.matching(project, List.of("../**", "../outside-secret.txt", "**/../**"));

        assertEquals(List.of(), matched);
    }

    @Test
    void anAbsolutePathGlobSelectsNothing(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("inside.txt"), "fine\n");

        assertEquals(List.of(), ProjectFiles.matching(project, List.of("/etc/passwd", "/**")));
    }

    @Test
    void aSymlinkPointingOutsideTheProjectIsNotArchived(@TempDir Path parent) throws IOException {
        Path project = Files.createDirectory(parent.resolve("project"));
        Path secret = parent.resolve("outside-secret.txt");
        Files.writeString(secret, "credentials\n");
        Files.createSymbolicLink(project.resolve("link.txt"), secret);
        Files.writeString(project.resolve("real.txt"), "fine\n");

        // the walk only reports regular files, so a symlink is never followed out of the project
        List<String> matched = ProjectFiles.matching(project, List.of("**"));

        assertEquals(List.of("real.txt"), matched);
    }

    @Test
    void gitAndForgeInternalsAreNeverSelectedByAWildcardGlob(@TempDir Path project) throws IOException {
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), "[remote]\n");
        Files.createDirectories(project.resolve(".forge/cache/objects"));
        Files.writeString(project.resolve(".forge/cache/objects/abc"), "cached bytes\n");
        Files.writeString(project.resolve("real.txt"), "fine\n");

        List<String> matched = ProjectFiles.matching(project, List.of("**"));

        assertEquals(List.of("real.txt"), matched);
    }

    @Test
    void anArchiveOnlyEverContainsProjectRelativeEntries(@TempDir Path parent) throws IOException {
        Path project = Files.createDirectory(parent.resolve("project"));
        Files.createDirectories(project.resolve("build/out"));
        Files.writeString(project.resolve("build/out/artifact.txt"), "built\n");
        Files.writeString(parent.resolve("outside-secret.txt"), "credentials\n");

        byte[] archive = TaskArchive.write(project, List.of("build/**", "../**"));
        Path restored = Files.createDirectory(parent.resolve("restored"));
        TaskArchive.extract(archive, restored);

        assertTrue(Files.exists(restored.resolve("build/out/artifact.txt")));
        assertTrue(Files.notExists(restored.resolve("outside-secret.txt")));
        assertTrue(Files.notExists(parent.resolve("restored/../outside-secret-copy.txt")));
    }
}
