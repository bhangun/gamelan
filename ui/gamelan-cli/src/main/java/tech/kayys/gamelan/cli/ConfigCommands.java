package tech.kayys.gamelan.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "config", description = "Manage CLI configuration")
public class ConfigCommands {

    @ParentCommand
    GamelanCli parent;

    @Command(name = "set", description = "Set a configuration property")
    static class Set implements Callable<Integer> {
        
        @Option(names = {"--property"}, description = "Configuration property name", required = true)
        String property;
        
        @Option(names = {"--value"}, description = "Configuration property value", required = true)
        String value;

        @Override
        public Integer call() throws Exception {
            Properties props = loadOrCreateConfig();
            props.setProperty(property, value);
            saveConfig(props);
            System.out.println("Configuration property '" + property + "' set to '" + value + "'");
            return 0;
        }
    }

    @Command(name = "get", description = "Get a configuration property")
    static class Get implements Callable<Integer> {
        
        @Option(names = {"--property"}, description = "Configuration property name", required = true)
        String property;

        @Override
        public Integer call() throws Exception {
            Properties props = loadOrCreateConfig();
            String value = props.getProperty(property);
            if (value != null) {
                System.out.println(property + "=" + value);
                return 0;
            } else {
                System.out.println("Configuration property '" + property + "' not found");
                return 1;
            }
        }
    }

    @Command(name = "list", description = "List all configuration properties")
    static class List implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            Properties props = loadOrCreateConfig();
            if (props.isEmpty()) {
                System.out.println("No configuration properties set");
                return 0;
            }
            
            for (String key : props.stringPropertyNames()) {
                System.out.println(key + "=" + props.getProperty(key));
            }
            return 0;
        }
    }

    @Command(name = "init", description = "Initialize configuration file with defaults")
    static class Init implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            Properties props = loadOrCreateConfig();
            if (props.isEmpty()) {
                props.setProperty("server.address", "localhost:9090");
                props.setProperty("output.format", "json");
                saveConfig(props);
                System.out.println("Configuration initialized with default values");
            } else {
                System.out.println("Configuration already exists. Use 'config list' to view current settings.");
            }
            return 0;
        }
    }

    private static Properties loadOrCreateConfig() throws IOException {
        Path configPath = getConfigPath();
        Properties props = new Properties();

        if (Files.exists(configPath)) {
            try (var fis = Files.newInputStream(configPath)) {
                props.load(fis);
            }
        }

        return props;
    }

    private static void saveConfig(Properties props) throws IOException {
        Path configPath = getConfigPath();
        Path parentDir = configPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        try (var fos = Files.newOutputStream(configPath)) {
            props.store(fos, "Gamelan CLI Configuration");
        }
    }

    private static Path getConfigPath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".gamelan", "config.properties");
    }
}