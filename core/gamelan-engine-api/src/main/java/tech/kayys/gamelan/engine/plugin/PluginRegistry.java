package tech.kayys.gamelan.engine.plugin;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registry for managing loaded plugins
 */
@ApplicationScoped
public class PluginRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(PluginRegistry.class);

    private final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();

    /**
     * Register a loaded plugin
     */
    public void register(LoadedPlugin plugin) {
        String pluginId = plugin.getMetadata().id();
        if (plugins.containsKey(pluginId)) {
            throw new IllegalStateException("Plugin already registered: " + pluginId);
        }
        plugins.put(pluginId, plugin);
        LOG.info("Registered plugin: {} v{}", plugin.getMetadata().name(), plugin.getMetadata().version());
    }

    /**
     * Unregister a plugin
     */
    public void unregister(String pluginId) {
        LoadedPlugin plugin = plugins.remove(pluginId);
        if (plugin != null) {
            LOG.info("Unregistered plugin: {}", pluginId);
        }
    }

    /**
     * Get a plugin by ID
     */
    public Optional<LoadedPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    /**
     * Get all loaded plugins
     */
    public Map<String, LoadedPlugin> getAllPlugins() {
        return Map.copyOf(plugins);
    }

    /**
     * Check if a plugin is registered
     */
    public boolean isRegistered(String pluginId) {
        return plugins.containsKey(pluginId);
    }

    /**
     * Get the number of loaded plugins
     */
    public int getPluginCount() {
        return plugins.size();
    }

    /**
     * Loaded plugin information
     */
    public static class LoadedPlugin {
        private final GamelanPlugin plugin;
        private final PluginMetadata metadata;
        private final PluginClassLoader classLoader;
        private PluginState state;

        public LoadedPlugin(GamelanPlugin plugin, PluginMetadata metadata,
                PluginClassLoader classLoader) {
            this.plugin = plugin;
            this.metadata = metadata;
            this.classLoader = classLoader;
            this.state = PluginState.LOADED;
        }

        public GamelanPlugin getPlugin() {
            return plugin;
        }

        public PluginMetadata getMetadata() {
            return metadata;
        }

        public PluginClassLoader getClassLoader() {
            return classLoader;
        }

        public PluginState getState() {
            return state;
        }

        public void setState(PluginState state) {
            this.state = state;
        }
    }

    /**
     * Plugin lifecycle state
     */
    public enum PluginState {
        LOADED,
        INITIALIZED,
        STARTED,
        STOPPED,
        FAILED
    }
}
