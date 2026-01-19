package tech.kayys.gamelan.runtime.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Manages plugin configuration settings
 */
@ApplicationScoped
public class PluginConfigurationService {

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "gamelan.plugins.directory", defaultValue = "./plugins")
    String pluginsDirectory;

    private static final String PLUGIN_CONFIG_FILE = "plugin-config.properties";

    /**
     * Gets the configuration file path for a specific plugin
     */
    public Path getPluginConfigPath(String pluginName) {
        return Paths.get(pluginsDirectory, pluginName, PLUGIN_CONFIG_FILE);
    }

    /**
     * Loads configuration for a specific plugin
     */
    public Properties loadPluginConfig(String pluginName) {
        Properties props = new Properties();
        Path configPath = getPluginConfigPath(pluginName);

        if (Files.exists(configPath)) {
            try (InputStream input = Files.newInputStream(configPath)) {
                props.load(input);
                Log.infof("Loaded configuration for plugin: %s", pluginName);
            } catch (IOException e) {
                Log.errorf("Failed to load configuration for plugin %s: %s", pluginName, e.getMessage());
            }
        }

        return props;
    }

    /**
     * Saves configuration for a specific plugin
     */
    public boolean savePluginConfig(String pluginName, Properties config) {
        Path configPath = getPluginConfigPath(pluginName);
        Path pluginDir = configPath.getParent();

        try {
            // Create plugin directory if it doesn't exist
            if (pluginDir != null && !Files.exists(pluginDir)) {
                Files.createDirectories(pluginDir);
            }

            try (OutputStream output = Files.newOutputStream(configPath)) {
                config.store(output, "Plugin configuration for " + pluginName);
                Log.infof("Saved configuration for plugin: %s", pluginName);
                return true;
            }
        } catch (IOException e) {
            Log.errorf("Failed to save configuration for plugin %s: %s", pluginName, e.getMessage());
            return false;
        }
    }

    /**
     * Updates a specific configuration property for a plugin
     */
    public boolean updatePluginConfigProperty(String pluginName, String key, String value) {
        Properties config = loadPluginConfig(pluginName);
        config.setProperty(key, value);
        return savePluginConfig(pluginName, config);
    }

    /**
     * Gets a specific configuration property for a plugin
     */
    public String getPluginConfigProperty(String pluginName, String key, String defaultValue) {
        Properties config = loadPluginConfig(pluginName);
        return config.getProperty(key, defaultValue);
    }

    /**
     * Removes a configuration property for a plugin
     */
    public boolean removePluginConfigProperty(String pluginName, String key) {
        Properties config = loadPluginConfig(pluginName);
        if (config.containsKey(key)) {
            config.remove(key);
            return savePluginConfig(pluginName, config);
        }
        return true; // Property didn't exist anyway
    }

    /**
     * Creates a default configuration template for a plugin
     */
    public boolean createDefaultConfigTemplate(String pluginName) {
        Properties defaultProps = new Properties();
        defaultProps.setProperty("enabled", "true");
        defaultProps.setProperty("auto-start", "true");
        defaultProps.setProperty("thread-pool-size", "5");
        defaultProps.setProperty("timeout-seconds", "30");

        return savePluginConfig(pluginName, defaultProps);
    }
}