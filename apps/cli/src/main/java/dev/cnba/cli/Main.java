package dev.cnba.cli;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(CNBACli.commandLine().execute(args));
    }
}
