package dev.forgeci.cli;

import picocli.CommandLine.Command;

@Command(
        name = "forge",
        mixinStandardHelpOptions = true,
        version = "forge 0.1.0-SNAPSHOT",
        subcommands = {PlanCommand.class})
public final class ForgeCli implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }
}
