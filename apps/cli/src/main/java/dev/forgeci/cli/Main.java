package dev.forgeci.cli;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(ForgeCli.commandLine().execute(args));
    }
}
