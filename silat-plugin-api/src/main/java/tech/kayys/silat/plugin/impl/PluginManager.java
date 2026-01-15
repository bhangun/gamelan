package tech.kayys.silat.plugin.impl;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.plugin.Plugin;
import tech.kayys.silat.plugin.PluginContext;
import tech.kayys.silat.plugin.PluginException;
import tech.kayys.silat.plugin.PluginMetadata;

/**
 * Central plugin manager for loading, managing, and unloading plugins
 */
@ApplicationScoped
public class PluginManager {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManager.class);

    private final tech.kayys.silat.plugin.impl.PluginRegistry registry = new PluginRegistry();
    private final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();

    @Inject
    tech.kayys.silat.plugin.ServiceRegistry serviceRegistry;

    @Inject
    tech.kayys.silat.plugin.EventBus eventBus;

    private String pluginDirectory = "/opt/silat/plugins";
    private String dataDirectory = "/opt/silat/plugin-data";

    /**
     * Load a plugin from a JAR file
     */
    public Uni<Plugin> loadPlugin(Path pluginJar) {
        return Uni.createFrom().item(() -> {
            try {
                LOG.info("Loading plugin from: {}", pluginJar);

                // Create plugin classloader
                PluginClassLoader classLoader = new PluginClassLoader(pluginJar, getClass().getClassLoader());

                // Use ServiceLoader to discover plugin
                ServiceLoader<Plugin> loader = ServiceLoader.load(Plugin.class, classLoader);
                Optional<Plugin> pluginOpt = loader.findFirst();

                if (pluginOpt.isEmpty()) {
                    throw new RuntimeException("No plugin found in JAR: " + pluginJar);
                }

                Plugin plugin = pluginOpt.get();
                PluginMetadata metadata = plugin.getMetadata();

                // Check if already loaded
                if (registry.isRegistered(metadata.id())) {
                    throw new RuntimeException("Plugin already loaded: " + metadata.id());
                }

                // Create plugin context
                String pluginDataDir = dataDirectory + "/" + metadata.id();
                createDirectoryIfNotExists(Paths.get(pluginDataDir));

                PluginContext context = new DefaultPluginContext(
                        metadata,
                        LoggerFactory.getLogger("plugin." + metadata.id()),
                        metadata.properties(),
                        serviceRegistry,
                        eventBus,
                        pluginDataDir);

                // Initialize plugin
                plugin.initialize(context);

                // Register plugin
                tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin loadedPlugin = new tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin(
                        plugin, metadata, classLoader);
                loadedPlugin.setState(tech.kayys.silat.plugin.impl.PluginRegistry.PluginState.INITIALIZED);
                registry.register(loadedPlugin);
                classLoaders.put(metadata.id(), classLoader);

                LOG.info("Plugin loaded successfully: {} v{}", metadata.name(), metadata.version());
                return plugin;

            } catch (PluginException e) {
                LOG.error("Failed to initialize plugin", e);
                throw new RuntimeException("Failed to initialize plugin: " + e.getMessage(), e);
            } catch (Exception e) {
                LOG.error("Failed to load plugin from: {}", pluginJar, e);
                throw new RuntimeException("Failed to load plugin: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Register a plugin instance directly (programmatic registration)
     */
    public Uni<Void> registerPlugin(Plugin plugin) {
        return Uni.createFrom().item(() -> {
            try {
                PluginMetadata metadata = plugin.getMetadata();
                LOG.info("Registering plugin: {} v{}", metadata.name(), metadata.version());

                if (registry.isRegistered(metadata.id())) {
                    throw new RuntimeException("Plugin already registered: " + metadata.id());
                }

                // Create plugin context
                String pluginDataDir = dataDirectory + "/" + metadata.id();
                createDirectoryIfNotExists(Paths.get(pluginDataDir));

                PluginContext context = new DefaultPluginContext(
                        metadata,
                        LoggerFactory.getLogger("plugin." + metadata.id()),
                        metadata.properties(),
                        serviceRegistry,
                        eventBus,
                        pluginDataDir);

                // Initialize plugin
                plugin.initialize(context);

                // Register plugin
                tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin loadedPlugin = new tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin(
                        plugin, metadata, null); // No dedicated classloader for programmatic plugins
                loadedPlugin.setState(tech.kayys.silat.plugin.impl.PluginRegistry.PluginState.INITIALIZED);
                registry.register(loadedPlugin);

                return null;
            } catch (PluginException e) {
                LOG.error("Failed to initialize registered plugin", e);
                throw new RuntimeException("Failed to initialize registered plugin: " + e.getMessage(), e);
            } catch (Exception e) {
                LOG.error("Failed to register plugin", e);
                throw new RuntimeException("Failed to register plugin: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Start a plugin
     */
    public Uni<Void> startPlugin(String pluginId) {
        return Uni.createFrom().item(() -> {
            try {
                Optional<tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin> loadedOpt = registry
                        .getPlugin(pluginId);
                if (loadedOpt.isEmpty()) {
                    throw new RuntimeException("Plugin not found: " + pluginId);
                }

                tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin loaded = loadedOpt.get();
                loaded.getPlugin().start();
                loaded.setState(tech.kayys.silat.plugin.impl.PluginRegistry.PluginState.STARTED);
                LOG.info("Plugin started: {}", pluginId);
                return null;
            } catch (PluginException e) {
                LOG.error("Failed to start plugin: {}", pluginId, e);
                throw new RuntimeException("Failed to start plugin: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Stop a plugin
     */
    public Uni<Void> stopPlugin(String pluginId) {
        return Uni.createFrom().item(() -> {
            try {
                Optional<tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin> loadedOpt = registry
                        .getPlugin(pluginId);
                if (loadedOpt.isEmpty()) {
                    throw new RuntimeException("Plugin not found: " + pluginId);
                }

                tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin loaded = loadedOpt.get();
                loaded.getPlugin().stop();
                loaded.setState(tech.kayys.silat.plugin.impl.PluginRegistry.PluginState.STOPPED);
                LOG.info("Plugin stopped: {}", pluginId);
                return null;
            } catch (PluginException e) {
                LOG.error("Failed to stop plugin: {}", pluginId, e);
                throw new RuntimeException("Failed to stop plugin: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Unload a plugin
     */
    public Uni<Void> unloadPlugin(String pluginId) {
        return stopPlugin(pluginId)
                .onFailure().recoverWithNull()
                .chain(() -> Uni.createFrom().item(() -> {
                    registry.unregister(pluginId);
                    PluginClassLoader classLoader = classLoaders.remove(pluginId);
                    if (classLoader != null) {
                        try {
                            classLoader.close();
                        } catch (IOException e) {
                            LOG.warn("Failed to close classloader for plugin: {}", pluginId, e);
                        }
                    }
                    LOG.info("Plugin unloaded: {}", pluginId);
                    return null;
                }));
    }

    /**
     * Reload a plugin (hot-reload)
     */
    public Uni<Plugin> reloadPlugin(String pluginId, Path pluginJar) {
        return unloadPlugin(pluginId)
                .chain(() -> loadPlugin(pluginJar))
                .chain(plugin -> startPlugin(pluginId).replaceWith(plugin));
    }

    /**
     * Get a plugin by ID
     */
    public Optional<Plugin> getPlugin(String pluginId) {
        return registry.getPlugin(pluginId).map(tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin::getPlugin);
    }

    /**
     * Get all loaded plugins
     */
    public List<Plugin> getAllPlugins() {
        return registry.getAllPlugins().values().stream()
                .map(tech.kayys.silat.plugin.impl.PluginRegistry.LoadedPlugin::getPlugin)
                .toList();
    }

    /**
     * Get plugins by type
     */
    @SuppressWarnings("unchecked")
    public <T extends Plugin> List<T> getPluginsByType(Class<T> pluginType) {
        return registry.getAllPlugins().values().stream()
                .map(PluginRegistry.LoadedPlugin::getPlugin)
                .filter(pluginType::isInstance)
                .map(p -> (T) p)
                .toList();
    }

    /**
     * Discover and load all plugins from the plugin directory
     */
    public Uni<List<Plugin>> discoverAndLoadPlugins() {
        return Uni.createFrom().item(() -> {
            Path pluginDir = Paths.get(pluginDirectory);
            if (!Files.exists(pluginDir)) {
                LOG.warn("Plugin directory does not exist: {}", pluginDirectory);
                return List.of();
            }

            List<Plugin> loadedPlugins = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir, "*.jar")) {
                for (Path jarFile : stream) {
                    try {
                        Plugin plugin = loadPlugin(jarFile).await().indefinitely();
                        startPlugin(plugin.getMetadata().id()).await().indefinitely();
                        loadedPlugins.add(plugin);
                    } catch (Exception e) {
                        LOG.error("Failed to load plugin from: {}", jarFile, e);
                    }
                }
            } catch (IOException e) {
                LOG.error("Failed to scan plugin directory", e);
            }

            LOG.info("Discovered and loaded {} plugins", loadedPlugins.size());
            return loadedPlugins;
        });
    }

    /**
     * Set the plugin directory
     */
    public void setPluginDirectory(String pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
    }

    /**
     * Set the data directory
     */
    public void setDataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }


    /**
     * Get the plugin registry (for internal use)
     */
    public PluginRegistry getRegistry() {
        return registry;
    }

    private void createDirectoryIfNotExists(Path dir) {
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            LOG.warn("Failed to create directory: {}", dir, e);
        }
    }
}
