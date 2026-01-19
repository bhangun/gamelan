package tech.kayys.gamelan.engine.plugin;

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
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.plugin.impl.DefaultPluginContext;

/**
 * Central plugin manager for loading, managing, and unloading plugins
 */
@ApplicationScoped
public class PluginManager {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManager.class);

    private final PluginRegistry registry = new PluginRegistry();
    private final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();

    @Inject
    ServiceRegistry serviceRegistry;

    @Inject
    EventBus eventBus;

    @Inject
    EventPublisher eventPublisher;

    @Inject
    ExtensionRegistry extensionRegistry;

    private String pluginDirectory = "/opt/gamelan/plugins";
    private String dataDirectory = "/opt/gamelan/plugin-data";

    /**
     * Load a plugin from a JAR file
     */
    public Uni<GamelanPlugin> loadPlugin(Path pluginJar) {
        return Uni.createFrom().item(() -> {
            try {
                LOG.info("Loading plugin from: {}", pluginJar);

                // Create plugin classloader
                PluginClassLoader classLoader = new PluginClassLoader(pluginJar, getClass().getClassLoader());

                // Use ServiceLoader to discover plugin
                ServiceLoader<GamelanPlugin> loader = ServiceLoader.load(GamelanPlugin.class, classLoader);
                Optional<GamelanPlugin> pluginOpt = loader.findFirst();

                if (pluginOpt.isEmpty()) {
                    throw new RuntimeException("No plugin found in JAR: " + pluginJar);
                }

                GamelanPlugin plugin = pluginOpt.get();
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
                        eventPublisher,
                        extensionRegistry,
                        pluginDataDir);

                // Initialize plugin
                plugin.initialize(context);

                // Register plugin
                PluginRegistry.LoadedPlugin loadedPlugin = new PluginRegistry.LoadedPlugin(
                        plugin, metadata, classLoader);
                loadedPlugin.setState(PluginRegistry.PluginState.INITIALIZED);
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
    public Uni<Void> registerPlugin(GamelanPlugin plugin) {
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
                        eventPublisher,
                        extensionRegistry,
                        pluginDataDir);

                // Initialize plugin
                plugin.initialize(context);

                // Register plugin
                PluginRegistry.LoadedPlugin loadedPlugin = new PluginRegistry.LoadedPlugin(
                        plugin, metadata, null); // No dedicated classloader for programmatic plugins
                loadedPlugin.setState(PluginRegistry.PluginState.INITIALIZED);
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
                Optional<PluginRegistry.LoadedPlugin> loadedOpt = registry
                        .getPlugin(pluginId);
                if (loadedOpt.isEmpty()) {
                    throw new RuntimeException("Plugin not found: " + pluginId);
                }

                PluginRegistry.LoadedPlugin loaded = loadedOpt.get();
                loaded.getPlugin().start();
                loaded.setState(PluginRegistry.PluginState.STARTED);
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
                Optional<PluginRegistry.LoadedPlugin> loadedOpt = registry
                        .getPlugin(pluginId);
                if (loadedOpt.isEmpty()) {
                    throw new RuntimeException("Plugin not found: " + pluginId);
                }

                PluginRegistry.LoadedPlugin loaded = loadedOpt.get();
                loaded.getPlugin().stop();
                loaded.setState(PluginRegistry.PluginState.STOPPED);
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
    public Uni<GamelanPlugin> reloadPlugin(String pluginId, Path pluginJar) {
        return unloadPlugin(pluginId)
                .chain(() -> loadPlugin(pluginJar))
                .chain(plugin -> startPlugin(pluginId).replaceWith(plugin));
    }

    /**
     * Get a plugin by ID
     */
    public Optional<GamelanPlugin> getPlugin(String pluginId) {
        return registry.getPlugin(pluginId).map(PluginRegistry.LoadedPlugin::getPlugin);
    }

    /**
     * Get all loaded plugins
     */
    public List<GamelanPlugin> getAllPlugins() {
        return registry.getAllPlugins().values().stream()
                .map(PluginRegistry.LoadedPlugin::getPlugin)
                .toList();
    }

    /**
     * Get plugins by type
     */
    @SuppressWarnings("unchecked")
    public <T extends GamelanPlugin> List<T> getPluginsByType(Class<T> pluginType) {
        return registry.getAllPlugins().values().stream()
                .map(PluginRegistry.LoadedPlugin::getPlugin)
                .filter(pluginType::isInstance)
                .map(p -> (T) p)
                .toList();
    }

    /**
     * Discover and load all plugins from the plugin directory and classpath
     */
    public Uni<List<GamelanPlugin>> discoverAndLoadPlugins() {
        return Uni.createFrom().item(() -> {
            List<GamelanPlugin> loadedPlugins = new ArrayList<>();

            // 1. Load from classpath using ServiceLoader
            LOG.info("Discovering plugins from classpath...");
            ServiceLoader<GamelanPlugin> loader = ServiceLoader.load(GamelanPlugin.class);
            for (GamelanPlugin plugin : loader) {
                try {
                    if (!registry.isRegistered(plugin.getMetadata().id())) {
                        LOG.info("Discovered classpath plugin: {}", plugin.getMetadata().id());
                        registerPlugin(plugin).await().indefinitely();
                        startPlugin(plugin.getMetadata().id()).await().indefinitely();
                        loadedPlugins.add(plugin);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to load classpath plugin: {}", plugin.getClass().getName(), e);
                }
            }

            // 2. Load from plugin directory
            Path pluginDir = Paths.get(pluginDirectory);
            if (Files.exists(pluginDir)) {
                LOG.info("Scanning plugin directory: {}", pluginDirectory);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir, "*.jar")) {
                    for (Path jarFile : stream) {
                        try {
                            GamelanPlugin plugin = loadPlugin(jarFile).await().indefinitely();
                            startPlugin(plugin.getMetadata().id()).await().indefinitely();
                            loadedPlugins.add(plugin);
                        } catch (Exception e) {
                            LOG.error("Failed to load plugin from: {}", jarFile, e);
                        }
                    }
                } catch (IOException e) {
                    LOG.error("Failed to scan plugin directory", e);
                }
            }

            LOG.info("Total plugins discovered and loaded: {}", loadedPlugins.size());
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
