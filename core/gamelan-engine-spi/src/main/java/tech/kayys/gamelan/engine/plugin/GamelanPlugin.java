package tech.kayys.gamelan.engine.plugin;

/**
 * Base interface for all Gamelan plugins
 * 
 * Plugins must implement this interface and provide a no-arg constructor.
 * The plugin lifecycle is:
 * 1. Constructor called
 * 2. initialize(PluginContext) called
 * 3. start() called
 * 4. Plugin is active
 * 5. stop() called
 * 6. Plugin is unloaded
 */
public interface GamelanPlugin {

    /**
     * Initialize the plugin with the provided context
     * 
     * This method is called once after the plugin is loaded.
     * Use this to set up any resources needed by the plugin.
     * 
     * @param context the plugin context
     * @throws PluginException if initialization fails
     */
    void initialize(PluginContext context) throws PluginException;

    /**
     * Start the plugin
     * 
     * This method is called after initialization.
     * The plugin should start any background tasks or services here.
     * 
     * @throws PluginException if start fails
     */
    void start() throws PluginException;

    /**
     * Stop the plugin
     * 
     * This method is called when the plugin is being unloaded.
     * The plugin should clean up any resources and stop any background tasks.
     * 
     * @throws PluginException if stop fails
     */
    void stop() throws PluginException;

    /**
     * Get the plugin metadata
     * 
     * @return the plugin metadata
     */
    PluginMetadata getMetadata();
}
