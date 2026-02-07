package tech.kayys.gamelan.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "error-codes", description = "Show workflow error codes")
public class ErrorCodeCommands implements Callable<Integer> {

    private static final Path DOC_PATH = Path.of("docs", "error-codes.md");

    @Option(names = { "--format" }, description = "Output format: text|json", defaultValue = "text")
    String format;

    @Override
    public Integer call() throws Exception {
        if (Files.exists(DOC_PATH)) {
            String content = Files.readString(DOC_PATH);
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(toJson(content));
            } else {
                System.out.println(content);
            }
            return 0;
        }

        System.out.println("Error codes docs not found at " + DOC_PATH + ".");
        System.out.println("If you're in the repo root, run:");
        System.out.println("  ./scripts/generate-error-codes.sh");
        return 0;
    }

    private String toJson(String markdown) {
        String escaped = markdown
                .replace("\\\\", "\\\\\\\\")
                .replace("\"", "\\\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
        return "{\"format\":\"markdown\",\"content\":\"" + escaped + "\"}";
    }
}
