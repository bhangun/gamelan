package tech.kayys.gamelan.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "gamelan",
    description = "Gamelan Workflow Engine CLI",
    footer = {
        "",
        "Error codes:",
        "  gamelan error-codes",
        "  gamelan error-codes --format json"
    },
    subcommands = {
        WorkflowDefinitionCommands.class,
        WorkflowRunCommands.class,
        ExecutorCommands.class,
        ConfigCommands.class,
        ErrorCodeCommands.class
    },
    mixinStandardHelpOptions = true,
    version = "1.0.0"
)
public class GamelanCli implements Runnable {

    @Option(
        names = {"-s", "--server"},
        description = "gRPC server address (host:port)",
        defaultValue = "localhost:9090"
    )
    private String serverAddress;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GamelanCli()).execute(args);
        System.exit(exitCode);
    }
}
