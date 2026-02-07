package tech.kayys.gamelan.engine.plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.event.EventBus;

/**
 * Unified plugin service that combines all plugin management functionality
 * This provides a single entry point for plugin operations in the engine
 */
public interface PluginService extends ServiceRegistry, EventBus {

    /**
     * Load a plugin from a JAR file
     */
    Uni<GamelanPlugin> loadPlugin(Path pluginJar);

    /**
     * Register a plugin instance directly (programmatic registration)
     */
    Uni<Void> registerPlugin(GamelanPlugin plugin);

    /**
     * Start a plugin
     */
    Uni<Void> startPlugin(String pluginId);

    /**
     * Stop a plugin
     */
    Uni<Void> stopPlugin(String pluginId);

    /**
     * Unload a plugin
     */
    Uni<Void> unloadPlugin(String pluginId);

    /**
     * Reload a plugin (hot-reload)
     */
    Uni<GamelanPlugin> reloadPlugin(String pluginId, Path pluginJar);

    /**
     * Get a plugin by ID
     */
    Optional<GamelanPlugin> getPlugin(String pluginId);

    /**
     * Get all loaded plugins
     */
    List<GamelanPlugin> getAllPlugins();

    /**
     * Get plugins by type
     */
    <T extends GamelanPlugin> List<T> getPluginsByType(Class<T> pluginType);

    /**
     * Discover and load all plugins from the plugin directory
     */
    Uni<List<GamelanPlugin>> discoverAndLoadPlugins();

    /**
     * Set the plugin directory
     */
    void setPluginDirectory(String pluginDirectory);

    /**
     * Set the data directory
     */
    void setDataDirectory(String dataDirectory);
}