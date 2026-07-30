package dev.forgeci.cli;

import dev.forgeci.core.git.GitWorkspace;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Checks the prerequisites local mode needs: a supported Java, a usable Git, a repository, and a
 * valid configuration. Later phases add Docker and control-plane connectivity.
 */
@Command(name = "doctor", description = "Check that this machine and repository can run ForgeCI.")
final class DoctorCommand implements Callable<Integer> {

    private static final int MINIMUM_JAVA_FEATURE = 21;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        Path directory = ProjectWorkspace.currentDirectory();
        List<Check> checks = List.of(javaCheck(), gitCheck(directory), repositoryCheck(directory), configCheck());

        PrintWriter out = spec.commandLine().getOut();
        out.println("ForgeCI doctor");
        out.println();
        for (Check check : checks) {
            out.println(
                    String.format("  %-14s %-5s %s", check.name(), check.ok() ? "OK" : "FAIL", check.detail()));
        }

        List<String> failed = new ArrayList<>();
        for (Check check : checks) {
            if (!check.ok()) {
                failed.add(check.name());
            }
        }
        out.println();
        if (failed.isEmpty()) {
            out.println("All " + checks.size() + " checks passed.");
        } else {
            out.println("Failed checks: " + String.join(", ", failed));
        }
        out.flush();
        return failed.isEmpty() ? ExitCode.SUCCESS : ExitCode.USER_ERROR;
    }

    private static Check javaCheck() {
        Runtime.Version version = Runtime.version();
        if (version.feature() < MINIMUM_JAVA_FEATURE) {
            return Check.fail(
                    "java",
                    "Java "
                            + version
                            + " is too old. ForgeCI needs Java "
                            + MINIMUM_JAVA_FEATURE
                            + " or newer — install it and point JAVA_HOME at it.");
        }
        return Check.ok("java", version.toString());
    }

    private static Check gitCheck(Path directory) {
        try {
            return Check.ok("git", GitWorkspace.gitVersion(directory));
        } catch (RuntimeException e) {
            return Check.fail("git", e.getMessage());
        }
    }

    private static Check repositoryCheck(Path directory) {
        try {
            GitWorkspace git = GitWorkspace.discover(directory);
            int changed = git.changedPaths(GitWorkspace.DEFAULT_BASE_REVISION).size();
            return Check.ok(
                    "repository",
                    git.repositoryRoot()
                            + " ("
                            + git.currentBranch()
                            + ", "
                            + changed
                            + " changed path"
                            + (changed == 1 ? "" : "s")
                            + " here)");
        } catch (RuntimeException e) {
            return Check.fail("repository", e.getMessage());
        }
    }

    private static Check configCheck() {
        try {
            ProjectWorkspace workspace = ProjectWorkspace.load();
            return Check.ok(
                    "configuration", "forgeci.yml: " + workspace.graph().size() + " tasks, no cycles");
        } catch (RuntimeException e) {
            return Check.fail("configuration", e.getMessage());
        }
    }

    private record Check(String name, boolean ok, String detail) {

        static Check ok(String name, String detail) {
            return new Check(name, true, detail);
        }

        static Check fail(String name, String detail) {
            return new Check(name, false, detail);
        }
    }
}
