package dev.forgeci.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.testsupport.GitTestRepository;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {

    private static final String BASE = GitWorkspace.DEFAULT_BASE_REVISION;

    @Test
    void reportsNothingForACleanTree(@TempDir Path directory) {
        GitTestRepository.initialize(directory).write("a.txt", "a\n").commitAll("init");

        assertEquals(Set.of(), GitWorkspace.discover(directory).changedPaths(BASE));
    }

    @Test
    void reportsModifiedAddedAndDeletedPaths(@TempDir Path directory) {
        GitTestRepository repository =
                GitTestRepository.initialize(directory)
                        .write("src/keep.txt", "keep\n")
                        .write("src/edit.txt", "before\n")
                        .write("src/remove.txt", "remove\n")
                        .commitAll("init");
        repository
                .write("src/edit.txt", "after\n")
                .delete("src/remove.txt")
                .write("src/new.txt", "new\n");

        assertEquals(
                Set.of("src/edit.txt", "src/new.txt", "src/remove.txt"),
                GitWorkspace.discover(directory).changedPaths(BASE));
    }

    @Test
    void reportsBothSidesOfARename(@TempDir Path directory) {
        GitTestRepository repository =
                GitTestRepository.initialize(directory)
                        .write("src/old.txt", "same content everywhere\n")
                        .commitAll("init");
        repository.move("src/old.txt", "src/new.txt");

        assertEquals(
                Set.of("src/old.txt", "src/new.txt"),
                GitWorkspace.discover(directory).changedPaths(BASE));
    }

    @Test
    void comparesAgainstAnEarlierRevision(@TempDir Path directory) {
        GitTestRepository repository =
                GitTestRepository.initialize(directory)
                        .write("src/a.txt", "one\n")
                        .commitAll("first");
        repository.write("src/a.txt", "two\n").write("src/b.txt", "b\n").commitAll("second");

        GitWorkspace workspace = GitWorkspace.discover(directory);
        assertEquals(Set.of(), workspace.changedPaths("HEAD"));
        assertEquals(Set.of("src/a.txt", "src/b.txt"), workspace.changedPaths("HEAD~1"));
    }

    @Test
    void scopesPathsToTheProjectDirectory(@TempDir Path directory) {
        GitTestRepository repository =
                GitTestRepository.initialize(directory)
                        .write("demo/project/src/inside.txt", "inside\n")
                        .write("other/outside.txt", "outside\n")
                        .commitAll("init");
        repository
                .write("demo/project/src/inside.txt", "changed\n")
                .write("other/outside.txt", "changed\n");

        Path projectDirectory = directory.resolve("demo/project");
        assertEquals(
                Set.of("src/inside.txt"),
                GitWorkspace.discover(projectDirectory).changedPaths(BASE));
    }

    @Test
    void treatsEveryFileAsNewBeforeTheFirstCommit(@TempDir Path directory) {
        GitTestRepository repository =
                GitTestRepository.initialize(directory).write("src/a.txt", "a\n");
        repository.git("add", "src/a.txt");
        repository.write("src/b.txt", "b\n");

        GitWorkspace workspace = GitWorkspace.discover(directory);
        assertEquals(Set.of("src/a.txt", "src/b.txt"), workspace.changedPaths(BASE));
        assertEquals("no commits yet", workspace.currentBranch());
    }

    @Test
    void refusesADirectoryOutsideAnyRepository(@TempDir Path directory) {
        GitException failure =
                assertThrows(GitException.class, () -> GitWorkspace.discover(directory));

        assertTrue(
                failure.getMessage().contains("not inside a Git repository"), failure.getMessage());
        assertTrue(failure.getMessage().contains("git init"), failure.getMessage());
    }

    @Test
    void reportsTheCurrentBranch(@TempDir Path directory) {
        GitTestRepository.initialize(directory).write("a.txt", "a\n").commitAll("init");

        assertEquals("main", GitWorkspace.discover(directory).currentBranch());
    }
}
