package tech.kayys.gamelan.engine.plugin;

import java.util.List;

/**
 * Strategy interface for loading plugins
 */
public interface PluginLoader {

    /**
     * Load plugins from a source
     *
     * @return list of loaded plugins
     */
    List<PluginRegistry.LoadedPlugin> loadPlugins();
    
}
