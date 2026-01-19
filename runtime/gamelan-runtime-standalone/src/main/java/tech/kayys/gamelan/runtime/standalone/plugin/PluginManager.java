package tech.kayys.gamelan.runtime.standalone.plugin;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Manages plugin loading, unloading, and lifecycle
 */
@ApplicationScoped
public class PluginManager {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManager.class);

    @Inject
    @ConfigProperty(name = "gamelan.plugins.directory", defaultValue = "./plugins")
    String pluginsDirectory;

    @Inject
    @ConfigProperty(name = "gamelan.plugins.auto-discover", defaultValue = "true")
    boolean autoDiscoverPlugins;

    private final Map<String, PluginInfo> loadedPlugins = new HashMap<>();
    private final List<ClassLoader> pluginClassLoaders = new ArrayList<>();

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing Plugin Manager with directory: {}", pluginsDirectory);
        
        // Create plugins directory if it doesn't exist
        Path pluginDir = Paths.get(pluginsDirectory);
        if (!Files.exists(pluginDir)) {
            try {
                Files.createDirectories(pluginDir);
                LOG.info("Created plugins directory: {}", pluginDir.toAbsolutePath());
            } catch (IOException e) {
                LOG.error("Failed to create plugins directory: {}", e.getMessage());
            }
        }

        if (autoDiscoverPlugins) {
            scanAndLoadPlugins();
        }
    }

    /**
     * Scans the plugins directory and loads all available plugins
     */
    public void scanAndLoadPlugins() {
        LOG.info("Scanning for plugins in directory: {}", pluginsDirectory);
        
        try {
            Files.walk(Paths.get(pluginsDirectory))
                    .filter(path -> path.toString().endsWith(".jar"))
                    .forEach(this::loadPlugin);
        } catch (IOException e) {
            LOG.error("Error scanning plugins directory: {}", e.getMessage());
        }
    }

    /**
     * Loads a plugin from the specified JAR file
     */
    public synchronized boolean loadPlugin(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        LOG.info("Loading plugin: {}", fileName);

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            // Check if plugin is already loaded
            if (loadedPlugins.containsKey(fileName)) {
                LOG.warn("Plugin {} is already loaded", fileName);
                return false;
            }

            // Extract plugin metadata from manifest
            String pluginName = jarFile.getManifest().getMainAttributes().getValue("Plugin-Name");
            String pluginVersion = jarFile.getManifest().getMainAttributes().getValue("Plugin-Version");
            String pluginClass = jarFile.getManifest().getMainAttributes().getValue("Plugin-Class");

            if (pluginName == null || pluginClass == null) {
                LOG.error("Plugin {} is missing required manifest attributes", fileName);
                return false;
            }

            // Create class loader for the plugin
            URL jarUrl = jarPath.toUri().toURL();
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, 
                    Thread.currentThread().getContextClassLoader());
            
            // Load the plugin class
            Class<?> pluginClazz = classLoader.loadClass(pluginClass);
            
            // Store plugin info
            PluginInfo pluginInfo = new PluginInfo(
                    pluginName,
                    pluginVersion != null ? pluginVersion : "unknown",
                    fileName,
                    jarPath.toAbsolutePath().toString(),
                    pluginClazz,
                    classLoader,
                    true // enabled by default
            );

            loadedPlugins.put(fileName, pluginInfo);
            pluginClassLoaders.add(classLoader);

            LOG.info("Successfully loaded plugin: {} ({})", pluginName, fileName);
            return true;

        } catch (Exception e) {
            LOG.error("Failed to load plugin {}: {}", fileName, e.getMessage());
            return false;
        }
    }

    /**
     * Unloads a plugin by name
     */
    public synchronized boolean unloadPlugin(String pluginFileName) {
        PluginInfo pluginInfo = loadedPlugins.get(pluginFileName);
        if (pluginInfo == null) {
            LOG.warn("Plugin {} is not loaded", pluginFileName);
            return false;
        }

        try {
            // Remove from loaded plugins
            loadedPlugins.remove(pluginFileName);
            
            // Remove class loader
            pluginClassLoaders.remove(pluginInfo.getClassLoader());
            
            // Attempt to close the class loader (Java 9+ feature)
            if (pluginInfo.getClassLoader() instanceof URLClassLoader) {
                try {
                    ((URLClassLoader) pluginInfo.getClassLoader()).close();
                } catch (IOException e) {
                    LOG.warn("Could not close class loader for plugin {}: {}", 
                            pluginFileName, e.getMessage());
                }
            }

            LOG.info("Successfully unloaded plugin: {}", pluginInfo.getName());
            return true;

        } catch (Exception e) {
            LOG.error("Failed to unload plugin {}: {}", pluginFileName, e.getMessage());
            return false;
        }
    }

    /**
     * Enables a plugin
     */
    public boolean enablePlugin(String pluginFileName) {
        PluginInfo pluginInfo = loadedPlugins.get(pluginFileName);
        if (pluginInfo == null) {
            LOG.warn("Cannot enable plugin {}: plugin not loaded", pluginFileName);
            return false;
        }

        pluginInfo.setEnabled(true);
        LOG.info("Enabled plugin: {}", pluginInfo.getName());
        return true;
    }

    /**
     * Disables a plugin
     */
    public boolean disablePlugin(String pluginFileName) {
        PluginInfo pluginInfo = loadedPlugins.get(pluginFileName);
        if (pluginInfo == null) {
            LOG.warn("Cannot disable plugin {}: plugin not loaded", pluginFileName);
            return false;
        }

        pluginInfo.setEnabled(false);
        LOG.info("Disabled plugin: {}", pluginInfo.getName());
        return true;
    }

    /**
     * Gets information about all loaded plugins
     */
    public List<PluginInfo> getAllPlugins() {
        return new ArrayList<>(loadedPlugins.values());
    }

    /**
     * Gets information about a specific plugin
     */
    public PluginInfo getPlugin(String pluginFileName) {
        return loadedPlugins.get(pluginFileName);
    }

    /**
     * Gets the plugins directory path
     */
    public String getPluginsDirectory() {
        return pluginsDirectory;
    }

    /**
     * Refreshes the plugin list by rescanning the directory
     */
    public void refreshPlugins() {
        LOG.info("Refreshing plugins...");
        scanAndLoadPlugins();
    }
}