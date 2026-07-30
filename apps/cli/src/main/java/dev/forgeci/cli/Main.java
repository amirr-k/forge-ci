package dev.forgeci.cli;

import picocli.CommandLine;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ForgeCli()).execute(args);
        System.exit(exitCode);
    }
}
