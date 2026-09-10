package tech.kayys.gamelan.core.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.core.config.PluginConfig;
import tech.kayys.gamelan.engine.plugin.PluginClassLoader;
import tech.kayys.gamelan.engine.plugin.PluginLoader;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginRegistry.LoadedPlugin;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin loader that scans a directory for plugin JARs
 */
public class FilePluginLoader implements PluginLoader {

    private static final Logger LOG = LoggerFactory.getLogger(FilePluginLoader.class);

    private final List<String> pluginDirectories;
    private final List<String> disabledPlugins;
    private final boolean failOnError;

    public FilePluginLoader(PluginConfig config) {
        this.pluginDirectories = config.directories()
                .orElse(List.of("plugins"));
        this.disabledPlugins = config.disabled()
                .orElse(List.of());
        this.failOnError = config.failOnError()
                .orElse(false);
    }

    // Backward compatibility constructor
    public FilePluginLoader(String pluginDirectory) {
        this.pluginDirectories = List.of(pluginDirectory);
        this.disabledPlugins = List.of();
        this.failOnError = false;
    }

    @Override
    public List<LoadedPlugin> loadPlugins() {
        List<LoadedPlugin> plugins = new ArrayList<>();

        for (String directory : pluginDirectories) {
            plugins.addAll(loadPluginsFromDirectory(directory));
        }

        return plugins;
    }

    private List<LoadedPlugin> loadPluginsFromDirectory(String directoryPath) {
        List<LoadedPlugin> plugins = new ArrayList<>();
        File pluginDir = new File(directoryPath);

        if (!pluginDir.exists() || !pluginDir.isDirectory()) {
            LOG.warn("Plugin directory does not exist: {}", directoryPath);
            return plugins;
        }

        File[] jarFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            LOG.info("No plugin JARs found in {}", directoryPath);
            return plugins;
        }

        for (File jarFile : jarFiles) {
            try {
                LOG.info("Loading plugin from: {}", jarFile.getAbsolutePath());

                PluginClassLoader classLoader = new PluginClassLoader(jarFile.toPath(), getClass().getClassLoader());

                ServiceLoader<GamelanPlugin> serviceLoader = ServiceLoader.load(GamelanPlugin.class, classLoader);

                for (GamelanPlugin plugin : serviceLoader) {
                    PluginMetadata metadata = plugin.getMetadata();

                    if (metadata == null) {
                        LOG.warn("Plugin has null metadata, skipping: {}", plugin.getClass().getName());
                        continue;
                    }

                    // Check if plugin is disabled
                    if (disabledPlugins.contains(metadata.id())) {
                        LOG.info("Plugin {} is disabled, skipping", metadata.id());
                        continue;
                    }

                    LOG.info("Loaded plugin: {} v{}", metadata.name(), metadata.version());
                    plugins.add(new LoadedPlugin(plugin, metadata, classLoader));
                }
            } catch (Exception e) {
                String message = "Failed to load plugin from " + jarFile.getName();
                if (failOnError) {
                    throw new RuntimeException(message, e);
                } else {
                    LOG.error(message, e);
                }
            }
        }

        return plugins;
    }
}
