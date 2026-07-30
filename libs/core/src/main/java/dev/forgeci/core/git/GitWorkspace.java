package dev.forgeci.core.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the changed-path set for a project directory from local Git metadata. Git reports paths
 * relative to the repository root; a project may sit below that root (the bundled demo repo does),
 * so paths are re-based onto the project directory and anything outside it is dropped — a task's
 * declared inputs are always project-relative.
 */
public final class GitWorkspace {

    public static final String DEFAULT_BASE_REVISION = "HEAD";

    private final Path projectDirectory;
    private final Path repositoryRoot;
    private final String pathPrefix;

    private GitWorkspace(Path projectDirectory, Path repositoryRoot) {
        this.projectDirectory = projectDirectory;
        this.repositoryRoot = repositoryRoot;
        String prefix = repositoryRoot.relativize(projectDirectory).toString().replace(File.separatorChar, '/');
        this.pathPrefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** The local Git version string, for environment checks. Throws if Git is not usable. */
    public static String gitVersion(Path directory) {
        return GitCommand.run(directory, "--version").trim();
    }

    public static GitWorkspace discover(Path projectDirectory) {
        Path directory = realPath(projectDirectory);
        if (findGitDirectory(directory) == null) {
            throw new GitException(
                    directory
                            + " is not inside a Git repository. ForgeCI decides what to rebuild from"
                            + " Git history — run 'git init' here, or run forge from a checkout.");
        }
        Path root = realPath(Path.of(GitCommand.run(directory, "rev-parse", "--show-toplevel").trim()));
        return new GitWorkspace(directory, root);
    }

    /**
     * Git reports the repository root as a resolved path, so the project directory has to be resolved
     * the same way — otherwise a symlinked prefix (macOS {@code /var} → {@code /private/var}) makes
     * the two look unrelated and every changed path is discarded as outside the project.
     */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new GitException("cannot resolve " + path + ": " + e.getMessage());
        }
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    /** True once the repository has at least one commit, i.e. {@code HEAD} resolves. */
    public boolean hasCommits() {
        return GitCommand.succeeds(projectDirectory, "rev-parse", "--verify", "--quiet", "HEAD");
    }

    /** The current branch name, or the short commit id when the head is detached. */
    public String currentBranch() {
        if (!hasCommits()) {
            return "no commits yet";
        }
        String branch = GitCommand.run(projectDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim();
        return branch.equals("HEAD")
                ? "detached at " + GitCommand.run(projectDirectory, "rev-parse", "--short", "HEAD").trim()
                : branch;
    }

    /**
     * Paths under the project directory that differ from {@code baseRevision}, including staged,
     * unstaged, and untracked files. Renames contribute both their old and new path, because a task
     * consuming either one may need to rerun. Returned paths are project-relative and sorted.
     */
    public Set<String> changedPaths(String baseRevision) {
        boolean hasCommits = hasCommits();
        Set<String> repositoryPaths = new TreeSet<>();
        if (hasCommits) {
            collectDiff(baseRevision, repositoryPaths);
        }
        collectUntracked(repositoryPaths, !hasCommits);

        Set<String> projectPaths = new TreeSet<>();
        for (String path : repositoryPaths) {
            if (path.startsWith(pathPrefix)) {
                projectPaths.add(path.substring(pathPrefix.length()));
            }
        }
        return projectPaths;
    }

    /**
     * Reads {@code diff --name-status -z}, whose records are NUL-separated status/path fields:
     * {@code M<NUL>path} for edits, {@code R100<NUL>old<NUL>new} for renames and copies.
     */
    private void collectDiff(String baseRevision, Set<String> into) {
        String output =
                GitCommand.run(
                        projectDirectory,
                        "-c",
                        "diff.relative=false",
                        "diff",
                        "--name-status",
                        "-z",
                        "-M",
                        baseRevision);
        String[] fields = output.split("\0", -1);
        int i = 0;
        while (i < fields.length) {
            String status = fields[i];
            if (status.isEmpty()) {
                break;
            }
            int pathCount = status.startsWith("R") || status.startsWith("C") ? 2 : 1;
            for (int p = 1; p <= pathCount && i + p < fields.length; p++) {
                into.add(fields[i + p]);
            }
            i += pathCount + 1;
        }
    }

    /**
     * Reads {@code status --porcelain=v1 -z}, taking only untracked ({@code ??}) entries — tracked
     * changes already came from the diff, and in a repository with no commits every tracked file is
     * reported as added, which is exactly what we want there.
     */
    private void collectUntracked(Set<String> into, boolean everythingIsNew) {
        String output =
                GitCommand.run(
                        projectDirectory,
                        "-c",
                        "status.relativePaths=false",
                        "status",
                        "--porcelain=v1",
                        "-z",
                        "--untracked-files=all");
        for (String record : output.split("\0", -1)) {
            if (record.length() < 4) {
                continue;
            }
            String status = record.substring(0, 2);
            if (status.equals("??") || everythingIsNew) {
                into.add(record.substring(3));
            }
        }
    }

    private static Path findGitDirectory(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return null;
    }
}
