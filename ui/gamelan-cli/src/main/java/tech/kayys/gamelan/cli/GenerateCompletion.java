package tech.kayys.gamelan.cli;

import picocli.AutoComplete;

/**
 * Generates shell completion script for the Gamelan CLI
 */
public class GenerateCompletion {
    public static void main(String[] args) {
        String[] cmdArgs = new String[]{
            "gamelan",
            "--completion-script-bash",
            "target/gamelan_completion.sh"
        };
        AutoComplete.main(cmdArgs);
    }
}